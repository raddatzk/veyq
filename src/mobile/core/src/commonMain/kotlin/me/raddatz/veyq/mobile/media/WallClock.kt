package me.raddatz.veyq.mobile.media

/**
 * A wall clock with no zone — an EXIF date, `YYYY:MM:DD HH:MM:SS` — as epoch ms read in the device's own zone, as the
 * web's `new Date(year, month - 1, day, hour, minute, second)` reads it: every capture time the clients sealed so far is
 * that reading. Out-of-range parts roll over as JavaScript's do; null where the platform has none. A `Double`, exact for
 * any instant, so the web's path carries no emulated `Long` ([localWallClock] is the apps').
 */
expect fun localWallClockMs(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Double?

/** [localWallClockMs] as a `Long`. */
fun localWallClock(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long? =
    localWallClockMs(year, month, day, hour, minute, second)?.toLong()
