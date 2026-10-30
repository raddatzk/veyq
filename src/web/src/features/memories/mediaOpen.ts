// A media's listed tier, opened (cr-0068; inbox #489): the seal's binding, the open and the display projection — pure,
// so the page (`media.ts` `unsealMeta`, the memo's fallback in `openedListing.ts`) and the listing opener Worker
// (`listingOpener.worker.ts`) run the same code and cannot drift into two answers for one seal. Nothing here touches the
// network, the API client or page state; it imports the crypto suite and nothing of the app.
import * as v1 from '@veyq/crypto/v1'
import { fromB64std } from '@veyq/crypto/base64'
import type { CaptureSource, MediaMeta } from './media'

/**
 * The sealed **listed-metadata** tier of one media (cr-0068), bound to (scope, media). This one blob carries the
 * geometry (`w,h`), the capture time (`capturedAt*`), and the GPS (`gpsLat,gpsLng`) — everything the grid, the
 * date sort, and the place medoid need at list-level. Consolidates the former `media-layout` + `media-capturedat`.
 */
export const mediaMetaContext = (scopeId: string, mediaId: string) => ({ purpose: 'media-meta', scopeId, mediaId }) as const

/**
 * A listed tier exactly as it was sealed — every field, known or not. Writers start from THIS, never from
 * [openMediaMeta]'s normalized copy: that copy is a display projection, and re-sealing a projection drops whatever it
 * does not carry. It once left out the resolved place name, so every date correction erased it (ADR 0077 point 4).
 */
export type RawMediaMeta = { v: number } & Record<string, unknown>

export function finiteNum(n: unknown): number | undefined {
  return typeof n === 'number' && Number.isFinite(n) ? n : undefined
}

export function cleanStr(s: unknown): string | undefined {
  const t = typeof s === 'string' ? s.trim() : ''
  return t.length ? t : undefined
}

/** Every `capturedAtSource` this client knows; any other value (a newer client's) reads as no source, so untrustworthy. */
const CAPTURE_SOURCES: ReadonlySet<CaptureSource> = new Set(['exif', 'container', 'file'])

/** The display projection of a raw tier: numbers coerced, geometry validated, unknown fields left out. */
export function normalizeMediaMeta(m: RawMediaMeta): MediaMeta {
  const w = finiteNum(m.w)
  const h = finiteNum(m.h)
  const geo = w !== undefined && h !== undefined && w > 0 && h > 0
  const source = CAPTURE_SOURCES.has(m.capturedAtSource as CaptureSource) ? (m.capturedAtSource as CaptureSource) : undefined
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

/**
 * Open a media's listed tier under `key` — the key its seals are under (`mediaKey`: `K_node`, or a WRITE_ONLY guest's
 * `K_up`) — and project it for display. Best-effort: null when none was sealed or the wrap doesn't open or parse.
 */
export function openMediaMeta(scopeId: string, key: Uint8Array, mediaId: string, wrappedMeta: string | undefined): MediaMeta | null {
  if (!wrappedMeta) return null
  try {
    const json = new TextDecoder().decode(v1.openField(mediaMetaContext(scopeId, mediaId), key, fromB64std(wrappedMeta)))
    const parsed = JSON.parse(json) as unknown
    if (parsed && typeof parsed === 'object') return normalizeMediaMeta(parsed as RawMediaMeta)
  } catch {
    /* not openable / malformed → treated as no metadata */
  }
  return null
}
