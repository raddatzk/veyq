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
  setMemoryLocation,
  computeMedoid,
  mediaGpsPoints,
  sameMemoryLocation,
  isLocationPending,
  type MemoryLocation,
} from './memoryLocation'
import { reverseGeocode, defaultLevel, nameAtLevel } from './geocode'
import { useMapConfig, setMapsConsent } from '../../shared/state/useMapConfig'
import type { MediaItem } from './media'
import type { TreeNode } from './tree'

/** The reactive context MemoryDetail hands the location slice — getters so props stay reactive, plus the live
 *  media list (the medoid materializes from it) and the host's `metaUpdated` emit. */
export interface MemoryLocationContext {
  scopeId: () => string
  node: () => TreeNode
  memoryKey: () => Uint8Array | null
  items: Ref<MediaItem[]>
  onMetaUpdated: (payload: { nodeId: string; wrappedMeta: string }) => void
}

export function useMemoryLocation(ctx: MemoryLocationContext) {
  const { t, locale } = useI18n()
  const { mapsAvailable, geocoderUrl, geocodingAvailable } = useMapConfig()

  const locationEditorOpen = ref(false)
  const consentDialogOpen = ref(false)
  const storedLocation = computed(() => (ctx.memoryKey() ? unsealMemoryLocation(ctx.memoryKey()!, ctx.node()) : null))
  const gpsPoints = computed(() => {
    const key = ctx.memoryKey()
    return key ? mediaGpsPoints(ctx.items.value, key) : []
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
    if (!key) return
    const nodeId = ctx.node().id
    try {
      const wrappedMeta = await setMemoryLocation(ctx.scopeId(), nodeId, key, location, ctx.node().wrappedMeta)
      ctx.onMetaUpdated({ nodeId, wrappedMeta })
    } catch {
      // best-effort: keep the header stable; a retry re-seals
    }
  }

  /** Re-medoid an `auto` location from the media, and materialize a **pending** name by reverse-geocoding the
   *  medoid once (opt-in) — the self-heal that turns a create-time `auto` arm into a named place on the first
   *  geotagged upload. Re-seals only when something changed. A `manual` name / a `fixed` place is never touched. */
  async function reconcileAutoLocation() {
    const key = ctx.memoryKey()
    if (!key) return
    const stored = unsealMemoryLocation(key, ctx.node())
    if (!stored || stored.derivedMode !== 'auto') return // no location / a `fixed` place is the user's — never moved
    const point = computeMedoid(mediaGpsPoints(ctx.items.value, key))
    if (!point) return // no GPS yet (e.g. an `auto` pre-armed at creation) — nothing to derive from
    let next: MemoryLocation = { ...stored, derived: point }
    // Materialize/refresh a geocoded name from the medoid at the stored granularity `level` (client-direct
    // third-party, ADR 0016 standing consent): a pending arm gets its first name; a moved medoid refreshes it.
    const medoidMoved = stored.derived?.lat !== point.lat || stored.derived?.lng !== point.lng
    if (stored.nameSource === 'geocoded' && geocodingAvailable.value && (isLocationPending(stored) || medoidMoved)) {
      const levels = await reverseGeocode(geocoderUrl.value, point.lat, point.lng, locale.value)
      const level = levels ? (stored.nameAnchor?.level && levels.find((l) => l.key === stored.nameAnchor?.level) ? { key: stored.nameAnchor.level, name: nameAtLevel(levels, stored.nameAnchor.level)! } : defaultLevel(levels)) : null
      if (level) next = { ...next, name: level.name, nameAnchor: { lat: point.lat, lng: point.lng, level: level.key } }
    }
    if (sameMemoryLocation(stored, next)) return
    const nodeId = ctx.node().id
    try {
      const wrappedMeta = await setMemoryLocation(ctx.scopeId(), nodeId, key, next, ctx.node().wrappedMeta)
      if (ctx.node().id === nodeId) ctx.onMetaUpdated({ nodeId, wrappedMeta })
    } catch {
      // leave the stale position; the next Memory open self-heals it
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
