package me.raddatz.veyq.mobile.sharing

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.raddatz.veyq.mobile.blob.FileSink
import me.raddatz.veyq.mobile.blob.UploadQueue
import me.raddatz.veyq.mobile.crypto.LibsodiumPrimitives
import me.raddatz.veyq.mobile.depot.FileModel
import me.raddatz.veyq.mobile.depot.FileSave
import me.raddatz.veyq.mobile.memories.MediaUpload
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.SpaceRef
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.session.DeviceStore
import me.raddatz.veyq.mobile.session.KeychainBridge
import me.raddatz.veyq.mobile.session.UserDefaultsStore
import me.raddatz.veyq.mobile.session.appleHttpClient
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.Foundation.timeIntervalSince1970
import platform.posix.memcpy

/**
 * The Apple app's door into a received link (ADR 0150 decisions 3–5): a guest view over libsodium and URLSession (the core's
 * ephemeral, nothing-on-disk session, `appleHttpClient`), this installation's `UserDefaults` for the terms' and the note's
 * acceptances (no secret, under the core's hashed names) and the Keychain for a write link's guest session
 * ([KeychainGuestSessions]); a guest's file saved where the person chose.
 */
object AppleSharing {
    /**
     * A guest view of [link], [password] handed in memory (a bookmark's); [queue] and [deriver] the app's for a guest's
     * uploads. Not loaded yet (`GuestScreen.load`).
     */
    fun view(
        link: ReceivedLink.ShareLink, password: String?, store: DeviceStore, sessions: GuestSessions, language: () -> String,
        queue: UploadQueue?, deriver: MediaUpload.Deriver?,
    ): GuestView = GuestView.open(
        LibsodiumPrimitives, link, { block -> appleHttpClient(block) }, store, sessions, language, password, queue, deriver,
        now = { (NSDate().timeIntervalSince1970 * 1000).toLong() },
    )

    /** The terms' and the note's acceptances in [defaults] (the app group's on iOS, as the trust marks). */
    fun store(defaults: NSUserDefaults): DeviceStore = UserDefaultsStore(defaults)

    /** A link's guest sessions in the Keychain ([KeychainGuestSessions]). */
    fun sessions(keychain: KeychainBridge): GuestSessions = KeychainGuestSessions(keychain)

    /** Each guest view the app opens, over this installation's stores, its upload queue and the person's [language]. */
    fun opener(
        store: DeviceStore, sessions: GuestSessions, language: String, queue: UploadQueue?, deriver: MediaUpload.Deriver?,
    ): GuestOpener = GuestOpener(store, sessions, language, queue, deriver)

    /** The page of bookmark [node] in [tenant], its link opened as a guest by [opener]. */
    fun bookmarkPage(tenant: TenantSession, node: NodeAt, opener: GuestOpener): BookmarkPage =
        BookmarkPage.of(tenant, node) { link, password -> opener.view(link, password) }

    // The calls that read, decrypt or derive run off the caller's thread (src/mobile/CLAUDE.md § Traps: a screen model that
    // decrypts or walks a tree): the Swift caller is the main actor, and the password's Argon2id alone takes a while.

    /** The link read and its gates decided (the content behind them where none asks). */
    @Throws(Exception::class)
    suspend fun load(screen: GuestScreen) = withContext(Dispatchers.Default) { screen.load() }

    /** After a gate was accepted (the terms, the note): the content. */
    @Throws(Exception::class)
    suspend fun enter(screen: GuestScreen) = withContext(Dispatchers.Default) { screen.enter() }

    /** The password entered: its token derived, the link resolved again, the content where no other gate asks. */
    @Throws(Exception::class)
    suspend fun unlock(screen: GuestScreen, password: String) = withContext(Dispatchers.Default) {
        screen.view.gates.submitPassword(password)
        screen.enter()
    }

    /** Child [nodeId] of the page on screen opened inside the link. */
    @Throws(Exception::class)
    suspend fun open(screen: GuestScreen, nodeId: String) = withContext(Dispatchers.Default) { screen.view.open(nodeId) }

    /** *Back*: a step inside the link, or at its root the question. */
    @Throws(Exception::class)
    suspend fun back(screen: GuestScreen) = withContext(Dispatchers.Default) { screen.back() }

    /** The bookmark's meta opened and its page decided (the share in place loaded with it). */
    @Throws(Exception::class)
    suspend fun load(page: BookmarkPage) = withContext(Dispatchers.Default) { page.load() }

    /** The places a link may be kept, the personal space first. */
    @Throws(Exception::class)
    suspend fun keepPlaces(screen: GuestScreen): List<SpaceRef> = withContext(Dispatchers.Default) { screen.keepPlaces() }

    /** A guest's naming (a READ_WRITE link's identity) as [displayName]; whether it went. */
    @Throws(Exception::class)
    suspend fun nameGuest(access: GuestAccess, displayName: String): Boolean =
        withContext(Dispatchers.Default) { runCatching { access.nameGuest(displayName.trim()) }.isSuccess }

    /** A drop box's guest named ([GuestDropBox.name]); whether it went. */
    @Throws(Exception::class)
    suspend fun nameDrop(drop: me.raddatz.veyq.mobile.depot.GuestDropBox, displayName: String): Boolean =
        withContext(Dispatchers.Default) { drop.name(displayName) }

    /** An uploader's guest named ([GuestUploader.name]); whether it went. */
    @Throws(Exception::class)
    suspend fun nameUploader(up: me.raddatz.veyq.mobile.memories.GuestUploader, displayName: String): Boolean =
        withContext(Dispatchers.Default) { up.name(displayName) }

    /** The bookmark's save ([BookmarkCreate.save]); whether it was made. */
    @Throws(Exception::class)
    suspend fun save(create: BookmarkCreate): Boolean = withContext(Dispatchers.Default) { create.save() }

    /**
     * A READ_WRITE guest's replace of [file]'s content with [source] (the job the guest's file door makes) in [queue]; the
     * page reads the file anew once it settled. False where the file takes no replace now.
     */
    fun replace(queue: UploadQueue, file: FileModel, source: me.raddatz.veyq.mobile.blob.UploadSource): Boolean {
        val job = file.replace(source) ?: return false
        queue.enqueue(listOf(job), source.name, file.node.nodeId, onSettled = { file.reload() }, onFinished = null)
        return true
    }

    /**
     * A guest's file saved at [path] (`FileModel.save` into core's [FileSink], which leaves nothing at [path] when it did not
     * finish), awaited to its end; null where there is nothing to save.
     */
    @Throws(Exception::class)
    suspend fun saveFile(file: FileModel, path: String): FileSave.End? {
        val save = file.save { produce ->
            withContext(Dispatchers.Default) { FileSink.write(path, kept = { it }) { sink -> produce(sink) } }
        } ?: return null
        return save.state.first { it.end != null }.end
    }
}

/**
 * Opens a received link as its guest ([view]) over this installation's stores, its upload queue and deriver, in the person's
 * [language] — what *Open a link*, a bookmark's page and its own page each make their guest view with. Built in Kotlin, so a
 * view made on core's threads (a bookmark's page loads off the main thread) calls nothing of the Swift app.
 */
class GuestOpener internal constructor(
    private val store: DeviceStore,
    private val sessions: GuestSessions,
    private val language: String,
    private val queue: UploadQueue?,
    private val deriver: MediaUpload.Deriver?,
) {
    /** A guest view of [link], [password] handed in memory (a bookmark's). */
    fun view(link: ReceivedLink.ShareLink, password: String?): GuestView =
        AppleSharing.view(link, password, store, sessions, { language }, queue, deriver)

    /** *Open a link*'s field over this opener ([AppleOpenLink]). */
    fun field(): AppleOpenLink = AppleOpenLink(this)
}

/**
 * *Open a link*'s field ([OpenLink]) and the screen it opens: the link's own `&report` request kept with it, so the screen
 * opens its report dialog (ADR 0120 decision 6) — what Swift would otherwise have to keep beside a core callback.
 */
class AppleOpenLink internal constructor(private val opener: GuestOpener) {
    private var report = false
    val field: OpenLink = OpenLink { link, asked ->
        report = asked
        opener.view(link, null)
    }

    /** The field's link as its guest screen ([tenant]: the signed-in person's, for *Keep*); null while the text is no link. */
    fun open(tenant: TenantSession?): GuestScreen? {
        val view = field.open() ?: return null
        return GuestScreen(view, tenant, reportAsked = report).also { report = false }
    }
}

/**
 * A link's guest sessions in the Keychain (ADR 0150 decision 3, `src/mobile/CLAUDE.md` § Nothing on disk): generic-password
 * items of this app on this device only (the same class as the device's sealed secrets — never in a backup or iCloud),
 * each named by the core's hashed name (`GuestNames.session`), never the share's id; on iOS in the shared access group
 * every new item goes to. A Keychain that refuses the write — a build without entitlements, as an ad-hoc signed Mac
 * build or an unsigned simulator build (`src/mobile/CLAUDE.md` § Traps) — keeps the session in this process's memory
 * instead: it lasts the run and is written nowhere, the core's in-memory port's behaviour.
 */
@OptIn(ExperimentalForeignApi::class)
class KeychainGuestSessions(private val keychain: KeychainBridge) : GuestSessions {
    private val memory = HashMap<String, String>()

    override fun read(name: String): String? =
        memory[name] ?: keychain.load(name, "", protected = false).data?.let { data ->
            val n = data.length.toInt()
            if (n == 0) "" else ByteArray(n).also { bytes -> bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) } }.decodeToString()
        }

    override fun write(name: String, value: String) {
        keychain.remove(name)
        val bytes = value.encodeToByteArray()
        val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
        if (keychain.store(name, data, protected = false, passcode = false)) memory.remove(name) else memory[name] = value
    }

    override fun remove(name: String) {
        memory.remove(name)
        keychain.remove(name)
    }
}
