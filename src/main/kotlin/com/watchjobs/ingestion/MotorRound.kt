package com.watchjobs.ingestion

import com.watchjobs.ingestion.ondemand.OnDemandTaskProcessor
import com.watchjobs.ingestion.pipeline.IngestionPipeline
import com.watchjobs.ingestion.pipeline.RoundOptions
import com.watchjobs.ingestion.pipeline.RoundSummary
import com.watchjobs.notification.EmailNotifier
import com.watchjobs.notification.MatchingBatchService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// Uma rodada completa do motor (3.6.1): fila da busca sob demanda primeiro, depois os alvos
// agendados (passos 0 a 7 e 10), e por fim notas em lote (8), avisos e e-mails (9).
@Service
class MotorRound(
    private val onDemand: OnDemandTaskProcessor,
    private val pipeline: IngestionPipeline,
    private val batch: MatchingBatchService,
    private val email: EmailNotifier
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun run(options: RoundOptions): RoundSummary {
        val roundKey = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"))
        val onDemandResults = runCatching { onDemand.process() }
            .onFailure { log.error("Fila da busca sob demanda falhou: {}", it.message) }
            .getOrDefault(emptyList())
        val summary = pipeline.runRound(options)
        runCatching { batch.run(roundKey) }.onFailure { log.error("Matching em lote falhou: {}", it.message) }
        runCatching { email.run() }.onFailure { log.error("Envio de e-mails falhou: {}", it.message) }
        return summary.copy(targets = onDemandResults + summary.targets)
    }
}
