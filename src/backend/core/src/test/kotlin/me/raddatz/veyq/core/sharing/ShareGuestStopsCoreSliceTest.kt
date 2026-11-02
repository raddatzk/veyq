package me.raddatz.veyq.core.sharing

import me.raddatz.veyq.core.TestIds
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.PUBLIC_SHARES
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds.PUBLIC_SHARES_CONTRIBUTE
import me.raddatz.veyq.core.platform.capabilities.CapabilityRequirementFixtures
import me.raddatz.veyq.core.platform.capabilities.CapabilityRequirements.LINK_MAX_DAYS
import me.raddatz.veyq.core.platform.capabilities.CapabilityRequirements.LINK_PASSWORD
import me.raddatz.veyq.core.platform.capabilities.CapabilitySwitchFixtures
import me.raddatz.veyq.core.platform.capabilities.TenantCapabilitySwitchFixtures
import me.raddatz.veyq.core.scope.ScopeEntity
import me.raddatz.veyq.core.sharing.GuestPaths.Path
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.MemberScope
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.nullValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * A member's share list says why a link serves no guest (inbox #457): `ShareSummary.guestStops`, the guest gate's own
 * answer ([ShareGuestGate.guestStops], which [ShareGuestGate.activeShare] is the emptiness of). Each reason on its own,
 * then the property the list exists for over a matrix of switches, requirements and links: the list's stops are the
 * rule's, a link is listed as serving exactly when a guest is served it, and a guest of a stopped link still gets the
 * unknown link's `404`, byte for byte (ADR 0088 decision 5) — the list's answer reaches no guest.
 *
 * The expected stops come from an oracle written here from the contract's words, not from the code under test, so a
 * reason the server drops, adds or reorders is red.
 */
@QuarkusTest
class ShareGuestStopsCoreSliceTest {

    @AfterEach
    fun clearEverything() {
        CapabilitySwitchFixtures.clear()
        TenantCapabilitySwitchFixtures.clear()
        CapabilityRequirementFixtures.clear()
    }

    // --- each reason ---------------------------------------------------------------------------------------------------

    @Test
    fun aServedLinkListsNoStopAndARevokedOrExpiredOneSaysSo() {
        val w = world()
        val served = link(w, ShareEntity.READ)
        val revoked = link(w, ShareEntity.READ) { enabled = false }
        val expired = link(w, ShareEntity.READ) { expiresAt = Instant.now().minusSeconds(60) }
        val both = link(w, ShareEntity.READ) { enabled = false; expiresAt = Instant.now().minusSeconds(60) }
        val stops = stopsOnNode(w)
        assertEquals(emptyList<String>(), stops.getValue(served))
        assertEquals(listOf("revoked"), stops.getValue(revoked))
        assertEquals(listOf("expired"), stops.getValue(expired))
        assertEquals(listOf("revoked", "expired"), stops.getValue(both))
        // The owner's own list (no node) carries the same answer.
        assertEquals(stops, stopsOfMine(w).filterKeys { it in stops })
    }

    @Test
    fun aSpaceBeingDeletedSaysSoToTheMemberWhoStillReadsIt() {
        val w = world()
        val l = link(w, ShareEntity.READ)
        QuarkusTransaction.requiringNew().run { ScopeEntity.findById(w.space.scopeId)!!.deletedAt = Instant.now() }
        assertEquals(listOf("spaceUnavailable"), stopsOnNode(w).getValue(l))
    }

    @Test
    fun anInstanceSwitchNamesItsEntryOnTheLinksOfItsRoleOnly() {
        val w = world()
        val read = link(w, ShareEntity.READ)
        val contribute = link(w, ShareEntity.READ_WRITE)
        val dropBox = link(w, ShareEntity.WRITE_ONLY)
        CapabilitySwitchFixtures.with(PUBLIC_SHARES_CONTRIBUTE) {
            val stops = stopsOnNode(w)
            assertEquals(listOf("instanceSwitch:$PUBLIC_SHARES_CONTRIBUTE"), stops.getValue(contribute))
            assertEquals(emptyList<String>(), stops.getValue(read), "another role's switch")
            assertEquals(emptyList<String>(), stops.getValue(dropBox), "another role's switch")
        }
        CapabilitySwitchFixtures.with(PUBLIC_SHARES) {
            val stops = stopsOnNode(w)
            listOf(read, contribute, dropBox).forEach { assertEquals(listOf("instanceSwitch:$PUBLIC_SHARES"), stops.getValue(it)) }
        }
    }

    @Test
    fun aTenantSwitchNamesItsEntryInThatTenantOnlyAndBothLevelsAreListed() {
        val a = world()
        val b = world()
        val inA = link(a, ShareEntity.READ_WRITE)
        val inB = link(b, ShareEntity.READ_WRITE)
        TenantCapabilitySwitchFixtures.with(a.space.tenantId, PUBLIC_SHARES) {
            assertEquals(listOf("tenantSwitch:$PUBLIC_SHARES"), stopsOnNode(a).getValue(inA))
            assertEquals(emptyList<String>(), stopsOnNode(b).getValue(inB), "another tenant")
            // Both levels off, on the parent and the leaf: every switch that would have to come back on, root first.
            CapabilitySwitchFixtures.with(PUBLIC_SHARES, PUBLIC_SHARES_CONTRIBUTE) {
                assertEquals(
                    listOf("instanceSwitch:$PUBLIC_SHARES", "tenantSwitch:$PUBLIC_SHARES", "instanceSwitch:$PUBLIC_SHARES_CONTRIBUTE"),
                    stopsOnNode(a).getValue(inA),
                )
            }
        }
    }

    @Test
    fun aMissedRequirementIsNamedWithTheLifetimeInEffect() {
        val w = world()
        val bare = link(w, ShareEntity.READ)
        val withPassword = link(w, ShareEntity.READ) { password() }
        val young = link(w, ShareEntity.READ) { expiresAt = Instant.now().plus(Duration.ofDays(2)) }
        // The strictest of both levels is in effect: the tenant's 5 days, not the instance's 30.
        CapabilityRequirementFixtures.with(
            instance = mapOf(LINK_PASSWORD to 1, LINK_MAX_DAYS to 30), tenantId = w.space.tenantId, tenant = mapOf(LINK_MAX_DAYS to 5),
        ) {
            val stops = stopsOnNode(w)
            assertEquals(listOf("linkPassword", "linkMaxDays:5"), stops.getValue(bare))
            assertEquals(listOf("linkMaxDays:5"), stops.getValue(withPassword))
            assertEquals(listOf("linkPassword"), stops.getValue(young))
        }
    }

    // --- the fix: the same link back (owner, 2026-10-16) ----------------------------------------------------------------

    /**
     * A link a new password requirement stopped comes back as the SAME link (its id, so its URL and key) once a member
     * gives it a password: the write toward the requirement is accepted, one that still misses it refused, and the guest
     * then meets the password prompt (the locked resolve) instead of the unknown link's 404.
     */
    @Test
    fun aMemberGivesAStoppedLinkAPasswordAndItsGuestMeetsThePromptInsteadOfThe404() {
        val w = world()
        val l = link(w, ShareEntity.READ)
        CapabilityRequirementFixtures.with(instance = mapOf(LINK_PASSWORD to 1)) {
            assertEquals(listOf("linkPassword"), stopsOnNode(w).getValue(l))
            assertEquals(emptyList<String>(), GuestPaths.refusedLikeAnUnknownLink(l, resolve, "before the fix"))
            edit(w, l, """{"passwordRequired":false}""").then().statusCode(422).body("code", equalTo("link_password_required"))
            edit(w, l, PASSWORD_EDIT).then().statusCode(204)
            assertEquals(emptyList<String>(), stopsOnNode(w).getValue(l))
            given().`when`().get("/api/shares/$l").then().statusCode(200)
                .body("passwordRequired", equalTo(true)).body("pwSalt", equalTo(PW_SALT)).body("wrap", nullValue())
        }
    }

    /** The lifetime's fix: an end date within the days counted from when the link was made, on the same link; one
     *  beyond them is refused and leaves the link stopped. */
    @Test
    fun aMemberGivesAStoppedLinkAnEndDateWithinTheLifetimeAndTheSameLinkServesAgain() {
        val w = world()
        val l = link(w, ShareEntity.READ)
        CapabilityRequirementFixtures.with(tenantId = w.space.tenantId, tenant = mapOf(LINK_MAX_DAYS to 7)) {
            assertEquals(listOf("linkMaxDays:7"), stopsOnNode(w).getValue(l))
            edit(w, l, """{"expiresAt":"${Instant.now().plus(Duration.ofDays(30))}"}""")
                .then().statusCode(422).body("code", equalTo("link_expiry_required"))
            assertEquals(listOf("linkMaxDays:7"), stopsOnNode(w).getValue(l), "a refused write changed nothing")
            edit(w, l, """{"expiresAt":"${Instant.now().plus(Duration.ofDays(2))}"}""").then().statusCode(204)
            assertEquals(emptyList<String>(), stopsOnNode(w).getValue(l))
            given().`when`().get("/api/shares/$l").then().statusCode(200).body("passwordRequired", equalTo(false))
        }
    }

    /** With both requirements missed, a write that meets one is still refused by the other; one that meets both serves. */
    @Test
    fun aWriteThatMeetsOneRequirementOfTwoIsRefusedAndOneThatMeetsBothServes() {
        val w = world()
        val l = link(w, ShareEntity.READ)
        CapabilityRequirementFixtures.with(instance = mapOf(LINK_PASSWORD to 1, LINK_MAX_DAYS to 7)) {
            edit(w, l, PASSWORD_EDIT).then().statusCode(422).body("code", equalTo("link_expiry_required"))
            edit(w, l, PASSWORD_EDIT.dropLast(1) + ""","expiresAt":"${Instant.now().plus(Duration.ofDays(3))}"}""")
                .then().statusCode(204)
            assertEquals(emptyList<String>(), stopsOnNode(w).getValue(l))
            assertEquals(emptyList<String>(), GuestPaths.served(l, resolve, "after both fixes"))
        }
    }

    /**
     * A link older than the lifetime has no end date an edit may give it (the days count from when it was made), so it is
     * renewed: a new link (a new id, so a new URL) with an end date within the days from now, the old one deleted.
     */
    @Test
    fun aLinkOlderThanTheLifetimeIsRefusedEveryEditAndIsRenewedAsANewLink() {
        val w = world()
        val old = link(w, ShareEntity.READ) { createdAt = Instant.now().minus(Duration.ofDays(10)) }
        CapabilityRequirementFixtures.with(instance = mapOf(LINK_MAX_DAYS to 7)) {
            edit(w, old, """{"expiresAt":"${Instant.now().plus(Duration.ofHours(1))}"}""")
                .then().statusCode(422).body("code", equalTo("link_expiry_required"))
            val renewed = TestIds.shareId()
            given().header("Authorization", bearer(w.space.userId)).contentType(ContentType.JSON)
                .body(
                    """{"shareId":"$renewed",${ShareWriteProof.field(renewed)},"wrap":"d3JhcA==","sealedName":"c2VhbGVk",""" +
                        """${ShareWriteProof.recovery()},"expiresAt":"${Instant.now().plus(Duration.ofDays(5))}"}""",
                )
                .`when`().post("/api/shares/$old/reissue").then().statusCode(201)
            val stops = stopsOnNode(w)
            assertEquals(emptyList<String>(), stops.getValue(renewed))
            assertFalse(old in stops, "the old link is deleted")
            assertEquals(emptyList<String>(), GuestPaths.served(renewed, resolve, "the renewed link"))
        }
    }

    /** Switching a link back on is never refused by a requirement it misses: it comes back on and says what it lacks,
     *  and the password then serves it — the same link throughout. */
    @Test
    fun aSwitchedOffLinkThatMissesAPasswordIsSwitchedBackOnAndThenGivenOne() {
        val w = world()
        val l = link(w, ShareEntity.READ) { enabled = false }
        CapabilityRequirementFixtures.with(instance = mapOf(LINK_PASSWORD to 1)) {
            assertEquals(listOf("revoked", "linkPassword"), stopsOnNode(w).getValue(l))
            given().header("Authorization", bearer(w.space.userId)).contentType(ContentType.JSON).body("""{"nodeEpoch":0}""").`when`().post("/api/shares/$l/reactivate").then().statusCode(204)
            assertEquals(listOf("linkPassword"), stopsOnNode(w).getValue(l))
            edit(w, l, PASSWORD_EDIT).then().statusCode(204)
            assertEquals(emptyList<String>(), stopsOnNode(w).getValue(l))
            assertEquals(emptyList<String>(), GuestPaths.served(l, resolve, "switched on and given a password"))
        }
    }

    // --- the property: the list and the guest gate agree ------------------------------------------------------------

    /**
     * Over every combination of the instance's and the tenant's share switches and the two link requirements, for links
     * of two roles with and without a password and with no, a short or a too-long lifetime (and a revoked and an expired
     * one): the member's list names exactly the oracle's stops, and a guest is served a link exactly when its list
     * names none — refused otherwise like an unknown link, byte for byte.
     */
    @Test
    fun theListAndTheGuestGateAgreeOverTheMatrix() {
        val w = world()
        val links = buildList {
            for (role in listOf(ShareEntity.READ, ShareEntity.READ_WRITE)) {
                for (pw in listOf(false, true)) {
                    for (life in Lifetime.entries) {
                        add(Link(role, pw, life, enabled = true, link(w, role) { if (pw) password(); life.apply(this) }))
                    }
                }
            }
            add(Link(ShareEntity.READ, false, Lifetime.NONE, enabled = false, link(w, ShareEntity.READ) { enabled = false }))
            add(Link(ShareEntity.READ, false, Lifetime.PAST, enabled = true, link(w, ShareEntity.READ) { Lifetime.PAST.apply(this) }))
        }
        val switchSets = listOf(emptyList(), listOf(PUBLIC_SHARES), listOf(PUBLIC_SHARES_CONTRIBUTE))
        val requirementSets = listOf(
            Rules(), Rules(password = true), Rules(maxDays = 7), Rules(password = true, maxDays = 7),
        )
        val failures = mutableListOf<String>()
        var served = 0
        var refused = 0
        for (instanceOff in switchSets) for (tenantOff in switchSets) for (rules in requirementSets) {
            val case = "instance off $instanceOff, tenant off $tenantOff, $rules"
            CapabilitySwitchFixtures.with(*instanceOff.toTypedArray()) {
                TenantCapabilitySwitchFixtures.with(w.space.tenantId, *tenantOff.toTypedArray()) {
                    // The password at the instance, the lifetime at the tenant: both levels feed the strictest.
                    CapabilityRequirementFixtures.with(
                        instance = if (rules.password) mapOf(LINK_PASSWORD to 1) else emptyMap(),
                        tenantId = w.space.tenantId,
                        tenant = rules.maxDays?.let { mapOf(LINK_MAX_DAYS to it) } ?: emptyMap(),
                    ) {
                        val listed = stopsOnNode(w)
                        for (l in links) {
                            val what = "${l.role} pw=${l.password} ${l.lifetime} enabled=${l.enabled} under $case"
                            val expected = oracle(l, instanceOff, tenantOff, rules)
                            val got = listed.getValue(l.shareId)
                            if (got != expected) failures += "$what: listed $got, expected $expected"
                            if (got.isEmpty()) {
                                served++
                                failures += GuestPaths.served(l.shareId, resolve, what)
                            } else {
                                refused++
                                failures += GuestPaths.refusedLikeAnUnknownLink(l.shareId, resolve, what)
                            }
                        }
                    }
                }
            }
        }
        // Both sides of the equivalence were reached, so neither half of it is vacuous.
        assertTrue(served > 0 && refused > 0, "served $served, refused $refused")
        assertTrue(failures.isEmpty(), "${failures.size} disagreement(s):\n" + failures.joinToString("\n"))
    }

    @Test
    fun aGuestIsNeverToldTheStops() {
        val w = world()
        val l = link(w, ShareEntity.READ)
        val body = given().`when`().get("/api/shares/$l").then().statusCode(200).extract().asString()
        assertFalse(body.contains("guestStops"), "the resolve carries no stop: $body")
    }

    // --- the oracle -----------------------------------------------------------------------------------------------------

    private enum class Lifetime(val apply: ShareEntity.() -> Unit) {
        NONE({}),
        SHORT({ expiresAt = Instant.now().plus(Duration.ofDays(2)) }),
        /** Made yesterday, ending in ten days: eleven days in all, beyond seven. */
        LONG({ createdAt = Instant.now().minus(Duration.ofDays(1)); expiresAt = Instant.now().plus(Duration.ofDays(10)) }),
        PAST({ createdAt = Instant.now().minus(Duration.ofDays(2)); expiresAt = Instant.now().minus(Duration.ofDays(1)) }),
    }

    private data class Rules(val password: Boolean = false, val maxDays: Int? = null)

    private data class Link(val role: String, val password: Boolean, val lifetime: Lifetime, val enabled: Boolean, val shareId: String)

    /** The contract's `ShareGuestStop` order, from its words: the link, its space, the switches root first (instance
     *  before tenant per entry), the requirements password first. */
    private fun oracle(l: Link, instanceOff: List<String>, tenantOff: List<String>, rules: Rules): List<String> = buildList {
        if (!l.enabled) add("revoked")
        if (l.lifetime == Lifetime.PAST) add("expired")
        val path = if (l.role == ShareEntity.READ_WRITE) listOf(PUBLIC_SHARES, PUBLIC_SHARES_CONTRIBUTE) else listOf(PUBLIC_SHARES)
        for (entry in path) {
            if (entry in instanceOff) add("instanceSwitch:$entry")
            if (entry in tenantOff) add("tenantSwitch:$entry")
        }
        if (rules.password && !l.password) add("linkPassword")
        if (rules.maxDays != null && l.lifetime in setOf(Lifetime.NONE, Lifetime.LONG)) add("linkMaxDays:${rules.maxDays}")
    }

    // --- helpers --------------------------------------------------------------------------------------------------------

    private val resolve = listOf(Path("resolveShare") { id -> given().`when`().get("/api/shares/$id") })

    private companion object {
        const val PW_SALT = "cHctc2FsdA=="
        const val PASSWORD_EDIT =
            """{"passwordRequired":true,"pwSalt":"$PW_SALT","pwVerifier":"cHctdmVyaWZpZXI=","sealedPassword":"c2VhbGVkLXB3"}"""
    }

    private data class World(val space: MemberScope, val folder: String)

    private fun world(): World {
        val space = seedMemberScope()
        return World(space, seedNode(space.scopeId, NodeEntity.FOLDER, wrappedContentKey = CONTENT_KEY))
    }

    /** A link of [role] on the world's folder, its row then changed by [edit]. */
    private fun link(w: World, role: String, edit: ShareEntity.() -> Unit = {}): String {
        val id = GuestTreeShare.seed(w.space.userId, w.space.scopeId, w.folder, role).shareId
        QuarkusTransaction.requiringNew().run { ShareEntity.findById(id)!!.edit() }
        return id
    }

    /** The share edit (`updateShare`) by the world's member, the link's owner. */
    private fun edit(w: World, shareId: String, body: String): Response =
        given().header("Authorization", bearer(w.space.userId)).contentType(ContentType.JSON).body(body)
            .`when`().patch("/api/shares/$shareId")

    private fun ShareEntity.password() {
        passwordRequired = true; pwSalt = "cHctc2FsdA=="; pwVerifier = "cHctdmVyaWZpZXI="
    }

    private fun stopsOnNode(w: World): Map<String, List<String>> = stops("/api/shares?nodeId=${w.folder}", w)

    private fun stopsOfMine(w: World): Map<String, List<String>> = stops("/api/shares", w)

    /** Each listed share's stops as `reason[:capabilityId|:maxDays]`, in the order served. */
    private fun stops(url: String, w: World): Map<String, List<String>> {
        val rows: List<Map<String, Any?>> = given().header("Authorization", bearer(w.space.userId))
            .`when`().get(url).then().statusCode(200).extract().jsonPath().getList("")
        return rows.associate { row ->
            @Suppress("UNCHECKED_CAST")
            val stops = row["guestStops"] as List<Map<String, Any?>>
            row["shareId"] as String to stops.map { s ->
                listOfNotNull(s["reason"], s["capabilityId"], s["maxDays"]).joinToString(":")
            }
        }
    }
}
