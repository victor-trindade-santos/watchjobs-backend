package com.watchjobs.documents

import com.watchjobs.exception.ServiceUnavailableException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

data class GeneratedText(val content: String, val model: String, val promptTokens: Int?, val completionTokens: Int?)

// Geração de texto por LLM. Isolada em uma interface para trocar de provedor e para os testes usarem um falso.
interface TextGenerator {
    val enabled: Boolean
    fun generate(system: String, user: String, maxTokens: Int): GeneratedText
}

// OpenAI Chat Completions. Sem OPENAI_API_KEY, fica desligado e a API responde 503 com uma mensagem clara.
// Falhas temporárias (429, 5xx, rede) têm uma nova tentativa; depois disso, 503.
@Component
class OpenAiTextGenerator(
    private val mapper: ObjectMapper,
    @Value("\${app.ai.openai.api-key:}") private val apiKey: String,
    @Value("\${app.ai.openai.model:gpt-4o-mini}") private val model: String,
    @Value("\${app.ai.openai.base-url:https://api.openai.com/v1}") private val baseUrl: String,
    @Value("\${app.ai.openai.timeout-seconds:60}") private val timeoutSeconds: Long,
    @Value("\${app.ai.openai.temperature:0.4}") private val temperature: Double
) : TextGenerator {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    override val enabled: Boolean
        get() = apiKey.isNotBlank()

    override fun generate(system: String, user: String, maxTokens: Int): GeneratedText {
        if (!enabled) throw ServiceUnavailableException("A geração com IA não está configurada no servidor (OPENAI_API_KEY).")
        val body = mapper.writeValueAsString(
            mapOf(
                "model" to model,
                "temperature" to temperature,
                "max_tokens" to maxTokens,
                "messages" to listOf(mapOf("role" to "system", "content" to system), mapOf("role" to "user", "content" to user))
            )
        )
        var lastError = "sem resposta"
        repeat(ATTEMPTS) { attempt ->
            if (attempt > 0) Thread.sleep(RETRY_DELAY_MS)
            val request = HttpRequest.newBuilder(URI("${baseUrl.trimEnd('/')}/chat/completions"))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            try {
                val response = http.send(request, HttpResponse.BodyHandlers.ofString())
                val status = response.statusCode()
                when {
                    status in 200..299 -> return parse(response.body())
                    status == 429 || status >= 500 -> lastError = "HTTP $status"
                    else -> {
                        log.warn("OpenAI recusou a requisição: HTTP {} {}", status, response.body().take(300))
                        throw ServiceUnavailableException("O serviço de IA recusou a requisição (HTTP $status). Tente novamente mais tarde.")
                    }
                }
            } catch (ex: HttpTimeoutException) {
                // Esperar de novo dobraria o tempo do usuário: tempo esgotado não tem nova tentativa.
                log.warn("OpenAI excedeu {}s", timeoutSeconds)
                throw ServiceUnavailableException("O serviço de IA demorou demais para responder. Tente gerar novamente.")
            } catch (ex: IOException) {
                lastError = ex.message ?: "falha de rede"
            }
        }
        log.warn("OpenAI indisponível após {} tentativas: {}", ATTEMPTS, lastError)
        throw ServiceUnavailableException("O serviço de IA não respondeu ($lastError). Tente gerar novamente em instantes.")
    }

    @Suppress("UNCHECKED_CAST")
    private fun parse(json: String): GeneratedText {
        val root = mapper.readValue(json, Map::class.java) as Map<String, Any?>
        val choice = (root["choices"] as? List<Map<String, Any?>>)?.firstOrNull()
        val content = ((choice?.get("message") as? Map<String, Any?>)?.get("content") as? String)?.trim()
        if (content.isNullOrBlank()) throw ServiceUnavailableException("O serviço de IA devolveu uma resposta vazia. Tente gerar novamente.")
        val usage = root["usage"] as? Map<String, Any?>
        return GeneratedText(
            content = content,
            model = root["model"] as? String ?: model,
            promptTokens = (usage?.get("prompt_tokens") as? Number)?.toInt(),
            completionTokens = (usage?.get("completion_tokens") as? Number)?.toInt()
        )
    }

    companion object {
        private const val ATTEMPTS = 2
        private const val RETRY_DELAY_MS = 1500L
    }
}
