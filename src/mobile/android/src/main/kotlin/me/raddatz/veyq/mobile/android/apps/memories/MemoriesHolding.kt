package me.raddatz.veyq.mobile.android.apps.memories

import android.app.Application
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.raddatz.veyq.mobile.android.core.AddHerePage
import me.raddatz.veyq.mobile.android.core.AddedHere
import me.raddatz.veyq.mobile.android.core.AppModel
import me.raddatz.veyq.mobile.android.core.Holding
import me.raddatz.veyq.mobile.android.core.HoldingKey
import me.raddatz.veyq.mobile.android.core.ShareIn
import me.raddatz.veyq.mobile.android.core.language
import me.raddatz.veyq.mobile.android.core.text
import me.raddatz.veyq.mobile.android.words
import me.raddatz.veyq.mobile.composition.AddHere
import me.raddatz.veyq.mobile.composition.DropOutcome
import me.raddatz.veyq.mobile.composition.DropTarget
import me.raddatz.veyq.mobile.composition.Dropped
import me.raddatz.veyq.mobile.composition.ShellModel
import me.raddatz.veyq.mobile.memories.Contributions
import me.raddatz.veyq.mobile.memories.GalleryFilter
import me.raddatz.veyq.mobile.memories.GalleryModel
import me.raddatz.veyq.mobile.memories.Lightbox
import me.raddatz.veyq.mobile.memories.MediaUploads
import me.raddatz.veyq.mobile.memories.MemoryCreate
import me.raddatz.veyq.mobile.memories.ParticipantsPicker
import me.raddatz.veyq.mobile.session.TenantSession

/** The key Memories' UI reaches its [MemoriesHolding] by (registered in [MemoriesUi.register]). */
internal val MemoriesHeld = HoldingKey(::MemoriesHolding)

/** What Memories holds in the app's model: the gallery on screen, its views and dialogs ([MemoriesHolding]). */
internal val AppModel.memories: MemoriesHolding get() = held(MemoriesHeld)

/**
 * What Memories holds in the app's model for the open tenant (ADR 0154 decision 3): the Memory's or chapter's page on
 * screen (core's `GalleryModel`, one at a time) and its Contributions, Map and Music views, the curation's dialogs and a
 * person's section held across a rotation, and the tenant's media uploads (core's `MediaUploads`, the tiers Android
 * derives). Each is closed where the tenant's models close.
 */
internal class MemoriesHolding(private val model: AppModel) : Holding() {
    private val scope get() = model.viewModelScope
    private val app get() = model.getApplication<Application>()

    // ---- the moments of the tenant's life (core asks them) ----

    override fun leave() {
        personSection = null
        closeCuration()
        closeViews()
        openGallery?.close()
        openGallery = null
        openContributions = null
    }

    override fun shown(shell: ShellModel, tenant: TenantSession, switched: Boolean) {
        // Android reads a pick in place: a file the queue is done with leaves nothing to remove but a share's own copy
        val made = shell.mediaUploads(model.uploads, deriver, left = ShareIn::release) { scope.launch { model.refreshLists(tenant) } }
        // a new tenant's queue was emptied; the same tenant opened again carries its uploads on
        media?.let { if (switched) it.close() else it.handOver(made) }
        media = made
    }

    override fun locked() {
        openMusic?.close()
        openMusic = null
        closeCuration()
        personSection = null
    }

    override fun signedOut() {
        openMusic?.close()
        openMusic = null
        closeCuration()
        openGallery?.close()
        openGallery = null
        media?.close()
        media = null
        personSection = null
    }

    override fun personOpened() {
        personSection = null
    }

    /** A Memory's row says its date as its last line (ADR 0143 D5). */
    override suspend fun rowLines(shell: ShellModel): Map<String, String> =
        shell.memoryDates().mapNotNull { (id, d) -> app.memoryDateText(d)?.let { id to it } }.toMap()

    /** *Add here* on a Memory's or a chapter's page: its gallery's upload; what is no photo or video said. */
    override fun addHere(target: AddHere, label: String): AddedHere? {
        if (target !is AddHere.Gallery) return null
        return target.take(model.shareInbox, label, null, ShareIn::release)?.let { AddedHere(MediaUploads.notTaken(it.refused)) }
    }

    /** Files dropped onto a gallery, or onto the bucket its Contributions view shows: its upload (core's one drop). */
    override suspend fun drop(shell: ShellModel, target: DropTarget, items: List<Dropped>): DropOutcome? =
        if (target is DropTarget.Gallery || target is DropTarget.Bucket) shell.drop(target, items, null, ShareIn::release) else null

    override val deriver: me.raddatz.veyq.mobile.media.Derivation.Deriver get() = tiers

    /** [gallery]'s page is on screen ([shown]) or gone: the pick mode's *Add here* puts what waits into it. */
    fun shareTarget(gallery: GalleryModel, shown: Boolean) {
        val page = pages.getOrPut(gallery) {
            object : AddHerePage {
                override val changes = gallery.state
                override fun at(shell: me.raddatz.veyq.mobile.navigation.Shell) = model.shellModel?.addHere(model.shareInbox, shell, gallery)
            }
        }
        model.addHerePage(page, shown)
        if (!shown) pages.remove(gallery)
    }

    private val pages = HashMap<GalleryModel, AddHerePage>()

    /** The open gallery's views close with it (another gallery, another tenant): its Music view and its Map view's tracks. */
    private fun closeViews() {
        openMusic?.close()
        openMusic = null
        openTracks?.close()
        openTracks = null
    }

    // ---- a Memory's curation (ADR 0144): its participants picker and its banner's dialog, held here so a rotation keeps them ----

    private val _participants = MutableStateFlow<ParticipantsPicker?>(null)

    /** The open gallery's participants picker (core's `ParticipantsPicker`); null while closed. */
    val participants: StateFlow<ParticipantsPicker?> = _participants.asStateFlow()

    private val _banner = MutableStateFlow<me.raddatz.veyq.mobile.memories.banner.BannerEditor?>(null)

    /** The open gallery's banner dialog (core's `BannerEditor`); null while closed. */
    val banner: StateFlow<me.raddatz.veyq.mobile.memories.banner.BannerEditor?> = _banner.asStateFlow()

    /** The dialog's running save or removal: Cancel cancels it, the old banner staying as it was. */
    private var bannerJob: Job? = null

    /** The file picked last in the banner's dialog, read where it lies (closed with the dialog). */
    private var bannerSource: me.raddatz.veyq.mobile.blob.UploadSource? = null

    /** The profile on screen's Memories section, kept here so a rotation keeps its List | Map choice. */
    private var personSection: me.raddatz.veyq.mobile.memories.people.PersonMemories? = null

    /** The Memory or chapter page on screen (core's `GalleryModel`): one at a time, read anew whenever the lists are. */
    private var openGallery: GalleryModel? = null

    /** The page of Memory or chapter [node] (core's `GalleryModel` over the shell, its uploads through [media]). */
    fun galleryModel(node: me.raddatz.veyq.mobile.navigation.NodeAt): GalleryModel? {
        openGallery?.takeIf { it.node == node }?.let { return it }
        val m = model.shellModel ?: return null
        val uploads = media ?: return null
        openMusic?.close()
        openMusic = null
        openTracks?.close()
        openTracks = null
        closeCuration()
        openGallery?.close()
        val app = app
        val music = model.music.value?.let { p -> me.raddatz.veyq.mobile.audio.PageMusic(p) { n -> app.words(me.raddatz.veyq.mobile.words.Msg.MUSIC_UNTITLED, n) } }
        openContributions = null
        return m.galleryModel(node, uploads, music, galleryCalendar()).also {
            openGallery = it
            followSearchOpened(it)
        }
    }

    private var searchOpenedWatch: Job? = null

    /**
     * A photo of this gallery chosen in Search (ADR 0146 decision 8: the gallery's own documents open in place) opened its
     * lightbox on a page the full-screen Search covers: Search is left for the page — the page itself where Search stands
     * on it, else the page opened where it lives (back returns to Search) —, which then shows the lightbox.
     */
    private fun followSearchOpened(gallery: GalleryModel) {
        searchOpenedWatch?.cancel()
        searchOpenedWatch = scope.launch {
            gallery.state.collect { st ->
                val m = model.shellModel ?: return@collect
                val shell = m.shell ?: return@collect
                if (st.searchOpened == null || !shell.searching || openGallery !== gallery) return@collect
                val open = shell.openObject
                if (open != null && open.scopeId == gallery.node.scopeId && open.nodeId == gallery.node.nodeId) model.search(false)
                else m.openFound(gallery.node.scopeId, gallery.node.nodeId)
            }
        }
    }

    /**
     * The viewer's calendar for a gallery filter's day chips (ADR 0149 decision 5, inbox #989): the core's
     * (`GalleryFilter.Calendar.local`: a capture time's local day, its chip labelled as the web labels it) in the app's language.
     */
    private fun galleryCalendar(): GalleryFilter.Calendar =
        GalleryFilter.Calendar.local(app.language)

    // ---- a Memory's Contributions view (ADR 0149 decision 6) ----

    /** The Contributions view on screen: one at a time, the open gallery's. */
    private var openContributions: Contributions? = null
    private var contributionsOf: GalleryModel? = null

    /** [gallery]'s Contributions view (core's `Contributions`), read on its first ask; null while no tenant is open. */
    fun contributions(gallery: GalleryModel): Contributions? {
        openContributions?.takeIf { contributionsOf === gallery }?.let { return it }
        val m = model.shellModel ?: return null
        return m.contributions(gallery).also { c ->
            openContributions = c
            contributionsOf = gallery
            scope.launch(Dispatchers.Default) { c.read() }
        }
    }

    /** A curation of [contributions] (a sub-bucket made, renamed, deleted, a medium moved), off the main thread. */
    fun curate(write: suspend () -> Unit) {
        scope.launch(Dispatchers.Default) { write() }
    }

    /** A person's Memories drawn as [view]: the section keeps it as the account's choice, which every level then follows. */
    fun choosePersonView(section: me.raddatz.veyq.mobile.memories.people.PersonMemories, view: me.raddatz.veyq.mobile.memories.people.PersonMemories.View) {
        section.view(view)
        model.followLevelView()
    }

    /** The gallery page went: its lightbox and any video's reader close with it, and its Music view (the music plays on). */
    fun closeGallery(gallery: GalleryModel) {
        if (openGallery !== gallery) return
        openMusic?.close()
        openMusic = null
        openTracks?.close()
        openTracks = null
        closeCuration()
        gallery.close()
        openGallery = null
        openContributions = null
        contributionsOf = null
    }

    // ---- a Memory's curation (ADR 0144): its participants picker and its banner's dialog, held here so a rotation keeps them ----


    /** [gallery]'s participants picker opened over the scope's current members, where its rights offer it. */
    fun openParticipants(gallery: GalleryModel) {
        val picker = gallery.participantsPicker() ?: return
        _participants.value = picker
        scope.launch { picker.load() }
    }

    /** The members read again (*Try again*). */
    fun reloadParticipants() {
        val picker = _participants.value ?: return
        scope.launch { picker.load() }
    }

    /** The pick saved (core's merge); the picker closes once it landed. */
    fun saveParticipants() {
        val picker = _participants.value ?: return
        scope.launch { if (picker.save().let { it == me.raddatz.veyq.mobile.memories.place.MemoryFacts.Outcome.Written || it == me.raddatz.veyq.mobile.memories.place.MemoryFacts.Outcome.Unchanged }) closeParticipants(picker) }
    }

    fun closeParticipants(picker: ParticipantsPicker? = _participants.value) {
        if (_participants.value === picker) _participants.value = null
    }


    /** A banner's pictures decoded on this device: an image as the cover's JPEG, a video's first frame ([MediaDerive]). */
    private val bannerPictures = object : me.raddatz.veyq.mobile.memories.banner.BannerWrite.Pictures {
        override suspend fun image(source: me.raddatz.veyq.mobile.blob.UploadSource): ByteArray? =
            (source as? AppModel.ContentSource)?.uri?.let { u -> withContext(Dispatchers.IO) { MediaDerive.bannerImage(app, u) } }

        override suspend fun poster(source: me.raddatz.veyq.mobile.blob.UploadSource): ByteArray? =
            (source as? AppModel.ContentSource)?.uri?.let { u -> withContext(Dispatchers.IO) { MediaDerive.bannerPoster(app, u) } }
    }

    /** [gallery]'s banner dialog opened, where whoever looks may set its banner online. */
    fun openBanner(gallery: GalleryModel) {
        if (openGallery !== gallery) return
        _banner.value = gallery.bannerEditor(bannerPictures)
    }

    /** The file the photo picker handed over ([uri]) taken as the banner: refused or decoded for the previews (core's). */
    fun pickBanner(uri: android.net.Uri) {
        val editor = _banner.value ?: return
        scope.launch {
            // the media store's original where full media access was given, as an upload reads it: the equal-file match needs its bytes
            val source = withContext(Dispatchers.IO) { pickedMedium(uri)?.let(model::source) } ?: return@launch
            bannerSource?.close()
            bannerSource = source
            editor.pick(source)
        }
    }

    /** The picked file made the banner, or [remove]d: one run at a time, cancelled by [cancelBanner]. */
    fun saveBanner(remove: Boolean = false) {
        val editor = _banner.value ?: return
        if (bannerJob?.isActive == true) return
        bannerJob = scope.launch { if (remove) editor.remove() else editor.save() }
    }

    fun cancelBanner() {
        bannerJob?.cancel()
        bannerJob = null
    }

    fun closeBanner() {
        cancelBanner()
        _banner.value = null
        bannerSource?.close()
        bannerSource = null
    }

    /** The open gallery's dialogs closed with it (a switch, a lock, a sign-out, another gallery). */
    fun closeCuration() {
        closeParticipants()
        closeBanner()
    }

    /** Memories' section of person [userId]'s profile (core's `PersonMemories`), read on its first ask. */
    fun personMemories(userId: String): me.raddatz.veyq.mobile.memories.people.PersonMemories? {
        personSection?.takeIf { it.userId == userId }?.let { return it }
        val m = model.shellModel ?: return null
        return m.personMemories(userId).also { section ->
            personSection = section
            // the index unseals each Memory's meta: off the main thread (src/mobile/CLAUDE.md § Traps)
            scope.launch(Dispatchers.Default) { section.load() }
        }
    }

    /** *Try again* on the section. */
    fun retryPerson(section: me.raddatz.veyq.mobile.memories.people.PersonMemories) {
        scope.launch(Dispatchers.Default) { section.load() }
    }

    /** [gallery]'s date editor (core's `DateEditor`), where whoever looks may edit its meta online; null otherwise. */
    fun dateEditor(gallery: GalleryModel): me.raddatz.veyq.mobile.memories.place.DateEditor? = model.shellModel?.dateEditor(gallery)

    /** [gallery]'s place editor (core's `PlaceEditor` over the map services), where whoever looks may edit its meta online. */
    fun placeEditor(gallery: GalleryModel): me.raddatz.veyq.mobile.memories.place.PlaceEditor? = model.shellModel?.placeEditor(gallery)

    /** The Memories bar's Map tab's pins over [memories] (its All tab's set; core's `MemoriesMap`). */
    suspend fun memoriesMap(memories: List<me.raddatz.veyq.mobile.navigation.ObjectRef>): me.raddatz.veyq.mobile.memories.place.MemoriesMap? {
        val m = model.shellModel ?: return null
        // each pin's meta opened off the main thread
        return withContext(Dispatchers.Default) {
            m.memoriesMap(memories.map { me.raddatz.veyq.mobile.navigation.NodeRef(it.scopeId, it.nodeId, null, it.kind, it.name) })
        }
    }

    /** The open gallery's Map view's tracks (core's `TrackList`): one at a time, closed with its gallery. */
    private var openTracks: me.raddatz.veyq.mobile.memories.tracks.TrackList? = null

    /** [gallery]'s Map view's tracks, its picked GPX files queued through [media]. */
    fun trackList(gallery: GalleryModel): me.raddatz.veyq.mobile.memories.tracks.TrackList? {
        openTracks?.takeIf { it.gallery === gallery }?.let { return it }
        val m = model.shellModel ?: return null
        val uploads = media ?: return null
        openTracks?.close()
        return m.trackList(gallery, uploads, left = {}).also { openTracks = it }
    }

    /** The GPX files the system's picker granted ([uris]) offered to [tracks] (core's checks; the accepted ones' labels asked there). */
    fun addTracks(tracks: me.raddatz.veyq.mobile.memories.tracks.TrackList, uris: List<android.net.Uri>) {
        scope.launch {
            val sources = withContext(Dispatchers.IO) { uris.mapNotNull { model.picked(it) }.map { model.source(it) } }
            tracks.add(sources)
        }
    }

    /** Track [mediaId] of [tracks] saved to the document the person made at [uri] (the gallery's save); a document it did not fill goes again. */
    fun saveGpx(tracks: me.raddatz.veyq.mobile.memories.tracks.TrackList, mediaId: String, uri: android.net.Uri): me.raddatz.veyq.mobile.blob.FileSave? {
        val resolver = app.contentResolver
        val save = tracks.save(mediaId) { produce ->
            val out = withContext(Dispatchers.IO) { resolver.openOutputStream(uri) } ?: return@save false
            out.use { o -> produce { o.write(it) } }
        } ?: return null
        scope.launch {
            val ended = save.state.first { it.end != null }
            if (ended.end != me.raddatz.veyq.mobile.blob.FileSave.End.SAVED) {
                withContext(Dispatchers.IO) { runCatching { android.provider.DocumentsContract.deleteDocument(resolver, uri) } }
            }
        }
        return save
    }

    /** The open gallery's Music view (core's `MusicModel`, ADR 0142 decision 8); one at a time, closed with its gallery. */
    private var openMusic: me.raddatz.veyq.mobile.memories.music.MusicModel? = null

    /** [gallery]'s Music view: its tracks, the picked files checked with Android's [musicMeasure], queued through [media]. */
    fun musicModel(gallery: GalleryModel): me.raddatz.veyq.mobile.memories.music.MusicModel? {
        openMusic?.takeIf { it.gallery === gallery }?.let { return it }
        val m = model.shellModel ?: return null
        val uploads = media ?: return null
        openMusic?.close()
        return m.musicModel(gallery, uploads, musicMeasure, left = {}).also { openMusic = it }
    }

    /**
     * A picked file's length as Android's player would play it (core's `MusicUpload.Measure`, ADR 0142 decision 7):
     * `MediaMetadataRetriever` over the document; null where it cannot read it, and the file is refused as unplayable. Its
     * two ends decoded by `MediaCodec` for the silence scan ([AudioEnds]).
     */
    private val musicMeasure = object : me.raddatz.veyq.mobile.memories.music.MusicUpload.Measure {
        override fun durationMs(source: me.raddatz.veyq.mobile.blob.UploadSource, type: String, done: (Long?) -> Unit) {
            val uri = (source as? AppModel.ContentSource)?.uri ?: return done(null)
            scope.launch(Dispatchers.IO) {
                val ms = runCatching {
                    val r = android.media.MediaMetadataRetriever()
                    try {
                        r.setDataSource(app, uri)
                        r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    } finally {
                        r.release()
                    }
                }.getOrNull()
                done(ms)
            }
        }

        /** A decoded format's two ends, MediaCodec's (inbox #932: [AudioEnds]); null where it does not decode them. */
        override fun ends(source: me.raddatz.veyq.mobile.blob.UploadSource, type: String, durationMs: Long, limitMs: Long, done: (Pair<ByteArray, ByteArray>?) -> Unit) {
            val uri = (source as? AppModel.ContentSource)?.uri ?: return done(null)
            scope.launch(Dispatchers.IO) { done(AudioEnds.decode(app, uri, durationMs, limitMs)) }
        }
    }

    /** The audio files the system's picker granted ([uris]) added to [music]'s node as tracks (core's checks, its refusals said there). */
    fun addMusic(music: me.raddatz.veyq.mobile.memories.music.MusicModel, uris: List<android.net.Uri>) {
        scope.launch {
            val sources = withContext(Dispatchers.IO) { uris.mapNotNull { model.picked(it) }.map { model.source(it) } }
            music.add(sources)
        }
    }

    /**
     * Track [mediaId] of [music] saved to the document the person made at [uri] (core's `FileSave`, as a lightbox's
     * original is): a document it did not fill goes again. The save, whose end the view says; null where it is not offered.
     */
    fun saveTrack(music: me.raddatz.veyq.mobile.memories.music.MusicModel, mediaId: String, uri: android.net.Uri): me.raddatz.veyq.mobile.blob.FileSave? {
        val resolver = app.contentResolver
        val save = music.save(mediaId) { produce ->
            val out = withContext(Dispatchers.IO) { resolver.openOutputStream(uri) } ?: return@save false
            out.use { o -> produce { o.write(it) } }
        } ?: return null
        scope.launch {
            val ended = save.state.first { it.end != null }
            if (ended.end != me.raddatz.veyq.mobile.blob.FileSave.End.SAVED) {
                withContext(Dispatchers.IO) { runCatching { android.provider.DocumentsContract.deleteDocument(resolver, uri) } }
            }
        }
        return save
    }

    /**
     * [gallery]'s delete, Undo or purge (core's `GalleryModel.trash`/`undo`/`purge`, what each says lands in its state) run
     * in the model's scope: a request the person started finishes though the lightbox or the snackbar that asked went.
     */
    fun galleryAct(act: suspend () -> Unit) {
        scope.launch { act() }
    }

    /** Decoded tiers by blob id, the most recent kept (a gallery asks for every tile as it scrolls past). */
    private val mediaPictures = object : android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: androidx.compose.ui.graphics.ImageBitmap) = value.width * value.height * 4
    }

    /** Blob [blobId]'s bytes as [read] hands them, decoded as a picture and kept; null where it does not read or is no picture. */
    suspend fun mediaPicture(blobId: String, read: suspend () -> ByteArray?): androidx.compose.ui.graphics.ImageBitmap? {
        mediaPictures.get(blobId)?.let { return it }
        val picture = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = read() ?: return@runCatching null
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        } ?: return null
        mediaPictures.put(blobId, picture)
        return picture
    }

    /**
     * The original [box] shows saved to the document the person made at [uri] (core's `FileSave`, a frame at a time); the
     * save outlives the lightbox, and half an original is no download: a document it did not fill goes again.
     */
    fun saveMedium(box: Lightbox, uri: android.net.Uri) {
        val resolver = app.contentResolver
        val save = box.save { produce ->
            val out = withContext(Dispatchers.IO) { resolver.openOutputStream(uri) } ?: return@save false
            out.use { o -> produce { o.write(it) } }
        } ?: return
        scope.launch {
            val ended = save.state.first { it.end != null }
            if (ended.end != me.raddatz.veyq.mobile.blob.FileSave.End.SAVED) {
                withContext(Dispatchers.IO) { runCatching { android.provider.DocumentsContract.deleteDocument(resolver, uri) } }
            }
        }
    }

    /** Memories' uploads of the open tenant (core's `MediaUploads` over [uploads]): Android reads each pick in place. */
    private var media: MediaUploads? = null

    /**
     * Queues the photos and videos the system's picker granted ([uris]) into [gallery]'s Memory or chapter (core's
     * `MediaUploads`: each one's tiers derived here ([MediaDerive]) while the one before it is on the wire, its bytes read at
     * any offset; the core reads the gallery anew as each is in, the lists at the batch's end). A link's guest not named
     * yet is asked its name first by the core (`GalleryModel.uploadAsking`, #1044).
     */
    fun uploadMedia(gallery: GalleryModel, uris: List<android.net.Uri>) {
        scope.launch {
            val sources = withContext(Dispatchers.IO) { uris.mapNotNull { pickedMedium(it) }.map { model.source(it) } }
            gallery.uploadAsking(sources)
        }
    }

    /** Android's tiers of a picked medium ([MediaDerive]), for core's upload job. */
    private val tiers = object : me.raddatz.veyq.mobile.media.Derivation.Deriver {
        override fun derive(source: me.raddatz.veyq.mobile.blob.UploadSource, type: String, done: (me.raddatz.veyq.mobile.media.Derivation.Derived) -> Unit) {
            val uri = (source as? AppModel.ContentSource)?.uri ?: (source as? ShareIn.Copy)?.let { android.net.Uri.fromFile(it.file) }
            done(uri?.let { MediaDerive.of(app, it, type) } ?: me.raddatz.veyq.mobile.media.Derivation.Derived(null, null, null, null))
        }
    }

    /**
     * A photo or video the system's picker handed over ([uri]), read as it was taken where the person gave the app full
     * media access: the media store's own item behind it — its location kept (`setRequireOriginal`), its own name — else
     * (no such access, only some photos allowed, an item the store does not hold) the picker's copy as it is.
     */
    private fun pickedMedium(uri: android.net.Uri): AppModel.Picked? {
        val picked = model.picked(uri) ?: return null
        val app = app
        fun granted(p: String) = app.checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val full = if (android.os.Build.VERSION.SDK_INT >= 33) granted(android.Manifest.permission.READ_MEDIA_IMAGES) && granted(android.Manifest.permission.READ_MEDIA_VIDEO)
        else granted(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        if (!full || !granted(android.Manifest.permission.ACCESS_MEDIA_LOCATION)) return picked
        return runCatching {
            val id = uri.lastPathSegment?.toLongOrNull() ?: return picked
            val video = picked.type.startsWith("video/")
            val base = if (video) android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI else android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val item = android.content.ContentUris.withAppendedId(base, id)
            val resolver = app.contentResolver
            val cols = arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, android.provider.MediaStore.MediaColumns.SIZE, android.provider.MediaStore.MediaColumns.DATE_MODIFIED)
            val listed = resolver.query(item, cols, null, null, null)?.use { c ->
                if (!c.moveToFirst()) null
                else Triple(c.getString(0), c.getLong(1), if (c.isNull(2)) null else c.getLong(2) * 1000)
            } ?: return picked
            val original = android.provider.MediaStore.setRequireOriginal(item)
            resolver.openFileDescriptor(original, "r")?.close() ?: return picked
            AppModel.Picked(original, listed.first ?: picked.name, listed.second.takeIf { it > 0 } ?: picked.size, picked.type, listed.third ?: picked.modifiedAt)
        }.getOrDefault(picked)
    }

    /** Whether the app asked for full media access before (once: a no is the person's, and the picker works without it). */
    fun mediaAccessAsked(): Boolean {
        val prefs = app.getSharedPreferences("veyq.memories", android.content.Context.MODE_PRIVATE)
        val asked = prefs.getBoolean("mediaAccessAsked", false)
        if (!asked) prefs.edit().putBoolean("mediaAccessAsked", true).apply()
        return asked
    }

    /**
     * A Memory [name] made in space [scopeId] under [parentId] (null: the space's top) — New Memory, core's
     * `ShellModel.createMemory` (the web's `MemoryNodeDialog`: checked against the loaded tree, then sent) —, opened once
     * made (the web's), its maker listed among its participants where [wasThere] (ADR 0144 decision 2). Why not, in the
     * dialog's words, or null.
     */
    suspend fun createMemory(scopeId: String, parentId: String?, name: String, wasThere: Boolean = false, autoPlace: Boolean = false): String? {
        val app = app
        val m = model.shellModel ?: return app.text(MemoryCreate.refused(null))
        return opened(m.createMemory(scopeId, parentId, name, wasThere, autoPlace)).also { model.readRowDetails(m.tenant) }
    }

    /** Whether New Memory in space [scopeId] offers *I was there* (a tenant or group space, ADR 0144 decision 2). */
    fun offersWasThere(scopeId: String): Boolean = model.shellModel?.offersWasThere(scopeId) == true

    /** Whether New Memory offers *Determine location automatically* (uc.memory.place A1: this tenant's map services shown on this device). */
    fun offersAutoPlace(): Boolean = model.shellModel?.offersAutoPlace() == true

    /** A chapter [name] made in [gallery]'s Memory (core's `GalleryModel.createChapter`), opened once made; why not, or null. */
    suspend fun createChapter(gallery: GalleryModel, name: String): String? {
        // core reads the lists anew once it is made (`ShellModel.createMemoryNode`)
        val made = gallery.createChapter(name)
        model.shellModel?.let { model.readRowDetails(it.tenant) }
        return opened(made)
    }

    /** What a Memory's or a chapter's create came to: the node made opened, or why not in words. */
    private fun opened(made: MemoryCreate.Made): String? {
        // made offline: the dialog closes, *Waiting to send* is said, it shows where it was made (core's `Made.queued`)
        if (made.queued) {
            made.notice?.let(model::said)
            return null
        }
        made.notice?.let { return app.text(it) }
        val n = made.node ?: return app.text(MemoryCreate.refused(null))
        GalleryModel.page(n)?.let(model::open)
        return null
    }
}
