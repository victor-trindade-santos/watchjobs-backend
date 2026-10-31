package com.watchjobs.ingestion.store

import com.watchjobs.ingestion.normalize.NormalizedJob
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

// Vaga já no corpus que pode ser a mesma de outra fonte.
data class GroupCandidate(
    val id: Long,
    val sourceId: Long,
    val externalId: String,
    val jobGroupId: Long?,
    val jobUrl: String,
    val fingerprint: String?,
    val workMode: String?,
    val jobType: String?,
    val countryCode: String?
)

// Regras puras da deduplicação entre fontes (job_group).
object DedupRules {
    // Mesma URL original = mesma vaga. Mesmo cargo + empresa + cidade (fingerprint) só une fontes diferentes
    // e só se modo, contrato e país não se contradisserem: duas vagas da mesma fonte nunca são unidas.
    fun sameJob(job: NormalizedJob, other: GroupCandidate): Boolean {
        if (normalizeUrl(job.jobUrl) == normalizeUrl(other.jobUrl)) return true
        if (job.sourceId == other.sourceId) return false
        if (other.fingerprint == null || job.fingerprint != other.fingerprint) return false
        return compatible(job.workMode, other.workMode) && compatible(job.jobType, other.jobType) &&
            compatible(job.countryCode, other.countryCode)
    }

    private fun compatible(a: String?, b: String?) = a == null || b == null || a == b

    // Sem esquema, "www.", barra final, fragmento e parâmetros de rastreio (utm_*, gh_src, source).
    fun normalizeUrl(url: String): String {
        val noFragment = url.substringBefore('#').trim()
        val base = noFragment.substringBefore('?')
        val query = noFragment.substringAfter('?', "").split('&')
            .filter { it.isNotBlank() && !it.lowercase().let { p -> p.startsWith("utm_") || p.startsWith("gh_src") || p.startsWith("source=") || p.startsWith("ref=") } }
            .sorted()
        val host = base.substringAfter("://").removePrefix("www.").trimEnd('/').lowercase()
        return if (query.isEmpty()) host else "$host?${query.joinToString("&")}"
    }
}

// Grupos de vagas iguais entre fontes. A representante (canonical) é a da fonte mais confiável (menor
// trust_rank); empate vai para a que tem salário e descrição, depois a vista mais recentemente.
@Repository
class JobGroupStore(private val jdbc: JdbcClient) {

    fun assign(key: CorpusKey, job: NormalizedJob): Long? {
        val candidates = jdbc.sql(
            """
            SELECT j.discovered_job_id, j.source_id, j.external_id, j.job_group_id, j.job_url, j.fingerprint,
                   j.work_mode, j.job_type, j.country_code
              FROM discovery.discovered_job j
             WHERE (j.fingerprint = :fingerprint OR j.job_url = :url)
               AND j.corpus_status = 'ACTIVE' AND j.expires_at > now()
               AND NOT (j.discovered_job_id = :id AND j.discovered_at = :at)
             LIMIT 50
            """
        ).param("fingerprint", job.fingerprint).param("url", job.jobUrl).param("id", key.id).param("at", key.discoveredAt)
            .query { rs, _ ->
                GroupCandidate(
                    rs.getLong("discovered_job_id"), rs.getLong("source_id"), rs.getString("external_id"),
                    (rs.getObject("job_group_id") as Number?)?.toLong(), rs.getString("job_url"), rs.getString("fingerprint")?.trim(),
                    rs.getString("work_mode"), rs.getString("job_type"), rs.getString("country_code")
                )
            }.list()
        val matches = candidates.filter { DedupRules.sameJob(job, it) }
        if (matches.isEmpty()) {
            clearGroup(key)
            return null
        }
        val groupId = matches.firstNotNullOfOrNull { it.jobGroupId } ?: createGroup(job.fingerprint)
        val members = matches.filter { it.jobGroupId != groupId }.map { it.id } + key.id
        jdbc.sql("UPDATE discovery.discovered_job SET job_group_id = :groupId WHERE discovered_job_id IN (:ids)")
            .param("groupId", groupId).param("ids", members).update()
        refreshCanonical(groupId)
        return groupId
    }

    // Vaga que deixou de bater com o grupo (mudou de cargo, por exemplo) sai dele.
    private fun clearGroup(key: CorpusKey) {
        val previous = jdbc.sql(
            "UPDATE discovery.discovered_job SET job_group_id = NULL WHERE discovered_job_id = :id AND discovered_at = :at AND job_group_id IS NOT NULL RETURNING job_group_id"
        ).param("id", key.id).param("at", key.discoveredAt).query(Long::class.java).list().firstOrNull()
        previous?.let(::refreshCanonical)
    }

    private fun createGroup(fingerprint: String): Long =
        jdbc.sql(
            """
            INSERT INTO discovery.job_group (fingerprint) VALUES (:fingerprint)
            ON CONFLICT (fingerprint) DO UPDATE SET last_seen_at = now()
            RETURNING job_group_id
            """
        ).param("fingerprint", fingerprint).query(Long::class.java).single()

    fun refreshCanonical(groupId: Long) {
        jdbc.sql(
            """
            UPDATE discovery.job_group g
               SET last_seen_at = now(),
                   canonical_discovered_job_id = (
                       SELECT j.discovered_job_id
                         FROM discovery.discovered_job j
                         JOIN core.job_source s ON s.job_source_id = j.source_id
                        WHERE j.job_group_id = g.job_group_id AND j.corpus_status = 'ACTIVE' AND j.expires_at > now()
                        ORDER BY s.trust_rank,
                                 (j.salary_min IS NOT NULL OR j.salary_max IS NOT NULL) DESC,
                                 (j.full_description IS NOT NULL) DESC,
                                 j.last_seen_at DESC, j.discovered_job_id
                        LIMIT 1)
             WHERE g.job_group_id = :groupId
            """
        ).param("groupId", groupId).update()
    }

    fun groupOf(id: Long): Long? =
        jdbc.sql("SELECT job_group_id FROM discovery.discovered_job WHERE discovered_job_id = :id")
            .param("id", id).query(Long::class.java).list().firstOrNull()

    companion object {
        // Filtro da busca: esconde as cópias de um grupo enquanto a representante estiver ativa (alias "j").
        const val CANONICAL_ONLY = """
            AND NOT EXISTS (
                SELECT 1 FROM discovery.job_group g
                  JOIN discovery.discovered_job c ON c.discovered_job_id = g.canonical_discovered_job_id
                 WHERE g.job_group_id = j.job_group_id AND c.discovered_job_id <> j.discovered_job_id
                   AND c.corpus_status = 'ACTIVE' AND c.expires_at > now())
        """
    }
}
