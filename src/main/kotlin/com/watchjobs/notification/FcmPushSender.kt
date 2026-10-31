package com.watchjobs.notification

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64

enum class PushResult { SENT, INVALID_TOKEN, FAILED }

interface PushSender {
    val enabled: Boolean
    fun send(token: String, title: String, body: String?, data: Map<String, String>): PushResult
}

// Push pelo Firebase Cloud Messaging (API HTTP v1). Sem FCM_PROJECT_ID e FCM_CREDENTIALS_JSON, fica desligado.
// FCM_CREDENTIALS_JSON aceita o JSON da conta de serviço puro ou em Base64.
@Component
class FcmPushSender(
    private val mapper: ObjectMapper,
    @Value("\${app.push.fcm.project-id:}") private val projectId: String,
    @Value("\${app.push.fcm.credentials:}") private val credentials: String
) : PushSender {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private var accessToken: String? = null
    private var accessExpiresAt: Instant = Instant.EPOCH

    private data class ServiceAccount(val clientEmail: String, val privateKey: PrivateKey, val tokenUri: String)

    private val account: ServiceAccount? by lazy {
        if (projectId.isBlank() || credentials.isBlank()) return@lazy null
        runCatching {
            val raw = credentials.trim().let { if (it.startsWith("{")) it else String(Base64.getDecoder().decode(it), StandardCharsets.UTF_8) }
            val node = mapper.readValue(raw, Map::class.java)
            val pem = (node["private_key"] as String)
                .replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replace("\\s".toRegex(), "")
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)))
            ServiceAccount(node["client_email"] as String, key, node["token_uri"] as? String ?: "https://oauth2.googleapis.com/token")
        }.onFailure { log.error("Credenciais do FCM inválidas: {}", it.message) }.getOrNull()
    }

    override val enabled: Boolean
        get() = account != null

    override fun send(token: String, title: String, body: String?, data: Map<String, String>): PushResult {
        val bearer = runCatching { bearer() }.onFailure { log.warn("Token OAuth do FCM falhou: {}", it.message) }.getOrNull()
            ?: return PushResult.FAILED
        val message = mapOf(
            "message" to mapOf(
                "token" to token,
                "notification" to mapOf("title" to title, "body" to (body ?: "")),
                "data" to data,
                "android" to mapOf("priority" to "HIGH", "notification" to mapOf("channel_id" to "watchjobs_avisos"))
            )
        )
        val request = HttpRequest.newBuilder(URI("https://fcm.googleapis.com/v1/projects/$projectId/messages:send"))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer $bearer")
            .header("Content-Type", "application/json; charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(message)))
            .build()
        return try {
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            when {
                response.statusCode() in 200..299 -> PushResult.SENT
                response.statusCode() == 404 || "UNREGISTERED" in response.body() || "INVALID_ARGUMENT" in response.body() -> PushResult.INVALID_TOKEN
                else -> {
                    log.warn("FCM respondeu {}: {}", response.statusCode(), response.body().take(300))
                    PushResult.FAILED
                }
            }
        } catch (ex: Exception) {
            log.warn("Envio de push falhou: {}", ex.message)
            PushResult.FAILED
        }
    }

    // JWT assinado com a chave da conta de serviço, trocado por um access token de 1 h (com folga de 5 min).
    @Synchronized
    private fun bearer(): String {
        val cached = accessToken
        if (cached != null && Instant.now().isBefore(accessExpiresAt.minusSeconds(300))) return cached
        val sa = account ?: error("FCM desligado")
        val now = Instant.now().epochSecond
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
        val claims = encoder.encodeToString(
            mapper.writeValueAsBytes(
                mapOf(
                    "iss" to sa.clientEmail,
                    "scope" to "https://www.googleapis.com/auth/firebase.messaging",
                    "aud" to sa.tokenUri,
                    "iat" to now,
                    "exp" to now + 3600
                )
            )
        )
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(sa.privateKey)
            update("$header.$claims".toByteArray())
        }.sign()
        val assertion = "$header.$claims.${encoder.encodeToString(signature)}"
        val form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8) +
            "&assertion=" + URLEncoder.encode(assertion, StandardCharsets.UTF_8)
        val response = http.send(
            HttpRequest.newBuilder(URI(sa.tokenUri)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
            HttpResponse.BodyHandlers.ofString()
        )
        check(response.statusCode() in 200..299) { "OAuth ${response.statusCode()}" }
        val node = mapper.readValue(response.body(), Map::class.java)
        val token = node["access_token"] as String
        accessToken = token
        accessExpiresAt = Instant.now().plusSeconds((node["expires_in"] as? Number)?.toLong() ?: 3600)
        return token
    }
}
