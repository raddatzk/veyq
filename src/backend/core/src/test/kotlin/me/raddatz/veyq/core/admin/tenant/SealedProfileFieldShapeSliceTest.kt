package me.raddatz.veyq.core.admin.tenant

import me.raddatz.veyq.core.identity.TenantProfileStore
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedTenant
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.util.UUID

/**
 * A sealed profile field, its value key's wrap and the tenant epoch that wrap is under (ADR 0115) have one shape on the
 * wire, and the contract states it as a schema rule the server holds exactly: a field and its wrap come together, a
 * field brings a non-null `epoch`, and a `null` is the field's absence — on both accept bodies (`AcceptInviteRequest`,
 * `RegisterAndAcceptRequest`) and on the profile edit (`ProfileUpdateRequest`). A body the rule refuses is `400
 * invalid_request` before any answer about the instance's state; one it admits is never refused for its shape.
 *
 * The API fuzzing (run of 2026-11-05, ST202) sent three bodies the contract admitted and the server refused: an accept
 * and a register carrying `wrappedAvatarValueKey` without its `sealedAvatar` (the pairing was prose only), and a profile
 * edit carrying `"epoch": null` beside its `sealedName` (`dependentRequired`, which a `null` satisfies). Each is here,
 * and so is the contract's rule, read from the file, so neither side can move without the other going red.
 */
@QuarkusTest
class SealedProfileFieldShapeSliceTest {

    @Inject lateinit var profiles: TenantProfileStore

    @Test
    fun theContractStatesEveryPairAndItsEpochOnANonNullValue() {
        for (schema in listOf("AcceptInviteRequest", "RegisterAndAcceptRequest")) {
            val rules = dependentSchemas("tenants.openapi.yaml", schema)
            for ((field, wrap) in PAIRS) {
                assertRule(rules, schema, field, brings = mapOf(wrap to "string", "epoch" to "integer"))
                assertRule(rules, schema, wrap, brings = mapOf(field to "string"))
            }
        }
        val profile = dependentSchemas("account.openapi.yaml", "ProfileUpdateRequest")
        assertRule(profile, "ProfileUpdateRequest", "sealedName", brings = mapOf("wrappedNameValueKey" to "string", "epoch" to "integer"))
        assertRule(profile, "ProfileUpdateRequest", "wrappedNameValueKey", brings = mapOf("sealedName" to "string"))
    }

    @Test
    fun anAcceptBodyTheRuleRefusesIs400BeforeTheInviteIsReadAndOneItAdmitsReachesIt() {
        val caller = seedUser()
        // The fuzzing's own case, verbatim but for the token: a wrap at its maximum length, no field beside it.
        accept(caller, """"grant":"AQ==","wrappedAvatarValueKey":"${"0".repeat(126)}=="""").statusCode(400).body("code", equalTo("invalid_request"))
        for ((field, wrap) in PAIRS) {
            for (refused in listOf(
                """"$wrap":"$WRAP","epoch":0""", // a wrap alone
                """"$field":"$SEALED","epoch":0""", // a field alone
                """"$field":"$SEALED","$wrap":null,"epoch":0""", // a field beside a null wrap
                """"$field":"$SEALED","$wrap":"$WRAP"""", // no epoch
                """"$field":"$SEALED","$wrap":"$WRAP","epoch":null""", // a null epoch
            )) {
                accept(caller, refused).statusCode(400).body("code", equalTo("invalid_request"))
            }
            // Admitted: the shape holds, so the answer is about the instance — an invite nobody has.
            accept(caller, """"$field":"$SEALED","$wrap":"$WRAP","epoch":0""").statusCode(404).body("code", equalTo("invite_not_found"))
            accept(caller, """"$field":null,"$wrap":null""").statusCode(404).body("code", equalTo("invite_not_found"))
        }
    }

    @Test
    fun aRegisterBodyTheRuleRefusesIs400BeforeTheInviteIsReadAndOneItAdmitsReachesIt() {
        register(""""grant":"AQ==","wrappedAvatarValueKey":"${"0".repeat(126)}=="""").statusCode(400).body("code", equalTo("invalid_request"))
        register(""""sealedJoinRecord":"$SEALED","wrappedJoinRecordValueKey":"$WRAP"""").statusCode(400).body("code", equalTo("invalid_request"))
        register(""""sealedJoinRecord":"$SEALED","wrappedJoinRecordValueKey":"$WRAP","epoch":0""").statusCode(404).body("code", equalTo("invite_not_found"))
    }

    @Test
    fun aProfileEditTheRuleRefusesIs400AndOneItAdmitsLands() {
        val owner = seedUser(email = "owner@shape-prof.example.com")
        val tenantId = seedTenant(domain = DOMAIN, ownerEmail = "owner@shape-prof.example.com", owner = owner)
        QuarkusTransaction.requiringNew().run { profiles.create(owner, tenantId, "shaped") }
        // The fuzzing's own case, verbatim: a null epoch beside the name.
        edit(owner, """{"username":"kevin","sealedName":"$SEALED","wrappedNameValueKey":"$WRAP","epoch":null}""")
            .statusCode(400).body("code", equalTo("invalid_request"))
        for (refused in listOf(
            """{"sealedName":"$SEALED","wrappedNameValueKey":null,"epoch":0}""",
            """{"sealedName":"$SEALED","epoch":0}""",
            """{"wrappedNameValueKey":"$WRAP","epoch":0}""",
            """{"sealedName":"$SEALED","wrappedNameValueKey":"$WRAP"}""",
        )) {
            edit(owner, refused).statusCode(400).body("code", equalTo("invalid_request"))
        }
        assertEquals("shaped", QuarkusTransaction.requiringNew().call { profiles.find(owner, tenantId)!!.username }, "a refused edit writes nothing")
        // Admitted: a null is the field's absence and brings nothing; a whole name lands.
        edit(owner, """{"wrappedNameValueKey":null,"epoch":null}""").statusCode(200)
        edit(owner, """{"username":"kevin","sealedName":"$SEALED","wrappedNameValueKey":"$WRAP","epoch":0}""").statusCode(200)
            .body("username", equalTo("kevin")).body("sealedName", equalTo(SEALED))
    }

    // --- the wire ---

    private fun accept(caller: String, fields: String) =
        given().header("Authorization", bearer(caller)).contentType(ContentType.JSON)
            .body("""{"bootstrapSecret":"no-such-invite","username":"kevin",$fields}""")
            .`when`().post("/api/tenants/accept").then()

    private fun register(fields: String) =
        given().contentType(ContentType.JSON)
            .body(
                """{"bootstrapSecret":"no-such-invite","userId":"${UUID.randomUUID()}","username":"kevin",$fields,""" +
                    """"account":{"suiteId":"v1","identityPublicKey":"aWRlbnRpdHktcHVibGljLWtleQ==",""" +
                    """"wrappedIdentityPrivateKey":"d3JhcHBlZC1pZGVudGl0eS1wcml2YXRlLWtleQ==","authPublicKey":"ZWQyNTUxOS1hdXRoLXB1Yg==",""" +
                    """"masterKeyWraps":[{"method":"PASSWORD","wrappedKey":"d3JhcHBlZC1tYXN0ZXIta2V5","kdfParams":"a2RmLXBhcmFtcw=="}],""" +
                    """"secretKeyProofPublicKey":"c2VjcmV0LWtleS1wcm9vZi1wdWJsaWMta2V5"}}""",
            )
            .`when`().post("/api/tenants/accept/register").then()

    private fun edit(caller: String, body: String) =
        given().header("Authorization", bearer(caller)).header("X-Forwarded-Host", DOMAIN).contentType(ContentType.JSON)
            .body(body).`when`().put("/api/account/profile").then()

    // --- the contract ---

    /** [schema]'s `dependentSchemas` in `contracts/api/[spec]`; a schema without them is a failure. */
    @Suppress("UNCHECKED_CAST")
    private fun dependentSchemas(spec: String, schema: String): Map<String, Map<String, Any?>> {
        val doc = Yaml().load<Map<String, Any?>>(File("../../contracts/api/$spec").readText())
        val node = ((doc["components"] as Map<String, Any?>)["schemas"] as Map<String, Any?>)[schema] as Map<String, Any?>
        return node["dependentSchemas"] as? Map<String, Map<String, Any?>> ?: error("$spec:$schema states no dependentSchemas")
    }

    /** The rule under [key]: it fires on a non-null [key] only (a `null` is absence), and then requires each of [brings]
     *  with the type named — so a `null` there does not pass for it. */
    @Suppress("UNCHECKED_CAST")
    private fun assertRule(rules: Map<String, Map<String, Any?>>, schema: String, key: String, brings: Map<String, String>) {
        val rule = rules[key] ?: error("$schema: no rule on $key")
        val condition = ((rule["if"] as? Map<String, Any?>)?.get("properties") as? Map<String, Map<String, Any?>>)?.get(key)
        assertEquals("string", condition?.get("type"), "$schema: the rule on $key fires on a non-null $key only")
        val then = rule["then"] as? Map<String, Any?> ?: error("$schema: the rule on $key requires nothing")
        assertEquals(brings.keys, (then["required"] as? List<String>).orEmpty().toSet(), "$schema: what a $key brings")
        val types = (then["properties"] as? Map<String, Map<String, Any?>>).orEmpty().mapValues { it.value["type"] }
        assertEquals(brings, types, "$schema: each field a $key brings is non-null")
    }

    private companion object {
        const val DOMAIN = "shape-prof.example.com"
        const val SEALED = "c2VhbGVk"
        const val WRAP = "d3JhcHBlZC12YWx1ZS1rZXk="
        /** Each sealed profile field of an accept beside its value key's wrap. */
        val PAIRS = listOf(
            "sealedJoinRecord" to "wrappedJoinRecordValueKey",
            "sealedName" to "wrappedNameValueKey",
            "sealedAvatar" to "wrappedAvatarValueKey",
        )
    }
}
