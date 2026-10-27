package com.watchjobs.board

import com.watchjobs.entity.enums.SavedJobStatus
import com.watchjobs.entity.enums.WorkMode
import com.watchjobs.exception.BusinessException
import com.watchjobs.exception.ConflictException
import com.watchjobs.exception.NotFoundException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import java.time.OffsetDateTime

data class BoardCard(
    val id: Long,
    val positionRole: String,
    val organizationName: String,
    val locationCity: String?,
    val workMode: WorkMode?,
    val status: SavedJobStatus,
    val boardRank: BigDecimal,
    val matchScore: Double?,
    val applicationId: Int?,
    val stageName: String?,
    val savedAt: OffsetDateTime,
    val statusUpdatedAt: OffsetDateTime
)

data class BoardColumnResponse(val column: BoardColumn, val cards: List<BoardCard>)

data class BoardResponse(val columns: List<BoardColumnResponse>)

data class MoveRequest(
    val toColumn: BoardColumn? = null,
    val status: SavedJobStatus? = null,
    val outcome: SavedJobStatus? = null,
    val beforeId: Long? = null,
    val afterId: Long? = null
)

data class TimelineEvent(
    val id: Long,
    val type: EventType,
    val fromStatus: SavedJobStatus?,
    val toStatus: SavedJobStatus?,
    val fromStage: String?,
    val toStage: String?,
    val note: String?,
    val occurredAt: OffsetDateTime,
    val description: String
)

// Kanban (3.8): 4 colunas derivadas do status, ordem por board_rank (ponto médio entre vizinhos) e histórico.
@Service
class BoardService(
    private val jdbc: JdbcClient,
    private val events: ApplicationEventService
) {
    @Transactional(readOnly = true)
    fun board(userId: Int): BoardResponse {
        val cards = cards(userId, null)
        return BoardResponse(BoardColumn.entries.map { column ->
            BoardColumnResponse(column, cards.filter { BoardColumnMapper.columnOf(it.status, it.stageName) == column })
        })
    }

    @Transactional
    fun move(userId: Int, id: Long, request: MoveRequest): BoardCard {
        val toColumn = request.toColumn ?: throw BusinessException("Informe a coluna de destino")
        jdbc.sql("SELECT status FROM core.saved_job WHERE saved_job_id = :id AND users_id = :userId FOR UPDATE")
            .param("id", id).param("userId", userId)
            .query(String::class.java).optional().orElse(null) ?: throw NotFoundException("Vaga não encontrada")
        val card = cards(userId, id).single()
        val currentStatus = card.status
        val currentColumn = BoardColumnMapper.columnOf(currentStatus, card.stageName)

        val outcome = request.outcome
        if (outcome != null && outcome !in BoardColumnMapper.outcomes) {
            throw BusinessException("Resultado inválido: use ACCEPTED, REJECTED ou WITHDRAWN")
        }
        val target = BoardColumnMapper.targetStatus(
            currentStatus, currentColumn, toColumn, request.status ?: outcome.takeIf { toColumn == BoardColumn.CLOSED }
        )
        val application = card.applicationId
        if (toColumn == BoardColumn.SAVED && application != null && currentColumn != BoardColumn.SAVED) {
            throw ConflictException("Exclua a candidatura para voltar a vaga para Salvas")
        }

        val rank = rankFor(userId, id, toColumn, request.beforeId, request.afterId)
        jdbc.sql(
            """
            UPDATE core.saved_job
               SET status = :status, board_rank = :rank,
                   status_updated_at = CASE WHEN status <> :status THEN now() ELSE status_updated_at END
             WHERE saved_job_id = :id AND users_id = :userId
            """
        ).param("status", target.name).param("rank", rank).param("id", id).param("userId", userId).update()

        // Entrar numa coluna de etapa (análise, entrevista, teste, oferta) também põe a candidatura na etapa certa.
        val columnStageId = if (toColumn != currentColumn) BoardColumnMapper.stageNameFor(toColumn)?.let(::stageId) else null
        var applicationId = application
        if (target !in SAVED_STATUSES) {
            if (applicationId == null) {
                // Ir para "Candidatado" (ou além) sem candidatura cria uma, com a data de hoje; sem etapa da coluna, começa em Triagem.
                val stageId = columnStageId
                    ?: jdbc.sql("SELECT application_stage_id FROM core.applicationstage ORDER BY sort_order LIMIT 1")
                        .query(Int::class.java).single()
                applicationId = jdbc.sql(
                    """
                    INSERT INTO core.application (application_status, start_date, application_stage_id, users_id, saved_job_id)
                    VALUES (:status, :today, :stageId, :userId, :id)
                    RETURNING application_id
                    """
                ).param("status", target.name).param("today", LocalDate.now()).param("stageId", stageId)
                    .param("userId", userId).param("id", id)
                    .query(Int::class.java).single()
                events.record(id, userId, EventType.APPLICATION_CREATED, currentStatus.name, target.name, applicationId, toStageId = stageId)
            } else {
                if (target != currentStatus) {
                    jdbc.sql("UPDATE core.application SET application_status = :status WHERE application_id = :applicationId")
                        .param("status", target.name).param("applicationId", applicationId).update()
                }
                if (columnStageId != null) {
                    val fromStageId = jdbc.sql("SELECT application_stage_id FROM core.application WHERE application_id = :applicationId")
                        .param("applicationId", applicationId).query(Int::class.java).single()
                    if (fromStageId != columnStageId) {
                        jdbc.sql("UPDATE core.application SET application_stage_id = :stageId WHERE application_id = :applicationId")
                            .param("stageId", columnStageId).param("applicationId", applicationId).update()
                        events.record(id, userId, EventType.STAGE_CHANGED, applicationId = applicationId, fromStageId = fromStageId, toStageId = columnStageId)
                    }
                }
            }
        }
        events.record(id, userId, EventType.MOVED, currentStatus.name, target.name, applicationId, note = toColumn.name)
        return cards(userId, id).single()
    }

    private fun stageId(name: String): Int? =
        jdbc.sql("SELECT application_stage_id FROM core.applicationstage WHERE name = :name LIMIT 1")
            .param("name", name).query(Int::class.java).optional().orElse(null)

    @Transactional(readOnly = true)
    fun timeline(userId: Int, id: Long): List<TimelineEvent> {
        val exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM core.saved_job WHERE saved_job_id = :id AND users_id = :userId)")
            .param("id", id).param("userId", userId).query(Boolean::class.java).single()
        if (!exists) throw NotFoundException("Vaga não encontrada")
        return jdbc.sql(
            """
            SELECT e.application_event_id, e.event_type, e.from_status, e.to_status, e.note, e.occurred_at,
                   fs.name AS from_stage, ts.name AS to_stage
              FROM core.application_event e
              LEFT JOIN core.applicationstage fs ON fs.application_stage_id = e.from_stage_id
              LEFT JOIN core.applicationstage ts ON ts.application_stage_id = e.to_stage_id
             WHERE e.saved_job_id = :id AND e.users_id = :userId
             ORDER BY e.occurred_at DESC, e.application_event_id DESC
            """
        ).param("id", id).param("userId", userId).query { rs, _ ->
            val type = EventType.valueOf(rs.getString("event_type"))
            val from = rs.getString("from_status")?.let(SavedJobStatus::valueOf)
            val to = rs.getString("to_status")?.let(SavedJobStatus::valueOf)
            val fromStage = rs.getString("from_stage")
            val toStage = rs.getString("to_stage")
            TimelineEvent(
                id = rs.getLong("application_event_id"),
                type = type,
                fromStatus = from,
                toStatus = to,
                fromStage = fromStage,
                toStage = toStage,
                note = rs.getString("note"),
                occurredAt = rs.getObject("occurred_at", OffsetDateTime::class.java),
                description = describe(type, from, to, fromStage, toStage)
            )
        }.list()
    }

    // Ponto médio entre os vizinhos; sem vizinhos, vai para o fim da coluna. Se os valores ficarem
    // próximos demais, a coluna é renumerada (de 1000 em 1000) antes.
    private fun rankFor(userId: Int, movingId: Long, column: BoardColumn, beforeId: Long?, afterId: Long?, renumbered: Boolean = false): BigDecimal {
        val ranks = cards(userId, null)
            .filter { BoardColumnMapper.columnOf(it.status, it.stageName) == column && it.id != movingId }
            .sortedBy { it.boardRank }
        fun rankOf(cardId: Long) = ranks.firstOrNull { it.id == cardId }?.boardRank
            ?: throw BusinessException("O card vizinho $cardId não está nesta coluna")

        var lower = beforeId?.let(::rankOf)
        var upper = afterId?.let(::rankOf)
        when {
            lower != null && upper == null -> upper = ranks.firstOrNull { it.boardRank > lower }?.boardRank
            lower == null && upper != null -> lower = ranks.lastOrNull { it.boardRank < upper }?.boardRank
            lower == null -> lower = ranks.lastOrNull()?.boardRank
        }
        if (lower != null && upper != null && upper - lower < MIN_GAP && !renumbered) {
            renumber(userId, ranks.map { it.id })
            return rankFor(userId, movingId, column, beforeId, afterId, renumbered = true)
        }
        return when {
            lower == null && upper == null -> STEP
            upper == null -> lower!! + STEP
            lower == null -> upper - STEP
            else -> (lower + upper).divide(BigDecimal(2), MathContext.DECIMAL64)
        }
    }

    private fun renumber(userId: Int, orderedIds: List<Long>) {
        orderedIds.forEachIndexed { index, cardId ->
            jdbc.sql("UPDATE core.saved_job SET board_rank = :rank WHERE saved_job_id = :id AND users_id = :userId")
                .param("rank", STEP * BigDecimal(index + 1)).param("id", cardId).param("userId", userId).update()
        }
    }

    private fun cards(userId: Int, onlyId: Long?): List<BoardCard> =
        jdbc.sql(
            """
            SELECT sj.saved_job_id, sj.position_role, sj.organization_name, sj.location_city, sj.work_mode, sj.status,
                   sj.board_rank, sj.match_score_at_save, sj.saved_at, sj.status_updated_at,
                   a.application_id, st.name AS stage_name
              FROM core.saved_job sj
              LEFT JOIN core.application a ON a.saved_job_id = sj.saved_job_id
              LEFT JOIN core.applicationstage st ON st.application_stage_id = a.application_stage_id
             WHERE sj.users_id = :userId AND (CAST(:onlyId AS int8) IS NULL OR sj.saved_job_id = CAST(:onlyId AS int8))
             ORDER BY sj.board_rank, sj.saved_at
            """
        ).param("userId", userId).param("onlyId", onlyId).query { rs, _ ->
            BoardCard(
                id = rs.getLong("saved_job_id"),
                positionRole = rs.getString("position_role"),
                organizationName = rs.getString("organization_name"),
                locationCity = rs.getString("location_city"),
                workMode = rs.getString("work_mode")?.let(WorkMode::valueOf),
                status = SavedJobStatus.valueOf(rs.getString("status")),
                boardRank = rs.getBigDecimal("board_rank"),
                matchScore = rs.getBigDecimal("match_score_at_save")?.toDouble(),
                applicationId = (rs.getObject("application_id") as Number?)?.toInt(),
                stageName = rs.getString("stage_name"),
                savedAt = rs.getObject("saved_at", OffsetDateTime::class.java),
                statusUpdatedAt = rs.getObject("status_updated_at", OffsetDateTime::class.java)
            )
        }.list()

    companion object {
        private val STEP = BigDecimal(1000)
        private val MIN_GAP = BigDecimal("0.000001")
        private val SAVED_STATUSES = BoardColumnMapper.statusesOf(BoardColumn.SAVED).toSet()

        private val STATUS_LABELS = mapOf(
            SavedJobStatus.SAVED to "Salva",
            SavedJobStatus.PLANNING_TO_APPLY to "Planejando se candidatar",
            SavedJobStatus.APPLIED to "Candidatura enviada",
            SavedJobStatus.IN_PROCESS to "Em processo",
            SavedJobStatus.OFFER_RECEIVED to "Proposta recebida",
            SavedJobStatus.ACCEPTED to "Aceita",
            SavedJobStatus.REJECTED to "Recusada",
            SavedJobStatus.WITHDRAWN to "Desistência"
        )

        fun describe(type: EventType, from: SavedJobStatus?, to: SavedJobStatus?, fromStage: String?, toStage: String?): String =
            when (type) {
                EventType.SAVED -> "Vaga salva"
                EventType.STATUS_CHANGED -> "Status: ${from?.let(STATUS_LABELS::get) ?: "-"} → ${to?.let(STATUS_LABELS::get) ?: "-"}"
                EventType.STAGE_CHANGED -> "Etapa: ${fromStage ?: "-"} → ${toStage ?: "-"}"
                EventType.MOVED -> if (from == to) "Card reordenado" else
                    "Movida de ${from?.let(STATUS_LABELS::get) ?: "-"} para ${to?.let(STATUS_LABELS::get) ?: "-"}"
                EventType.APPLICATION_CREATED -> "Candidatura criada" + (toStage?.let { " (etapa $it)" } ?: "")
                EventType.APPLICATION_DELETED -> "Candidatura excluída"
            }
    }
}
