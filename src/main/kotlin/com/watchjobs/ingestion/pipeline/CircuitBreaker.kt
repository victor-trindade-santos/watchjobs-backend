package com.watchjobs.ingestion.pipeline

import java.time.Duration
import java.time.OffsetDateTime
import kotlin.math.min

enum class CircuitState { CLOSED, OPEN, HALF_OPEN }

// Estado do circuito de uma fonte, como está em core.job_source.
data class Circuit(
    val state: CircuitState = CircuitState.CLOSED,
    val failures: Int = 0,
    val openedAt: OffsetDateTime? = null,
    val nextAttemptAt: OffsetDateTime? = null
)

data class CircuitPolicy(
    val failureThreshold: Int = 5,
    val recovery: Duration = Duration.ofMinutes(60),
    val maxRecovery: Duration = Duration.ofHours(24)
)

// Circuit breaker por fonte (regra pura, sem banco):
// CLOSED conta falhas; no limite abre (OPEN) e a fonte descansa; vencido o descanso, a próxima rodada
// faz uma tentativa de teste (HALF_OPEN). Sucesso fecha; falha reabre com descanso dobrado (até o teto).
object CircuitBreaker {
    fun canAttempt(circuit: Circuit, now: OffsetDateTime): Boolean =
        circuit.state != CircuitState.OPEN || circuit.nextAttemptAt == null || !circuit.nextAttemptAt.isAfter(now)

    // Antes de coletar: circuito aberto e vencido vira HALF_OPEN (só uma tentativa de teste).
    fun beforeAttempt(circuit: Circuit, now: OffsetDateTime): Circuit =
        if (circuit.state == CircuitState.OPEN && canAttempt(circuit, now)) circuit.copy(state = CircuitState.HALF_OPEN) else circuit

    fun onSuccess(): Circuit = Circuit()

    fun onFailure(circuit: Circuit, now: OffsetDateTime, policy: CircuitPolicy): Circuit {
        val failures = circuit.failures + 1
        val shouldOpen = circuit.state == CircuitState.HALF_OPEN || failures >= policy.failureThreshold
        if (!shouldOpen) return circuit.copy(state = CircuitState.CLOSED, failures = failures)
        val reopenings = (failures - policy.failureThreshold).coerceIn(0, 10)
        val wait = Duration.ofSeconds(min(policy.recovery.seconds * (1L shl reopenings), policy.maxRecovery.seconds))
        return Circuit(CircuitState.OPEN, failures, now, now.plus(wait))
    }
}
