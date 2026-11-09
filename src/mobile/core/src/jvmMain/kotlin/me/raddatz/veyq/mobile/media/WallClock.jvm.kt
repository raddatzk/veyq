package me.raddatz.veyq.mobile.media

import java.util.GregorianCalendar
import java.util.TimeZone

actual fun localWallClock(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long? =
    runCatching {
        GregorianCalendar(TimeZone.getDefault()).apply {
            isLenient = true
            clear()
            set(year, month - 1, day, hour, minute, second)
        }.timeInMillis
    }.getOrNull()
