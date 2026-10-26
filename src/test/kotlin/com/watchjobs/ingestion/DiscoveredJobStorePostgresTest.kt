package com.watchjobs.ingestion

import com.watchjobs.ingestion.normalize.ExtractedSkill
import com.watchjobs.ingestion.store.DiscoveredJobStore
import com.watchjobs.ingestion.store.JobSeenStore
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// F2-07: gravar uma vaga com skills e ler de volta; a filha cai na mesma partição do dia da vaga.
class DiscoveredJobStorePostgresTest : DiscoveryPostgresTestSupport() {
    @Autowired
    lateinit var store: DiscoveredJobStore

    @Autowired
    lateinit var seen: JobSeenStore

    @Test
    fun `job and skills are written to the same daily partition`() {
        val java = skillId("Java")
        val kotlin = skillId("Kotlin")
        val job = testJob(
            "$externalIdPrefix-1", "Backend Engineer", "Store Test",
            skills = listOf(ExtractedSkill(java, "Java", true), ExtractedSkill(kotlin, "Kotlin", false))
        )

        val key = store.insert(job, null, null)
        store.insertSkills(key, job.skills)
        seen.insert(job.sourceId, job.externalId, key, null, job.contentHash, runId = createRun())

        assertEquals(listOf(java, kotlin).sorted(), store.skillIds(key))
        val jobPartition = jdbc.queryForObject(
            "SELECT tableoid::regclass::text FROM discovery.discovered_job WHERE discovered_job_id = ?", String::class.java, key.id
        )!!
        val skillPartitions = jdbc.queryForList(
            "SELECT DISTINCT tableoid::regclass::text FROM discovery.discovered_job_skill WHERE discovered_job_id = ?",
            String::class.java, key.id
        )
        assertEquals(listOf(jobPartition.substringAfterLast("_p")), skillPartitions.map { it!!.substringAfterLast("_p") })

        val row = jdbc.queryForMap("SELECT * FROM discovery.discovered_job WHERE discovered_job_id = ?", key.id)
        assertEquals("Backend Engineer", row["title"])
        assertEquals("ACTIVE", row["corpus_status"])
        val days = jdbc.queryForObject(
            "SELECT extract(day FROM expires_at - discovered_at)::int FROM discovery.discovered_job WHERE discovered_job_id = ?",
            Int::class.java, key.id
        )
        assertEquals(30, days)

        val stored = assertNotNull(seen.find(job.sourceId, job.externalId))
        assertEquals(key, stored.key)
        assertEquals(1, stored.version)

        val changed = job.copy(title = "Senior Backend Engineer", skills = listOf(ExtractedSkill(java, "Java", true)))
        assertTrue(store.update(key, changed, null, null))
        store.replaceSkills(key, changed.skills)
        assertEquals(listOf(java), store.skillIds(key))
    }

    private fun createRun(): Long = requireNotNull(
        jdbc.queryForObject(
            "INSERT INTO discovery.ingestion_run (source_id, trigger_type) VALUES (?, 'MANUAL') RETURNING ingestion_run_id",
            Long::class.java, greenhouse().id
        )
    ).also { runIds += it }

    private val runIds = mutableListOf<Long>()

    @org.junit.jupiter.api.AfterEach
    fun deleteRuns() {
        runIds.forEach { jdbc.update("DELETE FROM discovery.ingestion_run WHERE ingestion_run_id = ?", it) }
    }
}
