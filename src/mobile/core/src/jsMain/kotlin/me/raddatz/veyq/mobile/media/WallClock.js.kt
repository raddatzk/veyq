package me.raddatz.veyq.mobile.media

import kotlin.js.Date

/** JavaScript's own `new Date(year, month - 1, day, hour, minute, second)`, which is the reading the contract names. */
actual fun localWallClock(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long? {
    val ms = Date(year, month - 1, day, hour, minute, second).getTime()
    return if (ms.isNaN()) null else ms.toLong()
}
