// uc.memory.place (web) — the Memory-location reactive slice of MemoryDetail, extracted into a composable
// (cr-0146, audit #77/P4a). A deliberate per-Memory named place sealed in the SAME node `wrappedMeta` blob as the
// date (read-modify-write, operator-blind). The map + geocoding are gated behind a personal two-stage opt-in
// (ADR 0016/0018); the position is the medoid of the Memory's photo GPS (`auto`, self-heals as media arrive) or a
// hand-dropped pin (`fixed`). Pure place maths + geocoding live in `./memoryLocation` + `./geocode`; this owns the
// reactive wiring (editor/consent state, the header label, save + the geocoding self-heal).
import { computed, ref, type Ref } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  unsealMemoryLocation,
  computeMedoid,
  mediaGpsPoints,
  sameMemoryLocation,
  isLocationPending,
  normalizeLocation,
  type MemoryLocation,
} from './memoryLocation'
import { nodeMetaState } from '../../core/node/nodeMeta'
import { useMetaWriteFeedback, type NodeMetaUpdate } from './useMetaWriteFeedback'
import { reverseGeocode, defaultLevel, nameAtLevel } from './geocode'
import { useMapConfig, setMapsConsent } from '../../shared/state/useMapConfig'
import type { MediaItem } from './media'
import type { TreeNode } from '../../core/node/tree'
import type { NodeMetaDoor } from './source'

/** The reactive context MemoryDetail hands the location slice — getters so props stay reactive, plus the live
 *  media list (the medoid materializes from it) and the host's `metaUpdated` emit. */
export interface MemoryLocationContext {
  scopeId: () => string
  node: () => TreeNode
  memoryKey: () => Uint8Array | null
  /** The node-meta writes this view may make — the source's door where the capabilities grant `editMeta`, else null:
   *  then the save and the `auto` self-heal write nothing (a guest, a scope pending deletion). */
  write: () => NodeMetaDoor | null
  items: Ref<MediaItem[]>
  onMetaUpdated: (payload: NodeMetaUpdate) => void
}

export function useMemoryLocation(ctx: MemoryLocationContext) {
  const { t, locale } = useI18n()
  const { mapsAvailable, geocoderUrl, geocodingAvailable } = useMapConfig()

  const locationEditorOpen = ref(false)
  const { editorFailed, selfHealFailed } = useMetaWriteFeedback(ctx.onMetaUpdated)
  const consentDialogOpen = ref(false)
  const storedLocation = computed(() => (ctx.memoryKey() ? unsealMemoryLocation(ctx.scopeId(), ctx.memoryKey()!, ctx.node()) : null))
  const gpsPoints = computed(() => {
    const key = ctx.memoryKey()
    return key ? mediaGpsPoints(ctx.scopeId(), ctx.items.value, key) : []
  })
  const medoid = computed(() => computeMedoid(gpsPoints.value))
  // A pending auto place (armed, name deferred) is only genuinely "being determined" when there's a geotagged
  // medium to derive a position from; with no GPS media there is nothing to detect, so say "no location detected"
  // rather than imply an in-progress lookup that will never resolve.
  const locationLabel = computed(() => {
    const loc = storedLocation.value
    if (!loc) return ''
    if (!isLocationPending(loc)) return loc.name
    return gpsPoints.value.length ? t('memory.place.pending') : t('memory.place.none')
  })
  // A location the header can show as a real, set place (a materialized name) — vs. the "set / pending / none"
  // placeholder states, which render in the muted `is-empty` style.
  const locationResolved = computed(() => {
    const loc = storedLocation.value
    return Boolean(loc && !isLocationPending(loc))
  })

  /** Open the location editor — but first gate on the personal maps opt-in (no consent ⇒ ask, no external calls). */
  function openLocationEditor() {
    if (!ctx.memoryKey()) return
    if (mapsAvailable.value) locationEditorOpen.value = true
    else consentDialogOpen.value = true
  }

  /** The user grants the coupled tiles+geocoding consent → enable and proceed into the editor. */
  function enableMaps() {
    setMapsConsent(true)
    consentDialogOpen.value = false
    if (mapsAvailable.value) locationEditorOpen.value = true
  }

  /** Save (or, for null, remove) the location: read-modify-write under K_mem — the date is preserved either way. */
  async function onLocationSave(location: MemoryLocation | null) {
    const key = ctx.memoryKey()
    const write = ctx.write()
    if (!key || !write) return
    const nodeId = ctx.node().id
    try {
      const state = await write.setLocation(nodeId, key, location, nodeMetaState(ctx.node()))
      ctx.onMetaUpdated({ nodeId, ...state })
    } catch (e) {
      // a peer's place written meanwhile re-opens the editor on it (ADR 0077); other failures stay best-effort
      await editorFailed(nodeId, e, () => (locationEditorOpen.value = true))
    }
  }

  /** The next `auto` place for `stored` at medoid `point`: re-medoided, and a geocoded name materialized (a pending
   *  arm) or refreshed (a moved medoid) at the stored granularity `level` (client-direct third-party, ADR 0016
   *  standing consent). */
  async function nextAutoLocation(stored: MemoryLocation, point: { lat: number; lng: number }): Promise<MemoryLocation> {
    let next: MemoryLocation = { ...stored, derived: point }
    const medoidMoved = stored.derived?.lat !== point.lat || stored.derived?.lng !== point.lng
    if (stored.nameSource === 'geocoded' && geocodingAvailable.value && (isLocationPending(stored) || medoidMoved)) {
      const levels = await reverseGeocode(geocoderUrl.value, point.lat, point.lng, locale.value)
      const level = levels ? (stored.nameAnchor?.level && levels.find((l) => l.key === stored.nameAnchor?.level) ? { key: stored.nameAnchor.level, name: nameAtLevel(levels, stored.nameAnchor.level)! } : defaultLevel(levels)) : null
      if (level) next = { ...next, name: level.name, nameAnchor: { lat: point.lat, lng: point.lng, level: level.key } }
    }
    return next
  }

  /** Re-medoid an `auto` location from the media, and materialize a **pending** name by reverse-geocoding the
   *  medoid once (opt-in) — the self-heal that turns a create-time `auto` arm into a named place on the first
   *  geotagged upload. Re-seals only when something changed. A `manual` name / a `fixed` place is never touched —
   *  decided on the server's current blob, so a place a peer pinned meanwhile is not moved (ADR 0077). */
  async function reconcileAutoLocation() {
    const key = ctx.memoryKey()
    const write = ctx.write()
    if (!key || !write) return
    const point = computeMedoid(mediaGpsPoints(ctx.scopeId(), ctx.items.value, key))
    if (!point) return // no GPS yet (e.g. an `auto` pre-armed at creation) — nothing to derive from
    const nodeId = ctx.node().id
    try {
      const { state } = await write.write(nodeId, key, nodeMetaState(ctx.node()), async (meta) => {
        const stored = normalizeLocation(meta.location)
        if (!stored || stored.derivedMode !== 'auto') return null // no location / a `fixed` place is the user's
        const next = await nextAutoLocation(stored, point)
        if (sameMemoryLocation(stored, next)) return null
        meta.location = next
        return meta
      })
      if (ctx.node().id === nodeId) ctx.onMetaUpdated({ nodeId, ...state })
    } catch (e) {
      // leave the stale position; the next Memory open self-heals it (a blob that does not open is never rebuilt)
      await selfHealFailed(nodeId, e)
    }
  }

  return {
    locationEditorOpen,
    consentDialogOpen,
    storedLocation,
    gpsPoints,
    medoid,
    locationLabel,
    locationResolved,
    openLocationEditor,
    enableMaps,
    onLocationSave,
    reconcileAutoLocation,
  }
}
