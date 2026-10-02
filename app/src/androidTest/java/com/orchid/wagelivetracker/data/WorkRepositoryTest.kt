package com.orchid.wagelivetracker.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.orchid.wagelivetracker.data.local.database.WageDatabase
import com.orchid.wagelivetracker.data.local.entity.BreakEntity
import com.orchid.wagelivetracker.data.repository.*
import com.orchid.wagelivetracker.domain.wage.WageCalculator
import com.orchid.wagelivetracker.domain.wage.WorkCondition
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.TimeZone
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkRepositoryTest {
    private lateinit var database: WageDatabase
    private lateinit var repository: WorkRepository
    private val start = LocalDateTime.parse("2026-10-02T21:00:00.123456789")
    private val condition = WorkCondition(BigDecimal("10000.123456789012345678900"), true, BigDecimal("0.5000"), BigDecimal("0.12500"))

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, WageDatabase::class.java,
        ).build()
        repository = WorkRepository(database)
    }

    @After fun tearDown() { database.close() }

    private suspend fun profile(nickname: String = "기본") = repository.saveProfile(WorkProfile(nickname = nickname, condition = condition, createdAt = start))
    private suspend fun shift() = repository.startShift(profile().id, start)
    private suspend inline fun <reified T : Throwable> failure(crossinline action: suspend () -> Unit) {
        try { action() } catch (error: Throwable) {
            if (error is T) return
            throw AssertionError("Expected ${T::class.java.name}, got $error", error)
        }
        fail("Expected ${T::class.java.name}")
    }

    @Test fun profileSaveAndRead() = runBlocking<Unit> {
        val saved = profile()
        assertTrue(saved.id > 0)
        assertEquals(saved, repository.getProfile(saved.id))
        assertEquals(saved, repository.getCurrentProfile())
    }

    @Test fun profileUpdatePreservesIdAndCreatedTime() = runBlocking<Unit> {
        val saved = profile()
        val updated = saved.copy(nickname = "변경", condition = condition.copy(hourlyWage = BigDecimal("15000")), updatedAt = start.plusDays(1))
        repository.saveProfile(updated)
        assertEquals(updated, repository.getProfile(saved.id))
    }

    @Test fun selectingCurrentProfileKeepsOtherProfiles() = runBlocking<Unit> {
        val first = profile("첫 번째")
        val second = profile("두 번째")
        assertEquals(second, repository.getCurrentProfile())
        assertEquals(first, repository.getProfile(first.id))
        repository.saveProfile(first)
        assertEquals(first, repository.getCurrentProfile())
    }

    @Test fun shiftSaveReadAndRecoveryThroughNewRepository() = runBlocking<Unit> {
        val saved = shift()
        assertEquals(saved, repository.getShift(saved.id)?.shift)
        assertEquals(saved, WorkRepository(database).getInProgressShift()?.shift)
        assertEquals(condition, saved.conditionSnapshot)
    }

    @Test fun openBreakSaveCloseUpdateAndRead() = runBlocking<Unit> {
        val saved = shift()
        val rest = repository.saveBreak(BreakRecord(shiftId = saved.id, startedAt = start.plusHours(1)))
        assertNull(repository.getShift(saved.id)!!.breaks.single().endedAt)
        val closed = repository.saveBreak(rest.copy(endedAt = start.plusHours(2)))
        assertEquals(closed, repository.getShift(saved.id)!!.breaks.single())
    }

    @Test fun relationLoadsMultipleBreaksInChronologicalOrder() = runBlocking<Unit> {
        val saved = shift()
        val later = repository.saveBreak(BreakRecord(shiftId = saved.id, startedAt = start.plusHours(4), endedAt = start.plusHours(5)))
        val earlier = repository.saveBreak(BreakRecord(shiftId = saved.id, startedAt = start.plusHours(1), endedAt = start.plusHours(2)))
        assertEquals(listOf(earlier, later), repository.getShift(saved.id)!!.breaks)
        assertEquals(2, database.shiftDao().getWithBreaks(saved.id)!!.breaks.size)
    }

    @Test fun completedShiftIsExcludedFromInProgressQuery() = runBlocking<Unit> {
        val saved = shift()
        val completed = repository.completeShift(saved.id, start.plusHours(10))
        assertEquals(ShiftStatus.COMPLETED, completed.shift.status)
        assertEquals(start.plusHours(10), completed.shift.endedAt)
        assertNull(repository.getInProgressShift())
        assertTrue(database.shiftDao().getInProgress().isEmpty())
    }

    @Test fun allSnapshotsSurviveProfileChangeAndFeedExistingCalculator() = runBlocking<Unit> {
        val savedProfile = profile()
        val saved = repository.startShift(savedProfile.id, start)
        repository.saveBreak(BreakRecord(shiftId = saved.id, startedAt = start.plusHours(2), endedAt = start.plusHours(3).plusMinutes(30)))
        val completed = repository.completeShift(saved.id, start.plusHours(10))
        val beforeInput = completed.toWageInput()
        val before = WageCalculator().calculate(beforeInput.workPeriod, beforeInput.condition, beforeInput.breaks)
        repository.saveProfile(savedProfile.copy(condition = WorkCondition(BigDecimal("25000"), false, BigDecimal("0.75"), BigDecimal("0.5")), updatedAt = start.plusDays(1)))
        val afterInput = repository.getShift(saved.id)!!.toWageInput()
        assertEquals(condition, afterInput.condition)
        assertEquals(before, WageCalculator().calculate(afterInput.workPeriod, afterInput.condition, afterInput.breaks))
    }

    @Test fun deletingShiftCascadesBreaks() = runBlocking<Unit> {
        val saved = shift()
        val rest = repository.saveBreak(BreakRecord(shiftId = saved.id, startedAt = start.plusHours(1)))
        assertTrue(repository.deleteShift(saved.id))
        assertNull(database.breakDao().getById(rest.id))
        assertNull(repository.getShift(saved.id))
    }

    @Test fun referencedProfileDeletionIsRestrictedAndHistorySurvives() = runBlocking<Unit> {
        val saved = shift()
        failure<SQLiteConstraintException> { repository.deleteProfile(saved.workProfileId) }
        assertNotNull(repository.getShift(saved.id))
        assertNotNull(repository.getProfile(saved.workProfileId))
    }

    @Test fun unreferencedProfileAndBreakCanBeDeleted() = runBlocking<Unit> {
        val unused = profile()
        assertTrue(repository.deleteProfile(unused.id))
        val saved = shift()
        val rest = repository.saveBreak(BreakRecord(shiftId = saved.id, startedAt = start.plusHours(1)))
        assertTrue(repository.deleteBreak(rest.id))
        assertTrue(repository.getShift(saved.id)!!.breaks.isEmpty())
    }

    @Test fun localTimeSurvivesTimezoneChangeAndDatabaseRoundTrip() = runBlocking<Unit> {
        val originalZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
            val saved = shift()
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            assertEquals(start, repository.getShift(saved.id)!!.shift.startedAt)
            assertEquals(start, repository.getProfile(saved.workProfileId)!!.createdAt)
        } finally { TimeZone.setDefault(originalZone) }
    }

    @Test fun decimalScaleAndStatusSurviveActualDatabaseRoundTrip() = runBlocking<Unit> {
        val saved = shift()
        assertEquals(condition, repository.getShift(saved.id)!!.shift.conditionSnapshot)
        assertEquals(ShiftStatus.IN_PROGRESS, repository.getShift(saved.id)!!.shift.status)
        repository.completeShift(saved.id, start.plusHours(1))
        assertEquals(ShiftStatus.COMPLETED, repository.getShift(saved.id)!!.shift.status)
        val cursor = database.openHelper.readableDatabase.query("SELECT status, hourlyWageSnapshot FROM shifts")
        cursor.use {
            assertTrue(it.moveToFirst())
            assertEquals("COMPLETED", it.getString(0))
            assertEquals(condition.hourlyWage.toString(), it.getString(1))
        }
    }

    @Test fun invalidForeignKeysFailAtDatabaseBoundary() = runBlocking<Unit> {
        failure<SQLiteConstraintException> { database.breakDao().insert(BreakEntity(shiftId = 9999, startedAt = start, endedAt = null)) }
        val saved = shift()
        val entity = database.shiftDao().getById(saved.id)!!
        failure<SQLiteConstraintException> { database.shiftDao().insert(entity.copy(id = 0, workProfileId = 9999)) }
    }

    @Test fun concurrentRepositoriesCannotStartTwoShifts() = runBlocking<Unit> {
        val saved = profile()
        val results = coroutineScope {
            listOf(repository, WorkRepository(database)).map { repo ->
                async { runCatching { repo.startShift(saved.id, start) } }
            }.map { it.await() }
        }
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, database.shiftDao().getInProgress().size)
    }

    @Test fun corruptedMultipleActiveShiftsAreReportedInsteadOfHidden() = runBlocking<Unit> {
        val saved = shift()
        database.shiftDao().insert(database.shiftDao().getById(saved.id)!!.copy(id = 0))
        failure<IllegalStateException> { repository.getInProgressShift() }
        failure<IllegalStateException> { repository.startShift(saved.workProfileId, start) }
    }

    @Test fun completionWithOpenBreakFailsAndRollsBack() = runBlocking<Unit> {
        val saved = shift()
        repository.saveBreak(BreakRecord(shiftId = saved.id, startedAt = start.plusHours(1)))
        failure<IllegalArgumentException> { repository.completeShift(saved.id, start.plusHours(2)) }
        assertEquals(ShiftStatus.IN_PROGRESS, repository.getShift(saved.id)!!.shift.status)
    }

    @Test fun overlappingAndMultipleOpenBreaksAreRejected() = runBlocking<Unit> {
        val saved = shift()
        val rest = repository.saveBreak(BreakRecord(shiftId = saved.id, startedAt = start.plusHours(1)))
        failure<IllegalArgumentException> { repository.saveBreak(BreakRecord(shiftId = saved.id, startedAt = start.plusHours(2))) }
        repository.saveBreak(rest.copy(endedAt = start.plusHours(3)))
        failure<IllegalArgumentException> { repository.saveBreak(BreakRecord(shiftId = saved.id, startedAt = start.plusHours(2), endedAt = start.plusHours(4))) }
        assertEquals(1, repository.getShift(saved.id)!!.breaks.size)
    }

    @Test fun invalidShiftAndBreakTimesAreRejected() = runBlocking<Unit> {
        val saved = shift()
        failure<IllegalArgumentException> { repository.completeShift(saved.id, start) }
        failure<IllegalArgumentException> { repository.completeShift(saved.id, start.minusHours(1)) }
        failure<IllegalArgumentException> { repository.saveBreak(BreakRecord(shiftId = saved.id, startedAt = start.minusHours(1))) }
        repository.completeShift(saved.id, start.plusHours(2))
        failure<IllegalArgumentException> { repository.saveBreak(BreakRecord(shiftId = saved.id, startedAt = start.plusHours(1), endedAt = start.plusHours(3))) }
    }

    @Test fun shiftTimeEditPreservesSnapshotAndValidatesExistingBreaks() = runBlocking<Unit> {
        val saved = shift()
        repository.saveBreak(BreakRecord(shiftId = saved.id, startedAt = start.plusHours(1), endedAt = start.plusHours(2)))
        repository.completeShift(saved.id, start.plusHours(3))
        val updated = repository.updateShiftTimes(saved.id, start.minusHours(1), start.plusHours(4))
        assertEquals(condition, updated.shift.conditionSnapshot)
        failure<IllegalArgumentException> { repository.updateShiftTimes(saved.id, start.plusHours(2), start.plusHours(4)) }
        assertEquals(updated, repository.getShift(saved.id))
    }

    @Test fun touchingBreaksAreAllowedAndCompletedBreakCanBeEdited() = runBlocking<Unit> {
        val saved = shift()
        val first = repository.saveBreak(BreakRecord(shiftId = saved.id, startedAt = start, endedAt = start.plusHours(1)))
        repository.saveBreak(BreakRecord(shiftId = saved.id, startedAt = start.plusHours(1), endedAt = start.plusHours(2)))
        repository.completeShift(saved.id, start.plusHours(3))
        repository.saveBreak(first.copy(endedAt = start.plusMinutes(30)))
        assertEquals(2, repository.getShift(saved.id)!!.breaks.size)
    }

    @Test fun fileDatabaseRecoversOpenShiftAndBreakAfterReopen() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "storage-recovery-test.db"
        context.deleteDatabase(name)
        var fileDatabase = Room.databaseBuilder(context, WageDatabase::class.java, name).build()
        try {
            var repo = WorkRepository(fileDatabase)
            val savedProfile = repo.saveProfile(WorkProfile(nickname = "복구", condition = condition, createdAt = start))
            val saved = repo.startShift(savedProfile.id, start)
            val rest = repo.saveBreak(BreakRecord(shiftId = saved.id, startedAt = start.plusHours(1)))
            fileDatabase.close()
            fileDatabase = Room.databaseBuilder(context, WageDatabase::class.java, name).build()
            repo = WorkRepository(fileDatabase)
            assertEquals(saved, repo.getInProgressShift()!!.shift)
            assertEquals(listOf(rest), repo.getInProgressShift()!!.breaks)
            assertEquals(savedProfile, repo.getCurrentProfile())
        } finally {
            fileDatabase.close()
            context.deleteDatabase(name)
        }
    }
}
