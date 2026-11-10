package me.raddatz.veyq.mobile.media

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant

/**
 * kotlinx-datetime in the device's zone (ADR 0123's Consequences, the core on Linux; Windows alike), with JavaScript's `new Date(y, m - 1, d, h, mi, s)`
 * semantics: a time in a spring-forward gap moves forward by the gap, an ambiguous autumn time takes the earlier offset
 * (both kotlinx-datetime's own rule for `toInstant`), and out-of-range parts roll over — built here by date arithmetic
 * from the first of the month, since `LocalDateTime` refuses them. `WallClockTest` (bridgedTest) pins each against Node.
 */
actual fun localWallClock(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long? = runCatching {
    // Months roll over into years, then days, hours, minutes and seconds into the date and the clock, as JavaScript's do.
    val m0 = month - 1
    val firstOfMonth = LocalDate(year + m0.floorDiv(12), m0.mod(12) + 1, 1)
    val totalSeconds = ((day - 1).toLong() * 86_400L) + hour.toLong() * 3_600L + minute.toLong() * 60L + second.toLong()
    val date = firstOfMonth.plus(totalSeconds.floorDiv(86_400L), DateTimeUnit.DAY)
    val s = totalSeconds.mod(86_400L).toInt()
    val local: LocalDateTime = date.atTime(s / 3_600, s % 3_600 / 60, s % 60)
    local.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
}.getOrNull()
