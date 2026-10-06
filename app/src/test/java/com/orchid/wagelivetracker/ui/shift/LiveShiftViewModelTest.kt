package com.orchid.wagelivetracker.ui.shift

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
class LiveShiftViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = TestClock(LocalDateTime.parse("2026-10-06T12:00:00"))
    private val profile = WorkProfile(1, "카페", WorkCondition(BigDecimal("3600"), true), clock.now)
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private class TestClock(var now: LocalDateTime) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
        override fun instant(): Instant = now.toInstant(ZoneOffset.UTC)
        fun advance(seconds: Long) { now = now.plusSeconds(seconds) }
    }

    private class FakeStore(var profile: WorkProfile?) : LiveShiftStore {
        var stored: StoredShift? = null
        var calls = 0
        var readCalls = 0
        var failLoad = false
        var failWrite = false
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun getCurrentProfile() = profile
        override suspend fun getInProgressShift(): StoredShift? {
            readCalls++
            if (failLoad) error("read failed")
            return stored?.takeIf { it.shift.status == ShiftStatus.IN_PROGRESS }
        }
        private suspend fun write() {
            calls++
            gate?.await()
            if (failWrite) error("write failed")
        }
        override suspend fun startShift(profileId: Long, startedAt: LocalDateTime): ShiftRecord {
            write()
            check(stored?.shift?.status != ShiftStatus.IN_PROGRESS)
            return ShiftRecord(1, profileId, startedAt, null, ShiftStatus.IN_PROGRESS, profile!!.condition).also {
                stored = StoredShift(it, emptyList())
            }
        }
        override suspend fun startBreak(shiftId: Long, startedAt: LocalDateTime): StoredShift {
            write()
            val previous = checkNotNull(stored)
            check(previous.breaks.none { it.endedAt == null })
            return previous.copy(breaks = previous.breaks + BreakRecord((previous.breaks.size + 1).toLong(), shiftId, startedAt)).also { stored = it }
        }
        override suspend fun endBreak(shiftId: Long, endedAt: LocalDateTime): StoredShift {
            write()
            return close(endedAt).also { stored = it }
        }
        private fun close(at: LocalDateTime): StoredShift {
            val previous = checkNotNull(stored)
            return previous.copy(breaks = previous.breaks.mapNotNull {
                if (it.endedAt != null) it else if (at == it.startedAt) null else it.copy(endedAt = at)
            })
        }
        override suspend fun finishShift(shiftId: Long, endedAt: LocalDateTime): StoredShift {
            write()
            val closed = close(endedAt)
            return closed.copy(shift = closed.shift.copy(endedAt = endedAt, status = ShiftStatus.COMPLETED)).also { stored = it }
        }
    }

    private fun active(vm: LiveShiftViewModel) = vm.state.value as LiveShiftState.Active
    private fun money(vm: LiveShiftViewModel) = active(vm).estimate.breakdown.totalEstimatedPay
    private fun assertMoney(expected: String, actual: BigDecimal) = assertEquals(0, BigDecimal(expected).compareTo(actual))

    @Test fun `current profile with no shift becomes idle`() = runTest {
        val vm = LiveShiftViewModel(FakeStore(profile), clock)
        assertEquals(LiveShiftState.Loading, vm.state.value)
        advanceUntilIdle()
        assertEquals(LiveShiftState.Idle(profile), vm.state.value)
    }

    @Test fun `no profile requires setup`() = runTest {
        val vm = LiveShiftViewModel(FakeStore(null), clock)
        advanceUntilIdle()
        assertEquals(LiveShiftState.SetupRequired, vm.state.value)
    }

    @Test fun `start stores snapshot and shows zero at identical instant`() = runTest {
        val store = FakeStore(profile)
        val vm = LiveShiftViewModel(store, clock)
        advanceUntilIdle()
        vm.startShift()
        advanceUntilIdle()
        assertEquals(profile.condition, active(vm).stored.shift.conditionSnapshot)
        assertMoney("0", money(vm))
        assertEquals(Duration.ZERO, active(vm).estimate.elapsed)
        assertEquals(Duration.ZERO, active(vm).estimate.breakdown.paidWorkDuration)
    }

    @Test fun `time increase raises pay with no database access on ticks`() = runTest {
        val store = FakeStore(profile)
        val vm = LiveShiftViewModel(store, clock)
        advanceUntilIdle(); vm.startShift(); advanceUntilIdle()
        val reads = store.readCalls
        val writes = store.calls
        clock.advance(10); vm.refreshTime()
        assertMoney("10", money(vm))
        clock.advance(20); vm.refreshTime()
        assertMoney("30", money(vm))
        assertEquals(reads, store.readCalls)
        assertEquals(writes, store.calls)
    }

    @Test fun `open break freezes pay while elapsed time keeps advancing`() = runTest {
        val vm = LiveShiftViewModel(FakeStore(profile), clock)
        advanceUntilIdle(); vm.startShift(); advanceUntilIdle()
        clock.advance(10); vm.startBreak(); advanceUntilIdle()
        clock.advance(20); vm.refreshTime()
        assertTrue(active(vm).onBreak)
        assertMoney("10", money(vm))
        assertEquals(Duration.ofSeconds(30), active(vm).estimate.elapsed)
        assertEquals(Duration.ofSeconds(10), active(vm).estimate.breakdown.paidWorkDuration)
        assertEquals(Duration.ofSeconds(20), active(vm).estimate.breakDuration)
        assertMoney("0", active(vm).estimate.payPerSecond)
    }

    @Test fun `break resume restarts pay growth`() = runTest {
        val vm = LiveShiftViewModel(FakeStore(profile), clock)
        advanceUntilIdle(); vm.startShift(); advanceUntilIdle()
        clock.advance(10); vm.startBreak(); advanceUntilIdle()
        clock.advance(20); vm.endBreak(); advanceUntilIdle()
        clock.advance(5); vm.refreshTime()
        assertFalse(active(vm).onBreak)
        assertMoney("15", money(vm))
    }

    @Test fun `multiple breaks are deducted from final summary`() = runTest {
        val vm = LiveShiftViewModel(FakeStore(profile), clock)
        advanceUntilIdle(); vm.startShift(); advanceUntilIdle()
        clock.advance(10); vm.startBreak(); advanceUntilIdle()
        clock.advance(10); vm.endBreak(); advanceUntilIdle()
        clock.advance(10); vm.startBreak(); advanceUntilIdle()
        clock.advance(20); vm.endBreak(); advanceUntilIdle()
        clock.advance(10); vm.finishShift(); advanceUntilIdle()
        val summary = vm.state.value as LiveShiftState.Summary
        assertEquals(Duration.ofSeconds(60), summary.estimate.elapsed)
        assertEquals(Duration.ofSeconds(30), summary.estimate.breakDuration)
        assertMoney("30", summary.estimate.breakdown.totalEstimatedPay)
    }

    @Test fun `22 boundary changes premium and speed without early night notice`() = runTest {
        clock.now = LocalDateTime.parse("2026-10-06T21:59:59.500")
        val vm = LiveShiftViewModel(FakeStore(profile), clock)
        advanceUntilIdle(); vm.startShift(); advanceUntilIdle()
        assertFalse(active(vm).estimate.nightPremiumActive)
        assertMoney("1", active(vm).estimate.payPerSecond)
        clock.now = LocalDateTime.parse("2026-10-06T22:00:00")
        vm.refreshTime()
        assertTrue(active(vm).estimate.nightPremiumActive)
        assertMoney("1.5", active(vm).estimate.payPerSecond)
        clock.advance(2); vm.refreshTime()
        assertMoney("3.5", money(vm))
        assertMoney("1", active(vm).estimate.breakdown.nightPremium)
    }

    @Test fun `06 boundary turns off night speed and retains earned premium`() = runTest {
        clock.now = LocalDateTime.parse("2026-10-07T05:59:59")
        val vm = LiveShiftViewModel(FakeStore(profile), clock)
        advanceUntilIdle(); vm.startShift(); advanceUntilIdle()
        assertTrue(active(vm).estimate.nightPremiumActive)
        clock.advance(1); vm.refreshTime()
        assertFalse(active(vm).estimate.nightPremiumActive)
        assertMoney("1.5", money(vm))
        assertMoney("1", active(vm).estimate.payPerSecond)
    }

    @Test fun `below five employees gets no night premium or notice`() = runTest {
        clock.now = LocalDateTime.parse("2026-10-06T22:00:00")
        val vm = LiveShiftViewModel(FakeStore(profile.copy(condition = profile.condition.copy(hasAtLeastFiveEmployees = false))), clock)
        advanceUntilIdle(); vm.startShift(); advanceUntilIdle()
        clock.advance(10); vm.refreshTime()
        assertMoney("10", money(vm))
        assertMoney("0", active(vm).estimate.breakdown.nightPremium)
        assertFalse(active(vm).estimate.nightPremiumActive)
    }

    @Test fun `rapid duplicate start is blocked before the write runs`() = runTest {
        val store = FakeStore(profile)
        val vm = LiveShiftViewModel(store, clock)
        advanceUntilIdle(); vm.startShift(); vm.startShift(); advanceUntilIdle(); vm.startShift()
        assertEquals(1, store.calls)
    }

    @Test fun `duplicate break and ending without open break are ignored`() = runTest {
        val store = FakeStore(profile)
        val vm = LiveShiftViewModel(store, clock)
        advanceUntilIdle(); vm.startShift(); advanceUntilIdle()
        vm.endBreak(); assertEquals(1, store.calls)
        vm.startBreak(); vm.startBreak(); advanceUntilIdle(); vm.startBreak()
        assertEquals(2, store.calls)
    }

    @Test fun `zero duration break can resume immediately without invalid engine interval`() = runTest {
        val vm = LiveShiftViewModel(FakeStore(profile), clock)
        advanceUntilIdle(); vm.startShift(); advanceUntilIdle(); vm.startBreak(); advanceUntilIdle()
        assertMoney("0", money(vm))
        vm.endBreak(); advanceUntilIdle()
        assertFalse(active(vm).onBreak)
        clock.advance(1); vm.refreshTime()
        assertMoney("1", money(vm))
    }

    @Test fun `finish during break closes break and freezes final summary`() = runTest {
        val store = FakeStore(profile)
        val vm = LiveShiftViewModel(store, clock)
        advanceUntilIdle(); vm.startShift(); advanceUntilIdle()
        clock.advance(10); vm.startBreak(); advanceUntilIdle()
        clock.advance(20); vm.finishShift(); advanceUntilIdle()
        val summary = vm.state.value as LiveShiftState.Summary
        assertEquals(ShiftStatus.COMPLETED, summary.stored.shift.status)
        assertEquals(clock.now, summary.stored.breaks.single().endedAt)
        assertMoney("10", summary.estimate.breakdown.totalEstimatedPay)
        clock.advance(100); vm.refreshTime()
        assertEquals(summary, vm.state.value)
        vm.dismissSummary(); advanceUntilIdle()
        assertEquals(LiveShiftState.Idle(profile), vm.state.value)
    }

    @Test fun `working shift restored from storage uses snapshot not changed profile`() = runTest {
        val store = FakeStore(profile)
        val vm = LiveShiftViewModel(store, clock)
        advanceUntilIdle(); vm.startShift(); advanceUntilIdle()
        store.profile = profile.copy(condition = profile.condition.copy(hourlyWage = BigDecimal("7200")))
        clock.advance(60)
        val restored = LiveShiftViewModel(store, clock)
        advanceUntilIdle()
        assertFalse(active(restored).onBreak)
        assertMoney("60", money(restored))
    }

    @Test fun `open break restored after time jump remains unpaid`() = runTest {
        val store = FakeStore(profile)
        val vm = LiveShiftViewModel(store, clock)
        advanceUntilIdle(); vm.startShift(); advanceUntilIdle()
        clock.advance(10); vm.startBreak(); advanceUntilIdle()
        clock.advance(3600)
        val restored = LiveShiftViewModel(store, clock)
        advanceUntilIdle()
        assertTrue(active(restored).onBreak)
        assertMoney("10", money(restored))
        assertEquals(Duration.ofSeconds(3610), active(restored).estimate.elapsed)
    }

    @Test fun `active shift wins when no current profile exists`() = runTest {
        val store = FakeStore(profile)
        val vm = LiveShiftViewModel(store, clock)
        advanceUntilIdle(); vm.startShift(); advanceUntilIdle()
        store.profile = null
        val restored = LiveShiftViewModel(store, clock)
        advanceUntilIdle()
        assertTrue(restored.state.value is LiveShiftState.Active)
    }

    @Test fun `load errors can retry`() = runTest {
        val store = FakeStore(profile).apply { failLoad = true }
        val vm = LiveShiftViewModel(store, clock)
        advanceUntilIdle()
        assertTrue(vm.state.value is LiveShiftState.Error)
        store.failLoad = false; vm.reload(); advanceUntilIdle()
        assertTrue(vm.state.value is LiveShiftState.Idle)
    }

    @Test fun `start error keeps idle for retry`() = runTest {
        val store = FakeStore(profile).apply { failWrite = true }
        val vm = LiveShiftViewModel(store, clock)
        advanceUntilIdle(); vm.startShift(); advanceUntilIdle()
        val idle = vm.state.value as LiveShiftState.Idle
        assertNotNull(idle.error); assertFalse(idle.isStarting)
        store.failWrite = false; vm.startShift(); advanceUntilIdle()
        assertTrue(vm.state.value is LiveShiftState.Active)
    }

    @Test fun `break and completion failures preserve active record for retry`() = runTest {
        val store = FakeStore(profile)
        val vm = LiveShiftViewModel(store, clock)
        advanceUntilIdle(); vm.startShift(); advanceUntilIdle()
        clock.advance(1); store.failWrite = true
        vm.startBreak(); advanceUntilIdle()
        assertNotNull(active(vm).error); assertFalse(active(vm).onBreak)
        vm.finishShift(); advanceUntilIdle()
        assertNull(active(vm).stored.shift.endedAt)
        assertNull(active(vm).operation)
        store.failWrite = false; vm.finishShift(); advanceUntilIdle()
        assertTrue(vm.state.value is LiveShiftState.Summary)
    }

    @Test fun `pending completion disables other actions`() = runTest {
        val store = FakeStore(profile)
        val vm = LiveShiftViewModel(store, clock)
        advanceUntilIdle(); vm.startShift(); advanceUntilIdle()
        clock.advance(1); store.gate = CompletableDeferred()
        vm.finishShift(); runCurrent(); vm.finishShift(); vm.startBreak(); vm.reload()
        assertEquals(ShiftOperation.COMPLETING, active(vm).operation)
        assertEquals(2, store.calls)
        store.gate!!.complete(Unit); advanceUntilIdle()
    }

    @Test fun `ticker starts only in foreground and can be stopped without DB updates`() = runTest {
        val store = FakeStore(profile)
        val vm = LiveShiftViewModel(store, clock)
        advanceUntilIdle(); vm.startShift(); advanceUntilIdle()
        vm.setForeground(true); runCurrent()
        clock.advance(1); advanceTimeBy(1000); runCurrent()
        assertMoney("1", money(vm))
        vm.setForeground(false)
        clock.advance(60); advanceTimeBy(5000); runCurrent()
        assertMoney("1", money(vm))
        vm.setForeground(true); runCurrent()
        assertMoney("61", money(vm))
        vm.setForeground(false)
        assertEquals(1, store.calls)
    }

    @Test fun `backwards clock does not reverse displayed time or pay`() = runTest {
        val vm = LiveShiftViewModel(FakeStore(profile), clock)
        advanceUntilIdle(); vm.startShift(); advanceUntilIdle()
        clock.advance(10); vm.refreshTime()
        clock.advance(-20); vm.refreshTime()
        assertMoney("10", money(vm))
        assertEquals(Duration.ofSeconds(10), active(vm).estimate.elapsed)
    }

    @Test fun `whole won display truncates only for display and duration includes days`() {
        assertEquals("12,345", formatEstimatedPay(BigDecimal("12345.999")))
        assertEquals("49:01:02", formatDuration(Duration.ofHours(49).plusMinutes(1).plusSeconds(2)))
    }
}
