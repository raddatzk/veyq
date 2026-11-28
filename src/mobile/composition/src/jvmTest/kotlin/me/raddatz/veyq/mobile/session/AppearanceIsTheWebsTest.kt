package me.raddatz.veyq.mobile.session

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The appearance and language choices are the web's (ADR 0162): the same entries in the same order as its Appearance
 * panel offers them (`features/settings/AppearancePanel.vue`'s themes, `i18n/index.ts`' `LOCALE_PREFERENCES`), and the
 * ids an install keeps are the values the web stores (`useThemeState.ts`' and `index.ts`' readers).
 */
class AppearanceIsTheWebsTest {
    private val root = File(System.getProperty("veyq.repoRoot"))

    private fun read(path: String) = File(root, "src/web/src/$path").readText()

    private fun list(text: String, before: String): List<String> {
        val at = text.indexOf(before).also { check(it >= 0) { "`$before` not found" } }
        val open = text.indexOf("= [", at) + 2
        val inner = text.substring(open + 1, text.indexOf(']', open))
        return Regex("""'([a-z]+)'""").findAll(inner).map { it.groupValues[1] }.toList()
    }

    @Test
    fun theThemesAreTheWebsInItsOrder() {
        val web = list(read("features/settings/AppearancePanel.vue"), "const themes")
        assertEquals(web, ThemeChoice.entries.map { it.id })
        val stored = read("shared/state/useThemeState.ts")
        for (t in ThemeChoice.entries) check("value === '${t.id}'" in stored) { "the web does not read back '${t.id}'" }
    }

    @Test
    fun theLanguagesAreTheWebsInItsOrder() {
        val text = read("i18n/index.ts")
        assertEquals(list(text, "export const LOCALE_PREFERENCES"), LanguageChoice.entries.map { it.id })
        val labels = Regex("""LOCALE_LABELS[^=]*=\s*\{([^}]*)\}""").find(text)!!.groupValues[1]
        val named = Regex("""(\w+):\s*'([^']+)'""").findAll(labels).associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(named, LanguageChoice.entries.filter { it.tag != null }.associate { it.id to englishOf(it) })
    }

    private fun englishOf(l: LanguageChoice): String = when (l) {
        LanguageChoice.SYSTEM -> "System"
        LanguageChoice.EN -> wordsJson("appearance_language_en")
        LanguageChoice.DE -> wordsJson("appearance_language_de")
    }

    private fun wordsJson(key: String): String {
        val text = File(root, "src/mobile/words/words.json").readText()
        val entry = text.substring(text.indexOf("\"$key\""))
        return Regex(""""en":\s*"([^"]*)"""").find(entry)!!.groupValues[1]
    }
}
