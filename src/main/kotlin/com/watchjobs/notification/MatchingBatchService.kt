package com.watchjobs.notification

import com.watchjobs.matching.FeedService
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

data class BatchSummary(val users: Int, val notifications: Int, val jobsNotified: Int)

// Passos 8 e 9 de 3.6.1, no fim de cada rodada do motor: nota das vagas novas para quem tem a busca
// automática ligada e vencida, e um aviso agrupado com as vagas acima da nota mínima, uma vez por vaga.
@Service
class MatchingBatchService(
    private val jdbc: JdbcClient,
    private val feed: FeedService,
    private val notifications: NotificationService,
    private val transactions: TransactionTemplate
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun run(roundKey: String): BatchSummary {
        val users = jdbc.sql(
            """
            SELECT users_id FROM core.userpreference
             WHERE auto_search_enabled AND confirmed_at IS NOT NULL AND (next_run_at IS NULL OR next_run_at <= now())
             ORDER BY next_run_at NULLS FIRST
            """
        ).query(Int::class.java).list().filterNotNull()
        var created = 0
        var jobs = 0
        users.forEach { userId ->
            try {
                val notified = transactions.execute { notifyUser(userId, roundKey) } ?: 0
                if (notified > 0) {
                    created++
                    jobs += notified
                }
            } catch (ex: Exception) {
                log.warn("Matching em lote do usuário {} falhou: {}", userId, ex.message)
            }
        }
        val summary = BatchSummary(users.size, created, jobs)
        log.info("Matching em lote: {} usuários, {} avisos, {} vagas avisadas", summary.users, summary.notifications, summary.jobsNotified)
        return summary
    }

    // Devolve quantas vagas entraram no aviso desta rodada.
    fun notifyUser(userId: Int, roundKey: String): Int {
        feed.ensureScores(userId)
        val preference = notifications.preference(userId)
        val next = jdbc.sql("UPDATE core.userpreference SET next_run_at = now() + interval '6 hours' WHERE users_id = :userId")
            .param("userId", userId)
        if (!preference.inAppEnabled && !preference.emailEnabled) {
            next.update()
            return 0
        }
        data class Hit(val jobId: Long, val score: Double, val title: String, val organization: String)
        val hits = jdbc.sql(
            """
            SELECT m.discovered_job_id, m.score_total, j.title, j.organization_name
              FROM discovery.user_job_match m
              JOIN core.userpreference p ON p.users_id = m.users_id AND p.profile_version = m.profile_version
              JOIN discovery.discovered_job j ON j.discovered_job_id = m.discovered_job_id
             WHERE m.users_id = :userId AND m.score_total >= :minScore
               AND j.corpus_status = 'ACTIVE' AND j.expires_at > now()
               AND j.discovered_at >= now() - interval '7 days'
               AND NOT EXISTS (SELECT 1 FROM discovery.match_notification n
                                WHERE n.users_id = m.users_id AND n.discovered_job_id = m.discovered_job_id)
               AND NOT EXISTS (SELECT 1 FROM core.usersjobinteraction i
                                WHERE i.users_id = m.users_id AND i.discovered_job_id = m.discovered_job_id
                                  AND i.interaction_type IN ('DISMISSED', 'HIDDEN'))
             ORDER BY m.score_total DESC
             LIMIT 50
            """
        ).param("userId", userId).param("minScore", preference.minScore).query { rs, _ ->
            Hit(rs.getLong("discovered_job_id"), rs.getBigDecimal("score_total").toDouble(), rs.getString("title"), rs.getString("organization_name"))
        }.list()
        next.update()
        if (hits.isEmpty()) return 0

        val minScore = preference.minScore.stripTrailingZeros().toPlainString()
        val id = notifications.create(
            userId = userId,
            type = NotificationType.NEW_MATCHES,
            title = if (hits.size == 1) "1 vaga nova com nota acima de $minScore" else "${hits.size} vagas novas com nota acima de $minScore",
            body = hits.take(3).joinToString("\n") { "${it.title} · ${it.organization} (${it.score.toInt()})" },
            payload = mapOf("jobIds" to hits.map { it.jobId }, "count" to hits.size, "minScore" to preference.minScore, "round" to roundKey),
            dedupKey = "matches:$roundKey"
        ) ?: return 0
        hits.forEach { hit ->
            jdbc.sql(
                """
                INSERT INTO discovery.match_notification (users_id, discovered_job_id, score_total)
                VALUES (:userId, :jobId, :score) ON CONFLICT DO NOTHING
                """
            ).param("userId", userId).param("jobId", hit.jobId).param("score", hit.score.toBigDecimal()).update()
        }
        log.debug("Aviso {} criado para o usuário {} com {} vagas", id, userId, hits.size)
        return hits.size
    }
}
