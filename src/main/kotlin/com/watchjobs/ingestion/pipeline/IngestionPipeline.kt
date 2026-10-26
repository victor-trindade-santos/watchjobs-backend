package com.watchjobs.ingestion.pipeline

import com.watchjobs.classification.JobClassifier
import com.watchjobs.ingestion.connector.FetchTarget
import com.watchjobs.ingestion.connector.JobSourceConnector
import com.watchjobs.ingestion.connector.SourceContext
import com.watchjobs.ingestion.normalize.JobNormalizer
import com.watchjobs.ingestion.normalize.NormalizeContext
import com.watchjobs.ingestion.normalize.SkillExtractor
import com.watchjobs.ingestion.store.CatalogStore
import com.watchjobs.ingestion.store.IngestionRunStore
import com.watchjobs.ingestion.store.RunCounters
import com.watchjobs.ingestion.store.RunStatus
import com.watchjobs.ingestion.store.SourceRow
import com.watchjobs.ingestion.store.SourceTargetStore
import com.watchjobs.ingestion.store.TriggerType
import com.watchjobs.sources.SourceType
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant

data class RoundOptions(
    val sourceCode: String? = null,
    val companyKey: String? = null,
    val force: Boolean = false,
    val maintenance: Boolean = true
) {
    val trigger: TriggerType
        get() = if (sourceCode != null || companyKey != null || force) TriggerType.MANUAL else TriggerType.SCHEDULED

    companion object {
        // Argumentos do executor: --source=GREENHOUSE --company=vtex --force --no-maintenance
        fun parse(args: Array<out String>): RoundOptions {
            fun value(name: String) = args.firstOrNull { it.startsWith("--$name=") }?.substringAfter("=")?.trim()?.ifEmpty { null }
            return RoundOptions(
                sourceCode = value("source")?.uppercase(),
                companyKey = value("company"),
                force = args.any { it == "--force" },
                maintenance = args.none { it == "--no-maintenance" }
            )
        }
    }
}

data class TargetResult(val source: String, val target: String, val status: RunStatus, val counters: RunCounters, val error: String?)

data class RoundSummary(
    val targets: List<TargetResult>,
    val sourcesAttempted: Int,
    val sourcesFailed: Int,
    val classifiedJobs: Int,
    val withoutRoleFamily: Int,
    val withoutLevel: Int,
    val budgetExceeded: Boolean
) {
    val totals: RunCounters
        get() = RunCounters().also { total -> targets.forEach { total += it.counters } }

    // A rodada só termina com erro se todas as fontes tentadas falharem.
    val allFailed: Boolean
        get() = sourcesAttempted > 0 && sourcesFailed == sourcesAttempted

    fun percent(part: Int): String = if (classifiedJobs == 0) "0,0" else "%.1f".format(part * 100.0 / classifiedJobs).replace('.', ',')

    fun describe(): String {
        val t = totals
        return buildString {
            appendLine("Resumo da rodada: ${targets.size} alvos, $sourcesAttempted fontes ($sourcesFailed com falha)")
            appendLine("  vagas lidas ${t.fetched}, novas ${t.new}, alteradas ${t.updated}, iguais/expiradas ${t.deduplicated}, rejeitadas ${t.rejected}, fechadas ${t.closed}")
            appendLine("  cobertura das regras: ${percent(withoutRoleFamily)}% sem área e ${percent(withoutLevel)}% sem nível ($classifiedJobs vagas novas ou alteradas)")
            if (budgetExceeded) appendLine("  orçamento de tempo esgotado: alvos restantes ficam para a próxima rodada")
            targets.forEach {
                appendLine("  - ${it.source}/${it.target}: ${it.status} (lidas ${it.counters.fetched}, novas ${it.counters.new}, alteradas ${it.counters.updated}, fechadas ${it.counters.closed})" +
                    (it.error?.let { e -> " erro: $e" } ?: ""))
            }
        }.trimEnd()
    }
}

// Orquestrador dos passos 0 a 7 e 10 de 3.6.1. Cada alvo é isolado: um erro é registrado e a rodada segue.
@Service
class IngestionPipeline(
    connectors: List<JobSourceConnector>,
    private val targets: SourceTargetStore,
    private val runs: IngestionRunStore,
    private val catalog: CatalogStore,
    private val writer: JobWriter,
    private val classifier: JobClassifier,
    private val jdbc: JdbcClient,
    private val transactions: TransactionTemplate,
    private val environment: Environment,
    @Value("\${app.ingestion.time-budget-minutes:8}") private val timeBudgetMinutes: Long
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val connectorsByCode = connectors.associateBy { it.code }

    // Proteção contra fechamento em massa: retrato vazio de uma empresa que tinha 5 ou mais vagas.
    private val massCloseThreshold = 5

    fun runRound(options: RoundOptions = RoundOptions()): RoundSummary {
        val deadline = Instant.now().plus(Duration.ofMinutes(timeBudgetMinutes))
        jdbc.sql("SELECT discovery.ensure_partitions(14)").query().singleRow()

        val normalizer = JobNormalizer(classifier, SkillExtractor(catalog.skillAliases()))
        val results = mutableListOf<TargetResult>()
        val coverage = Coverage()
        var attempted = 0
        var failed = 0
        var budgetExceeded = false

        val sources = targets.activeSources().filter { options.sourceCode == null || it.code == options.sourceCode }
        if (sources.isEmpty()) log.warn("Nenhuma fonte ativa para coletar (confira is_active e terms_reviewed_at)")

        for (source in sources) {
            val connector = connectorsByCode[source.code]
            if (connector == null) {
                log.info("Fonte {} ativa, mas sem conector neste build; ignorada", source.code)
                continue
            }
            val sourceTargets = when (source.type) {
                SourceType.AGGREGATOR -> targets.segments(options.force)
                else -> targets.companies(source.id, options.companyKey, options.force)
            }
            if (sourceTargets.isEmpty()) {
                log.info("Fonte {}: nenhum alvo vencido", source.code)
                continue
            }
            val context = try {
                contextOf(source)
            } catch (ex: IllegalStateException) {
                log.error("Fonte {}: {}", source.code, ex.message)
                attempted++
                failed++
                targets.sourceFailed(source.id)
                continue
            }

            attempted++
            val sourceResults = mutableListOf<TargetResult>()
            for (target in sourceTargets) {
                if (Instant.now().isAfter(deadline)) {
                    budgetExceeded = true
                    break
                }
                val limit = source.rateLimitPerDay
                if (limit != null && runs.countToday(source.id) >= limit) {
                    log.warn("Fonte {}: cota diária de {} chamadas atingida; alvos restantes ficam para amanhã", source.code, limit)
                    break
                }
                val result = processTarget(source, context, connector, target, options.trigger, normalizer, coverage)
                sourceResults += result
            }
            results += sourceResults
            if (sourceResults.isNotEmpty() && sourceResults.all { it.status == RunStatus.FAILED }) {
                failed++
                targets.sourceFailed(source.id)
            } else if (sourceResults.isNotEmpty()) {
                targets.sourceSucceeded(source.id)
            }
            if (budgetExceeded) break
        }

        if (options.maintenance) maintenance()

        val summary = RoundSummary(results, attempted, failed, coverage.total, coverage.withoutRoleFamily, coverage.withoutLevel, budgetExceeded)
        log.info(summary.describe())
        return summary
    }

    fun processTarget(
        source: SourceRow,
        context: SourceContext,
        connector: JobSourceConnector,
        target: FetchTarget,
        trigger: TriggerType,
        normalizer: JobNormalizer,
        coverage: Coverage = Coverage()
    ): TargetResult {
        val counters = RunCounters()
        val runId = runs.open(source.id, target.sourceCompanyId, target.segmentId, trigger)
        try {
            val fetched = connector.fetch(context, target)
            counters.fetched = fetched.postings.size
            val normalizeContext = NormalizeContext(
                sourceId = source.id,
                sourceType = source.type,
                allowsStorage = source.allowsStorage,
                sourceCompanyId = target.sourceCompanyId,
                organizationName = target.organizationName,
                defaultCountry = target.countryCode ?: context.configString("country")?.uppercase()
            )
            val fetchedIds = mutableSetOf<String>()
            for (raw in fetched.postings) {
                fetchedIds += raw.externalId
                val job = normalizer.normalize(raw, normalizeContext)
                if (job == null) {
                    counters.rejected++
                    continue
                }
                val outcome = try {
                    transactions.execute { writer.write(job, target.organizationId, runId) }
                } catch (ex: Exception) {
                    log.warn("{}/{}: vaga {} rejeitada: {}", source.code, target.label, raw.externalId, ex.message)
                    null
                }
                when (outcome) {
                    WriteOutcome.NEW -> counters.new++
                    WriteOutcome.CHANGED -> counters.updated++
                    WriteOutcome.UNCHANGED, WriteOutcome.EXPIRED -> counters.deduplicated++
                    null -> counters.rejected++
                }
                if (outcome == WriteOutcome.NEW || outcome == WriteOutcome.CHANGED) coverage.add(job.roleFamily, job.experienceLevel)
            }

            var status = RunStatus.COMPLETED
            var error: String? = null
            val companyId = target.sourceCompanyId
            if (companyId != null && source.type.closesMissingJobs(fetched.fullSnapshot)) {
                if (fetched.postings.isEmpty() && (target.lastJobCount ?: 0) >= massCloseThreshold) {
                    status = RunStatus.PARTIAL
                    error = "Retrato vazio, mas a última coleta tinha ${target.lastJobCount} vagas: nada foi fechado"
                    log.warn("{}/{}: {}", source.code, target.label, error)
                } else {
                    counters.closed = transactions.execute { writer.closeMissing(companyId, fetchedIds) }
                }
            }
            runs.close(runId, status, counters, fetched.fullSnapshot, error)
            companyId?.let { targets.companySucceeded(it, if (status == RunStatus.COMPLETED && fetched.fullSnapshot) counters.fetched else null) }
            target.segmentId?.let { targets.segmentIngested(it) }
            return TargetResult(source.code, target.label, status, counters, error)
        } catch (ex: Exception) {
            val message = ex.message ?: ex.javaClass.simpleName
            log.error("{}/{}: coleta falhou: {}", source.code, target.label, message)
            runs.close(runId, RunStatus.FAILED, counters, false, message)
            target.sourceCompanyId?.let { targets.companyFailed(it, message) }
            return TargetResult(source.code, target.label, RunStatus.FAILED, counters, message)
        }
    }

    // Credenciais pelos nomes de credentials_ref (variáveis de ambiente). O segredo nunca vai ao banco.
    fun contextOf(source: SourceRow): SourceContext {
        val names = source.credentialsRef?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val credentials = names.associateWith { environment.getProperty(it).orEmpty() }
        val missing = credentials.filterValues { it.isBlank() }.keys
        check(missing.isEmpty()) { "variáveis de credencial ausentes: ${missing.joinToString()}" }
        return SourceContext(source.id, source.code, source.baseUrl, source.config, credentials)
    }

    // Passo 10: partições, limpeza dos 30 dias e dos códigos de autenticação vencidos.
    private fun maintenance() {
        try {
            jdbc.sql("SELECT discovery.run_daily_maintenance()").query().singleRow()
            jdbc.sql("SELECT core.purge_expired_auth()").query().singleRow()
        } catch (ex: Exception) {
            log.error("Manutenção diária falhou: {}", ex.message)
        }
    }

    class Coverage {
        var total = 0
        var withoutRoleFamily = 0
        var withoutLevel = 0

        fun add(roleFamily: String?, level: String?) {
            total++
            if (roleFamily == null) withoutRoleFamily++
            if (level == null) withoutLevel++
        }
    }
}
