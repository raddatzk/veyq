package me.raddatz.veyq.mobile.memories.place

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.raddatz.veyq.mobile.map.GeoLevel
import me.raddatz.veyq.mobile.map.Geocoder
import me.raddatz.veyq.mobile.map.LatLng
import me.raddatz.veyq.mobile.map.Viewport

/** What an editor's save came to: saved, re-opened on a peer's value ([current]), or refused. */
sealed interface EditorSave {
    data object Saved : EditorSave
    data class Reopen(val current: MemoryFacts.Facts) : EditorSave
    data object Failed : EditorSave
}

private fun saveOf(o: MemoryFacts.Outcome): EditorSave = when (o) {
    MemoryFacts.Outcome.Written, MemoryFacts.Outcome.Unchanged -> EditorSave.Saved
    is MemoryFacts.Outcome.Conflict -> EditorSave.Reopen(o.current)
    else -> EditorSave.Failed
}

/**
 * A Memory's date editor (the web's `MemoryDateEditor.vue`; ADR 0143 D5), each platform drawing it with its own date
 * picker: the four modes over the stored date, `auto` previewing the live range from the media. A range whose `to` is
 * before its `from` is said and not saved (E4); `auto` saves the live range, so a list shows it at once. A save a peer's
 * date beat re-opens on the peer's ([EditorSave.Reopen]).
 */
class DateEditor(stored: MemoryDate?, val autoRange: MemoryDate, private val save: suspend (MemoryDate) -> MemoryFacts.Outcome) {
    data class State(val mode: MemoryDate.Mode, val from: String, val to: String) {
        /** A range whose end is before its start: said, never saved. */
        val rangeInvalid: Boolean get() = mode == MemoryDate.Mode.RANGE && from.isNotEmpty() && to.isNotEmpty() && !MemoryDate.isValidRange(from, to)

        val canSave: Boolean get() = when (mode) {
            MemoryDate.Mode.AUTO -> true
            MemoryDate.Mode.SINGLE, MemoryDate.Mode.ONGOING -> from.isNotEmpty()
            MemoryDate.Mode.RANGE -> MemoryDate.isValidRange(from, to)
        }
    }

    private val _state = MutableStateFlow(stateOf(stored))
    val state: StateFlow<State> = _state.asStateFlow()
    val current: State get() = _state.value

    private fun stateOf(d: MemoryDate?) = State(d?.mode ?: MemoryDate.Mode.AUTO, d?.from.orEmpty(), d?.to.orEmpty())

    fun mode(mode: MemoryDate.Mode) = _state.update { it.copy(mode = mode) }

    /** The start day (`yyyy-MM-dd`, the platform's picker's), or empty. */
    fun from(day: String) = _state.update { it.copy(from = day) }

    fun to(day: String) = _state.update { it.copy(to = day) }

    /** The date the fields hold, or null where it may not be saved (`buildDate`). */
    fun build(): MemoryDate? {
        val s = current
        if (!s.canSave) return null
        return when (s.mode) {
            MemoryDate.Mode.AUTO -> autoRange.copy(mode = MemoryDate.Mode.AUTO)
            MemoryDate.Mode.SINGLE -> MemoryDate(MemoryDate.Mode.SINGLE, s.from, s.from)
            MemoryDate.Mode.RANGE -> MemoryDate(MemoryDate.Mode.RANGE, s.from, s.to)
            MemoryDate.Mode.ONGOING -> MemoryDate(MemoryDate.Mode.ONGOING, s.from)
        }
    }

    /** Saves what the fields hold; on a peer's date written meanwhile the fields re-open on it. Null where nothing may be saved. */
    suspend fun save(): EditorSave? {
        val date = build() ?: return null
        val r = saveOf(save(date))
        if (r is EditorSave.Reopen) _state.value = stateOf(r.current.date)
        return r
    }
}

/**
 * A Memory's place editor (the web's `MemoryLocationEditor.vue`; uc.memory.place; ADR 0143 D5), each platform drawing its
 * map with a fixed centre pin over it and reporting the centre as it is framed ([frame]):
 *
 * - **`auto`** follows the photos' medoid; a pan **by the member** takes over (`fixed`), a frame the member did not make
 *   never makes a position (the opening view of a Memory without one is no place, E3); *Follow the photos* ([followAgain])
 *   returns to `auto`.
 * - **The name** comes from the reverse lookup [RESOLVE_DELAY_MS] after the map settles, at the chosen or the default
 *   level ([pickLevel], the level picker's options are [State.levels]); typing ([typeName]) makes it `manual` and freezes
 *   it; [toggleNameMode] switches between the two.
 * - **Without a map** ([State.withMap] false: none served, or this device's consent not given) it is the name field alone,
 *   no request leaving the device; [mapsTurnedOn] when the consent is given in it.
 * - [build] the place the fields hold, [save] it; *Remove place* is [remove] — the date and the cover kept either way.
 */
class PlaceEditor(
    private val stored: MemoryLocation?,
    /** The medoid of the Memory's photos' GPS (the `auto` position), or null. */
    val medoid: LatLng?,
    /** Every photo's GPS point: the map's dots and the `auto` framing. */
    val points: List<LatLng>,
    private val names: PlaceNames?,
    private val save: suspend (MemoryLocation?) -> MemoryFacts.Outcome,
    private val withMapNow: () -> Boolean = { names?.maps?.available == true },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    data class State(
        val withMap: Boolean,
        val derivedMode: MemoryLocation.DerivedMode,
        val derived: LatLng?,
        val name: String,
        val nameSource: MemoryLocation.NameSource,
        val levels: List<GeoLevel> = emptyList(),
        val chosenLevel: GeoLevel.Key? = null,
    ) {
        val canSave: Boolean get() = name.isNotBlank()

        /** The level picker is offered: the name is the map's, and the lookup answered levels. */
        val showLevelPicker: Boolean get() = nameSource == MemoryLocation.NameSource.GEOCODED && levels.isNotEmpty()
    }

    private val canGeocode: Boolean get() = current.withMap && names?.available == true

    private val _state: MutableStateFlow<State>
    val state: StateFlow<State>
    val current: State get() = _state.value

    init {
        val withMap = withMapNow()
        val mode = stored?.derivedMode ?: if (medoid != null || !withMap) MemoryLocation.DerivedMode.AUTO else MemoryLocation.DerivedMode.FIXED
        _state = MutableStateFlow(
            State(
                withMap = withMap,
                derivedMode = mode,
                derived = stored?.derived ?: if (mode == MemoryLocation.DerivedMode.AUTO) medoid else null,
                name = stored?.name.orEmpty(),
                nameSource = stored?.nameSource
                    ?: if (withMap && names?.available == true) MemoryLocation.NameSource.GEOCODED else MemoryLocation.NameSource.MANUAL,
                chosenLevel = stored?.nameAnchor?.level,
            ),
        )
        state = _state.asStateFlow()
    }

    /** Where the map opens, for a view of [width] × [height]: the whole spread of an `auto` place's photos, else its point at
     *  [DEFAULT_ZOOM], else the world view. */
    fun initialView(width: Double, height: Double): Viewport {
        val s = current
        if (s.derivedMode == MemoryLocation.DerivedMode.AUTO && medoid != null && points.size > 1) {
            Viewport.fit(points, width, height)?.let { return it }
        }
        val centre = s.derived ?: medoid
        return if (centre != null) Viewport(centre, DEFAULT_ZOOM, width, height)
        else Viewport(MemoryLocation.WORLD_CENTER, MemoryLocation.WORLD_ZOOM, width, height)
    }

    /** The point the place is saved at: the framed centre when `fixed`, else the medoid (`anchorPoint`). */
    val anchorPoint: LatLng? get() {
        val s = current
        return when {
            s.derivedMode == MemoryLocation.DerivedMode.FIXED -> s.derived
            s.withMap -> medoid
            else -> medoid ?: s.derived
        }
    }

    private var resolving: Job? = null

    private suspend fun resolveLevels(p: LatLng) {
        if (!canGeocode) return
        val levels = names?.levelsAt(p) ?: return
        _state.update { s ->
            if (s.nameSource == MemoryLocation.NameSource.MANUAL) s.copy(levels = levels)
            else {
                val level = s.chosenLevel?.let { k -> Geocoder.nameAtLevel(levels, k)?.let { GeoLevel(k, it) } } ?: Geocoder.defaultLevel(levels)
                if (level == null) s.copy(levels = levels)
                else s.copy(levels = levels, chosenLevel = level.key, name = level.name, nameSource = MemoryLocation.NameSource.GEOCODED)
            }
        }
    }

    private fun scheduleResolve(p: LatLng) {
        resolving?.cancel()
        resolving = scope.launch {
            delay(RESOLVE_DELAY_MS) // the pan settles first: one lookup, not one per frame
            resolveLevels(p)
        }
    }

    /**
     * The map's centre moved to [at] — by the member ([user]) or by the app (an opening, a follow). In `auto` a member's
     * pan takes over (`fixed`); a frame the member did not make never makes a position.
     */
    fun frame(at: LatLng, user: Boolean) {
        val s = current
        if (!user && s.derived == null) return
        if (s.derivedMode == MemoryLocation.DerivedMode.AUTO) {
            if (!user) return
        }
        _state.update { it.copy(derivedMode = MemoryLocation.DerivedMode.FIXED, derived = at) }
        scheduleResolve(at)
    }

    /** Back to `auto`: the medoid again and its name resolved; the platform recentres its map on [medoid]. */
    fun followAgain() {
        val m = medoid ?: return
        _state.update { it.copy(derivedMode = MemoryLocation.DerivedMode.AUTO, derived = m) }
        scope.launch { resolveLevels(m) }
    }

    /** The level picked from the picker: its name, as the map's. */
    fun pickLevel(key: GeoLevel.Key) {
        _state.update { s ->
            val n = Geocoder.nameAtLevel(s.levels, key) ?: return@update s
            s.copy(chosenLevel = key, name = n, nameSource = MemoryLocation.NameSource.GEOCODED)
        }
    }

    /** The member typed [text] as the name: it is theirs now (`manual`), cut to its bound. */
    fun typeName(text: String) = _state.update {
        it.copy(name = if (text.codePointCountApprox() > MemoryLocation.MAX_NAME) MemoryLocation.bound(text) else text, nameSource = MemoryLocation.NameSource.MANUAL)
    }

    /** Between typing an own name and the map's name at the chosen (or default) level. */
    fun toggleNameMode() = _state.update { s ->
        if (s.showLevelPicker) s.copy(nameSource = MemoryLocation.NameSource.MANUAL)
        else {
            val lvl = s.chosenLevel?.let { k -> Geocoder.nameAtLevel(s.levels, k)?.let { GeoLevel(k, it) } } ?: Geocoder.defaultLevel(s.levels)
            if (lvl != null) s.copy(nameSource = MemoryLocation.NameSource.GEOCODED, chosenLevel = lvl.key, name = lvl.name)
            else s.copy(nameSource = MemoryLocation.NameSource.GEOCODED)
        }
    }

    /** Frames the map's place and resolves its names — at the opening with a map, and once the map services are turned on. */
    fun open() {
        if (!current.withMap) return
        val s = current
        if (s.derivedMode == MemoryLocation.DerivedMode.AUTO && medoid != null) scope.launch { resolveLevels(medoid) }
        else s.derived?.let { d -> scope.launch { resolveLevels(d) } }
    }

    /** The consent was given in the editor (or the map came otherwise): the map stands where the name field's offer stood. */
    fun mapsTurnedOn() {
        if (!withMapNow()) return
        _state.update { s ->
            var n = s.copy(withMap = true)
            if (n.name.isBlank() && names?.available == true) n = n.copy(nameSource = MemoryLocation.NameSource.GEOCODED)
            if (n.derivedMode == MemoryLocation.DerivedMode.AUTO && medoid == null && n.derived == null) n = n.copy(derivedMode = MemoryLocation.DerivedMode.FIXED)
            n
        }
        open()
    }

    /** The place the fields hold, or null where it may not be saved (no name) — the web's `save`'s value. */
    fun build(): MemoryLocation? {
        val s = current
        if (!s.canSave) return null
        val p = anchorPoint
        val resolvedHere = s.withMap || s.levels.isNotEmpty()
        val anchor = if (!resolvedHere) {
            if (s.nameSource == MemoryLocation.NameSource.GEOCODED) stored?.nameAnchor else null
        } else if (s.nameSource == MemoryLocation.NameSource.GEOCODED && s.chosenLevel != null && p != null) MemoryLocation.Anchor(p, s.chosenLevel)
        else null
        return MemoryLocation(
            MemoryLocation.bound(s.name), s.nameSource, p,
            // no point: a name only, `auto`, so the first geotagged photo gives it one
            if (p != null) s.derivedMode else MemoryLocation.DerivedMode.AUTO,
            anchor,
        )
    }

    /** Saves the place the fields hold; a peer's place written meanwhile re-opens the editor on it. Null: nothing to save. */
    suspend fun save(): EditorSave? {
        val place = build() ?: return null
        return saveOf(save(place))
    }

    /** *Remove place*: the place dropped, the date and the cover kept. */
    suspend fun remove(): EditorSave = saveOf(save(null))

    /** The editor went: a pending lookup is dropped. */
    fun close() = scope.cancel()

    companion object {
        /** How long the map settles before its centre is looked up (the web's debounce). */
        const val RESOLVE_DELAY_MS = 700L

        /** The zoom a place with a point opens at. */
        const val DEFAULT_ZOOM = 13.0

        private fun String.codePointCountApprox(): Int {
            var n = 0
            var i = 0
            while (i < length) {
                i += if (this[i].isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) 2 else 1
                n++
            }
            return n
        }
    }
}
