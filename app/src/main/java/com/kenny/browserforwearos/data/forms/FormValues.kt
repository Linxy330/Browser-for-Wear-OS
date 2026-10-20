package com.kenny.browserforwearos.data.forms

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.round

/** HTML values, rather than locale-specific display strings, cross the engine boundary. */
object FormValues {
    fun validColor(value: String): Boolean = value.matches(Regex("#[0-9a-fA-F]{6}"))

    fun validDateTime(value: String, type: Int, minimum: String, maximum: String, step: String): Boolean {
        if (value.isEmpty()) return true // Clearing a field is distinct from cancelling its prompt.
        val number = numeric(value, type) ?: return false
        val min = numeric(minimum, type)
        val max = numeric(maximum, type)
        val overnight = type == 4 && min != null && max != null && min > max
        if (overnight) { if (number < min!! && number > max!!) return false }
        else if ((min != null && number < min) || (max != null && number > max)) return false
        if (step == "any") return true
        // Without a valid min, HTML may use the original value *attribute* as
        // its step base. Gecko exposes only the current value, so defer that
        // case to the document's native validity rather than reject valid input.
        val base = min ?: return true
        val scale = when (type) { 3 -> 7.0; else -> 1.0 }
        val defaultStep = if (type == 4 || type == 5) 60.0 else 1.0
        val increment = (step.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: defaultStep) * scale
        val quotient = (number - base) / increment
        return abs(quotient - round(quotient)) < 0.000001
    }

    fun hint(type: Int): String = when (type) {
        1 -> "YYYY-MM-DD"; 2 -> "YYYY-MM"; 3 -> "YYYY-Www"; 4 -> "HH:mm"; 5 -> "YYYY-MM-DDTHH:mm"; else -> ""
    }

    private fun numeric(value: String, type: Int): Double? = runCatching {
        when (type) {
            1 -> LocalDate.parse(value).toEpochDay().toDouble()
            2 -> YearMonth.parse(value).let { it.year.toDouble() * 12 + it.monthValue - 1 }
            3 -> LocalDate.parse("$value-1", DateTimeFormatter.ISO_WEEK_DATE).toEpochDay().toDouble()
            4 -> LocalTime.parse(value).toNanoOfDay() / 1_000_000_000.0
            5 -> LocalDateTime.parse(value).let { it.toEpochSecond(ZoneOffset.UTC).toDouble() + it.nano / 1_000_000_000.0 }
            else -> return null
        }
    }.getOrNull()
}
