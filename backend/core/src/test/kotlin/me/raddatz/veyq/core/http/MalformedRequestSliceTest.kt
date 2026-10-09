package me.raddatz.veyq.core.http

import me.raddatz.veyq.core.generated.model.ClaimRequest
import me.raddatz.veyq.core.generated.model.ErrorResponse
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import jakarta.inject.Inject
import jakarta.validation.ConstraintViolationException
import jakarta.validation.Validator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Every request the boundary refuses for its shape answers the contract's `400 ErrorResponse{code: invalid_request}`
 * — the same body a handler's `Failures.invalidRequest` writes — whichever layer refused it (inbox #339, #355):
 * Jackson while it binds the body (an unknown enum value, a missing or null required field, a wrong type, unparseable
 * JSON) or bean validation after it (an empty field, a bound, a pattern, a list element). Before the mappers in
 * `RequestRejectionMappers.kt` (measured 2026-10-09), the first family answered either an empty 400 with no content
 * type or Quarkus' dev/test detail (`attributeName` and the refused `value`), the second Quarkus' own `Constraint
 * Violation` report (the method, argument and field path, the rule's localized message).
 *
 * Every case asserts the whole wire shape ([assertRefused]): status 400, `application/json`, `code` the one key with
 * a value (no `message`, no violation list), and none of what the caller sent echoed back — the refused value is
 * often ciphertext or an opaque id, and the operator-blind boundary applies to errors too (styleguide §7). The
 * endpoints are core's, chosen for reaching body binding with no more than a session: `claimTasks` (a list of
 * patterned scalars, inbox #354), `enqueueTask` (a uuid), `setMemberRole` (an enum). The same shape for an
 * application's own generated resource is `:apps:files`' `FilesContentSliceTest`.
 */
@QuarkusTest
class MalformedRequestSliceTest {

    @Inject lateinit var validator: Validator

    private val user = seedUser()

    private fun post(path: String, body: String): Response =
        given().header("Authorization", bearer(user)).contentType(ContentType.JSON).body(body)
            .`when`().post(path)

    private fun claim(body: String) = post("/api/tasks/claim", body)

    private fun assertRefused(response: Response, vararg sent: String) {
        val raw = response.asString()
        assertEquals(400, response.statusCode, "status, body: $raw")
        assertTrue(response.contentType.startsWith("application/json"), "content type: ${response.contentType}")
        val body: Map<String, Any?> = response.jsonPath().getMap("")
        assertEquals(mapOf("code" to "invalid_request"), body.filterValues { it != null }, "body: $raw")
        assertTrue(body.keys.all { it in setOf("code", "message") }, "only ErrorResponse's properties: $raw")
        for (s in sent) assertFalse(raw.contains(s), "the refused value `$s` is not echoed: $raw")
        for (leak in listOf("jackson", "kotlin", "java.", "me.raddatz", "violations", "Exception")) {
            assertFalse(raw.contains(leak, ignoreCase = true), "no framework internals (`$leak`): $raw")
        }
    }

    // --- refused while Jackson binds the body (inbox #339) ---------------------------------------------------

    @Test
    fun anUnknownEnumValueIsInvalidRequest() {
        val response = given().header("Authorization", bearer(user)).contentType(ContentType.JSON)
            .body("""{"role":"EMPEROR"}""")
            .`when`().patch("/api/scopes/${UUID.randomUUID()}/members/${UUID.randomUUID()}")
        assertRefused(response, "EMPEROR", "OWNER", "MEMBER")
    }

    @Test
    fun aMissingRequiredFieldIsInvalidRequest() {
        assertRefused(claim("{}"), "capabilityTags")
    }

    @Test
    fun aNullRequiredFieldIsInvalidRequest() {
        assertRefused(claim("""{"capabilityTags":null}"""), "capabilityTags")
    }

    @Test
    fun aWrongTypeIsInvalidRequest() {
        assertRefused(post("/api/tasks/enqueue", """{"scopeId":"c2VjcmV0LWNpcGhlcnRleHQ","capabilityTag":"02f271ff"}"""),
            "c2VjcmV0LWNpcGhlcnRleHQ", "scopeId", "UUID")
        assertRefused(claim("""{"capabilityTags":{"a":1}}"""), "capabilityTags")
    }

    @Test
    fun unparseableJsonIsInvalidRequest() {
        assertRefused(claim("""{"capabilityTags":["02f271ff""""), "02f271ff")
    }

    // --- refused by bean validation after binding (inbox #355, #354) -----------------------------------------

    @Test
    fun anEmptyRequiredFieldIsInvalidRequest() {
        assertRefused(claim("""{"capabilityTags":[]}"""), "capabilityTags", "NotEmpty")
    }

    @Test
    fun aListOverItsBoundIsInvalidRequest() {
        val tags = (1..65).joinToString(",") { "\"0$it\"" }
        assertRefused(claim("""{"capabilityTags":[$tags]}"""), "capabilityTags", "64")
    }

    /** `ClaimRequest.capabilityTags`' elements carry `CapabilityTag`'s bounds (inbox #354): before, any string was a
     *  tag, so an upper-case or over-long one reached the claim query. */
    @Test
    fun aListElementOutsideItsBoundsIsInvalidRequest() {
        assertRefused(claim("""{"capabilityTags":["02f271ff","NOT-HEX-c2VjcmV0"]}"""), "NOT-HEX-c2VjcmV0", "[0-9a-f]")
        assertRefused(claim("""{"capabilityTags":["${"a".repeat(65)}"]}"""), "a".repeat(65))
        assertRefused(claim("""{"capabilityTags":[""]}"""), "capabilityTags")
        assertRefused(claim("""{"capabilityTags":[null]}"""), "capabilityTags")
    }

    /** Only a violation of the request is the caller's: one whose path names no method parameter — an entity checked
     *  as it persists, a return value — is the server's own defect, a `500 internal_error` with no report. */
    @Test
    fun aViolationOfTheServersOwnIsNoRequestRefusal() {
        val violations = validator.validate(ClaimRequest(capabilityTags = emptyList()))
        assertTrue(violations.isNotEmpty(), "the control: the bean itself is invalid")
        val response = ConstraintViolationExceptionMapper().toResponse(ConstraintViolationException(violations))
        assertEquals(500, response.status)
        assertEquals(ErrorResponse(code = "internal_error"), response.entity)
    }

    @Test
    fun aWellFormedRequestStillReachesTheHandler() {
        // The control: the same endpoint, a valid body — nothing to lease, so 204, not any refusal above.
        assertEquals(204, claim("""{"capabilityTags":["02f271ff"]}""").statusCode)
    }
}
