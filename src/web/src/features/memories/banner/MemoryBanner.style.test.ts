// uc.memory.banner - the banner's frame, pinned at the source since a unit test renders no CSS (ADR 0102, amended
// 2026-11-01): 16:9 where the Memory's column is wide, 1:1 where it is narrow, at most 60 % of the viewport, and the medium
// always contained - only its blurred copy behind it covers the frame.
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'

describe('the banner frame', () => {
  it('frames 16:9 on a wide column and 1:1 on a narrow one, at most 60 % of the viewport, the medium contained', () => {
    const sfc = readFileSync(new URL('./MemoryBanner.vue', import.meta.url), 'utf8')
    const style = sfc.slice(sfc.indexOf('<style'))
    expect(style).toMatch(/\.memory-banner \{[^}]*aspect-ratio:\s*16 \/ 9;[^}]*max-height:\s*60vh;/)
    expect(style).toMatch(/@container memory-detail-surface \(max-width: 720px\) \{\s*\.memory-banner \{\s*aspect-ratio:\s*1 \/ 1;/)
    expect(style).toMatch(/\.memory-banner-media \{[^}]*object-fit:\s*contain;/)
    const frame = readFileSync(new URL('./BannerFrame.vue', import.meta.url), 'utf8')
    expect(frame).toMatch(/\.banner-frame-media \{[^}]*object-fit:\s*contain;/)
  })

})
