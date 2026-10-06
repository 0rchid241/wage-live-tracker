package com.orchid.wagelivetracker.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.orchid.wagelivetracker.data.local.database.WageDatabase
import com.orchid.wagelivetracker.data.repository.*
import com.orchid.wagelivetracker.domain.wage.WorkCondition
import java.math.BigDecimal
import java.time.LocalDateTime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*

class ShiftSessionRepositoryTest {
    private lateinit var database: WageDatabase
    private lateinit var repository: WorkRepository
    private val start = LocalDateTime.parse("2026-10-06T12:00:00")
    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, WageDatabase::class.java).build()
        repository = WorkRepository(database)
    }
    @After fun tearDown() { database.close() }
    private suspend fun session(): StoredShift {
        val profile = repository.saveProfile(WorkProfile(nickname = "카페", condition = WorkCondition(BigDecimal("3600"), true), createdAt = start))
        repository.startShift(profile.id, start)
        return repository.getInProgressShift()!!
    }
    @Test fun concurrentSameTokenPausesExactlyOnceAcrossRepositoryInstances() = runBlocking<Unit> {
        val session = session()
        val results = coroutineScope {
            List(10) { async { WorkRepository(database).applyNotificationAction(session.shift.id, session.sessionRevision(), ShiftNotificationAction.BREAK, start.plusSeconds(10)) } }.awaitAll()
        }
        assertEquals(1, results.count { it != null }); assertEquals(1, repository.getInProgressShift()!!.breaks.size)
    }
    @Test fun previousPauseAndResumeTokensCannotMutateLaterCycles() = runBlocking<Unit> {
        val first = session()
        val paused = repository.applyNotificationAction(first.shift.id, first.sessionRevision(), ShiftNotificationAction.BREAK, start.plusSeconds(10))!!
        val resumed = repository.applyNotificationAction(first.shift.id, paused.sessionRevision(), ShiftNotificationAction.RESUME, start.plusSeconds(20))!!
        assertNull(repository.applyNotificationAction(first.shift.id, first.sessionRevision(), ShiftNotificationAction.BREAK, start.plusSeconds(30)))
        val pausedAgain = repository.applyNotificationAction(first.shift.id, resumed.sessionRevision(), ShiftNotificationAction.BREAK, start.plusSeconds(40))!!
        assertNull(repository.applyNotificationAction(first.shift.id, paused.sessionRevision(), ShiftNotificationAction.RESUME, start.plusSeconds(50)))
        assertEquals(pausedAgain, repository.getInProgressShift())
    }
    @Test fun oldCompletedShiftTokenCannotFinishNewShift() = runBlocking<Unit> {
        val old = session()
        repository.applyNotificationAction(old.shift.id, old.sessionRevision(), ShiftNotificationAction.FINISH, start.plusSeconds(10))
        val current = session()
        assertNull(repository.applyNotificationAction(old.shift.id, old.sessionRevision(), ShiftNotificationAction.FINISH, start.plusSeconds(20)))
        assertEquals(current, repository.getInProgressShift())
    }
    @Test fun failedFinishRollsBackClosedBreakAndCompletionTogether() = runBlocking<Unit> {
        val session = session()
        val paused = repository.startBreak(session.shift.id, start.plusSeconds(10))
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_notification_finish BEFORE UPDATE ON shifts BEGIN SELECT RAISE(ABORT, 'injected failure'); END")
        val result = runCatching { repository.applyNotificationAction(session.shift.id, paused.sessionRevision(), ShiftNotificationAction.FINISH, start.plusSeconds(20)) }
        assertTrue(result.isFailure); assertEquals(paused, repository.getInProgressShift())
        assertNull(repository.getShift(session.shift.id)!!.breaks.single().endedAt)
    }
    @Test fun observationIncludesBreakChangesAndFinishRemoval() = runBlocking<Unit> {
        val session = session()
        val observation = async { withTimeout(5_000) { repository.observeInProgressShift().first { it?.breaks?.any { rest -> rest.endedAt == null } == true } } }
        repository.startBreak(session.shift.id, start.plusSeconds(10))
        assertEquals(1, observation.await()!!.breaks.size)
        repository.finishShift(session.shift.id, start.plusSeconds(20))
        assertNull(withTimeout(5_000) { repository.observeInProgressShift().first() })
    }
}
