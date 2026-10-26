package com.watchjobs.ingestion.connector

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration

@Component
class RestClientHttpGateway(
    @Value("\${app.ingestion.http-timeout-seconds:5}") timeoutSeconds: Long,
    @Value("\${app.ingestion.user-agent:WatchJobsBot/1.0}") userAgent: String
) : HttpGateway {
    private val client: RestClient = RestClient.builder()
        .requestFactory(
            JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build()
            ).apply { setReadTimeout(Duration.ofSeconds(timeoutSeconds)) }
        )
        .defaultHeader("User-Agent", userAgent)
        .defaultHeader("Accept", "application/json")
        .build()

    override fun get(uri: URI, headers: Map<String, String>): String =
        client.get()
            .uri(uri)
            .headers { h -> headers.forEach { (name, value) -> h.set(name, value) } }
            .retrieve()
            .body(String::class.java)
            .orEmpty()
}
