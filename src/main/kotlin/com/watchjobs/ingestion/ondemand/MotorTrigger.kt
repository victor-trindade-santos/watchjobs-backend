package com.watchjobs.ingestion.ondemand

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient

// Acorda o motor para atender a fila. Em produção chama o workflow_dispatch do GitHub; sem token
// (local), não faz nada: o pedido fica na fila até o motor ser rodado na mão.
fun interface MotorTrigger {
    fun trigger()
}

class GitHubMotorTrigger(
    private val token: String,
    private val repository: String,
    private val workflow: String,
    private val ref: String
) : MotorTrigger {
    private val log = LoggerFactory.getLogger(javaClass)
    private val client = RestClient.builder().baseUrl("https://api.github.com").build()

    // Falhar aqui não perde o pedido: a tarefa continua na fila para a próxima rodada agendada.
    override fun trigger() {
        try {
            client.post()
                .uri("/repos/{repo}/actions/workflows/{workflow}/dispatches", repository, workflow)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("ref" to ref))
                .retrieve()
                .toBodilessEntity()
        } catch (ex: Exception) {
            log.warn("Não foi possível disparar o motor no GitHub: {}", ex.javaClass.simpleName)
        }
    }
}

@Configuration
class MotorTriggerConfig {
    @Bean
    @ConditionalOnExpression("'\${app.motor.github-token:}' != ''")
    fun gitHubMotorTrigger(
        @Value("\${app.motor.github-token}") token: String,
        @Value("\${app.motor.github-repo}") repository: String,
        @Value("\${app.motor.workflow:ingestion.yml}") workflow: String,
        @Value("\${app.motor.ref:main}") ref: String
    ): MotorTrigger = GitHubMotorTrigger(token, repository, workflow, ref)

    @Bean
    @ConditionalOnMissingBean(MotorTrigger::class)
    fun noopMotorTrigger(): MotorTrigger = MotorTrigger { }
}
