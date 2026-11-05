package me.raddatz.veyq.core.spaces.scope

import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.util.UUID

/**
 * A rename's invitee entries (uc.spaces.rename-group, ADR 0111 decision 9) and the contract are one shape. An entry's
 * `sealedNameKey` and `recipientIdentityDigest` are both set or both `null` — the schema's own rule (`InviteeNameKey`'s
 * `dependentSchemas`), so one `null` alone is the shape's `400 invalid_request`. One invitee named twice is a rule
 * across the items no schema states, so it is `422 invitee_repeated`, never the 400 a request the schema admits must
 * not get. Both are answered before anything about the instance's state, and write nothing.
 *
 * The API fuzzing (run of 2026-11-05, ST202) sent each as valid and got 400: a single entry with one `null` (the
 * pairing was prose only), and the coverage phase's near-boundary list — the same entry repeated.
 */
@QuarkusTest
class RenameGroupShapeSliceTest {

    @Test
    fun theContractStatesThePairRuleAndDeclaresTheRepeat() {
        val doc = Yaml().load<Map<String, Any?>>(File("../../contracts/api/scopes.openapi.yaml").readText())
        val rule = map(map(map(map(doc, "components"), "schemas"), "InviteeNameKey"), "dependentSchemas")
        val pair = map(rule, "sealedNameKey")
        assertEquals("string", map(map(map(pair, "if"), "properties"), "sealedNameKey")["type"], "the rule hangs on a set key")
        assertEquals("string", map(map(map(pair, "then"), "properties"), "recipientIdentityDigest")["type"], "a set key brings a set digest")
        assertEquals("null", map(map(map(pair, "else"), "properties"), "recipientIdentityDigest")["type"], "a null key a null digest")
        val refused = map(map(map(map(doc, "paths"), "/api/scopes/{scopeId}/name"), "post"), "responses")
        val repeat = map(map(map(refused, "422"), "content"), "application/json")
        assertEquals("invitee_repeated", map(repeat, "example")["code"], "renameGroup declares the repeat's 422")
    }

    @Test
    fun anEntryWithOneNullIs400BeforeAnyStateAnswerAndWritesNothing() {
        val (owner, scopeId, _) = seedMemberScope(ScopeEntity.GROUP)
        val stranger = seedUser()
        val invitee = "5b6c7c3f-0e2a-4b1d-8a6e-8b2c1f2d3e4a"
        for (caller in listOf(owner, stranger)) { // a non-member too: the shape is answered before the membership is
            for (entry in listOf(
                """{"userId":"$invitee","sealedNameKey":"$NAME_KEY","recipientIdentityDigest":null}""", // the fuzzing's case
                """{"userId":"$invitee","sealedNameKey":null,"recipientIdentityDigest":"$DIGEST"}""",
            )) {
                rename(caller, scopeId, entry).statusCode(400).body("code", equalTo("invalid_request"))
            }
        }
        assertEquals(null, name(scopeId), "a refused rename writes nothing")
        // Admitted: both null, and both set (an id that holds no pending proposal is ignored) — each lands.
        rename(owner, scopeId, """{"userId":"$invitee","sealedNameKey":null,"recipientIdentityDigest":null}""").statusCode(204)
        rename(owner, scopeId, """{"userId":"$invitee","sealedNameKey":"$NAME_KEY","recipientIdentityDigest":"$DIGEST"}""").statusCode(204)
        assertEquals(RENAMED, name(scopeId))
    }

    @Test
    fun oneInviteeNamedTwiceIs422NeverTheShapes400AndWritesNothing() {
        val (owner, scopeId, _) = seedMemberScope(ScopeEntity.GROUP)
        val stranger = seedUser()
        // The coverage phase's near-boundary list: one entry, repeated.
        val entry = """{"recipientIdentityDigest":"0000","sealedNameKey":"0000","userId":"e3e70682-c209-1cac-a29f-6fbed82c07cd"}"""
        for (caller in listOf(owner, stranger)) {
            rename(caller, scopeId, entry, entry, entry).statusCode(422).body("code", equalTo("invitee_repeated"))
        }
        // Two entries for one invitee that differ otherwise are the same repeat; and a repeat beside a one-null entry is
        // the shape's 400 first.
        rename(owner, scopeId, entry, """{"userId":"e3e70682-c209-1cac-a29f-6fbed82c07cd","sealedNameKey":null,"recipientIdentityDigest":null}""")
            .statusCode(422).body("code", equalTo("invitee_repeated"))
        rename(owner, scopeId, entry, entry, """{"userId":"${UUID.randomUUID()}","sealedNameKey":"0000","recipientIdentityDigest":null}""")
            .statusCode(400).body("code", equalTo("invalid_request"))
        assertEquals(null, name(scopeId), "a refused rename writes nothing")
        // Admitted: each invitee once.
        rename(owner, scopeId, entry, """{"recipientIdentityDigest":"0000","sealedNameKey":"0000","userId":"${UUID.randomUUID()}"}""").statusCode(204)
        assertEquals(RENAMED, name(scopeId))
    }

    private fun rename(caller: String, scopeId: String, vararg entries: String) =
        given().header("Authorization", bearer(caller)).contentType(ContentType.JSON)
            .body(
                """{"encryptedName":"$RENAMED","nameEpoch":0,"wrappedNameValueKey":"d3JhcHBlZC12YWx1ZS1rZXk=",""" +
                    """"inviteeNameKeys":[${entries.joinToString(",")}]}""",
            )
            .`when`().post("/api/scopes/$scopeId/name").then()

    private fun name(scopeId: String): String? = QuarkusTransaction.requiringNew().call { ScopeEntity.findById(scopeId)!!.encryptedName }

    @Suppress("UNCHECKED_CAST")
    private fun map(node: Map<String, Any?>, key: String): Map<String, Any?> =
        node[key] as? Map<String, Any?> ?: error("the contract has no `$key` here")

    private companion object {
        const val RENAMED = "cmVuYW1lZA=="
        const val NAME_KEY = "Z3JvdXAtbmFtZS1rZXktc2VhbGVkLXRvLWludml0ZWU="
        const val DIGEST = "ZGlnZXN0ZGlnZXN0ZGlnZXN0ZGlnZXN0ZGlnZXN0ZGk="
    }
}
