package com.orchid.wagelivetracker.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.orchid.wagelivetracker.data.local.database.WageDatabase
import com.orchid.wagelivetracker.data.repository.*
import com.orchid.wagelivetracker.domain.wage.WorkCondition
import com.orchid.wagelivetracker.ui.history.HistoryCalculation
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.YearMonth
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class WorkHistoryRepositoryTest {
    private lateinit var database: WageDatabase
    private lateinit var repository: WorkRepository
    private lateinit var profile: WorkProfile
    private val start = LocalDateTime.parse("2026-10-06T09:00:00")
    private val month = YearMonth.of(2026, 10)
    private val condition = WorkCondition(BigDecimal("10000.000"), true, BigDecimal("0.75"), BigDecimal("0.25"))
    @Before fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, WageDatabase::class.java).build()
        repository = WorkRepository(database)
        profile = repository.saveProfile(WorkProfile(nickname = "카페", condition = condition, createdAt = start))
    }
    @After fun tearDown() { database.close() }
    private fun draft(from: LocalDateTime = start, to: LocalDateTime = start.plusHours(2), wage: BigDecimal = condition.hourlyWage,
        breaks: List<HistoryBreak> = emptyList()) = CompletedShiftDraft(from, to, wage, breaks)
    private suspend fun failure(action: suspend () -> Unit) {
        try { action(); fail("Invalid operation was accepted") } catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { }
    }
    private fun money(expected: String, actual: BigDecimal) = assertEquals(0, BigDecimal(expected).compareTo(actual))

    @Test fun monthQueryIncludesOnlyCompletedStartsInSelectedMonthAndSortsParsedNanoseconds() = runBlocking<Unit> {
        val first = repository.addCompletedShift(draft(from = start.plusNanos(1)))
        val second = repository.addCompletedShift(draft(from = start.plusNanos(10)))
        repository.addCompletedShift(draft(start.minusMonths(1), start.minusMonths(1).plusHours(2)))
        val active = repository.startShift(profile.id, start.plusDays(1))
        val rows = repository.getCompletedShifts(month)
        assertEquals(listOf(second.stored.shift.id, first.stored.shift.id), rows.map { it.stored.shift.id })
        assertNull(repository.getCompletedShift(active.id))
        assertTrue(repository.getCompletedShifts(month.plusMonths(1)).isEmpty())
    }

    @Test fun crossingMonthBoundaryBelongsToStartMonthAndUsesSnapshotAfterProfileChange() = runBlocking<Unit> {
        val from = LocalDateTime.parse("2026-10-31T22:00:00")
        repository.addCompletedShift(draft(from, from.plusHours(8)))
        val before = HistoryCalculation().month(month, repository.getCompletedShifts(month))
        money("140000", before.totalPay)
        repository.saveProfile(profile.copy(condition = WorkCondition(BigDecimal("20000"), false), updatedAt = start.plusHours(1)))
        assertEquals(before, HistoryCalculation().month(month, repository.getCompletedShifts(month)))
        assertTrue(repository.getCompletedShifts(month.plusMonths(1)).isEmpty())
    }

    @Test fun manualShiftIsCompletedAndCopiesAllCurrentSnapshotFieldsWithoutChangingProfile() = runBlocking<Unit> {
        val record = repository.addCompletedShift(draft(wage = BigDecimal("12000.123"), breaks = listOf(HistoryBreak(start.plusMinutes(30), start.plusHours(1)))))
        assertEquals(ShiftStatus.COMPLETED, record.stored.shift.status)
        assertEquals(profile.id, record.stored.shift.workProfileId)
        assertEquals(condition.copy(hourlyWage = BigDecimal("12000.123")), record.stored.shift.conditionSnapshot)
        assertEquals(profile, repository.getCurrentProfile())
        assertEquals("카페", record.workplaceName)
        assertNull(repository.getInProgressShift())
        assertTrue(record.stored.breaks.all { it.shiftId == record.stored.shift.id && it.endedAt != null })
    }

    @Test fun manualShiftRequiresCurrentProfileAndLeavesDatabaseUntouchedOnFailure() = runBlocking<Unit> {
        database.workProfileDao().clearCurrent()
        failure { repository.addCompletedShift(draft()) }
        assertTrue(repository.getCompletedShifts(month).isEmpty())
    }

    @Test fun updatingCompletedShiftAtomicallyReplacesTimesWageAndBreaksButPreservesOtherSnapshots() = runBlocking<Unit> {
        val original = repository.addCompletedShift(draft(breaks = listOf(HistoryBreak(start, start.plusMinutes(30)))))
        val changedProfile = repository.saveProfile(profile.copy(condition = WorkCondition(BigDecimal("30000"), false), updatedAt = start.plusHours(1)))
        val saved = repository.updateCompletedShift(original.stored.shift.id,
            draft(start.plusMinutes(30), start.plusHours(3), BigDecimal("12000.00001"), listOf(HistoryBreak(start.plusHours(1), start.plusHours(2)))))
        assertEquals(original.stored.shift.id, saved.stored.shift.id)
        assertEquals(profile.id, saved.stored.shift.workProfileId)
        assertEquals(condition.copy(hourlyWage = BigDecimal("12000.00001")), saved.stored.shift.conditionSnapshot)
        assertEquals(changedProfile, repository.getCurrentProfile())
        assertEquals(1, saved.stored.breaks.size)
        assertEquals(start.plusHours(1), saved.stored.breaks.single().startedAt)
        money("18000.000015", HistoryCalculation().entry(saved).breakdown.totalEstimatedPay)
    }

    @Test fun addingChangingAndRemovingBreaksRecalculatesStoredPay() = runBlocking<Unit> {
        var record = repository.addCompletedShift(draft())
        val id = record.stored.shift.id
        record = repository.updateCompletedShift(id, draft(breaks = listOf(HistoryBreak(start, start.plusMinutes(30)))))
        money("15000", HistoryCalculation().entry(record).breakdown.totalEstimatedPay)
        record = repository.updateCompletedShift(id, draft(breaks = listOf(HistoryBreak(start, start.plusHours(1)))))
        money("10000", HistoryCalculation().entry(record).breakdown.totalEstimatedPay)
        record = repository.updateCompletedShift(id, draft())
        assertTrue(record.stored.breaks.isEmpty()); money("20000", HistoryCalculation().entry(record).breakdown.totalEstimatedPay)
    }

    @Test fun invalidCompletedDraftsAreRejectedForBothManualCreationAndReplacement() = runBlocking<Unit> {
        val original = repository.addCompletedShift(draft(breaks = listOf(HistoryBreak(start, start.plusMinutes(30)))))
        val invalid = listOf(
            draft(to = start), draft(to = start.minusSeconds(1)), draft(wage = BigDecimal.ZERO), draft(wage = BigDecimal("-1")),
            draft(breaks = listOf(HistoryBreak(start, null))), draft(breaks = listOf(HistoryBreak(start, start))),
            draft(breaks = listOf(HistoryBreak(start.plusMinutes(1), start))),
            draft(breaks = listOf(HistoryBreak(start.minusSeconds(1), start.plusSeconds(1)))),
            draft(breaks = listOf(HistoryBreak(start.plusMinutes(1), start.plusHours(3)))),
            draft(breaks = listOf(HistoryBreak(start, start.plusHours(1)), HistoryBreak(start.plusMinutes(30), start.plusHours(2)))))
        invalid.forEach {
            failure { repository.addCompletedShift(it) }
            failure { repository.updateCompletedShift(original.stored.shift.id, it) }
            assertEquals(original, repository.getCompletedShift(original.stored.shift.id))
            assertEquals(1, repository.getCompletedShifts(month).size)
        }
    }

    @Test fun touchingBreaksAndEntireShiftBreakAreAllowed() = runBlocking<Unit> {
        val record = repository.addCompletedShift(draft(breaks = listOf(HistoryBreak(start, start.plusHours(1)), HistoryBreak(start.plusHours(1), start.plusHours(2)))))
        money("0", HistoryCalculation().entry(record).breakdown.totalEstimatedPay)
    }

    @Test fun activeShiftCannotBeEditedOrDeletedThroughHistoryApis() = runBlocking<Unit> {
        val active = repository.startShift(profile.id, start)
        repository.startBreak(active.id, start.plusMinutes(1))
        val previous = repository.getInProgressShift()
        failure { repository.updateCompletedShift(active.id, draft()) }
        failure { repository.deleteCompletedShift(active.id) }
        assertEquals(previous, repository.getInProgressShift())
    }

    @Test fun deletingCompletedShiftCascadesBreaksAndChangesMonthlyTotal() = runBlocking<Unit> {
        val record = repository.addCompletedShift(draft(breaks = listOf(HistoryBreak(start, start.plusMinutes(30)))))
        val second = repository.addCompletedShift(draft(start.plusDays(1), start.plusDays(1).plusHours(1)))
        repository.deleteCompletedShift(record.stored.shift.id)
        assertNull(repository.getShift(record.stored.shift.id))
        assertTrue(database.breakDao().getForShift(record.stored.shift.id).isEmpty())
        assertEquals(listOf(second.stored.shift.id), repository.getCompletedShifts(month).map { it.stored.shift.id })
        money("10000", HistoryCalculation().month(month, repository.getCompletedShifts(month)).totalPay)
        failure { repository.deleteCompletedShift(record.stored.shift.id) }
    }

    @Test fun failingShiftUpdateRollsBackReplacedBreaksAndSnapshot() = runBlocking<Unit> {
        val original = repository.addCompletedShift(draft(breaks = listOf(HistoryBreak(start, start.plusMinutes(30)))))
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_history_update BEFORE UPDATE ON shifts BEGIN SELECT RAISE(ABORT, 'injected failure'); END")
        try {
            repository.updateCompletedShift(original.stored.shift.id, draft(wage = BigDecimal("20000"), breaks = listOf(HistoryBreak(start.plusMinutes(30), start.plusHours(1)))))
            fail("Injected update should fail")
        } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(original, repository.getCompletedShift(original.stored.shift.id))
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_history_update")
        assertEquals(0, BigDecimal("20000").compareTo(repository.updateCompletedShift(original.stored.shift.id, draft(wage = BigDecimal("20000"))).stored.shift.conditionSnapshot.hourlyWage))
    }

    @Test fun failingBreakInsertRollsBackManualShiftCreation() = runBlocking<Unit> {
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_history_insert BEFORE INSERT ON breaks BEGIN SELECT RAISE(ABORT, 'injected failure'); END")
        try {
            repository.addCompletedShift(draft(breaks = listOf(HistoryBreak(start, start.plusMinutes(30)))))
            fail("Injected insert should fail")
        } catch (_: android.database.sqlite.SQLiteException) { }
        assertTrue(repository.getCompletedShifts(month).isEmpty())
        assertNull(repository.getInProgressShift())
        assertEquals(profile, repository.getCurrentProfile())
    }

    @Test fun movingStartAcrossMonthChangesMembershipAndKeepsSingleRecord() = runBlocking<Unit> {
        val original = repository.addCompletedShift(draft())
        val saved = repository.updateCompletedShift(original.stored.shift.id, draft(start.plusMonths(1), start.plusMonths(1).plusHours(2)))
        assertTrue(repository.getCompletedShifts(month).isEmpty())
        assertEquals(listOf(saved), repository.getCompletedShifts(month.plusMonths(1)))
    }

    @Test fun fileDatabaseReopenRetainsManualEditAndDeletion() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "work-history-recovery-test.db"
        context.deleteDatabase(name)
        var db = Room.databaseBuilder(context, WageDatabase::class.java, name).build()
        try {
            var repo = WorkRepository(db)
            repo.saveProfile(profile.copy(id = 0))
            val first = repo.addCompletedShift(draft())
            val second = repo.addCompletedShift(draft(start.plusDays(1), start.plusDays(1).plusHours(2)))
            val updated = repo.updateCompletedShift(first.stored.shift.id, draft(wage = BigDecimal("12345.6789"), breaks = listOf(HistoryBreak(start, start.plusHours(1)))))
            repo.deleteCompletedShift(second.stored.shift.id)
            db.close(); db = Room.databaseBuilder(context, WageDatabase::class.java, name).build(); repo = WorkRepository(db)
            assertEquals(listOf(updated), repo.getCompletedShifts(month))
            assertEquals(updated, repo.getCompletedShift(first.stored.shift.id))
            assertNull(repo.getShift(second.stored.shift.id))
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
