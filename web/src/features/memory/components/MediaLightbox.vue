<script setup lang="ts">
// The Memory gallery lightbox (cr-0049, uc.memory.gallery). Ported from the design source's MediaLightbox
// (legacy/frontend-noe2ee) — same markup/classes (media.css) — with the data layer swapped to the operator-
// blind tiers: the stage shows the **preview** derivative (openLightbox), never the multi-MB original; the
// explicit download fetches the **original** (openOriginal). Every byte is fetched as ciphertext and decrypted
// on device; the decrypted preview becomes an object URL that is **revoked** on navigation / close / unmount.
// The detail panel (cr-0064) shows the lazily-loaded EXIF tier and lets the user **correct the capture time**
// (cr-0065, sealed under K_mem). Out of scope (own CRs): highlight, delete, fullscreen-original, sharing.
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  openLightbox,
  openOriginal,
  chunkProvider,
  hasDerivatives,
  loadMediaExif,
  unsealMeta,
  setMediaMeta,
  backfillMediaType,
  backfillPlaceName,
  isVideoMediaType,
  effectiveCapturedAt,
  plaintextSize,
  type MediaItem,
  type MediaExif,
  type MediaMeta,
  type ChunkProvider,
  type MediaMetaWrite,
} from '../media'
import { MetaConflictError, MetaUnreadableError, staleMetaOf, type SealedMetaState } from '../sealedMetaWrite'
import { derivedDownloadName, saveBytes, sniffMediaType } from '../download'
import { resolvePlaceName } from '../placeName'
import { useMapConfig } from '../../../shared/state/useMapConfig'
import MemoryMap from './MemoryMap.vue'
import { registerPlayback, revokePlayback, handleFromUrl } from '../../../sw/register'
import { useObjectUrl } from '../../../shared/state/useObjectUrls'
import { formatBytes } from '../../../shared/formatBytes'

const props = defineProps<{
  scopeId: string
  memoryKey: Uint8Array
  items: MediaItem[]
  startIndex: number
  memoryName: string
  coverMediaId?: string | null // the photo currently used as the Memory's cover — marks the "Set as cover" button
  // --- reuse hooks (cr-0100): the SAME lightbox drives the internal Memory (authed, default) AND the anonymous
  // public-share viewer. When these are omitted it behaves exactly as before (authed media API). When provided,
  // the byte/metadata access is injected — the share viewer passes its operator-blind, per-item share loaders.
  readonly?: boolean // hide every write affordance (set-cover, capture-time edit) — the public viewer is view-only
  previewLoader?: (item: MediaItem) => Promise<Uint8Array> // fetch+decrypt the preview tier (default: authed openLightbox)
  originalLoader?: (item: MediaItem) => Promise<Uint8Array> // fetch+decrypt the original (default: authed openOriginal)
  exifLoader?: (item: MediaItem) => Promise<MediaExif | null> // lazy heavy-EXIF (default: authed getMediaExif; skipped when readonly + absent)
  metaResolver?: (item: MediaItem) => MediaMeta | null // unseal the listed date/GPS tier (default: unsealMeta under memoryKey)
  // Slice 2 (ADR 0047): the per-item key-free chunk source for SW range playback. Default (authed) presigns via
  // the media API; the share viewer injects its share-scoped provider (per-item K_mem + guest pwToken), so the SW
  // path covers the anonymous viewer without the SW learning the route. Absent → the authed provider is used.
  videoChunkProvider?: (item: MediaItem) => ChunkProvider
  suspendKeys?: boolean // pause the global Escape/arrow handler while a modal (the cover cropper) is open over the lightbox
}>()
const emit = defineEmits<{
  close: []
  updated: [{ mediaId: string } & SealedMetaState] // a meta write landed, or a conflict handed back the current tier
  setCover: [{ mediaId: string }] // "use this photo as the Memory's cover" (cr-0073 §gallery-source)
  delete: [{ mediaId: string }] // trash this photo (cr-0110); the parent soft-deletes + shows an undo toast
}>()

const { t, locale } = useI18n()

// Byte/metadata access, resolved once per item: the injected loader when the host provides one (the share
// viewer), else the built-in authed media-API path (the internal Memory). Keeps behaviour identical by default.
const previewOf = (it: MediaItem) => (props.previewLoader ?? ((x: MediaItem) => openLightbox(props.scopeId, props.memoryKey, x)))(it)
const originalOf = (it: MediaItem) => (props.originalLoader ?? ((x: MediaItem) => openOriginal(props.scopeId, props.memoryKey, x)))(it)
// The Slice-2 chunk provider for the shown item's ORIGINAL (the video streams the pristine original). Injected by
// the share viewer; else the authed media-API provider. Keys never leave the page — the SW only pulls plaintext.
const videoProviderOf = (it: MediaItem): ChunkProvider =>
  (props.videoChunkProvider ?? ((x: MediaItem) => chunkProvider(props.scopeId, props.memoryKey, x)))(it)
const metaOf = (it: MediaItem) => (props.metaResolver ?? ((x: MediaItem) => unsealMeta(props.scopeId, props.memoryKey, x)))(it)
const exifOf = (it: MediaItem): Promise<MediaExif | null> =>
  props.exifLoader ? props.exifLoader(it) : props.readonly ? Promise.resolve(null) : loadMediaExif(props.scopeId, it, props.memoryKey)

const current = ref(props.startIndex)
// The current item's decrypted preview tier as an object URL — minted + revoked (on switch/unmount) by the primitive.
const { url: previewUrl, set: setPreview, clear: clearPreview } = useObjectUrl()
// A video's decrypted original, played from a typed blob URL — the Slice-1 FALLBACK path (no/stale SW). Its own
// object-URL owner (revoked on switch/unmount).
const { url: videoUrl, set: setVideo, clear: clearVideo } = useObjectUrl()
// Slice 2 (ADR 0047): the active SW playback. `videoSwUrl` is the synthetic `/__media/<handle>` src (NOT a blob
// URL — never an object-URL owner); `videoHandle` is kept so we can revoke the playback on switch/close/unmount.
const videoSwUrl = ref<string | null>(null)
const videoHandle = ref<string | null>(null)
const videoLoading = ref(false)
const loading = ref(false)
const error = ref(false)
const downloading = ref(false)
// The listed-metadata tier (cr-0066/0068): unsealed instantly from the listed item (no fetch). Null = none sealed.
const captured = ref<MediaMeta | null>(null)
// The panel bundle (cr-0064): everything the detail panel shows beyond the listed tier, lazy-loaded per item
// via the exif endpoint. What is in it is [MediaExif]'s business, not this comment's. Null = none/undecoded.
const exif = ref<MediaExif | null>(null)
const detailsLoading = ref(false)
const isDetailsOpen = ref(false) // mobile bottom-sheet toggle (the design's swipe handle)
// capturedAt override editor (cr-0065/0066)
const isEditingCapturedAt = ref(false)
const capturedAtInput = ref('') // bound to <input type="datetime-local">
const savingCapturedAt = ref(false)
// Why the last capture-time save did not land: it failed, a peer corrected the time meanwhile (the editor re-opened
// on theirs, ADR 0077), or the tier does not open and is never rebuilt from empty. Null = no message.
const capturedAtError = ref<'failed' | 'conflict' | 'unreadable' | null>(null)

const item = computed(() => props.items[current.value])
const isCover = computed(() => !!item.value && item.value.mediaId === props.coverMediaId) // this photo is the Memory's cover
const canPrev = computed(() => current.value > 0)
const canNext = computed(() => current.value < props.items.length - 1)
const previewable = computed(() => (item.value ? hasDerivatives(item.value) : false))
// The persisted video signal (#104): the sealed mediaType of the shown item, when it names a video. Drives the
// <video> branch + the play affordance. Undefined for images and for pre-field media (no sealed type yet — those
// stay a poster image until a download back-fills the type, ADR 0047).
const videoType = computed(() => (isVideoMediaType(captured.value?.mediaType) ? captured.value!.mediaType : undefined))

// Load the current item's preview tier (operator-blind: ciphertext → decrypt → object URL). A non-derivable
// item (E1) carries no preview tier, so the stage shows a download fallback instead of fetching its original.
async function loadPreview() {
  clearPreview()
  clearVideo() // drop the previous item's playing video (revoke its blob URL)
  stopSwPlayback() // and tear down any SW playback of the previous item (revoke handle + port)
  videoLoading.value = false
  error.value = false
  if (!item.value || !previewable.value) return
  loading.value = true
  const target = item.value
  try {
    const bytes = await previewOf(target)
    if (item.value !== target) return // navigated away while decrypting — drop this result
    setPreview(new Blob([bytes as BlobPart]))
  } catch {
    if (item.value === target) error.value = true
  } finally {
    if (item.value === target) loading.value = false
  }
}

// Reload when the SHOWN item changes — by identity, not just the index: after a delete (cr-0110) the parent
// removes the item and the array shifts, so the same index now holds a different item (its mediaId changes).
watch(() => item.value?.mediaId, loadPreview, { immediate: true })

// When the item changes: unseal the listed tier (date + GPS + place) **instantly** from the listed item (no
// fetch), then lazily fetch + unseal the panel bundle via the exif endpoint (never listed). Finally, resolve
// the place name if this photo has a coordinate and no name yet — see `resolvePlaceForCurrent` for its gates.
async function loadDetails() {
  exif.value = null
  isEditingCapturedAt.value = false // close the editor when the item changes
  capturedAtError.value = null
  const target = item.value
  if (!target) return
  captured.value = metaOf(target)
  detailsLoading.value = true
  try {
    const loaded = await exifOf(target)
    if (item.value === target) exif.value = loaded // ignore a result for an item we've navigated away from
  } catch {
    if (item.value === target) exif.value = null
  } finally {
    if (item.value === target) detailsLoading.value = false
  }
  void resolvePlaceForCurrent() // fire-and-forget: the panel is already rendered with the coordinate
}
watch(() => item.value?.mediaId, loadDetails, { immediate: true })

// --- capturedAt (cr-0066): effective time = override ?? derived original, from the listed capture-time tier ---
const effectiveCaptured = computed(() => effectiveCapturedAt(captured.value))
const isCapturedAtCorrected = computed(() => captured.value?.capturedAtOverride !== undefined)
// editable as soon as the item is shown — even with no date, the user can *set* one (a fresh tier). Never in
// the read-only public viewer (cr-0100) — it has no authed write path.
const canEditCapturedAt = computed(() => !!item.value && !props.readonly)

/** epoch-ms → the `<input type="datetime-local">` value (local wall-clock, minute precision). */
function msToDatetimeLocal(ms: number | undefined): string {
  if (ms === undefined || !Number.isFinite(ms)) return ''
  const d = new Date(ms)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`
}
/** the datetime-local value → epoch-ms (local wall-clock), or null when empty/invalid. */
function datetimeLocalToMs(value: string): number | null {
  if (!value) return null
  const ms = new Date(value).getTime()
  return Number.isFinite(ms) ? ms : null
}

function startCapturedAtEdit() {
  capturedAtInput.value = msToDatetimeLocal(effectiveCaptured.value)
  capturedAtError.value = null
  isEditingCapturedAt.value = true
}

/** A write landed (or a conflict handed back the current tier): show it, and let the grid patch its item — the
 *  item's `wrappedMeta` + `metaVersion` are the base of the next write from here. */
function applyMetaState(target: MediaItem, state: SealedMetaState, tier: MediaMeta | null) {
  if (item.value === target) captured.value = tier
  emit('updated', { mediaId: target.mediaId, ...state })
}

/** A backfill that did not land: keep the grid current if the server handed back a newer tier; otherwise quiet. */
async function backfillFailed(target: MediaItem, e: unknown) {
  const fresh = await staleMetaOf(e)
  if (fresh) applyMetaState(target, fresh, metaOf({ ...target, wrappedMeta: fresh.wrappedMeta ?? undefined }))
}

async function persistCapturedAt(overrideMs: number | null) {
  const target = item.value
  if (!target) return
  savingCapturedAt.value = true
  capturedAtError.value = null
  try {
    const updated: MediaMetaWrite = await setMediaMeta(props.scopeId, target, props.memoryKey, overrideMs)
    applyMetaState(target, updated, updated.tier) // let the grid re-sort
    if (item.value !== target) return // navigated away mid-save — drop the stale result
    isEditingCapturedAt.value = false
  } catch (e) {
    if (e instanceof MetaConflictError) {
      // A peer corrected the time meanwhile: show theirs and re-open the editor on it, instead of overwriting.
      applyMetaState(target, e.current, metaOf({ ...target, wrappedMeta: e.current.wrappedMeta ?? undefined }))
      if (item.value === target) {
        capturedAtInput.value = msToDatetimeLocal(effectiveCaptured.value)
        capturedAtError.value = 'conflict'
      }
    } else if (item.value === target) {
      capturedAtError.value = e instanceof MetaUnreadableError ? 'unreadable' : 'failed'
      await backfillFailed(target, e)
    }
  } finally {
    if (item.value === target) savingCapturedAt.value = false
  }
}
/** Save the edited date as the override (empty input clears it). */
const saveCapturedAt = () => persistCapturedAt(datetimeLocalToMs(capturedAtInput.value))
/** Reset back to the extracted original (removes the override). */
const resetCapturedAt = () => persistCapturedAt(null)

// --- EXIF value formatting (locale-aware; every field optional → empty string filtered out of the list) ---
const dateFormat = computed(() => new Intl.DateTimeFormat(locale.value === 'de' ? 'de-DE' : 'en-US', { dateStyle: 'medium', timeStyle: 'short' }))
function formatCaptured(ms?: number): string {
  return ms !== undefined && Number.isFinite(ms) ? dateFormat.value.format(new Date(ms)) : ''
}
function formatFocal(mm?: number): string {
  return mm ? `${Math.round(mm)} mm` : ''
}
function formatAperture(f?: number): string {
  return f ? `f/${Number(f.toFixed(1))}` : ''
}
function formatExposure(s?: number): string {
  if (!s || s <= 0) return ''
  return s >= 1 ? `${Number(s.toFixed(1))} s` : `1/${Math.round(1 / s)} s`
}
/** EV compensation, signed and explicit: "0 EV" is a real setting, so 0 is shown rather than filtered out. */
function formatEv(ev?: number): string {
  return ev !== undefined && Number.isFinite(ev) ? `${ev > 0 ? '+' : ''}${Number(ev.toFixed(1))} EV` : ''
}
/** The EXIF Flash **bitfield** — bit 0 is "fired". 16 is truthy and means it did not, which is why this is a
 *  bit test and not a boolean cast; the raw number is what `translateValues: false` keeps available. */
function formatFlash(flash?: number): string {
  if (flash === undefined || !Number.isFinite(flash)) return ''
  return (flash & 0x1) === 1 ? t('media.flashFired') : t('media.flashNotFired')
}
function formatMetres(m?: number, digits = 0): string {
  return m !== undefined && Number.isFinite(m) ? `${Number(m.toFixed(digits))} m` : ''
}
/** The direction the camera faced, named against the north it was measured from ('M' = magnetic). */
function formatDirection(deg?: number, ref?: string): string {
  if (deg === undefined || !Number.isFinite(deg)) return ''
  const rounded = Math.round(deg)
  return ref === 'M' ? t('media.directionMagnetic', { deg: rounded }) : t('media.directionTrue', { deg: rounded })
}
/** A 0..5 star rating, drawn rather than spelled so it reads the same in every language. */
function formatRating(stars?: number): string {
  if (stars === undefined || !Number.isFinite(stars) || stars <= 0) return ''
  const n = Math.max(0, Math.min(5, Math.round(stars)))
  return '★'.repeat(n) + '☆'.repeat(5 - n)
}
/** Byte size in the reader's locale, or '' when unknown. The PLAINTEXT size — see [plaintextSize]; `byteSize` is
 *  the ciphertext. */
function fileSize(bytes?: number): string {
  if (bytes === undefined || !Number.isFinite(bytes) || bytes <= 0) return ''
  return formatBytes(bytes, locale.value)
}

// GPS rides the always-listed metadata tier now (cr-0068), so coordinates show instantly from `captured` —
// no wait for the lazy EXIF fetch (and it's the same source the place medoid reads).
const hasGps = computed(() => {
  const m = captured.value
  return !!m && m.gpsLat !== undefined && m.gpsLng !== undefined
})
const gpsLabel = computed(() =>
  hasGps.value ? `${captured.value!.gpsLat!.toFixed(6)}, ${captured.value!.gpsLng!.toFixed(6)}` : '',
)
const placeLabel = computed(() => captured.value?.placeName ?? '')

// --- the resolved place name (uc.memory.place, ADR 0016) ------------------------------------------------
// Resolved once per photo, then read from the sealed tier forever. Four gates, and each one is a bite:
// consent (`geocodingAvailable` — with it off NO coordinate leaves the device), a coordinate to resolve,
// a writable surface (the public share viewer is `readonly` and must never write), and no name sealed yet.
const { mapsAvailable, tileUrl, tileAttribution, tileMaxZoom, geocoderUrl, geocodingAvailable } = useMapConfig()

// The mini map: the SAME MemoryMap the location editor uses, read-only (no crosshair, no frame handling) and
// gated on `mapsAvailable` — without consent no tile is requested and the text rows stand alone. A fixed close
// zoom, because the question this answers is "where in the town", not "where in the world".
const MINI_MAP_ZOOM = 15
const mapCenter = computed(() =>
  hasGps.value ? { lat: captured.value!.gpsLat!, lng: captured.value!.gpsLng! } : null,
)
const showMiniMap = computed(() => mapsAvailable.value && mapCenter.value !== null)
/** The direction the camera faced, as a CSS rotation for the compass needle drawn over the map (phase 1's
 *  `GPSImgDirection`). Absent on a photo whose fix carried no direction — then no needle is drawn at all. */
const viewDirection = computed(() => {
  const deg = exif.value?.imgDirection
  return deg !== undefined && Number.isFinite(deg) ? deg : null
})

async function resolvePlaceForCurrent() {
  const target = item.value
  const meta = captured.value
  if (!target || !meta || props.readonly) return
  if (!hasGps.value || meta.placeName !== undefined) return
  if (!geocodingAvailable.value) return // consent off → resolvePlaceName is never even asked
  const resolved = await resolvePlaceName(geocoderUrl.value, meta.gpsLat!, meta.gpsLng!, locale.value)
  if (!resolved) return
  try {
    const updated = await backfillPlaceName(props.scopeId, target, props.memoryKey, resolved.name, resolved.level)
    applyMetaState(target, updated, updated.tier)
  } catch (e) {
    /* best-effort: a failed seal leaves the coordinate showing, and the next open tries again — and a tier that
       does not open is never rebuilt from empty (ADR 0077) */
    await backfillFailed(target, e)
  }
}
// What the photo itself knows, in the order someone reads it: when, what it is, what took it, how it was
// exposed, who made it, and where. Every row is filtered out when its value is absent, so a phone snapshot
// shows three rows and a raw file from a camera shows twenty — without either shape being a special case.
const metadataRows = computed(() => {
  const e = exif.value
  // The capture date comes from the always-listed capture-time tier (independent of the lazy EXIF); everything
  // below it comes from the EXIF tier once it loads.
  const rows = [{ label: t('media.captured'), value: formatCaptured(effectiveCaptured.value) }]
  if (e) {
    rows.push(
      { label: t('media.fileName'), value: e.name ?? '' },
      { label: t('media.fileSize'), value: fileSize(item.value ? plaintextSize(item.value.original) : undefined) },
      { label: t('media.fileFormat'), value: captured.value?.mediaType ?? '' },
      { label: t('media.camera'), value: [e.make, e.model].filter(Boolean).join(' ').trim() },
      { label: t('media.lens'), value: e.lens ?? '' },
      { label: t('media.focalLength'), value: formatFocal(e.focalLength) },
      { label: t('media.focalLength35'), value: formatFocal(e.focalLength35) },
      { label: t('media.aperture'), value: formatAperture(e.fNumber) },
      { label: t('media.exposure'), value: formatExposure(e.exposureTime) },
      { label: t('media.exposureCompensation'), value: formatEv(e.exposureCompensation) },
      { label: t('media.iso'), value: e.iso !== undefined ? String(e.iso) : '' },
      { label: t('media.flash'), value: formatFlash(e.flash) },
      { label: t('media.dimensions'), value: e.w && e.h ? `${e.w} × ${e.h} px` : '' },
      { label: t('media.title'), value: e.title ?? '' },
      { label: t('media.description'), value: e.description ?? '' },
      { label: t('media.rating'), value: formatRating(e.rating) },
      { label: t('media.software'), value: e.software ?? '' },
      { label: t('media.artist'), value: e.artist ?? '' },
      { label: t('media.copyright'), value: e.copyright ?? '' },
      { label: t('media.altitude'), value: formatMetres(e.altitude) },
      { label: t('media.direction'), value: formatDirection(e.imgDirection, e.imgDirectionRef) },
      { label: t('media.gpsAccuracy'), value: formatMetres(e.gpsAccuracy, 1) },
    )
  }
  return rows.filter((row) => row.value)
})
const hasDetail = computed(() => metadataRows.value.length > 0 || hasGps.value)

function prev() {
  if (canPrev.value) current.value -= 1
}
function next() {
  if (canNext.value) current.value += 1
}

// After a delete (cr-0110) the parent removes the item from `items`, shrinking the array: the item that shifted
// into this slot shows (a natural advance), clamp to the last when the deleted one WAS last, and close when the
// grid empties — so the lightbox stays consistent with the optimistic removal.
watch(
  () => props.items.length,
  (len) => {
    if (len === 0) emit('close')
    else if (current.value >= len) current.value = len - 1
  },
)

/** Tear down the active SW playback (revoke the handle + close the page's port). Idempotent. */
function stopSwPlayback() {
  if (videoHandle.value) revokePlayback(videoHandle.value)
  videoHandle.value = null
  videoSwUrl.value = null
}

// Click-to-play (ADR 0047), so autoplay with sound is allowed (the user gesture drove it). Two paths:
//  - **Slice 2 (preferred):** register a key-free playback with the controlling Service Worker and set the video
//    src to a synthetic `/__media/<handle>`; the SW streams byte ranges, pulling decrypted chunks from the page's
//    ChunkProvider on demand. Keys never leave the page.
//  - **Slice 1 fallback (retained):** no SW, a stale/mismatched SW, or a registration failure → fetch + decrypt
//    the WHOLE original via `originalOf` and play it from a **typed** blob URL. No size cap; a large video simply
//    waits for its full decrypt (the download button is the escape hatch). This is a necessity for missing/old
//    SWs, not throwaway code.
async function playVideo() {
  const target = item.value
  const type = videoType.value
  if (!target || !type || videoLoading.value) return
  videoLoading.value = true
  error.value = false
  try {
    const provider = videoProviderOf(target)
    const url = await registerPlayback(
      { chunkCount: provider.chunkCount, byteSize: provider.byteSize, mediaType: type },
      provider,
    )
    if (url) {
      const handle = handleFromUrl(url)
      if (item.value !== target) {
        if (handle) revokePlayback(handle) // navigated away mid-register — don't leak the session
        return
      }
      videoHandle.value = handle
      videoSwUrl.value = url // the <video> src; the SW answers its range requests
    } else {
      const bytes = await originalOf(target) // Slice-1 fallback: no usable SW
      if (item.value !== target) return // navigated away mid-decrypt — drop it
      setVideo(new Blob([bytes as BlobPart], { type }))
    }
  } catch {
    if (item.value === target) error.value = true
  } finally {
    if (item.value === target) videoLoading.value = false
  }
}

async function download() {
  if (!item.value || downloading.value) return
  downloading.value = true
  const target = item.value
  const targetMeta = captured.value // the shown tier, only to skip a media that already has a type — the backfill itself re-reads the listed tier
  try {
    const bytes = await originalOf(target)
    saveBytes(bytes, derivedDownloadName(props.memoryName, current.value, bytes))
    await maybeBackfillMediaType(target, targetMeta, bytes)
  } catch {
    error.value = true
  } finally {
    downloading.value = false
  }
}

// Lazy mediaType backfill (ADR 0047 / #105): a pre-field media carries no sealed type, so on the download that
// already fetched its original we sniff the bytes and write the type back — the NEXT listing then shows the play
// badge + the <video> branch. Never in the read-only public viewer (no authed write path). Best-effort: a failed
// sniff/write just means the item stays untyped (no badge yet).
async function maybeBackfillMediaType(target: MediaItem, targetMeta: MediaMeta | null, bytes: Uint8Array) {
  if (props.readonly || targetMeta?.mediaType) return
  const sniffed = sniffMediaType(bytes)
  if (!sniffed) return
  try {
    const updated = await backfillMediaType(props.scopeId, target, props.memoryKey, sniffed)
    applyMetaState(target, updated, updated.tier) // let the grid re-list with the badge
  } catch (e) {
    /* best-effort — a failed backfill just means no badge yet */
    await backfillFailed(target, e)
  }
}

function emitDelete() {
  if (props.readonly || !item.value) return
  emit('delete', { mediaId: item.value.mediaId }) // the parent trashes it + shows an undo toast (cr-0110)
}

function onKeydown(event: KeyboardEvent) {
  if (props.suspendKeys) return // a modal (cover cropper) is open over the lightbox — let it own the keyboard
  if (event.key === 'Escape') emit('close')
  else if (event.key === 'ArrowLeft') prev()
  else if (event.key === 'ArrowRight') next()
  else if (event.key === 'Delete' || event.key === 'Backspace') { event.preventDefault(); emitDelete() } // cr-0110 culling hotkey
}
window.addEventListener('keydown', onKeydown)

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  stopSwPlayback() // revoke any live SW playback (its port + in-SW plaintext cache) when the lightbox unmounts
})
</script>

<template>
  <div
    class="media-lightbox"
    role="dialog"
    aria-modal="true"
    :aria-label="memoryName"
    @click.self="emit('close')"
  >
    <div class="media-lightbox-topbar">
      <div class="media-lightbox-title">
        <strong>{{ memoryName }}</strong>
        <small>{{ t('media.itemPosition', { index: current + 1, count: items.length }) }}</small>
      </div>
      <div class="media-lightbox-actions">
        <button
          class="veyq-icon-button lightbox-icon-button"
          type="button"
          :aria-label="t('media.download')"
          :title="t('media.download')"
          :disabled="downloading"
          @click="download"
        >
          <span class="pi" :class="downloading ? 'pi-spin pi-spinner' : 'pi-download'" />
        </button>
        <button
          v-if="previewable && !readonly"
          class="veyq-icon-button lightbox-icon-button"
          :class="{ 'lightbox-cover-active': isCover }"
          type="button"
          :aria-pressed="isCover"
          :aria-label="t(isCover ? 'memory.cover.isCover' : 'memory.cover.setFromPhoto')"
          :title="t(isCover ? 'memory.cover.isCover' : 'memory.cover.setFromPhoto')"
          @click="!isCover && item && emit('setCover', { mediaId: item.mediaId })"
        >
          <span class="pi" :class="isCover ? 'pi-check' : 'pi-image'" />
        </button>
        <button
          v-if="!readonly"
          class="veyq-icon-button lightbox-icon-button lightbox-delete-button"
          type="button"
          :aria-label="t('media.delete')"
          :title="t('media.delete')"
          @click="emitDelete"
        >
          <span class="pi pi-trash" />
        </button>
        <button
          class="veyq-icon-button lightbox-icon-button"
          type="button"
          :aria-label="t('media.close')"
          @click="emit('close')"
        >
          <span class="pi pi-times" />
        </button>
      </div>
    </div>

    <main class="media-lightbox-layout">
      <section class="media-lightbox-viewer">
        <button
          type="button"
          class="veyq-icon-button lightbox-nav lightbox-nav-previous"
          :aria-label="t('media.previous')"
          :disabled="!canPrev"
          @click="prev"
        >
          <span class="pi pi-chevron-left" />
        </button>

        <div class="media-lightbox-stage">
          <p v-if="error" class="media-lightbox-error">{{ t('media.loadError') }}</p>
          <!-- Video playback (#104, ADR 0047): once the user clicks play, the video plays either from the SW range
               path (`videoSwUrl` = /__media/<handle>, Slice 2) or, as a fallback, a decrypted typed blob URL
               (`videoUrl`, Slice 1). Before that, a video shows its poster (the PREVIEW tier) + a play affordance. -->
          <video v-else-if="videoSwUrl || videoUrl" :src="videoSwUrl ?? videoUrl ?? undefined" controls autoplay playsinline />
          <div v-else-if="videoType && previewUrl" class="media-lightbox-video-poster">
            <img :src="previewUrl" :alt="memoryName" />
            <button
              type="button"
              class="media-lightbox-play"
              :disabled="videoLoading"
              :aria-label="t('media.playVideo')"
              :title="t('media.playVideo')"
              @click="playVideo"
            >
              <span class="pi" :class="videoLoading ? 'pi-spin pi-spinner' : 'pi-play'" />
            </button>
          </div>
          <img v-else-if="previewUrl" :src="previewUrl" :alt="memoryName" />
          <span v-else-if="loading" class="media-original-loading">
            <span class="pi pi-spin pi-spinner" />
            <span>{{ t('media.loading') }}</span>
          </span>
          <button
            v-else
            type="button"
            class="media-lightbox-file-fallback"
            :disabled="downloading"
            @click="download"
          >
            <span class="pi pi-file" />
            <span>{{ t('media.download') }}</span>
          </button>
        </div>

        <button
          type="button"
          class="veyq-icon-button lightbox-nav lightbox-nav-next"
          :aria-label="t('media.next')"
          :disabled="!canNext"
          @click="next"
        >
          <span class="pi pi-chevron-right" />
        </button>
      </section>

      <aside
        class="veyq-surface media-lightbox-details"
        :class="{ 'media-lightbox-details-open': isDetailsOpen }"
      >
        <button
          class="media-lightbox-sheet-handle"
          type="button"
          :aria-expanded="isDetailsOpen"
          :aria-label="t('media.toggleInfo')"
          @click="isDetailsOpen = !isDetailsOpen"
        />
        <div class="media-lightbox-details-header">
          <h2>{{ t('media.information') }}</h2>
          <div v-if="canEditCapturedAt && !isEditingCapturedAt" class="media-lightbox-details-actions">
            <button
              class="veyq-icon-button lightbox-icon-button"
              type="button"
              :aria-label="t('media.editCapturedAt')"
              :title="t('media.editCapturedAt')"
              @click="startCapturedAtEdit"
            >
              <span class="pi pi-pencil" />
            </button>
          </div>
        </div>

        <form v-if="isEditingCapturedAt" class="media-lightbox-date-form" @submit.prevent="saveCapturedAt">
          <label for="captured-at-input">{{ t('media.captured') }}</label>
          <input id="captured-at-input" v-model="capturedAtInput" type="datetime-local" :disabled="savingCapturedAt" />
          <div class="media-lightbox-date-actions">
            <button class="veyq-text-button lightbox-text-button" type="submit" :disabled="savingCapturedAt">
              {{ t('common.save') }}
            </button>
            <button
              class="veyq-text-button lightbox-text-button"
              type="button"
              :disabled="savingCapturedAt"
              @click="isEditingCapturedAt = false"
            >
              {{ t('common.cancel') }}
            </button>
            <button
              v-if="isCapturedAtCorrected"
              class="veyq-text-button lightbox-text-button"
              type="button"
              :disabled="savingCapturedAt"
              @click="resetCapturedAt"
            >
              {{ t('media.resetCapturedAt') }}
            </button>
          </div>
          <p v-if="capturedAtError === 'failed'" class="media-lightbox-error">{{ t('media.capturedAtSaveFailed') }}</p>
          <p v-else-if="capturedAtError === 'conflict'" class="media-lightbox-error">{{ t('media.capturedAtConflict') }}</p>
          <p v-else-if="capturedAtError === 'unreadable'" class="media-lightbox-error">{{ t('media.metaUnreadable') }}</p>
        </form>

        <template v-else>
          <dl v-if="hasDetail" class="media-lightbox-metadata">
            <template v-for="row in metadataRows" :key="row.label">
              <dt>{{ row.label }}</dt>
              <dd>{{ row.value }}</dd>
            </template>
            <template v-if="isCapturedAtCorrected">
              <dt>{{ t('media.status') }}</dt>
              <dd>{{ t('media.capturedAtCorrected') }}</dd>
            </template>
            <template v-if="placeLabel">
              <dt>{{ t('media.place') }}</dt>
              <dd>{{ placeLabel }}</dd>
            </template>
            <template v-if="hasGps">
              <dt>{{ t('media.location') }}</dt>
              <dd>{{ gpsLabel }}</dd>
            </template>
          </dl>
          <!-- The map is an illustration of the coordinate above it, never a replacement: without consent the
               rows stand alone and no tile is fetched (ADR 0018). -->
          <div v-if="showMiniMap" class="media-lightbox-map">
            <MemoryMap
              :tile-url="tileUrl"
              :attribution="tileAttribution"
              :max-zoom="tileMaxZoom"
              :center="mapCenter!"
              :zoom="MINI_MAP_ZOOM"
              :marker="mapCenter"
            />
            <span
              v-if="viewDirection !== null"
              class="media-lightbox-view-direction"
              :style="{ transform: `rotate(${viewDirection}deg)` }"
              :aria-label="formatDirection(viewDirection, exif?.imgDirectionRef)"
            >
              <span class="pi pi-arrow-up" aria-hidden="true" />
            </span>
          </div>
          <p v-else class="media-lightbox-no-detail">{{ t('media.noDetail') }}</p>
        </template>
      </aside>
    </main>
  </div>
</template>

<style scoped>
/* The lightbox trash affordance (cr-0110): a subtle danger tint on hover, set apart from the neutral actions. */
.lightbox-delete-button:hover {
  color: var(--app-danger);
}
/* The "Set as cover" button, when THIS photo is the current cover: an accent-filled state ("it's the cover"). */
.lightbox-cover-active {
  color: var(--app-on-strong);
  background: var(--app-accent);
  border-color: var(--app-accent);
}
/* The mini map in the detail panel: a fixed, unobtrusive band under the rows — an illustration of the
   coordinate, not a second map surface. The compass needle sits over it, anchored to the marker. */
.media-lightbox-map {
  position: relative;
  height: 160px;
  margin-top: 0.75rem;
  overflow: hidden;
  border-radius: var(--app-radius-sm);
}
.media-lightbox-map :deep(.leaflet-container) {
  height: 100%;
}
.media-lightbox-view-direction {
  position: absolute;
  top: 50%;
  left: 50%;
  z-index: 500;
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  margin: -34px 0 0 -15px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--app-backdrop-strong) 65%, transparent);
  color: #fff;
  pointer-events: none;
}
.media-lightbox-no-detail {
  margin: 0;
  color: var(--app-muted);
  font-size: 0.9rem;
}
.media-lightbox-date-form {
  display: grid;
  gap: 0.55rem;
}
.media-lightbox-date-form label {
  color: var(--app-muted);
  font-size: 0.8rem;
}
.media-lightbox-date-form input {
  width: 100%;
  padding: 0.4rem 0.55rem;
  border: 1px solid var(--app-line);
  border-radius: 8px;
  background: var(--app-surface);
  color: var(--app-text);
  font: inherit;
}
.media-lightbox-date-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}
.media-lightbox-file-fallback {
  display: grid;
  place-items: center;
  gap: 0.6rem;
  padding: 2rem;
  border: 1px dashed var(--app-line);
  border-radius: var(--app-radius);
  background: var(--app-surface);
  color: var(--app-text);
  font-weight: 700;
  cursor: pointer;
}
.media-lightbox-file-fallback .pi {
  font-size: 2rem;
}
/* A video's poster (the PREVIEW tier) with a centered play affordance over it (#104). The poster <img> keeps the
   global `.media-lightbox-stage img` sizing; the wrapper just anchors the play button. */
.media-lightbox-video-poster {
  position: relative;
  display: inline-flex;
  max-width: 100%;
  max-height: 100%;
}
.media-lightbox-play {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  display: grid;
  place-items: center;
  width: 4rem;
  height: 4rem;
  border: none;
  border-radius: 999px;
  background: color-mix(in srgb, var(--app-backdrop-strong) 60%, transparent);
  color: #fff;
  font-size: 1.5rem;
  cursor: pointer;
  transition: background 0.15s ease;
}
.media-lightbox-play:hover:not(:disabled) {
  background: var(--app-accent);
}
.media-lightbox-play:disabled {
  cursor: default;
  opacity: 0.8;
}
.media-lightbox-play .pi {
  margin-left: 0.2rem; /* optically center the play triangle */
}
</style>
