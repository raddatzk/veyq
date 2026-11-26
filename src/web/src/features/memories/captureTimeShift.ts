// uc.memory.buckets § Correct a selection's capture time (web) — the shift's arithmetic, pure: no DOM, no write. A camera
// whose clock was wrong is wrong by the same offset on every photo it took, so a selection is corrected through ONE of its
// media (the anchor, the medium the member edits) and every other selected medium follows by that medium's offset. The
// writes are the lightbox's own per-medium meta write, one each (`useCaptureTimeShift.ts`); this module only says what
// each medium's override becomes.
import { effectiveCapturedAt, type MediaMeta } from './media'

const MINUTE_MS = 60_000

/** One selected medium as the shift reads it: its id and the listed tier it was opened with (null: none sealed). */
export interface ShiftInput {
  mediaId: string
  meta: MediaMeta | null
}

/** What a shift does to one medium: a new override, the override dropped (the shifted time is its own original again, as
 *  a reset leaves it), or nothing — it has no capture time to shift. */
export type ShiftStep =
  | { mediaId: string; action: 'set'; overrideMs: number }
  | { mediaId: string; action: 'clear' }
  | { mediaId: string; action: 'skip-undated' }

export interface ShiftPlan {
  /** The anchor's offset in ms: the new time minus its effective time as the editor showed it (to the minute). */
  offsetMs: number
  /** One step per selected medium, in the order they were handed in (the anchor among them). */
  steps: ShiftStep[]
  /** How many selected media have no capture time and stay as they are. */
  undated: number
}

/**
 * The shift of a selection through its anchor, or null where there is none to offer: the anchor has no capture time (no
 * offset exists), the edit cleared the field, or it moved nothing.
 *
 * The offset is taken against the anchor's effective time **as the editor showed it**, to the minute (the editor is
 * minute-granular): the anchor read 10:03:27 and showed 10:03; set to 11:15, every medium moves by exactly +1 h 12 min, the
 * anchor to 11:15:27 — the difference the member saw, and seconds kept, so the order among the selected stays as it was.
 */
export function planCaptureTimeShift(anchor: { before: MediaMeta | null; afterMs: number | null }, selection: readonly ShiftInput[]): ShiftPlan | null {
  const before = effectiveCapturedAt(anchor.before)
  if (before === undefined || anchor.afterMs === null || !Number.isFinite(anchor.afterMs)) return null
  const offsetMs = anchor.afterMs - Math.floor(before / MINUTE_MS) * MINUTE_MS
  if (offsetMs === 0) return null
  let undated = 0
  const steps = selection.map((m): ShiftStep => {
    const effective = effectiveCapturedAt(m.meta)
    if (effective === undefined) {
      undated += 1
      return { mediaId: m.mediaId, action: 'skip-undated' }
    }
    const shifted = effective + offsetMs
    // Back on its own original: no correction left, so the override goes (the panel's "Corrected" stays truthful).
    return m.meta?.capturedAt === shifted ? { mediaId: m.mediaId, action: 'clear' } : { mediaId: m.mediaId, action: 'set', overrideMs: shifted }
  })
  return { offsetMs, steps, undated }
}

/** An offset as the save writes it out: its sign and its whole days, hours and minutes (a shift is minute-granular). */
export function offsetParts(offsetMs: number): { sign: '+' | '−'; days: number; hours: number; minutes: number } {
  const total = Math.round(Math.abs(offsetMs) / MINUTE_MS)
  return {
    sign: offsetMs < 0 ? '−' : '+',
    days: Math.floor(total / (24 * 60)),
    hours: Math.floor((total % (24 * 60)) / 60),
    minutes: total % 60,
  }
}
