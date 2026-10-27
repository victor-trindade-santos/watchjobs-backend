package com.watchjobs.board

import com.watchjobs.entity.enums.SavedJobStatus
import com.watchjobs.exception.BusinessException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

// F6-02: as 7 colunas, os 8 status e a etapa.
class BoardColumnMapperTest {
    @Test
    fun `every status has one column`() {
        val expected = mapOf(
            SavedJobStatus.SAVED to BoardColumn.SAVED,
            SavedJobStatus.PLANNING_TO_APPLY to BoardColumn.SAVED,
            SavedJobStatus.APPLIED to BoardColumn.APPLIED,
            SavedJobStatus.IN_PROCESS to BoardColumn.IN_REVIEW,
            SavedJobStatus.OFFER_RECEIVED to BoardColumn.OFFER,
            SavedJobStatus.ACCEPTED to BoardColumn.CLOSED,
            SavedJobStatus.REJECTED to BoardColumn.CLOSED,
            SavedJobStatus.WITHDRAWN to BoardColumn.CLOSED
        )
        SavedJobStatus.entries.forEach { assertEquals(expected.getValue(it), BoardColumnMapper.columnOf(it)) }
    }

    @Test
    fun `in process is split by the application stage`() {
        assertEquals(BoardColumn.IN_REVIEW, BoardColumnMapper.columnOf(SavedJobStatus.IN_PROCESS, "Triagem"))
        assertEquals(BoardColumn.INTERVIEW, BoardColumnMapper.columnOf(SavedJobStatus.IN_PROCESS, "Entrevista técnica"))
        assertEquals(BoardColumn.INTERVIEW, BoardColumnMapper.columnOf(SavedJobStatus.IN_PROCESS, "Entrevista final"))
        assertEquals(BoardColumn.TECH_TEST, BoardColumnMapper.columnOf(SavedJobStatus.IN_PROCESS, "Teste técnico"))
    }

    @Test
    fun `moving to a column picks its default status`() {
        fun target(current: SavedJobStatus, to: BoardColumn) = BoardColumnMapper.targetStatus(current, BoardColumnMapper.columnOf(current), to)
        assertEquals(SavedJobStatus.SAVED, target(SavedJobStatus.APPLIED, BoardColumn.SAVED))
        assertEquals(SavedJobStatus.APPLIED, target(SavedJobStatus.SAVED, BoardColumn.APPLIED))
        assertEquals(SavedJobStatus.IN_PROCESS, target(SavedJobStatus.APPLIED, BoardColumn.INTERVIEW))
        assertEquals(SavedJobStatus.IN_PROCESS, target(SavedJobStatus.APPLIED, BoardColumn.TECH_TEST))
        assertEquals(SavedJobStatus.OFFER_RECEIVED, target(SavedJobStatus.IN_PROCESS, BoardColumn.OFFER))
    }

    @Test
    fun `reordering inside the column keeps the status`() {
        assertEquals(
            SavedJobStatus.OFFER_RECEIVED,
            BoardColumnMapper.targetStatus(SavedJobStatus.OFFER_RECEIVED, BoardColumn.OFFER, BoardColumn.OFFER)
        )
        assertEquals(
            SavedJobStatus.PLANNING_TO_APPLY,
            BoardColumnMapper.targetStatus(SavedJobStatus.PLANNING_TO_APPLY, BoardColumn.SAVED, BoardColumn.SAVED)
        )
    }

    @Test
    fun `closed requires an outcome`() {
        assertThrows<BusinessException> { BoardColumnMapper.targetStatus(SavedJobStatus.APPLIED, BoardColumn.APPLIED, BoardColumn.CLOSED) }
        assertEquals(
            SavedJobStatus.REJECTED,
            BoardColumnMapper.targetStatus(SavedJobStatus.APPLIED, BoardColumn.APPLIED, BoardColumn.CLOSED, SavedJobStatus.REJECTED)
        )
    }

    @Test
    fun `requested status must belong to the column`() {
        assertThrows<BusinessException> {
            BoardColumnMapper.targetStatus(SavedJobStatus.SAVED, BoardColumn.SAVED, BoardColumn.CLOSED, SavedJobStatus.APPLIED)
        }
    }
}
