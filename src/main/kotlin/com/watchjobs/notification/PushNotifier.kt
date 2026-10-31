package com.watchjobs.notification

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service

// Push no Android no fim da rodada: avisos do último dia ainda não enviados, para quem tem push ligado e aparelho registrado.
@Service
class PushNotifier(
    private val jdbc: JdbcClient,
    private val sender: PushSender
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private data class Pending(val id: Long, val userId: Int, val type: String, val title: String, val body: String?, val payload: String)

    fun run(): Int {
        if (!sender.enabled) return 0
        val pending = jdbc.sql(
            """
            SELECT n.notification_id, n.users_id, n.notification_type, n.title, n.body, CAST(n.payload AS text) AS payload
              FROM core.notification n
              JOIN core.notification_preference p ON p.users_id = n.users_id
             WHERE n.pushed_at IS NULL AND p.push_enabled AND n.read_at IS NULL
               AND n.created_at >= now() - interval '1 day'
               AND EXISTS (SELECT 1 FROM core.push_device d WHERE d.users_id = n.users_id)
             ORDER BY n.created_at
             LIMIT 500
            """
        ).query { rs, _ ->
            Pending(
                rs.getLong("notification_id"), rs.getInt("users_id"), rs.getString("notification_type"),
                rs.getString("title"), rs.getString("body"), rs.getString("payload")
            )
        }.list()
        var sent = 0
        pending.groupBy { it.userId }.forEach { (userId, items) ->
            val tokens = jdbc.sql("SELECT token FROM core.push_device WHERE users_id = :userId")
                .param("userId", userId).query(String::class.java).list().filterNotNull()
            items.forEach { item ->
                val results = tokens.map { token ->
                    sender.send(token, item.title, item.body, mapOf("notificationId" to item.id.toString(), "type" to item.type, "payload" to item.payload)).also {
                        if (it == PushResult.INVALID_TOKEN) {
                            jdbc.sql("DELETE FROM core.push_device WHERE token = :token").param("token", token).update()
                        }
                    }
                }
                if (PushResult.FAILED !in results || PushResult.SENT in results) {
                    jdbc.sql("UPDATE core.notification SET pushed_at = now() WHERE notification_id = :id").param("id", item.id).update()
                    if (PushResult.SENT in results) sent++
                }
            }
        }
        log.info("Push: {} avisos enviados", sent)
        return sent
    }
}
