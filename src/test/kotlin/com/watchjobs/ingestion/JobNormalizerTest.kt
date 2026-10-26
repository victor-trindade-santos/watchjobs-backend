package com.watchjobs.ingestion

import com.watchjobs.classification.ClassificationRule
import com.watchjobs.classification.MatchType
import com.watchjobs.classification.RuleSet
import com.watchjobs.classification.RuleTarget
import com.watchjobs.classification.RuleType
import com.watchjobs.ingestion.connector.AdzunaConnector
import com.watchjobs.ingestion.connector.GreenhouseConnector
import com.watchjobs.ingestion.connector.RawPosting
import com.watchjobs.ingestion.normalize.JobNormalizer
import com.watchjobs.ingestion.normalize.NormalizeContext
import com.watchjobs.ingestion.normalize.SkillExtractor
import com.watchjobs.sources.SourceType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JobNormalizerTest {
    private val normalizer = JobNormalizer(IngestionFixtures.classifier, SkillExtractor(IngestionFixtures.aliases))
    private val greenhouse = NormalizeContext(1, SourceType.ATS_PUBLIC, allowsStorage = false, sourceCompanyId = 10, organizationName = "VTEX")

    private fun greenhousePostings() =
        GreenhouseConnector({ _, _ -> "" }, IngestionFixtures.mapper)
            .parse(IngestionFixtures.read("greenhouse/vtex-jobs.json"), "vtex", "VTEX")

    @Test
    fun `greenhouse fixture becomes normalized corpus jobs`() {
        val jobs = greenhousePostings().map { requireNotNull(normalizer.normalize(it, greenhouse)) }

        val backend = jobs[0]
        assertEquals("BACKEND", backend.roleFamily)
        assertEquals("SENIOR", backend.experienceLevel)
        assertEquals("São Paulo", backend.city)
        assertEquals("SP", backend.state)
        assertEquals("BR", backend.countryCode)
        assertNull(backend.workMode)
        assertNull(backend.fullDescription)
        assertTrue(backend.descriptionSnippet!!.startsWith("We build commerce at scale."))
        assertEquals(
            mapOf("Java" to true, "Spring Boot" to true, "PostgreSQL" to true, "Kafka" to true, "Kubernetes" to false),
            backend.skills.associate { it.skillName to it.required }
        )
        assertEquals(64, backend.contentHash.length)
        assertEquals(IngestionFixtures.ruleSet.version, backend.rulesVersion)

        val frontend = jobs[1]
        assertEquals("FRONTEND", frontend.roleFamily)
        assertEquals(1.0, frontend.classificationConfidence)
        assertEquals("MID", frontend.experienceLevel)
        assertEquals("REMOTE", frontend.workMode)
        assertEquals("BR", frontend.countryCode)
        assertNull(frontend.city)
        assertTrue(frontend.skills.map { it.skillName }.containsAll(listOf("React", "TypeScript", "Next.js")))

        val data = jobs[2]
        assertEquals("DATA_ENGINEERING", data.roleFamily)
        assertEquals("SENIOR", data.experienceLevel)
        assertEquals("HYBRID", data.workMode)
        assertEquals("RJ", data.state)
        assertEquals(listOf("Python", "Spark", "Airflow", "dbt"), data.skills.map { it.skillName })

        val intern = jobs[3]
        assertEquals("MOBILE", intern.roleFamily)
        assertEquals("INTERN", intern.experienceLevel)
        assertEquals("INTERNSHIP", intern.jobType)
        assertEquals("SC", intern.state)
        assertEquals(mapOf("Flutter" to true, "Dart" to true, "Kotlin" to false), intern.skills.associate { it.skillName to it.required })
    }

    @Test
    fun `full description only when the source allows storage`() {
        val posting = greenhousePostings().first()
        val stored = normalizer.normalize(posting, greenhouse.copy(allowsStorage = true))!!
        assertTrue(stored.fullDescription!!.contains("Kubernetes"))
        val scraping = normalizer.normalize(posting, greenhouse.copy(sourceType = SourceType.SCRAPING, allowsStorage = true))!!
        assertNull(scraping.fullDescription)
    }

    @Test
    fun `aggregator jobs keep their own company and reject incomplete ones`() {
        val postings = AdzunaConnector({ _, _ -> "" }, IngestionFixtures.mapper)
            .parse(IngestionFixtures.read("adzuna/backend-br.json"), "BRL")
        val context = NormalizeContext(5, SourceType.AGGREGATOR, allowsStorage = false, defaultCountry = "BR")

        val first = normalizer.normalize(postings[0], context)!!
        assertEquals("Desenvolvedor Backend Pleno", first.title)
        assertEquals("Acme Tecnologia Ltda.", first.organizationName)
        assertEquals("BACKEND", first.roleFamily)
        assertEquals("MID", first.experienceLevel)
        assertEquals(9000.0, first.salaryMin)
        assertEquals("BRL", first.salaryCurrency)

        val second = normalizer.normalize(postings[1], context)!!
        assertEquals("REMOTE", second.workMode)
        assertEquals("BR", second.countryCode)
        // Cargos diferentes geram fingerprints diferentes, mesmo na mesma empresa.
        assertNotEquals(first.fingerprint, second.fingerprint)

        assertNull(normalizer.normalize(postings[2], context))
        assertNull(normalizer.normalize(RawPosting("x", "Dev", url = "javascript:alert(1)", organizationName = "Acme"), context))
    }

    @Test
    fun `content hash changes only when the job changes`() {
        val posting = greenhousePostings().first()
        val once = normalizer.normalize(posting, greenhouse)!!
        val again = normalizer.normalize(posting, greenhouse)!!
        val changed = normalizer.normalize(posting.copy(title = "Staff Backend Engineer (Java)"), greenhouse)!!
        assertEquals(once.contentHash, again.contentHash)
        assertNotEquals(once.contentHash, changed.contentHash)
        assertNotNull(once.fingerprint)
    }

    @Test
    fun `rule version and priority`() {
        val base = listOf(
            ClassificationRule(RuleType.ROLE_FAMILY, "backend", MatchType.WORD, "BACKEND", 30),
            ClassificationRule(RuleType.ROLE_FAMILY, "full stack", MatchType.PHRASE, "FULLSTACK", 10),
            ClassificationRule(RuleType.SENIORITY, "senior", MatchType.WORD, "SENIOR", 20),
            ClassificationRule(RuleType.SENIORITY, "tech lead", MatchType.PHRASE, "LEAD", 20)
        )
        val families = setOf("BACKEND", "FULLSTACK")
        val rules = RuleSet(base, families)

        assertEquals(12, rules.version.length)
        assertEquals(rules.version, RuleSet(base.reversed(), families).version)
        assertNotEquals(rules.version, RuleSet(base.map { if (it.pattern == "backend") it.copy(priority = 31) else it }, families).version)

        val hit = rules.bestMatch(RuleType.ROLE_FAMILY, "senior full stack backend developer", RuleTarget.JOB)!!
        assertEquals("FULLSTACK", hit.rule.targetValue)
        assertEquals(1.0, hit.confidence)
        assertEquals("LEAD", rules.bestMatch(RuleType.SENIORITY, "senior tech lead", RuleTarget.JOB)!!.rule.targetValue)
        assertNull(rules.bestMatch(RuleType.ROLE_FAMILY, "backendless", RuleTarget.JOB))
    }
}
