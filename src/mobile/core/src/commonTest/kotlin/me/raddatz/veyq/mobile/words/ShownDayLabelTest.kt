package me.raddatz.veyq.mobile.words

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A gallery's day chip as the web's `galleryFilter` labels it (`Intl.DateTimeFormat(locale, { day: 'numeric', month:
 * 'long' })`, the year where the Memory spans more than one) — the expected strings are Node's (ICU 77.1) for `en`/`de`.
 */
class ShownDayLabelTest {
    private val may3 = 20_576L // 2026-05-03
    private val march1 = 20_513L // 2026-03-01
    private val christmasEve = 20_446L // 2025-12-24

    @Test
    fun englishNamesTheMonthBeforeTheDay() {
        assertEquals("May 3", Shown.dayLabel(may3, withYear = false, language = "en"))
        assertEquals("March 1", Shown.dayLabel(march1, withYear = false, language = "en"))
        assertEquals("December 24, 2025", Shown.dayLabel(christmasEve, withYear = true, language = "en"))
        assertEquals("May 3, 2026", Shown.dayLabel(may3, withYear = true, language = "en"))
    }

    @Test
    fun germanNamesTheDayBeforeTheMonth() {
        assertEquals("3. Mai", Shown.dayLabel(may3, withYear = false, language = "de"))
        assertEquals("1. März", Shown.dayLabel(march1, withYear = false, language = "de"))
        assertEquals("24. Dezember 2025", Shown.dayLabel(christmasEve, withYear = true, language = "de"))
        assertEquals("3. Mai 2026", Shown.dayLabel(may3, withYear = true, language = "de"))
    }
}
