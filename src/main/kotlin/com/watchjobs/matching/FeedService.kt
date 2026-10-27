package com.watchjobs.matching

import com.watchjobs.exception.NotFoundException
import com.watchjobs.jobs.JobCorpusQueries
import com.watchjobs.jobs.JobCorpusService
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

// Feed por nota (3.7): o cálculo é sob demanda. Vagas sem nota para o profile_version atual são
// pré-filtradas em SQL, pontuadas em Kotlin e gravadas; o resto só é lido.
@Service
class FeedService(
    private val store: MatchStore,
    private val queries: JobCorpusQueries,
    private val jdbc: JdbcClient,
    private val mapper: ObjectMapper
) {
    @Transactional
    fun ensureScores(userId: Int, onlyIds: Collection<Long>? = null): Int {
        val (profile, version) = store.profile(userId)
        val candidates = store.unscoredCandidates(userId, version, profile, onlyIds)
        store.save(userId, version, candidates.map { (job, expires) -> ScoredJob(job, expires, MatchingEngine.score(profile, job)) })
        return candidates.size
    }

    @Transactional
    fun feed(userId: Int, filter: FeedFilter): FeedResponse {
        ensureScores(userId)
        val version = store.profile(userId).version
        val page = filter.page.coerceAtLeast(0)
        val size = filter.size.coerceIn(1, JobCorpusService.MAX_PAGE_SIZE)
        val params = mutableMapOf<String, Any?>()
        val where = filters(filter, params)
        val order = when (filter.sort) {
            FeedSort.SCORE -> "m.score_total DESC, $DATE DESC"
            FeedSort.SCORE_ASC -> "m.score_total ASC, $DATE DESC"
            FeedSort.DATE -> "$DATE DESC, m.score_total DESC"
            FeedSort.DATE_ASC -> "$DATE ASC, m.score_total DESC"
            FeedSort.SALARY_DESC -> "(COALESCE(j.salary_max, j.salary_min) * $PERIOD_FACTOR) DESC NULLS LAST, m.score_total DESC"
            FeedSort.SALARY_ASC -> "(COALESCE(j.salary_min, j.salary_max) * $PERIOD_FACTOR) ASC NULLS LAST, m.score_total DESC"
        }
        val total = jdbc.sql("SELECT count(*) $FROM $BASE_WHERE $where")
            .params(params).param("userId", userId).param("version", version)
            .query(Long::class.java).single()
        val items = select(userId, version, where, params, order, size, page.toLong() * size)
        return FeedResponse(items, page, size, total, version)
    }

    // Todos os filtros são opcionais; lista vazia não filtra.
    private fun filters(f: FeedFilter, params: MutableMap<String, Any?>): String {
        val w = StringBuilder()
        f.q?.trim()?.takeIf { it.isNotEmpty() }?.let {
            w.append(" AND (j.title ILIKE :q ESCAPE '\\' OR j.organization_name ILIKE :q ESCAPE '\\' OR j.description_snippet ILIKE :q ESCAPE '\\')")
            params["q"] = "%" + it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        }
        if (f.workModes.isNotEmpty()) { w.append(" AND j.work_mode IN (:workModes)"); params["workModes"] = f.workModes.map { it.name } }
        if (f.levels.isNotEmpty()) { w.append(" AND j.experience_level IN (:levels)"); params["levels"] = f.levels.map { it.name } }
        if (f.jobTypes.isNotEmpty()) { w.append(" AND j.job_type IN (:jobTypes)"); params["jobTypes"] = f.jobTypes.map { it.name } }
        if (f.roleFamilies.isNotEmpty()) { w.append(" AND j.role_family IN (:roleFamilies)"); params["roleFamilies"] = f.roleFamilies }
        if (f.countries.isNotEmpty()) { w.append(" AND j.country_code IN (:countries)"); params["countries"] = f.countries.map { it.uppercase() } }
        if (f.states.isNotEmpty()) { w.append(" AND l.state IN (:states)"); params["states"] = f.states }
        if (f.cities.isNotEmpty()) { w.append(" AND l.city IN (:cities)"); params["cities"] = f.cities }
        if (f.companies.isNotEmpty()) { w.append(" AND j.organization_name IN (:companies)"); params["companies"] = f.companies }
        // Faixa em reais por mês: vaga sem salário fica de fora quando há limite.
        if (f.salaryMin != null || f.salaryMax != null) {
            w.append(" AND (j.salary_currency IS NULL OR j.salary_currency = 'BRL')")
            f.salaryMin?.let { w.append(" AND COALESCE(j.salary_max, j.salary_min) * $PERIOD_FACTOR >= :salaryMin"); params["salaryMin"] = it }
            f.salaryMax?.let { w.append(" AND COALESCE(j.salary_min, j.salary_max) * $PERIOD_FACTOR <= :salaryMax"); params["salaryMax"] = it }
        }
        val skillIds = f.skillIds.distinct()
        if (skillIds.isNotEmpty()) {
            val matching = """
                FROM discovery.discovered_job_skill js
                WHERE js.discovered_job_id = j.discovered_job_id AND js.discovered_at = j.discovered_at AND js.skill_id IN (:skillIds)
            """
            params["skillIds"] = skillIds
            if (f.skillMatch == SkillMatch.ALL) {
                w.append(" AND (SELECT count(DISTINCT js.skill_id) $matching) = :skillCount")
                params["skillCount"] = skillIds.size.toLong()
            } else {
                w.append(" AND EXISTS (SELECT 1 $matching)")
            }
        }
        f.minScore?.let { w.append(" AND m.score_total >= :minScore"); params["minScore"] = it }
        return w.toString()
    }

    // Vagas escolhidas (busca sob demanda), já com nota, da maior para a menor.
    @Transactional
    fun scoredItems(userId: Int, ids: Collection<Long>, limit: Int = 50): List<FeedItem> {
        if (ids.isEmpty()) return emptyList()
        ensureScores(userId, ids)
        val version = store.profile(userId).version
        return select(
            userId, version, " AND j.discovered_job_id IN (:ids)", mapOf("ids" to ids.distinct()),
            "m.score_total DESC, COALESCE(j.posted_at, j.discovered_at) DESC", limit, 0
        )
    }

    private fun select(
        userId: Int,
        version: Int,
        extraWhere: String,
        params: Map<String, Any?>,
        order: String,
        limit: Int,
        offset: Long
    ): List<FeedItem> {
        val columns = JobCorpusQueries.SELECT.substringBefore("FROM discovery.discovered_job j")
        return jdbc.sql(
            """
            $columns, m.score_total, CAST(m.breakdown AS text) AS breakdown
            $FROM
            $BASE_WHERE $extraWhere
            ORDER BY $order, j.discovered_job_id DESC
            LIMIT :limit OFFSET :offset
            """
        ).params(params).param("userId", userId).param("version", version).param("limit", limit).param("offset", offset)
            .query { rs, _ ->
                FeedItem(
                    job = queries.map(rs).response,
                    score = rs.getBigDecimal("score_total").toDouble(),
                    highlights = highlights(rs.getString("breakdown"))
                )
            }.list().let(::withSkills)
    }

    // Skills da vaga (obrigatórias primeiro) para o cartão, numa consulta só para a página.
    private fun withSkills(items: List<FeedItem>): List<FeedItem> {
        if (items.isEmpty()) return items
        val skills = jdbc.sql(
            """
            SELECT js.discovered_job_id, s.skill_name
              FROM discovery.discovered_job_skill js
              JOIN core.skill s ON s.skill_id = js.skill_id
             WHERE js.discovered_job_id IN (:ids)
             ORDER BY js.is_required DESC, s.skill_name
            """
        ).param("ids", items.map { it.job.id }).query { rs, _ -> rs.getLong(1) to rs.getString(2) }.list()
            .groupBy({ it.first }, { it.second })
        return items.map { it.copy(skills = skills[it.job.id].orEmpty().take(MAX_CARD_SKILLS)) }
    }

    // Nota de uma vaga para o usuário; calcula e grava se ainda não houver para a versão atual.
    @Transactional
    fun matchFor(userId: Int, jobId: Long): MatchResult? {
        val version = store.profile(userId).version
        return store.match(userId, version, jobId) ?: run {
            ensureScores(userId, listOf(jobId))
            store.match(userId, version, jobId)
        }
    }

    @Transactional
    fun refresh(userId: Int): RefreshResponse {
        val version = store.profile(userId).version
        store.deleteVersion(userId, version)
        return RefreshResponse(ensureScores(userId), version)
    }

    @Transactional
    fun interact(userId: Int, jobId: Long, type: InteractionType) {
        if (queries.find(userId, jobId) == null) throw NotFoundException("Vaga não encontrada")
        jdbc.sql(
            """
            INSERT INTO core.usersjobinteraction (users_id, discovered_job_id, interaction_type)
            VALUES (:userId, :jobId, :type)
            ON CONFLICT (users_id, discovered_job_id, interaction_type) DO UPDATE SET updated_at = now()
            """
        ).param("userId", userId).param("jobId", jobId).param("type", type.name).update()
    }

    @Transactional
    fun removeInteraction(userId: Int, jobId: Long, type: InteractionType) {
        jdbc.sql(
            "DELETE FROM core.usersjobinteraction WHERE users_id = :userId AND discovered_job_id = :jobId AND interaction_type = :type"
        ).param("userId", userId).param("jobId", jobId).param("type", type.name).update()
    }

    // Os critérios que mais renderam, para o cartão do feed.
    private fun highlights(breakdown: String?): List<String> =
        runCatching {
            mapper.readValue(breakdown, MatchResult::class.java).criteria
                .filter { it.applicable && it.value >= 0.5 }
                .sortedByDescending { it.points }
                .take(3)
                .map { it.detail }
        }.getOrDefault(emptyList())

    companion object {
        private const val MAX_CARD_SKILLS = 6
        private const val DATE = "COALESCE(j.posted_at, j.discovered_at)"

        // Salário em reais por mês, a partir do período informado pela fonte.
        private const val PERIOD_FACTOR =
            "(CASE j.salary_period WHEN 'YEAR' THEN 1.0 / 12 WHEN 'HOUR' THEN 168 WHEN 'DAY' THEN 21 WHEN 'WEEK' THEN 4.345 ELSE 1 END)"

        private const val FROM = """
            FROM discovery.user_job_match m
            JOIN discovery.discovered_job j ON j.discovered_job_id = m.discovered_job_id
            JOIN core.job_source s ON s.job_source_id = j.source_id
            LEFT JOIN core."location" l ON l.location_id = j.location_id
        """

        private const val BASE_WHERE = """
            WHERE m.users_id = :userId AND m.profile_version = :version
              AND j.corpus_status = 'ACTIVE' AND j.expires_at > now()
              AND NOT EXISTS (SELECT 1 FROM core.usersjobinteraction i
                               WHERE i.users_id = :userId AND i.discovered_job_id = j.discovered_job_id
                                 AND i.interaction_type IN ('DISMISSED', 'HIDDEN'))
        """
    }
}
