package com.orchid.wagelivetracker.data

import com.orchid.wagelivetracker.data.local.converter.StorageConverters
import com.orchid.wagelivetracker.data.repository.BreakRecord
import com.orchid.wagelivetracker.data.repository.ShiftRecord
import com.orchid.wagelivetracker.data.repository.ShiftStatus
import com.orchid.wagelivetracker.data.repository.StoredShift
import com.orchid.wagelivetracker.domain.wage.WageCalculator
import com.orchid.wagelivetracker.domain.wage.WorkCondition
import java.math.BigDecimal
import java.time.LocalDateTime
import org.junit.Assert.*
import org.junit.Test

class StorageConvertersTest {
    private val converters = StorageConverters()

    @Test fun `local dates including nanoseconds round trip exactly`() {
        for (value in listOf(LocalDateTime.MIN, LocalDateTime.MAX, LocalDateTime.parse("2026-10-02T22:00:30.123456789"))) {
            assertEquals(value, converters.decodeTime(converters.encodeTime(value)))
        }
    }

    @Test fun `decimal value and scale including negative scale round trip exactly`() {
        for (value in listOf("10000.123456789012345678900", "0.5000", "1E+12", "0E-20")) {
            val decimal = BigDecimal(value)
            assertEquals(decimal, converters.decodeDecimal(converters.encodeDecimal(decimal)))
        }
    }

    @Test fun `nullable values round trip`() {
        assertNull(converters.decodeTime(converters.encodeTime(null)))
        assertNull(converters.decodeDecimal(converters.encodeDecimal(null)))
    }

    @Test fun `status persists explicit strings`() {
        for (status in ShiftStatus.entries) {
            assertEquals(status.storedValue, converters.encodeStatus(status))
            assertEquals(status, converters.decodeStatus(status.storedValue))
        }
    }

    @Test fun `unknown status fails rather than silently selecting a state`() {
        assertThrows(NoSuchElementException::class.java) { converters.decodeStatus("INVALID") }
    }

    private fun stored(status: ShiftStatus = ShiftStatus.COMPLETED, openBreak: Boolean = false): StoredShift {
        val start = LocalDateTime.parse("2026-10-02T21:00")
        val end = LocalDateTime.parse("2026-10-03T07:00")
        return StoredShift(
            ShiftRecord(1, 1, start, if (status == ShiftStatus.COMPLETED) end else null, status, WorkCondition(BigDecimal("10000"), true)),
            listOf(BreakRecord(1, 1, start.plusHours(2), if (openBreak) null else start.plusHours(3).plusMinutes(30))),
        )
    }

    @Test fun `completed storage model maps to existing wage engine`() {
        val input = stored().toWageInput()
        val result = WageCalculator().calculate(input.workPeriod, input.condition, input.breaks)
        assertEquals(0, BigDecimal("117500").compareTo(result.totalEstimatedPay))
    }

    @Test fun `in progress record cannot be mapped as completed work`() {
        assertThrows(IllegalStateException::class.java) { stored(ShiftStatus.IN_PROGRESS).toWageInput() }
    }

    @Test fun `open break cannot be mapped for historical calculation`() {
        assertThrows(IllegalStateException::class.java) { stored(openBreak = true).toWageInput() }
    }

    @Test fun `break from another shift is rejected by mapper`() {
        val original = stored()
        assertThrows(IllegalArgumentException::class.java) { original.copy(breaks = original.breaks.map { it.copy(shiftId = 2) }).toWageInput() }
    }
}
