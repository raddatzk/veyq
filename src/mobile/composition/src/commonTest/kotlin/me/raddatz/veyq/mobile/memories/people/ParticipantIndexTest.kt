package me.raddatz.veyq.mobile.memories.people

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.raddatz.veyq.mobile.map.LatLng
import me.raddatz.veyq.mobile.map.MapServices
import me.raddatz.veyq.mobile.map.MapState
import me.raddatz.veyq.mobile.navigation.SpaceRef
import me.raddatz.veyq.mobile.offline.PlayedTenant
import me.raddatz.veyq.mobile.offline.removeAll
import me.raddatz.veyq.mobile.session.OpenNode
import me.raddatz.veyq.mobile.session.Reach
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.session.VeyqAccount
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A person's index (ADR 0144 decision 12; the web's `participantIndex.test.ts` ported): built on the device over the
 * trees the session read — the tenant's and the groups' scopes, never the personal one; MEMORY nodes only; at most 500
 * Memories a scope, past it partial, as is a scope that did not read —, held in memory with the tree: the same index
 * until the tree is read anew, dropped when the tenant closes; offline built from what the device kept.
 */
class ParticipantIndexTest {
    init { me.raddatz.veyq.mobile.composition.AppComposition.install() }

    private val tenant = SpaceRef("t", "TENANT", "Family")
    private val group = SpaceRef("g", "GROUP", "Group")
    private val personal = SpaceRef("p", "PERSONAL", "Personal")

    private val metas = HashMap<String, JsonObject>()

    private fun node(space: SpaceRef, id: String, kind: String = "MEMORY", meta: String? = null): OpenNode {
        meta?.let { metas[id] = Json.parseToJsonElement(it) as JsonObject }
        return OpenNode(space.scopeId, id, null, kind, "Memory $id", ByteArray(32))
    }

    private fun with(vararg ids: String, extra: String = "") =
        """{"v":1,"participants":{"userIds":[${ids.joinToString(",") { "\"$it\"" }}]}$extra}"""

    private fun build(vararg scopes: ParticipantIndex.Scope) = ParticipantIndex.build(scopes.toList()) { metas[it.id] }

    @Test
    fun indexesTheMemoriesThatNameParticipantsAcrossTheTenantAndAGroup_neverThePersonalScope() {
        val index = build(
            ParticipantIndex.Scope(tenant, listOf(node(tenant, "a", meta = with("ada", "ben", extra = ""","date":{"mode":"single","from":"2026-05-01","to":"2026-05-01"}""")), node(tenant, "none", meta = """{"v":1}"""))),
            ParticipantIndex.Scope(group, listOf(node(group, "b", meta = with("ada", extra = ""","location":{"name":"Lisboa","nameSource":"manual","derived":{"lat":38.7,"lng":-9.1},"derivedMode":"fixed"}""")))),
            ParticipantIndex.Scope(personal, listOf(node(personal, "c", meta = with("ada")))),
        )
        assertEquals(setOf("a", "b"), index.of("ada").map { it.node.nodeId }.toSet(), "the personal scope's Memory is never indexed")
        assertEquals(listOf("a"), index.of("ben").map { it.node.nodeId })
        assertEquals(emptyList(), index.of("nobody"))
        assertFalse(index.partial)
        val a = index.of("ben").single()
        assertEquals("2026-05-01", a.date?.from)
        assertEquals(tenant, a.space)
        val b = index.of("ada").first { it.node.nodeId == "b" }
        assertEquals("Lisboa", b.placeName)
        assertEquals(LatLng(38.7, -9.1), b.pin?.at, "a located Memory is a pin of the map")
        assertEquals(null, a.pin, "one without a position is none")
        assertEquals(group, b.space)
    }

    @Test
    fun aChapterIsNeverIndexed_andAMemoryWhoseMetaDoesNotOpenIsLeftOut() {
        val index = build(
            ParticipantIndex.Scope(tenant, listOf(
                node(tenant, "ch", kind = "CHAPTER", meta = with("ada")),
                node(tenant, "f", kind = "FOLDER", meta = with("ada")),
                node(tenant, "sealed-away"),
                node(tenant, "m", meta = with("ada")),
            )),
        )
        assertEquals(listOf("m"), index.of("ada").map { it.node.nodeId }, "a chapter names no participants of its own")
        assertFalse(index.partial, "a meta that does not open is the web's skip, not a partial list")
    }

    @Test
    fun stopsAtThePerScopeCapAndSaysTheResultIsPartial() {
        fun many(n: Int, space: SpaceRef) = (1..n).map { node(space, "${space.scopeId}$it", meta = with("ada")) }
        val atCap = build(ParticipantIndex.Scope(tenant, many(ParticipantIndex.MAX_MEMORIES_PER_SCOPE, tenant)))
        assertEquals(500, atCap.of("ada").size)
        assertFalse(atCap.partial, "500 are all read")
        val over = build(ParticipantIndex.Scope(tenant, many(501, tenant)), ParticipantIndex.Scope(group, many(3, group)))
        assertEquals(503, over.of("ada").size, "the 501st is not read; the next scope still is")
        assertTrue(over.partial, "a scope over the cap makes the list partial")
        // chapters do not count toward the cap: only Memories are opened
        val chapters = (1..600).map { node(tenant, "c$it", kind = "CHAPTER") } + node(tenant, "last", meta = with("ada"))
        assertFalse(build(ParticipantIndex.Scope(tenant, chapters)).partial)
    }

    @Test
    fun aScopeThatDidNotReadMakesTheResultPartial_thePersonalOneNever() {
        val index = build(ParticipantIndex.Scope(tenant, listOf(node(tenant, "a", meta = with("ada")))), ParticipantIndex.Scope(group, null))
        assertEquals(listOf("a"), index.of("ada").map { it.node.nodeId }, "the readable scope is still indexed")
        assertTrue(index.partial)
        assertFalse(build(ParticipantIndex.Scope(tenant, emptyList()), ParticipantIndex.Scope(personal, null)).partial, "the personal scope is not walked at all")
    }

    // ---- over a tenant the test plays, real seals

    private val played = mutableListOf<PlayedTenant>()

    @AfterTest
    fun cleanUp() = played.forEach { removeAll(it.root) }

    private class Setup(val t: PlayedTenant, val tenantMemory: PlayedTenant.Node, val groupMemory: PlayedTenant.Node, val personalMemory: PlayedTenant.Node, val group: PlayedTenant.Scope)

    private suspend fun setup(): Setup {
        val t = PlayedTenant().also { played += it }
        val ts = t.scope("tenant", kind = "TENANT")
        val gs = t.scope("group", kind = "GROUP")
        val ps = t.scope("personal")
        val a = t.node(ts, "a", name = "Ostsee").also { t.participants(it, "ada") }
        val b = t.node(gs, "b", name = "Harz").also { t.participants(it, "ada", "ben") }
        val c = t.node(ps, "c", name = "Mine").also { t.participants(it, "ada") }
        t.node(ts, "ch", kind = "CHAPTER", parent = a).also { t.participants(it, "ada") }
        t.remember()
        return Setup(t, a, b, c, gs)
    }

    private suspend fun tenantOf(a: VeyqAccount): TenantSession = a.open(a.tenants.first())

    private fun ParticipantIndex.Index.names(t: PlayedTenant, userId: String) = of(userId).map { t.nameOf(it.node.nodeId) }.toSet()

    @Test
    fun overASessionsTreesTheTenantAndTheGroupAreIndexed_thePersonalScopeAndTheChapterNot() = runTest {
        val s = setup()
        val account = s.t.resume()
        val index = ParticipantIndex.of(tenantOf(account))
        assertEquals(setOf("a", "b"), index.names(s.t, "ada"))
        assertEquals(setOf("b"), index.names(s.t, "ben"))
        assertFalse(index.partial)
        account.retire()
    }

    @Test
    fun theIndexIsHeldWithTheTree_rebuiltWhenItIsReadAnew_droppedWhenTheTenantCloses() = runTest {
        val s = setup()
        val account = s.t.resume()
        val tenant = tenantOf(account)
        val first = ParticipantIndex.of(tenant)
        assertTrue(ParticipantIndex.held(tenant))
        assertSame(first, ParticipantIndex.of(tenant), "the same tree read: the same index, nothing opened again")

        // a peer removes ada from the group's Memory and lists her on another: held until the tree is read anew
        s.t.participants(s.groupMemory, "ben")
        s.t.participants(s.t.node(s.group, "d", name = "Rügen"), "ada")
        assertSame(first, ParticipantIndex.of(tenant))
        tenant.forgetTree()
        assertFalse(ParticipantIndex.held(tenant), "forgetting the tree drops what was derived from it")
        val second = ParticipantIndex.of(tenant)
        assertNotSame(first, second)
        assertEquals(setOf("a", "d"), second.names(s.t, "ada"), "a Memory whose participants dropped her is gone, the new one listed")

        // the tenant closes — a switch, a lock, a sign-out: the index goes with it
        tenant.close()
        assertFalse(ParticipantIndex.held(tenant), "the index outlived the tenant's close")
        account.retire()
    }

    @Test
    fun offlineTheIndexIsBuiltFromTheKeptTrees_cutMidSessionAndOpenedOffline() = runTest {
        val s = setup()
        val account = s.t.resume()
        val tenant = tenantOf(account)
        val online = ParticipantIndex.of(tenant)

        // the server goes while the session runs: the profile open keeps its rows, its map needs a connection
        s.t.reachable = false
        tenant.forgetTree()
        val cut = ParticipantIndex.of(tenant)
        assertEquals(online.names(s.t, "ada"), cut.names(s.t, "ada"))
        assertFalse(cut.partial, "every scope was kept")
        assertIs<Reach.Offline>(account.reach.value)
        assertEquals(MapState.Offline, MapServices.of(tenant).state.value, "the map says it needs a connection")
        account.retire()

        // the account opened offline: the same rows from what the device kept
        val offline = s.t.resume()
        assertIs<Reach.Offline>(offline.reach.value)
        val kept = tenantOf(offline)
        val index = ParticipantIndex.of(kept)
        assertEquals(setOf("a", "b"), index.names(s.t, "ada"))
        assertEquals(MapState.Offline, MapServices.of(kept).state.value)
        offline.retire()
    }

    @Test
    fun aScopeNothingOfWhichWasKeptMakesTheOfflineIndexPartial() = runTest {
        val s = setup()
        val first = s.t.resume()
        ParticipantIndex.of(tenantOf(first))
        first.retire()
        // a group joined since: listed when the tenant opens again, its tree not read before the server goes
        val late = s.t.scope("late group", kind = "GROUP")
        s.t.participants(s.t.node(late, "e"), "ada")
        val account = s.t.resume()
        val tenant = tenantOf(account)
        s.t.reachable = false
        val index = ParticipantIndex.of(tenant)
        assertEquals(setOf(late.id), tenant.unreadTrees, "the late group did not read")
        assertEquals(setOf("a", "b"), index.names(s.t, "ada"))
        assertTrue(index.partial, "a scope that did not read says the list may be incomplete")
        account.retire()
    }

}
