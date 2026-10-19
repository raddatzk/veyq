package me.raddatz.veyq.core.moderation

import me.raddatz.veyq.core.generated.model.ModerationGround
import me.raddatz.veyq.core.generated.model.ModerationStatementInput
import me.raddatz.veyq.core.generated.model.ObjectionSubmission
import me.raddatz.veyq.core.generated.model.SealedModerationNote
import me.raddatz.veyq.core.generated.model.SealedOrderReference
import me.raddatz.veyq.core.generated.model.ServerOwnerStatementInputFactsForLog
import me.raddatz.veyq.core.generated.model.WrappedFactsKey
import me.raddatz.veyq.core.testing.ContractBounds
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.validation.Validator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The server's half of the boundary `packages/crypto/src/report.test.ts` holds from the client's: a sealed moderation text
 * at its largest plaintext — 8,000 UTF-8 bytes, what the client lets a moderator, a reporter or an objector write — is
 * accepted, and one character more is refused. The client measures the seal's length from a real seal and pins the
 * contract's `maxLength` to it; here each request field's validation (generated from the same contract) takes exactly
 * that many characters and no more. When the bound was 12,000 the client wrote up to 12,456 and the server answered
 * `400 invalid_request` for every text of 7,677–8,000 bytes.
 *
 * - a note-encoded seal to the moderation key's public half: 1 + 1,120 + 12 + 8,192 + 16 = 9,341 bytes → 12,456;
 * - the facts sealed once under `K_facts`: 1 + 12 + 8,192 + 16 = 8,221 bytes → 10,964;
 * - `K_facts` wrapped to a member's identity: 1 + 1,120 + 12 + 32 + 16 = 1,181 bytes → 1,576, whatever the facts.
 */
@QuarkusTest
class SealedTextBoundsTest {

    @Inject
    lateinit var validator: Validator

    /** Holds [bound] as the contract's `maxLength` of [schema].[property], and the request at it and one past it. */
    private fun holds(spec: String, schema: String, property: String, bound: Int, request: (String) -> Any) {
        assertEquals(bound, ContractBounds.maxLength(spec, schema, property), "$schema.$property's maxLength")
        assertTrue(validator.validate(request("A".repeat(bound))).isEmpty(), "$schema.$property takes $bound characters")
        assertTrue(validator.validate(request("A".repeat(bound + 1))).isNotEmpty(), "$schema.$property refuses ${bound + 1}")
    }

    @Test
    fun aNoteEncodedSealOfEightThousandBytesIsTakenAndOneCharacterMoreIsNot() {
        holds(MODERATION, "SealedModerationNote", "sealedNote", NOTE) { SealedModerationNote(epoch = 1, sealedNote = it) }
        holds(MODERATION, "ObjectionSubmission", "sealedObjection", NOTE) { ObjectionSubmission(epoch = 1, sealedObjection = it) }
        holds(COMMON, "SealedStepFacts", "sealedFacts", NOTE) { ServerOwnerStatementInputFactsForLog(epoch = 1, sealedFacts = it) }
        holds(COMMON, "SealedOrderReference", "sealedReference", NOTE) { SealedOrderReference(epoch = 1, sealedReference = it) }
    }

    @Test
    fun theFactsSealedOnceAndEachWrapOfTheirKeyAreTakenAtTheirLargestAndNoLarger() {
        holds(MODERATION, "ModerationStatementInput", "sealedFacts", FACTS_ONCE) {
            ModerationStatementInput(statementId = UUID.randomUUID(), ground = ModerationGround.TERMS_BREACH, sealedFacts = it)
        }
        holds(MODERATION, "WrappedFactsKey", "wrappedFactsKey", WRAPPED_KEY) { WrappedFactsKey(accountId = UUID.randomUUID(), wrappedFactsKey = it) }
    }

    private companion object {
        const val MODERATION = "moderation.openapi.yaml"
        const val COMMON = "_common.openapi.yaml"
        const val NOTE = 12456
        const val FACTS_ONCE = 10964
        const val WRAPPED_KEY = 1576
    }
}
