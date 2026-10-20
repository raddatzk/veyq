// en.memory.derivatives § The three tiers — the ≤2× progressive-halving rule, pinned on the three geometries
// the tiers actually see. `halvingPlan` is the one implementation both the page port and the Worker port
// execute, so this pins the rule itself rather than a copy of it.
import { describe, expect, it } from 'vitest'
import { halvingPlan } from './mediaPixels'
import { TIER_SPECS } from './mediaDeriver'

describe('halvingPlan — the progressive-halving rule both canvas ports execute', () => {
  it('preview of a 3024×4032 original: within 2×, so no intermediate step', () => {
    expect(halvingPlan(3024, 4032, TIER_SPECS.PREVIEW.maxEdge)).toEqual({ steps: [], final: { w: 1536, h: 2048 } })
  })

  it('thumbnail from that preview: one halving step, then the final draw', () => {
    expect(halvingPlan(1536, 2048, TIER_SPECS.THUMBNAIL.maxEdge)).toEqual({
      steps: [{ w: 768, h: 1024 }],
      final: { w: 384, h: 512 },
    })
  })

  it('a direct 4032×3024 → 512: two halving steps, then the final draw', () => {
    expect(halvingPlan(4032, 3024, 512)).toEqual({
      steps: [{ w: 2016, h: 1512 }, { w: 1008, h: 756 }],
      final: { w: 512, h: 384 },
    })
  })
})
