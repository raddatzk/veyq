package me.raddatz.veyq.mobile.memories

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.api.createClientPlugin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.map.MapServices
import me.raddatz.veyq.mobile.map.MapState
import me.raddatz.veyq.mobile.memories.people.PersonMemories
import me.raddatz.veyq.mobile.memories.place.MemoryFacts
import me.raddatz.veyq.mobile.navigation.Hidden
import me.raddatz.veyq.mobile.navigation.ShellModel
import me.raddatz.veyq.mobile.session.LiveRun
import me.raddatz.veyq.mobile.session.NodeTrash
import me.raddatz.veyq.mobile.session.Reach
import me.raddatz.veyq.mobile.session.SoftwareDeviceKeys
import me.raddatz.veyq.mobile.session.StaySignedIn
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.session.ThisDevice
import me.raddatz.veyq.mobile.session.VeyqAccount
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import me.raddatz.veyq.mobile.session.LiveHttp

/**
 * A person's profile against a running dev instance (ADR 0144 decisions 11–14; `VEYQ_LIVE`, skipped without one). The dev
 * principal makes three Memories with itself listed as a participant — one in its tenant's space and one in a group
 * (each with *I was there*), and one in its personal space with the participant written into its meta afterwards — and
 * opens its own profile: Memories' section lists the first two and never the personal one (the index walks the tenant's
 * and the groups' scopes only). **Offline, both ways in:** the network cut while the session runs (the tree read anew
 * from what was kept), the open profile keeps its rows and its map says it needs a connection; and the account opened
 * offline from its store, the same rows.
 *
 * Needs a group the dev principal is a member of; without one the case is skipped, said so. The three Memories are trashed
 * and purged, the store wiped and every device taken off the account however the case ends.
 */
class PersonProfileLiveTest {
    init { me.raddatz.veyq.mobile.TestApplications.install(me.raddatz.veyq.mobile.memories.MemoriesKinds) }

    private val liveRun = LiveRun()

    @AfterTest
    fun takeOffTheDevices() = liveRun.removeAll()

    @Volatile private var reachable = true

    /** Every request of a client this makes fails before it is sent while [reachable] is false: the network cut. */
    private val cut = createClientPlugin("CutNetwork") {
        onRequest { _, _ -> if (!reachable) throw java.io.IOException("the network is cut") }
    }

    private val client: (HttpClientConfig<*>.() -> Unit) -> HttpClient = { block -> LiveHttp.client { block(); install(cut) } }

    private fun shellOf(tenant: TenantSession) = ShellModel(tenant, Hidden({}, emptySet())) { System.currentTimeMillis() }

    private suspend fun section(shell: ShellModel, userId: String): PersonMemories.State =
        shell.personMemories(userId).also { it.load() }.current

    @Test
    fun aProfileListsTheTenantsAndTheGroupsMemoriesWithAPerson_neverThePersonalOne_andOfflineKeepsThem() = runBlocking {
        val origin = liveRun.origin
        org.junit.Assume.assumeTrue("VEYQ_LIVE is not set: a live case", origin != null)
        val p = BouncyCastlePrimitives
        val env = liveRun.env
        val root = Files.createTempDirectory("veyq-profile-live").toFile()
        val device = ThisDevice(liveRun.store(), SoftwareDeviceKeys(p), "live profile test", p, offlineRoot = root.absolutePath).also { it.offlineFiles = me.raddatz.veyq.mobile.offline.SizedVolume() }
        var account = VeyqAccount.signIn(p, origin!!, env.getValue("VEYQ_EMAIL"), env.getValue("VEYQ_PASSWORD"), env.getValue("VEYQ_SECRET_KEY"), device, client)
        assertEquals(VeyqAccount.TrustOutcome.REMEMBERED, account.trustThisDevice(null, StaySignedIn.LOGIN), "the dev instance does not let a device stay signed in")
        val tenant = account.open(account.defaultTenant!!)
        val shell = shellOf(tenant)
        shell.load(openLastUsed = false)
        val spaces = shell.state.first { it != null }!!.lists.spaces
        val tenantSpace = assertNotNull(spaces.firstOrNull { it.kind == "TENANT" }?.scopeId, "no tenant space")
        val personalSpace = assertNotNull(spaces.firstOrNull { it.kind == "PERSONAL" }?.scopeId, "no personal space")
        val groupSpace = spaces.firstOrNull { it.kind == "GROUP" }?.scopeId
        org.junit.Assume.assumeTrue("the dev principal is in no group: the group's Memory cannot be made", groupSpace != null)
        val me = account.userId
        val n = System.currentTimeMillis() % 100_000
        val made = mutableListOf<Pair<String, String>>()
        try {
            val inTenant = assertNotNull(shell.createMemory(tenantSpace, null, "Profile live tenant $n", wasThere = true).node).nodeId.also { made += tenantSpace to it }
            val inGroup = assertNotNull(shell.createMemory(groupSpace!!, null, "Profile live group $n", wasThere = true).node).nodeId.also { made += groupSpace to it }
            val inPersonal = assertNotNull(shell.createMemory(personalSpace, null, "Profile live personal $n").node).nodeId.also { made += personalSpace to it }
            // the personal scope offers no I was there; the participant is written into its meta all the same
            tenant.forgetTree()
            assertNotNull(tenant.node(personalSpace, inPersonal))
            assertEquals(MemoryFacts.Outcome.Written, Participants.set(tenant, personalSpace, inPersonal, listOf(me), emptyList()))
            tenant.forgetTree()
            assertEquals(listOf(me), MemoryFacts.of(tenant, personalSpace, inPersonal)?.participants, "the personal Memory names the participant")

            val ours = setOf(inTenant, inGroup, inPersonal)
            fun PersonMemories.State.ours() = rows.map { it.ref.nodeId }.filter { it in ours }.toSet()
            val profile = shell.profile(me)
            assertTrue(profile.name != null, "one's own profile names oneself")
            val online = section(shell, me)
            assertEquals(setOf(inTenant, inGroup), online.ours(), "the tenant's and the group's Memories, never the personal one")
            println("veyq-live: the profile lists ${online.rows.size} Memories (${online.ours().size} of this case's), partial=${online.partial}")

            // the network cut while the session runs: the tree read anew from what was kept, the rows kept, no map
            reachable = false
            tenant.forgetTree()
            val cut = section(shell, me)
            assertEquals(setOf(inTenant, inGroup), cut.ours(), "the open profile lost its rows when the network went")
            assertIs<Reach.Offline>(account.reach.value)
            assertEquals(MapState.Offline, MapServices.of(tenant).state.value, "the map does not say it needs a connection")

            // the account opened offline from its store: the same rows
            account.retire()
            val record = assertNotNull(device.trusted().firstOrNull(), "the device forgot its trust")
            account = VeyqAccount.resume(p, record, device, client)
            assertIs<Reach.Offline>(account.reach.value)
            val kept = account.open(account.defaultTenant!!)
            val offline = section(shellOf(kept), me)
            assertEquals(setOf(inTenant, inGroup), offline.ours(), "an account opened offline lists no profile rows")
            assertEquals(MapState.Offline, MapServices.of(kept).state.value)
        } finally {
            reachable = true
            runCatching { account.retire() }
            val cleaner = runCatching { VeyqAccount.resume(p, device.trusted().first(), device, client) }.getOrNull()
            val t = cleaner?.let { runCatching { it.open(it.defaultTenant!!) }.getOrNull() }
            if (t != null) for ((scope, node) in made) {
                runCatching { NodeTrash.delete(t, scope, node) }
                runCatching { NodeTrash.purge(t, scope, node) }
            }
            // a remembered account's sign-out forgets its trust and wipes its store
            runCatching { cleaner?.signOut() }
            root.deleteRecursively()
        }
    }
}
