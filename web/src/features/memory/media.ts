// en.memory.media + en.memory.derivatives (web) — the encrypted media-blob data layer (cr-0028, cr-0045).
// Operator-blind, direct-to-store: a blob is sealed client-side as chunked AEAD under a fresh random K_blob
// (wrapped under the Memory's K_node, ADR 0012), the ciphertext chunks travel straight to the object store via
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
import { isHttpStatus, HttpStatus } from '../../shared/httpError'
import { mediaApi } from './api'
import { withRefresh } from '../../app/sessionRefresh'
import { type MediaListPage, type MediaSummary, type MediaDerivativeKind } from '../../api/generated/media'
import { expectMintedId } from '../../core/node/mintedId'
import {
  authedReserve,
  authedWindow,
  makeChunkProvider,
  openBlob,
  planBlob,
  presignAllChunks,
  readBlobMeta,
  reserveAndPump,
  throwIfAborted,
  unwrapBlobKey,
} from '../../core/blob/transport'
import type { BlobDeclare, BlobRef, BlobSource, ReservedUpload, WindowFn } from '../../core/blob/types'
import type { BlobMeta, OpenedBlobMeta } from '../../core/blob/meta'
import { sniffMediaType } from './download'
import { newestItemsCursor } from '../../core/items/itemsLive'
import { mediaKey, uploaderOriginOf, type UploaderOrigin } from './mediaKey'
import { MetaConflictError, MetaUnreadableError, writeSealedMeta, type MetaEditContext } from '../../core/node/sealedMetaWrite'
import type { ChunkProvider } from '../../sw/protocol'
import { createBrowserDeriver, deriveTiers, type MediaDeriver, type AddMediaSource, type Dimensions } from '../../shared/image/mediaDeriver'

// The on-device pixel stage lives in `shared/image/mediaDeriver` (cr-0143). Re-exported here so existing importers of
// `./media` (share.ts, useOptimisticTiles.ts, uploadQueue.ts, tests) keep their import path unchanged.
export { scaleToMaxEdge, TIER_SPECS, createBrowserDeriver, type MediaDeriver, type AddMediaSource, type Dimensions } from '../../shared/image/mediaDeriver'

// The per-uploader "Beiträge" buckets (en.memory.buckets, ADR 0034) live in `./mediaBuckets` (#96) — uploader
// metadata, not blob data. Re-exported here so existing importers of `./media` (MemoryBuckets.vue, tests) keep
// their import path unchanged.
export { openBucketName, listBuckets, createBucket, renameBucket, deleteBucket, moveBucketMedia, type BucketItem } from './mediaBuckets'

// The neutral blob transport — plan, pump, the authed reserve/window, the read side and the chunk provider — is core's
// (`core/blob/`, cr-files-application B1). Re-exported here so existing importers of `./media` (share.ts, the cover
// store, tests) keep their import path unchanged.
export { CHUNK_SIZE, PUMP_IN_FLIGHT, windowPuts, openBlobFromUrls, openBlob, makeChunkProvider } from '../../core/blob/transport'
export type { BlobRef, BlobDeclare, ChunkPut, ReservedUpload, WindowFn } from '../../core/blob/types'

// Every seal in this layer binds the object it is stored as (ADR 0072). A `K_blob` wrap is bound to (scope, owner,
// blob, role) by core's blob transport — the owner is the media for its tiers and the node for a cover — so a server
// that attaches a blob under another media, or serves a thumbnail in another's slot, gets a wrap that does not open
// there.
//
// A media's seals are under `K_node`, except for one a WRITE_ONLY guest added: that one's are under the guest's own
// key (ADR 0031 point 7). A function handed the MEDIA resolves the key itself ([mediaKey]); one handed only an id
// or a blob ref is handed the key, which its caller asks [mediaKey] for.

/**
 * The sealed **listed-metadata** tier of one media (cr-0068), bound to (scope, media). This one blob carries the
 * geometry (`w,h`), the capture time (`capturedAt*`), and the GPS (`gpsLat,gpsLng`) — everything the grid, the
 * date sort, and the place medoid need at list-level. Consolidates the former `media-layout` + `media-capturedat`.
 */
const mediaMetaContext = (scopeId: string, mediaId: string) => ({ purpose: 'media-meta', scopeId, mediaId }) as const
/** The sealed heavy EXIF tier of one media (cr-0064) — its own purpose, bound to (scope, media). */
const mediaExifContext = (scopeId: string, mediaId: string) => ({ purpose: 'media-exif', scopeId, mediaId }) as const

// --- read-back model -------------------------------------------------------

/** Memories' blob roles ([MemoryBlobRole], `./blobRole`) — the contract's and the crypto suite's role is an open token
 *  (a string) since cr-files-application P3-S2: the blob transport below takes any role, the read-back refs this layer
 *  builds carry one of Memories' four. */
export type { MemoryBlobRole } from './blobRole'

/** One role-tagged derivative tier of a media. */
export type MediaDerivative = { kind: MediaDerivativeKind; ref: BlobRef }

/**
 * The **listed-metadata tier** sealed under `K_node` (cr-0068) — one always-listed blob (returned with every
 * `listMedia`) carrying everything the gallery + place need at list-level, kept small:
 * - **geometry** `w,h` — the justified grid's aspect (cr-0059);
 * - **capture time** `capturedAt` (EXIF date or the file's creation date) + any user `capturedAtOverride` — date
 *   sort/display/edit (cr-0066). Effective = `capturedAtOverride ?? capturedAt`. `capturedAtSource` records
 *   where the derived `capturedAt` came from: `'exif'` (a real `DateTimeOriginal`, trustworthy) or `'file'` (the
 *   file's modification date, a fallback that must NOT feed a Memory's `auto` date range — an edited photo's
 *   file date would wrongly stretch it);
 * - **GPS** `gpsLat,gpsLng` (decimal degrees) — the source of a Memory's place medoid (cr-0068, uc.memory.place).
 *
 * Not here: the original's **type** and **file name**. They are what the upload declared about the original's bytes,
 * so they are its blob's sealed meta (core's `blob-meta` seal under the original's `K_blob`, inbox #397), served on the
 * same listing beside the `K_blob` wrap — [originalBlobMeta]. The tier carried a `mediaType` until then (with a lazy
 * back-fill on the first download), the EXIF tier the name (#395 F10); both copies are gone.
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
 * The heavy **EXIF bundle** sealed under `K_node`, lazy-loaded per item for the lightbox (cr-0064). A client-only,
 * versioned shape (never a contract field), so it can grow without the operator ever learning a field. Every
 * field is optional (best-effort extraction). The listed essentials — capture time (cr-0066) and GPS (cr-0068) —
 * live in the [MediaMeta] tier, not here; the original's file name and type in its blob's meta ([originalBlobMeta]).
 */
export type MediaExif = {
  v: number
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
  /** The original's listed-metadata tier sealed under `K_node` (opaque base64); unwrap with [unsealMeta]. Absent when none. */
  wrappedMeta?: string
  /** The revision [wrappedMeta] is at — what a meta write states it was built from (ADR 0077). Absent in the
   *  public-share viewer, which never writes; a write that lacks it states 0 and re-bases on the `409`. */
  metaRevision?: number
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
   *  opens from here (ADR 0031 point 7). Undefined for every other media, whose seals are under `K_node`. */
  uploader?: UploaderOrigin
}

/** What a per-media write or lazy read needs: the media's id and, for one a WRITE_ONLY guest added, its origin. */
export type MediaRef = Pick<MediaItem, 'mediaId' | 'uploader'>

/** A media list row as the UI holds it — exported so a test can feed the read path a row the server shaped. */
export function summaryToItem(s: MediaSummary, uploader?: UploaderOrigin): MediaItem {
  // Each ref is owned by THIS row's media, in the role the row lists it in (ADR 0072); each carries its blob's sealed
  // meta as served (inbox #397), opened only under that blob's own key ([originalBlobMeta]).
  return {
    mediaId: s.mediaId,
    original: {
      blobId: s.blobId, chunkCount: s.chunkCount, byteSize: s.byteSize, wrappedBlobKey: s.wrappedBlobKey,
      wrappedBlobMeta: s.wrappedBlobMeta ?? undefined, ownerId: s.mediaId, role: 'ORIGINAL',
    },
    derivatives: (s.derivatives ?? []).map((d) => ({
      kind: d.kind,
      ref: {
        blobId: d.blobId, chunkCount: d.chunkCount, byteSize: d.byteSize, wrappedBlobKey: d.wrappedBlobKey,
        wrappedBlobMeta: d.wrappedBlobMeta ?? undefined, ownerId: s.mediaId, role: d.kind,
      },
    })),
    wrappedMeta: s.wrappedMeta ?? undefined,
    metaRevision: s.metaRevision,
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
 * Unwrap a media's sealed listed-metadata tier under `K_node` (cr-0068; operator-blind — the server only ever
 * holds the ciphertext). Best-effort: null when none was sealed or the wrap doesn't open. Geometry `w,h` is
 * validated (positive, finite) so a bad payload falls back to a default aspect rather than break the grid; the
 * other fields are coerced to finite numbers. Use [effectiveCapturedAt] for the sort/display date.
 */
export function unsealMeta(scopeId: string, memoryKey: Uint8Array, item: MediaItem): MediaMeta | null {
  if (!item.wrappedMeta) return null
  try {
    const key = mediaKey(scopeId, memoryKey, item)
    const json = new TextDecoder().decode(v1.openField(mediaMetaContext(scopeId, item.mediaId), key, fromB64std(item.wrappedMeta)))
    const parsed = JSON.parse(json) as unknown
    if (parsed && typeof parsed === 'object') return normalizeMediaMeta(parsed as RawMediaMeta)
  } catch {
    /* not openable / malformed → treated as no metadata */
  }
  return null
}

/**
 * A media's listed tier exactly as it was sealed — every field, known or not ([RawMediaMeta]) — for a copy, which seals it
 * anew bound to the copied media ([addCopiedMedia]). Null when none was sealed or it does not open (the gallery then shows
 * the media without metadata, and so does the copy).
 */
export function rawMediaMeta(scopeId: string, memoryKey: Uint8Array, item: MediaItem): Record<string, unknown> | null {
  if (!item.wrappedMeta) return null
  try {
    return openMediaMetaForWrite(scopeId, memoryKey, item, item.wrappedMeta)
  } catch {
    return null
  }
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
    parsed = JSON.parse(new TextDecoder().decode(v1.openField(mediaMetaContext(scopeId, media.mediaId), key, fromB64std(wrappedMeta))))
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

/** Seal a small JSON payload as the field `context` names, under a field key derived from `K_node` → opaque base64. */
function sealJson(memoryKey: Uint8Array, value: unknown, context: v1.FieldContext): string {
  const bytes = new TextEncoder().encode(JSON.stringify(value))
  return b64std(v1.sealField(context, memoryKey, bytes))
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
 * Seal the two metadata tiers under `K_node` (best-effort, operator-blind): the always-listed **metadata** tier
 * (`{v,w,h,capturedAt,capturedAtSource,gpsLat,gpsLng}` — geometry + the EXIF date, else the file's creation date,
 * + GPS + a resolved place name) and the lazy **EXIF** tier ([MediaExif]: the panel bundle). Each tier is null when
 * its source is absent — a video, a screenshot or a PDF seals no EXIF tier; its name and type ride its blob's meta.
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
  }
  const hasMeta =
    metaTier.w !== undefined || metaTier.capturedAt !== undefined || metaTier.gpsLat !== undefined ||
    metaTier.gpsLng !== undefined
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
function sealExifTier(memoryKey: Uint8Array, context: v1.FieldContext, exif: MediaExif): string {
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

/** The type a media's original is declared under when nothing names it. */
const UNKNOWN_TYPE = 'application/octet-stream'
/** How much of an untyped original is read to sniff its type — every signature [sniffMediaType] knows lies in it. */
const SNIFF_BYTES = 1024

/**
 * The original's media type, decided ONCE per upload, before anything is derived or sealed: the source's own (the
 * picked file's `type`, or the one its extension names — `inferMediaType`), else sniffed from its first bytes
 * ([sniffMediaType]: an empty `File.type` is what Safari's Photos import hands over), else
 * `application/octet-stream`. The same type then drives the derivation, the EXIF read and the original's blob meta,
 * so they cannot disagree. Best-effort: a read that fails sniffs nothing.
 */
export async function resolveMediaType(source: AddMediaSource): Promise<string> {
  const given = cleanStr(source.mediaType)
  if (given !== undefined && given !== UNKNOWN_TYPE) return given
  try {
    const head = new Uint8Array(await source.blob.slice(0, SNIFF_BYTES).arrayBuffer())
    return sniffMediaType(head) ?? UNKNOWN_TYPE
  } catch {
    return UNKNOWN_TYPE
  }
}

/**
 * What each blob of a media declares about its bytes (core's `blob-meta`, sealed under that blob's own `K_blob`,
 * inbox #397): the ORIGINAL its type, the file name it was picked under and the file's modified time — the facts the
 * lightbox names, types and badges it by ([originalBlobMeta]); a derivative tier only its type (the deriver always
 * writes a JPEG). Never sent in the clear: the declare carries the seal only.
 */
function originalBlobMetaOf(source: AddMediaSource): BlobMeta {
  return { type: source.mediaType, name: source.name, modifiedAt: source.lastModified }
}
/** A derivative tier's and a derived cover's type — `shared/image/mediaDeriver` re-encodes every one as a JPEG. */
const DERIVED_META: BlobMeta = { type: 'image/jpeg' }

/** Upload progress (0..1) + cancellation hooks for [addMedia], used by the upload queue (uc.memory.upload). */
export type AddMediaOptions = { onProgress?: (fraction: number) => void; signal?: AbortSignal }

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

/**
 * Seal the original + its derivatives (each under its own `K_blob`), upload all ciphertext chunks direct-to-store,
 * and complete + attach — via an injected [MediaUploadTransport]. Shared by the authed [addMedia] and the
 * anonymous contributor upload. `memoryKey` is the key the new media is sealed under: `K_node`, or a WRITE_ONLY
 * guest's own identity key, since that guest never holds `K_node` (ADR 0031 point 7). `deriver` is injectable for
 * tests; `opts` carries `onProgress` (0..1) + an `AbortSignal`.
 *
 * The seam to core (cr-files-application B1): planning each blob ([planBlob]) and reserving + pumping them
 * ([reserveAndPump]) are core's blob transport; deriving the tiers, sealing the metadata and the complete are this
 * application's. The order — derive, seal metadata, plan, reserve + pump every blob, complete — is pinned on the wire
 * by `uploadWire.characterisation.test.ts`.
 */
export async function uploadMediaVia(
  scopeId: string,
  memoryKey: Uint8Array,
  source: AddMediaSource,
  deriver: MediaDeriver,
  transport: MediaUploadTransport,
  opts: AddMediaOptions = {},
): Promise<{ blobId: string; mediaId: string }> {
  throwIfAborted(opts.signal)
  const typed: AddMediaSource = { ...source, mediaType: await resolveMediaType(source) }
  // The client mints the media's id (ADR 0072): every blob wrap and both metadata tiers are sealed bound to it.
  const mediaId = crypto.randomUUID()
  // ONE decode of the original: `deriveTiers` produces both tiers from it and hands back the geometry it
  // already measured, so `sealMetadata` has nothing left to probe for an image.
  const { derivatives: tiers, dimensions } = await deriveTiers(typed, deriver)
  const { wrappedMeta, wrappedExif } = await sealMetadata(typed, deriver, memoryKey, { scopeId, mediaId }, dimensions)
  // index 0 = original, streamed from the source; the rest are the role-tagged derivatives — small, already in
  // memory from the derivation — in order.
  return uploadSealedMedia(scopeId, memoryKey, mediaId, {
    original: { source: typed.blob, meta: originalBlobMetaOf(typed) },
    tiers: tiers.map((t) => ({ kind: t.kind, source: new Blob([t.bytes as BlobPart]), meta: DERIVED_META })),
    wrappedMeta,
    wrappedExif,
  }, transport, opts)
}

/** A media's blobs and sealed tiers, ready to upload: the original and each role-tagged derivative with the meta its blob
 *  declares, and the two metadata tiers already sealed bound to the media. */
type SealedMedia = {
  original: { source: BlobSource; meta?: BlobMeta }
  tiers: { kind: MediaDerivativeKind; source: BlobSource; meta?: BlobMeta }[]
  wrappedMeta: string | null
  wrappedExif: string | null
}

/** Plan, reserve, pump and complete one media - the tail [uploadMediaVia] and [addCopiedMedia] share. */
async function uploadSealedMedia(
  scopeId: string, memoryKey: Uint8Array, mediaId: string, media: SealedMedia, transport: MediaUploadTransport, opts: AddMediaOptions,
): Promise<{ blobId: string; mediaId: string }> {
  const { onProgress, signal } = opts
  // index 0 = the original, the rest the derivatives in order. Each blob's meta is sealed under its own K_blob as it is planned.
  const plans = [
    planBlob(scopeId, { ownerId: mediaId, role: 'ORIGINAL' }, memoryKey, media.original.source, media.original.meta),
    ...media.tiers.map((t) => planBlob(scopeId, { ownerId: mediaId, role: t.kind }, memoryKey, t.source, t.meta)),
  ]

  // reserve one upload session per blob, then read → seal → PUT each chunk straight to the store (no base64, no
  // JSON, no credentials), window by window, passing the abort signal so a cancel stops the in-flight PUTs.
  const uploadIds = await reserveAndPump(plans, (d) => transport.reserve(d), transport.window, onProgress, signal)

  // complete — one logical media: the original + its role-tagged derivative uploads
  throwIfAborted(signal)
  const done = await transport.complete({
    mediaId,
    originalUploadId: uploadIds[0],
    derivatives: media.tiers.map((t, i) => ({ kind: t.kind, uploadId: uploadIds[i + 1] })),
    wrappedMeta: media.wrappedMeta,
    wrappedExif: media.wrappedExif,
  })
  return { blobId: expectMintedId(plans[0].blobId, done.blobId), mediaId: expectMintedId(mediaId, done.mediaId) }
}

/**
 * A media a COPY re-creates (ADR 0082 decision 11, uc.platform.copy-node): the original and each existing derivative tier
 * as sources read by frame from the media it copies (`core/export/copySource.ts` - never a `Blob`, never held whole), the
 * meta each of those blobs declared, and the two metadata tiers as plaintext - the listed tier verbatim ([rawMediaMeta]:
 * geometry, capture time and its correction, coordinates, place name, and any field a newer client wrote) and the EXIF
 * bundle. Nothing in it is decoded, derived or read again from the bytes.
 */
export type CopiedMediaSource = {
  /** `meta` absent where the copied blob declared none: the copy declares none either. */
  original: { source: BlobSource; meta?: BlobMeta }
  tiers: { kind: MediaDerivativeKind; source: BlobSource; meta?: BlobMeta }[]
  meta: Record<string, unknown> | null
  exif: MediaExif | null
}

/**
 * Re-create a copied media under `nodeId`: a fresh media id, every blob under a fresh `K_blob` wrapped under `memoryKey`
 * and bound to the new media, both tiers sealed anew bound to it - the upload [addMedia] does, without [deriveTiers]
 * and without reading EXIF (a copy hands over the tiers and facts the original already has; decoding the original would
 * hold it whole, a video included). The same transport and order as [addMedia].
 */
export async function addCopiedMedia(
  scopeId: string,
  nodeId: string,
  memoryKey: Uint8Array,
  copied: CopiedMediaSource,
  opts: AddMediaOptions = {},
): Promise<{ blobId: string; mediaId: string }> {
  throwIfAborted(opts.signal)
  const mediaId = crypto.randomUUID()
  const meta = copied.meta ? { ...copied.meta, v: finiteNum(copied.meta.v) ?? 1 } : null
  const wrappedMeta = meta && metaHasContent(meta) ? sealJson(memoryKey, prune(meta), mediaMetaContext(scopeId, mediaId)) : null
  const wrappedExif = copied.exif && hasExifPayload(copied.exif) ? sealExifTier(memoryKey, mediaExifContext(scopeId, mediaId), copied.exif) : null
  return uploadSealedMedia(scopeId, memoryKey, mediaId, { original: copied.original, tiers: copied.tiers, wrappedMeta, wrappedExif }, mediaApiTransport(scopeId, nodeId), opts)
}

/** The authed upload transport — the internal Memory control plane (`startBlobUploads` + `presignBlobUploadWindows` + `completeMedia`). */
function mediaApiTransport(scopeId: string, nodeId: string): MediaUploadTransport {
  return {
    reserve: (declares) => authedReserve(scopeId, declares),
    window: authedWindow(scopeId),
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

// --- the standalone cover blob is core's since inbox #411 ---------------------------------------------------------------
// Its upload (`uploadCoverImage`), spec and write (`setNodeCover` / `removeNodeCover`) live in `core/node/cover/`; what Memories
// adds - its second cover source, one of its own photos - is `memoryCover.ts` / `coverLoad.ts`.

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
  return newestItemsCursor(items, (it) => it.mediaId, (it) => it.updatedAt)
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
    const json = new TextDecoder().decode(v1.openField(mediaExifContext(scopeId, mediaId), key, fromB64std(exif.wrappedExif)))
    const parsed = JSON.parse(json) as unknown
    return parsed && typeof parsed === 'object' ? (parsed as MediaExif) : null
  } catch {
    return null
  }
}

/**
 * What a report of this medium hands over (uc.moderation.report, cr-report-and-hold decision 3): the `K_blob` of every
 * blob it has — the original and each derivative, tagged with its blob id — and the `K_field`s of its two metadata tiers,
 * `media-meta` and (only where this session reads it, a member) `media-exif`, each under the key the medium seals under
 * ([mediaKey]: `K_node`, or a WRITE_ONLY guest's `K_up`). The drop key never leaves; a key that does not open what it is
 * reported for is left out (`v1.fieldKeyOf` opens before it hands over). Null when no blob key opens: nothing to report.
 */
export async function mediumReportKeys(
  scopeId: string, memoryKey: Uint8Array, item: MediaItem, withExif: boolean,
): Promise<v1.ReportKey[] | null> {
  let key: Uint8Array
  try {
    key = mediaKey(scopeId, memoryKey, item)
  } catch {
    return null
  }
  const keys: v1.ReportKey[] = []
  for (const ref of [item.original, ...item.derivatives.map((d) => d.ref)]) {
    try {
      keys.push({ role: 'BLOB', ref: ref.blobId, key: unwrapBlobKey(scopeId, key, ref) })
    } catch {
      // a ref whose wrap does not open is not this medium's to hand over
    }
  }
  if (!keys.length) return null
  const field = (context: v1.FieldContext, envelope: string | null | undefined): Uint8Array | null => {
    if (!envelope) return null
    try {
      return v1.fieldKeyOf(context, key, fromB64std(envelope))
    } catch {
      return null
    }
  }
  const meta = field(mediaMetaContext(scopeId, item.mediaId), item.wrappedMeta)
  if (meta) keys.push({ role: 'MEDIA_META', ref: null, key: meta })
  if (withExif) {
    const exif = await withRefresh(() => mediaApi().getMediaExif({ scopeId, mediaId: item.mediaId })).catch(() => null)
    const exifKey = field(mediaExifContext(scopeId, item.mediaId), exif?.wrappedExif)
    if (exifKey) keys.push({ role: 'MEDIA_EXIF', ref: null, key: exifKey })
  }
  return keys
}

/** A media a meta write targets: its id + origin (which key seals it) and the tier + revision it was listed at. */
export type MediaMetaTarget = MediaRef & Pick<MediaItem, 'wrappedMeta' | 'metaRevision'>

/** The outcome of a media meta write: the display tier, the state the server confirmed or handed back, and
 *  whether this call wrote (a backfill the fresh tier no longer needs does not). */
export type MediaMetaWrite = { tier: MediaMeta | null; wrappedMeta: string | null; metaRevision: number; wrote: boolean }

/**
 * Set (or clear, with `null`) a media's **capturedAt override** (cr-0065/0066/0068) — a corrected capture time.
 * Operator-blind: the whole listed-metadata tier is re-sealed under `K_node` with the override added/removed and
 * PUT verbatim (geometry, GPS and the place name ride along unchanged — only the date is user-editable). The base
 * is the tier the media was listed with, at its `metaRevision` (ADR 0077); a peer's correction written meanwhile
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
 * capture-time override does — every other field rides along verbatim). A backfill: if
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

/** Whether a metadata tier carries anything worth sealing (else it drops to null on a re-seal). Any field but `v`
 *  counts — a known one (a tier whose only content is a resolved name is still content: sealing `null` would have
 *  the name resolved again on every open) or one a newer client wrote that this one does not know. */
function metaHasContent(m: RawMediaMeta): boolean {
  return Object.entries(m).some(([k, v]) => k !== 'v' && v !== undefined)
}

/**
 * Re-seal a metadata tier under the media's key and PUT it with the `metaRevision` it was built from
 * (`updateMediaMeta`, ADR 0077) — the one write path behind the capturedAt edit ([setMediaMeta]) and the place
 * backfill ([backfillPlaceName]). `edit` gets the RAW tier (every field verbatim, never [unsealMeta]'s projection) and returns the next
 * one, or null for no write; on a `409 stale_revision` it re-runs on the tier the conflict handed back. A present
 * tier that does not open is refused (`MetaUnreadableError`), never rebuilt from empty (#273). The key is the one
 * the media's origin fixes ([mediaKey]): a member editing what a WRITE_ONLY guest added re-seals under that guest's
 * key, never under `K_node`, so the media never changes path (ADR 0031 point 7). Drops to null when nothing is
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
    { wrappedMeta: media.wrappedMeta ?? null, metaRevision: media.metaRevision ?? 0 },
    {
      open: (wrappedMeta) => openMediaMetaForWrite(scopeId, memoryKey, media, wrappedMeta),
      seal: (raw) =>
        metaHasContent(raw) ? sealJson(mediaKey(scopeId, memoryKey, media), prune(raw), mediaMetaContext(scopeId, mediaId)) : null,
      send: async (wrappedMeta, metaRevision) => {
        await withRefresh(() => mediaApi().updateMediaMeta({ scopeId, mediaId, mediaMetaUpdate: { wrappedMeta, metaRevision } }))
      },
    },
    edit,
  )
  return { tier: state.wrappedMeta ? normalizeMediaMeta(meta) : null, wrappedMeta: state.wrappedMeta, metaRevision: state.metaRevision, wrote }
}

function tierRef(item: MediaItem, kind: MediaDerivativeKind): BlobRef | undefined {
  return item.derivatives.find((d) => d.kind === kind)?.ref
}

/**
 * Whether a media carries derivative tiers — i.e. it had a derivable frame (an image or video, cr-0045). A
 * gallery renders these as a thumbnail/preview; one **without** derivatives is a non-image/video (E1) shown as
 * an icon tile. (Its type is sealed in its original's blob meta, [originalMediaType] — the server never sees it.)
 */
export function hasDerivatives(item: MediaItem): boolean {
  return item.derivatives.length > 0
}

/** Whether a sealed media type names a video (#104/#105) — defined beside the playback shim, which applies the
 *  same predicate. Pure; the caller passes [originalMediaType]. */
export { isVideoMediaType } from '../../sw/protocol'

/**
 * The original's sealed blob meta (inbox #397) — its type, the file name it was uploaded under and the file's modified
 * time, opened under the original's `K_blob`, which is unwrapped under the key the media's origin fixes ([mediaKey]).
 * Synchronous: the meta rides the listing (`MediaSummary`/`ShareMedia`), no fetch. Null for a media whose original
 * carries none or whose meta does not open (a ref the server moved, another blob's meta in its place) — the caller
 * then falls back as if nothing was known; never a throw.
 */
export function originalBlobMeta(scopeId: string, memoryKey: Uint8Array, item: MediaItem): OpenedBlobMeta | null {
  let key: Uint8Array
  try {
    key = mediaKey(scopeId, memoryKey, item)
  } catch {
    return null
  }
  return readBlobMeta(scopeId, key, item.original)
}

/** The original's media type from its blob meta — the persisted video signal (`video/*` draws the tile's play badge
 *  and the lightbox's `<video>` branch, #104/#105) — or undefined when it carries none. */
export function originalMediaType(scopeId: string, memoryKey: Uint8Array, item: MediaItem): string | undefined {
  return mediaTypeOf(originalBlobMeta(scopeId, memoryKey, item))
}

/** The type a blob meta names, or undefined for none (a meta written with an empty or over-long type). */
export function mediaTypeOf(meta: OpenedBlobMeta | null | undefined): string | undefined {
  return cleanStr(meta?.type)
}

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
// The provider itself is core's ([makeChunkProvider], `core/blob/transport.ts`); this is the authed app's wiring of it
// over a media's original.

/** A media's derivative tiers as the authed app reads them, by chunk: each tier's ref, the meta its blob declared and a
 *  {@link ChunkProvider} over it under the key the media's origin fixes ([mediaKey]) - what a copy hands over instead of
 *  deriving the tiers again (ADR 0082 decision 11). Nothing is fetched until a chunk is asked for. */
export function tierChunkProviders(
  scopeId: string, memoryKey: Uint8Array, item: MediaItem,
): ({ kind: MediaDerivativeKind; ref: BlobRef } & ReadByChunk)[] {
  const key = mediaKey(scopeId, memoryKey, item)
  return item.derivatives.map(({ kind, ref }) => ({ kind, ref, ...readByChunk(scopeId, key, ref) }))
}

/** A blob as the authed app reads it by chunk: the meta it declared and a provider over its frames. */
export type ReadByChunk = { meta: OpenedBlobMeta | null; provider: ChunkProvider }

/** Any blob of the scope by chunk under `key` (its owner's key: a media's [mediaKey], a node's content key for its cover). */
export function readByChunk(scopeId: string, key: Uint8Array, ref: BlobRef): ReadByChunk {
  return { meta: readBlobMeta(scopeId, key, ref), provider: makeChunkProvider(scopeId, key, ref, () => presignAllChunks(scopeId, ref.blobId)) }
}

/** A {@link ChunkProvider} for the authed app over a media's original: presigns the whole blob via the media
 *  endpoint (bearer, `withRefresh`), and opens it under the key the media's origin fixes ([mediaKey]). */
export function chunkProvider(scopeId: string, memoryKey: Uint8Array, item: MediaItem): ChunkProvider {
  const ref = downloadRef(item)
  return makeChunkProvider(scopeId, mediaKey(scopeId, memoryKey, item), ref, () => presignAllChunks(scopeId, ref.blobId))
}
