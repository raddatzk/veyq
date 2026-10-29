package me.raddatz.veyq.core.moderation

import me.raddatz.veyq.core.generated.api.ModerationApi
import me.raddatz.veyq.core.moderation.ModerationTestKit.appointAndAccept
import me.raddatz.veyq.core.moderation.ModerationTestKit.asUser
import me.raddatz.veyq.core.moderation.ModerationTestKit.enable
import me.raddatz.veyq.core.moderation.ModerationTestKit.epochKey
import me.raddatz.veyq.core.moderation.ModerationTestKit.proof
import me.raddatz.veyq.core.testing.MainOutputs
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
 * invariant, ADR 0099 § 2) — a permanent sweep beside `RateLimitPostureSweepTest`, derived from the compiled resources,
 * never from a list of their operations. A moderation operation is every operation of a generated interface rooted under
 * `/api/moderation` ([ModerationProofGate.moderationApiOf]): the moderation spec's `ModerationApi`, and the review reads
 * other specs serve there (core's `CaseReviewApi`, Memories' `CaseMediaApi`, Depot's `CaseFilesApi` —
 * ADR 0093 § 3), which the gate finds by that path because core may not name them:
 *
 *  1. every operation is either key-proved (it declares the proof header, [ModerationProofGate.proofOperationsOf]) or —
 *     `ModerationApi`'s only — named in [ModerationProofGate.WITHOUT_PROOF] with its reason; never both, never neither,
 *     and no stale name;
 *  2. no such resource carries `@RolesAllowed` (it would answer `not_server_owner`, and hide the role check that is the
 *     gate's), and every method is `@Authenticated` but the unauthenticated exemptions, which are `@PermitAll`;
 *  3. **at runtime, every key-proved operation refuses a moderator's session that carries no proof**, reached by its
 *     own verb and path from the interface — and the same session with a proof over the operation and the case in its
 *     path is let through the gate (the control: whatever it answers then, it is not the gate's 403).
 *
 * The bite: drop the proof headers from one operation in `moderation.openapi.yaml` → (1) is red (it is in neither
 * set); make the gate pass a proof operation → (3) is red; root a review read anywhere but under `/api/moderation` → it
 * leaves the gate, and (0) misses it.
 */
@QuarkusTest
class ModerationProofPostureSweepTest {

    /** Every resource implementing a moderation interface, with that interface. */
    private val resources: List<Pair<Class<*>, Class<*>>> by lazy {
        val loader = Thread.currentThread().contextClassLoader
        MainOutputs.classNames()
            .mapNotNull { runCatching { Class.forName(it, false, loader) }.getOrNull() }
            .filter { !it.isInterface }
            .mapNotNull { cls -> ModerationProofGate.moderationApiOf(cls)?.let { cls to it } }
            .sortedBy { it.first.name }
    }

    @Test
    fun theSweepSeesTheModerationSpecAndEveryReviewRead() {
        val apis = resources.map { it.second.simpleName }.toSet()
        assertTrue(apis.containsAll(setOf("ModerationApi", "CaseReviewApi", "CaseMediaApi", "CaseFilesApi")), "found only $apis")
    }

    @Test
    fun everyOperationIsKeyProvedOrExemptWithAReason() {
        for ((_, api) in resources) {
            val operations = api.declaredMethods.map { it.name }.toSet()
            val proved = ModerationProofGate.proofOperationsOf(api)
            val exempt = if (api == ModerationApi::class.java) ModerationProofGate.WITHOUT_PROOF.keys else emptySet()
            assertTrue(proved.isNotEmpty(), "${api.simpleName}: no operation declares the proof header — the derivation found nothing")
            assertEquals(emptySet<String>(), operations - proved - exempt, "${api.simpleName}: an operation neither key-proved nor exempt with a reason")
            assertEquals(emptySet<String>(), proved intersect exempt, "an operation both key-proved and exempt")
        }
        assertEquals(
            emptySet<String>(), ModerationProofGate.WITHOUT_PROOF.keys - ModerationApi::class.java.declaredMethods.map { it.name }.toSet(),
            "an exemption names no operation of ModerationApi",
        )
        assertTrue(ModerationProofGate.WITHOUT_PROOF.values.all { it.reason.isNotBlank() })
    }

    @Test
    fun theRoleIsTheGatesAndEveryMethodIsAuthenticatedOrDeliberatelyPublic() {
        for ((resource, api) in resources) {
            assertTrue(resource.getAnnotation(RolesAllowed::class.java) == null, "@RolesAllowed on ${resource.simpleName}")
            assertTrue(resource.getAnnotation(Authenticated::class.java) != null, "${resource.simpleName} is @Authenticated by default")
            for (name in api.declaredMethods.map { it.name }) {
                val method = resource.methods.first { it.name == name }
                assertTrue(method.getAnnotation(RolesAllowed::class.java) == null, "$name carries @RolesAllowed")
                val public = method.getAnnotation(PermitAll::class.java) != null
                val shouldBePublic = api == ModerationApi::class.java &&
                    ModerationProofGate.WITHOUT_PROOF[name]?.needsRole == false && name == "getModerationKey"
                assertEquals(shouldBePublic, public, "$name: @PermitAll only on the published key")
            }
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
        return resources.flatMap { (_, api) ->
            ModerationProofGate.proofOperationsOf(api).sorted().map { op -> api to op }
        }.map { (api, op) ->
            DynamicTest.dynamicTest("${api.simpleName}.$op") {
                val method = api.declaredMethods.first { it.name == op }
                val template = api.getAnnotation(Path::class.java).value + (method.getAnnotation(Path::class.java)?.value ?: "")
                val path = template.replace(Regex("\\{[^}]+}"), other)
                val caseId = if ("{caseId}" in template) other else null
                val verb = when {
                    method.isAnnotationPresent(GET::class.java) -> "GET"
                    method.isAnnotationPresent(POST::class.java) -> "POST"
                    method.isAnnotationPresent(PUT::class.java) -> "PUT"
                    method.isAnnotationPresent(DELETE::class.java) -> "DELETE"
                    else -> error("$op has no verb")
                }
                asUser(owner).contentType(ContentType.JSON).body("{}").request(verb, path)
                    .then().statusCode(403).body("code", equalTo("moderation_proof_invalid"))
                val control = asUser(owner).contentType(ContentType.JSON).body("{}").headers(proof(owner, op, key, caseId))
                    .request(verb, path).then().extract()
                assertTrue(
                    control.statusCode() != 403 || control.path<String>("code") != "moderation_proof_invalid",
                    "$op: the gate refused a valid proof (${control.statusCode()})",
                )
            }
        }
    }
}
