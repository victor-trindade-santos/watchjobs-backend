package com.watchjobs.controller

import com.watchjobs.notification.EmailNotifier
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@TestPropertySource(properties = ["app.mail.mode=log"])
class NotificationEmailPostgresTest : AuthSessionPostgresTestSupport() {
    @Autowired
    private lateinit var notifier: EmailNotifier

    private fun prepare(userId: Int, frequency: String): Long {
        jdbc.update(
            """
            insert into core.notification_preference (users_id, email_enabled, email_frequency) values (?, true, ?)
            on conflict (users_id) do update set email_enabled = true, email_frequency = excluded.email_frequency
            """.trimIndent(),
            userId, frequency
        )
        jdbc.update(
            "insert into core.notification (users_id, notification_type, title, dedup_key) values (?, 'NEW_MATCHES', 'Novas vagas', ?)",
            userId, "test-${UUID.randomUUID()}"
        )
        return requireNotNull(
            jdbc.queryForObject(
                "select max(notification_id) from core.notification where users_id = ?", Long::class.java, userId
            )
        )
    }

    private fun emailedAt(notificationId: Long): Any? =
        jdbc.queryForMap("select emailed_at from core.notification where notification_id = ?", notificationId)["emailed_at"]

    private fun clockAt(hourUtc: Int): Clock =
        Clock.fixed(LocalDate.now(ZoneOffset.UTC).atTime(hourUtc, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)

    @Test
    fun `immediate mode sends and marks the notification as emailed`() {
        val login = signupAndLogin()
        val id = prepare(login.user.id, "IMMEDIATE")

        notifier.sendImmediate()

        assertNotNull(emailedAt(id))
    }

    @Test
    fun `daily digest waits for the digest hour in Sao Paulo`() {
        val login = signupAndLogin()
        val id = prepare(login.user.id, "DAILY")

        // 09:00 UTC = 06:00 em Brasília: antes das 8 h, nada sai.
        assertEquals0(notifier.sendDigests(clockAt(9)))
        assertNull(emailedAt(id))

        // 13:00 UTC = 10:00 em Brasília: o resumo sai e o aviso fica marcado.
        notifier.sendDigests(clockAt(13))
        assertNotNull(emailedAt(id))
    }

    @Test
    fun `immediate pass ignores users that chose the daily digest`() {
        val login = signupAndLogin()
        val id = prepare(login.user.id, "DAILY")

        notifier.sendImmediate()

        assertNull(emailedAt(id))
    }

    private fun assertEquals0(value: Int) = kotlin.test.assertEquals(0, value)
}
