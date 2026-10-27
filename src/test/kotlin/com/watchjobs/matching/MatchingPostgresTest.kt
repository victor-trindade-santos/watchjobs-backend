package com.watchjobs.matching

import com.watchjobs.controller.AuthSessionPostgresTestSupport
import com.watchjobs.ingestion.normalize.NormalizedJob
import com.watchjobs.ingestion.store.DiscoveredJobStore
import com.watchjobs.notification.MatchingBatchService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// F4 (feed, nota, interações, nota congelada) e F5-04/F5-06 (avisos sem repetição, preferência padrão).
class MatchingPostgresTest : AuthSessionPostgresTestSupport() {
    @Autowired
    lateinit var store: DiscoveredJobStore

    @Autowired
    lateinit var feed: FeedService

    @Autowired
    lateinit var batch: MatchingBatchService

    private val token = "mx" + UUID.randomUUID().toString().take(8)

    @AfterEach
    fun deleteJobs() {
        jdbc.update("DELETE FROM discovery.discovered_job WHERE external_id LIKE ?", "$token%")
    }

    private fun job(suffix: String, title: String, level: String = "SENIOR", mode: String = "REMOTE") = NormalizedJob(
        sourceId = requireNotNull(jdbc.queryForObject("SELECT job_source_id FROM core.job_source WHERE code = 'GREENHOUSE'", Long::class.java)),
        sourceCompanyId = null,
        externalId = "$token-$suffix",
        title = title,
        organizationName = "Empresa $token",
        jobUrl = "https://example.com/jobs/$token-$suffix",
        city = null, state = null, countryCode = "BR", rawLocation = "Remote - Brazil",
        workMode = mode, experienceLevel = level, jobType = null, roleFamily = "BACKEND",
        classificationConfidence = 1.0, rulesVersion = "test",
        salaryMin = null, salaryMax = null, salaryCurrency = null, salaryPeriod = null,
        fullDescription = null, descriptionSnippet = "Vaga $title", skills = emptyList(), postedAt = null,
        contentHash = "e".repeat(64), fingerprint = "f".repeat(64)
    )

    private fun preferences(bearer: String, role: String, extra: String = "") =
        mockMvc.put("/profile/preferences") {
            header("Authorization", bearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"preferredRoles":["$role"],"preferredWorkMode":"REMOTE","preferredExperienceLevel":"SENIOR"$extra}"""
        }.andExpect { status { isOk() } }

    private fun matchRows(userId: Int): Int =
        requireNotNull(jdbc.queryForObject("SELECT count(*) FROM discovery.user_job_match WHERE users_id = ?", Int::class.java, userId))

    @Test
    fun `feed scores once per profile version, hides dismissed jobs and freezes the score on save`() {
        val login = signupAndLogin()
        val bearer = "Bearer ${login.token}"
        val role = "Xyzzy Quux $token"
        preferences(bearer, role)
        val exact = store.insert(job("1", role), null, null)

        mockMvc.get("/jobs/feed") { header("Authorization", bearer) }.andExpect { status { isOk() } }
        val first = matchRows(login.user.id)
        assertTrue(first > 0)
        mockMvc.get("/jobs/feed") { header("Authorization", bearer) }.andExpect { status { isOk() } }
        assertEquals(first, matchRows(login.user.id))

        mockMvc.get("/jobs/${exact.id}") { header("Authorization", bearer) }.andExpect {
            status { isOk() }
            jsonPath("$.match.score") { value(100.0) }
            jsonPath("$.match.algoVersion") { value(MatchingEngine.ALGO_VERSION) }
        }

        preferences(bearer, role, ""","salaryMin":5000""")
        mockMvc.get("/jobs/feed") { header("Authorization", bearer) }.andExpect { status { isOk() } }
        assertTrue(matchRows(login.user.id) > first)

        mockMvc.post("/jobs/${exact.id}/save") { header("Authorization", bearer) }.andExpect { status { isCreated() } }
        val frozen = jdbc.queryForObject(
            "SELECT match_score_at_save FROM core.saved_job WHERE users_id = ? AND external_id = ?",
            java.math.BigDecimal::class.java, login.user.id, "$token-1"
        )
        assertEquals(0, java.math.BigDecimal("100.00").compareTo(frozen))

        mockMvc.post("/jobs/${exact.id}/interactions") {
            header("Authorization", bearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"type":"DISMISSED"}"""
        }.andExpect { status { isNoContent() } }
        assertTrue(feed.scoredItems(login.user.id, listOf(exact.id)).isEmpty())

        mockMvc.post("/jobs/refresh") { header("Authorization", bearer) }.andExpect { status { isOk() } }
    }

    @Test
    fun `new user has default notification preference`() {
        val bearer = "Bearer ${signupAndLogin().token}"
        mockMvc.get("/notifications/preferences") { header("Authorization", bearer) }.andExpect {
            status { isOk() }
            jsonPath("$.minScore") { value(70.0) }
            jsonPath("$.inAppEnabled") { value(true) }
            jsonPath("$.emailEnabled") { value(false) }
        }
        mockMvc.put("/notifications/preferences") {
            header("Authorization", bearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"minScore":150}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `batch alerts high scores once and a lower minimum brings the rest`() {
        val login = signupAndLogin()
        val bearer = "Bearer ${login.token}"
        val role = "Xyzzy Quux $token"
        preferences(bearer, role)
        val high = store.insert(job("high", role), null, null)
        val medium = store.insert(job("medium", "Outro cargo $token"), null, null)
        fun notified(id: Long) = requireNotNull(jdbc.queryForObject(
            "SELECT count(*) FROM discovery.match_notification WHERE users_id = ? AND discovered_job_id = ?",
            Int::class.java, login.user.id, id
        ))
        fun resetRun() = jdbc.update("UPDATE core.userpreference SET next_run_at = now() WHERE users_id = ?", login.user.id)

        batch.notifyUser(login.user.id, "$token-1")
        assertEquals(1, notified(high.id))
        assertEquals(0, notified(medium.id))
        mockMvc.get("/notifications/unread-count") { header("Authorization", bearer) }
            .andExpect { jsonPath("$.unread") { value(1) } }

        resetRun()
        assertEquals(0, batch.notifyUser(login.user.id, "$token-2"))

        mockMvc.put("/notifications/preferences") {
            header("Authorization", bearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"minScore":50}"""
        }.andExpect { status { isOk() } }
        resetRun()
        batch.notifyUser(login.user.id, "$token-3")
        assertEquals(1, notified(medium.id))
        assertEquals(1, notified(high.id))

        val list = objectMapper.readTree(
            mockMvc.get("/notifications") { header("Authorization", bearer) }.andReturn().response.contentAsString
        )
        val firstId = list["items"][0]["id"].asLong()
        assertNotNull(firstId)
        mockMvc.post("/notifications/read-all") { header("Authorization", bearer) }.andExpect { status { isNoContent() } }
        mockMvc.get("/notifications/unread-count") { header("Authorization", bearer) }
            .andExpect { jsonPath("$.unread") { value(0) } }
    }

    @Test
    fun `on demand search is limited per user`() {
        val login = signupAndLogin()
        val bearer = "Bearer ${login.token}"
        repeat(5) {
            jdbc.update(
                "INSERT INTO discovery.search_request (users_id, query_text, query_key, status) VALUES (?, 'x', ?, 'SYNC_DONE')",
                login.user.id, "0".repeat(64)
            )
        }
        mockMvc.post("/jobs/discover") {
            header("Authorization", bearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"q":"kotlin"}"""
        }.andExpect {
            status { isTooManyRequests() }
            jsonPath("$.code") { value("TOO_MANY_REQUESTS") }
        }
        mockMvc.get("/jobs/discover/999999999") { header("Authorization", bearer) }.andExpect { status { isNotFound() } }
    }
}
