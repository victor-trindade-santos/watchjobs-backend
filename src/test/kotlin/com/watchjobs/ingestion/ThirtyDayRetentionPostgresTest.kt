package com.watchjobs.ingestion

import com.watchjobs.entity.User
import com.watchjobs.ingestion.pipeline.JobWriter
import com.watchjobs.ingestion.pipeline.WriteOutcome
import com.watchjobs.ingestion.store.DiscoveredJobStore
import com.watchjobs.ingestion.store.JobSeenStore
import com.watchjobs.jobs.JobCorpusService
import com.watchjobs.repository.UserRepository
import com.watchjobs.service.SavedJobService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

// F2-16: a vaga some no 31º dia, não volta como nova, e a cópia salva continua.
class ThirtyDayRetentionPostgresTest : DiscoveryPostgresTestSupport() {
    @Autowired
    lateinit var store: DiscoveredJobStore

    @Autowired
    lateinit var seen: JobSeenStore

    @Autowired
    lateinit var writer: JobWriter

    @Autowired
    lateinit var corpus: JobCorpusService

    @Autowired
    lateinit var savedJobs: SavedJobService

    @Autowired
    lateinit var users: UserRepository

    private var userId: Int? = null
    private val runIds = mutableListOf<Long>()

    @AfterEach
    fun deleteUserAndRuns() {
        userId?.let { jdbc.update("DELETE FROM core.users WHERE users_id = ?", it) }
        jdbc.update("DELETE FROM discovery.job_seen WHERE external_id LIKE ?", "$externalIdPrefix%")
        runIds.forEach { jdbc.update("DELETE FROM discovery.ingestion_run WHERE ingestion_run_id = ?", it) }
    }

    @Test
    fun `an old job is purged, never comes back and the saved copy remains`() {
        val user = users.save(
            User(
                firstName = "Retencao",
                lastName = "Teste",
                birthDate = LocalDate.of(1995, 1, 1),
                email = "retention-${UUID.randomUUID()}@example.com",
                password = "test-hash"
            )
        )
        userId = requireNotNull(user.id)

        // 1. A vaga entra hoje e o usuário a salva (é assim que se guarda além dos 30 dias).
        val job = testJob("$externalIdPrefix-old", "Kotlin Engineer", "Retention Test")
        val key = store.insert(job, null, null)
        seen.insert(job.sourceId, job.externalId, key, null, job.contentHash, runId = createRun())
        val saved = corpus.save(userId!!, key.id)

        // 2. A vaga é movida para um dia de 40 dias atrás (simula o tempo passando).
        val oldDay = LocalDate.now(ZoneOffset.UTC).minusDays(40)
        listOf("discovered_job", "discovered_job_skill", "discovered_job_language", "discovered_job_education", "discovered_job_certification")
            .forEach { jdbc.execute("SELECT discovery.create_daily_partition('$it', DATE '$oldDay')") }
        val oldInstant = oldDay.atTime(12, 0).atOffset(ZoneOffset.UTC)
        jdbc.update(
            "UPDATE discovery.discovered_job SET discovered_at = ?, expires_at = ? WHERE discovered_job_id = ?",
            oldInstant, oldInstant.plusDays(30), key.id
        )
        jdbc.update("UPDATE discovery.job_seen SET discovered_at = ? WHERE external_id = ?", oldInstant, job.externalId)

        // 3. A limpeza descarta a partição do dia e marca a vaga como expirada.
        jdbc.execute("SELECT discovery.purge_expired()")

        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM discovery.discovered_job WHERE discovered_job_id = ?", Long::class.java, key.id))
        val expired = assertNotNull(seen.find(job.sourceId, job.externalId))
        assertNotNull(expired.expiredAt)

        // 4. A fonte continua listando a vaga: ela não volta como nova.
        assertEquals(WriteOutcome.EXPIRED, writer.write(job, null, createRun()))
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM discovery.discovered_job WHERE external_id = ?", Long::class.java, job.externalId))

        // 5. A vaga salva continua na lista do usuário.
        val list = savedJobs.findAll(userId!!, null)
        assertEquals(listOf(saved.id), list.map { it.id })
        assertEquals("Kotlin Engineer", list.single().positionRole)
    }

    private fun createRun(): Long = requireNotNull(
        jdbc.queryForObject(
            "INSERT INTO discovery.ingestion_run (source_id, trigger_type) VALUES (?, 'MANUAL') RETURNING ingestion_run_id",
            Long::class.java, greenhouse().id
        )
    ).also { runIds += it }
}
