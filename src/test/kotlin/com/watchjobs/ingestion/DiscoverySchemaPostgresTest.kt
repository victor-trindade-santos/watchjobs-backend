package com.watchjobs.ingestion

import com.watchjobs.catalog.NameNormalizer
import com.watchjobs.sources.JobSourceRepository
import com.watchjobs.sources.SourceType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// F2-01 a F2-05: as migrations V5 a V8 e as sementes aplicadas num PostgreSQL de verdade.
class DiscoverySchemaPostgresTest : DiscoveryPostgresTestSupport() {
    @Autowired
    lateinit var sources: JobSourceRepository

    @Test
    fun `migrations up to V8 are applied and discovered_job has 15 daily partitions ahead`() {
        assertEquals(1L, jdbc.queryForObject(
            "SELECT count(*) FROM public.flyway_schema_history WHERE version = '8' AND success",
            Long::class.java
        ))
        jdbc.execute("SELECT discovery.ensure_partitions(14)")
        val partitions = jdbc.queryForObject(
            """
            SELECT count(*)
              FROM pg_inherits i
              JOIN pg_class c ON c.oid = i.inhrelid
              JOIN pg_class p ON p.oid = i.inhparent
              JOIN pg_namespace n ON n.oid = p.relnamespace
             WHERE n.nspname = 'discovery' AND p.relname = 'discovered_job'
               AND to_date(right(c.relname, 8), 'YYYYMMDD')
                   BETWEEN (now() AT TIME ZONE 'UTC')::date AND (now() AT TIME ZONE 'UTC')::date + 14
            """,
            Long::class.java
        )
        assertEquals(15L, partitions)
    }

    @Test
    fun `nine sources are seeded and the source type drives behaviour`() {
        val codes = sources.findAll().map { it.code }.toSet()
        assertTrue(
            codes.containsAll(listOf("GREENHOUSE", "LEVER", "ASHBY", "SMARTRECRUITERS", "ADZUNA", "JOOBLE", "REMOTIVE", "GUPY", "CAREER_PAGES")),
            codes.toString()
        )
        val adzuna = assertNotNull(sources.findByCode("ADZUNA"))
        assertEquals(SourceType.AGGREGATOR, adzuna.sourceType)
        assertEquals("ADZUNA_APP_ID,ADZUNA_APP_KEY", adzuna.credentialsRef)
        assertFalse(SourceType.AGGREGATOR.closesMissingJobs(fullSnapshot = true))
        assertEquals(6L, jdbc.queryForObject(
            """
            SELECT count(*) FROM core.source_company sc JOIN core.job_source js ON js.job_source_id = sc.job_source_id
             WHERE js.code = 'GREENHOUSE' AND sc.external_key IN ('quintoandar', 'stone', 'ebanx', 'vtex', 'gympass', 'wildlifestudios')
            """,
            Long::class.java
        ))
    }

    @Test
    fun `a source cannot be activated without reviewed terms`() {
        val lever = assertNotNull(sources.findByCode("LEVER"))
        lever.active = true
        assertThrows<DataIntegrityViolationException> { sources.saveAndFlush(lever) }
        assertThrows<DataIntegrityViolationException> {
            jdbc.update("UPDATE core.job_source SET is_active = true, terms_reviewed_at = NULL WHERE code = 'LEVER'")
        }
        assertThrows<DataIntegrityViolationException> {
            jdbc.update("UPDATE core.job_source SET allows_storage = true WHERE code = 'CAREER_PAGES'")
        }
        assertThrows<DataIntegrityViolationException> {
            jdbc.update("UPDATE core.job_source SET credentials_ref = 'abc-123-secret' WHERE code = 'ADZUNA'")
        }
    }

    @Test
    fun `the same company written differently conflicts on the normalized name`() {
        val token = UUID.randomUUID().toString().take(8)
        val insert = "INSERT INTO core.organization (name, organization_type, normalized_name) VALUES (?, 'COMPANY', ?)"
        jdbc.update(insert, "Nubank $token", NameNormalizer.normalize("Nubank $token"))
        try {
            assertThrows<DuplicateKeyException> {
                jdbc.update(insert, "NUBANK $token ", NameNormalizer.normalize("NUBANK $token "))
            }
        } finally {
            jdbc.update("DELETE FROM core.organization WHERE normalized_name = ?", NameNormalizer.normalize("Nubank $token"))
        }
    }

    @Test
    fun `daily maintenance runs`() {
        jdbc.execute("SELECT discovery.purge_expired()")
        jdbc.execute("SELECT discovery.run_daily_maintenance()")
    }
}
