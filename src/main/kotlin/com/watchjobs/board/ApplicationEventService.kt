package com.watchjobs.board

import com.watchjobs.entity.enums.SavedJobStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service

enum class EventType { SAVED, STATUS_CHANGED, STAGE_CHANGED, MOVED, APPLICATION_CREATED, APPLICATION_DELETED }

// Serviço único de eventos do histórico (core.application_event), chamado por qualquer caminho que
// muda status ou etapa, cria ou exclui a candidatura. Roda na transação de quem chama.
@Service
class ApplicationEventService(private val jdbc: JdbcClient) {

    fun record(
        savedJobId: Long,
        userId: Int,
        type: EventType,
        fromStatus: String? = null,
        toStatus: String? = null,
        applicationId: Int? = null,
        fromStageId: Int? = null,
        toStageId: Int? = null,
        note: String? = null
    ) {
        jdbc.sql(
            """
            INSERT INTO core.application_event (saved_job_id, users_id, application_id, event_type, from_status,
                to_status, from_stage_id, to_stage_id, note)
            VALUES (:savedJobId, :userId, :applicationId, :type, :fromStatus, :toStatus, :fromStage, :toStage, :note)
            """
        )
            .param("savedJobId", savedJobId)
            .param("userId", userId)
            .param("applicationId", applicationId)
            .param("type", type.name)
            .param("fromStatus", fromStatus)
            .param("toStatus", toStatus)
            .param("fromStage", fromStageId)
            .param("toStage", toStageId)
            .param("note", note)
            .update()
    }

    // Vaga recém-salva: evento SAVED e card no fim da coluna do status.
    fun saved(savedJobId: Long, userId: Int, status: SavedJobStatus = SavedJobStatus.SAVED) {
        appendToColumn(savedJobId, userId, status)
        record(savedJobId, userId, EventType.SAVED, toStatus = status.name)
    }

    fun appendToColumn(savedJobId: Long, userId: Int, status: SavedJobStatus) {
        val statuses = BoardColumnMapper.statusesOf(BoardColumnMapper.columnOf(status)).map { it.name }
        jdbc.sql(
            """
            UPDATE core.saved_job
               SET board_rank = (SELECT COALESCE(max(board_rank), 0) + 1000 FROM core.saved_job
                                  WHERE users_id = :userId AND saved_job_id <> :id AND status IN (:statuses))
             WHERE saved_job_id = :id AND users_id = :userId
            """
        ).param("userId", userId).param("id", savedJobId).param("statuses", statuses).update()
    }
}
