package com.watchjobs.ingestion.connector

import org.jsoup.Connection
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

// robots.txt: grupo do nosso robô (ou "*"), regra mais específica vence e Allow ganha no empate.
class RobotsTxt private constructor(private val rules: List<Pair<Boolean, String>>, val crawlDelaySeconds: Long?) {
    fun allows(path: String): Boolean {
        val target = path.ifEmpty { "/" }
        val match = rules.filter { (_, pattern) -> pattern.isNotEmpty() && matches(pattern, target) }
            .maxWithOrNull(compareBy<Pair<Boolean, String>> { it.second.length }.thenBy { it.first })
        return match?.first ?: true
    }

    private fun matches(pattern: String, path: String): Boolean {
        val anchored = pattern.endsWith("$")
        val regex = pattern.removeSuffix("$").split("*").joinToString(".*") { Regex.escape(it) }
        return Regex("^$regex${if (anchored) "$" else ""}").containsMatchIn(path)
    }

    companion object {
        val ALLOW_ALL = RobotsTxt(emptyList(), null)

        fun parse(text: String, userAgent: String): RobotsTxt {
            val agent = userAgent.substringBefore('/').lowercase()
            data class Group(val agents: MutableList<String> = mutableListOf(), val rules: MutableList<Pair<Boolean, String>> = mutableListOf(), var delay: Long? = null)
            val groups = mutableListOf<Group>()
            var current: Group? = null
            var lastWasAgent = false
            text.lineSequence().map { it.substringBefore('#').trim() }.filter { it.contains(':') }.forEach { line ->
                val field = line.substringBefore(':').trim().lowercase()
                val value = line.substringAfter(':').trim()
                when (field) {
                    "user-agent" -> {
                        if (current == null || !lastWasAgent) current = Group().also(groups::add)
                        current!!.agents += value.lowercase()
                        lastWasAgent = true
                    }
                    "allow", "disallow" -> {
                        current?.rules?.add((field == "allow") to value)
                        lastWasAgent = false
                    }
                    "crawl-delay" -> {
                        current?.delay = value.toDoubleOrNull()?.toLong()
                        lastWasAgent = false
                    }
                    else -> lastWasAgent = false
                }
            }
            val chosen = groups.firstOrNull { g -> g.agents.any { it != "*" && agent.contains(it) } }
                ?: groups.firstOrNull { g -> "*" in g.agents }
                ?: return ALLOW_ALL
            return RobotsTxt(chosen.rules, chosen.delay)
        }
    }
}

// Vagas em JSON-LD (schema.org JobPosting): o padrão que as páginas de carreira publicam para buscadores.
class JobPostingJsonLd(private val mapper: ObjectMapper) {
    fun extract(document: Document, pageUrl: String, organizationName: String?): List<RawPosting> =
        document.select("script[type=application/ld+json]").flatMap { script ->
            val tree = runCatching { mapper.readValue(script.data(), Any::class.java) }.getOrNull() ?: return@flatMap emptyList()
            postings(tree).mapNotNull { toRaw(it, pageUrl, organizationName) }
        }

    @Suppress("UNCHECKED_CAST")
    private fun postings(node: Any?): List<Map<String, Any?>> = when (node) {
        is List<*> -> node.flatMap { postings(it) }
        is Map<*, *> -> {
            val map = node as Map<String, Any?>
            val type = map["@type"]
            val isPosting = type == "JobPosting" || (type is List<*> && "JobPosting" in type)
            (if (isPosting) listOf(map) else emptyList()) + postings(map["@graph"])
        }
        else -> emptyList()
    }

    @Suppress("UNCHECKED_CAST")
    private fun toRaw(map: Map<String, Any?>, pageUrl: String, organizationName: String?): RawPosting? {
        val title = map["title"] as? String ?: return null
        val url = (map["url"] as? String)?.let { resolve(pageUrl, it) } ?: pageUrl
        val identifier = when (val id = map["identifier"]) {
            is Map<*, *> -> id["value"]?.toString()
            null -> null
            else -> id.toString()
        }
        val org = (map["hiringOrganization"] as? Map<String, Any?>)?.get("name") as? String
        val location = firstMap(map["jobLocation"])?.let { firstMap(it["address"]) }
        val locationText = location?.let {
            listOfNotNull(it["addressLocality"] as? String, it["addressRegion"] as? String, countryOf(it["addressCountry"])).joinToString(", ")
        }?.ifBlank { null }
        val salary = firstMap(map["baseSalary"])
        val value = firstMap(salary?.get("value"))
        return RawPosting(
            externalId = identifier ?: url,
            title = title,
            url = url,
            organizationName = organizationName ?: org,
            locationText = locationText,
            description = map["description"] as? String,
            postedAt = (map["datePosted"] as? String)?.let(::parseDate),
            salaryMin = (value?.get("minValue") ?: value?.get("value")).toDoubleOrNull(),
            salaryMax = value?.get("maxValue").toDoubleOrNull(),
            salaryCurrency = salary?.get("currency") as? String,
            salaryPeriod = (value?.get("unitText") as? String)?.uppercase(),
            workMode = if ((map["jobLocationType"] as? String)?.uppercase() == "TELECOMMUTE") "REMOTE" else null,
            jobType = StructuredHints.jobType(map["employmentType"]?.let { if (it is List<*>) it.firstOrNull() else it }?.toString())
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun firstMap(node: Any?): Map<String, Any?>? = when (node) {
        is Map<*, *> -> node as Map<String, Any?>
        is List<*> -> node.firstOrNull() as? Map<String, Any?>
        else -> null
    }

    private fun countryOf(node: Any?): String? = when (node) {
        is String -> node
        is Map<*, *> -> node["name"] as? String
        else -> null
    }

    private fun Any?.toDoubleOrNull(): Double? = when (this) {
        is Number -> toDouble()
        is String -> replace(",", ".").toDoubleOrNull()
        else -> null
    }

    private fun parseDate(value: String): OffsetDateTime? =
        runCatching { OffsetDateTime.parse(value) }.getOrNull()
            ?: runCatching { LocalDate.parse(value.take(10)).atStartOfDay().atOffset(ZoneOffset.UTC) }.getOrNull()

    private fun resolve(base: String, href: String): String? = runCatching { URI(base).resolve(href.trim()).toString() }.getOrNull()
}

// Scraping de páginas de carreira com jsoup (SCRAPING). Só quando não há API: respeita o robots.txt,
// pausa entre acessos (Crawl-delay ou minDelayMs), tempo limite, identifica o robô e lê só o JSON-LD.
// Lê a página de carreiras e as páginas de vaga ligadas a ela no mesmo domínio (até maxPages).
@Component
class CareerPageConnector(
    private val mapper: ObjectMapper,
    @Value("\${app.ingestion.user-agent:WatchJobsBot/1.0}") private val userAgent: String,
    @Value("\${app.ingestion.http-timeout-seconds:5}") private val timeoutSeconds: Int
) : JobSourceConnector {
    override val code = "CAREER_PAGES"
    private val robotsCache = ConcurrentHashMap<String, RobotsTxt>()
    private val lastAccess = ConcurrentHashMap<String, Long>()
    private val jsonLd = JobPostingJsonLd(mapper)

    // Gancho para testes: busca a página (ou o robots.txt) e devolve o corpo.
    internal var fetcher: (String) -> String = { url -> connect(url).execute().body() }

    override fun fetch(source: SourceContext, target: FetchTarget): FetchResult {
        val careersUrl = target.careersUrl ?: throw ConnectorException("Empresa sem URL de carreiras")
        val maxPages = source.configLong("maxPages", 20).toInt()
        val minDelay = source.configLong("minDelayMs", 5000)
        val linkSelector = source.configString("jobLinkSelector") ?: DEFAULT_LINK_SELECTOR
        val start = URI(careersUrl)
        val host = start.host ?: throw ConnectorException("URL de carreiras inválida")

        val listing = page(careersUrl, minDelay) ?: return FetchResult(emptyList(), fullSnapshot = false)
        val found = jsonLd.extract(listing, careersUrl, target.organizationName).toMutableList()
        val links = listing.select(linkSelector).map { it.absUrl("href") }
            .filter { link -> link.isNotBlank() && runCatching { URI(link).host }.getOrNull()?.removePrefix("www.") == host.removePrefix("www.") }
            .map { it.substringBefore('#') }.distinct().filter { it != careersUrl }.take(maxPages)
        links.forEach { link ->
            page(link, minDelay)?.let { found += jsonLd.extract(it, link, target.organizationName) }
        }
        return FetchResult(found.distinctBy { it.externalId }, fullSnapshot = false)
    }

    // null quando o robots.txt não permite a página.
    private fun page(url: String, minDelayMs: Long): Document? {
        val uri = URI(url)
        val origin = "${uri.scheme}://${uri.authority}"
        val robots = robotsCache.getOrPut(origin) {
            runCatching { RobotsTxt.parse(fetcher("$origin/robots.txt"), userAgent) }.getOrElse { ex ->
                // Sem robots.txt (404) = tudo liberado; erro de rede = melhor não acessar agora.
                if (ex is HttpStatusException && ex.statusCode in 400..499) RobotsTxt.ALLOW_ALL else throw httpError("robots.txt de ${uri.host}", ex as? Exception ?: RuntimeException(ex))
            }
        }
        if (!robots.allows(uri.rawPath.orEmpty() + (uri.rawQuery?.let { "?$it" } ?: ""))) return null
        pause(origin, maxOf(minDelayMs, (robots.crawlDelaySeconds ?: 0) * 1000))
        val html = try {
            fetcher(url)
        } catch (ex: HttpStatusException) {
            throw ConnectorException("Página de carreiras respondeu HTTP ${ex.statusCode}")
        } catch (ex: Exception) {
            throw ConnectorException("Página de carreiras indisponível: ${ex.javaClass.simpleName}")
        }
        return Jsoup.parse(html, url)
    }

    private fun pause(origin: String, delayMs: Long) {
        synchronized(lastAccess) {
            val wait = (lastAccess[origin] ?: 0L) + delayMs - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(wait)
            lastAccess[origin] = System.currentTimeMillis()
        }
    }

    private fun connect(url: String): Connection =
        Jsoup.connect(url)
            .userAgent(userAgent)
            .timeout(timeoutSeconds * 1000)
            .followRedirects(true)
            .maxBodySize(5 * 1024 * 1024)
            .ignoreContentType(url.endsWith("robots.txt"))

    companion object {
        const val DEFAULT_LINK_SELECTOR = "a[href*=job], a[href*=vaga], a[href*=career], a[href*=position], a[href*=opening]"
    }
}
