package com.watchjobs.ingestion

import com.watchjobs.ingestion.queue.PermanentTaskException
import com.watchjobs.ingestion.queue.TaskOutcome
import com.watchjobs.ingestion.queue.TaskQueue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// F07: fila com SKIP LOCKED, lease, backoff, DLQ e retomada de tarefas abandonadas, no PostgreSQL.
class TaskQueuePostgresTest : DiscoveryPostgresTestSupport() {
    @Autowired
    lateinit var queue: TaskQueue

    private val keys = mutableListOf<String>()

    private fun enqueue(maxAttempts: Int = 3): Long {
        val key = "test-queue-${UUID.randomUUID()}"
        keys += key
        return requireNotNull(
            jdbc.queryForObject(
                "INSERT INTO discovery.ingestion_task (task_type, idempotency_key, max_attempts, priority) VALUES ('VERIFY_LINKS', ?, ?, 0) RETURNING ingestion_task_id",
                Long::class.java, key, maxAttempts
            )
        )
    }

    private fun status(id: Long) = jdbc.queryForMap("SELECT status, attempts, run_after > now() AS delayed, failure_kind FROM discovery.ingestion_task WHERE ingestion_task_id = ?", id)

    @AfterEach
    fun cleanupTasks() {
        keys.forEach { jdbc.update("DELETE FROM discovery.ingestion_task WHERE idempotency_key = ?", it) }
    }

    @Test
    fun `concurrent workers never claim the same task`() {
        val ids = (1..6).map { enqueue() }.toSet()
        val pool = Executors.newFixedThreadPool(3)
        val claimed = pool.invokeAll((1..3).map { Callable { queue.claim(listOf("VERIFY_LINKS"), 2).map { it.id } } }).flatMap { it.get() }
        pool.shutdown()
        val mine = claimed.filter { it in ids }
        assertEquals(mine.size, mine.toSet().size)
        assertEquals(ids, mine.toSet())
    }

    @Test
    fun `transient failure retries with backoff and exhausting attempts goes to the dead letter queue`() {
        val id = enqueue(maxAttempts = 2)
        val first = queue.claim(listOf("VERIFY_LINKS"), 50).single { it.id == id }
        assertEquals(TaskOutcome.RETRY_SCHEDULED, queue.fail(first, IllegalStateException("timeout")))
        assertEquals("PENDING", status(id)["status"])
        assertEquals(true, status(id)["delayed"])
        assertTrue(queue.claim(listOf("VERIFY_LINKS"), 50).none { it.id == id })

        jdbc.update("UPDATE discovery.ingestion_task SET run_after = now() WHERE ingestion_task_id = ?", id)
        val second = queue.claim(listOf("VERIFY_LINKS"), 50).single { it.id == id }
        assertEquals(2, second.attempt)
        assertEquals(TaskOutcome.DEAD, queue.fail(second, IllegalStateException("timeout de novo")))
        assertEquals("DEAD", status(id)["status"])

        assertTrue(queue.requeueDead(id))
        assertEquals("PENDING", status(id)["status"])
    }

    @Test
    fun `permanent failure goes straight to the dead letter queue`() {
        val id = enqueue()
        val task = queue.claim(listOf("VERIFY_LINKS"), 50).single { it.id == id }
        assertEquals(TaskOutcome.DEAD, queue.fail(task, PermanentTaskException("payload inválido")))
        assertEquals("PERMANENT", status(id)["failure_kind"])
    }

    @Test
    fun `abandoned task is reclaimed after the lease and a stale worker cannot complete it`() {
        val id = enqueue()
        val stale = queue.claim(listOf("VERIFY_LINKS"), 50).single { it.id == id }
        jdbc.update("UPDATE discovery.ingestion_task SET locked_until = now() - interval '1 minute' WHERE ingestion_task_id = ?", id)

        val retaken = queue.claim(listOf("VERIFY_LINKS"), 50).single { it.id == id }
        assertEquals(2, retaken.attempt)
        assertEquals(false, queue.complete(stale))
        assertEquals(true, queue.complete(retaken))
        assertEquals("COMPLETED", status(id)["status"])
    }
}
