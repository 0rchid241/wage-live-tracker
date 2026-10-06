package com.orchid.wagelivetracker.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.orchid.wagelivetracker.data.local.database.WageDatabase
import com.orchid.wagelivetracker.data.local.entity.BreakEntity
import com.orchid.wagelivetracker.data.repository.*
import com.orchid.wagelivetracker.domain.wage.WorkCondition
import java.math.BigDecimal
import java.time.LocalDateTime
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class LiveShiftRepositoryTest {
    private lateinit var database: WageDatabase
    private lateinit var repository: WorkRepository
    private val start = LocalDateTime.parse("2026-10-06T21:00:00")

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, WageDatabase::class.java).build()
        repository = WorkRepository(database)
    }
    @After fun tearDown() { database.close() }
    private suspend fun shift(): ShiftRecord {
        val profile = repository.saveProfile(WorkProfile(nickname = "카페", condition = WorkCondition(BigDecimal("10000"), true), createdAt = start))
        return repository.startShift(profile.id, start)
    }
    private suspend fun failure(action: suspend () -> Unit) {
        try { action() } catch (_: Exception) { return }
        fail("Invalid operation should have failed")
    }

    @Test fun startAndEndBreakAreStoredAtomically() = runBlocking<Unit> {
        val shift = shift()
        val paused = repository.startBreak(shift.id, start.plusHours(1))
        assertNull(paused.breaks.single().endedAt)
        assertEquals(paused.breaks.single(), repository.getOpenBreak(shift.id))
        val resumed = repository.endBreak(shift.id, start.plusHours(2))
        assertEquals(start.plusHours(2), resumed.breaks.single().endedAt)
        assertNull(repository.getOpenBreak(shift.id))
    }

    @Test fun finishWhilePausedClosesBreakAndShiftTogether() = runBlocking<Unit> {
        val shift = shift()
        repository.startBreak(shift.id, start.plusHours(1))
        val completed = repository.finishShift(shift.id, start.plusHours(3))
        assertEquals(ShiftStatus.COMPLETED, completed.shift.status)
        assertEquals(completed.shift.endedAt, completed.breaks.single().endedAt)
        assertNull(repository.getInProgressShift())
        val input = completed.toWageInput()
        val pay = com.orchid.wagelivetracker.domain.wage.WageCalculator().calculate(input.workPeriod, input.condition, input.breaks)
        assertEquals(0, BigDecimal("10000").compareTo(pay.totalEstimatedPay))
    }

    @Test fun duplicateStartsAndEndsAreRejected() = runBlocking<Unit> {
        val shift = shift()
        failure { repository.endBreak(shift.id, start.plusMinutes(1)) }
        repository.startBreak(shift.id, start.plusMinutes(1))
        failure { repository.startBreak(shift.id, start.plusMinutes(2)) }
        repository.endBreak(shift.id, start.plusMinutes(3))
        failure { repository.endBreak(shift.id, start.plusMinutes(4)) }
        assertEquals(1, repository.getShift(shift.id)!!.breaks.size)
    }

    @Test fun concurrentBreakStartsAllowExactlyOne() = runBlocking<Unit> {
        val shift = shift()
        val results = coroutineScope {
            listOf(repository, WorkRepository(database)).map { repo ->
                async { runCatching { repo.startBreak(shift.id, start.plusMinutes(1)) } }
            }.map { it.await() }
        }
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, repository.getShift(shift.id)!!.breaks.size)
    }

    @Test fun sameInstantResumeRemovesZeroLengthBreak() = runBlocking<Unit> {
        val shift = shift()
        repository.startBreak(shift.id, start)
        assertTrue(repository.endBreak(shift.id, start).breaks.isEmpty())
        assertTrue(repository.finishShift(shift.id, start.plusSeconds(1)).breaks.isEmpty())
    }

    @Test fun finishingAtBreakStartDiscardsOnlyZeroLengthBreak() = runBlocking<Unit> {
        val shift = shift()
        repository.startBreak(shift.id, start.plusSeconds(1))
        val completed = repository.finishShift(shift.id, start.plusSeconds(1))
        assertTrue(completed.breaks.isEmpty())
        assertEquals(ShiftStatus.COMPLETED, completed.shift.status)
    }

    @Test fun invalidSameInstantFinishRollsBackZeroBreakRemoval() = runBlocking<Unit> {
        val shift = shift()
        repository.startBreak(shift.id, start)
        failure { repository.finishShift(shift.id, start) }
        assertNotNull(repository.getOpenBreak(shift.id))
        assertEquals(ShiftStatus.IN_PROGRESS, repository.getShift(shift.id)!!.shift.status)
    }

    @Test fun failingShiftWriteRollsBackBreakClosure() = runBlocking<Unit> {
        val shift = shift()
        repository.startBreak(shift.id, start.plusMinutes(1))
        // Fault injection only in this in-memory test: fail AFTER the break update has succeeded.
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_finish BEFORE UPDATE ON shifts BEGIN SELECT RAISE(ABORT, 'injected failure'); END")
        failure { repository.finishShift(shift.id, start.plusMinutes(2)) }
        assertNull(repository.getShift(shift.id)!!.shift.endedAt)
        assertNull(repository.getOpenBreak(shift.id)!!.endedAt)
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_finish")
        assertEquals(ShiftStatus.COMPLETED, repository.finishShift(shift.id, start.plusMinutes(2)).shift.status)
    }

    @Test fun backwardsEndAndFinishAreRejectedWithoutPartialWrites() = runBlocking<Unit> {
        val shift = shift()
        repository.startBreak(shift.id, start.plusMinutes(1))
        failure { repository.endBreak(shift.id, start) }
        failure { repository.finishShift(shift.id, start) }
        assertNotNull(repository.getOpenBreak(shift.id))
        assertNull(repository.getShift(shift.id)!!.shift.endedAt)
    }

    @Test fun completedShiftRejectsFurtherBreakAndFinishActions() = runBlocking<Unit> {
        val shift = shift()
        repository.finishShift(shift.id, start.plusMinutes(1))
        failure { repository.startBreak(shift.id, start.plusMinutes(2)) }
        failure { repository.endBreak(shift.id, start.plusMinutes(2)) }
        failure { repository.finishShift(shift.id, start.plusMinutes(2)) }
    }

    @Test fun malformedMultipleOpenBreaksCannotBeSilentlyClosed() = runBlocking<Unit> {
        val shift = shift()
        repository.startBreak(shift.id, start.plusMinutes(1))
        database.breakDao().insert(BreakEntity(shiftId = shift.id, startedAt = start.plusMinutes(2), endedAt = null))
        failure { repository.getOpenBreak(shift.id) }
        failure { repository.endBreak(shift.id, start.plusMinutes(3)) }
        failure { repository.finishShift(shift.id, start.plusMinutes(3)) }
        assertNull(repository.getShift(shift.id)!!.shift.endedAt)
    }

    @Test fun pausedShiftRecoversAfterFileDatabaseIsReopened() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "live-shift-recovery-test.db"
        context.deleteDatabase(name)
        var db = Room.databaseBuilder(context, WageDatabase::class.java, name).build()
        try {
            var repo = WorkRepository(db)
            val profile = repo.saveProfile(WorkProfile(nickname = "복구", condition = WorkCondition(BigDecimal("12000"), true), createdAt = start))
            val shift = repo.startShift(profile.id, start)
            val original = repo.startBreak(shift.id, start.plusHours(1))
            db.close()
            db = Room.databaseBuilder(context, WageDatabase::class.java, name).build()
            repo = WorkRepository(db)
            assertEquals(original, repo.getInProgressShift())
            assertEquals(original.breaks.single(), repo.getOpenBreak(shift.id))
            repo.endBreak(shift.id, start.plusHours(2))
            assertEquals(ShiftStatus.COMPLETED, repo.finishShift(shift.id, start.plusHours(3)).shift.status)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
