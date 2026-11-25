package me.raddatz.veyq.mobile.android

import android.content.Context
import me.raddatz.veyq.mobile.words.Msg
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The generated `Context.words` (generate_words.py `--android-kotlin`): since `Msg` is a class, not an enum (inbox #1341),
 * a word reaches its resource through an array of ids in the catalog's order — every word must land on the resource of
 * its own key, in the person's language.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class WordsTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun everyWordIsTheResourceOfItsOwnKey() {
        val r = context.resources
        for (m in Msg.entries) {
            // an Int fills `%n$d` and `%n$s` alike; a plural's count first
            val args = Array<Any>(maxOf(m.args, if (m.plural) 1 else 0)) { 2 }
            val id = r.getIdentifier(m.key, if (m.plural) "plurals" else "string", context.packageName)
            val expected = if (m.plural) r.getQuantityString(id, 2, *args) else context.getString(id, *args)
            assertEquals(m.key, expected, context.words(m, *args))
        }
    }

    @Test
    @Config(qualifiers = "de")
    fun aWordIsInThePersonsLanguage() {
        assertEquals("„Urlaub“ liegt jetzt im Papierkorb.", context.words(Msg.TRASH_MOVED, "Urlaub"))
        assertEquals("1 Datei ließ sich nicht lesen und wurde nicht hochgeladen.", context.words(Msg.DEPOT_FILES_UNREADABLE, 1))
    }
}
