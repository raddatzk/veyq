// A Memory's BANNER as the web models it (uc.memory.banner, ADR 0102): one image or one video above the menu bar,
// independent of the cover. Its bytes are the node's own attachments - the `BANNER` slot (the image, or the video) and,
// for a video, the `BANNER_POSTER` slot (its first frame) - and the `banner` field of the node's sealed `wrappedMeta`
// (core's `nodeMeta.ts`, purpose `node-meta`, bound to scope and node, ADR 0072) names them with how to frame them:
//
//   banner = { medium: 'image' | 'video', blob: Ref, poster?: Ref, type?: string, origin?: { mediaId },
//              frame: 'fill' | 'fit', focal: { x, y }, alt?: string }   Ref = { blobId, chunkCount, byteSize, wrappedBlobKey }
//
// A ref carries neither owner nor role: both are the node's and the slot's, taken from where the meta is read, never from
// the sealed payload (ADR 0072), so a ref copied into another node's meta refuses to open there. Nor does it carry the
// served `wrappedBlobMeta`: an image and a poster are always `image/jpeg`, and a video's own type is the one `type` field
// (the copied original's container, which the service worker hands the `<video>`); the meta's size is bounded (8192
// base64 characters, shared with the cover, the place and 100 participants - `bannerModel.test.ts` measures the worst
// case).
//
// **The later slot.** A video banner today is the copy of the original (ADR 0102, amended: no transcoder in the browser).
// The small playback derivative comes with the client worker queue (inbox #23): a worker will fill a third slot,
// `BANNER_PLAYBACK`, and add a `playback` ref here beside `blob` - which keeps meaning the copied original. A reader that
// knows `playback` plays it; one that does not plays `blob`; neither moves.
//
// Pure and side-effect free, so it reads in tests without a DOM: the network is `bannerWrite.ts`, the decrypt
// `bannerStore.ts`. A reader never throws: a malformed focal point is the centre, an unknown frame is `fill`, and an
// unknown shape is no banner - preserved untouched by every other meta writer (`editField` keeps unknown fields).
import type { BlobRef } from '../../../core/blob/types'

/** The banner's image or video slot - also the role its upload is started in. */
export const BANNER_ROLE = 'BANNER'
/** A video banner's poster slot - also the role its upload is started in. */
export const BANNER_POSTER_ROLE = 'BANNER_POSTER'
/** The description's bound (characters, as the export schema's `alt.maxLength`). */
export const MAX_BANNER_ALT = 200
/** A video banner's type when none (or a malformed one) is stored. */
export const DEFAULT_VIDEO_TYPE = 'video/mp4'
const VIDEO_TYPE = /^video\/[A-Za-z0-9.+-]{1,60}$/
const videoType = (v: unknown): string => (typeof v === 'string' && VIDEO_TYPE.test(v) ? v : DEFAULT_VIDEO_TYPE)
/** A description trimmed and cut to [MAX_BANNER_ALT] characters - code points, so no character is split in half. */
export const boundAlt = (v: string | undefined): string => (v ? Array.from(v.trim()).slice(0, MAX_BANNER_ALT).join('') : '')

export type BannerMedium = 'image' | 'video'
/** `fill`: the medium covers the frame around the focal point; `fit`: the whole medium, its sides a blurred enlargement. */
export type BannerFrame = 'fill' | 'fit'
/** The point of the medium (`0..1` of its width and height) that `fill` keeps in view, in every aspect. */
export type FocalPoint = { x: number; y: number }

/** A stored blob ref: what the meta names an attachment by. Owner and role come from the node and the slot. */
export type BannerRefShape = { blobId: string; chunkCount: number; byteSize: number; wrappedBlobKey: string }

/** A banner as read: its refs ready to open (owner = the node, role = the slot). */
export type Banner = {
  medium: BannerMedium
  blob: BlobRef
  poster?: BlobRef
  /** A video's media type (the copied original's); absent for an image. */
  type?: string
  origin?: { mediaId: string }
  frame: BannerFrame
  focal: FocalPoint
  alt?: string
}

export const CENTRE: FocalPoint = { x: 0.5, y: 0.5 }

const finiteNum = (v: unknown): number | undefined => (typeof v === 'number' && Number.isFinite(v) ? v : undefined)
const unit = (v: unknown): number | undefined => {
  const n = finiteNum(v)
  return n === undefined || n < 0 || n > 1 ? undefined : n
}

/** A focal point kept on the medium; anything malformed is the centre (never a throw). */
export function normalizeFocal(raw: unknown): FocalPoint {
  if (!raw || typeof raw !== 'object') return { ...CENTRE }
  const p = raw as { x?: unknown; y?: unknown }
  const x = unit(p.x)
  const y = unit(p.y)
  return x === undefined || y === undefined ? { ...CENTRE } : { x, y }
}

/** A stored ref, as a ref that opens on `nodeId` in `role` - or null when a required field is off. */
export function normalizeBannerRef(raw: unknown, nodeId: string, role: string): BlobRef | null {
  if (!raw || typeof raw !== 'object') return null
  const r = raw as { blobId?: unknown; chunkCount?: unknown; byteSize?: unknown; wrappedBlobKey?: unknown }
  const chunkCount = finiteNum(r.chunkCount)
  const byteSize = finiteNum(r.byteSize)
  if (typeof r.blobId !== 'string' || !r.blobId) return null
  if (typeof r.wrappedBlobKey !== 'string' || !r.wrappedBlobKey) return null
  if (chunkCount === undefined || byteSize === undefined) return null
  return { blobId: r.blobId, chunkCount, byteSize, wrappedBlobKey: r.wrappedBlobKey, ownerId: nodeId, role }
}

/**
 * Read a `banner` field value (null: none, or a shape this client does not know). The medium and its ref are
 * mandatory; a video's poster is optional (a banner whose poster is gone still plays). `frame` falls back to `fill`,
 * the focal point to the centre, a description longer than its bound is cut to it.
 */
export function normalizeBanner(raw: unknown, nodeId: string): Banner | null {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null
  const b = raw as {
    medium?: unknown; blob?: unknown; poster?: unknown; type?: unknown; origin?: unknown; frame?: unknown; focal?: unknown; alt?: unknown
  }
  if (b.medium !== 'image' && b.medium !== 'video') return null
  const blob = normalizeBannerRef(b.blob, nodeId, BANNER_ROLE)
  if (!blob) return null
  const poster = b.medium === 'video' ? normalizeBannerRef(b.poster, nodeId, BANNER_POSTER_ROLE) : null
  const originId = (b.origin as { mediaId?: unknown } | null | undefined)?.mediaId
  const alt = typeof b.alt === 'string' ? boundAlt(b.alt) : ''
  return {
    medium: b.medium,
    blob,
    ...(poster ? { poster } : {}),
    ...(b.medium === 'video' ? { type: videoType(b.type) } : {}),
    ...(typeof originId === 'string' && originId ? { origin: { mediaId: originId } } : {}),
    frame: b.frame === 'fit' ? 'fit' : 'fill',
    focal: normalizeFocal(b.focal),
    ...(alt ? { alt } : {}),
  }
}

/** The blobs a stored `banner` names - the compare values of `setMemoryBanner` / `removeMemoryBanner`: what the writer
 *  read in the two slots. Read from the raw value, so a banner this client cannot fully read still compares. */
export function bannerBlobIds(raw: unknown): { blobId: string | null; posterBlobId: string | null } {
  const b = raw && typeof raw === 'object' ? (raw as { blob?: { blobId?: unknown }; poster?: { blobId?: unknown } }) : null
  const id = (v: unknown) => (typeof v === 'string' && v ? v : null)
  return { blobId: id(b?.blob?.blobId), posterBlobId: id(b?.poster?.blobId) }
}

/** Only the four fields a stored ref keeps (an upload plan or a read ref carries more). */
export function refShape(ref: BannerRefShape): BannerRefShape {
  const { blobId, chunkCount, byteSize, wrappedBlobKey } = ref
  return { blobId, chunkCount, byteSize, wrappedBlobKey }
}

/** What the editor decided: everything but the bytes' refs. */
export type BannerFraming = { frame: BannerFrame; focal: FocalPoint; alt?: string }

/** The `banner` value to seal, in a fixed key order. `origin` only for a banner made from a gallery medium. */
export function sealedBanner(a: {
  medium: BannerMedium
  blob: BannerRefShape
  poster?: BannerRefShape | null
  /** A video's media type. */
  type?: string | null
  origin?: string | null
  framing: BannerFraming
}): Record<string, unknown> {
  const alt = boundAlt(a.framing.alt)
  return {
    medium: a.medium,
    blob: refShape(a.blob),
    ...(a.medium === 'video' && a.poster ? { poster: refShape(a.poster) } : {}),
    ...(a.medium === 'video' ? { type: videoType(a.type) } : {}),
    ...(a.origin ? { origin: { mediaId: a.origin } } : {}),
    frame: a.framing.frame,
    focal: { x: round(a.framing.focal.x), y: round(a.framing.focal.y) },
    ...(alt ? { alt } : {}),
  }
}

/** A re-frame of a stored banner: the same bytes, a new frame, focal point or description. Unknown fields kept. */
export function reframedBanner(raw: unknown, framing: BannerFraming): Record<string, unknown> | null {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null
  const { alt: _old, ...rest } = raw as Record<string, unknown>
  const alt = boundAlt(framing.alt)
  return { ...rest, frame: framing.frame, focal: { x: round(framing.focal.x), y: round(framing.focal.y) }, ...(alt ? { alt } : {}) }
}

/** Four decimals: a focal point needs no more (1/10,000 of the medium), and the meta's size is bounded. */
const round = (v: number): number => Math.round(Math.min(1, Math.max(0, v)) * 10_000) / 10_000

/** Whether a medium of these dimensions is portrait - the only kind that may switch between fit and fill. */
export const isPortrait = (width: number, height: number): boolean => height > width

/** The frame a medium starts in: a portrait one fits, a landscape or square one fills (ADR 0102 decision 2). */
export const defaultFrame = (width: number, height: number): BannerFrame => (isPortrait(width, height) ? 'fit' : 'fill')

/** The pure display formula: the `object-position` that keeps the focal point in view under `object-fit: cover`. */
export function bannerStyle(focal: FocalPoint): { objectPosition: string } {
  return { objectPosition: `${pct(focal.x)} ${pct(focal.y)}` }
}

const pct = (v: number): string => `${Math.round(v * 10_000) / 100}%`
