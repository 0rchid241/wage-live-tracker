package com.orchid.wagelivetracker.ui.history

import com.orchid.wagelivetracker.data.repository.*
import com.orchid.wagelivetracker.domain.wage.WorkCondition
import java.math.BigDecimal
import java.time.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkHistoryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = Clock.fixed(Instant.parse("2026-10-06T12:00:00Z"), ZoneOffset.UTC)
    private val start = LocalDateTime.parse("2026-10-06T09:00:00")
    private val month = YearMonth.of(2026, 10)
    private val condition = WorkCondition(BigDecimal("10000.000"), true, BigDecimal("0.75"), BigDecimal("0.25"))
    private val profile = WorkProfile(1, "카페", condition, start.minusDays(1))
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun record(id: Long = 1, from: LocalDateTime = start, to: LocalDateTime = start.plusHours(2),
        snapshot: WorkCondition = condition, breaks: List<HistoryBreak> = emptyList()): HistoryRecord = HistoryRecord(
        StoredShift(ShiftRecord(id, 1, from, to, ShiftStatus.COMPLETED, snapshot),
            breaks.mapIndexed { index, rest -> BreakRecord((index + 1).toLong(), id, rest.startedAt, rest.endedAt) }), "카페")

    private class FakeStore(var profile: WorkProfile?, records: List<HistoryRecord>) : WorkHistoryStore {
        val records = records.associateBy { it.stored.shift.id }.toMutableMap()
        var failRead = false
        var failWrite = false
        var writes = 0
        var gate: CompletableDeferred<Unit>? = null
        var readGate: CompletableDeferred<Unit>? = null
        override suspend fun getCurrentProfile() = profile
        override suspend fun getCompletedShifts(month: YearMonth): List<HistoryRecord> {
            readGate?.await()
            if (failRead) error("read failed")
            return records.values.filter { it.stored.shift.status == ShiftStatus.COMPLETED && YearMonth.from(it.stored.shift.startedAt) == month }
        }
        override suspend fun getCompletedShift(id: Long): HistoryRecord? {
            if (failRead) error("detail failed")
            return records[id]?.takeIf { it.stored.shift.status == ShiftStatus.COMPLETED }
        }
        private suspend fun write() { writes++; gate?.await(); if (failWrite) error("write failed") }
        private fun replace(shift: ShiftRecord, draft: CompletedShiftDraft, name: String): HistoryRecord = HistoryRecord(
            StoredShift(shift.copy(startedAt = draft.startedAt, endedAt = draft.endedAt, conditionSnapshot = shift.conditionSnapshot.copy(hourlyWage = draft.hourlyWage)),
                draft.breaks.map { BreakRecord(shiftId = shift.id, startedAt = it.startedAt, endedAt = it.endedAt) }), name).also { records[shift.id] = it }
        override suspend fun updateCompletedShift(id: Long, draft: CompletedShiftDraft): HistoryRecord {
            write(); val previous = checkNotNull(records[id]); return replace(previous.stored.shift, draft, previous.workplaceName)
        }
        override suspend fun addCompletedShift(draft: CompletedShiftDraft): HistoryRecord {
            write(); val current = checkNotNull(profile)
            val id = (records.keys.maxOrNull() ?: 0) + 1
            return replace(ShiftRecord(id, current.id, draft.startedAt, draft.endedAt, ShiftStatus.COMPLETED, current.condition), draft, current.nickname)
        }
        override suspend fun deleteCompletedShift(id: Long) { write(); checkNotNull(records.remove(id)) }
    }

    private fun loaded(vm: WorkHistoryViewModel) = (vm.state.value as WorkHistoryState.MonthLoaded).summary
    private fun editor(vm: WorkHistoryViewModel) = (vm.state.value as WorkHistoryState.Editing).editor
    private fun detail(vm: WorkHistoryViewModel) = (vm.state.value as WorkHistoryState.Detail).entry
    private fun money(expected: String, actual: BigDecimal) = assertEquals(0, BigDecimal(expected).compareTo(actual))
    private fun invalid(editor: HistoryEditor) {
        try { editor.draft(); fail("Invalid editor accepted") } catch (_: IllegalArgumentException) { }
        assertNull(editor.preview())
    }

    @Test fun `initial closed opens clock month and empty state navigates months`() = runTest {
        val vm = WorkHistoryViewModel(FakeStore(profile, emptyList()), clock)
        assertEquals(WorkHistoryState.Closed, vm.state.value)
        vm.open(); advanceUntilIdle()
        assertEquals(month, loaded(vm).month); money("0", loaded(vm).totalPay)
        vm.changeMonth(-1); advanceUntilIdle(); assertEquals(month.minusMonths(1), loaded(vm).month)
        vm.changeMonth(1); advanceUntilIdle(); assertEquals(month, loaded(vm).month)
        vm.back(); assertEquals(WorkHistoryState.Closed, vm.state.value)
    }

    @Test fun `selected month excludes active shifts and other months and sorts latest first`() {
        val first = record()
        val second = record(2, start.plusDays(1), start.plusDays(1).plusHours(1))
        val active = record(3).let { it.copy(stored = it.stored.copy(shift = it.stored.shift.copy(status = ShiftStatus.IN_PROGRESS, endedAt = null))) }
        val other = record(4, start.minusMonths(1), start.minusMonths(1).plusHours(1))
        val result = HistoryCalculation().month(month, listOf(first, active, second, other))
        assertEquals(listOf(2L, 1L), result.entries.map { it.record.stored.shift.id })
        money("30000", result.totalPay)
    }

    @Test fun `overnight shift belongs entirely to start month including night premium`() {
        val from = LocalDateTime.parse("2026-10-31T22:00:00")
        val record = record(from = from, to = from.plusHours(8), snapshot = WorkCondition(BigDecimal("10000"), true))
        money("120000", HistoryCalculation().month(month, listOf(record)).totalPay)
        assertTrue(HistoryCalculation().month(month.plusMonths(1), listOf(record)).entries.isEmpty())
    }

    @Test fun `monthly sums paid breaks and fractional money before display rounding`() {
        val one = record(breaks = listOf(HistoryBreak(start.plusMinutes(30), start.plusHours(1))))
        val two = record(2, start.plusDays(1), start.plusDays(1).plusHours(1), WorkCondition(BigDecimal("10000.123"), false))
        val result = HistoryCalculation().month(month, listOf(one, two))
        assertEquals(Duration.ofMinutes(150), result.paidDuration)
        assertEquals(Duration.ofMinutes(30), result.breakDuration)
        money("25000.123", result.totalPay)
    }

    @Test fun `current profile changes cannot change old monthly snapshot calculations`() = runTest {
        val store = FakeStore(profile, listOf(record()))
        val vm = WorkHistoryViewModel(store, clock); vm.open(); advanceUntilIdle()
        val previous = loaded(vm)
        store.profile = profile.copy(condition = WorkCondition(BigDecimal("20000"), false))
        vm.back(); vm.open(); advanceUntilIdle()
        assertEquals(previous, loaded(vm)); money("20000", loaded(vm).totalPay)
    }

    @Test fun `detail includes stored condition and all calculated durations`() = runTest {
        val vm = WorkHistoryViewModel(FakeStore(profile, listOf(record(breaks = listOf(HistoryBreak(start, start.plusHours(1)))))), clock)
        vm.open(); advanceUntilIdle(); vm.detail(1); advanceUntilIdle()
        assertEquals(condition, detail(vm).record.stored.shift.conditionSnapshot)
        assertEquals(Duration.ofHours(2), detail(vm).elapsed)
        assertEquals(Duration.ofHours(1), detail(vm).breakDuration); money("10000", detail(vm).breakdown.totalEstimatedPay)
    }

    @Test fun `editing times and snapshot wage recalculates detail and monthly totals`() = runTest {
        val store = FakeStore(profile, listOf(record()))
        val vm = WorkHistoryViewModel(store, clock); vm.open(); advanceUntilIdle(); vm.detail(1); advanceUntilIdle(); vm.edit()
        vm.changeStart(start.plusMinutes(30)); vm.changeEnd(start.plusHours(3)); vm.changeWage("12000.5")
        money("30001.25", editor(vm).preview()!!.breakdown.totalEstimatedPay)
        vm.save(); advanceUntilIdle(); money("30001.25", detail(vm).breakdown.totalEstimatedPay)
        val snapshot = detail(vm).record.stored.shift.conditionSnapshot
        assertEquals(condition.copy(hourlyWage = BigDecimal("12000.5")), snapshot)
        assertEquals(profile, store.profile)
        vm.back(); advanceUntilIdle(); money("30001.25", loaded(vm).totalPay)
    }

    @Test fun `moving shift start into another month updates membership after save`() = runTest {
        val vm = WorkHistoryViewModel(FakeStore(profile, listOf(record())), clock)
        vm.open(); advanceUntilIdle(); vm.detail(1); advanceUntilIdle(); vm.edit()
        vm.changeStart(start.plusMonths(1)); vm.changeEnd(start.plusMonths(1).plusHours(1)); vm.save(); advanceUntilIdle()
        vm.back(); advanceUntilIdle(); assertEquals(month.plusMonths(1), loaded(vm).month); assertEquals(1, loaded(vm).entries.size)
        vm.changeMonth(-1); advanceUntilIdle(); assertTrue(loaded(vm).entries.isEmpty())
    }

    @Test fun `breaks can be added edited and deleted with recalculated preview and saved pay`() = runTest {
        val vm = WorkHistoryViewModel(FakeStore(profile, listOf(record())), clock)
        vm.open(); advanceUntilIdle(); vm.detail(1); advanceUntilIdle(); vm.edit()
        vm.addBreak(); money("15000", editor(vm).preview()!!.breakdown.totalEstimatedPay)
        vm.changeBreak(0, HistoryBreak(start.plusMinutes(30), start.plusHours(1).plusMinutes(30)))
        money("10000", editor(vm).preview()!!.breakdown.totalEstimatedPay)
        vm.removeBreak(0); money("20000", editor(vm).preview()!!.breakdown.totalEstimatedPay)
        vm.addBreak(); vm.save(); advanceUntilIdle(); money("15000", detail(vm).breakdown.totalEstimatedPay)
        assertEquals(1, detail(vm).record.stored.breaks.size)
    }

    @Test fun `invalid wage syntax zero negative and excessive values cannot be saved`() = runTest {
        val store = FakeStore(profile, listOf(record())); val vm = WorkHistoryViewModel(store, clock)
        vm.open(); advanceUntilIdle(); vm.detail(1); advanceUntilIdle(); vm.edit()
        for (raw in listOf("", "0", "-1", "1e8", "1,000", "1000001", "9".repeat(65))) {
            vm.changeWage(raw); vm.save(); advanceUntilIdle(); assertNotNull(editor(vm).error); assertFalse(editor(vm).saving)
        }
        assertEquals(0, store.writes)
    }

    @Test fun `invalid time and break ranges overlap and open intervals are rejected`() = runTest {
        val vm = WorkHistoryViewModel(FakeStore(profile, listOf(record())), clock)
        vm.open(); advanceUntilIdle(); vm.detail(1); advanceUntilIdle(); vm.edit()
        val valid = editor(vm)
        invalid(valid.copy(endedAt = start)); invalid(valid.copy(endedAt = start.minusSeconds(1)))
        val invalidBreaks = listOf(
            listOf(HistoryBreak(start, null)), listOf(HistoryBreak(start, start)),
            listOf(HistoryBreak(start.minusSeconds(1), start.plusMinutes(1))),
            listOf(HistoryBreak(start.plusHours(1), start.plusHours(3))),
            listOf(HistoryBreak(start, start.plusHours(1)), HistoryBreak(start.plusMinutes(30), start.plusHours(2))))
        invalidBreaks.forEach { invalid(valid.copy(breaks = it)) }
        vm.changeEnd(start); vm.save(); assertNotNull(editor(vm).error)
    }

    @Test fun `touching breaks and full shift break are valid`() = runTest {
        val vm = WorkHistoryViewModel(FakeStore(profile, listOf(record())), clock)
        vm.open(); advanceUntilIdle(); vm.detail(1); advanceUntilIdle(); vm.edit()
        val edited = editor(vm).copy(breaks = listOf(HistoryBreak(start, start.plusHours(1)), HistoryBreak(start.plusHours(1), start.plusHours(2))))
        money("0", edited.preview()!!.breakdown.totalEstimatedPay)
    }

    @Test fun `manual completed shift copies current condition and adds immediately to selected month`() = runTest {
        val store = FakeStore(profile, emptyList()); val vm = WorkHistoryViewModel(store, clock)
        vm.open(month.minusMonths(1)); advanceUntilIdle(); vm.add(); advanceUntilIdle()
        assertEquals(LocalDateTime.parse("2026-09-01T09:00:00"), editor(vm).startedAt)
        vm.changeWage("12500"); vm.addBreak(); vm.save(); advanceUntilIdle()
        assertEquals(1, loaded(vm).entries.size)
        val saved = loaded(vm).entries.single().record.stored.shift
        assertEquals(ShiftStatus.COMPLETED, saved.status); assertEquals(1L, saved.workProfileId)
        assertEquals(condition.copy(hourlyWage = BigDecimal("12500")), saved.conditionSnapshot)
        assertEquals(profile, store.profile)
    }

    @Test fun `missing current profile blocks manual entry while old records stay readable`() = runTest {
        val vm = WorkHistoryViewModel(FakeStore(null, listOf(record())), clock)
        vm.open(); advanceUntilIdle(); vm.add(); advanceUntilIdle()
        assertNotNull((vm.state.value as WorkHistoryState.MonthLoaded).error)
        vm.detail(1); advanceUntilIdle(); money("20000", detail(vm).breakdown.totalEstimatedPay)
    }

    @Test fun `delete requires confirmation cancel keeps record and confirm refreshes totals`() = runTest {
        val store = FakeStore(profile, listOf(record())); val vm = WorkHistoryViewModel(store, clock)
        vm.open(); advanceUntilIdle(); vm.detail(1); advanceUntilIdle()
        vm.confirmDelete(); assertEquals(0, store.writes)
        vm.requestDelete(); vm.cancelDelete(); assertEquals(1, store.records.size)
        vm.requestDelete(); vm.confirmDelete(); vm.confirmDelete(); advanceUntilIdle()
        assertTrue(loaded(vm).entries.isEmpty()); money("0", loaded(vm).totalPay); assertEquals(1, store.writes)
    }

    @Test fun `read failures retry and detail failure returns usable month`() = runTest {
        val store = FakeStore(profile, listOf(record())).apply { failRead = true }; val vm = WorkHistoryViewModel(store, clock)
        vm.open(); advanceUntilIdle(); assertTrue(vm.state.value is WorkHistoryState.Error)
        store.failRead = false; vm.retry(); advanceUntilIdle(); assertEquals(1, loaded(vm).entries.size)
        store.failRead = true; vm.detail(1); advanceUntilIdle(); assertNotNull((vm.state.value as WorkHistoryState.MonthLoaded).error)
    }

    @Test fun `save failure preserves editor and stored data for retry`() = runTest {
        val original = record(); val store = FakeStore(profile, listOf(original)); val vm = WorkHistoryViewModel(store, clock)
        vm.open(); advanceUntilIdle(); vm.detail(1); advanceUntilIdle(); vm.edit(); vm.changeWage("13000")
        store.failWrite = true; vm.save(); advanceUntilIdle()
        assertEquals("13000", editor(vm).wage); assertNotNull(editor(vm).error); assertFalse(editor(vm).saving)
        assertEquals(original, store.records[1])
        store.failWrite = false; vm.save(); advanceUntilIdle(); money("26000", detail(vm).breakdown.totalEstimatedPay)
    }

    @Test fun `delete failure retains detail and can be retried`() = runTest {
        val store = FakeStore(profile, listOf(record())); val vm = WorkHistoryViewModel(store, clock)
        vm.open(); advanceUntilIdle(); vm.detail(1); advanceUntilIdle()
        store.failWrite = true; vm.requestDelete(); vm.confirmDelete(); advanceUntilIdle()
        assertNotNull((vm.state.value as WorkHistoryState.Detail).error); assertEquals(1, store.records.size)
        store.failWrite = false; vm.requestDelete(); vm.confirmDelete(); advanceUntilIdle(); assertTrue(loaded(vm).entries.isEmpty())
    }

    @Test fun `pending save blocks duplicate submissions input changes and back`() = runTest {
        val store = FakeStore(profile, emptyList()); val vm = WorkHistoryViewModel(store, clock)
        vm.open(); advanceUntilIdle(); vm.add(); advanceUntilIdle(); store.gate = CompletableDeferred()
        vm.save(); vm.save(); vm.changeWage("20000"); vm.back(); runCurrent()
        assertTrue(editor(vm).saving); assertEquals("10000", editor(vm).wage); assertEquals(1, store.writes)
        store.gate!!.complete(Unit); advanceUntilIdle(); assertEquals(1, loaded(vm).entries.size)
    }

    @Test fun `cancel edit changes neither record nor snapshot precision`() = runTest {
        val original = record(from = start.plusNanos(123), to = start.plusHours(2).plusNanos(456))
        val store = FakeStore(profile, listOf(original)); val vm = WorkHistoryViewModel(store, clock)
        vm.open(); advanceUntilIdle(); vm.detail(1); advanceUntilIdle(); vm.edit()
        assertEquals(BigDecimal("10000.000"), editor(vm).draft().hourlyWage)
        assertEquals(original.stored.shift.startedAt, editor(vm).draft().startedAt)
        vm.changeWage("12000"); vm.back(); assertEquals(original, detail(vm).record); assertEquals(0, store.writes)
    }

    @Test fun `closing a pending load cannot reopen history after request completes`() = runTest {
        val store = FakeStore(profile, emptyList()).apply { readGate = CompletableDeferred() }; val vm = WorkHistoryViewModel(store, clock)
        vm.open(); runCurrent(); vm.back(); store.readGate!!.complete(Unit); advanceUntilIdle()
        assertEquals(WorkHistoryState.Closed, vm.state.value)
    }
}
