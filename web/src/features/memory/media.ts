// en.memory.media + en.memory.derivatives (web) — the encrypted media-blob data layer (cr-0028, cr-0045).
// Operator-blind, direct-to-store: a blob is sealed client-side as chunked AEAD under a fresh random K_blob
// (wrapped under the Memory's K_mem, ADR 0012), the ciphertext chunks travel straight to the object store via
// short-TTL presigned URLs (ADR 0013) — never through the backend — and the backend records only opaque
// metadata + the wrap + the media→node link.
//
// On add-media (cr-0045) the client also generates a **thumbnail** and a **preview** derivative (image: two
// downscales; video: a first-frame poster used for both), seals **each as its own K_blob blob**, and uploads
// the original + both derivatives in **one batch**. A media then carries them role-tagged, so a gallery fetches
// grid = thumbnail / lightbox = preview / download = original without ever pulling the full original for a grid.
// The pixel stage (decode → downscale → JPEG re-encode) is hidden behind an injectable MediaDeriver so the
// wiring is unit-testable; the real Canvas/<video> decode is exercised by a live browser round-trip.
import exifr from 'exifr'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { isCodedError, isHttpStatus, HttpStatus } from '../../shared/httpError'
import { blobApi } from '../../api/client'
import { mediaApi } from './api'
import { withRefresh } from '../../app/sessionRefresh'
import { type MediaListPage, type MediaSummary, type MediaDerivativeKind } from '../../api/generated/media'
import { type PresignedUrl, type BlobRole as WireBlobRole } from '../../api/generated/blob'
import { expectMintedId } from '../../core/node/mintedId'
import { mediaKey, uploaderOriginOf, type UploaderOrigin } from './mediaKey'
import { MetaConflictError, MetaUnreadableError, writeSealedMeta, type MetaEditContext } from './sealedMetaWrite'
import type { ChunkProvider } from '../../sw/protocol'
import { createBrowserDeriver, deriveTiers, type MediaDeriver, type AddMediaSource, type Dimensions } from '../../shared/image/mediaDeriver'

// The on-device pixel stage lives in `shared/image/mediaDeriver` (cr-0143). Re-exported here so existing importers of
// `./media` (share.ts, useOptimisticTiles.ts, uploadQueue.ts, tests) keep their import path unchanged.
export { scaleToMaxEdge, TIER_SPECS, createBrowserDeriver, type MediaDeriver, type AddMediaSource, type Dimensions } from '../../shared/image/mediaDeriver'

// The per-uploader "Beiträge" buckets (en.memory.buckets, ADR 0034) live in `./mediaBuckets` (#96) — uploader
// metadata, not blob data. Re-exported here so existing importers of `./media` (MemoryBuckets.vue, tests) keep
// their import path unchanged.
export { openBucketName, listBuckets, createBucket, renameBucket, deleteBucket, moveBucketMedia, type BucketItem } from './mediaBuckets'

/**
 * Every seal in this layer binds the object it is stored as (ADR 0072). A `K_blob` wrap is bound to (scope, owner,
 * blob, role) — the owner is the media for its tiers and the node for a cover — so a server that attaches a blob
 * under another media, or serves a thumbnail in another's slot, gets a wrap that does not open there.
 *
 * A media's seals are under `K_mem`, except for one a WRITE_ONLY guest added: that one's are under the guest's own
 * key (ADR 0031 point 7). A function handed the MEDIA resolves the key itself ([mediaKey]); one handed only an id
 * or a blob ref is handed the key, which its caller asks [mediaKey] for.
 */
const blobKeyContext = (scopeId: string, ref: { blobId: string; ownerId: string; role: BlobRole }) =>
  ({ purpose: 'blob-key', scopeId, ownerId: ref.ownerId, blobId: ref.blobId, role: ref.role }) as const
/**
 * The sealed **listed-metadata** tier of one media (cr-0068), bound to (scope, media). This one blob carries the
 * geometry (`w,h`), the capture time (`capturedAt*`), and the GPS (`gpsLat,gpsLng`) — everything the grid, the
 * date sort, and the place medoid need at list-level. Consolidates the former `media-layout` + `media-capturedat`.
 */
const mediaMetaContext = (scopeId: string, mediaId: string) => ({ purpose: 'media-meta', scopeId, mediaId }) as const
/** The sealed heavy EXIF tier of one media (cr-0064) — its own purpose, bound to (scope, media). */
const mediaExifContext = (scopeId: string, mediaId: string) => ({ purpose: 'media-exif', scopeId, mediaId }) as const

/** Plaintext bytes per full chunk before sealing — the contract's frame size (ADR 0013 §3), not a choice made here:
 *  the server derives `chunkCount` from `byteSize` on exactly this and refuses any other. */
export const CHUNK_SIZE = v1.CHUNK_FRAME_SIZE.plaintextBytes

// --- read-back model -------------------------------------------------------

/** The role a blob plays for its owner — the `K_blob` wrap is sealed bound to it (ADR 0072). The crypto suite's role is
 *  an open token (`v1.BlobRole`, a string) since Files brought FILE_CONTENT; this layer keeps the four it writes, which
 *  the blob contract still closes, so a declare type-checks against the wire item and no string role reaches it. */
export type BlobRole = WireBlobRole

/**
 * Everything needed to open one blob: its id + the metadata + the K_blob wrap (under K_mem) + where that wrap is
 * sealed — its owner (the media it is a tier of, or the node it is the cover of) and its role there. The owner and
 * role are set by whoever built the ref from the row it addressed (a media row's id, the node whose cover it is),
 * never read back from the blob, so a ref the server moved to another row carries that row's owner and refuses.
 */
export type BlobRef = {
  blobId: string
  chunkCount: number
  byteSize: number
  wrappedBlobKey: string
  ownerId: string
  role: BlobRole
}

/** One role-tagged derivative tier of a media. */
export type MediaDerivative = { kind: MediaDerivativeKind; ref: BlobRef }

/**
 * The **listed-metadata tier** sealed under `K_mem` (cr-0068) — one always-listed blob (returned with every
 * `listMedia`) carrying everything the gallery + place need at list-level, kept small:
 * - **geometry** `w,h` — the justified grid's aspect (cr-0059);
 * - **capture time** `capturedAt` (EXIF date or the file's creation date) + any user `capturedAtOverride` — date
 *   sort/display/edit (cr-0066). Effective = `capturedAtOverride ?? capturedAt`. `capturedAtSource` records
 *   where the derived `capturedAt` came from: `'exif'` (a real `DateTimeOriginal`, trustworthy) or `'file'` (the
 *   file's modification date, a fallback that must NOT feed a Memory's `auto` date range — an edited photo's
 *   file date would wrongly stretch it);
 * - **GPS** `gpsLat,gpsLng` (decimal degrees) — the source of a Memory's place medoid (cr-0068, uc.memory.place).
 * - **media type** `mediaType` — the original's full MIME (cr-inline-video-playback, #104/#105). Sealed here
 *   rather than exposed as a contract field precisely because a server-visible one would leak photo-vs-video-vs-PDF
 *   for every medium ([ADR 0044](../../../docs/decisions/0044-field-visibility-declared-at-the-field.md)). It is
 *   the persisted video signal: `video/*` draws the tile's play badge and the lightbox's `<video>` branch without
 *   fetching the original. Set at upload from the file's own type; back-filled for pre-field media on first
 *   original-fetch ([sniffMediaType]).
 *
 * Every field is optional (best-effort). Versioned `v`, opaque — grows without a contract change.
 */
export type MediaMeta = {
  v: number
  w?: number
  h?: number
  capturedAt?: number
  capturedAtOverride?: number
  capturedAtSource?: 'exif' | 'file'
  gpsLat?: number
  gpsLng?: number
  mediaType?: string
  /**
   * The resolved place name for this photo's own coordinate, and the hierarchy level it was taken from
   * (`city`, `country`, …). Sealed once, then read from the tier forever — the provider is asked once per
   * place, never once per open ([ADR 0016](../../../../docs/decisions/0016-geocoding-provider-neutrality.md)
   * § Request discipline). **The only field this CR may add to the listed tier**, because that tier is capped
   * at 512 base64 and is shared with the capture-time PUT: overflow would break that write too, which is why
   * the name is clamped to [PLACE_NAME_MAX_BYTES] before it is sealed.
   */
  placeName?: string
  placeLevel?: string
}

/**
 * The heavy **EXIF bundle** sealed under `K_mem`, lazy-loaded per item for the lightbox (cr-0064). A client-only,
 * versioned shape (never a contract field), so it can grow without the operator ever learning a field. Every
 * field is optional (best-effort extraction). The listed essentials — capture time (cr-0066) and GPS (cr-0068) —
 * live in the [MediaMeta] tier, not here.
 */
export type MediaExif = {
  v: number
  /** The original's filename, sealed here and nowhere else — it never reaches the server (uc.memory.gallery). */
  name?: string
  make?: string
  model?: string
  lens?: string
  focalLength?: number
  /** The 35 mm-equivalent focal length, which is what makes a crop-sensor number comparable. */
  focalLength35?: number
  fNumber?: number
  exposureTime?: number
  /** EV compensation the photographer dialled in. Signed — exifr calls this `ExposureCompensation`. */
  exposureCompensation?: number
  iso?: number
  /** The raw EXIF Flash **bitfield**, not a boolean: bit 0 is "fired", so 16 is truthy and means it did NOT.
   *  Kept raw so the surface can phrase it in the reader's language instead of the library's English. */
  flash?: number
  orientation?: number
  /** Provenance the editor wrote in: the software, and who claims the picture. */
  software?: string
  artist?: string
  copyright?: string
  /** What an editor wrote into XMP/IPTC — a 0..5 star rating and the two text fields. */
  rating?: number
  title?: string
  description?: string
  /** Metres above sea level, already signed (`GPSAltitudeRef` 1 means below). */
  altitude?: number
  /** Where the camera was pointing, in degrees, and whether that is true or magnetic north. */
  imgDirection?: number
  imgDirectionRef?: string
  /** The fix's own claimed accuracy in metres — how much the coordinate should be trusted. */
  gpsAccuracy?: number
  w?: number
  h?: number
}

/** A node's media for the UI/read-back: the original blob ref + its role-tagged derivative tiers. */
export type MediaItem = {
  mediaId: string
  original: BlobRef
  derivatives: MediaDerivative[]
  /** The original's listed-metadata tier sealed under `K_mem` (opaque base64); unwrap with [unsealMeta]. Absent when none. */
  wrappedMeta?: string
  /** The version [wrappedMeta] is at — what a meta write states it was built from (ADR 0077). Absent in the
   *  public-share viewer, which never writes; a write that lacks it states 0 and re-bases on the `409`. */
  metaVersion?: number
  /** When this media last changed (cr-0107) — the `(updatedAt, mediaId)` pair is the live-updates keyset cursor.
   *  Present for the authenticated gallery (`listMedia`/`listMediaPage`); absent for the public-share snapshot
   *  viewer, which doesn't poll (the sharing resolve carries no per-media timestamp). */
  updatedAt?: Date
  /** When this media was trashed + who trashed it (cr-0110, the deletion audit) — present only in the Trash tab
   *  listing ([listTrash]); undefined for a live media. */
  deletedAt?: Date
  deletedBy?: string
  /** cr-0113: the uploader's opaque owner_key (`user:{id}` | `share:{id}`) — the buckets attribution the Beiträge
   *  tab groups by; undefined for a pre-buckets member row or the public-share viewer (no attribution). */
  ownerKey?: string
  /** cr-0113: the bucket this media lives in, or undefined = the uploader's primary. */
  bucketId?: string
  /** Set when a WRITE_ONLY guest added this media: its seals are under that guest's own key, which [mediaKey]
   *  opens from here (ADR 0031 point 7). Undefined for every other media, whose seals are under `K_mem`. */
  uploader?: UploaderOrigin
}

/** What a per-media write or lazy read needs: the media's id and, for one a WRITE_ONLY guest added, its origin. */
export type MediaRef = Pick<MediaItem, 'mediaId' | 'uploader'>

/** A media list row as the UI holds it — exported so a test can feed the read path a row the server shaped. */
export function summaryToItem(s: MediaSummary, uploader?: UploaderOrigin): MediaItem {
  // Each ref is owned by THIS row's media, in the role the row lists it in (ADR 0072).
  return {
    mediaId: s.mediaId,
    original: {
      blobId: s.blobId, chunkCount: s.chunkCount, byteSize: s.byteSize, wrappedBlobKey: s.wrappedBlobKey,
      ownerId: s.mediaId, role: 'ORIGINAL',
    },
    derivatives: (s.derivatives ?? []).map((d) => ({
      kind: d.kind,
      ref: {
        blobId: d.blobId, chunkCount: d.chunkCount, byteSize: d.byteSize, wrappedBlobKey: d.wrappedBlobKey,
        ownerId: s.mediaId, role: d.kind,
      },
    })),
    wrappedMeta: s.wrappedMeta ?? undefined,
    metaVersion: s.metaVersion,
    updatedAt: s.updatedAt,
    deletedAt: s.deletedAt ?? undefined,
    deletedBy: s.deletedBy ?? undefined,
    ownerKey: s.ownerKey ?? undefined,
    bucketId: s.bucketId ?? undefined,
    uploader,
  }
}

/** A listing page's rows as items — a row a WRITE_ONLY guest added carries its origin, which the page itself
 *  serves: the node's sealed drop key and the guest's wrapped key (ADR 0031 point 7). */
function pageItems(nodeId: string, page: MediaListPage): MediaItem[] {
  const wrapped = new Map((page.uploaderKeys ?? []).map((k) => [k.identityId, k.wrappedIdentityKey]))
  return page.media.map((s) => summaryToItem(s, uploaderOriginOf(s.ownerKey, nodeId, page.sealedDropPrivateKey, wrapped)))
}

/**
 * Unwrap a media's sealed listed-metadata tier under `K_mem` (cr-0068; operator-blind — the server only ever
 * holds the ciphertext). Best-effort: null when none was sealed or the wrap doesn't open. Geometry `w,h` is
 * validated (positive, finite) so a bad payload falls back to a default aspect rather than break the grid; the
 * other fields are coerced to finite numbers. Use [effectiveCapturedAt] for the sort/display date.
 */
export function unsealMeta(scopeId: string, memoryKey: Uint8Array, item: MediaItem): MediaMeta | null {
  if (!item.wrappedMeta) return null
  try {
    const key = mediaKey(scopeId, memoryKey, item)
    const json = new TextDecoder().decode(v1.open(mediaMetaContext(scopeId, item.mediaId), key, fromB64std(item.wrappedMeta)))
    const parsed = JSON.parse(json) as unknown
    if (parsed && typeof parsed === 'object') return normalizeMediaMeta(parsed as RawMediaMeta)
  } catch {
    /* not openable / malformed → treated as no metadata */
  }
  return null
}

/**
 * A listed tier exactly as it was sealed — every field, known or not. Writers start from THIS, never from
 * [unsealMeta]'s normalized copy: that copy is a display projection, and re-sealing a projection drops whatever it
 * does not carry. It once left out the resolved place name, so every date correction erased it (ADR 0077 point 4).
 */
type RawMediaMeta = { v: number } & Record<string, unknown>

/** The display projection of a raw tier: numbers coerced, geometry validated, unknown fields left out. */
function normalizeMediaMeta(m: RawMediaMeta): MediaMeta {
  const w = finiteNum(m.w)
  const h = finiteNum(m.h)
  const geo = w !== undefined && h !== undefined && w > 0 && h > 0
  const source = m.capturedAtSource === 'exif' || m.capturedAtSource === 'file' ? m.capturedAtSource : undefined
  return {
    v: finiteNum(m.v) ?? 1,
    w: geo ? w : undefined,
    h: geo ? h : undefined,
    capturedAt: finiteNum(m.capturedAt),
    capturedAtOverride: finiteNum(m.capturedAtOverride),
    capturedAtSource: source,
    gpsLat: finiteNum(m.gpsLat),
    gpsLng: finiteNum(m.gpsLng),
    mediaType: cleanStr(m.mediaType),
    placeName: cleanStr(m.placeName),
    placeLevel: cleanStr(m.placeLevel),
  }
}

/** The listed tier for a WRITE: absent → a fresh `{ v: 1 }`; present but unopenable or unparseable → refused. */
function openMediaMetaForWrite(scopeId: string, memoryKey: Uint8Array, media: MediaRef, wrappedMeta: string | null): RawMediaMeta {
  if (!wrappedMeta) return { v: 1 }
  let parsed: unknown
  try {
    const key = mediaKey(scopeId, memoryKey, media)
    parsed = JSON.parse(new TextDecoder().decode(v1.open(mediaMetaContext(scopeId, media.mediaId), key, fromB64std(wrappedMeta))))
  } catch {
    throw new MetaUnreadableError()
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new MetaUnreadableError()
  return { ...(parsed as Record<string, unknown>), v: finiteNum((parsed as { v?: unknown }).v) ?? 1 }
}

/**
 * The original's **plaintext** size in bytes, recovered from what the server stores. Every chunk is framed with
 * a 12-byte nonce + a 16-byte AEAD tag, so the ciphertext carries exactly `FRAME_OVERHEAD` bytes per chunk that the file
 * never had. Showing `byteSize` raw would report a size the user's own file does not have — and would drift
 * with the chunking, not with the photo.
 */
export function plaintextSize(ref: { byteSize: number; chunkCount: number }): number {
  return Math.max(0, ref.byteSize - v1.FRAME_OVERHEAD * ref.chunkCount)
}

/** The effective capture time of a media's metadata tier: the user's correction if set, else the derived original. */
export function effectiveCapturedAt(meta: MediaMeta | null): number | undefined {
  return finiteNum(meta?.capturedAtOverride) ?? finiteNum(meta?.capturedAt)
}

// --- the capture-time key ---------------------------------------------------
//
// ONE rule with TWO consumers, which is why it is a named export rather than a line inside `sealMetadata`:
// the metadata tier sealed at upload (below), and the in-flight upload tile that must know where it belongs in
// the gallery **before a byte is uploaded** (`useOptimisticTiles`). If these two ever disagreed, a tile would
// jump the moment its upload settled — which is the defect this rule's single definition exists to prevent.

/** A media's capture time and where it came from — an EXIF date is trustworthy, a file date is a fallback. */
export type CaptureTime = { capturedAt?: number; capturedAtSource?: 'exif' | 'file' }

/**
 * The rule: the EXIF date if the image carries one, else the file's own date. The source is recorded because
 * only a trustworthy EXIF date (or a user override) may feed a Memory's `auto` range (cr-0067) — a file
 * modification date must not stretch it.
 */
export function captureTimeOf(exifDate: number | undefined, lastModified: number | undefined): CaptureTime {
  const capturedAt = exifDate ?? finiteNum(lastModified)
  if (capturedAt === undefined) return {}
  return { capturedAt, capturedAtSource: exifDate !== undefined ? 'exif' : 'file' }
}

/**
 * The same rule applied to a **picked file**, before anything is read into memory or uploaded: parse its EXIF
 * date (best-effort — a non-image, absent EXIF or a decode error yields nothing) and fall back to the date the
 * file itself carries. `lastModified` is passed explicitly rather than read off the blob, so the fallback half
 * of the rule is visible at the call site and a caller cannot silently lose it.
 */
export async function readCaptureTime(file: Blob, mediaType: string, lastModified: number | undefined): Promise<CaptureTime> {
  if (!mediaType.startsWith('image/')) return captureTimeOf(undefined, lastModified)
  try {
    const raw = await exifr.parse(file, { tiff: true, exif: true })
    return captureTimeOf(raw ? exifCapturedAt(raw) : undefined, lastModified)
  } catch {
    return captureTimeOf(undefined, lastModified)
  }
}

// --- add-media -------------------------------------------------------------

/**
 * A blob about to be uploaded: its plaintext source, a fresh `K_blob` (with its wrap under `K_mem`) and the shape
 * the frame size fixes. Nothing is sealed yet — the pump seals frame `i` from `source.slice` when it is about to
 * send it, so the client never holds the whole file, nor its sealed copy (ADR 0013 Consequences).
 */
type BlobPlan = {
  source: Blob
  blobKey: Uint8Array
  wrappedBlobKey: string
  chunkCount: number
  byteSize: number
  blobId: string
  ownerId: string
  role: BlobRole
}

/** Plan `source` as a blob under a fresh random `K_blob`, wrapped under `K_mem` bound to where it will live — the
 *  client-minted blob id, its owner and its role (ADR 0072). Its shape follows from its size alone: every frame
 *  but the last carries [CHUNK_SIZE] plaintext bytes, and an empty source is one empty frame. */
function planBlob(scopeId: string, owner: { ownerId: string; role: BlobRole }, memoryKey: Uint8Array, source: Blob): BlobPlan {
  const blobKey = v1.randomBytes(32)
  const blobId = crypto.randomUUID()
  const wrappedBlobKey = b64std(v1.seal(blobKeyContext(scopeId, { blobId, ...owner }), memoryKey, blobKey))
  const chunkCount = Math.max(1, Math.ceil(source.size / CHUNK_SIZE))
  return { source, blobKey, wrappedBlobKey, chunkCount, byteSize: source.size + chunkCount * v1.FRAME_OVERHEAD, blobId, ...owner }
}

/** Read and seal frame `index` of a planned blob — one [CHUNK_SIZE] slice of the source, never more. */
async function sealFrameAt(plan: BlobPlan, index: number): Promise<Uint8Array> {
  const slice = plan.source.slice(index * CHUNK_SIZE, (index + 1) * CHUNK_SIZE)
  const plain = new Uint8Array(await slice.arrayBuffer())
  // Outside the typed seal union on purpose (ADR 0072 Decision 2): the frame key is this blob's own K_blob, so the
  // key is the object binding; the AAD binds only the frame's position (index ‖ total).
  return v1.sealFrame(plan.blobKey, index, plan.chunkCount, plain, v1.randomBytes(12))
}

/** Seal a small JSON payload under `K_mem` as the object `context` names (a short AEAD blob) → opaque base64. */
function sealJson(memoryKey: Uint8Array, value: unknown, context: v1.SealContext): string {
  const bytes = new TextEncoder().encode(JSON.stringify(value))
  return b64std(v1.seal(context, memoryKey, bytes))
}

function finiteNum(n: unknown): number | undefined {
  return typeof n === 'number' && Number.isFinite(n) ? n : undefined
}

function cleanStr(s: unknown): string | undefined {
  const t = typeof s === 'string' ? s.trim() : ''
  return t.length ? t : undefined
}

/** The EXIF capture date, by its one precedence rule. Both readers of an image's date go through here. */
function exifCapturedAt(raw: { DateTimeOriginal?: unknown; CreateDate?: unknown; ModifyDate?: unknown }): number | undefined {
  return toEpochMs(raw.DateTimeOriginal ?? raw.CreateDate ?? raw.ModifyDate)
}

/** Coerce an EXIF date (exifr yields a `Date`; some sources a number) to epoch-ms, or undefined when unusable. */
function toEpochMs(d: unknown): number | undefined {
  if (d instanceof Date) return finiteNum(d.getTime())
  return finiteNum(d)
}

/**
 * The option set every EXIF read passes — exported because the fixture test parses with **exactly this** or it
 * proves nothing about the client (`readExif.fixture.test.ts`).
 *
 * **`translateValues: false` is load-bearing, and was measured rather than assumed.** With exifr's default
 * translation `Flash` arrives as the English string "Flash fired, compulsory flash mode" and `Orientation` as
 * "Rotate 90 CW" — so a bit test on Flash silently reads 0, and `finiteNum(raw.Orientation)` has been yielding
 * `undefined` since this bundle was introduced, meaning the sealed `orientation` field never once carried a
 * value. Raw values also keep the phrasing ours: a library's English string sealed into a user's tier would be
 * shown untranslated to a German reader. `reviveValues` stays ON, because it is what makes `DateTimeOriginal` a
 * `Date`, which the capture-time rule depends on.
 */
export const EXIF_PARSE_OPTIONS = { tiff: true, exif: true, gps: true, xmp: true, iptc: true, translateValues: false } as const

/** XMP text arrives as a language alternative (`{ lang, value }`), not a string. Take the value. */
function xmpText(v: unknown): string | undefined {
  if (typeof v === 'string') return v
  if (v && typeof v === 'object' && 'value' in v) return cleanStr((v as { value: unknown }).value)
  return undefined
}

/** Altitude, signed. `GPSAltitudeRef` 1 means *below* sea level, and exifr hands it back byte-indexed. */
function signedAltitude(alt: unknown, ref: unknown): number | undefined {
  const metres = finiteNum(alt)
  if (metres === undefined) return undefined
  const refByte = typeof ref === 'object' && ref !== null ? finiteNum((ref as Record<number, unknown>)[0]) : finiteNum(ref)
  return refByte === 1 ? -metres : metres
}

/** Whether an EXIF bundle carries anything beyond the version tag (else there's nothing worth sealing). */
function hasExifPayload(e: MediaExif): boolean {
  return Object.entries(e).some(([k, val]) => k !== 'v' && val !== undefined)
}

/** Probe the original's intrinsic `{w,h}` (best-effort — undecodable/unsupported/no prober → null). */
async function probeDims(source: AddMediaSource, deriver: MediaDeriver): Promise<Dimensions | null> {
  if (!deriver.probeDimensions) return null
  try {
    return await deriver.probeDimensions(source.blob, source.mediaType)
  } catch {
    return null
  }
}

/**
 * Read an image's original bytes with `exifr` (HEIC/JPEG/TIFF/PNG; GPS auto-decoded DMS→decimal) and split the
 * result into the **capture time** (epoch-ms, cr-0066) and the **panel bundle** ([MediaExif], date-free).
 * Best-effort: non-images, unreadable/absent EXIF, or a decode error → `{ capturedAt: undefined, exif: null }`
 * (the item still uploads). Never leaves the device in the clear — the caller seals both.
 */
async function readExif(source: AddMediaSource): Promise<{ capturedAt?: number; gpsLat?: number; gpsLng?: number; exif: MediaExif | null }> {
  if (!source.mediaType.startsWith('image/')) return { exif: null }
  try {
    const raw = await exifr.parse(source.blob, EXIF_PARSE_OPTIONS)
    if (!raw) return { exif: null }
    const exif: MediaExif = {
      v: 1,
      name: cleanStr(source.name),
      make: cleanStr(raw.Make),
      model: cleanStr(raw.Model),
      lens: cleanStr(raw.LensModel ?? raw.LensMake),
      focalLength: finiteNum(raw.FocalLength),
      focalLength35: finiteNum(raw.FocalLengthIn35mmFormat),
      fNumber: finiteNum(raw.FNumber),
      exposureTime: finiteNum(raw.ExposureTime),
      exposureCompensation: finiteNum(raw.ExposureCompensation),
      iso: finiteNum(raw.ISO),
      flash: finiteNum(raw.Flash),
      orientation: finiteNum(raw.Orientation),
      software: cleanStr(raw.Software ?? raw.CreatorTool),
      artist: cleanStr(raw.Artist ?? raw.Creator),
      copyright: cleanStr(raw.Copyright ?? raw.Rights),
      rating: finiteNum(raw.Rating),
      title: cleanStr(xmpText(raw.title) ?? raw.ObjectName),
      description: cleanStr(xmpText(raw.description) ?? raw.ImageDescription ?? raw['Caption-Abstract']),
      altitude: signedAltitude(raw.GPSAltitude, raw.GPSAltitudeRef),
      imgDirection: finiteNum(raw.GPSImgDirection),
      imgDirectionRef: cleanStr(raw.GPSImgDirectionRef),
      gpsAccuracy: finiteNum(raw.GPSHPositioningError),
      w: finiteNum(raw.ExifImageWidth ?? raw.ImageWidth),
      h: finiteNum(raw.ExifImageHeight ?? raw.ImageHeight),
    }
    return {
      capturedAt: exifCapturedAt(raw),
      gpsLat: finiteNum(raw.latitude), // GPS rides the listed tier now (cr-0068), not the lazy EXIF
      gpsLng: finiteNum(raw.longitude),
      exif: hasExifPayload(exif) ? exif : null,
    }
  } catch {
    return { exif: null }
  }
}

/**
 * Seal the two metadata tiers under `K_mem` (best-effort, operator-blind): the always-listed **metadata** tier
 * (`{v,w,h,capturedAt,capturedAtSource,gpsLat,gpsLng}` — geometry + the EXIF date, else the file's creation date,
 * + GPS + a resolved place name) and the lazy, panel-only **EXIF** tier ([MediaExif]). Each tier is null when
 * its source is absent.
 */
async function sealMetadata(
  source: AddMediaSource,
  deriver: MediaDeriver,
  memoryKey: Uint8Array,
  /** The media the tiers belong to — both are sealed bound to it (ADR 0072). */
  media: { scopeId: string; mediaId: string },
  /** The geometry the tier derivation already measured, when it had it — an image always does. Absent for a
   *  video or an underivable source, and only then is a probe (its own decode) worth paying for. */
  knownDims: Dimensions | null = null,
): Promise<{ wrappedMeta: string | null; wrappedExif: string | null }> {
  const [dims, meta] = await Promise.all([knownDims ?? probeDims(source, deriver), readExif(source)])
  const { capturedAt, capturedAtSource } = captureTimeOf(meta.capturedAt, source.lastModified)
  const metaTier: MediaMeta = {
    v: 1,
    w: dims?.w,
    h: dims?.h,
    capturedAt,
    capturedAtSource,
    gpsLat: meta.gpsLat,
    gpsLng: meta.gpsLng,
    mediaType: cleanStr(source.mediaType), // the persisted video signal (#104/#105); the file's own type, sealed
  }
  const hasMeta =
    metaTier.w !== undefined || metaTier.capturedAt !== undefined || metaTier.gpsLat !== undefined ||
    metaTier.gpsLng !== undefined || metaTier.mediaType !== undefined
  const wrappedMeta = hasMeta ? sealJson(memoryKey, prune(metaTier), mediaMetaContext(media.scopeId, media.mediaId)) : null
  const wrappedExif = meta.exif ? sealExifTier(memoryKey, mediaExifContext(media.scopeId, media.mediaId), meta.exif) : null
  return { wrappedMeta, wrappedExif }
}

/**
 * The sealed EXIF tier's cap in base64 characters — `wrappedExif.maxLength` in `media.openapi.yaml`, which the
 * server enforces with a 400. The generated client carries no bounds, so the contract's value lives here too.
 */
export const EXIF_TIER_MAX_B64 = 16384

/**
 * Each free-text EXIF field's cap in UTF-8 bytes. Camera and editor strings are short; the fields a person
 * types (`description`, `title`, `copyright`) are where an unbounded value comes from. Ten fields at this cap
 * plus every number still seal under [EXIF_TIER_MAX_B64] — unless JSON escaping inflates them, which is what
 * the drop step in [sealExifTier] is for.
 */
export const EXIF_TEXT_MAX_BYTES = 1024

/**
 * Seal the EXIF tier so it always fits [EXIF_TIER_MAX_B64] — the upload never fails for its metadata. Every
 * string field is clamped to [EXIF_TEXT_MAX_BYTES]; a tier that still does not fit drops its longest free-text
 * field, one at a time, until it does. Numbers are never dropped: they are bounded by their type, so a tier of
 * numbers alone is a few hundred bytes. The string set is read off the value, not listed, so a text field added
 * to [MediaExif] later is clamped without anyone remembering to add it here.
 */
function sealExifTier(memoryKey: Uint8Array, context: v1.SealContext, exif: MediaExif): string {
  const tier: Record<string, unknown> = { ...exif }
  const textKeys = () => Object.keys(tier).filter((k) => typeof tier[k] === 'string')
  for (const k of textKeys()) tier[k] = clampUtf8(tier[k] as string, EXIF_TEXT_MAX_BYTES)
  let sealed = sealJson(memoryKey, tier, context)
  while (sealed.length > EXIF_TIER_MAX_B64) {
    const keys = textKeys()
    if (!keys.length) break // unreachable: see the doc above — numbers alone cannot reach the cap
    const longest = keys.reduce((a, b) => (JSON.stringify(tier[b]).length > JSON.stringify(tier[a]).length ? b : a))
    delete tier[longest]
    sealed = sealJson(memoryKey, tier, context)
  }
  return sealed
}

/** Drop `undefined` fields so a sealed blob carries only what's present (keeps it small + JSON tidy). */
function prune<T extends object>(obj: T): Partial<T> {
  return Object.fromEntries(Object.entries(obj).filter(([, v]) => v !== undefined)) as Partial<T>
}

/** Upload progress (0..1) + cancellation hooks for [addMedia], used by the upload queue (uc.memory.upload). */
export type AddMediaOptions = { onProgress?: (fraction: number) => void; signal?: AbortSignal }

/** Throw an AbortError if the caller cancelled — the queue maps it to a cancelled (not failed) upload. */
function throwIfAborted(signal?: AbortSignal): void {
  if (signal?.aborted) throw new DOMException('Upload cancelled', 'AbortError')
}

/** One blob to reserve: its declared shape + the K_blob wrap (under K_mem) + the ids that wrap is bound to. */
export type BlobDeclare = {
  blobId: string
  ownerId: string
  role: BlobRole
  chunkCount: number
  byteSize: number
  wrappedBlobKey: string
}
/** One presigned PUT: the chunk it is for + its URL. */
export type ChunkPut = { index: number; url: string }
/** One reserved upload: the session id + the PUTs of its first window (ADR 0013 §2 — never the whole blob). */
export type ReservedUpload = { uploadId: string; puts: ChunkPut[] }
/** Sign the window of an open upload that starts at `fromChunk` — each next window, or one whose URLs expired. */
export type WindowFn = (uploadId: string, fromChunk: number) => Promise<ChunkPut[]>
/** One logical media to complete: the original session + its role-tagged derivative sessions + the sealed metadata. */
export type CompleteDecl = {
  /** The client-minted media id every upload of it was started for (ADR 0072). */
  mediaId: string
  originalUploadId: string
  derivatives: { kind: MediaDerivativeKind; uploadId: string }[]
  wrappedMeta: string | null
  wrappedExif: string | null
}
/**
 * The upload control plane behind [uploadMediaVia] — reserve sessions (+ their first window of PUTs), sign each
 * next window, then complete + attach. The **authed** transport ([mediaApiTransport]) drives the internal Memory
 * upload; the **share-scoped** transport (share.ts) drives the anonymous contributor upload (cr-0101). The sealing
 * + direct-to-store PUTs are identical either way — only who authorizes the reserve/window/complete differs.
 */
export type MediaUploadTransport = {
  reserve(declares: BlobDeclare[]): Promise<ReservedUpload[]>
  window: WindowFn
  complete(media: CompleteDecl): Promise<{ blobId: string; mediaId: string }>
}

/** How many `PUT`s the pump keeps in flight — and so how many sealed frames it holds (≈256 KiB each). */
export const PUMP_IN_FLIGHT = 4

/**
 * How close to the end of the signed range the pump asks for the next window: when it starts a chunk this few
 * places before the last signed one. Enough that the next window is usually back before the pump reaches it.
 */
const WINDOW_LOOKAHEAD = 2 * PUMP_IN_FLIGHT

/**
 * The one upload pump (ADR 0013 §2 + Consequences): read, seal and `PUT` a planned blob one frame at a time, with
 * at most [PUMP_IN_FLIGHT] `PUT`s in flight, signing each next window before the URLs in hand run out. A `PUT` the
 * store answers `403` (its window's TTL ran out — a stall, a slow link) re-signs from that chunk and is retried
 * once, as the GET side does. Every upload — member, contributor, cover — goes through here.
 */
async function pumpBlob(
  plan: BlobPlan,
  upload: ReservedUpload,
  signWindow: WindowFn,
  onChunkDone: () => void,
  signal?: AbortSignal,
): Promise<void> {
  const urls = new Map<number, string>()
  let signedUpTo = 0 // exclusive: every chunk below it has been signed at least once
  const take = (puts: ChunkPut[]) => {
    for (const p of puts) {
      urls.set(p.index, p.url)
      signedUpTo = Math.max(signedUpTo, p.index + 1)
    }
  }
  take(upload.puts)

  // At most one next-window request at a time; every worker that needs it waits on the same one.
  let pending: Promise<void> | null = null
  const nextWindow = (): Promise<void> => {
    pending ??= signWindow(upload.uploadId, signedUpTo).then(take).finally(() => { pending = null })
    return pending
  }

  let next = 0
  const worker = async () => {
    for (;;) {
      throwIfAborted(signal)
      const i = next++
      if (i >= plan.chunkCount) return
      if (signedUpTo < plan.chunkCount && i >= signedUpTo - WINDOW_LOOKAHEAD) nextWindow().catch(() => {}) // ahead; a failure resurfaces below
      while (!urls.has(i)) {
        const before = signedUpTo
        await nextWindow()
        if (signedUpTo === before) throw new Error('upload_failed') // a window that signs nothing would spin forever
      }
      const frame = await sealFrameAt(plan, i)
      try {
        await putChunk(urls.get(i)!, frame, signal)
      } catch (e) {
        if (chunkPutStatus(e) !== HttpStatus.forbidden) throw e
        take(await signWindow(upload.uploadId, i)) // the window expired → re-sign from this chunk, retry it once
        await putChunk(urls.get(i)!, frame, signal)
      }
      urls.delete(i)
      onChunkDone()
    }
  }
  await Promise.all(Array.from({ length: Math.min(PUMP_IN_FLIGHT, plan.chunkCount) }, worker))
}

/** Reserve [plans] through [transport] and pump each in turn, reporting progress over every chunk of every blob. */
async function reserveAndPump(
  plans: BlobPlan[],
  reserve: (declares: BlobDeclare[]) => Promise<ReservedUpload[]>,
  signWindow: WindowFn,
  onProgress?: (fraction: number) => void,
  signal?: AbortSignal,
): Promise<string[]> {
  throwIfAborted(signal)
  const reserved = await reserve(plans.map((p) => ({
    blobId: p.blobId, ownerId: p.ownerId, role: p.role,
    chunkCount: p.chunkCount, byteSize: p.byteSize, wrappedBlobKey: p.wrappedBlobKey,
  })))
  if (reserved.length !== plans.length) throw new Error('upload_failed')
  const totalChunks = plans.reduce((n, p) => n + p.chunkCount, 0)
  let doneChunks = 0
  onProgress?.(0)
  for (const [i, plan] of plans.entries()) {
    await pumpBlob(plan, reserved[i], signWindow, () => {
      doneChunks += 1
      onProgress?.(doneChunks / totalChunks)
    }, signal)
  }
  return reserved.map((r) => r.uploadId)
}

/**
 * Seal the original + its derivatives (each under its own `K_blob`), upload all ciphertext chunks direct-to-store,
 * and complete + attach — via an injected [MediaUploadTransport]. Shared by the authed [addMedia] and the
 * anonymous contributor upload. `memoryKey` is the key the new media is sealed under: `K_mem`, or a WRITE_ONLY
 * guest's own identity key, since that guest never holds `K_mem` (ADR 0031 point 7). `deriver` is injectable for
 * tests; `opts` carries `onProgress` (0..1) + an `AbortSignal`.
 */
export async function uploadMediaVia(
  scopeId: string,
  memoryKey: Uint8Array,
  source: AddMediaSource,
  deriver: MediaDeriver,
  transport: MediaUploadTransport,
  opts: AddMediaOptions = {},
): Promise<{ blobId: string; mediaId: string }> {
  const { onProgress, signal } = opts
  throwIfAborted(signal)
  // The client mints the media's id (ADR 0072): every blob wrap and both metadata tiers are sealed bound to it.
  const mediaId = crypto.randomUUID()
  // ONE decode of the original: `deriveTiers` produces both tiers from it and hands back the geometry it
  // already measured, so `sealMetadata` has nothing left to probe for an image.
  const { derivatives: tiers, dimensions } = await deriveTiers(source, deriver)
  const { wrappedMeta, wrappedExif } = await sealMetadata(source, deriver, memoryKey, { scopeId, mediaId }, dimensions)
  // index 0 = original, streamed from the source; the rest are the role-tagged derivatives — small, already in
  // memory from the derivation — in order
  const plans = [
    planBlob(scopeId, { ownerId: mediaId, role: 'ORIGINAL' }, memoryKey, source.blob),
    ...tiers.map((t) => planBlob(scopeId, { ownerId: mediaId, role: t.kind }, memoryKey, new Blob([t.bytes as BlobPart]))),
  ]

  // reserve one upload session per blob, then read → seal → PUT each chunk straight to the store (no base64, no
  // JSON, no credentials), window by window, passing the abort signal so a cancel stops the in-flight PUTs.
  const uploadIds = await reserveAndPump(plans, (d) => transport.reserve(d), transport.window, onProgress, signal)

  // complete — one logical media: the original + its role-tagged derivative uploads
  throwIfAborted(signal)
  const done = await transport.complete({
    mediaId,
    originalUploadId: uploadIds[0],
    derivatives: tiers.map((t, i) => ({ kind: t.kind, uploadId: uploadIds[i + 1] })),
    wrappedMeta,
    wrappedExif,
  })
  return { blobId: expectMintedId(plans[0].blobId, done.blobId), mediaId: expectMintedId(mediaId, done.mediaId) }
}

/** Reserve through core's authed blob control plane (`startBlobUploads`) — the member upload and the cover share it. */
async function mediaReserve(scopeId: string, declares: BlobDeclare[]): Promise<ReservedUpload[]> {
  const started = await withRefresh(() =>
    blobApi().startBlobUploads({ scopeId, blobUploadsBatchRequest: { items: declares } }),
  )
  if (started.results.length !== declares.length) throw new Error('upload_failed')
  return started.results.map((r) => {
    if (!r || r.error) throw new Error(r?.error ?? 'upload_failed')
    if (!r.uploadId || !r.presignedPuts) throw new Error('store_unavailable')
    return { uploadId: r.uploadId, puts: r.presignedPuts }
  })
}

/** Sign an authed upload's next window (`presignBlobUploadWindows`). */
function mediaWindow(scopeId: string): WindowFn {
  return async (uploadId, fromChunk) => {
    const signed = await withRefresh(() =>
      blobApi().presignBlobUploadWindows({ scopeId, blobUploadWindowsRequest: { items: [{ uploadId, fromChunk }] } }),
    )
    return windowPuts(signed.results[0])
  }
}

/** One window result → its PUTs, or the per-item error it carries (`upload_not_found`, `range_invalid`, …). */
export function windowPuts(r: { presignedPuts?: PresignedUrl[] | null; error?: string | null } | undefined): ChunkPut[] {
  if (!r || r.error) throw new Error(r?.error ?? 'upload_failed')
  if (!r.presignedPuts) throw new Error('store_unavailable')
  return r.presignedPuts
}

/** The authed upload transport — the internal Memory control plane (`startBlobUploads` + `presignBlobUploadWindows` + `completeMedia`). */
function mediaApiTransport(scopeId: string, nodeId: string): MediaUploadTransport {
  return {
    reserve: (declares) => mediaReserve(scopeId, declares),
    window: mediaWindow(scopeId),
    async complete(media) {
      const done = await withRefresh(() =>
        mediaApi().completeMedia({
          scopeId,
          mediaBatchRequest: {
            items: [{
              mediaId: media.mediaId, nodeId, originalUploadId: media.originalUploadId, derivatives: media.derivatives,
              wrappedMeta: media.wrappedMeta, wrappedExif: media.wrappedExif,
            }],
          },
        }),
      )
      const m = done.results[0]
      if (!m || m.error || !m.blobId || !m.mediaId) throw new Error(m?.error ?? 'attach_failed')
      return { blobId: m.blobId, mediaId: m.mediaId }
    },
  }
}

/**
 * Seal the original + its derivatives, upload direct-to-store, and attach to `nodeId` (a MEMORY/CHAPTER) as one
 * role-tagged media. Returns the new ids. `deriver` is injectable for tests; `opts` carries `onProgress` + an
 * `AbortSignal` for the upload queue.
 */
export async function addMedia(
  scopeId: string,
  nodeId: string,
  memoryKey: Uint8Array,
  source: AddMediaSource,
  deriver: MediaDeriver = createBrowserDeriver(),
  opts: AddMediaOptions = {},
): Promise<{ blobId: string; mediaId: string }> {
  return uploadMediaVia(scopeId, memoryKey, source, deriver, mediaApiTransport(scopeId, nodeId), opts)
}

// --- standalone cover blob (cr-0073) ---------------------------------------

/**
 * Cover downscale spec: a Memory's cover is only ever shown small-to-medium (a card tile, a ~180 px detail
 * preview, a 34 px pin), so an uploaded cover is downscaled to ONE cover-sized JPEG — no THUMBNAIL/PREVIEW
 * tiers. Matches the PREVIEW long edge; the normalized crop then renders it identically at any size.
 */
export const COVER_SPEC = { maxEdge: 2048, quality: 0.85 }

/**
 * Upload a brand-new image as a Memory's **standalone cover blob** (cr-0073, uc.memory.cover second source):
 * downscale on device to one cover-sized JPEG, seal it under a fresh `K_blob` (wrapped under `K_mem`), upload
 * the ciphertext chunks direct-to-store, and commit it to the node via `commitCoverBlob` — attached with **no
 * `veyq_media` row** (never in the gallery, no derivative tiers). Commit **atomically reclaims** any prior
 * cover blob on the node. Returns the committed [BlobRef] the display layer opens with [openBlob]. Best-effort
 * downscale: an undecodable source seals the original bytes rather than fail (uc.memory.upload E4).
 */
export async function uploadCoverBlob(
  scopeId: string,
  nodeId: string,
  memoryKey: Uint8Array,
  source: AddMediaSource,
  deriver: MediaDeriver = createBrowserDeriver(),
): Promise<BlobRef> {
  let blob = source.blob
  if (source.mediaType.startsWith('image/')) {
    try {
      blob = new Blob([(await deriver.deriveImage(source.blob, COVER_SPEC)).bytes as BlobPart])
    } catch {
      /* undecodable codec → seal the pristine bytes (still a valid, if larger, cover) */
    }
  }
  // A cover's owner is its node, in the COVER role (ADR 0072) — the server commits only an upload started so.
  const cover = { ownerId: nodeId, role: 'COVER' } as const
  const plan = planBlob(scopeId, cover, memoryKey, blob)
  const [uploadId] = await reserveAndPump([plan], (d) => mediaReserve(scopeId, d), mediaWindow(scopeId))

  const committed = await withRefresh(() =>
    mediaApi().commitCoverBlob({ scopeId, nodeId, coverBlobCommitRequest: { uploadId } }),
  )
  return {
    blobId: expectMintedId(plan.blobId, committed.blobId),
    chunkCount: committed.chunkCount, byteSize: committed.byteSize, wrappedBlobKey: committed.wrappedBlobKey, ...cover,
  }
}

/**
 * Detach + reclaim a node's standalone cover blob (cr-0073) — the client-driven counterpart to `deleteNode`'s
 * cascade. Called when an **uploaded** cover is superseded by a media cover or removed (a fresh upload commit
 * already reclaims the prior blob atomically, so a replace-upload-with-upload needs no explicit delete).
 *
 * `blobId` is the upload the caller's write replaced: the node has ONE cover-blob slot, and a peer's upload may
 * have landed in it since. The server clears the slot only while it still holds `blobId`, and answers
 * `409 cover_replaced` otherwise — that peer's commit already reclaimed our blob, so there is nothing to delete.
 */
export async function deleteCoverBlob(scopeId: string, nodeId: string, blobId: string): Promise<void> {
  try {
    await withRefresh(() => mediaApi().deleteCoverBlob({ scopeId, nodeId, blobId }))
  } catch (e) {
    if (!(await isCodedError(e, HttpStatus.conflict, 'cover_replaced'))) throw e
  }
}

// --- delete / restore / trash (uc.memory.delete-media, cr-0110) -------------

/**
 * Trash a media — soft-delete it into the Memory's recoverable per-Memory trash. Any scope member may do it; the
 * server flips `deleted_at`/`deleted_by` (the audit) and bumps `delete_revision` so every open gallery drops the
 * tile on its next poll (cr-0107). The row + its blobs survive until the retention purge, so [restoreMedia] brings
 * it back. Operator-blind — a flag flip, no content, no key.
 */
export async function deleteMedia(scopeId: string, mediaId: string): Promise<void> {
  await withRefresh(() => mediaApi().deleteMedia({ scopeId, mediaId }))
}

/** Restore a trashed media back into the gallery (cr-0110) — clears the trash flags; the tile re-surfaces via the live poll. */
export async function restoreMedia(scopeId: string, mediaId: string): Promise<void> {
  await withRefresh(() => mediaApi().restoreMedia({ scopeId, mediaId }))
}

/**
 * List a node's TRASHED media for the Trash tab (cr-0110): the soft-deleted items with their deletion audit
 * (`deletedAt`/`deletedBy`), newest-trashed first — a plain full list (not the live conditional-GET). Each item
 * still carries its original + derivative tiers, so the tab renders thumbnails the same way the gallery does.
 */
export async function listTrash(scopeId: string, nodeId: string): Promise<MediaItem[]> {
  const page = await withRefresh(() => mediaApi().listNodeMedia({ scopeId, nodeId, state: 'trashed' }))
  return pageItems(nodeId, page)
}

/**
 * Permanently delete a **trashed** media now (cr-0111) — the member-expedited purge (hard-delete + blob reclaim).
 * **Irreversible.** Only a media already in the trash can be purged (a live one is a 404). Operator-blind.
 */
export async function purgeMedia(scopeId: string, mediaId: string): Promise<void> {
  await withRefresh(() => mediaApi().purgeMedia({ scopeId, mediaId }))
}

// --- read-back -------------------------------------------------------------

/**
 * One conditional-GET page of a node's media (uc.memory.live-updates, cr-0107, ADR 0033). A poll echoes the
 * cached `mediaRevision` as `ifNoneMatch`; unchanged ⇒ `{ notModified: true }` (the server ran no media query).
 * On a change ⇒ a **keyset delta** — only the media past the `since` cursor (`"updatedAt,mediaId"`) — plus both
 * revisions and, **only when a delete advanced `deleteRevision`** past the echoed `sinceDelete`, the node's
 * current `mediaIds` set for delete-reconcile (else null). First load: omit `ifNoneMatch`/`since` for the full
 * list. Operator-blind: opaque counters + ids + wraps only.
 */
export type MediaPage =
  | { notModified: true }
  | { notModified: false; items: MediaItem[]; mediaRevision: number; deleteRevision: number; mediaIds: string[] | null }

export async function listMediaPage(
  scopeId: string,
  nodeId: string,
  opts: { ifNoneMatch?: string; since?: string; sinceDelete?: number } = {},
): Promise<MediaPage> {
  try {
    const page = await withRefresh(() =>
      mediaApi().listNodeMedia({ scopeId, nodeId, ifNoneMatch: opts.ifNoneMatch, since: opts.since, sinceDelete: opts.sinceDelete }),
    )
    return {
      notModified: false,
      items: pageItems(nodeId, page),
      mediaRevision: page.mediaRevision,
      deleteRevision: page.deleteRevision,
      mediaIds: page.mediaIds ?? null,
    }
  } catch (e) {
    if (isHttpStatus(e, HttpStatus.notModified)) return { notModified: true } // idle fast path — nothing changed
    throw e
  }
}

/**
 * List a node's media in full (opaque metadata + the K_blob wraps for the original + every derivative tier). A
 * thin wrapper over [listMediaPage] with no cursor/ETag, so the server always returns the whole list — used
 * where a snapshot suffices (cover picker, tests); the live gallery uses [listMediaPage] directly to poll deltas.
 */
export async function listMedia(scopeId: string, nodeId: string): Promise<MediaItem[]> {
  const page = await listMediaPage(scopeId, nodeId)
  return page.notModified ? [] : page.items // no ifNoneMatch was sent, so a 304 is impossible — the guard is just exhaustiveness
}

/** The newest `(updatedAt, mediaId)` keyset cursor across [items] — what a live poll echoes as `since`; undefined
 *  when none carry an `updatedAt` (e.g. an empty grid). Millisecond-precision by construction (a JS `Date`), which
 *  round-trips losslessly against the server's `TIMESTAMP(3)` so the same rows are never re-fetched. */
export function newestCursor(items: readonly MediaItem[]): string | undefined {
  let bestT = -Infinity
  let bestId = ''
  let found = false
  for (const it of items) {
    if (!it.updatedAt) continue
    const t = it.updatedAt.getTime()
    if (t > bestT || (t === bestT && it.mediaId > bestId)) {
      bestT = t
      bestId = it.mediaId
      found = true
    }
  }
  return found ? `${new Date(bestT).toISOString()},${bestId}` : undefined
}

/**
 * Lazily fetch + unseal a media's heavy **EXIF** bundle via the exif endpoint (cr-0064/0068) — the lightbox calls
 * this only when an item opens, so `listMedia` stays constant-small. Operator-blind: the server returns opaque
 * ciphertext, unsealed here under the media's key ([mediaKey]). Best-effort: null when none was sealed, the wrap
 * doesn't open, or the payload isn't a sane object (the lightbox then shows no detail rather than break).
 */
export async function loadMediaExif(scopeId: string, media: MediaRef, memoryKey: Uint8Array): Promise<MediaExif | null> {
  const { mediaId } = media
  const exif = await withRefresh(() => mediaApi().getMediaExif({ scopeId, mediaId }))
  if (!exif.wrappedExif) return null
  try {
    const key = mediaKey(scopeId, memoryKey, media)
    const json = new TextDecoder().decode(v1.open(mediaExifContext(scopeId, mediaId), key, fromB64std(exif.wrappedExif)))
    const parsed = JSON.parse(json) as unknown
    return parsed && typeof parsed === 'object' ? (parsed as MediaExif) : null
  } catch {
    return null
  }
}

/** A media a meta write targets: its id + origin (which key seals it) and the tier + version it was listed at. */
export type MediaMetaTarget = MediaRef & Pick<MediaItem, 'wrappedMeta' | 'metaVersion'>

/** The outcome of a media meta write: the display tier, the state the server confirmed or handed back, and
 *  whether this call wrote (a backfill the fresh tier no longer needs does not). */
export type MediaMetaWrite = { tier: MediaMeta | null; wrappedMeta: string | null; metaVersion: number; wrote: boolean }

/**
 * Set (or clear, with `null`) a media's **capturedAt override** (cr-0065/0066/0068) — a corrected capture time.
 * Operator-blind: the whole listed-metadata tier is re-sealed under `K_mem` with the override added/removed and
 * PUT verbatim (geometry, GPS and the place name ride along unchanged — only the date is user-editable). The base
 * is the tier the media was listed with, at its `metaVersion` (ADR 0077); a peer's correction written meanwhile
 * throws `MetaConflictError` so the lightbox re-opens on it, while a peer's backfill merges silently. Clearing the
 * last field drops the tier to null. Returns the new tier so the caller can update local state (and the grid can
 * re-sort).
 */
export async function setMediaMeta(
  scopeId: string,
  media: MediaMetaTarget,
  memoryKey: Uint8Array,
  overrideMs: number | null,
): Promise<MediaMetaWrite> {
  const openedWith = unsealMeta(scopeId, memoryKey, media as MediaItem)?.capturedAtOverride
  return persistMeta(scopeId, media, memoryKey, (raw, { retry, state }) => {
    if (retry && finiteNum(raw.capturedAtOverride) !== openedWith) throw new MetaConflictError(state)
    if (overrideMs === null) delete raw.capturedAtOverride
    else raw.capturedAtOverride = overrideMs
    return raw
  })
}

/**
 * The clamp on a resolved place name, in **UTF-8 bytes** rather than characters, because the budget being
 * protected is the listed tier's 512-base64 cap and a CJK name costs three bytes per character. Long enough for
 * "Bezirk Friedrichshain-Kreuzberg", short enough that a maximal name on a tier already carrying every other
 * field still seals inside the cap — which is asserted, not reasoned about.
 */
export const PLACE_NAME_MAX_BYTES = 96

/** Clamp to [PLACE_NAME_MAX_BYTES] without splitting a multi-byte character in half. */
export function clampPlaceName(name: string): string {
  return clampUtf8(name, PLACE_NAME_MAX_BYTES)
}

/**
 * The longest prefix of `s` that fits `maxBytes` of UTF-8, never splitting a character. Cut on the bytes, then
 * step back over continuation bytes (`10xxxxxx`) to the character's lead byte — one encode, not one per
 * character, which matters for a 20 000-character EXIF description.
 */
function clampUtf8(s: string, maxBytes: number): string {
  const bytes = new TextEncoder().encode(s)
  if (bytes.length <= maxBytes) return s
  let end = maxBytes
  while (end > 0 && (bytes[end] & 0xc0) === 0x80) end--
  return new TextDecoder().decode(bytes.subarray(0, end))
}

/**
 * Seal a media's resolved **place name** into the listed tier (read-modify-write from the RAW tier, exactly as the
 * capture-time override and the mediaType back-fill do — every other field rides along verbatim). A backfill: if
 * the tier it re-bases on after a `409` already carries a name, nothing is written. Operator-blind: the name is
 * resolved client-direct from a bare coordinate and the server only ever sees the re-sealed ciphertext.
 */
export async function backfillPlaceName(
  scopeId: string,
  media: MediaMetaTarget,
  memoryKey: Uint8Array,
  placeName: string,
  placeLevel?: string,
): Promise<MediaMetaWrite> {
  return persistMeta(scopeId, media, memoryKey, (raw) => {
    if (cleanStr(raw.placeName) !== undefined) return null // the fresh tier already has one — nothing to fill
    raw.placeName = clampPlaceName(placeName)
    if (placeLevel === undefined) delete raw.placeLevel
    else raw.placeLevel = placeLevel
    return raw
  })
}

/**
 * Lazily back-fill a pre-field media's sealed **`mediaType`** (cr-inline-video-playback, #104/#105). Called on the
 * first original-fetch (download or play) of an item that carries no sealed type: re-seal the listed tier with the
 * sniffed MIME merged in — every other field untouched — and PUT it, so the **next** listing carries the video
 * signal (the tile play badge + the lightbox `<video>` branch). Nothing is written when the tier it re-bases on
 * already has a type. Operator-blind: the MIME is sniffed on device from plaintext the client already holds
 * ([sniffMediaType]); the server only ever sees the re-sealed ciphertext. The accepted boundary (ADR 0047 / #105):
 * a pre-field video that is never fetched stays without a signal — there is **no** eager original-fetch just to
 * back-fill.
 */
export async function backfillMediaType(
  scopeId: string,
  media: MediaMetaTarget,
  memoryKey: Uint8Array,
  mediaType: string,
): Promise<MediaMetaWrite> {
  return persistMeta(scopeId, media, memoryKey, (raw) => {
    if (cleanStr(raw.mediaType) !== undefined) return null
    raw.mediaType = mediaType
    return raw
  })
}

/** Whether a metadata tier carries anything worth sealing (else it drops to null on a re-seal). Any field but `v`
 *  counts — a known one (a tier whose only content is a resolved name is still content: sealing `null` would have
 *  the name resolved again on every open) or one a newer client wrote that this one does not know. */
function metaHasContent(m: RawMediaMeta): boolean {
  return Object.entries(m).some(([k, v]) => k !== 'v' && v !== undefined)
}

/**
 * Re-seal a metadata tier under the media's key and PUT it with the `metaVersion` it was built from
 * (`updateMediaMeta`, ADR 0077) — the one write path behind the capturedAt edit ([setMediaMeta]) and the two
 * backfills. `edit` gets the RAW tier (every field verbatim, never [unsealMeta]'s projection) and returns the next
 * one, or null for no write; on a `409 stale_version` it re-runs on the tier the conflict handed back. A present
 * tier that does not open is refused (`MetaUnreadableError`), never rebuilt from empty (#273). The key is the one
 * the media's origin fixes ([mediaKey]): a member editing what a WRITE_ONLY guest added re-seals under that guest's
 * key, never under `K_mem`, so the media never changes path (ADR 0031 point 7). Drops to null when nothing is
 * left worth sealing.
 */
async function persistMeta(
  scopeId: string,
  media: MediaMetaTarget,
  memoryKey: Uint8Array,
  edit: (raw: RawMediaMeta, ctx: MetaEditContext) => RawMediaMeta | null,
): Promise<MediaMetaWrite> {
  const { mediaId } = media
  const { state, meta, wrote } = await writeSealedMeta<RawMediaMeta>(
    { wrappedMeta: media.wrappedMeta ?? null, metaVersion: media.metaVersion ?? 0 },
    {
      open: (wrappedMeta) => openMediaMetaForWrite(scopeId, memoryKey, media, wrappedMeta),
      seal: (raw) =>
        metaHasContent(raw) ? sealJson(mediaKey(scopeId, memoryKey, media), prune(raw), mediaMetaContext(scopeId, mediaId)) : null,
      send: async (wrappedMeta, metaVersion) => {
        await withRefresh(() => mediaApi().updateMediaMeta({ scopeId, mediaId, mediaMetaUpdate: { wrappedMeta, metaVersion } }))
      },
    },
    edit,
  )
  return { tier: state.wrappedMeta ? normalizeMediaMeta(meta) : null, wrappedMeta: state.wrappedMeta, metaVersion: state.metaVersion, wrote }
}

function tierRef(item: MediaItem, kind: MediaDerivativeKind): BlobRef | undefined {
  return item.derivatives.find((d) => d.kind === kind)?.ref
}

/**
 * Whether a media carries derivative tiers — i.e. it had a derivable frame (an image or video, cr-0045). A
 * gallery renders these as a thumbnail/preview; one **without** derivatives is a non-image/video (E1) shown as
 * an icon tile. (No media type is stored — operator-blind — so presence of a tier is the only signal.)
 */
export function hasDerivatives(item: MediaItem): boolean {
  return item.derivatives.length > 0
}

/** Whether a sealed `mediaType` names a video (#104/#105) — defined beside the playback shim, which applies the
 *  same predicate. Pure; the caller unseals the tier ([unsealMeta]) and passes `meta.mediaType`. */
export { isVideoMediaType } from '../../sw/protocol'

/** Grid tier (small): the thumbnail derivative, or the original when a media has no derivatives. */
export function gridRef(item: MediaItem): BlobRef {
  return tierRef(item, 'THUMBNAIL') ?? item.original
}

/** Lightbox tier (medium): the preview derivative, or the original when a media has no derivatives. */
export function lightboxRef(item: MediaItem): BlobRef {
  return tierRef(item, 'PREVIEW') ?? item.original
}

/** Download tier: always the pristine original. */
export function downloadRef(item: MediaItem): BlobRef {
  return item.original
}

/**
 * Open a blob from a set of **already-presigned** per-chunk GET URLs: unwrap its `K_blob` under `K_mem`, fetch
 * the ciphertext frames direct-from-store, and `openFrame` them back to plaintext (the AAD position check still
 * applies). Shared by the authed [openBlob] (presign via the media endpoint) and the anonymous share viewer
 * (presign via the gated share endpoint, cr-0100) — the decrypt is identical; only who presigns differs.
 */
export async function openBlobFromUrls(scopeId: string, memoryKey: Uint8Array, ref: BlobRef, getUrls: string[]): Promise<Uint8Array> {
  const blobKey = v1.open(blobKeyContext(scopeId, ref), memoryKey, fromB64std(ref.wrappedBlobKey))
  const frames = await Promise.all(getUrls.map((url) => getChunk(url)))
  const chunks = frames.map((frame, i) => v1.openFrame(blobKey, i, frames.length, frame))
  return v1.concat(...chunks)
}

/**
 * Presign GETs for **every** chunk of a blob — the whole-blob presign the authed read paths share (the batch
 * endpoint is whole-blob only; there is no per-chunk presign). Returned as URLs ordered by chunk index.
 */
async function presignAllChunks(scopeId: string, blobId: string): Promise<string[]> {
  const presigned = await withRefresh(() =>
    blobApi().presignBlobDownloads({ scopeId, blobDownloadsBatchRequest: { items: [{ blobId }] } }),
  )
  const d = presigned.results[0]
  if (!d || d.error || !d.presignedGets) throw new Error(d?.error ?? 'store_unavailable')
  return byIndex(d.presignedGets)
}

/**
 * Open a blob: unwrap its `K_blob` under `K_mem`, presign the chunk GETs, fetch the ciphertext frames
 * direct-from-store, and `openFrame` them back to plaintext (the AAD position check still applies).
 */
export async function openBlob(scopeId: string, memoryKey: Uint8Array, ref: BlobRef): Promise<Uint8Array> {
  return openBlobFromUrls(scopeId, memoryKey, ref, await presignAllChunks(scopeId, ref.blobId))
}

/** Open the grid tier (thumbnail) of a media — under the key its origin fixes ([mediaKey]). */
export const openGrid = (scopeId: string, memoryKey: Uint8Array, item: MediaItem) =>
  openBlob(scopeId, mediaKey(scopeId, memoryKey, item), gridRef(item))
/** Open the lightbox tier (preview) of a media. */
export const openLightbox = (scopeId: string, memoryKey: Uint8Array, item: MediaItem) =>
  openBlob(scopeId, mediaKey(scopeId, memoryKey, item), lightboxRef(item))
/** Open the pristine original of a media (download). */
export const openOriginal = (scopeId: string, memoryKey: Uint8Array, item: MediaItem) =>
  openBlob(scopeId, mediaKey(scopeId, memoryKey, item), downloadRef(item))

// --- Slice-2 range streaming: the page-side chunk provider (ADR 0047) --------
//
// The Service-Worker range layer (cr-inline-video-playback Slice 2) holds NO key and NO auth: it asks the page
// for the decrypted *plaintext* of individual chunks over a MessagePort and assembles the byte ranges the
// <video> demuxer requests. This is that page-side seam — a per-blob source of plaintext chunks over the same
// presign→openFrame plumbing the whole-blob read paths use. K_blob and the auth context never leave the page.

/** Re-presign a little before the backend's 600 s TTL (`application.properties`) so a long playback never races expiry. */
const PRESIGN_REFRESH_MS = 9 * 60 * 1000

/** Parse the HTTP status out of a {@link getChunk} failure (`chunk_get_failed:<status>`), or undefined if not one. */
function chunkGetStatus(e: unknown): number | undefined {
  const m = e instanceof Error ? /^chunk_get_failed:(\d+)$/.exec(e.message) : null
  return m ? Number(m[1]) : undefined
}

/**
 * Build a {@link ChunkProvider} over a `presignAll` that yields the whole blob's per-chunk GET URLs (ordered by
 * index). The two auth contexts — the authed app ({@link chunkProvider}) and the anonymous share
 * (`shareChunkProvider`, share.ts) — differ ONLY in that function; the unwrap + `openFrame` core here is
 * identical, so the SW never learns which route served the bytes. `K_blob` is unwrapped once; the presigned URLs
 * are cached with their issue time and re-presigned on TTL/expiry or a store `403`.
 */
export function makeChunkProvider(
  scopeId: string,
  memoryKey: Uint8Array,
  ref: BlobRef,
  presignAll: () => Promise<string[]>,
): ChunkProvider {
  const blobKey = v1.open(blobKeyContext(scopeId, ref), memoryKey, fromB64std(ref.wrappedBlobKey))
  let urls: string[] | undefined
  let issuedAt = 0
  async function freshUrls(force: boolean): Promise<string[]> {
    if (force || !urls || Date.now() - issuedAt > PRESIGN_REFRESH_MS) {
      urls = await presignAll()
      issuedAt = Date.now()
    }
    return urls
  }
  return {
    chunkCount: ref.chunkCount,
    byteSize: ref.byteSize,
    async getPlaintextChunk(index: number): Promise<Uint8Array> {
      if (!Number.isInteger(index) || index < 0 || index >= ref.chunkCount) {
        throw new Error(`chunk_out_of_range:${index}/${ref.chunkCount}`)
      }
      let all = await freshUrls(false)
      let frame: Uint8Array
      try {
        frame = await getChunk(all[index])
      } catch (e) {
        if (chunkGetStatus(e) !== HttpStatus.forbidden) throw e
        all = await freshUrls(true) // presign expired → re-presign the WHOLE blob, retry the one chunk once
        frame = await getChunk(all[index])
      }
      // AAD trap (load-bearing): `total` is the WHOLE blob's chunkCount, never the streamed range subset —
      // openFrame binds `index‖total` (v1.ts), so a subset `total` fails every open. See the CR Slice-2 §5b.
      return v1.openFrame(blobKey, index, ref.chunkCount, frame)
    },
  }
}

/** A {@link ChunkProvider} for the authed app over a media's original: presigns the whole blob via the media
 *  endpoint (bearer, `withRefresh`), and opens it under the key the media's origin fixes ([mediaKey]). */
export function chunkProvider(scopeId: string, memoryKey: Uint8Array, item: MediaItem): ChunkProvider {
  const ref = downloadRef(item)
  return makeChunkProvider(scopeId, mediaKey(scopeId, memoryKey, item), ref, () => presignAllChunks(scopeId, ref.blobId))
}

// --- transport helpers -----------------------------------------------------

/** Order presigned URLs by their declared chunk index (the server returns them in order, but don't assume). */
function byIndex(urls: PresignedUrl[]): string[] {
  return [...urls].sort((a, b) => a.index - b.index).map((u) => u.url)
}

/** The one `Content-Type` a presigned PUT signs, so the store refuses any other (ADR 0013 §6). The uploader no
 *  longer chooses the stored type: whatever the store later serves is never renderable. */
const PUT_CONTENT_TYPE = 'application/octet-stream'

async function putChunk(url: string, frame: Uint8Array, signal?: AbortSignal): Promise<void> {
  const res = await fetch(url, { method: 'PUT', headers: { 'Content-Type': PUT_CONTENT_TYPE }, body: frame as BodyInit, signal })
  if (!res.ok) throw new Error(`chunk_put_failed:${res.status}`)
}

/** Parse the HTTP status out of a {@link putChunk} failure (`chunk_put_failed:<status>`), or undefined if not one. */
function chunkPutStatus(e: unknown): number | undefined {
  const m = e instanceof Error ? /^chunk_put_failed:(\d+)$/.exec(e.message) : null
  return m ? Number(m[1]) : undefined
}

async function getChunk(url: string): Promise<Uint8Array> {
  const res = await fetch(url, { method: 'GET' })
  if (!res.ok) throw new Error(`chunk_get_failed:${res.status}`)
  return new Uint8Array(await res.arrayBuffer())
}
