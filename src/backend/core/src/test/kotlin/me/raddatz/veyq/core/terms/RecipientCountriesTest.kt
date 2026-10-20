package me.raddatz.veyq.core.terms

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * A privacy-notice recipient's country (ADR 0035 decision 8): the codes the backend accepts are the JDK's, the clients'
 * copy in `contracts/terms/recipient-countries.json` is that list, and the JDK names a country in each page's language.
 * The names are pinned as the JDK measured them (Temurin 21, the image's `eclipse-temurin:21-jre`, CLDR locale data):
 * a runtime without the German locale data would name every country in English, and this test is what turns red.
 */
class RecipientCountriesTest {

    @Test
    fun theContractsListIsTheJdksList() {
        val file = ObjectMapper().readTree(File("../../contracts/terms/recipient-countries.json"))
        val codes = file.required("codes").map { it.asText() }
        assertEquals(codes.sorted(), codes, "the contract's list is sorted")
        assertEquals(RecipientCountries.CODES.toList(), codes, "contracts/terms/recipient-countries.json differs from Locale.getISOCountries()")
        assertEquals(249, codes.size) // measured on Temurin 21.0.12 and 25.0.4: a JDK whose list moves says so here
    }

    @Test
    fun theJdkNamesACountryInEachPagesLanguage() {
        val pinned = mapOf(
            "DE" to ("Germany" to "Deutschland"),
            "US" to ("United States" to "Vereinigte Staaten"),
            "FI" to ("Finland" to "Finnland"),
            "AT" to ("Austria" to "Österreich"),
        )
        for ((code, names) in pinned) {
            assertEquals(names.first, RecipientCountries.name(code, en = true), code)
            assertEquals(names.second, RecipientCountries.name(code, en = false), code)
        }
        // every code has a name of its own on both pages, never the bare code
        for (code in RecipientCountries.CODES) {
            assertNotEquals(code, RecipientCountries.name(code, en = true), code)
            assertNotEquals(code, RecipientCountries.name(code, en = false), code)
        }
    }

    @Test
    fun onlyACodeTheJdkKnowsIsAccepted() {
        assertTrue(RecipientCountries.known("DE"))
        assertTrue(RecipientCountries.known(null), "a country not named")
        assertFalse(RecipientCountries.known("XX"), "the shape of a code, no country")
        assertFalse(RecipientCountries.known("XK"), "a user-assigned code the JDK names (Kosovo) but does not list")
        assertFalse(RecipientCountries.known("de"))
        assertFalse(RecipientCountries.known("Germany"))
    }
}
