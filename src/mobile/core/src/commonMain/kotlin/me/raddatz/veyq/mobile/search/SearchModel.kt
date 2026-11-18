package me.raddatz.veyq.mobile.search

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import me.raddatz.veyq.mobile.navigation.ShellWords
import me.raddatz.veyq.mobile.navigation.SpaceRef
import me.raddatz.veyq.mobile.session.LocalDays
import me.raddatz.veyq.mobile.session.OpenNode
import me.raddatz.veyq.mobile.session.Person
import me.raddatz.veyq.mobile.words.Joined
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.MsgKey
import me.raddatz.veyq.mobile.words.Notice
import me.raddatz.veyq.mobile.words.Piece

/** What a search field draws (ADR 0146 decision 7): the core's state, each platform's control draws it and decides nothing. */
@Serializable
data class SearchShown(
    /** The text as the model holds it. */
    val text: String = "",
    /** The chips set, in the order they were chosen. */
    val chips: List<ChipShown> = emptyList(),
    /** Something is asked (text or a chip): the list is open. A calm focus leaves it closed. */
    val asked: Boolean = false,
    /** The chips offered for the text, under *Filter by* (`search_filter_by`). */
    val suggestions: List<SuggestionShown> = emptyList(),
    /** The first results, under *Results* (`search_results`). */
    val results: List<ResultShown> = emptyList(),
    /** How many results *Show more* adds (`search_show_more`); 0: no *Show more*. */
    val more: Int = 0,
    /** How many documents match, those not drawn included. */
    val total: Int = 0,
    /** What the field says beside its lists: a space loading, spaces not searched, offline's photos, the empty state. */
    val status: List<Notice> = emptyList(),
    /** What a screen reader hears now (a polite announcement): the last chip added or removed, then the count. */
    val announcement: List<Notice> = emptyList(),
    /** A settings section a result chose (`SettingsSection.id`): the platform opens its settings page there; cleared by the next action. */
    val openSettings: String? = null,
    /**
     * How many times a settings result was opened in this field: a platform opens the page at [openSettings] whenever this
     * moves. The same section chosen twice in a row leaves [openSettings] as it was, and a state equal to the last one is
     * never delivered again (a flow keeps distinct states only); the count tells the second choice apart.
     */
    val openSettingsCount: Int = 0,
)

/** A chip set: its [id] (`Query.chipKey`), its type's [word] (*Year*, *Place*, …), its value as [label], and the icon kind it draws. */
@Serializable
data class ChipShown(
    val id: String,
    @Serializable(with = MsgKey::class) val word: Msg,
    val label: Piece,
    val icon: String,
)

/** A chip offered: as [ChipShown], with [where] its second line (an *In:* node's space and path; empty otherwise). */
@Serializable
data class SuggestionShown(
    val id: String,
    @Serializable(with = MsgKey::class) val word: Msg,
    val label: Piece,
    val icon: String,
    val where: List<Piece> = emptyList(),
)

/** A part of a name as a row draws it: [strong] where it matched. */
@Serializable
data class PartShown(val text: String, val strong: Boolean)

/**
 * One result row: its icon kind, its name in parts (bold where matched), its second line in pieces — joined by the
 * platform with " · " — and, where a settings section's word is why it is listed, that word in parts after it ([reason]).
 */
@Serializable
data class ResultShown(
    val id: String,
    val icon: String,
    val name: List<PartShown>,
    val line: List<Piece>,
    val reason: List<PartShown>? = null,
)

/** The words the search matches and names in the UI's language (each platform's catalog: Android's, Apple's, the host's). */
fun interface SearchWords {
    suspend fun say(msg: Msg): String
}

/**
 * The search field's model (ADR 0146 decisions 6–10; the web's `SearchField.vue` with `searchIndex.ts`): one per tenant's
 * shell, its state [SearchShown]. The first [focus] builds the index — the documents of every searched space from the
 * shell's opened trees (nothing opened of its own, no key held), then each application's objects by its listing's tag —;
 * every later focus, and every [REVALIDATE_MS] while something is asked, revalidates it: each space's tree revision (a
 * `304` keeps everything) and each listing's tag. The members and the settings sections are documents too; the open
 * Memory's photos are its gallery's contribution. A keystroke cancels the previous answer (the latest wins), which runs off
 * the caller's thread; the matcher's memory clears at [close] and when the documents change. Nothing is written anywhere.
 */
class SearchModel(
    private val door: Door,
    private val kinds: SearchKinds,
    private val words: SearchWords,
    private val requests: OpenRequests,
    private val contributions: SearchContributions,
    private val now: () -> Long,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    /** What the model reads and opens (the shell over a tenant: [me.raddatz.veyq.mobile.navigation.ShellModel.searchModel]). */
    interface Door {
        /** The spaces searched now: the tenant's this device shows (`Hidden`, ADR 0146 decision 10). */
        suspend fun spaces(): List<SpaceRef>

        /** A value derived from the shell's trees as last read and dropped with them (`TenantSession.fromTree`). */
        suspend fun <V : Any> fromTree(key: String, derive: suspend (Map<String, List<OpenNode>>) -> V): V

        /** Whether [scopeId]'s tree moved; where it did, the shell reads its lists anew (a `304`: no). Throws where unread. */
        suspend fun treeMoved(scopeId: String): Boolean

        /** The spaces whose tree did not read (closed, or offline with nothing kept). */
        fun notRead(): Set<String>

        /** Node [node]'s sealed meta opened under its key (core's node-meta opener); null where it does not open. */
        fun openMeta(node: OpenNode): JsonObject?

        /** Whether the account reaches its server: offline no listing is read (ADR 0146 decision 11). */
        fun online(): Boolean

        /** The tenant's members as the shell's roster holds them (kept offline too). */
        fun people(): Collection<Person>

        /** The settings sections the page offers now (`SettingsSection.offered`). */
        suspend fun settings(): List<SettingsSection>

        /** Where the member stands: the active space and the open object. */
        fun where(): Results.SearchContext

        /** The applications switched off in this tenant: their space searches are not asked. */
        fun applicationsOff(): Set<String>

        /** [app]'s objects of one space (`ApplicationSearch.scopeDocuments` with the tenant), or null where unchanged. */
        suspend fun scopeDocuments(
            app: ApplicationSearch, scopeId: String, tag: String?, previous: Map<String, ScopeDocument>, nodes: Map<String, OpenNode>,
            unnamed: (Boolean) -> String,
        ): ScopeDocumentsAnswer?

        /** Open node [nodeId] of [scopeId] where it lives: an object in its application, a folder on Home. */
        fun openNode(scopeId: String, nodeId: String)

        /** Open person [userId]'s profile. */
        fun openPerson(userId: String)
    }

    private val _state = MutableStateFlow(SearchShown())
    val state: StateFlow<SearchShown> = _state.asStateFlow()

    /** The state now. */
    val current: SearchShown get() = _state.value

    /** A watch of [state] for a platform without flows, stopped with [stop]. */
    class Watch internal constructor(private val job: Job) {
        fun stop() = job.cancel()
    }

    /** Calls [listener] with the state now and with each change after (on a background thread). */
    fun watch(listener: (SearchShown) -> Unit): Watch = Watch(scope.launch { state.collect { listener(it) } })

    // ---- the field's own state, changed on one lane (the actions are called from any thread) ----

    @OptIn(ExperimentalCoroutinesApi::class)
    private val lane = Dispatchers.Default.limitedParallelism(1)

    private var text = ""
    private var chips: List<SearchChip> = emptyList()
    private var shown = PAGE
    private var chipNote: Pair<Notice, String>? = null
    private var openSettings: String? = null
    private var openSettingsCount = 0
    @kotlin.concurrent.Volatile private var closed = false
    private val matcher = Query.Matcher()
    private var results: List<SearchDocument> = emptyList()
    private var suggestions: List<Query.ChipSuggestion> = emptyList()

    private fun act(change: suspend () -> Unit) {
        scope.launch(lane) {
            if (closed) return@launch
            openSettings = null
            change()
            answer()
        }
    }

    /** The field gained focus: the first builds the index, every later one revalidates it. */
    fun focus() = act { index(revalidate = indexed) }

    /** The text as typed now. */
    fun text(text: String) = act {
        if (text != this.text) shown = PAGE
        this.text = text
        if (chipNote?.second != text) chipNote = null
    }

    /** Suggestion [index] chosen: its chip set, the text what stays. */
    fun choose(index: Int) = act {
        val s = suggestions.getOrNull(index) ?: return@act
        chips = chips + s.chip
        text = s.text
        shown = PAGE
        chipNote = Notice(Msg.SEARCH_ANNOUNCE_CHIP_ADDED, label(s.chip)) to text
    }

    /** Chip [index] removed. */
    fun removeChip(index: Int) = act {
        val c = chips.getOrNull(index) ?: return@act
        chips = chips.filterIndexed { i, _ -> i != index }
        shown = PAGE
        chipNote = Notice(Msg.SEARCH_ANNOUNCE_CHIP_REMOVED, label(c)) to text
    }

    /** Backspace in an empty field: the last chip removed. */
    fun removeLastChip() = act {
        if (text.isNotEmpty() || chips.isEmpty()) return@act
        val c = chips.last()
        chips = chips.dropLast(1)
        shown = PAGE
        chipNote = Notice(Msg.SEARCH_ANNOUNCE_CHIP_REMOVED, label(c)) to text
    }

    /** *Show more*: [PAGE] more results. */
    fun more() = act { shown += PAGE }

    /**
     * Result [index] opened where it lives (ADR 0146 decision 8): a node through the shell, a person on their profile, a
     * settings section on the settings page ([SearchShown.openSettings]), the open Memory's photo in its lightbox in place,
     * and a photo another space's listing found by opening its Memory with a request its gallery takes.
     */
    fun open(index: Int) = act {
        val doc = results.getOrNull(index) ?: return@act
        when {
            doc.kind == SearchDocument.PERSON -> door.openPerson(doc.id)
            doc.kind == SearchDocument.SETTINGS -> {
                openSettings = doc.id
                openSettingsCount++
            }
            doc.opensItself -> requests.request(OpenRequests.Request(doc.scopeId, doc.nodeId, doc.id))
            doc.id == doc.nodeId -> door.openNode(doc.scopeId, doc.nodeId)
            else -> {
                requests.request(OpenRequests.Request(doc.scopeId, doc.nodeId, doc.id))
                door.openNode(doc.scopeId, doc.nodeId)
            }
        }
    }

    /** The field was left: the text and the chips go, the matcher forgets, nothing is revalidated any more. */
    fun close() = act {
        text = ""
        chips = emptyList()
        shown = PAGE
        chipNote = null
        matcher.clear()
        revalidating?.cancel()
        revalidating = null
    }

    /** The tenant closed (a switch, a lock, a sign-out): everything is dropped and nothing runs any more. */
    fun dispose() {
        closed = true
        scope.cancel()
        objects.clear()
        nodeDocs = emptyMap()
        docs = null
    }

    // ---- the index ----

    /** The node documents of one read of the trees, held with it (`fromTree`): no key, names and ids only. */
    private class NodeIndex(val trees: Map<String, List<OpenNode>>, val docs: Map<String, List<SearchDocument>>)

    private class ObjectsEntry(val tag: String?, val objects: Map<String, ScopeDocument>, val documents: List<SearchDocument>, val placedIn: Any?)

    private val indexLock = Mutex()
    private var indexed = false
    private var indexing: Job? = null
    private var revalidating: Job? = null
    private val facets = HashMap<String, Documents.Facets>()
    private var nodeDocs: Map<String, List<SearchDocument>> = emptyMap()
    private var trees: Map<String, List<OpenNode>> = emptyMap()

    /** By space, then by application. */
    private val objects = HashMap<String, HashMap<String, ObjectsEntry>>()
    private var spaces: List<SpaceRef> = emptyList()
    private var loading: Set<String> = emptySet()
    private var loadingSince: Long? = null
    private var failed: Set<String> = emptySet()
    private var offlineUnread = false
    private var settingsRead: List<SettingsSection>? = null
    private var revision = 0L

    private fun index(revalidate: Boolean) {
        indexing?.takeIf { it.isActive }?.let { return }
        indexing = scope.launch { indexLock.withLock { build(revalidate) } }
    }

    /** One walk over every searched space (see the class): the nodes first, then each application's objects by space. */
    private suspend fun build(revalidate: Boolean) {
        val online = door.online()
        val listed = runCatching { door.spaces() }.getOrDefault(spaces)
        val notRead = door.notRead()
        withContext(lane) {
            spaces = listed
            loading = listed.map { it.scopeId }.toSet()
            loadingSince = now()
            failed = emptySet()
        }
        if (settingsRead == null) settingsRead = runCatching { door.settings() }.getOrNull()
        val moved = HashSet<String>()
        if (revalidate && online) {
            for (s in listed) if (s.scopeId !in notRead) {
                currentCoroutineContext().ensureActive()
                if (runCatching { door.treeMoved(s.scopeId) }.getOrDefault(false)) moved += s.scopeId
            }
        }
        val read = door.fromTree(NODE_DOCUMENTS) { t ->
            NodeIndex(t, t.mapValues { (scopeId, nodes) -> Documents.nodeDocuments(scopeId, nodes, kinds, now(), door::openMeta, facets) })
        }
        val nowTrees = read.trees
        withContext(lane) {
            trees = nowTrees
            nodeDocs = read.docs
            facets.keys.retainAll(nodeDocs.values.flatMap { d -> d.map { it.id } }.toSet())
            revision++
        }
        answerOnLane()
        val notSearched = HashSet<String>()
        var unreadOffline = false
        val off = door.applicationsOff()
        val unnamed = mapOf(false to words.say(Msg.SEARCH_KIND_PHOTO), true to words.say(Msg.SEARCH_KIND_VIDEO))
        for (s in listed) {
            currentCoroutineContext().ensureActive()
            if (s.scopeId in notRead) {
                notSearched += s.scopeId
                withContext(lane) { objects.remove(s.scopeId) }
            } else {
                val nodes = nowTrees[s.scopeId].orEmpty()
                val byId = nodes.associateBy { it.id }
                for ((app, search) in kinds.applications) {
                    if (app in off) continue
                    val prev = withContext(lane) { objects[s.scopeId]?.get(app) }
                    if (!online) {
                        if (prev == null) unreadOffline = true
                        else if (prev.placedIn !== nowTrees) place(s.scopeId, app, prev.tag, prev.objects, byId, nowTrees)
                        continue
                    }
                    try {
                        val answer = door.scopeDocuments(search, s.scopeId, prev?.tag, prev?.objects.orEmpty(), byId) { unnamed.getValue(it) }
                        if (answer == null) {
                            if (prev == null) throw IllegalStateException("objects_unchanged_without_entry")
                            if (prev.placedIn !== nowTrees) place(s.scopeId, app, prev.tag, prev.objects, byId, nowTrees)
                        } else {
                            place(s.scopeId, app, answer.tag, answer.documents.associateBy { it.id }, byId, nowTrees)
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // a listing that does not read keeps the space's nodes and names it as not searched
                        notSearched += s.scopeId
                    }
                }
            }
            withContext(lane) { loading = loading - s.scopeId }
            answerOnLane()
        }
        withContext(lane) {
            // a space no longer searched (hidden, left) loses its objects
            val ids = listed.map { it.scopeId }.toSet()
            objects.keys.retainAll(ids)
            failed = notSearched
            offlineUnread = unreadOffline
            loading = emptySet()
            loadingSince = null
            indexed = true
            revision++
        }
        answerOnLane()
    }

    private suspend fun place(
        scopeId: String, app: String, tag: String?, objs: Map<String, ScopeDocument>, byId: Map<String, OpenNode>, placedIn: Any?,
    ) {
        val docs = Documents.scopeDocuments(scopeId, objs.values.toList(), byId)
        withContext(lane) {
            objects.getOrPut(scopeId) { HashMap() }[app] = ObjectsEntry(tag, objs, docs, placedIn)
            revision++
        }
    }

    // ---- the documents (on the lane) ----

    private var docs: SearchDocuments? = null
    private var docsOf: List<Any?> = emptyList()
    private var settingsDocs: List<SearchDocument> = emptyList()
    private var settingsDocsOf: List<SettingsSection>? = null

    /** Every document now: the index's, the page's contribution (winning over the same object), the members and the sections. */
    private suspend fun documents(): SearchDocuments {
        val people = door.people()
        val contribution = contributions.current.value
        val sections = settingsRead
        val inputs = listOf(revision, people, contribution, sections, spaces)
        docs?.let { d -> if (inputs.size == docsOf.size && inputs.indices.all { inputs[it] === docsOf[it] || inputs[it] == docsOf[it] }) return d }
        if (sections !== settingsDocsOf) {
            settingsDocs = sections.orEmpty().map { section ->
                SearchDocument.of(
                    scopeId = "", kind = SearchDocument.SETTINGS, id = section.id, name = words.say(section.word), opensItself = true,
                    keywords = words.say(section.keywords).split(',').map { it.trim() }.filter { it.isNotEmpty() },
                    subtitle = words.say(Msg.SEARCH_SETTINGS), icon = SETTINGS_ICON,
                )
            }
            settingsDocsOf = sections
        }
        val visible = spaces.map { it.scopeId }.toSet()
        val indexed = ArrayList<SearchDocument>()
        for ((scopeId, list) in nodeDocs) if (scopeId in visible) indexed += list
        for ((scopeId, byApp) in objects) if (scopeId in visible) for (e in byApp.values) indexed += e.documents
        val own = contribution?.takeIf { it.scopeId in visible }?.let { c ->
            val nodes = trees[c.scopeId].orEmpty()
            val byId = nodes.associateBy { it.id }
            val at = byId[c.nodeId]?.let { Documents.placeWithin(it, byId) } ?: Documents.NodePlace(emptyList(), listOf(c.nodeId))
            c.documents.map {
                // a medium without a name of its own is named by its kind's word, as the space's search names it
                val name = it.name.ifEmpty { documentWord(it.kind)?.let { w -> words.say(w) } ?: "" }
                SearchDocument.of(
                    scopeId = c.scopeId, kind = it.kind, id = it.id, name = name, nodeId = c.nodeId, revision = it.revision,
                    path = at.names, pathIds = at.ids, facets = it.facets, opensItself = true, icon = it.icon,
                )
            }
        }.orEmpty()
        // the page's own documents win over the same object a space's search found
        val mine = own.mapTo(HashSet()) { "${it.scopeId}:${it.id}" }
        val content = if (mine.isEmpty()) indexed else indexed.filter { it.id == it.nodeId || "${it.scopeId}:${it.id}" !in mine }
        val persons = people.map { p ->
            SearchDocument.of(
                scopeId = "", kind = SearchDocument.PERSON, id = p.userId, name = p.displayName, opensItself = true,
                aliases = listOf("~${p.username}"), subtitle = "~${p.username}", icon = PERSON_ICON,
            )
        }
        return SearchDocuments(content + own + persons + settingsDocs).also {
            docs = it
            docsOf = inputs
            matcher.clear()
        }
    }

    // ---- the answer ----

    private var answering: Job? = null

    private suspend fun answerOnLane() = withContext(lane) { answer() }

    /** The state anew for the text and chips now: the previous answer cancelled, this one run off the lane. */
    private fun answer() {
        answering?.cancel()
        val text = text
        val chips = chips
        val shown = shown
        val note = chipNote
        val settings = openSettings
        val settingsCount = openSettingsCount
        answering = scope.launch(lane) {
            val all = documents()
            val asked = JsText.trim(text).isNotEmpty() || chips.isNotEmpty()
            val where = door.where()
            // matched on the lane, off the caller's thread: the matcher is one field's, never asked twice at once
            val found = if (asked) Results.searchResults(all, SearchQuery(chips, text), where, shown, matcher) else null
            ensureActive()
            val offered = Query.suggestChips(text, chips, sources(all))
            ensureActive()
            results = found?.results.orEmpty()
            suggestions = offered
            revalidate(asked)
            _state.value = shown(text, chips, asked, found, offered, note, settings, settingsCount)
            scheduleLoading(asked)
        }
    }

    private suspend fun sources(all: SearchDocuments): Query.ChipSources {
        val spaceLabels = spaces.map { it.scopeId to say(ShellWords.space(it)) }
        val people = door.people().map { it.userId to it.displayName }
        val kindWords = LinkedHashMap<String, MutableList<String>>()
        fun add(word: String, kind: String) {
            kindWords.getOrPut(word) { ArrayList() }.let { if (kind !in it) it += kind }
        }
        add(words.say(Msg.SEARCH_KIND_PERSON), SearchDocument.PERSON)
        add(words.say(Msg.SEARCH_KIND_SETTING), SearchDocument.SETTINGS)
        for (k in kinds.kinds) {
            val ks = kinds.kind(k) ?: continue
            add(words.say(ks.word), k)
            for (d in ks.documents) add(words.say(d.word), d.kind)
        }
        val months = MONTHS.mapIndexed { i, msg -> listOf(words.say(msg), ENGLISH_MONTHS[i]).distinct() }
        return Query.ChipSources(all, spaceLabels, people, kindWords.map { it.key to it.value }, months)
    }

    private suspend fun say(p: Piece): String = when (p) {
        is Piece.Plain -> p.text
        is Piece.Said -> words.say(p.notice.msg)
        else -> ""
    }

    private fun spaceOf(scopeId: String): SpaceRef? = spaces.firstOrNull { it.scopeId == scopeId }

    private fun shown(
        text: String, chips: List<SearchChip>, asked: Boolean, found: Results.SearchResults?, offered: List<Query.ChipSuggestion>,
        note: Pair<Notice, String>?, settings: String?, settingsCount: Int,
    ): SearchShown {
        val t = Query.matchedText(text)
        val rows = found?.results.orEmpty().map { d ->
            val reason = Query.reasonKeyword(d, t)
            ResultShown(
                id = "${d.scopeId}:${d.kind}:${d.id}", icon = iconOf(d), name = parts(Query.nameParts(d.name, t)), line = line(d),
                reason = reason?.let { parts(Query.nameParts(it, t)) },
            )
        }
        val total = found?.total ?: 0
        val status = ArrayList<Notice>()
        if (asked && loading.isNotEmpty() && loadingLong) status += Notice(Msg.SEARCH_LOADING, loading.size)
        if (failed.isNotEmpty()) {
            status += Notice(Msg.SEARCH_NOT_SEARCHED, Joined(failed.mapNotNull { spaceOf(it) }.map { spaceWord(it) }, ", "))
        }
        if (offlineUnread) status += Notice(Msg.SEARCH_OFFLINE_PHOTOS)
        if (asked && total == 0 && offered.isEmpty() && loading.isEmpty()) {
            status += Notice(Msg.SEARCH_EMPTY)
            status += Notice(Msg.SEARCH_EXPLAINED)
        }
        val announcement = ArrayList<Notice>()
        note?.let { announcement += it.first }
        if (asked) announcement += if (total == 0) Notice(Msg.SEARCH_ANNOUNCE_NONE) else Notice(Msg.SEARCH_ANNOUNCE_RESULTS, total)
        return SearchShown(
            text = text, chips = chips.map(::chipShown), asked = asked,
            suggestions = offered.map { s ->
                val c = chipShown(s.chip)
                SuggestionShown("chip:${c.id}", c.word, c.label, c.icon, where(s.chip))
            },
            results = rows, more = minOf(total - rows.size, PAGE).coerceAtLeast(0), total = total, status = status,
            announcement = announcement, openSettings = settings, openSettingsCount = settingsCount,
        )
    }

    private fun parts(p: List<Query.NamePart>) = p.map { PartShown(it.text, it.strong) }

    /** A space as a row names it: the personal and the group space by their words, another by its own name. */
    private fun spaceWord(space: SpaceRef): Any = when (val p = ShellWords.space(space)) {
        is Piece.Said -> p.notice
        is Piece.Plain -> p.text
        else -> space.name
    }

    /**
     * What a row says under its name (the web's `secondLine`): its own line (a person's handle, a section's *Settings*), or
     * its space and its path — the space always —, a photo's day after it; the open Memory's photo its space and its day.
     */
    private fun line(d: SearchDocument): List<Piece> {
        d.subtitle?.let { return listOf(Piece.Plain(it)) }
        val out = ArrayList<Piece>()
        spaceOf(d.scopeId)?.let { out += ShellWords.space(it) }
        if (!d.opensItself && d.path.isNotEmpty()) out += Piece.Plain(d.path.joinToString(" / "))
        d.facets.capturedAt?.let { out += Piece.Day(LocalDays.isoOf(it)) }
        return out
    }

    private fun where(chip: SearchChip): List<Piece> = when (chip) {
        is SearchChip.In -> listOfNotNull(spaceOf(chip.scopeId)?.let { ShellWords.space(it) }, chip.path.takeIf { it.isNotEmpty() }?.let { Piece.Plain(it.joinToString(" / ")) })
        else -> emptyList()
    }

    private fun documentWord(kind: String): Msg? =
        kinds.kinds.firstNotNullOfOrNull { k -> kinds.kind(k)?.documents?.firstOrNull { it.kind == kind }?.word }

    private fun iconOf(d: SearchDocument): String =
        d.icon ?: kinds.kind(d.kind)?.icon ?: kinds.kinds.firstNotNullOfOrNull { k -> kinds.kind(k)?.documents?.firstOrNull { it.kind == d.kind }?.icon } ?: FILE_ICON

    private fun label(chip: SearchChip): Any = when (chip) {
        is SearchChip.Year -> chip.year.toString()
        is SearchChip.Month -> Notice(Msg.SEARCH_MONTH_YEAR, Notice(MONTHS[chip.month - 1]), chip.year.toString())
        is SearchChip.Space -> chip.label
        is SearchChip.Place -> chip.label
        is SearchChip.Person -> chip.label
        is SearchChip.Kind -> chip.label
        is SearchChip.In -> chip.label
    }

    private fun chipShown(chip: SearchChip): ChipShown {
        val (word, icon) = when (chip) {
            is SearchChip.Year -> Msg.SEARCH_CHIP_YEAR to "calendar"
            is SearchChip.Month -> Msg.SEARCH_CHIP_MONTH to "calendar"
            is SearchChip.Place -> Msg.SEARCH_CHIP_PLACE to "map-marker"
            is SearchChip.Person -> Msg.SEARCH_CHIP_PERSON to "user"
            is SearchChip.Space -> Msg.SEARCH_CHIP_SPACE to "users"
            is SearchChip.Kind -> Msg.SEARCH_CHIP_KIND to "tag"
            // an In: chip draws its node's kind's icon (the web's chipIcon)
            is SearchChip.In -> Msg.SEARCH_CHIP_IN to (trees[chip.scopeId]?.firstOrNull { it.id == chip.nodeId }?.kind?.let { kinds.kind(it)?.icon } ?: "folder-open")
        }
        val label = when (val l = label(chip)) {
            is Notice -> Piece.Said(l)
            else -> Piece.Plain(l.toString())
        }
        return ChipShown(Query.chipKey(chip), word, label, icon)
    }

    // ---- what runs while something is asked ----

    private var loadingLong = false
    private var loadingTimer: Job? = null

    /** A space loading is said only once the list has waited [LOADING_DELAY_MS] on it (a calm focus, uc step 2). */
    private fun scheduleLoading(asked: Boolean) {
        val waiting = asked && loading.isNotEmpty()
        if (!waiting) {
            loadingTimer?.cancel()
            loadingTimer = null
            loadingLong = false
            return
        }
        if (loadingTimer != null || loadingLong) return
        loadingTimer = scope.launch(lane) {
            delay(LOADING_DELAY_MS)
            loadingLong = true
            loadingTimer = null
            answer()
        }
    }

    /** While something is asked, every space is revalidated every [REVALIDATE_MS]. */
    private fun revalidate(asked: Boolean) {
        if (!asked || indexing == null) {
            revalidating?.cancel()
            revalidating = null
            return
        }
        if (revalidating?.isActive == true) return
        revalidating = scope.launch(lane) {
            while (isActive) {
                delay(REVALIDATE_MS)
                index(revalidate = true)
            }
        }
    }

    /** Waits until the index and the answer in flight are done (a test's, a measurement's). */
    internal suspend fun settled() {
        while (true) {
            indexing?.join()
            val a = withContext(lane) { answering }
            a?.join()
            val again = withContext(lane) { indexing?.isActive == true || (answering != null && answering !== a) }
            if (!again) return
        }
    }

    init {
        // the page's contribution and the members move the documents: the answer follows them
        scope.launch { contributions.current.drop(1).collect { withContext(lane) { if (!closed) answer() } } }
    }

    companion object {
        /** How many results the list shows at first, and how many more each *Show more* adds (the web's `PAGE`). */
        const val PAGE = 10

        /** How often a list that asks something revalidates every space (the web's `REVALIDATE_MS`). */
        const val REVALIDATE_MS = 20_000L

        /** How long the list waits on a loading space before it says so (the web's `LOADING_DELAY_MS`). */
        const val LOADING_DELAY_MS = 300L

        /** The key the node documents are held under on the tree (`TenantSession.fromTree`). */
        internal const val NODE_DOCUMENTS = "search.nodeDocuments"

        const val PERSON_ICON = "user"
        const val SETTINGS_ICON = "settings"
        const val FILE_ICON = "file"

        private val MONTHS = listOf(
            Msg.SEARCH_MONTH_1, Msg.SEARCH_MONTH_2, Msg.SEARCH_MONTH_3, Msg.SEARCH_MONTH_4, Msg.SEARCH_MONTH_5, Msg.SEARCH_MONTH_6,
            Msg.SEARCH_MONTH_7, Msg.SEARCH_MONTH_8, Msg.SEARCH_MONTH_9, Msg.SEARCH_MONTH_10, Msg.SEARCH_MONTH_11, Msg.SEARCH_MONTH_12,
        )

        /** The months in English too, whatever the UI's language (the web offers both). */
        private val ENGLISH_MONTHS = listOf(
            "January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December",
        )
    }
}
