package com.watchjobs.board

import com.watchjobs.entity.enums.SavedJobStatus
import com.watchjobs.exception.BusinessException

enum class BoardColumn { SAVED, APPLIED, IN_REVIEW, INTERVIEW, TECH_TEST, OFFER, CLOSED }

// Regra pura (1.5): o app pensa em 7 colunas; o banco guarda 8 status e a etapa da candidatura.
// "Em análise", "Entrevista" e "Teste técnico" são o status IN_PROCESS separado pela etapa.
object BoardColumnMapper {
    private val statusesByColumn = mapOf(
        BoardColumn.SAVED to listOf(SavedJobStatus.SAVED, SavedJobStatus.PLANNING_TO_APPLY),
        BoardColumn.APPLIED to listOf(SavedJobStatus.APPLIED),
        BoardColumn.IN_REVIEW to listOf(SavedJobStatus.IN_PROCESS),
        BoardColumn.INTERVIEW to listOf(SavedJobStatus.IN_PROCESS),
        BoardColumn.TECH_TEST to listOf(SavedJobStatus.IN_PROCESS),
        BoardColumn.OFFER to listOf(SavedJobStatus.OFFER_RECEIVED),
        BoardColumn.CLOSED to listOf(SavedJobStatus.ACCEPTED, SavedJobStatus.REJECTED, SavedJobStatus.WITHDRAWN)
    )

    // Nome (em core.applicationstage) da etapa que a candidatura recebe ao entrar na coluna.
    private val stageByColumn = mapOf(
        BoardColumn.IN_REVIEW to "Triagem",
        BoardColumn.INTERVIEW to "Entrevista RH",
        BoardColumn.TECH_TEST to "Teste técnico",
        BoardColumn.OFFER to "Proposta"
    )

    val outcomes = statusesByColumn.getValue(BoardColumn.CLOSED).toSet()

    // Sem a etapa, IN_PROCESS cai em "Em análise".
    fun columnOf(status: SavedJobStatus, stageName: String? = null): BoardColumn = when (status) {
        SavedJobStatus.SAVED, SavedJobStatus.PLANNING_TO_APPLY -> BoardColumn.SAVED
        SavedJobStatus.APPLIED -> BoardColumn.APPLIED
        SavedJobStatus.IN_PROCESS -> columnOfStage(stageName)
        SavedJobStatus.OFFER_RECEIVED -> BoardColumn.OFFER
        SavedJobStatus.ACCEPTED, SavedJobStatus.REJECTED, SavedJobStatus.WITHDRAWN -> BoardColumn.CLOSED
    }

    private fun columnOfStage(stageName: String?): BoardColumn {
        val name = stageName?.lowercase().orEmpty()
        return when {
            "teste" in name -> BoardColumn.TECH_TEST
            "entrevista" in name || "proposta" in name -> BoardColumn.INTERVIEW
            else -> BoardColumn.IN_REVIEW
        }
    }

    fun statusesOf(column: BoardColumn): List<SavedJobStatus> = statusesByColumn.getValue(column)

    fun stageNameFor(column: BoardColumn): String? = stageByColumn[column]

    // Status ao chegar na coluna: se já está nela, mantém; "Encerradas" exige o resultado.
    fun targetStatus(
        current: SavedJobStatus,
        currentColumn: BoardColumn,
        column: BoardColumn,
        requested: SavedJobStatus? = null
    ): SavedJobStatus {
        if (requested != null) {
            if (requested !in statusesOf(column)) throw BusinessException("O status $requested não pertence à coluna $column")
            return requested
        }
        if (currentColumn == column) return current
        return when (column) {
            BoardColumn.SAVED -> SavedJobStatus.SAVED
            BoardColumn.APPLIED -> SavedJobStatus.APPLIED
            BoardColumn.IN_REVIEW, BoardColumn.INTERVIEW, BoardColumn.TECH_TEST -> SavedJobStatus.IN_PROCESS
            BoardColumn.OFFER -> SavedJobStatus.OFFER_RECEIVED
            BoardColumn.CLOSED -> throw BusinessException("Informe o resultado para mover para Encerradas")
        }
    }
}
