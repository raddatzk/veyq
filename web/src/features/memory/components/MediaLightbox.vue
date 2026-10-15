<script setup lang="ts">
// The Memory gallery lightbox (cr-0049, uc.memory.gallery). Ported from the design source's MediaLightbox
// (legacy/frontend-noe2ee) — same markup/classes (media.css) — with the data layer swapped to the operator-
// blind tiers: the stage shows the **preview** derivative (openLightbox), never the multi-MB original; the
// explicit download streams the **original** chunk by chunk into a save target (inbox #371). Every byte is
// fetched as ciphertext and decrypted on device; the decrypted preview becomes an object URL that is **revoked** on
// navigation / close / unmount.
// The detail panel (cr-0064) shows the lazily-loaded EXIF tier and lets the user **correct the capture time**
// (cr-0065, sealed under K_node).
// **Zoom** (cr-lightbox-zoom-slideshow, uc.memory.gallery 3a): double-click/tap, pinch, ctrl+wheel and `+`/`-`/`0` on a
// photo (`shared/ui/usePanZoom.ts`, a drag or the arrow keys pan while zoomed); past the preview's resolution the ORIGINAL replaces it in
// place where `../lightboxZoom.ts` allows - fetched as ciphertext through the source's `originalLoader`, decrypted here, its
// object URL revoked on navigation / close like the preview's. A video does not zoom.
// **Slideshow** (same CR): the toolbar's play control advances every 5 s through the items in their grid order
// (`createVisibilityPoller`); a video shows its poster for one interval, never auto-played; it stops on the last item and
// pauses on any key, pointer or wheel (the zoom and the details panel included), a hidden tab and `suspendKeys`. One
// preview is fetched ahead and dropped if it is not shown. While it runs no EXIF read and no place backfill happen —
// pausing on an item runs them.
//
// **One lightbox for a member and a share guest** (inbox #401). It touches no API itself: every byte and seal comes
// through the loaders the Memory's data source hands in (`../source.ts`, the member's or the guest's — required, so no
// default can fall through to a member call), and every write is offered per capability, never by a mode flag:
// 'set as cover' where `canSetCover`, the delete where `canDelete` answers for the shown item (a member's trash any, a
// guest's purge only its own — the parent runs each), the capture-time correction and the place-name backfill where
// the source carries the medium's meta door (`mediaMeta`), the heavy EXIF where it carries `exifLoader`.
import { ref, computed, watch, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import {
  hasDerivatives,
  isVideoMediaType,
  mediaTypeOf,
  effectiveCapturedAt,
  plaintextSize,
  type MediaItem,
  type MediaExif,
  type MediaMeta,
  type MediaMetaWrite,
} from '../media'
import { MetaConflictError, MetaUnreadableError, staleMetaOf, type SealedMetaState } from '../../../core/node/sealedMetaWrite'
import { downloadOriginal, originalDownloadName } from '../download'
import type { OpenedBlobMeta } from '../../../core/blob/meta'
import { SaveTooLarge, isSaveCancelled } from '../../../shared/saveStream'
import { useToast } from '../../../shared/state/useToast'
import type { ChunkProvider } from '../../../sw/protocol'
import { resolvePlaceName } from '../placeName'
import { useMapConfig } from '../../../shared/state/useMapConfig'
import MemoryMap from './MemoryMap.vue'
import { registerPlayback, revokePlayback, handleFromUrl } from '../../../sw/register'
import { useObjectUrl } from '../../../shared/state/useObjectUrls'
import { formatBytes } from '../../../shared/formatBytes'
import type { MediaMetaDoor } from '../source'
import type { UploaderLabel } from '../useUploaderNames'
import { personHash } from '../../../core/node/location'
import { usePanZoom } from '../../../shared/ui/usePanZoom'
import { createVisibilityPoller } from '../../../shared/state/visibilityPoller'
import { exceedsPreview, originalZoomable, previewDownscaled } from '../lightboxZoom'

const props = defineProps<{
  memoryKey: Uint8Array
  items: MediaItem[]
  startIndex: number
  memoryName: string
  coverMediaId?: string | null // the photo currently used as the Memory's cover — marks the "Set as cover" button
  /** Offer "use this photo as the Memory's cover" (`setCover` event). */
  canSetCover: boolean
  /** Whether the shown item carries a delete (`delete` event; the parent trashes or purges it, Delete key included). */
  canDelete: (item: MediaItem) => boolean
  previewLoader: (item: MediaItem) => Promise<Uint8Array> // fetch+decrypt the preview tier
  originalLoader: (item: MediaItem) => Promise<Uint8Array> // fetch+decrypt the whole original — the Slice-1 video fallback and the zoom
  metaResolver: (item: MediaItem) => MediaMeta | null // unseal the listed date/GPS tier
  // the original's sealed blob meta — its type, file name, modified time (inbox #397)
  blobMetaResolver: (item: MediaItem) => OpenedBlobMeta | null
  // Slice 2 (ADR 0047): the per-item key-free chunk source over the ORIGINAL, for SW range playback and the streamed
  // download (inbox #371 S4) — the member's presigns via the media API, the guest's via the share presign, so the SW
  // never learns the route.
  videoChunkProvider: (item: MediaItem) => ChunkProvider
  // lazy heavy-EXIF; absent: no endpoint serves it to this session (a guest) and the panel shows the listed tier only
  exifLoader?: (item: MediaItem) => Promise<MediaExif | null>
  // The medium's meta writes (capture-time correction, place-name backfill) as the data source carries them; absent:
  // neither is offered nor run.
  mediaMeta?: MediaMetaDoor
  /** Who added the shown item (ADR 0083 decision 7): the name, and the member's id only where the name may link to a profile.
   *  Absent: no uploader is shown (a share guest's session carries none). */
  uploaderOf?: (item: MediaItem) => UploaderLabel | null
  suspendKeys?: boolean // pause the global Escape/arrow handler while a modal (the cover cropper) is open over the lightbox
}>()
const emit = defineEmits<{
  close: []
  updated: [{ mediaId: string } & SealedMetaState] // a meta write landed, or a conflict handed back the current tier
  setCover: [{ mediaId: string }] // "use this photo as the Memory's cover" (cr-0073 §gallery-source)
  delete: [{ mediaId: string }] // delete this photo: a member's trash with undo (cr-0110), a guest's purge of its own (asked first)
}>()

const { t, locale } = useI18n()
const toast = useToast()

// Byte/metadata access through the source's loaders (see the header). The whole decrypted original is only the Slice-1
// video fallback (no usable SW); the video and the download stream the ORIGINAL through the chunk provider.
const previewOf = (it: MediaItem) => props.previewLoader(it)
const originalOf = (it: MediaItem) => props.originalLoader(it)
const originalProviderOf = (it: MediaItem): ChunkProvider => props.videoChunkProvider(it)
const metaOf = (it: MediaItem) => props.metaResolver(it)
const blobMetaOf = (it: MediaItem) => props.blobMetaResolver(it)
const exifOf = (it: MediaItem): Promise<MediaExif | null> => (props.exifLoader ? props.exifLoader(it) : Promise.resolve(null))

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
// The original's sealed blob meta (inbox #397): its type, the file name it was uploaded under, its modified time —
// opened synchronously from the listed item (it rides the listing beside the K_blob wrap), so the download name and
// the video signal are known the moment the item shows; nothing waits for the lazy EXIF tier. Null = none / unopenable.
const blobMeta = computed(() => (item.value ? blobMetaOf(item.value) : null))
const mediaType = computed(() => mediaTypeOf(blobMeta.value))
// The persisted video signal (#104): the original's sealed type, when it names a video. Drives the <video> branch +
// the play affordance. Undefined for images and for a media whose original carries no meta (a poster image only).
const videoType = computed(() => (isVideoMediaType(mediaType.value) ? mediaType.value : undefined))

// --- slideshow (cr-lightbox-zoom-slideshow) ----------------------------------------------------------------------------
// One tick every 5 s advances; a tick waits for the preview it showed (the poller skips a tick while one is in flight, so
// a slow preview is never skipped past), skips while the tab is hidden, and `stop()` fences it. The state is announced.
const SLIDESHOW_INTERVAL_MS = 5000
const slideshow = createVisibilityPoller(SLIDESHOW_INTERVAL_MS)
const playing = ref(false)
const slideshowState = ref<'playing' | 'paused' | 'ended' | null>(null)
const slideshowAnnouncement = computed(() =>
  slideshowState.value === 'playing' ? t('media.slideshowPlaying')
    : slideshowState.value === 'paused' ? t('media.slideshowPaused')
      : slideshowState.value === 'ended' ? t('media.slideshowEnded') : '',
)
// The next item's preview, fetched one ahead while playing: its decrypt in flight or done, never an object URL — a URL is
// minted only for the item on screen, so one fetched ahead and not shown is simply dropped.
let ahead: { mediaId: string; bytes: Promise<Uint8Array> } | null = null
// The current preview's load (`loadPreview`), which a tick waits for.
let previewLoad: Promise<void> = Promise.resolve()
// The item whose EXIF read and place backfill the running slideshow held back — run when it pauses on that item.
let detailsDeferred: string | null = null

function prefetchAhead() {
  const next = props.items[current.value + 1]
  if (!next || !hasDerivatives(next) || ahead?.mediaId === next.mediaId) return
  const bytes = previewOf(next)
  bytes.catch(() => {}) // a failed prefetch is the show's to retry
  ahead = { mediaId: next.mediaId, bytes }
}
/** The prefetched preview when it is `target`'s; whatever was held is dropped either way. */
function takeAhead(target: MediaItem): Promise<Uint8Array> | null {
  const held = ahead
  ahead = null
  return held?.mediaId === target.mediaId ? held.bytes.catch(() => previewOf(target)) : null
}

async function slideshowTick(signal: AbortSignal) {
  if (!canNext.value) return stopSlideshow('ended')
  current.value += 1
  await nextTick() // the item's watchers ran: its preview load started (taking the prefetch), its details were held back
  if (signal.aborted) return
  if (!canNext.value) return stopSlideshow('ended') // stops ON the last item
  prefetchAhead()
  await previewLoad
}
function startSlideshow() {
  if (playing.value || !canNext.value) return
  zoom.reset()
  playing.value = true
  slideshowState.value = 'playing'
  prefetchAhead()
  slideshow.start(slideshowTick)
}
function stopSlideshow(state: 'paused' | 'ended') {
  if (!playing.value) return
  slideshow.stop()
  playing.value = false
  slideshowState.value = state
  ahead = null
  if (detailsDeferred && detailsDeferred === item.value?.mediaId) void loadDetails()
}
const toggleSlideshow = () => (playing.value ? stopSlideshow('paused') : startSlideshow())
const isSlideshowToggle = (target: EventTarget | null) => target instanceof Element && !!target.closest('[data-slideshow-toggle]')
/** Any pointer or wheel in the lightbox pauses — the zoom's gestures and the details panel included; the toggle is its own. */
function pauseOnInteraction(event: Event) {
  if (playing.value && !isSlideshowToggle(event.target)) stopSlideshow('paused')
}
// Native capture listeners, not Vue's `@pointerdown.capture`: the first Vue invoker an event meets stamps it (`e._vts`),
// and every later Vue handler on its path drops an event stamped no later than that handler was attached. With a Vue
// capture handler on this root, the stage's own zoom handlers lost presses intermittently (measured 2026-10-14: the zoom
// tests flaked in 5 of 5 runs with it, in 0 of 6 without); why the stage's handler counted as attached that late was not
// established - keep the root's listeners native.
const rootEl = ref<HTMLElement | null>(null)
onMounted(() => {
  rootEl.value?.addEventListener('pointerdown', pauseOnInteraction, { capture: true })
  rootEl.value?.addEventListener('wheel', pauseOnInteraction, { capture: true, passive: true })
})
function onVisibility() {
  if (document.visibilityState === 'hidden') stopSlideshow('paused')
}
document.addEventListener('visibilitychange', onVisibility)
watch(() => props.suspendKeys, (suspended) => {
  if (suspended) stopSlideshow('paused') // a modal opened over the lightbox
})

// Load the current item's preview tier (operator-blind: ciphertext → decrypt → object URL). A non-derivable
// item (E1) carries no preview tier, so the stage shows a download fallback instead of fetching its original.
async function loadPreview() {
  clearPreview()
  dropZoom() // navigation resets the zoom and revokes the previous item's original
  clearVideo() // drop the previous item's playing video (revoke its blob URL)
  stopSwPlayback() // and tear down any SW playback of the previous item (revoke handle + port)
  videoLoading.value = false
  error.value = false
  if (!item.value || !previewable.value) {
    ahead = null // a prefetch for another item is not kept
    return
  }
  loading.value = true
  const target = item.value
  try {
    const bytes = await (takeAhead(target) ?? previewOf(target)) // the slideshow's prefetch, when it is this item's
    if (item.value !== target) return // navigated away while decrypting — drop this result
    setPreview(new Blob([bytes as BlobPart]))
  } catch {
    if (item.value === target) error.value = true
  } finally {
    if (item.value === target) loading.value = false
  }
}

// --- zoom (cr-lightbox-zoom-slideshow) ---------------------------------------------------------------------------------
// Only a shown photo zooms: not a video (poster or playing), not an error or a fallback. The transform sits on the stage's
// <img>; the stage is the viewport the pan is bounded to.
const stageEl = ref<HTMLElement | null>(null)
const stageImg = ref<HTMLImageElement | null>(null)
const zoomable = computed(() => !!previewUrl.value && !videoType.value && !error.value && !videoSwUrl.value && !videoUrl.value)
const zoom = usePanZoom(() => {
  const img = stageImg.value
  const stage = stageEl.value
  return img && stage ? { content: { w: img.clientWidth, h: img.clientHeight }, viewport: stage.getBoundingClientRect() } : null
})
// The original past the preview's resolution: its own object-URL owner, shown once it decoded (`originalReady`), asked
// for once per shown item (`originalAsked` holds that item's id), dropped on navigation and revoked on unmount.
const { url: originalUrl, set: setOriginal, clear: clearOriginal } = useObjectUrl()
const originalReady = ref(false)
let originalAsked: string | null = null
// The preview's own pixel size while the original shows: the original is drawn no larger than the preview was, so the
// swap lands in place at the same transform (same box, same aspect, more pixels).
const previewNatural = ref<{ w: number; h: number } | null>(null)
const shownImageUrl = computed(() => (originalReady.value && originalUrl.value ? originalUrl.value : previewUrl.value))
const stageImageStyle = computed(() => ({
  transform: zoom.transform.value,
  ...(originalReady.value && previewNatural.value
    ? { maxWidth: `min(100%, ${previewNatural.value.w}px)`, maxHeight: `min(100%, ${previewNatural.value.h}px)` }
    : {}),
}))

function dropZoom() {
  zoom.reset()
  clearOriginal()
  originalReady.value = false
  previewNatural.value = null
  originalAsked = null
}

/** Past the preview's resolution, fetch the original once for this item - where its type and size allow (`lightboxZoom.ts`). */
async function maybeLoadOriginal() {
  const target = item.value
  const img = stageImg.value
  if (!target || !img || !zoomable.value || originalAsked === target.mediaId) return
  if (!originalZoomable(mediaType.value, target.original.byteSize)) return
  if (!previewDownscaled(img.naturalWidth, img.naturalHeight)) return // the original has no more pixels than this
  if (!exceedsPreview(img.clientWidth, zoom.scale.value, window.devicePixelRatio, img.naturalWidth)) return
  originalAsked = target.mediaId
  const natural = { w: img.naturalWidth, h: img.naturalHeight }
  try {
    const bytes = await originalOf(target)
    if (item.value !== target) return // navigated away while decrypting — drop it
    setOriginal(new Blob([bytes as BlobPart]))
    const probe = new Image()
    probe.src = originalUrl.value!
    await probe.decode() // a type that claims a raster but does not decode keeps the preview
    if (item.value !== target) return
    previewNatural.value = natural
    originalReady.value = true
  } catch {
    if (item.value === target) clearOriginal() // the preview stays, silently
  }
}
watch(zoom.scale, () => void maybeLoadOriginal())

// Reload when the SHOWN item changes — by identity, not just the index: after a delete (cr-0110) the parent
// removes the item and the array shifts, so the same index now holds a different item (its mediaId changes).
watch(() => item.value?.mediaId, () => { previewLoad = loadPreview() }, { immediate: true })

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
  if (playing.value) {
    detailsDeferred = target.mediaId // no EXIF read, no place backfill while the slideshow runs: pausing here runs them
    return
  }
  detailsDeferred = null
  try {
    const loaded = await exifOf(target)
    if (item.value === target) exif.value = loaded // ignore a result for an item we've navigated away from
  } catch {
    if (item.value === target) exif.value = null
  }
  void resolvePlaceForCurrent() // fire-and-forget: the panel is already rendered with the coordinate
}
watch(() => item.value?.mediaId, loadDetails, { immediate: true })

// --- capturedAt (cr-0066): effective time = override ?? derived original, from the listed capture-time tier ---
const effectiveCaptured = computed(() => effectiveCapturedAt(captured.value))
const isCapturedAtCorrected = computed(() => captured.value?.capturedAtOverride !== undefined)
// editable as soon as the item is shown — even with no date, the user can *set* one (a fresh tier). Only where the
// source carries the medium's meta door: a guest's has none (no guest route writes a medium's meta).
const canEditCapturedAt = computed(() => !!item.value && !!props.mediaMeta)

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
  const write = props.mediaMeta
  if (!target || !write) return
  savingCapturedAt.value = true
  capturedAtError.value = null
  try {
    const updated: MediaMetaWrite = await write.set(target, props.memoryKey, overrideMs)
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
// a writable surface (the source's `mediaMeta` door — a guest's has none and must never write), and no name sealed yet.
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
  const write = props.mediaMeta
  if (!target || !meta || !write) return
  if (!hasGps.value || meta.placeName !== undefined) return
  if (!geocodingAvailable.value) return // consent off → resolvePlaceName is never even asked
  const resolved = await resolvePlaceName(geocoderUrl.value, meta.gpsLat!, meta.gpsLng!, locale.value)
  if (!resolved) return
  try {
    const updated = await write.backfillPlace(target, props.memoryKey, resolved.name, resolved.level)
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
  // The capture date comes from the always-listed capture-time tier, the file's name and format from the original's
  // blob meta, its size from the listed ref — all independent of the lazy EXIF; everything below them comes from the
  // EXIF tier once it loads.
  const rows = [
    { label: t('media.captured'), value: formatCaptured(effectiveCaptured.value) },
    { label: t('media.fileName'), value: blobMeta.value?.name ?? '' },
    { label: t('media.fileSize'), value: fileSize(item.value ? plaintextSize(item.value.original) : undefined) },
    { label: t('media.fileFormat'), value: mediaType.value ?? '' },
  ]
  if (e) {
    rows.push(
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
const uploader = computed(() => (item.value && props.uploaderOf ? props.uploaderOf(item.value) : null))
const hasDetail = computed(() => metadataRows.value.length > 0 || hasGps.value || uploader.value !== null)

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
    const provider = originalProviderOf(target)
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
  try {
    // Streamed (inbox #371 S4): the original's chunk provider — the one playback uses, so the share viewer's injected
    // provider covers a guest's download too — written chunk by chunk into a picked file, the SW download route or a
    // capped Blob. Nothing is awaited before this call: the save picker needs the click's activation (download.ts).
    // Named by the original's own file name from its blob meta (inbox #397), known with the listing — the Memory name
    // and the gallery position only name a media whose original carries none.
    const name = originalDownloadName(blobMetaOf(target), props.memoryName, current.value)
    await downloadOriginal(originalProviderOf(target), name)
  } catch (e) {
    if (e instanceof SaveTooLarge) toast.error(t('media.downloadTooLarge'))
    else if (!isSaveCancelled(e)) error.value = true
  } finally {
    downloading.value = false
  }
}

function emitDelete() {
  if (!item.value || !props.canDelete(item.value)) return
  emit('delete', { mediaId: item.value.mediaId }) // the parent trashes it with an undo, or purges a guest's own (asked first)
}

function onKeydown(event: KeyboardEvent) {
  // Any key pauses the slideshow — except the activation keys on its own toggle, whose click would start it again.
  if (!(isSlideshowToggle(event.target) && (event.key === 'Enter' || event.key === ' '))) pauseOnInteraction(event)
  if (props.suspendKeys) return // a modal (cover cropper) is open over the lightbox — let it own the keyboard
  if (zoomable.value && !isEditingCapturedAt.value && zoom.onKey(event.key)) return // + / - / 0, and the arrows' pan while zoomed
  if (event.key === 'Escape') {
    if (zoom.zoomed.value) zoom.reset() // Escape un-zooms first, then closes
    else emit('close')
  } else if (zoom.zoomed.value && (event.key === 'ArrowLeft' || event.key === 'ArrowRight')) return // no paging while zoomed (the arrows pan, above)
  else if (event.key === 'ArrowLeft') prev()
  else if (event.key === 'ArrowRight') next()
  else if (event.key === 'Delete' || event.key === 'Backspace') { event.preventDefault(); emitDelete() } // cr-0110 culling hotkey
}
window.addEventListener('keydown', onKeydown)

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  document.removeEventListener('visibilitychange', onVisibility)
  rootEl.value?.removeEventListener('pointerdown', pauseOnInteraction, { capture: true })
  rootEl.value?.removeEventListener('wheel', pauseOnInteraction, { capture: true })
  slideshow.stop()
  ahead = null
  stopSwPlayback() // revoke any live SW playback (its port + in-SW plaintext cache) when the lightbox unmounts
})
</script>

<template>
  <div
    ref="rootEl"
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
        <span class="visually-hidden" role="status" aria-live="polite" data-testid="slideshow-status">{{ slideshowAnnouncement }}</span>
      </div>
      <div class="media-lightbox-actions">
        <button
          v-if="items.length > 1"
          class="veyq-icon-button lightbox-icon-button"
          type="button"
          data-slideshow-toggle
          :aria-label="t(playing ? 'media.slideshowPause' : 'media.slideshowStart')"
          :title="t(playing ? 'media.slideshowPause' : 'media.slideshowStart')"
          :disabled="!playing && !canNext"
          @click="toggleSlideshow"
        >
          <span class="pi" :class="playing ? 'pi-pause' : 'pi-play'" />
        </button>
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
          v-if="previewable && canSetCover"
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
          v-if="item && canDelete(item)"
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

        <div
          ref="stageEl"
          class="media-lightbox-stage"
          :class="{
            'media-lightbox-stage-zoomable': zoomable,
            'media-lightbox-stage-zoomed': zoomable && zoom.zoomed.value,
            'media-lightbox-stage-gesturing': zoom.gesturing.value,
          }"
          @pointerdown="zoomable && zoom.onPointerDown($event)"
          @pointermove="zoomable && zoom.onPointerMove($event)"
          @pointerup="zoomable && zoom.onPointerUp($event)"
          @pointercancel="zoomable && zoom.onPointerUp($event)"
          @wheel="zoomable && zoom.onWheel($event)"
        >
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
          <img
            v-else-if="previewUrl"
            ref="stageImg"
            :src="shownImageUrl ?? undefined"
            :alt="memoryName"
            :style="stageImageStyle"
            draggable="false"
          />
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
            <template v-if="uploader">
              <dt>{{ t('media.uploadedBy') }}</dt>
              <dd data-testid="lightbox-uploader">
                <RouterLink v-if="uploader.userId" :to="{ hash: personHash(uploader.userId) }" class="media-lightbox-uploader-link" @click="emit('close')">{{ uploader.name }}</RouterLink>
                <template v-else>{{ uploader.name }}</template>
              </dd>
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
          <!-- Only when nothing at all is known: it hung off the map's v-if before, so every photo without a coordinate
               showed its rows AND "no details" (seen once inbox #397 listed a file's own facts for every media). -->
          <p v-if="!hasDetail" class="media-lightbox-no-detail">{{ t('media.noDetail') }}</p>
        </template>
      </aside>
    </main>
  </div>
</template>

<style scoped>
/* The zoom (cr-lightbox-zoom-slideshow): a shown photo takes the gestures itself (no browser pan / pinch-zoom of the page),
   a zoomed one is clipped to the stage so it never covers the paging buttons or the details, and the transform eases
   except while a finger or the mouse drives it. */
.media-lightbox-stage-zoomable {
  touch-action: none;
  user-select: none;
  cursor: zoom-in;
}
.media-lightbox-stage-zoomed {
  overflow: hidden;
  cursor: grab;
}
.media-lightbox-stage-zoomable img {
  transition: transform 0.12s ease-out;
  will-change: transform;
}
.media-lightbox-stage-gesturing {
  cursor: grabbing;
}
.media-lightbox-stage-gesturing img {
  transition: none;
}
/* The lightbox trash affordance (cr-0110): a subtle danger tint on hover, set apart from the neutral actions. */
.lightbox-delete-button:hover {
  color: var(--app-danger);
}
/* The "Set as cover" button, when THIS photo is the current cover: an accent-filled state ("it's the cover"). */
.lightbox-cover-active {
  color: var(--app-on-fill);
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
  color: var(--app-on-fill);
}
.media-lightbox-play:disabled {
  cursor: default;
  opacity: 0.8;
}
.media-lightbox-play .pi {
  margin-left: 0.2rem; /* optically center the play triangle */
}
</style>
