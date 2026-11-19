package me.raddatz.veyq.mobile.sharing

import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.flow.StateFlow
import me.raddatz.veyq.mobile.blob.UploadQueue
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.memories.MediaUpload
import me.raddatz.veyq.mobile.navigation.NodeAt

/**
 * What an open link lets its guest do, handed to its kind's door (ADR 0150 decision 5; the web's `guestAccess.ts`): the
 * link's gates (its password token, its write token), the opened head and tree, its blobs, and the guest's one session
 * on this device. Each application's door reads and writes through it, and through nothing of an account: a guest has
 * none (signed in or not, the link is opened as its guest). Lives in the open link's model ([GuestView]).
 */
class GuestAccess internal constructor(
    internal val gates: ShareGates,
    internal val head: ShareHead,
    /** The subtree a READ or READ_WRITE link serves; null for a WRITE_ONLY link, which is served none. */
    val tree: GuestTree?,
    /** The guest view's upload queue (a guest's uploads); null where the host gave none. */
    val queue: UploadQueue?,
    /** The platform's tiers of a picked photo or video; null where the host gave none (a guest then uploads none). */
    val deriver: MediaUpload.Deriver?,
    /** The device's clock in epoch ms. */
    val now: () -> Long,
    /** Whether the platform hands a copy on through a share sheet (a file's "Send a copy"). */
    val sharesCopies: Boolean = false,
    /** The player a guest's Memory plays its music with (the account's one, or the app's while signed out); null: none. */
    val music: me.raddatz.veyq.mobile.memories.GalleryModel.Music? = null,
) {
    val role: String get() = head.role
    val shareId: String get() = head.shareId
    val scopeId: String get() = head.scopeId
    val origin: String get() = head.origin

    /** A READ_WRITE or WRITE_ONLY link: the guest writes (once it named itself). */
    val writes: Boolean get() = role != READ

    internal val client: GuestClient get() = gates.guestClient
    internal val suite: V1 get() = client.suite
    internal val pwToken: String? get() = gates.passwordToken
    val blobs: GuestBlobs = GuestBlobs(gates)

    /** The guest's session of this link on this device (null: it has not named itself). */
    val session: StateFlow<GuestSession?> get() = gates.guest

    /** Name the guest of a READ_WRITE link (under the root's key); a WRITE_ONLY kind names its uploader itself. */
    suspend fun nameGuest(displayName: String): GuestSession = gates.nameGuest(displayName)

    /** *Not you?* */
    fun forgetGuest() = gates.forgetGuest()

    internal fun saveSession(session: GuestSession) = gates.saveSession(session)
    internal fun writeToken(): String = gates.writeToken()

    /**
     * How many days the share's instance keeps a write's record of this guest (its address, port, time and `Origin`, ADR
     * 0093 § 10): what the notice a write link's guest is shown before it names itself says (GDPR Art. 13); null where none.
     */
    val guestWriteRecordDays: Int? get() = head.share.guestWriteRecordDays

    /** The epoch the root's key was minted at (a write under it, or under a key sealed to its drop key, states it). */
    val rootEpoch: Int get() = head.rootEpoch

    /** The scope epoch a guest mints a new key under (`PublicShare.rootScopeEpoch`, ADR 0114 decision 3). */
    val rootScopeEpoch: Int get() = head.share.rootScopeEpoch ?: 0

    /** The link's root as a guest page names it. */
    val root: NodeAt get() = NodeAt(head.scopeId, head.share.nodeId ?: tree?.root?.id ?: "", head.rootKind ?: "", head.name)

    /** A WRITE_ONLY link's drop public key, opened under `K_share` (`drop-public-key`); null where it does not open. */
    internal fun dropPublicKey(): ByteArray? = runCatching {
        suite.deserializeHybridPublic(suite.open(Context("drop-public-key"), head.kShare, unb64(head.share.sealedDropPublicKey!!)))
    }.getOrNull()

    /**
     * Name a WRITE_ONLY link's uploader (the web's `nameDropGuest` and `createContributorIdentity`, ADR 0031 point 7): a
     * fresh identity key `K_up` wrapped to the root's drop public key (bound to scope, root and identity), the name sealed
     * under `K_up`, an anonymous identity created with both and the write token, saved as the link's one session with its
     * `K_up` — through the credential store, never a plain one. Throws where the link's drop key does not open or the
     * server refuses.
     */
    suspend fun nameUploader(displayName: String): GuestSession {
        val dropPublic = dropPublicKey() ?: throw ShareRefused.Unavailable(false)
        val rootId = head.share.nodeId ?: throw ShareRefused.Unavailable(false)
        val identityId = uuid()
        val kUp = suite.p.randomBytes(32)
        try {
            val wrapped = suite.sealTo(
                Context("upload-identity-key", "scopeId" to scopeId, "nodeId" to rootId, "identityId" to identityId), dropPublic, kUp,
            )
            val sealedName = suite.sealField(GuestTree.identityName(scopeId, shareId, identityId), kUp, me.raddatz.veyq.mobile.crypto.utf8(displayName))
            val r = me.raddatz.veyq.mobile.api.apis.SharingApi(client.origin, client.http).createShareIdentity(
                shareId,
                me.raddatz.veyq.mobile.api.models.CreateShareIdentityRequest(
                    nodeEpoch = rootEpoch, identityId = identityId, sealedDisplayName = me.raddatz.veyq.mobile.crypto.b64(sealedName),
                    wrappedIdentityKey = me.raddatz.veyq.mobile.crypto.b64(wrapped),
                ),
                pwToken, writeToken(),
            )
            check(r.success) { "share_identity_refused ${r.status}" }
            val session = GuestSession(identityId, r.body().contributorToken, displayName, me.raddatz.veyq.mobile.crypto.b64(kUp))
            saveSession(session)
            return session
        } finally {
            kUp.fill(0)
        }
    }

    /** The uploader's own `K_up` of [session] (a fresh copy, the caller's to zero); null where the session holds none. */
    internal fun uploaderKey(session: GuestSession): ByteArray? = session.uploaderKey?.let { runCatching { unb64(it) }.getOrNull() }

    /** A fresh client id (a UUID v4). */
    internal fun uuid(): String = client.uuid()

    /** Whether [nodeId] is a guest's identity folder or lies in one: nothing is placed there but by its identity (ADR 0080 decision 11). */
    fun inIdentityFolder(nodeId: String): Boolean {
        val t = tree ?: return false
        var at = t.node(nodeId)
        val seen = HashSet<String>()
        while (at != null && seen.add(at.id)) {
            if (at.shareIdentityId != null) return true
            at = at.parentId?.let { t.node(it) }
        }
        return false
    }

    /**
     * A READ_WRITE guest's Replace of [file]'s content with [source] (the web's share page: `guestFileUploadJob` with a
     * `replace` target), queued on this link's own [queue] — never a tenant's: signed out or in, the link writes as its
     * guest (#1035). The page reads the file anew once it settled. False — nothing queued, [source] the caller's to close —
     * on a READ link, before the guest named itself, without a queue, or where Replace was not chosen on [file]
     * (`FileModel.chooseReplace`, the choice then left standing).
     */
    fun replace(file: me.raddatz.veyq.mobile.depot.FileModel, source: me.raddatz.veyq.mobile.blob.UploadSource): Boolean {
        val q = queue ?: return false
        if (role != READ_WRITE || session.value == null) return false
        val job = file.replace(source) ?: return false
        q.enqueue(listOf(job), source.name, file.node.nodeId, onSettled = { file.reload() }, onFinished = null)
        return true
    }

    /** What a guest's create came to. */
    sealed class Created {
        /** Made: its id and its fresh key (the caller's to zero), at its own epoch 0 under the parent's scope epoch. */
        class Done(val nodeId: String, val key: ByteArray, val scopeEpoch: Int) : Created()
        /** A sibling of the kind holds the name already (`slug_taken`). */
        object Taken : Created()
        /** The server refused it (an illegal place or kind, an epoch moved on, …): its code where it named one. */
        class Refused(val code: String?) : Created()
    }

    /**
     * A [kind] node named [name] created as the guest of [session] under [parent] (the web's `createShareNodeAsGuest`): a
     * fresh key wrapped under the parent's, its name and slug token sealed under it, the scope epoch the parent's key was
     * minted under stated. Never under a key the guest does not hold; the server checks the place and the kind
     * (`GuestCreatableKinds`).
     */
    internal suspend fun createNode(session: GuestSession, parent: me.raddatz.veyq.mobile.session.OpenNode, kind: String, name: String): Created {
        val input = me.raddatz.veyq.mobile.AppKinds.tokenInput(kind, name) ?: return Created.Refused("unknown_kind")
        val nodeId = uuid()
        val scopeEpoch = parent.scopeEpoch
        val binding = arrayOf<Pair<String, Any?>>("scopeId" to scopeId, "nodeId" to nodeId, "epoch" to scopeEpoch.toLong(), "placementRevision" to 0L)
        val key = suite.p.randomBytes(32)
        val r = me.raddatz.veyq.mobile.api.apis.SharingApi(client.origin, client.http).createShareNode(
            shareId, session.contributorToken,
            me.raddatz.veyq.mobile.api.models.CreateShareNodeRequest(
                parentEpoch = parent.epoch, nodeId = nodeId, parentId = parent.id, kind = kind,
                encryptedName = me.raddatz.veyq.mobile.crypto.b64(suite.sealField(Context("node-name", *binding), parent.key, me.raddatz.veyq.mobile.crypto.utf8(name))),
                slugToken = me.raddatz.veyq.mobile.crypto.b64(suite.slugToken(parent.key, parent.id, input)),
                scopeEpoch = scopeEpoch,
                wrappedContentKey = me.raddatz.veyq.mobile.crypto.b64(suite.seal(Context("content-key", *binding), parent.key, key)),
            ),
            pwToken,
        )
        if (r.success && r.body().nodeId == nodeId) return Created.Done(nodeId, key, scopeEpoch)
        key.fill(0)
        val code = GuestBlobs.codeOf(runCatching { r.response.bodyAsText() }.getOrDefault(""))
        return if (code == "slug_taken" || (r.status == 409 && code == null)) Created.Taken else Created.Refused(code ?: "status_${r.status}")
    }

    /** Take back node [nodeId] this guest created for an upload that never filled it (`abandonShareNode`); best effort. */
    internal suspend fun abandonNode(session: GuestSession, nodeId: String) {
        runCatching { me.raddatz.veyq.mobile.api.apis.SharingApi(client.origin, client.http).abandonShareNode(shareId, nodeId, session.contributorToken, pwToken) }
    }

    companion object {
        const val READ = "READ"
        const val READ_WRITE = "READ_WRITE"
        const val WRITE_ONLY = "WRITE_ONLY"
    }
}

/**
 * What a guest is shown of one node of a link (ADR 0150 decision 5): the member's own page model of its kind, handed the
 * guest's door and rights — share view = member view, rights only remove functions — or, for a kind without a guest
 * answer, the share page's "not shown here" state, never a half view. The platform draws each as it draws the member's.
 */
sealed class GuestPage {
    abstract val node: NodeAt

    /** A folder: core's level over the guest's tree. */
    class Level(override val node: NodeAt, val model: GuestLevel) : GuestPage()
    /** A Depot file: its page with a guest's viewer. */
    class File(override val node: NodeAt, val model: me.raddatz.veyq.mobile.depot.FileModel) : GuestPage()
    /** A Memory or a chapter: its gallery with a guest's access. */
    class Gallery(override val node: NodeAt, val model: me.raddatz.veyq.mobile.memories.GalleryModel) : GuestPage()
    /** A Pot: its page over the link's Pot reads. */
    class Pot(override val node: NodeAt, val model: me.raddatz.veyq.mobile.expenses.PotModel) : GuestPage()
    /** A drop box's WRITE_ONLY link: the drop page (the guest names itself, adds files). */
    class DropBox(override val node: NodeAt, val model: me.raddatz.veyq.mobile.depot.GuestDropBox) : GuestPage()
    /** A Memory's or a chapter's WRITE_ONLY link: the uploader (the guest names itself, adds photos and videos). */
    class Uploader(override val node: NodeAt, val model: me.raddatz.veyq.mobile.memories.GuestUploader) : GuestPage()
    /** A kind this build shows no guest: the share page's "not shown here". */
    class NotShown(override val node: NodeAt) : GuestPage()
}

/** A kind's answer to a link's guest (`AppKinds.guestDoor`): the page of [node] for [access]. */
fun interface GuestDoor {
    suspend fun open(access: GuestAccess, node: NodeAt, view: GuestView): GuestPage
}
