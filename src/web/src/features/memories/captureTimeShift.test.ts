// uc.memory.buckets § Correct a selection's capture time — the shift's arithmetic, without a DOM: the offset both ways, a
// shift that lands on a medium's own original drops its override, an undated medium is skipped and counted, an undated
// anchor (or an edit that cleared or moved nothing) offers no shift, and nothing outside the selection is planned.
import { describe, it, expect } from 'vitest'
import { offsetParts, planCaptureTimeShift, type ShiftInput } from './captureTimeShift'
import type { MediaMeta } from './media'

const at = (iso: string) => Date.parse(iso)
const meta = (capturedAt?: string, override?: string): MediaMeta => ({
  v: 1,
  ...(capturedAt ? { capturedAt: at(capturedAt) } : {}),
  ...(override ? { capturedAtOverride: at(override) } : {}),
})

describe('planCaptureTimeShift', () => {
  const a: ShiftInput = { mediaId: 'a', meta: meta('2024-06-01T10:00:00Z') }
  const b: ShiftInput = { mediaId: 'b', meta: meta('2024-06-01T10:20:00Z') }
  const c: ShiftInput = { mediaId: 'c', meta: meta('2024-06-01T11:00:00Z', '2024-06-01T12:00:00Z') } // already corrected

  it('moves every dated medium by the anchor\'s offset, forward', () => {
    const plan = planCaptureTimeShift({ before: b.meta, afterMs: at('2024-06-01T11:32:00Z') }, [a, b, c])
    expect(plan?.offsetMs).toBe((72 * 60) * 1000) // +1 h 12 min
    expect(plan?.steps).toEqual([
      { mediaId: 'a', action: 'set', overrideMs: at('2024-06-01T11:12:00Z') },
      { mediaId: 'b', action: 'set', overrideMs: at('2024-06-01T11:32:00Z') },
      // from its EFFECTIVE time (the override), not its original
      { mediaId: 'c', action: 'set', overrideMs: at('2024-06-01T13:12:00Z') },
    ])
    expect(plan?.undated).toBe(0)
  })

  it('and backward: a negative offset, the order among them kept', () => {
    const plan = planCaptureTimeShift({ before: a.meta, afterMs: at('2024-05-31T22:00:00Z') }, [a, b, c])!
    expect(plan.offsetMs).toBe(-12 * 3600_000)
    const times = plan.steps.map((s) => (s.action === 'set' ? s.overrideMs : NaN))
    expect(times).toEqual([at('2024-05-31T22:00:00Z'), at('2024-05-31T22:20:00Z'), at('2024-06-01T00:00:00Z')])
    expect([...times].sort((x, y) => x - y), 'the selection keeps its order').toEqual(times)
  })

  it('a medium whose shifted time is its own original drops the override, as a reset does', () => {
    // c reads 12:00 (corrected from 11:00); shifting everything by -1 h lands c back on its original
    const plan = planCaptureTimeShift({ before: c.meta, afterMs: at('2024-06-01T11:00:00Z') }, [a, c])!
    expect(plan.steps).toEqual([
      { mediaId: 'a', action: 'set', overrideMs: at('2024-06-01T09:00:00Z') },
      { mediaId: 'c', action: 'clear' },
    ])
  })

  it('an undated medium is skipped and counted, never given a time', () => {
    const none: ShiftInput = { mediaId: 'n', meta: null }
    const empty: ShiftInput = { mediaId: 'e', meta: { v: 1, w: 800, h: 600 } } // a tier, but no time in it
    const plan = planCaptureTimeShift({ before: a.meta, afterMs: at('2024-06-01T11:00:00Z') }, [a, none, empty])!
    expect(plan.steps).toEqual([
      { mediaId: 'a', action: 'set', overrideMs: at('2024-06-01T11:00:00Z') },
      { mediaId: 'n', action: 'skip-undated' },
      { mediaId: 'e', action: 'skip-undated' },
    ])
    expect(plan.undated).toBe(2)
  })

  it('no shift is offered for an undated anchor, a cleared field or an edit that moved nothing', () => {
    expect(planCaptureTimeShift({ before: null, afterMs: at('2024-06-01T11:00:00Z') }, [a, b]), 'an undated anchor').toBeNull()
    expect(planCaptureTimeShift({ before: a.meta, afterMs: null }, [a, b]), 'the field cleared').toBeNull()
    expect(planCaptureTimeShift({ before: a.meta, afterMs: at('2024-06-01T10:00:00Z') }, [a, b]), 'nothing moved').toBeNull()
    expect(planCaptureTimeShift({ before: a.meta, afterMs: at('2024-06-01T10:01:00Z') }, [a, b]), 'control: one minute is a shift').not.toBeNull()
  })

  it('the offset is the difference the editor showed: the anchor\'s seconds are not part of it, and stay its own', () => {
    // the anchor read 10:03:27; the minute-granular editor showed 10:03, the member set 11:15
    const anchor: ShiftInput = { mediaId: 'x', meta: meta('2024-06-01T10:03:27Z') }
    const plan = planCaptureTimeShift({ before: anchor.meta, afterMs: at('2024-06-01T11:15:00Z') }, [anchor, a])!
    expect(plan.offsetMs).toBe(72 * 60_000)
    expect(plan.steps).toEqual([
      { mediaId: 'x', action: 'set', overrideMs: at('2024-06-01T11:15:27Z') },
      { mediaId: 'a', action: 'set', overrideMs: at('2024-06-01T11:12:00Z') },
    ])
  })

  it('plans only the selection handed in', () => {
    const plan = planCaptureTimeShift({ before: a.meta, afterMs: at('2024-06-01T11:00:00Z') }, [a, c])!
    expect(plan.steps.map((s) => s.mediaId)).toEqual(['a', 'c']) // b, not selected, is not in the plan
  })
})

describe('offsetParts', () => {
  it('writes an offset out as its sign, days, hours and minutes', () => {
    expect(offsetParts(72 * 60_000)).toEqual({ sign: '+', days: 0, hours: 1, minutes: 12 })
    expect(offsetParts(-(26 * 60 + 5) * 60_000)).toEqual({ sign: '−', days: 1, hours: 2, minutes: 5 })
    expect(offsetParts(365 * 24 * 3600_000)).toEqual({ sign: '+', days: 365, hours: 0, minutes: 0 })
  })
})
