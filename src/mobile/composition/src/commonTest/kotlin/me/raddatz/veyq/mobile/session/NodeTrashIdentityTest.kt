package me.raddatz.veyq.mobile.session

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.offline.PlayedTenant
import me.raddatz.veyq.mobile.offline.removeAll
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * A guest's identity folder in a drop box, deleted by a member, is in the space's trash and comes back from it (ADR 0080
 * decision 11: "a member's restore brings the folder back"; inbox #904) — as the web's `listTrash` and `restoreOnce` do it:
 * its name opens through the drop box's drop key and the guest's `K_up` (`Tree.childEntryKey`), and the restore's slug
 * token is derived under that same `K_up`, the key its entry lives under (`TrashedNodeSummary.shareIdentityId`). A guest
 * entry that does not open is left out, never failing the trash; a member's entry beside it is listed (the control).
 */
class NodeTrashIdentityTest {
    init { me.raddatz.veyq.mobile.composition.AppComposition.install() }

    private val played = mutableListOf<PlayedTenant>()

    @AfterTest
    fun cleanUp() = played.forEach { removeAll(it.root) }

    private fun tokenIn(body: String): String = Regex("\"slugToken\"\\s*:\\s*\"([^\"]+)\"").find(body)!!.groupValues[1]

    @Test
    fun aTrashedGuestFolderIsListedByItsGuestsNameAndRestoredUnderItsOwnKey() = runTest {
        val t = PlayedTenant().also { played += it }
        val s = t.scope("s1")
        val box = t.dropBox(s, "box", name = "Einsendungen")
        val guest = t.identityFolder(box, "guest", "Adelgunde")
        val mine = t.node(s, "mine", kind = "FOLDER", name = "Mine")
        t.remember()
        val account = t.resume()
        val tenant = account.open(account.tenants.first())
        assertEquals(setOf("Einsendungen", "Adelgunde", "Mine"), tenant.trees().getValue(s.id).map { it.name }.toSet())

        t.trashNode(guest)
        t.trashNode(mine)
        tenant.forgetTree()
        val trash = assertNotNull(NodeTrash.list(tenant, s.id))
        assertEquals(setOf("Adelgunde", "Mine"), trash.map { it.name }.toSet(), "the guest's folder is in the member's trash")

        val listed = trash.first { it.id == guest.id }
        assertEquals(NodeTrash.Restored.RESTORED, NodeTrash.restoreFromTrash(tenant, listed, "adelgunde"))
        val kUp = guest.identity!!.second
        assertEquals(b64(account.suite.slugToken(kUp, box.id, "adelgunde")), tokenIn(t.restores.single()), "the token under the guest's K_up")
    }

    @Test
    fun theUndoOfAGuestFoldersDeleteDerivesItsTokenUnderTheGuestsKey() = runTest {
        val t = PlayedTenant().also { played += it }
        val s = t.scope("s1")
        val box = t.dropBox(s, "box")
        val guest = t.identityFolder(box, "guest", "Adelgunde")
        t.remember()
        val account = t.resume()
        val tenant = account.open(account.tenants.first())
        val held = tenant.trees().getValue(s.id).first { it.id == guest.id }
        t.trashNode(guest)
        tenant.forgetTree()
        assertEquals(NodeTrash.Restored.RESTORED, NodeTrash.restore(tenant, held, "adelgunde"))
        assertEquals(b64(account.suite.slugToken(guest.identity!!.second, box.id, "adelgunde")), tokenIn(t.restores.single()))
    }

    @Test
    fun aGuestFolderOfADropBoxMadeSinceTheTreeWasReadIsListedToo() = runTest {
        val t = PlayedTenant().also { played += it }
        val s = t.scope("s1")
        t.remember()
        val account = t.resume()
        val tenant = account.open(account.tenants.first())
        tenant.trees() // read before the drop box exists (another device made it)
        val box = t.dropBox(s, "box")
        val guest = t.identityFolder(box, "guest", "Adelgunde")
        t.trashNode(guest)
        assertEquals(listOf("Adelgunde"), assertNotNull(NodeTrash.list(tenant, s.id)).map { it.name })
    }

    @Test
    fun aGuestEntryThatDoesNotOpenIsLeftOutAndTheRestStaysListed() = runTest {
        val t = PlayedTenant().also { played += it }
        val s = t.scope("s1")
        val box = t.dropBox(s, "box")
        val guest = t.identityFolder(box, "guest", "Adelgunde")
        val mine = t.node(s, "mine", kind = "FOLDER", name = "Mine")
        t.remember()
        val account = t.resume()
        val tenant = account.open(account.tenants.first())
        t.trashNode(guest)
        t.trashNode(mine)
        // the guest's K_up wrapped to another drop key than the box's: it does not open on the member's device
        guest.wrapSeed = t.p.randomBytes(32)
        tenant.forgetTree()
        val trash = assertNotNull(NodeTrash.list(tenant, s.id))
        assertEquals(listOf("Mine"), trash.map { it.name })
    }
}
