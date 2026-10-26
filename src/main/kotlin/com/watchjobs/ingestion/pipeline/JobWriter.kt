package com.watchjobs.ingestion.pipeline

import com.watchjobs.ingestion.store.CatalogStore
import com.watchjobs.ingestion.store.DiscoveredJobStore
import com.watchjobs.ingestion.store.JobSeen
import com.watchjobs.ingestion.store.JobSeenStore
import com.watchjobs.ingestion.normalize.NormalizedJob
import org.springframework.stereotype.Component

enum class WriteOutcome { NEW, UNCHANGED, CHANGED, EXPIRED }

// Passos 4 e 5 do pipeline (3.6.1): deduplica pelo job_seen e grava no corpus.
// Chamado dentro de uma transação por vaga, para uma vaga ruim não derrubar o alvo inteiro.
@Component
class JobWriter(
    private val jobs: DiscoveredJobStore,
    private val seen: JobSeenStore,
    private val catalog: CatalogStore
) {
    fun write(job: NormalizedJob, organizationId: Long?, runId: Long): WriteOutcome {
        val existing = seen.find(job.sourceId, job.externalId)
        return when {
            existing == null -> {
                val key = jobs.insert(job, organizationId ?: catalog.organizationId(job.organizationName), locationOf(job))
                jobs.insertSkills(key, job.skills)
                seen.insert(job.sourceId, job.externalId, key, job.sourceCompanyId, job.contentHash, runId)
                WriteOutcome.NEW
            }
            // Já cumpriu os 30 dias: não volta como nova enquanto a fonte continuar listando.
            existing.expiredAt != null -> WriteOutcome.EXPIRED
            existing.contentHash == job.contentHash -> {
                if (!jobs.touch(existing.key)) return expire(existing)
                seen.markSeen(existing, runId)
                WriteOutcome.UNCHANGED
            }
            else -> {
                if (!jobs.update(existing.key, job, organizationId ?: catalog.organizationId(job.organizationName), locationOf(job))) {
                    return expire(existing)
                }
                jobs.replaceSkills(existing.key, job.skills)
                seen.markChanged(existing, job.contentHash, runId)
                WriteOutcome.CHANGED
            }
        }
    }

    // Passo 6: o que não veio num retrato completo vira PROBABLY_CLOSED. Devolve quantas fechou.
    fun closeMissing(sourceCompanyId: Long, fetchedExternalIds: Set<String>): Int =
        seen.findOpenByCompany(sourceCompanyId)
            .filter { it.externalId !in fetchedExternalIds }
            .count { open ->
                seen.markClosed(open)
                jobs.markProbablyClosed(open.key)
            }

    private fun expire(existing: JobSeen): WriteOutcome {
        seen.markExpired(existing)
        return WriteOutcome.EXPIRED
    }

    private fun locationOf(job: NormalizedJob): Long? = catalog.locationId(job.countryCode, job.state, job.city)
}
