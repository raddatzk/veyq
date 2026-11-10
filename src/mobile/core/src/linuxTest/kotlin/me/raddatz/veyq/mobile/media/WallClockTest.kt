package me.raddatz.veyq.mobile.media

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Linux's `localWallClock` against what the web's `new Date(y, m - 1, d, h, mi, s)` gives (CR core-as-a-native-library
 * D4). Each expected value was produced on 2026-11-10 by
 * `TZ=Europe/Berlin node -e 'console.log(new Date(y, m - 1, d, h, mi, s).getTime())'` (Node 23.11.0), so the test
 * runs in Europe/Berlin and refuses to run in any other zone rather than pass by luck. kotlinx-datetime reads the
 * device's zone from `/etc/localtime`, not from `TZ` (measured 2026-11-10: with only `TZ` set it read UTC), so
 * `run.py test` points the container's `/etc/localtime` at Europe/Berlin.
 */
class WallClockTest {
    private fun at(y: Int, m: Int, d: Int, h: Int, mi: Int, s: Int) = localWallClock(y, m, d, h, mi, s)

    @Test
    fun berlinsClockReadsAsTheWebReadsIt() {
        assertEquals("Europe/Berlin", TimeZone.currentSystemDefault().id, "run in Europe/Berlin: the values below are Berlin's")
        // ordinary winter and summer times
        assertEquals(1767222000000, at(2026, 1, 1, 0, 0, 0))
        assertEquals(1774745999000, at(2026, 3, 29, 1, 59, 59))
        // spring forward: 02:30 does not exist and moves forward by the gap, to 03:30+02:00
        assertEquals(1774747800000, at(2026, 3, 29, 2, 30, 0))
        // fall back: 02:30 happens twice and takes the earlier offset, +02:00
        assertEquals(1792888200000, at(2026, 10, 25, 2, 30, 0))
        assertEquals(1792884600000, at(2026, 10, 25, 1, 30, 0))
        assertEquals(1792893600000, at(2026, 10, 25, 3, 0, 0))
        // out-of-range parts roll over: month 13, day 0, hour 25 with minute and second 61, month 0 with second 60
        assertEquals(1798801200000, at(2026, 13, 1, 12, 0, 0))
        assertEquals(1772276400000, at(2026, 3, 0, 12, 0, 0))
        assertEquals(1781568121000, at(2026, 6, 15, 25, 61, 61))
        assertEquals(1767222000000, at(2026, 0, 31, 23, 59, 60))
    }
}
