package com.watchjobs.ingestion

import com.watchjobs.ingestion.pipeline.Circuit
import com.watchjobs.ingestion.pipeline.CircuitBreaker
import com.watchjobs.ingestion.pipeline.CircuitPolicy
import com.watchjobs.ingestion.pipeline.CircuitState
import com.watchjobs.ingestion.queue.TaskQueue
import com.watchjobs.ingestion.store.DedupRules
import com.watchjobs.ingestion.store.GroupCandidate
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// F07: regras puras do motor (circuit breaker, backoff da fila e deduplicação entre fontes).
class IngestionEngineRulesTest {
    private val now = OffsetDateTime.parse("2026-10-30T12:00:00Z")
    private val policy = CircuitPolicy(failureThreshold = 3, recovery = Duration.ofMinutes(10), maxRecovery = Duration.ofMinutes(60))

    @Test
    fun `circuit opens at the threshold, probes half open and closes on success`() {
        var circuit = Circuit()
        repeat(2) { circuit = CircuitBreaker.onFailure(circuit, now, policy) }
        assertEquals(CircuitState.CLOSED, circuit.state)
        circuit = CircuitBreaker.onFailure(circuit, now, policy)
        assertEquals(CircuitState.OPEN, circuit.state)
        assertEquals(now.plusMinutes(10), circuit.nextAttemptAt)
        assertFalse(CircuitBreaker.canAttempt(circuit, now.plusMinutes(5)))
        assertTrue(CircuitBreaker.canAttempt(circuit, now.plusMinutes(10)))

        val probing = CircuitBreaker.beforeAttempt(circuit, now.plusMinutes(11))
        assertEquals(CircuitState.HALF_OPEN, probing.state)
        assertEquals(CircuitState.CLOSED, CircuitBreaker.onSuccess().state)
        assertEquals(0, CircuitBreaker.onSuccess().failures)
    }

    @Test
    fun `failed probe reopens with doubled recovery up to the cap`() {
        val open = Circuit(CircuitState.HALF_OPEN, failures = 3)
        val reopened = CircuitBreaker.onFailure(open, now, policy)
        assertEquals(CircuitState.OPEN, reopened.state)
        assertEquals(now.plusMinutes(20), reopened.nextAttemptAt)
        val many = CircuitBreaker.onFailure(Circuit(CircuitState.HALF_OPEN, failures = 12), now, policy)
        assertEquals(now.plusMinutes(60), many.nextAttemptAt)
    }

    @Test
    fun `queue backoff is exponential and capped`() {
        assertEquals(Duration.ofSeconds(60), TaskQueue.backoffFor(1, 60, 360))
        assertEquals(Duration.ofSeconds(120), TaskQueue.backoffFor(2, 60, 360))
        assertEquals(Duration.ofSeconds(480), TaskQueue.backoffFor(4, 60, 360))
        assertEquals(Duration.ofMinutes(360), TaskQueue.backoffFor(15, 60, 360))
    }

    private fun candidate(sourceId: Long, url: String, fingerprint: String = "f".repeat(64), workMode: String? = "REMOTE") =
        GroupCandidate(99, sourceId, "x", null, url, fingerprint, workMode, null, "BR")

    @Test
    fun `dedup joins different sources and never two postings of the same source`() {
        val job = TestJobs.job(sourceId = 1, url = "https://boards.greenhouse.io/acme/jobs/1?gh_src=abc", fingerprint = "f".repeat(64))

        assertTrue(DedupRules.sameJob(job, candidate(2, "https://other.example/job/9")))
        assertFalse(DedupRules.sameJob(job, candidate(1, "https://boards.greenhouse.io/acme/jobs/2")))
        assertFalse(DedupRules.sameJob(job, candidate(2, "https://other.example/job/9", fingerprint = "e".repeat(64))))
        assertFalse(DedupRules.sameJob(job, candidate(2, "https://other.example/job/9", workMode = "ON_SITE")))
        assertTrue(DedupRules.sameJob(job, candidate(1, "https://www.boards.greenhouse.io/acme/jobs/1/?utm_source=x", fingerprint = "e".repeat(64))))
        assertEquals("example.com/a?id=1", DedupRules.normalizeUrl("https://www.Example.com/a/?utm_medium=x&id=1#top"))
    }
}

// Monta uma NormalizedJob mínima sem banco.
object TestJobs {
    fun job(sourceId: Long, url: String, fingerprint: String, externalId: String = "e1") = com.watchjobs.ingestion.normalize.NormalizedJob(
        sourceId = sourceId, sourceCompanyId = null, externalId = externalId, title = "Backend Engineer", organizationName = "ACME",
        jobUrl = url, city = "São Paulo", state = "SP", countryCode = "BR", rawLocation = null, workMode = "REMOTE",
        experienceLevel = null, jobType = null, roleFamily = null, classificationConfidence = null, rulesVersion = "t",
        salaryMin = null, salaryMax = null, salaryCurrency = null, salaryPeriod = null, fullDescription = null,
        descriptionSnippet = null, skills = emptyList(), postedAt = null, contentHash = "a".repeat(64), fingerprint = fingerprint
    )
}
