// en.memory.derivatives § The three tiers — the ≤2× progressive-halving rule, pinned on the three geometries
// the tiers actually see. `halvingPlan` is the one implementation both the page port and the Worker port
// execute, so this pins the rule itself rather than a copy of it.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { drawEncoded, halvingPlan, type CanvasPort } from './mediaPixels'
import { TIER_SPECS } from './mediaDeriver'

// ADR 0127: the encoder is libwebp's module; here a spy, so the test sees which encoder a request reached. The real
// module runs in webp-encoder/encodeWebp.test.ts.
const encodeWebp = vi.hoisted(() => vi.fn<(rgba: Uint8Array | Uint8ClampedArray, width: number, height: number, quality: number) => Promise<Uint8Array>>())
vi.mock('../../../webp-encoder/encodeWebp', () => ({ encodeWebp }))

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

/** A canvas port whose canvases record their fill and hand out their pixels, and whose `encode` records each call. */
function recordingPort() {
  const encodes: { type: string; quality: number }[] = []
  const reads: { w: number; h: number; fill: string | null }[] = []
  const port: CanvasPort<HTMLCanvasElement> = {
    create(width, height) {
      let fill: string | null = null
      const ctx = {
        set fillStyle(v: string) { fill = v },
        get fillStyle() { return fill ?? '' },
        fillRect: () => {},
        drawImage: () => {},
        getImageData: (_x: number, _y: number, w: number, h: number) => {
          reads.push({ w, h, fill })
          return { data: new Uint8ClampedArray(w * h * 4) }
        },
      }
      return { width, height, getContext: () => ctx } as unknown as HTMLCanvasElement
    },
    async encode(_canvas, type, quality) {
      encodes.push({ type, quality })
      return new Blob([new Uint8Array([7]) as BlobPart], { type })
    },
  }
  return { port, encodes, reads }
}

describe('drawEncoded — a WebP is libwebp\'s, everything else the canvas\'s (ADR 0127)', () => {
  const source = {} as CanvasImageSource
  beforeEach(() => {
    encodeWebp.mockReset()
    encodeWebp.mockResolvedValue(new Uint8Array([5, 5, 5]))
  })

  it("hands the final canvas's pixels to encodeWebp, at its size and the caller's quality, and never calls port.encode", async () => {
    const { port, encodes, reads } = recordingPort()

    const bytes = await drawEncoded(port, source, 1024, 768, 256, 0.82, 'image/webp', '#abcdef')

    expect(bytes).toEqual(new Uint8Array([5, 5, 5]))
    expect(encodes).toEqual([])
    expect(reads).toEqual([{ w: 256, h: 192, fill: '#abcdef' }]) // the FINAL canvas, filled, read once
    expect(encodeWebp).toHaveBeenCalledTimes(1)
    const [rgba, width, height, quality] = encodeWebp.mock.calls[0]
    expect([rgba.length, width, height, quality]).toEqual([256 * 192 * 4, 256, 192, 0.82])
  })

  it('keeps every other type on the canvas: a JPEG tier never reaches libwebp', async () => {
    const { port, encodes, reads } = recordingPort()

    await drawEncoded(port, source, 1024, 768, 512, 0.8)

    expect(encodes).toEqual([{ type: 'image/jpeg', quality: 0.8 }])
    expect(reads).toEqual([])
    expect(encodeWebp).not.toHaveBeenCalled()
  })

  it('rejects when the encoder cannot load or refuses — there is no fallback to another format', async () => {
    const { port, encodes } = recordingPort()
    encodeWebp.mockRejectedValue(new Error('webp_encoder_unavailable: 404'))

    await expect(drawEncoded(port, source, 300, 300, 256, 0.9, 'image/webp')).rejects.toThrow('webp_encoder_unavailable')
    expect(encodes).toEqual([])
  })
})
