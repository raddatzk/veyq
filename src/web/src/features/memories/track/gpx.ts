// uc.memory.tracks (ADR 0130) — a GPX track, read on the device only. A track is a medium of its Memory whose original is
// the GPX file as it was picked (decision 1): the server stores a sealed blob of a size, as for any medium, and never
// learns that it is a track. Everything here is pure: what counts as a track, how its file is read, what is drawn and the
// numbers shown beside it. No key, no request.
import type { OpenedBlobMeta } from '../../../core/blob/meta'
import { parseInertXml } from '../../../shared/security/trustedTypes'

/** The type a track's original is sealed under (its `blob-meta`), set by the track's own upload. */
export const GPX_MEDIA_TYPE = 'application/gpx+xml'

/** The largest GPX file a track is made from (ADR 0130 decision 2): a long day's recording at one point a second is a
 *  few MB; what is larger is refused on the device before anything is uploaded. */
export const TRACK_MAX_BYTES = 25 * 1024 * 1024

/** How many tracks one Memory or chapter holds at most (ADR 0130 decision 2). Held by the client that adds one: the server
 *  cannot tell a track from a photo, so it counts neither. */
export const TRACKS_PER_MEMORY = 50

/** How many points of one segment are drawn at most; the numbers beside a track are computed over every point. */
export const DRAWN_POINTS_MAX = 2000

/** One point of a track: degrees, metres above sea level and epoch milliseconds where the file has them. */
export type TrackPoint = { lat: number; lng: number; ele?: number; time?: number }

/** A track as its file holds it: its own name (none where the file names none) and its segments, each a line of its own. */
export type ParsedTrack = { name: string | null; segments: TrackPoint[][] }

/** The numbers shown beside a track. A field the file cannot give (no times, no elevations) is absent. */
export type TrackStats = {
  distanceMeters: number
  durationMs?: number
  ascentMeters?: number
  descentMeters?: number
  startedAt?: number
}

/** Why a picked file is no track — each the key of its own sentence (`memory.tracks.problem.*`). */
export type TrackProblem = 'tooLarge' | 'invalid' | 'tooShort' | 'tooMany'

/** A file that does not read as a track, with the reason. */
export class GpxError extends Error {
  constructor(readonly problem: Exclude<TrackProblem, 'tooLarge' | 'tooMany'>) {
    super(`gpx_${problem}`)
  }
}

const XML_TYPES = new Set(['application/xml', 'text/xml', 'application/octet-stream'])

/**
 * Whether a medium is a track: its original's sealed type is the GPX type — or, for a file whose type was declared as
 * generic XML or bytes, its sealed name ends in `.gpx`. A medium whose original carries no meta is no track.
 *
 * Every reader of a Memory's media that shows photos asks this: the gallery's list does once for its views
 * (`useMediaGalleryLive`), and a reader that lists media without it asks itself - the space's search (`search.ts`) lists
 * every medium of a space and made a track a photo document until it did. A new such reader is the next place to ask.
 */
export function isTrackMeta(meta: Pick<OpenedBlobMeta, 'type' | 'name'> | { readonly type?: unknown; readonly name?: unknown } | null | undefined): boolean {
  if (!meta) return false
  const type = typeof meta.type === 'string' ? meta.type.trim().toLowerCase() : ''
  if (type === GPX_MEDIA_TYPE) return true
  const name = typeof meta.name === 'string' ? meta.name.trim().toLowerCase() : ''
  return name.endsWith('.gpx') && (type === '' || XML_TYPES.has(type))
}

/** Whether a picked file is offered as a GPX file: by its type, else by its extension (a browser often types it empty). */
export function isGpxFile(file: Pick<File, 'name' | 'type'>): boolean {
  return file.type === GPX_MEDIA_TYPE || file.name.toLowerCase().endsWith('.gpx')
}

/** The direct children of [parent] named [local], in any namespace (GPX 1.0 and 1.1 differ only in theirs). */
function childrenNamed(parent: Element, local: string): Element[] {
  return Array.from(parent.children).filter((c) => c.localName === local)
}

function textOf(parent: Element, local: string): string | undefined {
  const t = childrenNamed(parent, local)[0]?.textContent?.trim()
  return t ? t : undefined
}

/** One `trkpt`/`rtept`, or null for a point whose coordinates are missing or out of range (it is skipped). */
function pointOf(el: Element): TrackPoint | null {
  const lat = Number(el.getAttribute('lat'))
  const lng = Number(el.getAttribute('lon'))
  if (!el.hasAttribute('lat') || !el.hasAttribute('lon') || !Number.isFinite(lat) || !Number.isFinite(lng)) return null
  if (lat < -90 || lat > 90 || lng < -180 || lng > 180) return null
  const point: TrackPoint = { lat, lng }
  const ele = Number(textOf(el, 'ele'))
  // An elevation outside what the earth has is a broken file's, not a place's: it is left out, the point kept.
  if (textOf(el, 'ele') !== undefined && Number.isFinite(ele) && Math.abs(ele) <= 100_000) point.ele = ele
  const time = Date.parse(textOf(el, 'time') ?? '')
  if (Number.isFinite(time)) point.time = time
  return point
}

/**
 * Read a GPX document (ADR 0130 decision 2). Its tracks' segments (`trk`/`trkseg`/`trkpt`) are the lines; a file with
 * none is read as its routes (`rte`/`rtept`), one line each; waypoints are not drawn. The name is the first track's
 * `name`, else the file's `metadata/name`, else the first route's — null where none is given. Throws [GpxError]:
 * `invalid` for a document that is no GPX (not XML, another root, a document type declaration — refused before parsing,
 * so no entity is ever expanded) and `tooShort` for one with fewer than two usable points.
 */
export function parseGpx(text: string): ParsedTrack {
  if (/<!DOCTYPE|<!ENTITY/i.test(text)) throw new GpxError('invalid')
  let doc: Document
  try {
    // Through the policy's inert-XML door: the built CSP enforces Trusted Types, under which a DOMParser refuses a plain
    // string (ADR 0117) - a bare parse here blocked every GPX upload in the shipped build while jsdom stayed green.
    doc = parseInertXml(text, 'application/xml')
  } catch {
    throw new GpxError('invalid')
  }
  const root = doc.documentElement
  if (!root || root.localName !== 'gpx' || doc.getElementsByTagName('parsererror').length > 0) throw new GpxError('invalid')

  const lines = (groups: Element[], point: string): TrackPoint[][] =>
    groups.map((g) => childrenNamed(g, point).map(pointOf).filter((p): p is TrackPoint => p !== null)).filter((s) => s.length > 0)

  const tracks = childrenNamed(root, 'trk')
  const routes = childrenNamed(root, 'rte')
  let segments = lines(tracks.flatMap((trk) => childrenNamed(trk, 'trkseg')), 'trkpt')
  if (segments.length === 0) segments = lines(routes, 'rtept')
  if (segments.reduce((n, s) => n + s.length, 0) < 2) throw new GpxError('tooShort')

  const metadata = childrenNamed(root, 'metadata')[0]
  const name = (tracks[0] && textOf(tracks[0], 'name')) ?? (metadata && textOf(metadata, 'name')) ?? (routes[0] && textOf(routes[0], 'name')) ?? null
  return { name: name ?? null, segments }
}

/** The name a track is listed under: its file's own, else the file name it was picked under without `.gpx`, else none. */
export function trackName(parsed: Pick<ParsedTrack, 'name'> | null, fileName: string | undefined): string | null {
  const own = parsed?.name?.trim()
  if (own) return own
  const file = fileName?.trim().replace(/\.gpx$/i, '').trim()
  return file || null
}

const EARTH_RADIUS_M = 6_371_008.8

/** The great-circle distance between two points, in metres (haversine). */
export function distanceMeters(a: TrackPoint, b: TrackPoint): number {
  const rad = Math.PI / 180
  const dLat = (b.lat - a.lat) * rad
  const dLng = (b.lng - a.lng) * rad
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * rad) * Math.cos(b.lat * rad) * Math.sin(dLng / 2) ** 2
  return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(h)))
}

/** How much an elevation must change before it counts as a climb or a descent: a GPS elevation wobbles by a few metres
 *  from point to point, and summing every wobble reads a flat walk as a mountain. */
const ELEVATION_STEP_M = 3

/**
 * The numbers shown beside a track (ADR 0130 decision 4), over every point: the distance along each segment (the gaps
 * between segments are not walked), the time from its first to its last timestamp, and the climb and the descent counted
 * once the elevation moved by [ELEVATION_STEP_M] from the last counted level.
 */
export function trackStats(segments: readonly TrackPoint[][]): TrackStats {
  let distance = 0
  let first: number | undefined
  let last: number | undefined
  let ascent = 0
  let descent = 0
  let anyEle = false
  for (const segment of segments) {
    let level: number | undefined
    for (let i = 0; i < segment.length; i++) {
      const p = segment[i]
      if (i > 0) distance += distanceMeters(segment[i - 1], p)
      if (p.time !== undefined) {
        first = first === undefined ? p.time : Math.min(first, p.time)
        last = last === undefined ? p.time : Math.max(last, p.time)
      }
      if (p.ele === undefined) continue
      anyEle = true
      if (level === undefined) level = p.ele
      else if (p.ele - level >= ELEVATION_STEP_M) {
        ascent += p.ele - level
        level = p.ele
      } else if (level - p.ele >= ELEVATION_STEP_M) {
        descent += level - p.ele
        level = p.ele
      }
    }
  }
  const stats: TrackStats = { distanceMeters: distance }
  if (first !== undefined && last !== undefined) {
    stats.startedAt = first
    if (last > first) stats.durationMs = last - first
  }
  if (anyEle) {
    stats.ascentMeters = ascent
    stats.descentMeters = descent
  }
  return stats
}

/** A segment as it is drawn: at most [max] points, evenly picked by index, its first and last always kept. */
export function simplifyLine<T>(points: readonly T[], max = DRAWN_POINTS_MAX): T[] {
  if (points.length <= max) return points.slice()
  const out: T[] = []
  const step = (points.length - 1) / (max - 1)
  for (let i = 0; i < max - 1; i++) out.push(points[Math.round(i * step)])
  out.push(points[points.length - 1])
  return out
}

/** The colours tracks are drawn in, by their place in the list; a Memory with more tracks starts again. */
export const TRACK_COLORS = ['#7c6cf0', '#0e8fd0', '#0f9d6e', '#d88a06', '#dc3b3b', '#d6457f'] as const

/** The colour of the track at [index] in the list. */
export function trackColor(index: number): string {
  return TRACK_COLORS[((index % TRACK_COLORS.length) + TRACK_COLORS.length) % TRACK_COLORS.length]
}

/**
 * Check a picked file before it is uploaded as a track (ADR 0130 decision 2): its size, that it reads as a GPX track,
 * and that the Memory has room for it. Answers the problem, or the parsed track. Reads the file's text once.
 */
export async function checkGpxFile(
  file: Pick<File, 'size' | 'text'>,
  tracksHeld: number,
): Promise<{ problem: TrackProblem } | { track: ParsedTrack }> {
  if (tracksHeld >= TRACKS_PER_MEMORY) return { problem: 'tooMany' }
  if (file.size > TRACK_MAX_BYTES) return { problem: 'tooLarge' }
  try {
    return { track: parseGpx(await file.text()) }
  } catch (e) {
    return { problem: e instanceof GpxError ? e.problem : 'invalid' }
  }
}
