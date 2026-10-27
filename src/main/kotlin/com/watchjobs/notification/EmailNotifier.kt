package com.watchjobs.notification

import com.watchjobs.mail.Mailer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

// E-mail dos avisos (F5-05): imediato no fim da rodada, ou resumo diário na primeira rodada depois
// das 8 h de Brasília. Todo e-mail leva o link assinado para desligar.
@Service
class EmailNotifier(
    private val jdbc: JdbcClient,
    private val mailer: Mailer,
    private val tokens: UnsubscribeTokens,
    @Value("\${app.public-base-url:http://localhost:8081}") private val publicBaseUrl: String,
    @Value("\${app.notify.digest-hour:8}") private val digestHour: Int
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val zone = ZoneId.of("America/Sao_Paulo")

    private data class Pending(val id: Long, val userId: Int, val email: String, val title: String, val body: String?)

    fun run(clock: Clock = Clock.systemUTC()): Int = sendImmediate() + sendDigests(clock)

    fun sendImmediate(): Int {
        val pending = pending("p.email_frequency = 'IMMEDIATE'")
        pending.forEach { n ->
            send(n.userId, n.email, "WatchJobs: ${n.title}", listOfNotNull(n.title, n.body).joinToString("\n\n"), listOf(n.id))
        }
        return pending.size
    }

    fun sendDigests(clock: Clock = Clock.systemUTC()): Int {
        val now = OffsetDateTime.now(clock).atZoneSameInstant(zone)
        if (now.hour < digestHour) return 0
        val todayStart = LocalDate.now(clock.withZone(zone)).atTime(digestHour, 0).atZone(zone).toOffsetDateTime()
        val alreadySent = jdbc.sql(
            "SELECT DISTINCT users_id FROM core.notification WHERE emailed_at >= :since"
        ).param("since", todayStart).query(Int::class.java).list().toSet()
        val byUser = pending("p.email_frequency = 'DAILY'").filter { it.userId !in alreadySent }.groupBy { it.userId }
        byUser.forEach { (userId, items) ->
            val text = items.joinToString("\n\n") { listOfNotNull("• ${it.title}", it.body).joinToString("\n") }
            send(userId, items.first().email, "WatchJobs: resumo do dia (${items.size} avisos)", text, items.map { it.id })
        }
        return byUser.size
    }

    private fun pending(frequencyFilter: String): List<Pending> =
        jdbc.sql(
            """
            SELECT n.notification_id, n.users_id, u.email, n.title, n.body
              FROM core.notification n
              JOIN core.notification_preference p ON p.users_id = n.users_id
              JOIN core.users u ON u.users_id = n.users_id
             WHERE p.email_enabled AND $frequencyFilter
               AND n.emailed_at IS NULL AND n.notification_type IN ('NEW_MATCHES', 'SEARCH_COMPLETED')
               AND n.created_at >= now() - interval '1 day'
             ORDER BY n.users_id, n.created_at
            """
        ).query { rs, _ ->
            Pending(rs.getLong("notification_id"), rs.getInt("users_id"), rs.getString("email"), rs.getString("title"), rs.getString("body"))
        }.list()

    private fun send(userId: Int, email: String, subject: String, text: String, notificationIds: List<Long>) {
        val link = "${publicBaseUrl.trimEnd('/')}/notifications/unsubscribe?token=${tokens.create(userId)}"
        try {
            mailer.send(email, subject, "$text\n\nAbra o app para ver as vagas.\n\nPara não receber mais estes e-mails: $link")
            jdbc.sql("UPDATE core.notification SET emailed_at = now() WHERE notification_id IN (:ids)")
                .param("ids", notificationIds).update()
        } catch (ex: Exception) {
            log.warn("E-mail de avisos para o usuário {} falhou: {}", userId, ex.message)
        }
    }
}
