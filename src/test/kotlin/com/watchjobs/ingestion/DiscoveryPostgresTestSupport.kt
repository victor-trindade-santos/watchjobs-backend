package com.watchjobs.ingestion

import com.watchjobs.ingestion.normalize.ExtractedSkill
import com.watchjobs.ingestion.normalize.NormalizedJob
import com.watchjobs.ingestion.connector.FetchTarget
import com.watchjobs.ingestion.store.SourceRow
import com.watchjobs.sources.SourceType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import kotlin.test.assertEquals

// Testes com o PostgreSQL local (o mesmo dos testes de autenticação). Cada teste cria as próprias
// empresas e vagas, com chaves únicas, e apaga só o que criou.
@SpringBootTest(properties = ["spring.docker.compose.enabled=false"])
abstract class DiscoveryPostgresTestSupport {
    @Autowired
    protected lateinit var jdbc: JdbcTemplate

    private val companyIds = mutableListOf<Long>()
    private val organizationIds = mutableListOf<Long>()
    protected val externalIdPrefix = "test-" + UUID.randomUUID().toString().take(8)

    @BeforeEach
    fun requirePostgres() {
        requireNotNull(jdbc.dataSource).connection.use { connection ->
            assertEquals("PostgreSQL", connection.metaData.databaseProductName)
        }
    }

    @AfterEach
    fun cleanupDiscovery() {
        companyIds.forEach { id ->
            jdbc.update("DELETE FROM discovery.discovered_job WHERE source_company_id = ?", id)
            jdbc.update("DELETE FROM discovery.job_seen WHERE source_company_id = ?", id)
            jdbc.update("DELETE FROM discovery.ingestion_run WHERE source_company_id = ?", id)
            jdbc.update("DELETE FROM core.source_company WHERE source_company_id = ?", id)
        }
        jdbc.update("DELETE FROM discovery.discovered_job WHERE external_id LIKE ?", "$externalIdPrefix%")
        jdbc.update("DELETE FROM discovery.job_seen WHERE external_id LIKE ?", "$externalIdPrefix%")
        organizationIds.forEach { jdbc.update("DELETE FROM core.organization WHERE organization_id = ?", it) }
        companyIds.clear()
        organizationIds.clear()
    }

    protected fun greenhouse(): SourceRow {
        val id = requireNotNull(jdbc.queryForObject("SELECT job_source_id FROM core.job_source WHERE code = 'GREENHOUSE'", Long::class.java))
        return SourceRow(id, "GREENHOUSE", "Greenhouse", SourceType.ATS_PUBLIC, null, false, null, null, mapOf("minDelayMs" to 0))
    }

    // Uma empresa nova no Greenhouse, com board exclusivo do teste.
    protected fun createCompany(): FetchTarget {
        val board = externalIdPrefix + "-" + UUID.randomUUID().toString().take(6)
        val name = "Empresa $board"
        val organizationId = requireNotNull(
            jdbc.queryForObject(
                "INSERT INTO core.organization (name, organization_type, normalized_name) VALUES (?, 'COMPANY', ?) RETURNING organization_id",
                Long::class.java, name, name.lowercase()
            )
        )
        organizationIds += organizationId
        val companyId = requireNotNull(
            jdbc.queryForObject(
                "INSERT INTO core.source_company (job_source_id, organization_id, external_key) VALUES (?, ?, ?) RETURNING source_company_id",
                Long::class.java, greenhouse().id, organizationId, board
            )
        )
        companyIds += companyId
        return FetchTarget(sourceCompanyId = companyId, externalKey = board, organizationId = organizationId, organizationName = name)
    }

    protected fun lastJobCount(target: FetchTarget): Int? =
        jdbc.queryForObject("SELECT last_job_count FROM core.source_company WHERE source_company_id = ?", Int::class.javaObjectType, target.sourceCompanyId)

    protected fun skillId(name: String): Long =
        requireNotNull(jdbc.queryForObject("SELECT skill_id FROM core.skill WHERE lower(skill_name) = lower(?)", Long::class.java, name))

    protected fun testJob(
        externalId: String,
        title: String,
        organizationName: String,
        sourceCompanyId: Long? = null,
        skills: List<ExtractedSkill> = emptyList()
    ) = NormalizedJob(
        sourceId = greenhouse().id,
        sourceCompanyId = sourceCompanyId,
        externalId = externalId,
        title = title,
        organizationName = organizationName,
        jobUrl = "https://example.com/jobs/$externalId",
        city = "São Paulo",
        state = "SP",
        countryCode = "BR",
        rawLocation = "São Paulo, Brazil",
        workMode = "REMOTE",
        experienceLevel = "SENIOR",
        jobType = null,
        roleFamily = "BACKEND",
        classificationConfidence = 0.6,
        rulesVersion = "test",
        salaryMin = null,
        salaryMax = null,
        salaryCurrency = null,
        salaryPeriod = null,
        fullDescription = null,
        descriptionSnippet = "Trecho da vaga $title",
        skills = skills,
        postedAt = null,
        contentHash = "a".repeat(64),
        fingerprint = "b".repeat(64)
    )
}
