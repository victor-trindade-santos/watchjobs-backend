package com.watchjobs.analytics

import com.watchjobs.board.BoardColumn
import com.watchjobs.board.BoardColumnMapper
import com.watchjobs.board.BoardService
import com.watchjobs.board.EventType
import com.watchjobs.entity.enums.SavedJobStatus
import com.watchjobs.exception.BusinessException
import com.watchjobs.security.AppUserDetails
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.OffsetDateTime

enum class FunnelStage { SAVED, APPLIED, IN_PROCESS, INTERVIEW, OFFER, ACCEPTED }

data class FunnelStep(val stage: FunnelStage, val label: String, val count: Int, val conversionFromPrevious: Double?)

data class StageCount(val column: BoardColumn, val label: String, val count: Int)

data class FamilyCount(val roleFamily: String?, val label: String, val saved: Int, val applied: Int, val interviews: Int, val offers: Int)

data class PeriodPoint(val start: LocalDate, val saved: Int, val applied: Int, val advanced: Int, val closed: Int, val events: Int)

data class HistoryItem(
    val savedJobId: Long,
    val positionRole: String,
    val organizationName: String,
    val type: EventType,
    val description: String,
    val occurredAt: OffsetDateTime
)

data class AnalyticsTotals(
    val saved: Int,
    val applied: Int,
    val active: Int,
    val interviews: Int,
    val offers: Int,
    val accepted: Int,
    val rejected: Int,
    val withdrawn: Int
)

data class AnalyticsResponse(
    val generatedAt: OffsetDateTime,
    val lastEventAt: OffsetDateTime?,
    val weeks: Int,
    val totals: AnalyticsTotals,
    val funnel: List<FunnelStep>,
    val byStage: List<StageCount>,
    val byRoleFamily: List<FamilyCount>,
    val weekly: List<PeriodPoint>,
    val monthly: List<PeriodPoint>,
    val history: List<HistoryItem>
)

// Uma vaga salva com o ponto mais avançado a que chegou, pelos eventos registrados e pelo estado atual.
data class JobProgress(
    val roleFamily: String?,
    val roleFamilyName: String?,
    val status: SavedJobStatus,
    val stageName: String?,
    val applied: Boolean,
    val inProcess: Boolean,
    val interview: Boolean,
    val offer: Boolean,
    val accepted: Boolean
)

// Regras puras de agregação (testáveis sem banco).
object AnalyticsRules {
    val FUNNEL_LABELS = mapOf(
        FunnelStage.SAVED to "Salvas",
        FunnelStage.APPLIED to "Candidaturas",
        FunnelStage.IN_PROCESS to "Em processo",
        FunnelStage.INTERVIEW to "Entrevistas e testes",
        FunnelStage.OFFER to "Propostas",
        FunnelStage.ACCEPTED to "Aceitas"
    )
    val COLUMN_LABELS = mapOf(
        BoardColumn.SAVED to "Salvas",
        BoardColumn.APPLIED to "Candidatado",
        BoardColumn.IN_REVIEW to "Em análise",
        BoardColumn.INTERVIEW to "Entrevista",
        BoardColumn.TECH_TEST to "Teste técnico",
        BoardColumn.OFFER to "Proposta",
        BoardColumn.CLOSED to "Encerradas"
    )
    private val CLOSED = setOf(SavedJobStatus.ACCEPTED, SavedJobStatus.REJECTED, SavedJobStatus.WITHDRAWN)
    private val SAVED = setOf(SavedJobStatus.SAVED, SavedJobStatus.PLANNING_TO_APPLY)

    fun funnel(jobs: List<JobProgress>): List<FunnelStep> {
        val counts = listOf(
            FunnelStage.SAVED to jobs.size,
            FunnelStage.APPLIED to jobs.count { it.applied },
            FunnelStage.IN_PROCESS to jobs.count { it.inProcess },
            FunnelStage.INTERVIEW to jobs.count { it.interview },
            FunnelStage.OFFER to jobs.count { it.offer },
            FunnelStage.ACCEPTED to jobs.count { it.accepted }
        )
        return counts.mapIndexed { i, (stage, count) ->
            val previous = counts.getOrNull(i - 1)?.second
            FunnelStep(stage, FUNNEL_LABELS.getValue(stage), count, previous?.let { rate(count, it) })
        }
    }

    // Sem candidaturas na etapa anterior não há conversão (null), em vez de 0% enganoso.
    fun rate(count: Int, previous: Int): Double? =
        if (previous == 0) null else Math.round(count * 1000.0 / previous) / 1000.0

    fun byStage(jobs: List<JobProgress>): List<StageCount> {
        val counts = jobs.groupingBy { BoardColumnMapper.columnOf(it.status, it.stageName) }.eachCount()
        return BoardColumn.entries.map { StageCount(it, COLUMN_LABELS.getValue(it), counts[it] ?: 0) }
    }

    fun byRoleFamily(jobs: List<JobProgress>): List<FamilyCount> =
        jobs.groupBy { it.roleFamily }.map { (family, list) ->
            FamilyCount(
                roleFamily = family,
                label = list.firstNotNullOfOrNull { it.roleFamilyName } ?: family?.lowercase()?.replace('_', ' ')
                    ?.replaceFirstChar { it.uppercase() } ?: "Sem área definida",
                saved = list.size,
                applied = list.count { it.applied },
                interviews = list.count { it.interview },
                offers = list.count { it.offer }
            )
        }.sortedWith(compareByDescending<FamilyCount> { it.saved }.thenBy { it.label })

    fun totals(jobs: List<JobProgress>): AnalyticsTotals = AnalyticsTotals(
        saved = jobs.size,
        applied = jobs.count { it.applied },
        active = jobs.count { it.status !in CLOSED && it.status !in SAVED },
        interviews = jobs.count { it.interview },
        offers = jobs.count { it.offer },
        accepted = jobs.count { it.status == SavedJobStatus.ACCEPTED },
        rejected = jobs.count { it.status == SavedJobStatus.REJECTED },
        withdrawn = jobs.count { it.status == SavedJobStatus.WITHDRAWN }
    )

    // Completa com zero as semanas/meses sem movimentação, para o gráfico não "pular" períodos.
    fun fill(starts: List<LocalDate>, points: Map<LocalDate, PeriodPoint>): List<PeriodPoint> =
        starts.map { points[it] ?: PeriodPoint(it, 0, 0, 0, 0, 0) }
}

// Analytics (F09): tudo calculado de core.saved_job + core.application_event, sempre filtrado pelo usuário do token.
@Service
class AnalyticsService(private val jdbc: JdbcClient) {

    @Transactional(readOnly = true)
    fun analytics(userId: Int, weeks: Int, today: LocalDate = LocalDate.now(ZONE)): AnalyticsResponse {
        if (weeks !in 4..52) throw BusinessException("O período deve ter entre 4 e 52 semanas")
        val jobs = progress(userId)
        val weekStart = today.minusDays((today.dayOfWeek.value - 1).toLong())
        val weekStarts = (weeks - 1 downTo 0).map { weekStart.minusWeeks(it.toLong()) }
        val monthStart = today.withDayOfMonth(1)
        val monthStarts = (MONTHS - 1 downTo 0).map { monthStart.minusMonths(it.toLong()) }
        return AnalyticsResponse(
            generatedAt = OffsetDateTime.now(),
            lastEventAt = jdbc.sql("SELECT max(occurred_at) FROM core.application_event WHERE users_id = :userId")
                .param("userId", userId).query(OffsetDateTime::class.java).list().firstOrNull(),
            weeks = weeks,
            totals = AnalyticsRules.totals(jobs),
            funnel = AnalyticsRules.funnel(jobs),
            byStage = AnalyticsRules.byStage(jobs),
            byRoleFamily = AnalyticsRules.byRoleFamily(jobs),
            weekly = AnalyticsRules.fill(weekStarts, series(userId, "week", weekStarts.first())),
            monthly = AnalyticsRules.fill(monthStarts, series(userId, "month", monthStarts.first())),
            history = history(userId)
        )
    }

    private fun progress(userId: Int): List<JobProgress> =
        jdbc.sql(
            """
            SELECT sj.status, sj.role_family, rf.name AS role_family_name, max(cs.name) AS stage_name,
                   COALESCE(bool_or(e.to_status IN ('APPLIED', 'IN_PROCESS', 'OFFER_RECEIVED', 'ACCEPTED')
                                    OR e.event_type = 'APPLICATION_CREATED'), false) AS ev_applied,
                   COALESCE(bool_or(e.to_status IN ('IN_PROCESS', 'OFFER_RECEIVED', 'ACCEPTED')), false) AS ev_process,
                   COALESCE(bool_or(lower(ts.name) LIKE '%entrevista%' OR lower(ts.name) LIKE '%teste%'), false) AS ev_interview,
                   COALESCE(bool_or(e.to_status IN ('OFFER_RECEIVED', 'ACCEPTED') OR lower(ts.name) LIKE '%proposta%'), false) AS ev_offer,
                   COALESCE(bool_or(e.to_status = 'ACCEPTED'), false) AS ev_accepted,
                   count(a.application_id) > 0 AS has_application
              FROM core.saved_job sj
              LEFT JOIN core.role_family rf ON rf.code = sj.role_family
              LEFT JOIN core.application a ON a.saved_job_id = sj.saved_job_id
              LEFT JOIN core.applicationstage cs ON cs.application_stage_id = a.application_stage_id
              LEFT JOIN core.application_event e ON e.saved_job_id = sj.saved_job_id AND e.users_id = sj.users_id
              LEFT JOIN core.applicationstage ts ON ts.application_stage_id = e.to_stage_id
             WHERE sj.users_id = :userId
             GROUP BY sj.saved_job_id, rf.name
            """
        ).param("userId", userId).query { rs, _ ->
            val status = SavedJobStatus.valueOf(rs.getString("status"))
            val stage = rs.getString("stage_name")
            val stageLower = stage?.lowercase().orEmpty()
            val accepted = rs.getBoolean("ev_accepted") || status == SavedJobStatus.ACCEPTED
            val offer = accepted || rs.getBoolean("ev_offer") || status == SavedJobStatus.OFFER_RECEIVED
            val interview = rs.getBoolean("ev_interview") ||
                (status == SavedJobStatus.IN_PROCESS && ("entrevista" in stageLower || "teste" in stageLower))
            val inProcess = interview || offer || rs.getBoolean("ev_process") || status == SavedJobStatus.IN_PROCESS
            val applied = inProcess || rs.getBoolean("ev_applied") || rs.getBoolean("has_application") || status == SavedJobStatus.APPLIED
            JobProgress(
                roleFamily = rs.getString("role_family"),
                roleFamilyName = rs.getString("role_family_name"),
                status = status,
                stageName = stage,
                applied = applied,
                inProcess = inProcess,
                interview = interview,
                offer = offer,
                accepted = accepted
            )
        }.list()

    // Eventos por semana (ou mês) no fuso do produto. Cada vaga conta uma vez por indicador e período;
    // "events" é a atividade bruta (todos os registros, exceto reordenar dentro da mesma coluna).
    private fun series(userId: Int, unit: String, from: LocalDate): Map<LocalDate, PeriodPoint> =
        jdbc.sql(
            """
            SELECT CAST(date_trunc(:unit, e.occurred_at AT TIME ZONE :zone) AS date) AS period,
                   count(DISTINCT e.saved_job_id) FILTER (WHERE e.event_type = 'SAVED') AS saved,
                   count(DISTINCT e.saved_job_id) FILTER (WHERE e.event_type = 'APPLICATION_CREATED' OR e.to_status = 'APPLIED') AS applied,
                   count(DISTINCT e.saved_job_id) FILTER (WHERE e.event_type = 'STAGE_CHANGED'
                       OR e.to_status IN ('IN_PROCESS', 'OFFER_RECEIVED')) AS advanced,
                   count(DISTINCT e.saved_job_id) FILTER (WHERE e.to_status IN ('ACCEPTED', 'REJECTED', 'WITHDRAWN')) AS closed,
                   count(*) FILTER (WHERE NOT (e.event_type = 'MOVED' AND e.from_status IS NOT DISTINCT FROM e.to_status)) AS events
              FROM core.application_event e
             WHERE e.users_id = :userId
               AND e.occurred_at >= CAST(:from AS timestamp) AT TIME ZONE :zone
             GROUP BY 1
            """
        )
            .param("unit", unit)
            .param("zone", ZONE.id)
            .param("userId", userId)
            .param("from", from.atStartOfDay())
            .query { rs, _ ->
                val start = rs.getObject("period", LocalDate::class.java)
                PeriodPoint(start, rs.getInt("saved"), rs.getInt("applied"), rs.getInt("advanced"), rs.getInt("closed"), rs.getInt("events"))
            }.list().associateBy { it.start }

    private fun history(userId: Int): List<HistoryItem> =
        jdbc.sql(
            """
            SELECT e.saved_job_id, e.event_type, e.from_status, e.to_status, e.occurred_at,
                   fs.name AS from_stage, ts.name AS to_stage, sj.position_role, sj.organization_name
              FROM core.application_event e
              JOIN core.saved_job sj ON sj.saved_job_id = e.saved_job_id AND sj.users_id = e.users_id
              LEFT JOIN core.applicationstage fs ON fs.application_stage_id = e.from_stage_id
              LEFT JOIN core.applicationstage ts ON ts.application_stage_id = e.to_stage_id
             WHERE e.users_id = :userId
               AND NOT (e.event_type = 'MOVED' AND e.from_status IS NOT DISTINCT FROM e.to_status)
             ORDER BY e.occurred_at DESC, e.application_event_id DESC
             LIMIT :limit
            """
        ).param("userId", userId).param("limit", HISTORY_LIMIT).query { rs, _ ->
            val type = EventType.valueOf(rs.getString("event_type"))
            val from = rs.getString("from_status")?.let(SavedJobStatus::valueOf)
            val to = rs.getString("to_status")?.let(SavedJobStatus::valueOf)
            HistoryItem(
                savedJobId = rs.getLong("saved_job_id"),
                positionRole = rs.getString("position_role"),
                organizationName = rs.getString("organization_name"),
                type = type,
                description = BoardService.describe(type, from, to, rs.getString("from_stage"), rs.getString("to_stage")),
                occurredAt = rs.getObject("occurred_at", OffsetDateTime::class.java)
            )
        }.list()

    companion object {
        val ZONE: java.time.ZoneId = java.time.ZoneId.of("America/Sao_Paulo")
        private const val MONTHS = 12
        private const val HISTORY_LIMIT = 30
    }
}

@RestController
@RequestMapping("/analytics")
class AnalyticsController(private val service: AnalyticsService) {
    @GetMapping
    fun analytics(
        @AuthenticationPrincipal principal: AppUserDetails,
        @RequestParam(defaultValue = "12") weeks: Int
    ): AnalyticsResponse = service.analytics(principal.id, weeks)
}
