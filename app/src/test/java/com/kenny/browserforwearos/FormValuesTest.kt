package com.kenny.browserforwearos

import com.kenny.browserforwearos.data.forms.FormValues
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** HTML value constraints without an Android runtime or native browser. */
class FormValuesTest {
    @Test
    fun dateParsingUsesTheGregorianLeapYearRules() {
        assertTrue(valid("2024-02-29", DATE))
        assertTrue(valid("2000-02-29", DATE))
        assertFalse(valid("2023-02-29", DATE))
        assertFalse(valid("1900-02-29", DATE))
        listOf("2024-02-30", "2024-04-31", "2024-13-01", "2024-00-01", "2024-01-00", "2024-1-02", "02/29/2024").forEach {
            assertFalse("Invalid HTML date: $it", valid(it, DATE))
        }
    }

    @Test
    fun monthAndIsoWeekParsingRespectTheirActualCalendars() {
        assertTrue(valid("2024-02", MONTH))
        assertTrue(valid("2024-12", MONTH))
        assertFalse(valid("2024-00", MONTH))
        assertFalse(valid("2024-13", MONTH))
        assertFalse(valid("2024-02-01", MONTH))
        assertTrue(valid("2020-W53", WEEK))
        assertTrue(valid("2021-W01", WEEK))
        assertFalse(valid("2021-W53", WEEK))
        assertFalse(valid("2024-W00", WEEK))
        assertFalse(valid("2024-W54", WEEK))
        assertFalse(valid("2024-W1", WEEK))
    }

    @Test
    fun dateAndMonthBoundsAreInclusive() {
        assertTrue(valid("2024-02-29", DATE, "2024-02-29", "2024-03-02"))
        assertTrue(valid("2024-03-02", DATE, "2024-02-29", "2024-03-02"))
        assertFalse(valid("2024-02-28", DATE, "2024-02-29", "2024-03-02"))
        assertFalse(valid("2024-03-03", DATE, "2024-02-29", "2024-03-02"))
        assertTrue(valid("2024-12", MONTH, "2024-11", "2025-02"))
        assertTrue(valid("2025-02", MONTH, "2024-11", "2025-02"))
        assertFalse(valid("2025-03", MONTH, "2024-11", "2025-02"))
        assertTrue(valid("2024-01-01", DATE, "invalid", "invalid"))
    }

    @Test
    fun weeklyBoundsAndStepsCrossTheIsoYearBoundary() {
        assertTrue(valid("2020-W53", WEEK, "2020-W52", "2021-W02"))
        assertTrue(valid("2021-W02", WEEK, "2020-W52", "2021-W02"))
        assertFalse(valid("2021-W03", WEEK, "2020-W52", "2021-W02"))
        assertTrue(valid("2021-W01", WEEK, "2020-W52", step = "2"))
        assertFalse(valid("2020-W53", WEEK, "2020-W52", step = "2"))
    }

    @Test
    fun dateAndMonthStepUseAnExplicitMinimum() {
        assertTrue(valid("2024-03-02", DATE, "2024-02-29", step = "2"))
        assertFalse(valid("2024-03-01", DATE, "2024-02-29", step = "2"))
        assertTrue(valid("2024-05", MONTH, "2024-02", step = "3"))
        assertFalse(valid("2024-04", MONTH, "2024-02", step = "3"))
    }

    @Test
    fun timeBoundsIncludeEndpointsAndAllowAnOvernightRange() {
        assertTrue(valid("09:00", TIME, "09:00", "17:00"))
        assertTrue(valid("17:00", TIME, "09:00", "17:00"))
        assertFalse(valid("08:59", TIME, "09:00", "17:00"))
        assertFalse(valid("17:01", TIME, "09:00", "17:00"))
        listOf("23:00", "23:30", "00:00", "00:30", "01:00").forEach {
            assertTrue("Inside overnight range: $it", valid(it, TIME, "23:00", "01:00"))
        }
        listOf("01:01", "12:00", "22:59").forEach {
            assertFalse("Outside overnight range: $it", valid(it, TIME, "23:00", "01:00"))
        }
        assertTrue(valid("00:30", TIME, "23:00", "01:00", "5400"))
        assertFalse(valid("00:00", TIME, "23:00", "01:00", "5400"))
    }

    @Test
    fun defaultTimeStepIsOneMinuteAndAnyAllowsSeconds() {
        listOf("", "0", "-1", "invalid", "NaN", "Infinity").forEach { step ->
            assertTrue("Default minute step: $step", valid("12:34", TIME, min = "00:00", step = step))
            assertFalse("Seconds must violate the default minute step: $step", valid("12:34:01", TIME, min = "00:00", step = step))
        }
        assertTrue(valid("12:34:01.125", TIME, step = "any"))
        assertTrue(valid("12:34:01", TIME, step = "1"))
        assertFalse(valid("24:00", TIME, step = "any"))
        assertFalse(valid("12:60", TIME, step = "any"))
        assertFalse(valid("12:34:60", TIME, step = "any"))
    }

    @Test
    fun fractionalSecondsAreCheckedRatherThanTruncated() {
        assertTrue(valid("12:00:00.500", TIME, min = "00:00", step = "0.5"))
        assertFalse(valid("12:00:00.250", TIME, min = "00:00", step = "0.5"))
        assertFalse(valid("12:00:00.500", TIME, min = "00:00", step = "1"))
        assertTrue(valid("2024-01-01T12:00:00.500", DATETIME_LOCAL, "2024-01-01T12:00", step = "0.5"))
        assertFalse(valid("2024-01-01T12:00:00.250", DATETIME_LOCAL, "2024-01-01T12:00", step = "0.5"))
        assertFalse(valid("2024-01-01T12:00:00.500", DATETIME_LOCAL, min = "2024-01-01T00:00", step = "1"))
        assertTrue(valid("2024-01-01T12:00:00.125", DATETIME_LOCAL, step = "any"))
    }

    @Test
    fun localDateTimeHasMinuteDefaultsBoundsAndNoTimeZoneSuffix() {
        assertTrue(valid("2024-02-29T12:34", DATETIME_LOCAL))
        assertFalse(valid("2024-02-29T12:34:01", DATETIME_LOCAL, min = "2024-02-29T00:00"))
        assertTrue(valid("2024-02-29T12:34:01", DATETIME_LOCAL, step = "1"))
        assertFalse(valid("2023-02-29T12:34", DATETIME_LOCAL))
        assertFalse(valid("2024-02-29T12:34Z", DATETIME_LOCAL))
        assertTrue(valid("2024-02-29T12:34", DATETIME_LOCAL, "2024-02-29T12:34", "2024-02-29T12:35"))
        assertFalse(valid("2024-02-29T12:33", DATETIME_LOCAL, "2024-02-29T12:34", "2024-02-29T12:35"))
        assertFalse(valid("2024-02-29T12:36", DATETIME_LOCAL, "2024-02-29T12:34", "2024-02-29T12:35"))
    }

    @Test
    fun clearingAValueRemainsDifferentFromCancellingThePrompt() {
        (DATE..DATETIME_LOCAL).forEach { type -> assertTrue(valid("", type, "2024-01-01", "2024-01-31")) }
        assertFalse(valid("2024-01-01", 99))
    }

    @Test
    fun unknownHtmlValueAttributeBaseDoesNotRejectAValidNonEpochStep() {
        assertTrue(valid("2024-01-01", DATE, step = "2"))
        assertTrue(valid("2024-01-03", DATE, step = "2"))
        assertTrue(valid("12:34:01", TIME, step = "60"))
        assertTrue(valid("1970-W02", WEEK, step = "2"))
    }

    @Test
    fun colorsRequireExactlySixHexDigitsAfterTheHash() {
        listOf("#000000", "#ffffff", "#aB12Cd", "#00FF99").forEach {
            assertTrue("Valid HTML color: $it", FormValues.validColor(it))
        }
        listOf("", "#fff", "#12345678", "123456", "#12345", "#gg0000", "red", " #123456", "#123456 ").forEach {
            assertFalse("Invalid HTML color: $it", FormValues.validColor(it))
        }
    }

    private fun valid(value: String, type: Int, min: String = "", max: String = "", step: String = "") =
        FormValues.validDateTime(value, type, min, max, step)

    companion object {
        // Public Gecko DateTimePrompt.Type values; keeping this test Android-free.
        private const val DATE = 1
        private const val MONTH = 2
        private const val WEEK = 3
        private const val TIME = 4
        private const val DATETIME_LOCAL = 5
    }
}
