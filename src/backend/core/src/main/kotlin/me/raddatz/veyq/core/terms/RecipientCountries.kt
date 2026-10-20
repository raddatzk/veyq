package me.raddatz.veyq.core.terms

import java.util.Locale

/**
 * Where a privacy-notice recipient processes data (ADR 0035 decision 8): an ISO 3166-1 alpha-2 code, which the notice
 * names in each page's own language, so the German page never shows the English name or the reverse (owner
 * 2026-10-19). The list and the names are the JDK's locale data — no table of veyq's own. The clients, which cannot
 * ask the JDK, read `contracts/terms/recipient-countries.json`, held equal to [CODES] by `RecipientCountriesTest`.
 */
object RecipientCountries {
    /** Every code a recipient may name: `Locale.getISOCountries()`, the JDK's current ISO 3166-1 alpha-2 list. */
    val CODES: Set<String> = Locale.getISOCountries().toSortedSet()

    /** Whether [code] is one of [CODES]; an unnamed country (null) is always fine. */
    fun known(code: String?): Boolean = code == null || code in CODES

    /** [code]'s name in the page's language — English where [en], German otherwise. */
    fun name(code: String, en: Boolean): String =
        Locale.of("", code).getDisplayCountry(if (en) Locale.ENGLISH else Locale.GERMAN)
}
