package me.raddatz.veyq.mobile.offline

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import me.raddatz.veyq.mobile.api.depot.models.FileListPage
import me.raddatz.veyq.mobile.api.memories.models.MediaListPage
import me.raddatz.veyq.mobile.api.models.AccountKeyMaterial
import me.raddatz.veyq.mobile.api.models.ApplicationBlobPage
import me.raddatz.veyq.mobile.api.models.MyTenant
import me.raddatz.veyq.mobile.api.models.NodeSummary
import me.raddatz.veyq.mobile.api.models.ScopeSummary

/**
 * The reads whose answers the offline store keeps (ADR 0140 decision 5) — the one place they are named. Each is a
 * listing the server answers with ciphertext and opaque metadata, kept exactly as answered (every page of it, [Pages]),
 * and read back by the same code that reads the server's: [kind] names it in an [EntryKey], [model] is the generated
 * client's model of one page, from which `KeptReadsTest` derives every field the store may hold and refuses any that
 * names or describes a token or an address. A blob's frames are kept beside them, under [FRAMES].
 *
 * `listMyTenants` is kept too, though decision 5's list does not name it: an account opened offline needs the tenants it
 * belongs to (their names and domains) before it can open one, and the answer carries no token or address.
 */
enum class KeptRead(val kind: String, internal val model: KSerializer<*>) {
    /** `getAccountKeyMaterial`: this account's wraps and sealed identity (decision 4) — the identity an offline unlock opens. */
    KEY_MATERIAL("getAccountKeyMaterial", AccountKeyMaterial.serializer()),
    MY_TENANTS("listMyTenants", ListSerializer(MyTenant.serializer())),
    /** Per tenant: its scopes with their grants (decision 4's scope grants). */
    MY_SCOPES("listMyScopes", ListSerializer(ScopeSummary.serializer())),
    /** Per scope: its tree, `ETag` its revision (ADR 0081). */
    NODES("listNodes", ListSerializer(NodeSummary.serializer())),
    /** Per Memory or chapter: its media, `mediaRevision` its revision. */
    NODE_MEDIA("listNodeMedia", MediaListPage.serializer()),
    /** Per Depot folder: its files' blobs. */
    FILES("listFiles", FileListPage.serializer()),
    /** Per node: its application blobs, `ETag` its revision. */
    APPLICATION_BLOBS("listApplicationBlobs", ApplicationBlobPage.serializer()),
    ;

    companion object {
        /** A blob's frames, as the object store served them. */
        const val FRAMES = "frames"

        /**
         * The account's pins on this device (ADR 0140 decision 9: each pin's node, its state, a pin dropped with its
         * notice), sealed as every entry is — no server's answer, the store's own record.
         */
        const val PINS = "pins"

        /** The reads an account opens with: never evicted (an offline unlock needs them), dropped only by a wipe. */
        internal val OPENING = setOf(KEY_MATERIAL.kind, MY_TENANTS.kind, MY_SCOPES.kind)

        /** What neither the limit, "Clear cache" nor the sweep drops: the opening answers and the pins' record. */
        internal val PROTECTED = OPENING + PINS

        /** The listings below a scope: what a pin reaches through. */
        internal val LISTINGS = setOf(NODES.kind, NODE_MEDIA.kind, FILES.kind, APPLICATION_BLOBS.kind)
    }
}

/** A kept answer's pages, each exactly as the server sent it: a count, then each page length-prefixed. */
internal object Pages {
    fun encode(pages: List<ByteArray>): ByteArray = Writer().apply {
        int(pages.size)
        for (p in pages) { int(p.size); raw(p) }
    }.bytes()

    fun decode(bytes: ByteArray): List<ByteArray> {
        val r = Reader(bytes)
        val n = r.int()
        require(n >= 0) { "page count" }
        val out = ArrayList<ByteArray>(n)
        repeat(n) { out += r.raw(r.int()) }
        require(r.done) { "pages trailing bytes" }
        return out
    }
}
