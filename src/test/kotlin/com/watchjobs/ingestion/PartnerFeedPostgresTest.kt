package com.watchjobs.ingestion

import com.watchjobs.controller.AuthSessionPostgresTestSupport
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.post
import java.util.UUID
import kotlin.test.assertEquals

// F07: lote de parceiro com chave, checksum contra reenvio e erro isolado por registro.
@TestPropertySource(properties = ["GUPY_FEED_KEY=test-partner-key"])
class PartnerFeedPostgresTest : AuthSessionPostgresTestSupport() {
    private val prefix = "partner-" + UUID.randomUUID().toString().take(8)
    private var wasActive = false

    @BeforeEach
    fun activateFeed() {
        wasActive = jdbc.queryForObject("SELECT is_active FROM core.job_source WHERE code = 'GUPY'", Boolean::class.java) == true
        jdbc.update("UPDATE core.job_source SET is_active = true, terms_reviewed_at = COALESCE(terms_reviewed_at, now()) WHERE code = 'GUPY'")
    }

    @AfterEach
    fun cleanupFeed() {
        jdbc.update("DELETE FROM discovery.discovered_job WHERE external_id LIKE ?", "$prefix%")
        jdbc.update("DELETE FROM discovery.job_seen WHERE external_id LIKE ?", "$prefix%")
        jdbc.update("DELETE FROM discovery.ingestion_run WHERE batch_reference = ?", prefix)
        if (!wasActive) jdbc.update("UPDATE core.job_source SET is_active = false WHERE code = 'GUPY'")
    }

    private val batch = """
        {"batchReference":"$prefix","jobs":[
          {"externalId":"$prefix-1","title":"Desenvolvedor Backend Pleno","company":"Parceira SA","url":"https://parceira.example/vagas/1",
           "location":"São Paulo, SP","workMode":"HYBRID","jobType":"CLT","salaryMin":8000,"salaryMax":11000,"salaryCurrency":"BRL","salaryPeriod":"MONTH"},
          {"externalId":"$prefix-2","title":"Vaga sem link","company":"Parceira SA","url":"ftp://parceira.example/2"},
          {"externalId":"$prefix-3","title":"Analista de Dados","company":"Parceira SA","url":"https://parceira.example/vagas/3","postedAt":"2026-10-28T10:00:00-03:00"}
        ]}
    """.trimIndent()

    private fun send(key: String?) = mockMvc.post("/partner-feed/gupy/batches") {
        contentType = MediaType.APPLICATION_JSON
        content = batch
        key?.let { header("X-Partner-Key", it) }
    }

    @Test
    fun `partner batch is validated per record and a resend is ignored`() {
        send("errada").andExpect { status { isUnauthorized() } }
        send(null).andExpect { status { isUnauthorized() } }

        send("test-partner-key").andExpect {
            status { isOk() }
            jsonPath("$.duplicate") { value(false) }
            jsonPath("$.created") { value(2) }
            jsonPath("$.rejected") { value(1) }
            jsonPath("$.errors[0].index") { value(1) }
        }
        val runId = requireNotNull(jdbc.queryForObject("SELECT ingestion_run_id FROM discovery.ingestion_run WHERE batch_reference = ?", Long::class.java, prefix))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM discovery.ingestion_record_error WHERE ingestion_run_id = ?", Int::class.java, runId))
        assertEquals("HYBRID", jdbc.queryForObject("SELECT work_mode FROM discovery.discovered_job WHERE external_id = ?", String::class.java, "$prefix-1"))

        send("test-partner-key").andExpect {
            status { isOk() }
            jsonPath("$.duplicate") { value(true) }
        }
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM discovery.discovered_job WHERE external_id LIKE ?", Int::class.java, "$prefix%"))
    }
}
