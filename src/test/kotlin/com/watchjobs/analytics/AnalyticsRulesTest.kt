package com.watchjobs.analytics

import com.watchjobs.board.BoardColumn
import com.watchjobs.entity.enums.SavedJobStatus
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

// F09: regras de agregação do analytics (sem banco).
class AnalyticsRulesTest {
    private fun job(
        status: SavedJobStatus,
        family: String? = "BACKEND",
        stage: String? = null,
        applied: Boolean = false,
        inProcess: Boolean = false,
        interview: Boolean = false,
        offer: Boolean = false,
        accepted: Boolean = false
    ) = JobProgress(family, family?.let { "Back-end" }, status, stage, applied, inProcess, interview, offer, accepted)

    private val jobs = listOf(
        job(SavedJobStatus.SAVED),
        job(SavedJobStatus.SAVED, family = null),
        job(SavedJobStatus.APPLIED, applied = true),
        job(SavedJobStatus.IN_PROCESS, stage = "Entrevista RH", applied = true, inProcess = true, interview = true),
        job(SavedJobStatus.REJECTED, applied = true, inProcess = true, interview = true),
        job(SavedJobStatus.ACCEPTED, family = "DATA", applied = true, inProcess = true, interview = true, offer = true, accepted = true)
    )

    @Test
    fun `funnel counts the furthest point each job reached and the conversion between steps`() {
        val funnel = AnalyticsRules.funnel(jobs)
        assertEquals(listOf(6, 4, 3, 3, 1, 1), funnel.map { it.count })
        assertNull(funnel.first().conversionFromPrevious)
        assertEquals(0.667, funnel[1].conversionFromPrevious)
        assertEquals(0.333, funnel[4].conversionFromPrevious)
        assertEquals(1.0, funnel[5].conversionFromPrevious)
    }

    @Test
    fun `conversion is undefined without anything in the previous step`() {
        val funnel = AnalyticsRules.funnel(listOf(job(SavedJobStatus.SAVED)))
        assertEquals(0.0, funnel[1].conversionFromPrevious)
        assertNull(funnel[2].conversionFromPrevious)
    }

    @Test
    fun `current distribution follows the board columns`() {
        val byStage = AnalyticsRules.byStage(jobs).associate { it.column to it.count }
        assertEquals(BoardColumn.entries.toSet(), byStage.keys)
        assertEquals(2, byStage[BoardColumn.SAVED])
        assertEquals(1, byStage[BoardColumn.APPLIED])
        assertEquals(1, byStage[BoardColumn.INTERVIEW])
        assertEquals(2, byStage[BoardColumn.CLOSED])
        assertEquals(0, byStage[BoardColumn.OFFER])
    }

    @Test
    fun `role families group jobs and unknown areas get a readable label`() {
        val families = AnalyticsRules.byRoleFamily(jobs)
        assertEquals(listOf("BACKEND", "DATA", null), families.map { it.roleFamily })
        assertEquals(4, families[0].saved)
        assertEquals(3, families[0].applied)
        assertEquals("Sem área definida", families[2].label)
    }

    @Test
    fun `totals separate active, closed outcomes and saved`() {
        val totals = AnalyticsRules.totals(jobs)
        assertEquals(6, totals.saved)
        assertEquals(4, totals.applied)
        assertEquals(2, totals.active)
        assertEquals(1, totals.accepted)
        assertEquals(1, totals.rejected)
    }

    @Test
    fun `periods without events are filled with zero`() {
        val starts = listOf(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 12), LocalDate.of(2026, 1, 19))
        val filled = AnalyticsRules.fill(starts, mapOf(starts[1] to PeriodPoint(starts[1], 2, 1, 0, 0, 5)))
        assertEquals(starts, filled.map { it.start })
        assertEquals(listOf(0, 5, 0), filled.map { it.events })
    }
}
