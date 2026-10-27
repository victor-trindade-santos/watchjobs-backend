package com.watchjobs.notification

import com.watchjobs.exception.NotFoundException
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.OffsetDateTime

enum class NotificationType { NEW_MATCHES, SEARCH_COMPLETED, SYSTEM }

enum class EmailFrequency { IMMEDIATE, DAILY }

data class NotificationResponse(
    val id: Long,
    val type: NotificationType,
    val title: String,
    val body: String?,
    val payload: Map<String, Any?>,
    val createdAt: OffsetDateTime,
    val readAt: OffsetDateTime?
)

data class NotificationPage(val items: List<NotificationResponse>, val page: Int, val size: Int, val total: Long)

data class UnreadCount(val unread: Long)

data class NotificationPreferenceResponse(
    val minScore: BigDecimal,
    val inAppEnabled: Boolean,
    val emailEnabled: Boolean,
    val emailFrequency: EmailFrequency
)

data class NotificationPreferenceRequest(
    @field:DecimalMin(value = "0", message = "A nota mínima vai de 0 a 100")
    @field:DecimalMax(value = "100", message = "A nota mínima vai de 0 a 100")
    val minScore: BigDecimal? = null,
    val inAppEnabled: Boolean? = null,
    val emailEnabled: Boolean? = null,
    val emailFrequency: EmailFrequency? = null
)

// Avisos do sininho e preferências de notificação (3.7.2).
@Service
class NotificationService(
    private val jdbc: JdbcClient,
    private val mapper: ObjectMapper
) {
    // Cria o aviso uma vez só por dedup_key; devolve o id ou null se já existia.
    fun create(userId: Int, type: NotificationType, title: String, body: String?, payload: Map<String, Any?>, dedupKey: String): Long? =
        jdbc.sql(
            """
            INSERT INTO core.notification (users_id, notification_type, title, body, payload, dedup_key)
            VALUES (:userId, :type, :title, :body, CAST(:payload AS jsonb), :dedupKey)
            ON CONFLICT (users_id, dedup_key) DO NOTHING
            RETURNING notification_id
            """
        )
            .param("userId", userId)
            .param("type", type.name)
            .param("title", title.take(150))
            .param("body", body)
            .param("payload", mapper.writeValueAsString(payload))
            .param("dedupKey", dedupKey.take(150))
            .query(Long::class.java)
            .optional().orElse(null)

    fun createDefaultPreference(userId: Int) {
        jdbc.sql("INSERT INTO core.notification_preference (users_id) VALUES (:userId) ON CONFLICT (users_id) DO NOTHING")
            .param("userId", userId).update()
    }

    @Transactional(readOnly = true)
    fun list(userId: Int, unreadOnly: Boolean, page: Int, size: Int): NotificationPage {
        val safePage = page.coerceAtLeast(0)
        val safeSize = size.coerceIn(1, 50)
        val filter = if (unreadOnly) "AND read_at IS NULL" else ""
        val total = jdbc.sql("SELECT count(*) FROM core.notification WHERE users_id = :userId $filter")
            .param("userId", userId).query(Long::class.java).single()
        val items = jdbc.sql(
            """
            SELECT notification_id, notification_type, title, body, CAST(payload AS text) AS payload, created_at, read_at
              FROM core.notification
             WHERE users_id = :userId $filter
             ORDER BY created_at DESC, notification_id DESC
             LIMIT :size OFFSET :offset
            """
        ).param("userId", userId).param("size", safeSize).param("offset", safePage.toLong() * safeSize).query { rs, _ ->
            @Suppress("UNCHECKED_CAST")
            NotificationResponse(
                id = rs.getLong("notification_id"),
                type = NotificationType.valueOf(rs.getString("notification_type")),
                title = rs.getString("title"),
                body = rs.getString("body"),
                payload = runCatching { mapper.readValue(rs.getString("payload"), Map::class.java) as Map<String, Any?> }
                    .getOrDefault(emptyMap()),
                createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
                readAt = rs.getObject("read_at", OffsetDateTime::class.java)
            )
        }.list()
        return NotificationPage(items, safePage, safeSize, total)
    }

    // Consulta barata: usa o índice parcial de não lidos (o app pergunta a cada 60 s).
    @Transactional(readOnly = true)
    fun unreadCount(userId: Int): UnreadCount =
        UnreadCount(
            jdbc.sql("SELECT count(*) FROM core.notification WHERE users_id = :userId AND read_at IS NULL")
                .param("userId", userId).query(Long::class.java).single()
        )

    @Transactional
    fun markRead(userId: Int, id: Long) {
        val updated = jdbc.sql(
            "UPDATE core.notification SET read_at = COALESCE(read_at, now()) WHERE notification_id = :id AND users_id = :userId"
        ).param("id", id).param("userId", userId).update()
        if (updated == 0) throw NotFoundException("Aviso não encontrado")
    }

    @Transactional
    fun markAllRead(userId: Int): Int =
        jdbc.sql("UPDATE core.notification SET read_at = now() WHERE users_id = :userId AND read_at IS NULL")
            .param("userId", userId).update()

    @Transactional
    fun preference(userId: Int): NotificationPreferenceResponse {
        createDefaultPreference(userId)
        return jdbc.sql(
            "SELECT min_score, in_app_enabled, email_enabled, email_frequency FROM core.notification_preference WHERE users_id = :userId"
        ).param("userId", userId).query { rs, _ ->
            NotificationPreferenceResponse(
                minScore = rs.getBigDecimal("min_score"),
                inAppEnabled = rs.getBoolean("in_app_enabled"),
                emailEnabled = rs.getBoolean("email_enabled"),
                emailFrequency = EmailFrequency.valueOf(rs.getString("email_frequency"))
            )
        }.single()
    }

    @Transactional
    fun updatePreference(userId: Int, request: NotificationPreferenceRequest): NotificationPreferenceResponse {
        createDefaultPreference(userId)
        jdbc.sql(
            """
            UPDATE core.notification_preference
               SET min_score = COALESCE(:minScore, min_score),
                   in_app_enabled = COALESCE(CAST(:inApp AS boolean), in_app_enabled),
                   email_enabled = COALESCE(CAST(:email AS boolean), email_enabled),
                   email_frequency = COALESCE(CAST(:frequency AS text), email_frequency)
             WHERE users_id = :userId
            """
        )
            .param("minScore", request.minScore)
            .param("inApp", request.inAppEnabled)
            .param("email", request.emailEnabled)
            .param("frequency", request.emailFrequency?.name)
            .param("userId", userId)
            .update()
        return preference(userId)
    }

    @Transactional
    fun disableEmail(userId: Int) {
        jdbc.sql("UPDATE core.notification_preference SET email_enabled = false WHERE users_id = :userId")
            .param("userId", userId).update()
    }
}
