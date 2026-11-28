package me.raddatz.veyq.mobile.depot

import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.utf8
import me.raddatz.veyq.mobile.session.OpenNode
import me.raddatz.veyq.mobile.sharing.GuestTree
import me.raddatz.veyq.mobile.session.testPrimitives
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Who uploaded a file, as its page names them ([Uploaders]; the web's `uploader.ts` and its `FileDetail.uploader.test.ts`,
 * inbox #1301): a member by the display rule, one who left as a former member, a guest by the name it gave itself — its
 * identity folder inside a drop box, else its own sealed name under the file's key or an ancestor's. Bites, each measured
 * red: [Uploaders.guestName] trying the file's key alone, not binding the seal to its share (another share's opens), the
 * folder of any identity naming the guest, [Uploaders.of] calling a member former where the members did not read.
 */
class UploadersTest {
    private val suite = V1(testPrimitives)
    private val scope = "11111111-1111-4111-8111-111111111111"
    private val share = "A".repeat(43)
    private val guest = "33333333-3333-4333-8333-333333333333"
    private val folderKey = suite.p.randomBytes(32)
    private val fileKey = suite.p.randomBytes(32)

    private fun node(id: String, parent: String?, key: ByteArray, identity: String? = null, name: String = id) =
        OpenNode(scope, id, parent, "FILE", name, key, shareIdentityId = identity)

    private val tree = listOf(node("docs", null, folderKey), node("report", "docs", fileKey))

    private fun sealed(key: ByteArray, name: String, shareId: String = share) =
        Uploaders.SealedName(b64(suite.sealField(GuestTree.identityName(scope, shareId, guest), key, utf8(name))), shareId)

    private fun named(tree: List<OpenNode>, s: Uploaders.SealedName?) = Uploaders.guestName(suite, tree, scope, "report", guest, s)

    @Test
    fun aFolderLinksGuestIsNamedUnderTheFolderAboveTheFile_aSingleFileLinksUnderTheFilesOwn() {
        assertEquals("Ben Gast", named(tree, sealed(folderKey, "Ben Gast")))
        assertEquals("Ben Gast", named(tree, sealed(fileKey, "Ben Gast")))
    }

    @Test
    fun aNameThatOpensNowhereLeavesTheGuestUnnamed() {
        assertNull(named(tree, sealed(suite.p.randomBytes(32), "Ben Gast")), "a key no node holds")
        assertNull(named(tree, sealed(folderKey, "Ben Gast").let { Uploaders.SealedName(it.sealedName, "B".repeat(43)) }), "the seal bound to another share")
        assertNull(named(tree, Uploaders.SealedName("%%%", share)), "no base64")
        assertNull(named(tree, null), "none served")
    }

    @Test
    fun aDropBoxsGuestIsNamedByItsOwnIdentityFolder_notAnothersNorItsSeal() {
        val box = listOf(node("box", null, folderKey), node("clara", "box", folderKey, identity = guest, name = "Clara"), node("report", "clara", fileKey))
        assertEquals("Clara", named(box, sealed(suite.p.randomBytes(32), "Clara Sealed")))
        val other = listOf(node("box", null, folderKey), node("eve", "box", folderKey, identity = "55555555-5555-4555-8555-555555555555", name = "Eve"), node("report", "eve", fileKey))
        assertNull(named(other, sealed(suite.p.randomBytes(32), "Clara Sealed")), "another guest's folder names nobody else")
    }

    @Test
    fun aMemberByTheirName_oneWhoLeftAsFormer_unnamedWhereTheMembersDidNotRead() {
        val members = listOf(Uploaders.Member("u-ana", "Anna Meyer"))
        assertEquals("Anna Meyer", Uploaders.of("user:u-ana", members) { null }?.name)
        assertEquals(Uploaders.Kind.FORMER, Uploaders.of("user:u-gone", members) { null }?.kind)
        assertNull(Uploaders.of("user:u-ana", null) { null }, "not called former where the directory did not read")
        val g = Uploaders.of("share:$guest", null) { if (it == guest) "Ben" else null }
        assertEquals(Uploaders.Kind.GUEST to "Ben", g?.kind to g?.name)
        assertNull(Uploaders.of(null, members) { null })
    }
}
