// uc.memory.tracks A8 (ADR 0130 decision 9) — a track's own name and its activity, as a member gave them. Both are fields
// of the track's sealed listed tier (`trackName`, `trackActivity`), written through the one meta write a medium has
// (`setTrackLabel`, the same version-checked PUT as a capture-time correction), so the server learns neither. Pure: what
// the values may be, how a tier's values are read, and how an activity is drawn.
import type { MediaMeta } from '../media'

/** The activities a track may be given - the old application's ten, as sealed tokens (never shown: each has its words). */
export const TRACK_ACTIVITIES = [
  'hiking',
  'walking',
  'running',
  'cycling',
  'mountain-biking',
  'road-cycling',
  'gravel-cycling',
  'skiing',
  'water',
  'other',
] as const
export type TrackActivity = (typeof TRACK_ACTIVITIES)[number]

const ACTIVITIES: ReadonlySet<string> = new Set(TRACK_ACTIVITIES)

/** A sealed activity this client knows, else null: none was given, or a newer client's value (left in the tier as it is). */
export function trackActivityOf(value: unknown): TrackActivity | null {
  return typeof value === 'string' && ACTIVITIES.has(value) ? (value as TrackActivity) : null
}

/** What a member gave a track: its own name (null: named by its file) and its activity (null: none). */
export type TrackLabel = { name: string | null; activity: TrackActivity | null }

/** The label a track's opened listed tier carries. */
export function trackLabelOf(meta: Pick<MediaMeta, 'trackName' | 'trackActivity'> | null | undefined): TrackLabel {
  const name = typeof meta?.trackName === 'string' && meta.trackName.trim() ? meta.trackName.trim() : null
  return { name, activity: trackActivityOf(meta?.trackActivity) }
}

/**
 * The dash a line is drawn with for its activity, as Leaflet's `dashArray` and SVG's `stroke-dasharray` take it - the old
 * application's patterns: on foot (and none, and other) solid, running dotted, cycling dashed, mountain biking dash-dot,
 * skiing a long dash-dot, water a fine dot.
 */
export function activityDash(activity: TrackActivity | null): string | undefined {
  switch (activity) {
    case 'running':
      return '2 8'
    case 'cycling':
    case 'road-cycling':
    case 'gravel-cycling':
      return '10 7'
    case 'mountain-biking':
      return '10 5 2 5'
    case 'skiing':
      return '14 6 3 6'
    case 'water':
      return '3 9'
    default:
      return undefined
  }
}
