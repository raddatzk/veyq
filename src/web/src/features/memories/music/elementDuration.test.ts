// @vitest-environment happy-dom
// ADR 0135 decisions 3-4: a track's length is what this browser's `<audio>` element reads from the local file's metadata;
// a file it cannot read within 10 s is unplayable here. The element and its object URL are let go either way.
import { describe, it, expect, vi, afterEach } from 'vitest'
import { elementDurationMs, AudioUnplayable, METADATA_TIMEOUT_MS } from './audioFacts'

type FakeAudio = { preload: string; src: string; duration: number; error: unknown; onloadedmetadata: (() => void) | null; onerror: (() => void) | null; removeAttribute: ReturnType<typeof vi.fn>; load: ReturnType<typeof vi.fn> }

function fakeElement(): FakeAudio {
  const el: FakeAudio = { preload: '', src: '', duration: NaN, error: null, onloadedmetadata: null, onerror: null, removeAttribute: vi.fn(), load: vi.fn() }
  vi.spyOn(document, 'createElement').mockReturnValue(el as unknown as HTMLElement)
  return el
}

afterEach(() => {
  vi.restoreAllMocks()
  vi.useRealTimers()
})

describe('elementDurationMs', () => {
  it('reads the duration from the metadata only, in whole milliseconds, and lets the element and the URL go', async () => {
    const revoke = vi.spyOn(URL, 'revokeObjectURL')
    const el = fakeElement()
    const read = elementDurationMs(new Blob(['x']))
    expect(el.preload).toBe('metadata')
    expect(el.src).toMatch(/^blob:/)
    el.duration = 183.2046
    el.onloadedmetadata!()
    await expect(read).resolves.toBe(183_205)
    expect(el.removeAttribute).toHaveBeenCalledWith('src')
    expect(el.load).toHaveBeenCalled()
    expect(revoke).toHaveBeenCalledTimes(1)
  })

  it('an error, a duration that is not finite, or no answer within 10 s is unplayable', async () => {
    const failed = fakeElement()
    const a = elementDurationMs(new Blob(['x']))
    failed.onerror!()
    await expect(a).rejects.toBeInstanceOf(AudioUnplayable)

    const endless = fakeElement()
    const b = elementDurationMs(new Blob(['x']))
    endless.duration = Infinity
    endless.onloadedmetadata!()
    await expect(b).rejects.toBeInstanceOf(AudioUnplayable)

    vi.useFakeTimers()
    fakeElement()
    const c = elementDurationMs(new Blob(['x']))
    vi.advanceTimersByTime(METADATA_TIMEOUT_MS)
    await expect(c).rejects.toBeInstanceOf(AudioUnplayable)
  })
})
