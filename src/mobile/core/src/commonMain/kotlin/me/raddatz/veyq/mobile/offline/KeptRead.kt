package me.raddatz.veyq.mobile.offline

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import me.raddatz.veyq.mobile.api.models.AccountKeyMaterial
import me.raddatz.veyq.mobile.api.models.ApplicationBlobPage
import me.raddatz.veyq.mobile.api.models.MyTenant
import me.raddatz.veyq.mobile.api.models.NodeSummary
import me.raddatz.veyq.mobile.api.models.ScopeMember
import me.raddatz.veyq.mobile.api.models.ScopeSummary

/**
 * A read whose answers the offline store keeps (ADR 0140 decision 5): core's are named here ([platform]), each
 * application's in its own part ([ApplicationKept.reads]: Memories' media, marks, buckets and a space's media, Depot's
 * files, Expenses' ledger), and `KeptReadsTest` reads them all. Each is a
 * listing the server answers with ciphertext and opaque metadata, kept exactly as answered (every page of it, [Pages]),
 * and read back by the same code that reads the server's: [kind] names it in an [EntryKey], [model] is the generated
 * client's model of one page, from which `KeptReadsTest` derives every field the store may hold and refuses any that
 * names or describes a token or an address. A blob's frames are kept beside them, under [FRAMES].
 *
 * `listMyTenants` is kept too, though decision 5's list does not name it: an account opened offline needs the tenants it
 * belongs to (their names and domains) before it can open one, and the answer carries no token or address.
 */
class KeptRead(val kind: String, internal val model: KSerializer<*>) {
    companion object {
        /** `getAccountKeyMaterial`: this account's wraps and sealed identity (decision 4) — the identity an offline unlock opens. */
        val KEY_MATERIAL = KeptRead("getAccountKeyMaterial", AccountKeyMaterial.serializer())
        val MY_TENANTS = KeptRead("listMyTenants", ListSerializer(MyTenant.serializer()))
        /** Per tenant: its scopes with their grants (decision 4's scope grants). */
        val MY_SCOPES = KeptRead("listMyScopes", ListSerializer(ScopeSummary.serializer()))
        /** Per scope: its tree, `ETag` its revision (ADR 0081). */
        val NODES = KeptRead("listNodes", ListSerializer(NodeSummary.serializer()))
        /** Per node: its application blobs, `ETag` its revision. */
        val APPLICATION_BLOBS = KeptRead("listApplicationBlobs", ApplicationBlobPage.serializer())
        /**
         * Per scope: its members, the directory that names a Memory's participants and a medium's uploader (ADR 0144 decision 7): the
         * usernames, sealed real names and avatar revisions as the server answered, each scope's own list (a group's members are
         * not the tenant's). No revision; replaced at each online read.
         */
        val MEMBERS = KeptRead("listScopeMembers", ListSerializer(ScopeMember.serializer()))

        /** Core's kept reads (each application's are its own, `ApplicationKept.reads`). */
        val platform: List<KeptRead> = listOf(KEY_MATERIAL, MY_TENANTS, MY_SCOPES, NODES, APPLICATION_BLOBS, MEMBERS)

        /** A blob's frames, as the object store served them. */
        const val FRAMES = "frames"

        /**
         * A map tile as its tile host served it (ADR 0143 D4 as amended for inbox #939), per tenant, named by the served
         * template and its `z/x/y`: the automatic tier only — never pinned, never reached by a listing, kept while its
         * tenant is (the sweep), dropped where the consent no longer holds for its host.
         */
        const val TILE = "tile"

        /**
         * The account's pins on this device (ADR 0140 decision 9: each pin's node, its state, a pin dropped with its
         * notice), sealed as every entry is — no server's answer, the store's own record.
         */
        const val PINS = "pins"

        /**
         * A write the person made offline, waiting to be sent (ADR 0153 decisions 1 and 2, [Outbox]): its intent, sealed as
         * every entry is — no server's answer, the person's own input —, and the order of them all (the entry of id `""`).
         */
        const val OUTBOX = "outbox"

        /** A queued blob's frames, each sealed under its own fresh `K_blob` at once (ADR 0153 decision 1), then under `K_off`. */
        const val OUTBOX_FRAMES = "outbox-frames"

        /** The outbox's kinds: never evicted, never cleared, never swept — only sent, discarded or wiped. */
        internal val QUEUED = setOf(OUTBOX, OUTBOX_FRAMES)

        /** The reads an account opens with: never evicted (an offline unlock needs them), dropped only by a wipe. */
        internal val OPENING = setOf(KEY_MATERIAL.kind, MY_TENANTS.kind, MY_SCOPES.kind)

        /** What neither the limit, "Clear cache" nor the sweep drops: the opening answers, the pins' record and the outbox. */
        internal val PROTECTED = OPENING + PINS + QUEUED

        /** The listings below a scope: what a pin reaches through — core's and each application's ([ApplicationKept.listings]). */
        internal val LISTINGS: Set<String>
            get() = setOf(NODES.kind, APPLICATION_BLOBS.kind) + me.raddatz.veyq.mobile.AppKinds.kept.flatMap { it.listings }.map { it.kind }
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
