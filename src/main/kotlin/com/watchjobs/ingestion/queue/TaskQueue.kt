package com.watchjobs.ingestion.queue

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Duration
import kotlin.math.min

// Erro que não adianta repetir (dado inválido, 4xx): a tarefa vai direto para a fila de mortas.
class PermanentTaskException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

// Tarefa travada por este worker; "attempt" é a ficha do lease: só quem tem a tentativa atual conclui.
data class ClaimedTask(val id: Long, val type: String, val payload: String, val attempt: Int, val maxAttempts: Int)

enum class TaskOutcome { RETRY_SCHEDULED, DEAD, IGNORED }

// Fila do motor em discovery.ingestion_task: SELECT ... FOR UPDATE SKIP LOCKED, lease (locked_until), backoff
// exponencial entre tentativas, DLQ (status DEAD) e retomada de tarefas abandonadas por um worker que caiu.
@Repository
class TaskQueue(
    private val jdbc: JdbcClient,
    @Value("\${app.queue.lease-minutes:15}") private val leaseMinutes: Int,
    @Value("\${app.queue.backoff-base-seconds:60}") private val backoffBaseSeconds: Long,
    @Value("\${app.queue.backoff-max-minutes:360}") private val backoffMaxMinutes: Long
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // Pega até "limit" tarefas prontas. Duas rodadas ao mesmo tempo nunca pegam a mesma (SKIP LOCKED),
    // e uma tarefa RUNNING com lease vencido volta a ser elegível.
    fun claim(types: Collection<String>, limit: Int = 20): List<ClaimedTask> =
        jdbc.sql(
            """
            UPDATE discovery.ingestion_task t
               SET status = 'RUNNING', attempts = t.attempts + 1, last_attempt_at = now(),
                   locked_until = now() + make_interval(mins => :lease)
             WHERE t.ingestion_task_id IN (
                   SELECT ingestion_task_id FROM discovery.ingestion_task
                    WHERE task_type IN (:types) AND run_after <= now() AND attempts < max_attempts
                      AND (status = 'PENDING' OR (status = 'RUNNING' AND locked_until < now()))
                    ORDER BY priority, run_after, created_at
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED)
            RETURNING t.ingestion_task_id, t.task_type, CAST(t.payload AS text) AS payload, t.attempts, t.max_attempts
            """
        ).param("lease", leaseMinutes).param("types", types).param("limit", limit)
            .query { rs, _ ->
                ClaimedTask(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5))
            }.list()

    // Renova o lease de uma tarefa longa (heartbeat). Falso = outro worker já retomou a tarefa.
    fun extendLease(task: ClaimedTask): Boolean =
        jdbc.sql(
            """
            UPDATE discovery.ingestion_task SET locked_until = now() + make_interval(mins => :lease)
             WHERE ingestion_task_id = :id AND status = 'RUNNING' AND attempts = :attempt
            """
        ).param("lease", leaseMinutes).param("id", task.id).param("attempt", task.attempt).update() == 1

    fun complete(task: ClaimedTask): Boolean =
        jdbc.sql(
            """
            UPDATE discovery.ingestion_task
               SET status = 'COMPLETED', completed_at = now(), locked_until = NULL, last_error = NULL, failure_kind = NULL
             WHERE ingestion_task_id = :id AND status = 'RUNNING' AND attempts = :attempt
            """
        ).param("id", task.id).param("attempt", task.attempt).update() == 1

    // Falha temporária: volta para PENDING com backoff. Permanente ou sem tentativas: vai para a DLQ.
    fun fail(task: ClaimedTask, error: Throwable): TaskOutcome {
        val permanent = error is PermanentTaskException
        val dead = permanent || task.attempt >= task.maxAttempts
        val message = (error.message ?: error.javaClass.simpleName).take(2000)
        val updated = if (dead) {
            jdbc.sql(
                """
                UPDATE discovery.ingestion_task
                   SET status = 'DEAD', dead_at = now(), locked_until = NULL, last_error = :error, failure_kind = :kind
                 WHERE ingestion_task_id = :id AND status = 'RUNNING' AND attempts = :attempt
                """
            )
        } else {
            jdbc.sql(
                """
                UPDATE discovery.ingestion_task
                   SET status = 'PENDING', locked_until = NULL, last_error = :error, failure_kind = :kind,
                       run_after = now() + make_interval(secs => :delay)
                 WHERE ingestion_task_id = :id AND status = 'RUNNING' AND attempts = :attempt
                """
            ).param("delay", backoff(task.attempt).seconds.toDouble())
        }.param("error", message).param("kind", if (permanent) "PERMANENT" else "TRANSIENT")
            .param("id", task.id).param("attempt", task.attempt).update()
        if (updated == 0) return TaskOutcome.IGNORED
        if (dead) log.warn("Tarefa {} ({}) foi para a fila de mortas: {}", task.id, task.type, message)
        return if (dead) TaskOutcome.DEAD else TaskOutcome.RETRY_SCHEDULED
    }

    // Lease vencido sem tentativas restantes: o worker caiu na última tentativa, a tarefa vai para a DLQ.
    // As que ainda têm tentativas são retomadas pelo próprio claim().
    fun recoverAbandoned(): Int =
        jdbc.sql(
            """
            UPDATE discovery.ingestion_task
               SET status = 'DEAD', dead_at = now(), locked_until = NULL, failure_kind = 'TRANSIENT',
                   last_error = COALESCE(last_error, 'Lease vencido: o worker parou no meio da tarefa')
             WHERE status = 'RUNNING' AND locked_until < now() AND attempts >= max_attempts
            """
        ).update()

    // Recoloca uma tarefa da DLQ na fila (uso manual, depois de corrigir a causa).
    fun requeueDead(id: Long): Boolean =
        jdbc.sql(
            """
            UPDATE discovery.ingestion_task
               SET status = 'PENDING', attempts = 0, run_after = now(), dead_at = NULL, failure_kind = NULL
             WHERE ingestion_task_id = :id AND status = 'DEAD'
            """
        ).param("id", id).update() == 1

    fun backoff(attempt: Int): Duration = backoffFor(attempt, backoffBaseSeconds, backoffMaxMinutes)

    companion object {
        // base · 2^(tentativa − 1), limitado ao teto.
        fun backoffFor(attempt: Int, baseSeconds: Long, maxMinutes: Long): Duration {
            val exponent = (attempt - 1).coerceIn(0, 20)
            val seconds = min(baseSeconds * (1L shl exponent), maxMinutes * 60)
            return Duration.ofSeconds(seconds)
        }
    }
}
