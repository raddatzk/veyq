package me.raddatz.veyq.mobile.expenses

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The one currency table (ADR 0127): the web's `Intl` codes and digits, whatever the platform's own table says. */
class CurrenciesTest {
    @Test
    fun the_codes_are_the_webs_sorted_and_each_a_currency_of_the_format() {
        assertEquals(162, Currencies.known.size)
        assertEquals(Currencies.known.sorted(), Currencies.known)
        assertEquals(Currencies.known.distinct(), Currencies.known)
        assertTrue(Currencies.known.all { RevisionFormat.CURRENCY.matches(it) })
        assertTrue("EUR" in Currencies.known && "CHF" in Currencies.known)
    }

    @Test
    fun the_digits_are_intls_where_iso_differs_too() {
        assertEquals(2, Currencies.minorUnits("EUR"))
        assertEquals(0, Currencies.minorUnits("JPY"))
        assertEquals(3, Currencies.minorUnits("BHD"))
        assertEquals(0, Currencies.minorUnits("IQD")) // ISO 4217 (java.util.Currency) says 3; Intl, the web, 0
        assertEquals(2, Currencies.minorUnits("ZZZ"))
        assertEquals(126, Currencies.known.count { Currencies.minorUnits(it) == 2 })
    }
}
