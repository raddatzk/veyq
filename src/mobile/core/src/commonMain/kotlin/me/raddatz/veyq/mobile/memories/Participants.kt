package me.raddatz.veyq.mobile.memories

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.raddatz.veyq.mobile.crypto.nfkd
import me.raddatz.veyq.mobile.memories.place.MemoryFacts
import me.raddatz.veyq.mobile.session.NodeMetaWrite
import me.raddatz.veyq.mobile.session.Person
import me.raddatz.veyq.mobile.session.TenantSession

/**
 * Who was there (uc.memory.participants, ADR 0083; ADR 0144 decision 1): the `participants` sub-object of a Memory's
 * sealed node meta, `{userIds}` — **opaque user ids only**, never a name: the list sits in the blob a share serves
 * unchanged, so ids are what keeps the residual harmless (ADR 0083 decision 4). A port of `memoryParticipants.ts` and of
 * `nodeMeta.ts`' `editParticipantsOf`.
 *
 * The write is a read-modify-write through [NodeMetaWrite] with **the merge as its edit**, not `MemoryFacts.editField`:
 * the list is a set two members may extend at once, and a same-field conflict would drop a peer's addition or make the
 * editor redo its pick. On a `409 stale_revision` the blob is read anew and merged ([merge]): what is stored united with
 * what the editor wants, except that an id the editor REMOVED stays removed (uc.memory.participants A2).
 */
object Participants {
    /** The node meta's field. */
    const val FIELD = "participants"

    /** At most this many: `wrappedMeta` is limited to 8192 base64 characters (ADR 0083 decision 3a; the web's `MAX_PARTICIPANTS`). */
    const val MAX = 100

    /**
     * A loosely typed `participants` value's user ids (`normalizeParticipants`): strings only, non-empty, unique (first
     * wins), capped at [MAX]. Anything else is no participants — a blob that does not fit is never trusted.
     */
    fun normalize(raw: JsonElement?): List<String> {
        val ids = ((raw as? JsonObject)?.get("userIds") as? JsonArray) ?: return emptyList()
        val out = LinkedHashSet<String>()
        for (id in ids) {
            val s = (id as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            if (s.isEmpty() || !out.add(s)) continue
            if (out.size >= MAX) break
        }
        return out.toList()
    }

    /** A node meta's participants (best-effort: none where absent or malformed). */
    fun read(meta: JsonObject): List<String> = normalize(meta[FIELD])

    /**
     * The merge on a conflict (`mergeParticipants`): [stored] (what the server holds now) united with [wanted] (what the
     * editor wants), minus what the editor removed ([openedWith] − [wanted]). A peer's addition survives; the editor's own
     * removal wins for the id it removed; the order is stored first, then the editor's new ones. Capped at [MAX].
     */
    fun merge(stored: List<String>, wanted: List<String>, openedWith: List<String>): List<String> {
        val wantedSet = wanted.toSet()
        val removed = openedWith.filterNot { it in wantedSet }.toSet()
        val out = LinkedHashSet<String>()
        for (id in stored + wanted) if (id !in removed) out += id
        return out.take(MAX)
    }

    /**
     * The edit of the `participants` sub-object (`editParticipantsOf`): [merge] handed the stored ids that are non-empty
     * strings, de-duplicated, in stored order; its answer the same as stored → no write (null); empty → the sub-object
     * removed, every sibling kept; otherwise `userIds` replaced and every other field of the sub-object kept (a value that is
     * no object — an array, a string — starts a clean one).
     */
    fun edit(merge: (stored: List<String>) -> List<String>): suspend (JsonObject) -> JsonObject? = { meta ->
        val raw = meta[FIELD]
        val sub = raw as? JsonObject
        val stored = ((sub?.get("userIds") as? JsonArray) ?: JsonArray(emptyList()))
            .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf { s -> s.isNotEmpty() } }
        val next = merge(stored.distinct())
        when {
            // compared with the list as stored, duplicates included: a merge that only cleans them up still writes
            next == stored -> null
            next.isEmpty() -> JsonObject(meta - FIELD)
            else -> {
                val fields = LinkedHashMap<String, JsonElement>(sub ?: emptyMap())
                fields["userIds"] = JsonArray(next.map(::JsonPrimitive))
                JsonObject(LinkedHashMap<String, JsonElement>(meta).also { it[FIELD] = JsonObject(fields) })
            }
        }
    }

    /** The editor's save as an edit: [wanted] over what the editor opened with ([openedWith]), merged with what is stored. */
    fun editTo(wanted: List<String>, openedWith: List<String>) = edit { stored -> merge(stored, wanted, openedWith) }

    /**
     * The participants written over [send] (the seam a test plays a server through): [wanted], the editor having opened
     * with [openedWith], through [NodeMetaWrite.write] — a `409` re-runs the merge, never a conflict.
     */
    suspend fun write(
        base: NodeMetaWrite.State,
        open: (String?) -> JsonObject,
        seal: (JsonObject) -> String,
        wanted: List<String>,
        openedWith: List<String>,
        send: suspend (wrappedMeta: String, metaRevision: Long) -> NodeMetaWrite.Answer,
    ): Pair<MemoryFacts.Outcome, NodeMetaWrite.State> = MemoryFacts.write(base, open, seal, editTo(wanted, openedWith), send)

    /** Memory [nodeId]'s participants set to [wanted] on the tenant's server, the editor having opened with [openedWith]. */
    suspend fun set(tenant: TenantSession, scopeId: String, nodeId: String, wanted: List<String>, openedWith: List<String>): MemoryFacts.Outcome =
        MemoryFacts.onServer(tenant, scopeId, nodeId) { _, _ -> editTo(wanted, openedWith) }

    /** The `participants` a Memory is born with where its maker ticked *I was there* (ADR 0144 decision 2). */
    fun born(userId: String): Pair<String, JsonObject> = FIELD to JsonObject(mapOf("userIds" to JsonArray(listOf(JsonPrimitive(userId)))))

    // ---- the header's people line ----

    /** One participant as the header names them: [name] null for a former member (their words), or while names read. */
    data class Entry(val userId: String, val name: String?, val former: Boolean, val avatarRevision: Int = 0)

    /**
     * The header's people line (ADR 0144 decision 1): everyone listed ([people], in order), the avatars drawn ([avatars]:
     * all of up to three; the first two of more, [more] in the third slot as "+N"), [named] false while the members did not
     * read (a name then is none, and nobody is called former). [add] is the *Add participants* chip of an empty list where
     * [edits] holds.
     */
    data class Line(
        val people: List<Entry>,
        val avatars: List<Entry>,
        val more: Int,
        val named: Boolean,
        val edits: Boolean,
    ) {
        val add: Boolean get() = people.isEmpty() && edits
    }

    /** How many avatars the line draws before "+N" takes the third slot. */
    const val AVATARS = 3

    /**
     * The line for [ids] named by the scope's current [members] (null: not read); null where nothing is shown — nobody
     * listed and no right to edit. An id no member holds is a former member (A1, A4).
     */
    fun line(ids: List<String>, members: List<Person>?, edits: Boolean): Line? {
        if (ids.isEmpty() && !edits) return null
        val byId = members?.associateBy { it.userId }
        val people = ids.map { id ->
            val p = byId?.get(id)
            Entry(id, p?.displayName, former = byId != null && p == null, avatarRevision = p?.avatarRevision ?: 0)
        }
        val avatars = if (people.size <= AVATARS) people else people.take(AVATARS - 1)
        return Line(people, avatars, people.size - avatars.size, members != null, edits)
    }

    /** A name as the picker's search compares it (the web's `fold`): decomposed, its marks dropped, lower-cased, `ß` as `ss`. */
    fun fold(s: String): String = nfkd(s).filterNot { it in '̀'..'ͯ' }.lowercase().replace("ß", "ss")
}

/**
 * The participants picker (ADR 0144 decision 1; the web's `MemoryParticipantsDialog.vue` with `useMemoryParticipants`):
 * the scope's CURRENT members ([State.members], `TenantSession.scopeMembers`) as ticks, and above them any id already
 * listed that no member holds any more ([State.former]), ticked until the member unticks it — then it is gone from the
 * pick. **Only an id the members' list returned can be ticked**: the list is sealed, so the server cannot check the ids
 * and the client limits the pick. At [Participants.MAX] no one more is ticked ([State.full]). The search hides rows only;
 * the pick is untouched. [save] writes the pick through [Participants.set]'s merge.
 */
class ParticipantsPicker internal constructor(
    /** The ids listed when the picker opened: its first pick, and what a removal is measured against. */
    private val openedWith: List<String>,
    private val members: suspend () -> List<Person>?,
    private val write: suspend (wanted: List<String>, openedWith: List<String>) -> MemoryFacts.Outcome,
) {
    data class State(
        /** The scope's current members; null while they read or where they did not ([failed]). */
        val members: List<Person>?,
        val failed: Boolean,
        /** The pick, in the order ticked. */
        val pick: List<String>,
        val query: String = "",
        val saving: Boolean = false,
        /** The last save did not land; null otherwise. */
        val said: MemoryFacts.Outcome? = null,
        /** Saved: the platform closes the picker. */
        val saved: Boolean = false,
    ) {
        /** Picked ids no current member holds: former members, listed above the members until unticked. */
        val former: List<String> get() = members?.let { m -> pick.filter { id -> m.none { it.userId == id } } } ?: emptyList()

        /** At the cap: no one more is ticked (the web's `full` words). */
        val full: Boolean get() = pick.size >= Participants.MAX

        /** The members the search shows (by display name, any case and accent). */
        val visible: List<Person>
            get() {
                val q = Participants.fold(query.trim())
                return members.orEmpty().filter { q.isEmpty() || Participants.fold(it.displayName).contains(q) }
            }

        /** The search hides every member. */
        val noMatches: Boolean get() = !members.isNullOrEmpty() && visible.isEmpty()

        /** Nobody to choose from. */
        val nobody: Boolean get() = members?.isEmpty() == true && former.isEmpty()

        fun ticked(userId: String): Boolean = userId in pick

        /** Whether [userId]'s tick may change now: a member, or a former one still picked; never past the cap. */
        fun togglable(userId: String): Boolean =
            ticked(userId) || (!full && members?.any { it.userId == userId } == true)

        /** Save is offered once the members read. */
        val canSave: Boolean get() = members != null && !saving
    }

    private val _state = MutableStateFlow(State(null, false, openedWith))
    val state: StateFlow<State> = _state.asStateFlow()
    val current: State get() = _state.value

    /** The members read (again, after [State.failed]: *Try again*). */
    suspend fun load() {
        _state.update { it.copy(failed = false) }
        val read = runCatching { members() }.getOrNull()
        _state.update { if (read == null) it.copy(failed = true) else it.copy(members = read, failed = false) }
    }

    fun search(query: String) = _state.update { it.copy(query = query) }

    /** [userId] ticked or unticked; refused (false) for an id the members' list did not return, or past the cap. */
    fun toggle(userId: String): Boolean {
        var done = false
        _state.update { s ->
            when {
                s.ticked(userId) -> s.copy(pick = s.pick - userId).also { done = true }
                s.togglable(userId) -> s.copy(pick = s.pick + userId).also { done = true }
                else -> s
            }
        }
        return done
    }

    /** The pick written ([Participants.set]: merged with a peer's write meanwhile). */
    suspend fun save(): MemoryFacts.Outcome? {
        val s = current
        if (!s.canSave) return null
        _state.update { it.copy(saving = true, said = null) }
        val out = runCatching { write(s.pick, openedWith) }.getOrElse { MemoryFacts.Outcome.Refused(-1, null) }
        val ok = out == MemoryFacts.Outcome.Written || out == MemoryFacts.Outcome.Unchanged
        _state.update { it.copy(saving = false, saved = ok, said = if (ok) null else out) }
        return out
    }
}
