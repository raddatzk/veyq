package me.raddatz.veyq.core.moderation

import me.raddatz.veyq.core.generated.api.ModerationApi
import me.raddatz.veyq.core.moderation.ModerationTestKit.appointAndAccept
import me.raddatz.veyq.core.moderation.ModerationTestKit.asUser
import me.raddatz.veyq.core.moderation.ModerationTestKit.enable
import me.raddatz.veyq.core.moderation.ModerationTestKit.epochKey
import me.raddatz.veyq.core.moderation.ModerationTestKit.proof
import me.raddatz.veyq.core.testing.TestFixtures.IDENTITY_PUBLIC_KEY
import me.raddatz.veyq.core.testing.TestFixtures.ensureUser
import me.raddatz.veyq.core.testing.TestFixtures.seedUser
import io.quarkus.security.Authenticated
import io.quarkus.test.junit.QuarkusTest
import io.restassured.http.ContentType
import jakarta.annotation.security.PermitAll
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.util.UUID

/**
 * **Every moderation operation passes [ModerationProofGate]** (cr-moderators-and-the-moderation-key's structural
 * invariant, ADR 0099 § 2) — a permanent sweep beside `RateLimitPostureSweepTest`, derived from the generated
 * `ModerationApi`, never from a list of its operations:
 *
 *  1. every operation is either key-proved (it declares the proof header, [ModerationProofGate.PROOF_OPERATIONS]) or
 *     named in [ModerationProofGate.WITHOUT_PROOF] with its reason — never both, never neither, and no stale name;
 *  2. the resource carries no `@RolesAllowed` (it would answer `not_server_owner`, and hide the role check that is the
 *     gate's), and every method is `@Authenticated` but the unauthenticated exemptions, which are `@PermitAll`;
 *  3. **at runtime, every key-proved operation refuses a moderator's session that carries no proof**, reached by its
 *     own verb and path from the interface — and the same session with a proof is let through the gate (the control:
 *     whatever it answers then, it is not the gate's 403).
 *
 * The bite: drop the proof headers from one operation in `moderation.openapi.yaml` → (1) is red (it is in neither
 * set); make the gate pass a proof operation → (3) is red.
 */
@QuarkusTest
class ModerationProofPostureSweepTest {

    private val operations: Set<String> = ModerationApi::class.java.declaredMethods.map { it.name }.toSet()

    @Test
    fun everyOperationIsKeyProvedOrExemptWithAReason() {
        val proved = ModerationProofGate.PROOF_OPERATIONS
        val exempt = ModerationProofGate.WITHOUT_PROOF.keys
        assertTrue(proved.isNotEmpty(), "no operation declares the proof header — the derivation found nothing")
        assertEquals(emptySet<String>(), operations - proved - exempt, "an operation neither key-proved nor exempt with a reason")
        assertEquals(emptySet<String>(), proved intersect exempt, "an operation both key-proved and exempt")
        assertEquals(emptySet<String>(), exempt - operations, "an exemption names no operation of ModerationApi")
        assertTrue(ModerationProofGate.WITHOUT_PROOF.values.all { it.reason.isNotBlank() })
    }

    @Test
    fun theRoleIsTheGatesAndEveryMethodIsAuthenticatedOrDeliberatelyPublic() {
        val resource = ModerationResource::class.java
        assertTrue(resource.getAnnotation(RolesAllowed::class.java) == null, "@RolesAllowed on the moderation resource")
        assertTrue(resource.getAnnotation(Authenticated::class.java) != null, "the resource is @Authenticated by default")
        for (name in operations) {
            val method = resource.methods.first { it.name == name }
            assertTrue(method.getAnnotation(RolesAllowed::class.java) == null, "$name carries @RolesAllowed")
            val public = method.getAnnotation(PermitAll::class.java) != null
            val shouldBePublic = ModerationProofGate.WITHOUT_PROOF[name]?.needsRole == false && name == "getModerationKey"
            assertEquals(shouldBePublic, public, "$name: @PermitAll only on the published key")
        }
    }

    @TestFactory
    fun everyKeyProvedOperationRefusesAModeratorWithoutTheProof(): List<DynamicTest> {
        ModerationTestKit.reset()
        val owner = UUID.randomUUID().toString().also { ensureUser(it, "sweep-owner-$it@example.com", "SERVER_OWNER") }
        val key = epochKey()
        enable(owner, key, IDENTITY_PUBLIC_KEY)
        val other = UUID.randomUUID().toString().also { seedUser(id = it, email = "sweep-$it@example.com") }
        appointAndAccept(owner, "sweep-$other@example.com", other)
        return ModerationProofGate.PROOF_OPERATIONS.sorted().map { op ->
            DynamicTest.dynamicTest(op) {
                val method = ModerationApi::class.java.declaredMethods.first { it.name == op }
                val path = (ModerationApi::class.java.getAnnotation(Path::class.java).value +
                    (method.getAnnotation(Path::class.java)?.value ?: ""))
                    .replace(Regex("\\{[^}]+}"), other)
                val verb = when {
                    method.isAnnotationPresent(GET::class.java) -> "GET"
                    method.isAnnotationPresent(POST::class.java) -> "POST"
                    method.isAnnotationPresent(PUT::class.java) -> "PUT"
                    method.isAnnotationPresent(DELETE::class.java) -> "DELETE"
                    else -> error("$op has no verb")
                }
                asUser(owner).contentType(ContentType.JSON).body("{}").request(verb, path)
                    .then().statusCode(403).body("code", equalTo("moderation_proof_invalid"))
                val control = asUser(owner).contentType(ContentType.JSON).body("{}").headers(proof(owner, op, key))
                    .request(verb, path).then().extract()
                assertTrue(
                    control.statusCode() != 403 || control.path<String>("code") != "moderation_proof_invalid",
                    "$op: the gate refused a valid proof (${control.statusCode()})",
                )
            }
        }
    }
}
