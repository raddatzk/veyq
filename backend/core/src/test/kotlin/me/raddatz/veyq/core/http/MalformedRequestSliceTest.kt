package me.raddatz.veyq.core.http

import me.raddatz.veyq.core.generated.model.AuthorityOrderRequest
import me.raddatz.veyq.core.generated.model.BlockAccountSignInRequest
import me.raddatz.veyq.core.generated.model.ClaimRequest
import me.raddatz.veyq.core.generated.model.CreateNodeRequest
import me.raddatz.veyq.core.generated.model.CreateTenantRequest
import me.raddatz.veyq.core.generated.model.DeviceRegisterUpdate
import me.raddatz.veyq.core.generated.model.ErrorResponse
import me.raddatz.veyq.core.generated.model.ServerOwnerStatementInput
import me.raddatz.veyq.core.generated.model.ServerOwnerStatementInputFactsForLog
import me.raddatz.veyq.core.generated.model.TenantMailSettingsUpdate
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.exc.MismatchedInputException
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
import org.junit.jupiter.api.assertThrows
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
 * application's own generated resource is `:apps:depot`' `DepotContentSliceTest`.
 */
@QuarkusTest
class MalformedRequestSliceTest {

    @Inject lateinit var validator: Validator

    @Inject lateinit var mapper: ObjectMapper

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

    /** A `null` where a list of objects holds its elements: no contract declares a nullable element, and before
     *  `NullElementsRefused` Jackson bound it, bean validation's `@Valid` cascade skips a null, and the handler's first
     *  read of it answered `500` (the API fuzzing's first run, cr-api-fuzzing: four operations). */
    @Test
    fun aNullElementInAListOfObjectsIsInvalidRequest() {
        assertRefused(post("/api/scopes/${UUID.randomUUID()}/blob-uploads-batch", """{"items":[null]}"""), "items")
    }

    // --- a JSON value of the wrong type (StrictJsonTypes; the API fuzzing's first run, cr-api-fuzzing Q5) ---------

    /** Jackson coerced a number into a string field: `12345678` became the capability tag `"12345678"`. */
    @Test
    fun aNumberForAStringIsInvalidRequest() {
        assertRefused(claim("""{"capabilityTags":[12345678]}"""), "12345678")
    }

    /** An integer for an enum used to bind by ordinal. */
    @Test
    fun aNumberForAnEnumIsInvalidRequest() {
        val response = given().header("Authorization", bearer(user)).contentType(ContentType.JSON)
            .body("""{"role":1}""")
            .`when`().patch("/api/scopes/${UUID.randomUUID()}/members/${UUID.randomUUID()}")
        assertRefused(response, "role")
    }

    /** The remaining coercions, each at the mapper every body is bound by: what [MismatchedInputExceptionMapper]
     *  answers `400 invalid_request` for is exactly this exception. */
    @Test
    fun theOtherScalarCoercionsAreRefusedWhileBinding() {
        val order = """"id":"${UUID.randomUUID()}","kind":"REMOVAL","shareId":"${"a".repeat(43)}","receivedAt":"2026-10-18T07:12:00Z","reference":{"epoch":2,"sealedReference":"cmVm"}"""
        val node = """"nodeId":"${UUID.randomUUID()}","kind":"FOLDER","encryptedName":"bmFtZQ==","slugToken":"c2x1Zw==","wrappedContentKey":"a2V5""""
        val coerced = mapOf(
            "an integer for a boolean" to ("""{"tls":1,"enabled":true}""" to TenantMailSettingsUpdate::class.java),
            "a string for a boolean" to ("""{"tls":"true","enabled":true}""" to TenantMailSettingsUpdate::class.java),
            "a string for an integer" to ("""{$node,"orderIndex":"0"}""" to CreateNodeRequest::class.java),
            "a fraction for an integer" to ("""{$node,"orderIndex":1.5}""" to CreateNodeRequest::class.java),
            "a number for a date-time" to ("""{$order,"deferStatementUntil":0}""" to AuthorityOrderRequest::class.java),
            "an empty string for a uuid" to ("""{$node,"parentId":""}""" to CreateNodeRequest::class.java),
            // A field trimmed by `TrimmingStringDeserializer` read any scalar as its text and an object as "".
            "a boolean for a trimmed string" to ("""{"tenantName":false,"domain":"a.example","ownerEmail":"a@b.example"}""" to CreateTenantRequest::class.java),
            "an object for a trimmed string" to ("""{"tenantName":{},"domain":"a.example","ownerEmail":"a@b.example"}""" to CreateTenantRequest::class.java),
        )
        for ((what, case) in coerced) {
            assertThrows<MismatchedInputException>(what) { mapper.readValue(case.first, case.second) }
        }
        // The control: the same documents with the declared types bind.
        mapper.readValue("""{"tls":true,"enabled":true}""", TenantMailSettingsUpdate::class.java)
        mapper.readValue("""{$node,"orderIndex":0}""", CreateNodeRequest::class.java)
        mapper.readValue("""{$order,"deferStatementUntil":"2026-10-20T00:00:00Z"}""", AuthorityOrderRequest::class.java)
        assertEquals("Fuzz", mapper.readValue("""{"tenantName":" Fuzz ","domain":"a.example","ownerEmail":"a@b.example"}""", CreateTenantRequest::class.java).tenantName)
    }

    /** An integer is a value, not a spelling: JSON Schema's `integer` admits `2.0`, so the contract does, and the API
     *  fuzzing's positive writes sent one to every integer field (`metaRevision`, `epoch`, `bytes` …) and got `400`
     *  (ADR 0091 decision 9). A whole number in any spelling binds; a fraction, or a number past the field's type, is
     *  still refused while binding — no truncation, no wrap-around ([StrictJsonTypes]). */
    @Test
    fun aWholeNumberSpelledAsAFloatIsThatIntegerAndNothingElseIs() {
        val node = """"nodeId":"${UUID.randomUUID()}","kind":"FOLDER","encryptedName":"bmFtZQ==","slugToken":"c2x1Zw==","wrappedContentKey":"a2V5""""
        assertEquals(2, mapper.readValue("""{$node,"orderIndex":2.0}""", CreateNodeRequest::class.java).orderIndex)
        assertEquals(2, mapper.readValue("""{$node,"orderIndex":2e0}""", CreateNodeRequest::class.java).orderIndex)
        assertEquals(1676914L, mapper.readValue("""{"blob":"YmxvYg==","revision":1676914.0}""", DeviceRegisterUpdate::class.java).revision)
        for (bad in listOf("1.5", "2147483648", "2147483648.0", "1e300", "-2147483649")) {
            assertThrows<MismatchedInputException>(bad) { mapper.readValue("""{$node,"orderIndex":$bad}""", CreateNodeRequest::class.java) }
        }
        for (bad in listOf("9223372036854775808", "9.3e18", "0.5")) {
            assertThrows<MismatchedInputException>(bad) { mapper.readValue("""{"blob":"YmxvYg==","revision":$bad}""", DeviceRegisterUpdate::class.java) }
        }
    }

    /** A date-time the `TIMESTAMP` columns cannot hold — MariaDB's, from 1970-01-01T00:00:01Z to 2038-01-19T03:14:07Z —
     *  reached the `UPDATE` and answered 500 (`updateShare` with an `expiresAt` in 2577: the API fuzzing's positive
     *  writes, ADR 0091 decision 9). Refused while binding, as any date-time that is not one ([StrictJsonTypes]); H2
     *  stores such an instant, so this reds on the missing refusal, not on the database. */
    @Test
    fun aDateTimeTheTimestampColumnsCannotHoldIsInvalidRequest() {
        val order = """"id":"${UUID.randomUUID()}","kind":"REMOVAL","shareId":"${"a".repeat(43)}","receivedAt":"2026-10-18T07:12:00Z","reference":{"epoch":2,"sealedReference":"cmVm"}"""
        for (bad in listOf("2577-12-11T13:05:27+01:00", "2038-01-19T03:14:08Z", "1970-01-01T00:00:00Z", "0001-01-01T00:00:00Z")) {
            assertThrows<MismatchedInputException>(bad) {
                mapper.readValue("""{$order,"deferStatementUntil":"$bad"}""", AuthorityOrderRequest::class.java)
            }
        }
        // The controls: the edges of the range, in any offset, bind.
        for (ok in listOf("2038-01-19T03:14:07Z", "2038-01-19T04:14:07+01:00", "1970-01-01T00:00:01Z")) {
            mapper.readValue("""{$order,"deferStatementUntil":"$ok"}""", AuthorityOrderRequest::class.java)
        }
    }

    /** A JSON `null` binds only where the contract allows one (NullRefused): `orderIndex` is an optional integer with
     *  no `null` in its type, `parentId` an optional `[uuid, null]` — the scope root. */
    @Test
    fun aNullWhereTheContractAllowsNoneIsInvalidRequest() {
        val node = """"nodeId":"${UUID.randomUUID()}","kind":"FOLDER","encryptedName":"bmFtZQ==","slugToken":"c2x1Zw==","wrappedContentKey":"a2V5""""
        assertThrows<MismatchedInputException> { mapper.readValue("""{$node,"orderIndex":null}""", CreateNodeRequest::class.java) }
        // The controls: a null the contract allows binds, and an absent property stays absent.
        assertEquals(null, mapper.readValue("""{$node,"parentId":null}""", CreateNodeRequest::class.java).parentId)
        mapper.readValue("""{$node}""", CreateNodeRequest::class.java)
    }

    /** A nullable nested object (`oneOf` [a schema, `null`]) is validated into: the generator dropped its `@Valid`, so a
     *  Server Owner's statement passed any bound of its sealed facts (NullableObjectCascade, the API fuzzing's first run). */
    @Test
    fun aNullableNestedObjectIsValidatedInto() {
        val facts = { sealed: String -> ServerOwnerStatementInputFactsForLog(epoch = 1, sealedFacts = sealed) }
        val request = { sealed: String ->
            BlockAccountSignInRequest(
                address = "member@example.com",
                statement = ServerOwnerStatementInput(UUID.randomUUID(), ServerOwnerStatementInput.Ground.TERMS_BREACH, factsForLog = facts(sealed)),
            )
        }
        assertTrue(validator.validate(request("A".repeat(12457))).isNotEmpty(), "sealed facts past 12456 characters")
        assertTrue(validator.validate(request("c2VhbGVk")).isEmpty(), "the control: within its bounds")
    }

    // --- a query parameter of the wrong type or format (StrictParamConverters) ------------------------------------

    /** A query parameter the framework could not convert answered an empty `404` (JAX-RS' rule for a query
     *  parameter), and `java.util.UUID.fromString` accepts `1-1-1-1-1`. The contract says `400 invalid_request`. */
    @Test
    fun aMalformedQueryParameterIsInvalidRequest() {
        for (nodeId in listOf("not-a-uuid", "1-1-1-1-1")) {
            val response = given().header("Authorization", bearer(user)).queryParam("nodeId", nodeId)
                .`when`().get("/api/shares")
            assertRefused(response, nodeId)
        }
        val revoke = given().header("Authorization", bearer(user)).queryParam("revokeLinks", "5")
            .`when`().delete("/api/tenants/${UUID.randomUUID()}/members/${UUID.randomUUID()}")
        assertRefused(revoke, "revokeLinks")
        // The control: a well-formed one reaches the handler (nothing shared from a node nobody owns: 200, empty).
        assertEquals(200, given().header("Authorization", bearer(user)).queryParam("nodeId", UUID.randomUUID().toString())
            .`when`().get("/api/shares").statusCode)
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
