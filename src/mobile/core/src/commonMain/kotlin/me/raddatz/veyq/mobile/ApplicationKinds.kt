package me.raddatz.veyq.mobile

import me.raddatz.veyq.mobile.tree.KindFacts
import kotlin.native.HiddenFromObjC
import me.raddatz.veyq.mobile.blob.UploadQueue
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.export.NodeExport
import me.raddatz.veyq.mobile.export.SubtreeCopy
import me.raddatz.veyq.mobile.export.SubtreeImport
import me.raddatz.veyq.mobile.media.Derivation
import me.raddatz.veyq.mobile.navigation.ApplicationId
import me.raddatz.veyq.mobile.navigation.HeaderAction
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.ShareAnswer
import me.raddatz.veyq.mobile.offline.PinKinds
import me.raddatz.veyq.mobile.offline.Queued
import me.raddatz.veyq.mobile.offline.QueuedSender
import me.raddatz.veyq.mobile.search.ApplicationSearch
import me.raddatz.veyq.mobile.search.KindSearch
import me.raddatz.veyq.mobile.session.NodeRekey
import me.raddatz.veyq.mobile.session.OpenNode
import me.raddatz.veyq.mobile.session.ShareWords
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.session.VeyqAccount
import me.raddatz.veyq.mobile.session.rotation.ScopeRekeyParticipant
import me.raddatz.veyq.mobile.sharing.GuestAccess
import me.raddatz.veyq.mobile.sharing.GuestDoor
import me.raddatz.veyq.mobile.sharing.GuestSession
import me.raddatz.veyq.mobile.words.Msg

/**
 * One application's part of [AppKinds], the platform's one registry (ADR 0154 decision 3; the web's `register.ts` per
 * application): what the platform's callers read of the node kinds the application owns, and of the application itself.
 * Each member answers for the application's own kinds — or, where an application acts for a platform kind (Depot's
 * answer to files shared to a folder, its uploads into a link's folder), for that one — and null or nothing for every
 * other, so [AppKinds] asks each part and takes the one answer. The composition installs one part per [ApplicationId]
 * (`composition`'s `AppComposition`); `AppCompositionTest` holds that no kind has two answers.
 *
 * Hidden from Objective-C: Swift reads [AppKinds], never a part.
 */
@HiddenFromObjC
interface ApplicationKinds {
    val application: ApplicationId

    /** The node kinds it owns, in the build's order (the search lists them so). */
    val kinds: List<String>

    /** Those of [kinds] that are an object of the application (the drawer's, `applicationOf`); a chapter is none. */
    val objectKinds: List<String> get() = kinds

    /** The noun a node of its kind [kind] is named with where it holds a name another wanted ("a Pot"); null: "an item". */
    fun noun(kind: String): Msg? = null

    /** Its name. */
    val word: Msg

    /** The name of its bar tab [key] ([objectKind]: the object open); null for a tab of another application. */
    fun tabWord(key: String, objectKind: String?): Msg? = null

    /** The sender of a queued write it owns ([AppKinds.queuedSender]). */
    @PlatformApi
    @HiddenFromObjC
    fun queuedSender(q: Queued): QueuedSender? = null

    /** Its kinds' facts as the platform's tree rules read them ([AppKinds.facts]): each kind's route slug, its name's folding
     *  for the slug token, its placement. One entry per kind of [kinds]. */
    val kindFacts: List<KindFacts.Kind> get() = emptyList()

    /** [AppKinds.rekeys] for its kinds. */
    fun rekey(kind: String): NodeRekey.ContentRekey? = null

    /** Its part of a scope rotation's scope-level move ([AppKinds.scopeRekeyParticipants]). */
    val scopeRekeyParticipants: List<ScopeRekeyParticipant> get() = emptyList()

    /** Its material derived from the Master Key, carried to the new one ([AppKinds.masterKeyRotated]); may throw. */
    suspend fun masterKeyRotated(account: VeyqAccount, oldMk: ByteArray, newMk: ByteArray) {}

    /** How its kinds export into a download, for one run ([AppKinds.exports]); null where none does. */
    fun exports(deletedName: String): NodeExport.Kinds? = null

    /** Whether a node of [kind] downloads as its own content ([AppKinds.downloadsItself]). */
    fun downloadsItself(kind: String): Boolean = false

    /** The own download of a node whose kind [downloadsItself] ([AppKinds.ownDownload]). */
    suspend fun ownDownload(tenant: TenantSession, ref: NodeAt, sink: (ByteArray) -> Unit): Boolean = false

    /** The name such a download is saved as ([AppKinds.downloadName]). */
    suspend fun downloadName(tenant: TenantSession, ref: NodeAt): String = ref.name

    /** How its kinds copy, for one run ([AppKinds.copies]); null where none does. */
    fun copies(deletedName: String, uuid: () -> String): SubtreeCopy.Kinds? = null

    /** How its kinds import a download, for one run ([AppKinds.imports]); null where none does. */
    fun imports(deletedName: String, uuid: () -> String, deriver: Derivation.Deriver?, now: () -> Long): SubtreeImport.Kinds? = null

    /** [AppKinds.headerOrder] for its kinds. */
    fun headerOrder(kind: String): List<HeaderAction>? = null

    /** [AppKinds.header] for its kinds. */
    fun header(kind: String): AppKinds.Header? = null

    /** [AppKinds.doors] for its kinds. */
    fun doors(kind: String, facts: AppKinds.Facts): AppKinds.Doors? = null

    /** What a copy of a node of [kind] leaves behind ([AppKinds.lossesOfCopy]); null for another's kind. */
    fun lossesOfCopy(kind: String): List<Msg>? = null

    /** Whether a node of [kind] copies by its open model ([AppKinds.copiesByOpenModel]). */
    fun copiesByOpenModel(kind: String): Boolean = false

    /** What the offline store keeps of it and reaches through it ([AppKinds.kept]); null where it keeps nothing. */
    @PlatformApi
    @HiddenFromObjC
    val kept: me.raddatz.veyq.mobile.offline.ApplicationKept? get() = null

    /** What a pin keeps of its kinds ([AppKinds.pins]); null where it keeps none. */
    val pins: PinKinds? get() = null

    /** The search part of its kind [kind] ([AppKinds.search]). */
    fun search(kind: String): KindSearch? = null

    /** Its search over a whole space, if it has one ([AppKinds.search]'s applications). */
    val spaceSearch: ApplicationSearch? get() = null

    /** The words of the share sheet of its kind [kind] ([AppKinds.shareWords]). */
    fun shareWords(kind: String): ShareWords? = null

    /** Its answer to files shared to a node of [kind] ([AppKinds.share]). */
    fun share(kind: String): ShareAnswer? = null

    /** Its door for a link's guest of [role] at a node of [kind] ([AppKinds.guestDoor]). */
    fun guestDoor(kind: String, role: String): GuestDoor? = null

    /**
     * Whether a READ_WRITE link's guest may move a node of its kind [kind] out of a folder into another of the link
     * ([AppKinds.guestMoves], the web's application rights for a guest: a file and a Memory move; a chapter, a drop box and
     * a Pot do not); false for a kind not its own.
     */
    fun guestMoves(kind: String): Boolean = false

    /** The job a READ_WRITE guest's upload of [source] into a link's folder makes ([AppKinds.guestLevelUploads]). */
    fun guestLevelUpload(access: GuestAccess, session: GuestSession, parent: OpenNode, source: UploadSource): UploadQueue.Job? = null
}
