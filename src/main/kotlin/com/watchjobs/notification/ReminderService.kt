package com.watchjobs.notification

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class ReminderSummary(val interviews: Int, val staleApplications: Int, val savedJobAlerts: Int)

// Lembretes do fim de cada rodada: entrevistas próximas, candidaturas paradas e vagas salvas saindo do ar.
// O dedup_key garante um aviso só por situação, mesmo com várias rodadas por dia.
@Service
class ReminderService(
    private val jdbc: JdbcClient,
    private val notifications: NotificationService,
    @Value("\${app.notify.interview-hours:24}") private val interviewHours: Int,
    @Value("\${app.notify.stale-days:14}") private val staleDays: Int,
    @Value("\${app.notify.saved-expiring-days:3}") private val expiringDays: Int
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val shortDate = DateTimeFormatter.ofPattern("dd/MM HH:mm")

    fun run(): ReminderSummary {
        val summary = ReminderSummary(
            runCatching { interviews() }.onFailure { log.warn("Lembretes de entrevista falharam: {}", it.message) }.getOrDefault(0),
            runCatching { staleApplications() }.onFailure { log.warn("Lembretes de candidaturas paradas falharam: {}", it.message) }.getOrDefault(0),
            runCatching { savedJobAlerts() }.onFailure { log.warn("Avisos de vagas salvas falharam: {}", it.message) }.getOrDefault(0)
        )
        log.info("Lembretes: {} entrevistas, {} candidaturas paradas, {} vagas salvas", summary.interviews, summary.staleApplications, summary.savedJobAlerts)
        return summary
    }

    // interview_at é hora local (sem fuso) de Brasília.
    fun interviews(): Int {
        data class Row(val applicationId: Int, val userId: Int, val savedJobId: Long, val at: LocalDateTime, val role: String, val org: String)
        val rows = jdbc.sql(
            """
            SELECT a.application_id, a.users_id, a.saved_job_id, a.interview_at, sj.position_role, sj.organization_name
              FROM core.application a
              JOIN core.saved_job sj ON sj.saved_job_id = a.saved_job_id
              LEFT JOIN core.notification_preference p ON p.users_id = a.users_id
             WHERE a.interview_at IS NOT NULL
               AND a.application_status IN ('APPLIED', 'IN_PROCESS', 'OFFER_RECEIVED')
               AND (a.interview_at AT TIME ZONE 'America/Sao_Paulo') BETWEEN now() AND now() + make_interval(hours => :hours)
               AND COALESCE(p.in_app_enabled OR p.email_enabled OR p.push_enabled, true)
            """
        ).param("hours", interviewHours).query { rs, _ ->
            Row(
                rs.getInt("application_id"), rs.getInt("users_id"), rs.getLong("saved_job_id"),
                rs.getObject("interview_at", LocalDateTime::class.java), rs.getString("position_role"), rs.getString("organization_name")
            )
        }.list()
        return rows.count { row ->
            notifications.create(
                userId = row.userId,
                type = NotificationType.INTERVIEW_REMINDER,
                title = "Entrevista com ${row.org}, ${row.at.format(shortDate)}",
                body = row.role,
                payload = mapOf("savedJobId" to row.savedJobId, "applicationId" to row.applicationId, "interviewAt" to row.at.toString()),
                dedupKey = "interview:${row.applicationId}:${row.at}"
            ) != null
        }
    }

    // Uma vez a cada "staleDays" sem mudança de status, enquanto a candidatura estiver aberta.
    fun staleApplications(): Int {
        data class Row(val savedJobId: Long, val userId: Int, val role: String, val org: String, val days: Int, val bucket: Int)
        val rows = jdbc.sql(
            """
            SELECT sj.saved_job_id, sj.users_id, sj.position_role, sj.organization_name,
                   floor(extract(epoch FROM now() - sj.status_updated_at) / 86400)::int AS days
              FROM core.saved_job sj
              LEFT JOIN core.notification_preference p ON p.users_id = sj.users_id
             WHERE sj.status IN ('APPLIED', 'IN_PROCESS')
               AND sj.status_updated_at < now() - make_interval(days => :days)
               AND COALESCE(p.in_app_enabled OR p.email_enabled OR p.push_enabled, true)
            """
        ).param("days", staleDays).query { rs, _ ->
            val days = rs.getInt("days")
            Row(rs.getLong("saved_job_id"), rs.getInt("users_id"), rs.getString("position_role"), rs.getString("organization_name"), days, days / staleDays)
        }.list()
        return rows.count { row ->
            notifications.create(
                userId = row.userId,
                type = NotificationType.STALE_APPLICATION,
                title = "Sem novidades há ${row.days} dias: ${row.org}",
                body = "${row.role}. Que tal mandar um follow-up ou atualizar a etapa?",
                payload = mapOf("savedJobId" to row.savedJobId, "days" to row.days),
                dedupKey = "stale:${row.savedJobId}:${row.bucket}"
            ) != null
        }
    }

    // Vagas salvas (ainda sem candidatura) cuja vaga de origem foi encerrada ou sai do ar em breve.
    fun savedJobAlerts(): Int {
        data class Row(val savedJobId: Long, val userId: Int, val role: String, val org: String, val closed: Boolean)
        val rows = jdbc.sql(
            """
            SELECT sj.saved_job_id, sj.users_id, sj.position_role, sj.organization_name, (j.corpus_status <> 'ACTIVE') AS closed
              FROM core.saved_job sj
              JOIN discovery.discovered_job j ON j.discovered_job_id = sj.discovered_job_id
              LEFT JOIN core.notification_preference p ON p.users_id = sj.users_id
             WHERE sj.status IN ('SAVED', 'PLANNING_TO_APPLY')
               AND (j.corpus_status <> 'ACTIVE' OR j.expires_at < now() + make_interval(days => :days))
               AND COALESCE(p.in_app_enabled OR p.email_enabled OR p.push_enabled, true)
            """
        ).param("days", expiringDays).query { rs, _ ->
            Row(rs.getLong("saved_job_id"), rs.getInt("users_id"), rs.getString("position_role"), rs.getString("organization_name"), rs.getBoolean("closed"))
        }.list()
        return rows.count { row ->
            notifications.create(
                userId = row.userId,
                type = NotificationType.SAVED_JOB_ALERT,
                title = if (row.closed) "Vaga encerrada na fonte: ${row.org}" else "Candidate-se logo: ${row.org} sai do ar em breve",
                body = row.role,
                payload = mapOf("savedJobId" to row.savedJobId, "closed" to row.closed),
                dedupKey = "saved:${row.savedJobId}:${if (row.closed) "closed" else "expiring"}"
            ) != null
        }
    }
}
