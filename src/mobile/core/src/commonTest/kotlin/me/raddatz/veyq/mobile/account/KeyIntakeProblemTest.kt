package me.raddatz.veyq.mobile.account

import me.raddatz.veyq.mobile.crypto.SecretKeyProblem
import me.raddatz.veyq.mobile.session.testPrimitives
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A Secret Key field's text as it is typed (`KeyIntake.problem`, inbox #1058): the web's `SecretKeyField` says a typo
 * and nothing for a malformed entry, so the core answers which of the two it is. Over the vectors' fourth key
 * ([KitImportTest.KEY_TEXT], `v1.json#secret_key_text`) and the kit fixture's token; the vectors' refused cases are
 * `Conformance`'s, on every target.
 */
class KeyIntakeProblemTest {
    private val intake = KeyIntake(testPrimitives)

    @Test
    fun a_key_and_a_kit_token_have_no_problem() {
        for (text in listOf(KitImportTest.KEY_TEXT, KitImportTest.KEY_TEXT.lowercase().replace("-", " "), KitImportTest.TOKEN_A)) {
            assertNull(intake.problem(text))
        }
    }

    @Test
    fun a_wrong_symbol_is_a_typo_and_a_short_entry_malformed() {
        val k = KitImportTest.KEY_TEXT
        // the last check symbol changed: 54 symbols, the check disagrees
        assertEquals(SecretKeyProblem.TYPO, intake.problem(k.dropLast(1) + (if (k.last() == 'C') 'D' else 'C')))
        // one symbol short, and one outside the alphabet: no key at all
        assertEquals(SecretKeyProblem.MALFORMED, intake.problem(k.dropLast(1)))
        // an empty field is no key either, as the reader has it; it says nothing, as every malformed entry
        assertEquals(SecretKeyProblem.MALFORMED, intake.problem(""))
        assertEquals(SecretKeyProblem.MALFORMED, intake.problem("U" + k.drop(1)))
        // a token is read as a kit first; one that is none is no key either
        assertEquals(SecretKeyProblem.MALFORMED, intake.problem("veyqkit:not-a-kit"))
    }
}
