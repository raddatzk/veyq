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
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { isHttpStatus, HttpStatus } from '../../shared/httpError'
import { NODE_REKEY_PENDING, NodeRekeyPendingError, STALE_NODE_EPOCH, StaleNodeEpochError, writeAtNodeEpoch } from '../../shared/rekeyRetry'
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
  type FrameOpener,
} from '../../core/blob/transport'
import type { BlobDeclare, BlobRef, BlobSource, ReservedUpload, WindowFn } from '../../core/blob/types'
import type { BlobMeta, OpenedBlobMeta } from '../../core/blob/meta'
import { contentDigest, sealedByteSize, shareBlob, type SharedBlobRequest, type StoredBlob } from '../../core/blob/sharedBlob'
import { sniffMediaType } from './download'
import { readItemPages, type ItemsOnePage } from '../../core/items/itemsLive'
import { mediaKey, uploaderOriginOf, type UploaderOrigin } from './mediaKey'
import { cleanStr, finiteNum, mediaMetaContext, normalizeMediaMeta, openMediaMeta, type RawMediaMeta } from './mediaOpen'
import { MetaConflictError, MetaUnreadableError, writeSealedMeta, type MetaEditContext } from '../../core/node/sealedMetaWrite'
import type { ChunkProvider } from '../../sw/protocol'
import { createBrowserDeriver, deriveTiers, type MediaDeriver, type AddMediaSource, type Dimensions } from '../../shared/image/mediaDeriver'
import { readExifTags, type ExifTags } from '../../shared/image/exifTags'
import { videoCaptureTimeFromHeader } from '../../shared/image/videoHeader'

// The on-device pixel stage lives in `shared/image/mediaDeriver` (cr-0143). Re-exported here so existing importers of
// `./media` (share.ts, useOptimisticTiles.ts, uploadQueue.ts, tests) keep their import path unchanged.
export { scaleToMaxEdge, TIER_SPECS, createBrowserDeriver, tileVideoPosterOf, type MediaDeriver, type AddMediaSource, type Dimensions } from '../../shared/image/mediaDeriver'

// The per-uploader "Beiträge" buckets (en.memory.buckets, ADR 0034) live in `./mediaBuckets` (#96) — uploader
// metadata, not blob data. Re-exported here so existing importers of `./media` (MemoryBuckets.vue, tests) keep
// their import path unchanged.
export { openBucketName, listBuckets, createBucket, renameBucket, deleteBucket, moveBucketMedia, type BucketItem } from './mediaBuckets'

// The neutral blob transport — plan, pump, the authed reserve/window, the read side and the chunk provider — is core's
// (`core/blob/`, cr-files-application B1). Re-exported here so existing importers of `./media` (share.ts, the cover
// store, tests) keep their import path unchanged.
export { CHUNK_SIZE, PUMP_IN_FLIGHT, windowPuts, openBlobVia, openBlob, makeChunkProvider } from '../../core/blob/transport'
export type { BlobRef, BlobDeclare, ChunkPut, ReservedUpload, WindowFn } from '../../core/blob/types'

// Every seal in this layer binds the object it is stored as (ADR 0072). A `K_blob` wrap is bound to (scope, owner,
// blob, role) by core's blob transport — the owner is the media for its tiers and the node for a cover — so a server
// that attaches a blob under another media, or serves a thumbnail in another's slot, gets a wrap that does not open
// there.
//
// A media's seals are under `K_node`, except for one a WRITE_ONLY guest added: that one's are under the guest's own
// key (ADR 0031 point 7). A function handed the MEDIA resolves the key itself ([mediaKey]); one handed only an id
// or a blob ref is handed the key, which its caller asks [mediaKey] for.

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
 * - **capture time** `capturedAt` (a photo's EXIF date, a video's container time, else the file's date) + any user
 *   `capturedAtOverride` — date sort/display/edit (cr-0066). Effective = `capturedAtOverride ?? capturedAt`.
 *   `capturedAtSource` records where the derived `capturedAt` came from: `'exif'` (a real `DateTimeOriginal`,
 *   trustworthy), `'container'` (a video's recorded time, inbox #794, trustworthy) or `'file'` (the file's
 *   modification date, a fallback that must NOT feed a Memory's `auto` date range — an edited photo's file date
 *   would wrongly stretch it). A client that predates a value reads it as no source, so as untrustworthy;
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
  capturedAtSource?: CaptureSource
  gpsLat?: number
  gpsLng?: number
  /**
   * The resolved place name for this photo's own coordinate, and the hierarchy level it was taken from
   * (`city`, `country`, …). Sealed once, then read from the tier forever — the provider is asked once per
   * place, never once per open ([ADR 0016](../../../../../docs/decisions/0016-geocoding-provider-neutrality.md)
   * § Request discipline). **The only field this CR may add to the listed tier**, because that tier is capped
   * at 512 base64 and is shared with the capture-time PUT: overflow would break that write too, which is why
   * the name is clamped to [PLACE_NAME_MAX_BYTES] before it is sealed.
   */
  placeName?: string
  placeLevel?: string
  /**
   * A GPX track's own name and its activity, as a member gave them (uc.memory.tracks A8, ADR 0130 decision 9): a track has
   * no geometry, GPS or place name, so the two ride the room those leave in the 512-base64 tier. The name is clamped to
   * [TRACK_NAME_MAX_BYTES]; the activity is an opaque token here, read by `track/trackLabel.ts`.
   */
  trackName?: string
  trackActivity?: string
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
  /** EV compensation the photographer dialled in. Signed — EXIF's `ExposureBiasValue`. */
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
  /** When this media last changed (cr-0107) — served, not the keyset: a page answers its own `cursor` (ADR 0033 decision 3).
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
  /** A moderator blocked this medium, its node or a node above it (uc.moderation.take-a-measure): its tile and the
   *  lightbox show "Blocked by the operator" and load no bytes (each would answer 451); only delete is offered. */
  blocked?: boolean
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
    ...(s.blockedByOperator ? { blocked: true } : {}),
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
  let key: Uint8Array
  try {
    key = mediaKey(scopeId, memoryKey, item)
  } catch {
    return null // an uploader row whose chain does not open: no metadata, as an unopenable seal
  }
  return openMediaMeta(scopeId, key, item.mediaId, item.wrappedMeta)
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

/** The listed tier for a WRITE: absent → a fresh `{ v: 1 }`; present but unopenable or unparseable → refused. */
function openMediaMetaForWrite(scopeId: string, memoryKey: Uint8Array, media: MediaRef, wrappedMeta: string | null): RawMediaMeta {
  return openMediaMetaUnder(scopeId, () => mediaKey(scopeId, memoryKey, media), media.mediaId, wrappedMeta)
}

/** {@link openMediaMetaForWrite} under the key `key` resolves to (the media's own, [mediaKey]); a key that does not
 *  resolve is a tier that does not open. */
function openMediaMetaUnder(scopeId: string, key: () => Uint8Array, mediaId: string, wrappedMeta: string | null): RawMediaMeta {
  if (!wrappedMeta) return { v: 1 }
  let parsed: unknown
  try {
    parsed = JSON.parse(new TextDecoder().decode(v1.openField(mediaMetaContext(scopeId, mediaId), key(), fromB64std(wrappedMeta))))
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

/** Where a capture time came from: a photo's EXIF (or its XMP, which `exif` names too: both are the photo's embedded
 *  metadata), a video's container header, or the file's own date. */
export type CaptureSource = 'exif' | 'container' | 'file'
/** The two sources the file's bytes carry, so a trustworthy capture time; the file's date is only a fallback. */
export type EmbeddedSource = Exclude<CaptureSource, 'file'>

/** A media's capture time and where it came from — an embedded date is trustworthy, a file date is a fallback. */
export type CaptureTime = { capturedAt?: number; capturedAtSource?: CaptureSource }

/**
 * The rule: the date the bytes carry (a photo's EXIF, a video's container time — [embeddedCaptureTime]) if there is
 * one, else the file's own date. The source is recorded because only a trustworthy embedded date (or a user override)
 * may feed a Memory's `auto` range (cr-0067) — a file modification date must not stretch it.
 */
export function captureTimeOf(embedded: number | undefined, lastModified: number | undefined, embeddedSource: EmbeddedSource = 'exif'): CaptureTime {
  const capturedAt = embedded ?? finiteNum(lastModified)
  if (capturedAt === undefined) return {}
  return { capturedAt, capturedAtSource: embedded !== undefined ? embeddedSource : 'file' }
}

/**
 * A video's recorded time from its container header (`videoHeader.ts`; MP4 / QuickTime only), best-effort: another
 * type, a container that carries none or a read that fails yields nothing. Both readers of a video's date — the tile
 * and the sealed tier — go through here, as both of an image's go through [exifCapturedAt].
 */
async function containerCapturedAt(file: Blob, mediaType: string): Promise<number | undefined> {
  if (!mediaType.startsWith('video/')) return undefined
  try {
    return (await videoCaptureTimeFromHeader(file)) ?? undefined
  } catch {
    return undefined
  }
}

/**
 * The same rule applied to a **picked file**, before anything is read into memory or uploaded: parse its EXIF
 * date (best-effort — a non-image, absent EXIF or a decode error yields nothing) and fall back to the date the
 * file itself carries. `lastModified` is passed explicitly rather than read off the blob, so the fallback half
 * of the rule is visible at the call site and a caller cannot silently lose it.
 */
export async function readCaptureTime(file: Blob, mediaType: string, lastModified: number | undefined): Promise<CaptureTime> {
  if (mediaType.startsWith('video/')) return captureTimeOf(await containerCapturedAt(file, mediaType), lastModified, 'container')
  if (!mediaType.startsWith('image/')) return captureTimeOf(undefined, lastModified)
  try {
    const tags = await readExifTags(file, 'date')
    // XMP only where EXIF names no capture time (an editor's export, inbox #808), so a camera's file is read once
    const xmp = tags?.DateTimeOriginal ?? tags?.DateTimeDigitized ? undefined : await readExifTags(file, 'xmpDate')
    return captureTimeOf(exifCapturedAt({ ...tags, ...xmp }), lastModified)
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

/** A photo's embedded capture date, by its one precedence rule: EXIF's capture dates, then XMP's (inbox #808), then the
 *  EXIF date the file was last changed. Both readers of an image's date go through here. */
function exifCapturedAt(tags: ExifTags): number | undefined {
  return finiteNum(tags.DateTimeOriginal ?? tags.DateTimeDigitized ?? tags.xmpCapturedAt ?? tags.DateTime)
}

/** Altitude, signed. `GPSAltitudeRef` 1 means *below* sea level. */
function signedAltitude(metres: number | undefined, ref: number | undefined): number | undefined {
  if (metres === undefined) return undefined
  return ref === 1 ? -metres : metres
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
 * Read an image's tags ([readExifTags]: the formats and how the file is read are its header's) and split them into
 * the **capture time** (epoch-ms, cr-0066), the **position** and the **panel bundle** ([MediaExif], date-free).
 * Best-effort: non-images, unreadable/absent EXIF, or a decode error → `{ capturedAt: undefined, exif: null }`
 * (the item still uploads). Never leaves the device in the clear — the caller seals both.
 *
 * Each field takes the EXIF tag first, then the one an editor writes when the camera wrote none. **Raw values only**:
 * `Flash` is the bitfield and `Orientation` the number 1..8, never a library's English phrase ("Flash fired, compulsory
 * flash mode", "right-top"): a bit test on the phrase reads 0, `finiteNum` of it is nothing (that sealed `orientation`
 * empty from cr-0064 until it was measured), and a library's wording sealed into a user's tier would reach a German
 * reader untranslated.
 */
async function readExif(source: AddMediaSource): Promise<{ capturedAt?: number; gpsLat?: number; gpsLng?: number; exif: MediaExif | null }> {
  if (!source.mediaType.startsWith('image/')) return { exif: null }
  try {
    const tags = await readExifTags(source.blob, 'all')
    if (!tags) return { exif: null }
    const exif: MediaExif = {
      v: 1,
      make: cleanStr(tags.Make),
      model: cleanStr(tags.Model),
      lens: cleanStr(tags.LensModel ?? tags.LensMake),
      focalLength: finiteNum(tags.FocalLength),
      focalLength35: finiteNum(tags.FocalLengthIn35mmFilm),
      fNumber: finiteNum(tags.FNumber),
      exposureTime: finiteNum(tags.ExposureTime),
      exposureCompensation: finiteNum(tags.ExposureBiasValue),
      iso: finiteNum(tags.ISOSpeedRatings),
      flash: finiteNum(tags.Flash),
      orientation: finiteNum(tags.Orientation),
      software: cleanStr(tags.Software ?? tags.xmpCreatorTool),
      artist: cleanStr(tags.Artist),
      copyright: cleanStr(tags.Copyright),
      rating: finiteNum(tags.Rating ?? tags.xmpRating),
      title: cleanStr(tags.xmpTitle ?? tags.iptcObjectName),
      description: cleanStr(tags.xmpDescription ?? tags.ImageDescription),
      altitude: signedAltitude(tags.GPSAltitude, tags.GPSAltitudeRef),
      imgDirection: finiteNum(tags.GPSImgDirection),
      imgDirectionRef: cleanStr(tags.GPSImgDirectionRef),
      gpsAccuracy: finiteNum(tags.GPSHPositioningError),
      w: finiteNum(tags.PixelXDimension ?? tags.ImageWidth),
      h: finiteNum(tags.PixelYDimension ?? tags.ImageLength),
    }
    return {
      capturedAt: exifCapturedAt(tags),
      gpsLat: finiteNum(tags.latitude), // GPS rides the listed tier now (cr-0068), not the lazy EXIF
      gpsLng: finiteNum(tags.longitude),
      exif: hasExifPayload(exif) ? exif : null,
    }
  } catch {
    return { exif: null }
  }
}

/** What the metadata tiers are sealed from, read on the device before any key is used: the geometry and the EXIF read. */
type MetadataRead = { dims: Dimensions | null; capturedAtFrom?: EmbeddedSource } & Awaited<ReturnType<typeof readExif>>

/**
 * Read what the two metadata tiers carry — key-free, sending nothing, so it is part of a file's on-device stage
 * ([prepareMedia]). `knownDims` is the geometry the tier derivation already measured, when it had it — an image always
 * does; absent for a video or an underivable source, and only then is a probe (its own decode) worth paying for.
 */
async function readMetadata(source: AddMediaSource, deriver: MediaDeriver, knownDims: Dimensions | null): Promise<MetadataRead> {
  const [dims, meta, recorded] = await Promise.all([
    knownDims ?? probeDims(source, deriver),
    readExif(source),
    containerCapturedAt(source.blob, source.mediaType),
  ])
  return recorded === undefined ? { dims, ...meta } : { dims, ...meta, capturedAt: recorded, capturedAtFrom: 'container' }
}

/**
 * Seal the two metadata tiers under `K_node` (best-effort, operator-blind) from what [readMetadata] read: the always-listed **metadata** tier
 * (`{v,w,h,capturedAt,capturedAtSource,gpsLat,gpsLng}` — geometry + the EXIF or container date, else the file's date,
 * + GPS; and what an import restores, a corrected capture time and a place name) and the lazy **EXIF** tier ([MediaExif]: the panel bundle). Each tier is null when
 * its source is absent — a video, a screenshot or a PDF seals no EXIF tier; its name and type ride its blob's meta.
 */
function sealMetadata(
  meta: MetadataRead,
  lastModified: number | undefined,
  memoryKey: Uint8Array,
  /** The media the tiers belong to — both are sealed bound to it (ADR 0072). */
  media: { scopeId: string; mediaId: string },
  /** What an import restores beside the bytes ([RestoredMediaFacts]), sealed into the listed tier with the rest. */
  restored: RestoredMediaFacts = {},
): { wrappedMeta: string | null; wrappedExif: string | null } {
  const dims = meta.dims
  const { capturedAt, capturedAtSource } = captureTimeOf(meta.capturedAt, lastModified, meta.capturedAtFrom)
  const placeName = cleanStr(restored.placeName)
  const metaTier: MediaMeta = {
    v: 1,
    w: dims?.w,
    h: dims?.h,
    capturedAt,
    capturedAtOverride: finiteNum(restored.capturedAtOverride),
    capturedAtSource,
    gpsLat: meta.gpsLat,
    gpsLng: meta.gpsLng,
    placeName: placeName === undefined ? undefined : clampPlaceName(placeName),
    ...trackLabelFields(restored.trackName ?? null, restored.trackActivity ?? null),
  }
  const hasMeta = metaHasContent(metaTier)
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
 * inbox #397): the ORIGINAL its type, the file name it was picked under, the file's modified time — the facts the
 * lightbox names, types and badges it by ([originalBlobMeta]) - and the SHA-256 of its bytes, by which a later upload of
 * the same file into the Memory's cover or banner dialog shares its stored object (ADR 0013 § 5, `storedMatch.ts`); a
 * derivative tier only its type (the deriver always writes a JPEG). Never sent in the clear: the declare carries the seal.
 */
function originalBlobMetaOf(source: AddMediaSource, sha256: string): BlobMeta {
  return { type: source.mediaType, name: source.name, modifiedAt: source.lastModified, sha256 }
}
/** A derivative tier's and a derived cover's type — `shared/image/mediaDeriver` re-encodes every one as a JPEG. */
const DERIVED_META: BlobMeta = { type: 'image/jpeg' }

/** Upload progress (0..1) + cancellation hooks for [addMedia], used by the upload queue (uc.memory.upload). */
export type AddMediaOptions = {
  onProgress?: (fraction: number) => void
  signal?: AbortSignal
  /** A stored blob of the target node whose plaintext is the picked file - by its digest and sealed size - which the new
   *  medium's original shares instead of being uploaded (ADR 0013 § 5: a video banner, `storedMatch.bannerOriginalOf`).
   *  A member's upload only; absent, the original is uploaded. */
  storedOriginal?: (sha256: string, byteSize: number) => StoredBlob | null
  /** The target node's epoch as read with `memoryKey` (`TreeNode.epoch`): the completion states it as `nodeEpoch`, since
   *  every wrap and seal of the medium is under that node's key (ADR 0109 point 6). Every production caller states it
   *  ([UploadTarget.nodeEpoch], the import); absent it is 0, the epoch of a scope never rotated — a suite's fake scope. */
  nodeEpoch?: number
  /** What the bytes cannot carry, sealed at birth ([RestoredMediaFacts]): an import's restored facts, or the label a
   *  track was given as it was added; absent for every other upload. */
  restored?: RestoredMediaFacts
  /** The source's on-device stage, already run ([prepareMedia]) — by the upload queue while the file before it was on
   *  the wire (inbox #220). Absent, [uploadMediaVia] runs it itself. It must be of the same source. */
  prepared?: PreparedMedia
}

/**
 * A file's ON-DEVICE stage (inbox #220): its type, its digest beside its tiers, and what its metadata tiers will carry.
 * Key-free and sending nothing — every key use, seal and request of the file stays in [uploadMediaVia], after the
 * device floor — so the upload queue may run it for the next file while the current one is on the wire.
 */
export type PreparedMedia = {
  /** The source, re-typed by [resolveMediaType]. */
  source: AddMediaSource
  sha256: string
  tiers: Awaited<ReturnType<typeof deriveTiers>>['derivatives']
  meta: MetadataRead
}

/**
 * Run [PreparedMedia]'s stage for `source`. The original's digest (its `blob-meta` `sha256`, read frame by frame in core's
 * digest worker - or, for an imported download's payload, the one core's check already computed, `contentDigest`, inbox
 * #555) and its tiers are started TOGETHER (inbox #554): the derivation does not need the digest, and run first and alone
 * the digest was the upload's critical path while every worker waited. ONE decode of the original: `deriveTiers`
 * produces both tiers from it and hands back the geometry it already measured, so [readMetadata] has nothing left to
 * probe for an image. `deriveTiers` never rejects (its catch makes the upload original-only), so only the digest's abort
 * or a read the browser refuses ends it here; that drops at most one derivation, and nothing is reserved.
 */
export async function prepareMedia(source: AddMediaSource, deriver: MediaDeriver, signal?: AbortSignal): Promise<PreparedMedia> {
  throwIfAborted(signal)
  const typed: AddMediaSource = { ...source, mediaType: await resolveMediaType(source) }
  const [sha256, { derivatives: tiers, dimensions }] = await Promise.all([
    contentDigest(typed.blob, signal),
    deriveTiers(typed, deriver),
  ])
  return { source: typed, sha256, tiers, meta: await readMetadata(typed, deriver, dimensions) }
}

/**
 * What an import of a download restores of a medium beside its original (uc.platform.export-import): a capture time the
 * member CORRECTED (`capturedAtOverride`) and a place name the bytes do not carry. Both are sealed into the listed tier
 * at birth, in the one completion that adds the medium and under the key it lands under — so a rotation and re-key that
 * lands while the import runs cannot leave them to a second write opening a tier the re-key re-sealed (inbox #700), as a
 * copy seals every fact at birth ([addCopiedMedia]). The name is clamped as a backfill clamps it ([clampPlaceName]). A
 * GPX track's own name and activity (ADR 0130 decision 9) are restored the same way, the name clamped as its edit clamps it
 * - and sealed the same way when a member or guest names a track as they add it (`enqueueUploads`' `facts`), so a track's
 * label never needs a second write after its upload.
 */
export type RestoredMediaFacts = { capturedAtOverride?: number; placeName?: string; trackName?: string; trackActivity?: string }

/** One logical media to complete: the original session + its role-tagged derivative sessions + the sealed metadata. */
export type CompleteDecl = {
  /** The client-minted media id every upload of it was started for (ADR 0072). */
  mediaId: string
  /** The original's upload - or, in its place, a new row on a stored object of the node ([originalShared]). */
  originalUploadId?: string
  originalShared?: SharedBlobRequest
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
 * application's. The order — the digest beside the derivation, seal metadata, plan, reserve + pump every blob,
 * complete — is pinned on the wire by `uploadWire.characterisation.test.ts`.
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
  // The on-device stage: prepared ahead by the upload queue (inbox #220), else run here. Nothing before this line used a
  // key; everything after it does, so it all runs inside this upload, after the caller's device floor.
  const { source: typed, sha256, tiers, meta } = opts.prepared ?? await prepareMedia(source, deriver, opts.signal)
  throwIfAborted(opts.signal)
  const stored = opts.storedOriginal?.(sha256, sealedByteSize(typed.blob.size)) ?? null
  // The client mints the media's id (ADR 0072): every blob wrap and both metadata tiers are sealed bound to it.
  const mediaId = crypto.randomUUID()
  const { wrappedMeta, wrappedExif } = sealMetadata(meta, typed.lastModified, memoryKey, { scopeId, mediaId }, opts.restored)
  // index 0 = original, streamed from the source; the rest are the role-tagged derivatives — small, already in
  // memory from the derivation — in order.
  return uploadSealedMedia(scopeId, memoryKey, mediaId, {
    original: { source: typed.blob, meta: originalBlobMetaOf(typed, sha256), ...(stored ? { stored } : {}) },
    tiers: tiers.map((t) => ({ kind: t.kind, source: new Blob([t.bytes as BlobPart]), meta: DERIVED_META })),
    wrappedMeta,
    wrappedExif,
  }, transport, opts)
}

/** A media's blobs and sealed tiers, ready to upload: the original and each role-tagged derivative with the meta its blob
 *  declares, and the two metadata tiers already sealed bound to the media. */
type SealedMedia = {
  /** `stored`: the node already holds these bytes - the original is a shared row on them, nothing uploaded. */
  original: { source: BlobSource; meta?: BlobMeta; stored?: StoredBlob }
  tiers: { kind: MediaDerivativeKind; source: BlobSource; meta?: BlobMeta }[]
  wrappedMeta: string | null
  wrappedExif: string | null
}

/** Plan, reserve, pump and complete one media - the tail [uploadMediaVia] and [addCopiedMedia] share. */
async function uploadSealedMedia(
  scopeId: string, memoryKey: Uint8Array, mediaId: string, media: SealedMedia, transport: MediaUploadTransport, opts: AddMediaOptions,
): Promise<{ blobId: string; mediaId: string }> {
  const { onProgress, signal } = opts
  const original = { ownerId: mediaId, role: 'ORIGINAL' }
  // A stored original is a new row on the node's object (ADR 0013 § 5); else index 0 is the original's plan. The rest are
  // the derivatives in order. Each blob's meta is sealed under its own K_blob as it is planned.
  const shared = media.original.stored ? shareBlob(scopeId, media.original.stored, memoryKey, original, media.original.meta) : null
  const plans = [
    ...(shared ? [] : [planBlob(scopeId, original, memoryKey, media.original.source, media.original.meta)]),
    ...media.tiers.map((t) => planBlob(scopeId, { ownerId: mediaId, role: t.kind }, memoryKey, t.source, t.meta)),
  ]
  const first = shared ? 0 : 1 // where the derivatives' plans start

  // reserve one upload session per blob, then read → seal → PUT each chunk straight to the store (no base64, no
  // JSON, no credentials), window by window, passing the abort signal so a cancel stops the in-flight PUTs.
  const uploadIds = await reserveAndPump(plans, (d) => transport.reserve(d), transport.window, onProgress, signal)

  // complete — one logical media: the original (an upload, or the shared row) + its role-tagged derivative uploads
  throwIfAborted(signal)
  const done = await transport.complete({
    mediaId,
    ...(shared ? { originalShared: shared.request } : { originalUploadId: uploadIds[0] }),
    derivatives: media.tiers.map((t, i) => ({ kind: t.kind, uploadId: uploadIds[i + first] })),
    wrappedMeta: media.wrappedMeta,
    wrappedExif: media.wrappedExif,
  })
  return { blobId: expectMintedId(shared ? shared.ref.blobId : plans[0].blobId, done.blobId), mediaId: expectMintedId(mediaId, done.mediaId) }
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
  return uploadSealedMedia(
    scopeId, memoryKey, mediaId, { original: copied.original, tiers: copied.tiers, wrappedMeta, wrappedExif },
    mediaApiTransport(scopeId, nodeId, opts.nodeEpoch ?? 0), opts,
  )
}

/** The authed upload transport — the internal Memory control plane (`startBlobUploads` + `presignBlobUploadWindows` + `completeMedia`). */
function mediaApiTransport(scopeId: string, nodeId: string, nodeEpoch: number): MediaUploadTransport {
  return {
    reserve: (declares) => authedReserve(scopeId, declares),
    window: authedWindow(scopeId),
    async complete(media) {
      const done = await withRefresh(() =>
        mediaApi().completeMedia({
          scopeId,
          mediaBatchRequest: {
            items: [{
              nodeEpoch, mediaId: media.mediaId, nodeId, originalUploadId: media.originalUploadId ?? null, originalShared: media.originalShared ?? null,
              derivatives: media.derivatives, wrappedMeta: media.wrappedMeta, wrappedExif: media.wrappedExif,
            }],
          },
        }),
      )
      const m = done.results[0]
      // the per-item epoch refusals (`MediaStore.attach`), as the retry reads a `409 node_rekey_pending` / `409 stale_epoch`
      if (m?.error === NODE_REKEY_PENDING) throw new NodeRekeyPendingError()
      if (m?.error === STALE_NODE_EPOCH) throw new StaleNodeEpochError()
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
  return uploadMediaVia(scopeId, memoryKey, source, deriver, mediaApiTransport(scopeId, nodeId, opts.nodeEpoch ?? 0), opts)
}

// --- the standalone cover blob is core's since inbox #411 ---------------------------------------------------------------
// Its upload (`uploadCoverImage`), spec and write (`setNodeCover` / `removeNodeCover`) live in `core/node/cover/`; what Memories
// adds - where an equal picture is already stored, so the cover shares it - is `storedMatch.ts` (`findStoredCover`).

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
 * (`deletedAt`/`deletedBy`), newest-trashed first — every page of the listing (not the live conditional-GET; it pages
 * on the live list's keyset, so the order by `deletedAt` is set here, once every page is in). Each item still carries
 * its original + derivative tiers, so the tab renders thumbnails the same way the gallery does.
 */
export async function listTrash(scopeId: string, nodeId: string): Promise<MediaItem[]> {
  const read = await readItemPages(
    () => readMediaPage(scopeId, nodeId, { state: 'trashed' }),
    (since) => readMediaPage(scopeId, nodeId, { since, state: 'trashed' }),
    (m) => m.mediaId,
  )
  const items = read.notModified ? [] : read.items // nothing conditional was asked: exhaustiveness only
  const at = (m: MediaItem) => m.deletedAt?.getTime() ?? 0
  return items.sort((a, b) => at(b) - at(a) || (a.mediaId < b.mediaId ? -1 : a.mediaId > b.mediaId ? 1 : 0))
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
 * How many media one page of the list asks for (ADR 0033 decision 6): the server's cap, `ItemsLimit`'s 1000. Measured on
 * the production recipe pinned to two cores at 10,000 photos (2026-10-19, a developer Mac, load 4-5, so indicative), the
 * whole list read warm in 829 ms in pages of 1000, 961 ms in pages of 500 and 1,058 ms in pages of 250 — 13 SELECTs a page
 * at every size, so a page costs a round trip and little else. A page is no first screen (the gallery's order is the
 * sealed capture time, read on the device), so a smaller one buys nothing the user sees.
 */
export const MEDIA_PAGE_LIMIT = 1000

/**
 * A node's media read in full — every page of it (uc.memory.live-updates, cr-0107, ADR 0033 decisions 2, 3 and 6). A
 * poll echoes the cached `mediaRevision` as `ifNoneMatch`; unchanged ⇒ `{ notModified: true }` (the server ran no media
 * query). On a change ⇒ a **keyset delta** — only the media past the `since` cursor, in pages until none remain — with
 * the FIRST page's revisions, the node's current `mediaIds` set for delete-reconcile only when a delete advanced
 * `deleteRevision` past the echoed `sinceDelete` (else null), and the `cursor` of the last page — what the next poll
 * echoes as `since` (null: no page carried a row; keep the one held). First load: omit `ifNoneMatch`/`since`.
 * Operator-blind: opaque counters + ids + wraps only.
 */
export type MediaPage =
  | { notModified: true }
  | { notModified: false; items: MediaItem[]; mediaRevision: number; deleteRevision: number; mediaIds: string[] | null; cursor: string | null }

/** ONE page of the list as the server answers it — what [listMediaPages] reads in a loop. */
export type MediaOnePage =
  | { notModified: true }
  | { notModified: false; items: MediaItem[]; mediaRevision: number; deleteRevision: number; mediaIds: string[] | null; more: boolean; cursor: string | null }

/** One page of a node's media — `limit` items at most ([MEDIA_PAGE_LIMIT] by default), `more` while more remain. The
 *  one call of the media list; [listMediaPages] is the loop over it, and no other caller pages by hand. */
export async function listMediaPage(
  scopeId: string,
  nodeId: string,
  opts: { ifNoneMatch?: string; since?: string; sinceDelete?: number; limit?: number; state?: 'trashed' } = {},
): Promise<MediaOnePage> {
  const page = await readMediaPage(scopeId, nodeId, opts)
  if (page.notModified) return page
  return { notModified: false, items: page.items, mediaRevision: page.revision, deleteRevision: page.deleteRevision, mediaIds: page.ids, more: page.more, cursor: page.cursor }
}

async function readMediaPage(
  scopeId: string,
  nodeId: string,
  opts: { ifNoneMatch?: string; since?: string; sinceDelete?: number; limit?: number; state?: 'trashed' },
): Promise<ItemsOnePage<MediaItem>> {
  try {
    const page = await withRefresh(() =>
      mediaApi().listNodeMedia({
        scopeId,
        nodeId,
        ifNoneMatch: opts.ifNoneMatch,
        since: opts.since,
        sinceDelete: opts.sinceDelete,
        limit: opts.limit ?? MEDIA_PAGE_LIMIT,
        ...(opts.state ? { state: opts.state } : {}),
      }),
    )
    return {
      notModified: false,
      items: pageItems(nodeId, page),
      revision: page.mediaRevision,
      deleteRevision: page.deleteRevision,
      ids: page.mediaIds ?? null,
      more: page.more ?? false,
      cursor: page.cursor ?? null,
    }
  } catch (e) {
    if (isHttpStatus(e, HttpStatus.notModified)) return { notModified: true } // idle fast path — nothing changed
    throw e
  }
}

/**
 * Every page of a node's media from `opts` (core's [readItemPages]): the first read is conditional when `ifNoneMatch` is
 * given, every later one continues from the page before's cursor with the first page's delete revision. The one loop
 * the gallery's first load, its live poll, the cover picker and the export go through.
 */
export async function listMediaPages(
  scopeId: string,
  nodeId: string,
  opts: { ifNoneMatch?: string; since?: string; sinceDelete?: number } = {},
): Promise<MediaPage> {
  const read = await readItemPages(
    () => readMediaPage(scopeId, nodeId, opts),
    (since, sinceDelete) => readMediaPage(scopeId, nodeId, { since, sinceDelete }),
    (m) => m.mediaId,
  )
  if (read.notModified) return read
  return { notModified: false, items: read.items, mediaRevision: read.revision, deleteRevision: read.deleteRevision, mediaIds: read.ids, cursor: read.cursor }
}

/**
 * List a node's media in full (opaque metadata + the K_blob wraps for the original + every derivative tier): every page,
 * no ETag — used where a snapshot suffices (cover picker, export, tests); the live gallery reads [listMediaPages] for its
 * revisions and cursor.
 */
export async function listMedia(scopeId: string, nodeId: string): Promise<MediaItem[]> {
  const page = await listMediaPages(scopeId, nodeId)
  return page.notModified ? [] : page.items // no ifNoneMatch was sent, so a 304 is impossible — the guard is just exhaustiveness
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
 * What a report of this medium hands over (uc.moderation.report, en.moderation.hold § What a report hands over): the
 * `K_blob` of every
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
  nodeEpoch: number,
  overrideMs: number | null,
  opts: MediaMetaWriteOptions = {},
): Promise<MediaMetaWrite> {
  const openedWith = unsealMeta(scopeId, memoryKey, media as MediaItem)?.capturedAtOverride
  return persistMeta(scopeId, media, memoryKey, nodeEpoch, (raw, { retry, state }) => {
    if (retry && finiteNum(raw.capturedAtOverride) !== openedWith) throw new MetaConflictError(state)
    if (overrideMs === null) delete raw.capturedAtOverride
    else raw.capturedAtOverride = overrideMs
    return raw
  }, opts)
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
 * The clamp on a GPX track's own name (ADR 0130 decision 9), in UTF-8 bytes for the reason [PLACE_NAME_MAX_BYTES] is: the
 * listed tier's 512-base64 cap, shared with the capture-time PUT. A track's tier carries no geometry, GPS or place name, so
 * the name may be longer than a place's; that a maximal name and the longest activity still seal inside the cap on a tier
 * carrying every field a track's tier holds is asserted (`media.test.ts`), not reasoned about. A photo's full tier leaves
 * no room for a label, which is why only a track is offered one.
 */
export const TRACK_NAME_MAX_BYTES = 120

/** The listed tier's fields for a track's label: a blank name or no activity is the field left out. */
function trackLabelFields(name: string | null, activity: string | null): Pick<MediaMeta, 'trackName' | 'trackActivity'> {
  const n = cleanStr(name)
  const a = cleanStr(activity)
  return { trackName: n === undefined ? undefined : clampUtf8(n, TRACK_NAME_MAX_BYTES), trackActivity: a }
}

/**
 * Set a GPX track's own **name** and **activity** (uc.memory.tracks A8, ADR 0130 decision 9) - null for either drops it, so
 * the track is named by its file again, or drawn without an activity. The same read-modify-write as the capture-time
 * correction ([setMediaMeta]): the whole listed tier re-sealed under the medium's key with every other field verbatim, PUT
 * at the revision it was listed at; a peer's label written meanwhile throws `MetaConflictError`, any other peer's write
 * merges. Operator-blind: the server sees a re-sealed blob of about the same size.
 */
export async function setTrackLabel(
  scopeId: string,
  media: MediaMetaTarget,
  memoryKey: Uint8Array,
  nodeEpoch: number,
  label: { name: string | null; activity: string | null },
  opts: MediaMetaWriteOptions = {},
): Promise<MediaMetaWrite> {
  const opened = unsealMeta(scopeId, memoryKey, media as MediaItem)
  const next = trackLabelFields(label.name, label.activity)
  return persistMeta(scopeId, media, memoryKey, nodeEpoch, (raw, { retry, state }) => {
    if (retry && (cleanStr(raw.trackName) !== opened?.trackName || cleanStr(raw.trackActivity) !== opened?.trackActivity)) {
      throw new MetaConflictError(state)
    }
    if (next.trackName === undefined) delete raw.trackName
    else raw.trackName = next.trackName
    if (next.trackActivity === undefined) delete raw.trackActivity
    else raw.trackActivity = next.trackActivity
    return raw
  }, opts)
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
  nodeEpoch: number,
  placeName: string,
  placeLevel?: string,
  opts: MediaMetaWriteOptions = {},
): Promise<MediaMetaWrite> {
  return persistMeta(scopeId, media, memoryKey, nodeEpoch, (raw) => {
    if (cleanStr(raw.placeName) !== undefined) return null // the fresh tier already has one — nothing to fill
    raw.placeName = clampPlaceName(placeName)
    if (placeLevel === undefined) delete raw.placeLevel
    else raw.placeLevel = placeLevel
    return raw
  }, opts)
}

/** Whether a metadata tier carries anything worth sealing (else it drops to null on a re-seal). Any field but `v`
 *  counts — a known one (a tier whose only content is a resolved name is still content: sealing `null` would have
 *  the name resolved again on every open) or one a newer client wrote that this one does not know. */
function metaHasContent(m: RawMediaMeta): boolean {
  return Object.entries(m).some(([k, v]) => k !== 'v' && v !== undefined)
}

/**
 * How a MEMBER's media-meta write meets an epoch refusal of the medium's node (ADR 0109 point 6): the device floor before
 * each attempt (`atFloor`), and on `node_rekey_pending` (or the floor's refusal) the node's path pulled forward on this
 * device (`pull`, ADR 0111 decision 11) and its key re-read (`reread`) for a rebuild; `stale_epoch` re-reads once. Without
 * `reread` a refusal is the caller's — a guest's write, which has no key above its root to pull with.
 */
export type MediaMetaWriteOptions = {
  atFloor?: (nodeEpoch: number) => void
  reread?: () => Promise<{ key: Uint8Array; epoch: number }>
  pull?: () => Promise<boolean>
}

/**
 * Re-seal a metadata tier under the media's key and PUT it with the `metaRevision` it was built from
 * (`updateMediaMeta`, ADR 0077) — the one write path behind the capturedAt edit ([setMediaMeta]), the place
 * backfill ([backfillPlaceName]) and a track's label ([setTrackLabel]). `edit` gets the RAW tier (every field verbatim, never [unsealMeta]'s projection) and returns the next
 * one, or null for no write; on a `409 stale_revision` it re-runs on the tier the conflict handed back. A present
 * tier that does not open is refused (`MetaUnreadableError`), never rebuilt from empty (#273). The key is the one
 * the media's origin fixes ([mediaKey]): a member editing what a WRITE_ONLY guest added re-seals under that guest's
 * key, never under `K_node`, so the media never changes path (ADR 0031 point 7). Drops to null when nothing is
 * left worth sealing. `nodeEpoch` is the medium's node's epoch as read with `memoryKey`, which the write states (ADR 0109
 * point 6).
 *
 * A rebuild after the node's re-key ([MediaMetaWriteOptions]) seals under the re-read key at its epoch, and starts from the
 * tier AS LISTED — opened once, under the key it was listed with: the re-key re-sealed that tier without changing a byte of
 * its plaintext and raised its revision, so the rebuild's send meets `409 stale_revision` and re-bases on the re-sealed tier,
 * which opens under the new key. A WRITE_ONLY guest's medium is neither re-sealed nor re-revised by a re-key, and its `K_up`
 * stays the same: it is resolved once, from the origin as listed (whose drop key the re-key replaced).
 */
async function persistMeta(
  scopeId: string,
  media: MediaMetaTarget,
  memoryKey: Uint8Array,
  nodeEpoch: number,
  edit: (raw: RawMediaMeta, ctx: MetaEditContext) => RawMediaMeta | null,
  opts: MediaMetaWriteOptions = {},
): Promise<MediaMetaWrite> {
  const { mediaId } = media
  const base = { wrappedMeta: media.wrappedMeta ?? null, metaRevision: media.metaRevision ?? 0 }
  let guestKey: Uint8Array | undefined
  const keyFor = (nodeKey: Uint8Array) => (media.uploader ? (guestKey ??= mediaKey(scopeId, memoryKey, media)) : nodeKey)
  let listed: RawMediaMeta | undefined
  const openListed = () => (listed ??= openMediaMetaForWrite(scopeId, memoryKey, media, base.wrappedMeta))
  const once = async (nodeKey: Uint8Array, epoch: number) => {
    opts.atFloor?.(epoch)
    return writeSealedMeta<RawMediaMeta>(
      base,
      {
        open: (wrappedMeta) =>
          wrappedMeta === base.wrappedMeta ? structuredClone(openListed()) : openMediaMetaUnder(scopeId, () => keyFor(nodeKey), mediaId, wrappedMeta),
        seal: (raw) => (metaHasContent(raw) ? sealJson(keyFor(nodeKey), prune(raw), mediaMetaContext(scopeId, mediaId)) : null),
        send: async (wrappedMeta, metaRevision) => {
          await withRefresh(() => mediaApi().updateMediaMeta({ scopeId, mediaId, mediaMetaUpdate: { wrappedMeta, metaRevision, nodeEpoch: epoch } }))
        },
      },
      edit,
    )
  }
  const reread = opts.reread
  const { state, meta, wrote } = reread
    ? await writeAtNodeEpoch(async (again) => {
      const at = again ? await reread() : { key: memoryKey, epoch: nodeEpoch }
      return once(at.key, at.epoch)
    }, { pull: opts.pull })
    : await once(memoryKey, nodeEpoch)
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

/** Open the grid tier (thumbnail) of a media — under the key its origin fixes ([mediaKey]). A grid's bulk read
 *  (`core/blob/readScheduler.ts`): behind anything the user opened, dropped while it waits once `signal` aborts. */
export const openGrid = (scopeId: string, memoryKey: Uint8Array, item: MediaItem, signal?: AbortSignal, openFrames?: FrameOpener) =>
  openBlob(scopeId, mediaKey(scopeId, memoryKey, item), gridRef(item), { lane: 'bulk', signal, openFrames })
/** Open the lightbox tier (preview) of a media. */
export const openLightbox = (scopeId: string, memoryKey: Uint8Array, item: MediaItem) =>
  openBlob(scopeId, mediaKey(scopeId, memoryKey, item), lightboxRef(item))
/** Open the pristine original of a media (download). */
export const openOriginal = (scopeId: string, memoryKey: Uint8Array, item: MediaItem) =>
  openBlob(scopeId, mediaKey(scopeId, memoryKey, item), downloadRef(item))

// --- range streaming: the page-side chunk provider (ADR 0047) ---------------
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
