package com.orchid.wagelivetracker.ui.profile

import com.orchid.wagelivetracker.data.repository.WorkProfile
import com.orchid.wagelivetracker.data.repository.WorkProfileStore
import com.orchid.wagelivetracker.domain.wage.WorkCondition
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkProfileSetupViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = Clock.fixed(Instant.parse("2026-10-02T12:00:00Z"), ZoneId.of("Asia/Seoul"))
    private val now = LocalDateTime.now(clock)
    private val existing = WorkProfile(7, "PC방", WorkCondition(BigDecimal("12000.000"), true, BigDecimal("0.75"), BigDecimal("0.25")), now.minusDays(2), now.minusDays(1))

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private class FakeStore(var current: WorkProfile? = null) : WorkProfileStore {
        var loadFails = false
        var saveFails = false
        var saveCalls = 0
        var gate: CompletableDeferred<Unit>? = null
        var selected = false
        override suspend fun getCurrentProfile(): WorkProfile? {
            if (loadFails) error("load failure")
            return current
        }
        override suspend fun saveProfile(profile: WorkProfile, makeCurrent: Boolean): WorkProfile {
            saveCalls++
            gate?.await()
            if (saveFails) error("save failure")
            selected = makeCurrent
            return profile.copy(id = if (profile.id == 0L) 1 else profile.id).also { current = it }
        }
    }

    private fun form(vm: WorkProfileSetupViewModel) = vm.state.value as WorkProfileSetupState.Form
    private fun fill(vm: WorkProfileSetupViewModel, wage: String = "12000") {
        vm.changeWage(wage)
        vm.chooseEmployeeCount(true)
    }

    @Test fun `missing profile loads first setup with no guessed employee count`() = runTest {
        val vm = WorkProfileSetupViewModel(FakeStore(), clock)
        assertEquals(WorkProfileSetupState.Loading, vm.state.value)
        advanceUntilIdle()
        assertNull(form(vm).original)
        assertNull(form(vm).hasAtLeastFiveEmployees)
    }

    @Test fun `existing profile loads ready without forcing form`() = runTest {
        val vm = WorkProfileSetupViewModel(FakeStore(existing), clock)
        advanceUntilIdle()
        assertEquals(WorkProfileSetupState.Ready(existing), vm.state.value)
    }

    @Test fun `blank hourly wage is rejected`() = runTest {
        val store = FakeStore()
        val vm = WorkProfileSetupViewModel(store, clock)
        advanceUntilIdle()
        fill(vm, "  ")
        vm.save()
        assertEquals("시급을 입력해 주세요.", form(vm).wageError)
        assertEquals(0, store.saveCalls)
    }

    @Test fun `invalid numbers exponents commas and huge pasted strings are rejected`() = runTest {
        val store = FakeStore()
        val vm = WorkProfileSetupViewModel(store, clock)
        advanceUntilIdle()
        for (invalid in listOf("abc", "NaN", "Infinity", "1e999999999", "12,000", "12.0.0", "9".repeat(10000))) {
            fill(vm, invalid)
            vm.save()
            assertNotNull(form(vm).wageError)
        }
        assertEquals(0, store.saveCalls)
    }

    @Test fun `zero and negative wages are rejected`() = runTest {
        val store = FakeStore()
        val vm = WorkProfileSetupViewModel(store, clock)
        advanceUntilIdle()
        for (invalid in listOf("0", "0.000", "-1", "-12000.5")) {
            fill(vm, invalid)
            vm.save()
            assertEquals("시급은 0원보다 커야 해요.", form(vm).wageError)
        }
        assertEquals(0, store.saveCalls)
    }

    @Test fun `wage above input guard is rejected`() = runTest {
        val vm = WorkProfileSetupViewModel(FakeStore(), clock)
        advanceUntilIdle()
        fill(vm, "1000000.01")
        vm.save()
        assertEquals("시급은 1,000,000원 이하로 입력해 주세요.", form(vm).wageError)
    }

    @Test fun `maximum guarded wage is accepted`() = runTest {
        val store = FakeStore()
        val vm = WorkProfileSetupViewModel(store, clock)
        advanceUntilIdle()
        fill(vm, "1000000")
        vm.save()
        advanceUntilIdle()
        assertEquals(BigDecimal("1000000"), store.current!!.condition.hourlyWage)
    }

    @Test fun `employee count must be explicitly selected`() = runTest {
        val store = FakeStore()
        val vm = WorkProfileSetupViewModel(store, clock)
        advanceUntilIdle()
        vm.changeWage("12000")
        vm.save()
        assertNotNull(form(vm).employeeError)
        assertEquals(0, store.saveCalls)
    }

    @Test fun `valid new profile uses domain rates injected time and current selection`() = runTest {
        val store = FakeStore()
        val vm = WorkProfileSetupViewModel(store, clock)
        advanceUntilIdle()
        fill(vm)
        vm.changeNickname("  카페  ")
        vm.save()
        assertTrue(form(vm).isSaving)
        advanceUntilIdle()
        val saved = store.current!!
        assertEquals("카페", saved.nickname)
        assertEquals(WorkCondition(BigDecimal("12000"), true), saved.condition)
        assertEquals(now, saved.createdAt)
        assertEquals(now, saved.updatedAt)
        assertTrue(store.selected)
        assertEquals(WorkProfileSetupState.Ready(saved), vm.state.value)
    }

    @Test fun `empty nickname and below five employees are valid`() = runTest {
        val store = FakeStore()
        val vm = WorkProfileSetupViewModel(store, clock)
        advanceUntilIdle()
        vm.changeWage("12000")
        vm.changeNickname("   ")
        vm.chooseEmployeeCount(false)
        vm.save()
        advanceUntilIdle()
        assertEquals("", store.current!!.nickname)
        assertFalse(store.current!!.condition.hasAtLeastFiveEmployees)
    }

    @Test fun `low wage is allowed without minimum wage gate`() = runTest {
        val store = FakeStore()
        val vm = WorkProfileSetupViewModel(store, clock)
        advanceUntilIdle()
        fill(vm, "1")
        vm.save()
        advanceUntilIdle()
        assertEquals(BigDecimal.ONE, store.current!!.condition.hourlyWage)
    }

    @Test fun `fractional hourly wage is preserved without floating point conversion`() = runTest {
        val store = FakeStore()
        val vm = WorkProfileSetupViewModel(store, clock)
        advanceUntilIdle()
        fill(vm, "12000.123456789012345678900")
        vm.save()
        advanceUntilIdle()
        assertEquals(BigDecimal("12000.123456789012345678900"), store.current!!.condition.hourlyWage)
    }

    @Test fun `edit prefills normalized display and existing employee selection`() = runTest {
        val vm = WorkProfileSetupViewModel(FakeStore(existing), clock)
        advanceUntilIdle()
        vm.editProfile()
        assertEquals("12000", form(vm).hourlyWage)
        assertEquals("PC방", form(vm).nickname)
        assertEquals(true, form(vm).hasAtLeastFiveEmployees)
    }

    @Test fun `editing keeps id creation and rates while updating timestamp`() = runTest {
        val store = FakeStore(existing)
        val vm = WorkProfileSetupViewModel(store, clock)
        advanceUntilIdle()
        vm.editProfile()
        vm.changeWage("15000")
        vm.chooseEmployeeCount(false)
        vm.save()
        advanceUntilIdle()
        val saved = store.current!!
        assertEquals(existing.id, saved.id)
        assertEquals(existing.createdAt, saved.createdAt)
        assertEquals(now, saved.updatedAt)
        assertEquals(existing.condition.nightPremiumRate, saved.condition.nightPremiumRate)
        assertEquals(existing.condition.overtimePremiumRate, saved.condition.overtimePremiumRate)
        assertEquals(BigDecimal("15000"), saved.condition.hourlyWage)
        assertFalse(saved.condition.hasAtLeastFiveEmployees)
    }

    @Test fun `unchanged normalized wage keeps original decimal scale`() = runTest {
        val store = FakeStore(existing)
        val vm = WorkProfileSetupViewModel(store, clock)
        advanceUntilIdle()
        vm.editProfile()
        vm.changeNickname("새 별명")
        vm.save()
        advanceUntilIdle()
        assertEquals(BigDecimal("12000.000"), store.current!!.condition.hourlyWage)
    }

    @Test fun `backwards clock respects repository monotonic timestamp policy`() = runTest {
        val future = existing.copy(updatedAt = now.plusDays(1))
        val store = FakeStore(future)
        val vm = WorkProfileSetupViewModel(store, clock)
        advanceUntilIdle()
        vm.editProfile()
        vm.save()
        advanceUntilIdle()
        assertEquals(future.updatedAt, store.current!!.updatedAt)
    }

    @Test fun `load failure has retry and does not create a profile`() = runTest {
        val store = FakeStore().apply { loadFails = true }
        val vm = WorkProfileSetupViewModel(store, clock)
        advanceUntilIdle()
        assertTrue(vm.state.value is WorkProfileSetupState.LoadError)
        store.loadFails = false
        vm.retryLoad()
        advanceUntilIdle()
        assertTrue(vm.state.value is WorkProfileSetupState.Form)
        assertEquals(0, store.saveCalls)
    }

    @Test fun `save failure keeps input and permits retry`() = runTest {
        val store = FakeStore().apply { saveFails = true }
        val vm = WorkProfileSetupViewModel(store, clock)
        advanceUntilIdle()
        fill(vm)
        vm.changeNickname("PC방")
        vm.save()
        advanceUntilIdle()
        assertNotNull(form(vm).saveError)
        assertFalse(form(vm).isSaving)
        assertEquals("PC방", form(vm).nickname)
        store.saveFails = false
        vm.save()
        advanceUntilIdle()
        assertTrue(vm.state.value is WorkProfileSetupState.Ready)
    }

    @Test fun `saving blocks repeated submit field changes and cancellation`() = runTest {
        val store = FakeStore(existing).apply { gate = CompletableDeferred() }
        val vm = WorkProfileSetupViewModel(store, clock)
        advanceUntilIdle()
        vm.editProfile()
        vm.save()
        vm.save()
        runCurrent()
        vm.changeWage("999")
        vm.cancelEdit()
        assertTrue(form(vm).isSaving)
        assertEquals("12000", form(vm).hourlyWage)
        assertEquals(1, store.saveCalls)
        store.gate!!.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.state.value is WorkProfileSetupState.Ready)
    }

    @Test fun `cancel editing restores persisted profile without write`() = runTest {
        val store = FakeStore(existing)
        val vm = WorkProfileSetupViewModel(store, clock)
        advanceUntilIdle()
        vm.editProfile()
        vm.changeWage("999")
        vm.cancelEdit()
        assertEquals(WorkProfileSetupState.Ready(existing), vm.state.value)
        assertEquals(0, store.saveCalls)
    }

    @Test fun `overlong nickname is rejected before save`() = runTest {
        val store = FakeStore()
        val vm = WorkProfileSetupViewModel(store, clock)
        advanceUntilIdle()
        fill(vm)
        vm.changeNickname("가".repeat(81))
        vm.save()
        assertNotNull(form(vm).nicknameError)
        assertEquals(0, store.saveCalls)
    }

    @Test fun `currency display removes unnecessary zeros without rounding fractions`() {
        assertEquals("12,000", formatWage(BigDecimal("12000.000")))
        assertEquals("12,000.123456789", formatWage(BigDecimal("12000.123456789")))
    }
}
