package me.raddatz.veyq.mobile.offline

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import me.raddatz.veyq.mobile.api.depot.models.FileListPage
import me.raddatz.veyq.mobile.api.memories.models.MediaListPage
import me.raddatz.veyq.mobile.api.models.AccountKeyMaterial
import me.raddatz.veyq.mobile.api.models.ApplicationBlobPage
import me.raddatz.veyq.mobile.api.models.MyTenant
import me.raddatz.veyq.mobile.api.models.NodeSummary
import me.raddatz.veyq.mobile.api.models.ScopeMember
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
    /**
     * Per scope: its members, the directory that names a Memory's participants and a medium's uploader (ADR 0144 decision 7): the
     * usernames, sealed real names and avatar revisions as the server answered, each scope's own list (a group's members are
     * not the tenant's). No revision; replaced at each online read.
     */
    MEMBERS("listScopeMembers", ListSerializer(ScopeMember.serializer())),
    /**
     * Per Memory or chapter: every member's sealed item-mark set (ADR 0149 decision 4), so a kept Memory shows its badges
     * offline. No member id: whose a set is, is its self-tag. No revision; replaced at each online read.
     */
    ITEM_FAVORITES("getItemFavorites", ListSerializer(me.raddatz.veyq.mobile.api.memories.models.ItemFavoritesEntry.serializer())),
    /**
     * Per Memory or chapter: its buckets with their sealed names (ADR 0149 decision 6), so a guest stays named offline. No
     * revision; replaced at each online read.
     */
    NODE_BUCKETS("listNodeBuckets", me.raddatz.veyq.mobile.api.memories.models.BucketListResponse.serializer()),
    /**
     * Per Pot: its whole ledger (ADR 0153 decision 6), every page of a read without `since`, `ledgerRevision` its revision.
     * Kept by each whole read — the Pot's open and a pin's sync —, replaced and never merged; the live poll's deltas are
     * not kept, so an entry a co-member deleted or an erasure dropped is gone from the next whole answer.
     */
    POT_REVISIONS("listPotRevisions", me.raddatz.veyq.mobile.api.expenses.models.PotRevisionPage.serializer()),
    /**
     * Per scope: every live medium of the space (ADR 0153 decision 7), what the search opens (`MemoriesSearch`), `ETag` its
     * revision. The automatic tier only — never pinned, never among [PROTECTED] —, replaced and never merged, kept while
     * its scope is the account's (the sweep, from [MY_SCOPES]).
     */
    SCOPE_MEDIA("listScopeMedia", me.raddatz.veyq.mobile.api.memories.models.ScopeMediaPage.serializer()),
    ;

    companion object {
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

        /** The listings below a scope: what a pin reaches through. */
        internal val LISTINGS = setOf(NODES.kind, NODE_MEDIA.kind, FILES.kind, APPLICATION_BLOBS.kind, POT_REVISIONS.kind)
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
