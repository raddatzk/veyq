// uc.memory.upload — the image pixel stage runs in a Worker where the browser offers one, and degrades to the page
// thread where it cannot. The real Worker never runs in this env (no `OffscreenCanvas`, no module Worker); these
// drive `createBrowserDeriver().deriveImage` against stub Workers and a stubbed page canvas, and count what each
// path did. The page path's decode (`createImageBitmap`) is the fallback counter: it runs once per fallback and
// never when the Worker answered. Which Worker exists is set per test with `vi.stubGlobal` (argonWorker.test.ts's
// pattern), never assumed from the environment.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

type Opts = { maxEdge: number; quality: number; mimeType?: string; background?: string }
type Listener = (e: unknown) => void

/** The page canvas, stubbed: each `toBlob` records its type, quality and the fill on that canvas. */
function stubPageCanvas() {
  const decodes = vi.fn(async () => ({ width: 3024, height: 4032, close: () => {} }))
  const encodes: { type: string; quality: number; fill: string | null }[] = []
  vi.stubGlobal('createImageBitmap', decodes)
  vi.stubGlobal('document', {
    createElement: () => {
      let fill: string | null = null
      const ctx = {
        set fillStyle(v: string) { fill = v },
        get fillStyle() { return fill ?? '' },
        fillRect: () => {},
        drawImage: () => {},
        imageSmoothingEnabled: false,
        imageSmoothingQuality: 'low',
      }
      return {
        width: 0,
        height: 0,
        getContext: () => ctx,
        toBlob: (cb: (b: Blob) => void, type: string, quality: number) => {
          encodes.push({ type, quality, fill })
          cb(new Blob([new Uint8Array([7, 7, 7]) as BlobPart], { type }))
        },
      }
    },
  })
  return { decodes, encodes }
}

/** A stub Worker class whose reply to each request is `reply(request)`, or that dies (`error`) on its first post. */
function stubWorker(reply: ((msg: { id: number; opts: Opts }) => unknown) | 'die' | 'throw') {
  const stats = { constructed: 0, posted: [] as { id: number; opts: Opts }[] }
  class StubWorker {
    private handlers: Record<string, Listener[]> = {}
    constructor() {
      stats.constructed += 1
    }
    addEventListener(type: string, cb: Listener): void {
      ;(this.handlers[type] ??= []).push(cb)
    }
    postMessage(msg: { id: number; opts: Opts }): void {
      if (reply === 'throw') throw new DOMException('could not clone', 'DataCloneError')
      stats.posted.push(msg)
      queueMicrotask(() => {
        if (reply === 'die') this.handlers['error']?.forEach((cb) => cb({}))
        else this.handlers['message']?.forEach((cb) => cb({ data: reply(msg) }))
      })
    }
    terminate(): void {}
  }
  vi.stubGlobal('Worker', StubWorker as unknown as typeof Worker)
  return stats
}

const ICON: Opts = { maxEdge: 512, quality: 0.7, mimeType: 'image/webp', background: '#123456' }
const blob = () => new Blob([new Uint8Array([1, 2, 3]) as BlobPart], { type: 'image/png' })

async function freshDeriverModule() {
  vi.resetModules() // the Worker and its latch are module-level: each case starts with neither
  return import('./mediaDeriver')
}

beforeEach(() => vi.resetModules())
afterEach(() => {
  vi.unstubAllGlobals()
  vi.resetModules()
})

describe('the fallback carries the whole request (B2)', () => {
  it('with no Worker global, the page path encodes with the caller’s mimeType, quality and background', async () => {
    vi.stubGlobal('Worker', undefined)
    const page = stubPageCanvas()
    const { createBrowserDeriver } = await freshDeriverModule()

    const out = await createBrowserDeriver().deriveImage(blob(), ICON)

    expect(page.decodes).toHaveBeenCalledTimes(1)
    expect(page.encodes).toEqual([{ type: 'image/webp', quality: 0.7, fill: '#123456' }])
    expect(out).toEqual({ bytes: new Uint8Array([7, 7, 7]), source: { w: 3024, h: 4032 } })
  })

  it('a Worker request carries the same whole opts, and its answer is used without touching the page path', async () => {
    const page = stubPageCanvas()
    const worker = stubWorker(({ id }) => ({ id, ok: true, bytes: new Uint8Array([9]), source: { w: 10, h: 20 } }))
    const { createBrowserDeriver } = await freshDeriverModule()

    const out = await createBrowserDeriver().deriveImage(blob(), ICON)

    expect(worker.posted.map((m) => m.opts)).toEqual([ICON])
    expect(out).toEqual({ bytes: new Uint8Array([9]), source: { w: 10, h: 20 } })
    expect(page.decodes).not.toHaveBeenCalled() // positive control for B4's zero: the counter is not stuck at 0
  })
})

describe('an infrastructure failure degrades to the page thread and latches the Worker off (B3, B5)', () => {
  it('a Worker that fires `error` finishes the request on the page path; the next request builds no new Worker (B3)', async () => {
    const page = stubPageCanvas()
    const worker = stubWorker('die')
    const { createBrowserDeriver } = await freshDeriverModule()
    const deriver = createBrowserDeriver()

    await expect(deriver.deriveImage(blob(), ICON)).resolves.toEqual({ bytes: new Uint8Array([7, 7, 7]), source: { w: 3024, h: 4032 } })
    await deriver.deriveImage(blob(), ICON)

    expect(worker.constructed).toBe(1)
    expect(page.decodes).toHaveBeenCalledTimes(2)
  })

  it('a Worker answering `infra` is retried on the page path and latched off (B5)', async () => {
    const page = stubPageCanvas()
    const worker = stubWorker(({ id }) => ({ id, ok: false, kind: 'infra', error: 'offscreen_canvas_unavailable' }))
    const { createBrowserDeriver } = await freshDeriverModule()
    const deriver = createBrowserDeriver()

    await expect(deriver.deriveImage(blob(), ICON)).resolves.toEqual({ bytes: new Uint8Array([7, 7, 7]), source: { w: 3024, h: 4032 } })
    expect(page.encodes).toEqual([{ type: 'image/webp', quality: 0.7, fill: '#123456' }])
    await deriver.deriveImage(blob(), ICON)

    expect(worker.posted).toHaveLength(1) // the second request never went to the latched Worker
    expect(worker.constructed).toBe(1)
    expect(page.decodes).toHaveBeenCalledTimes(2)
  })

  it('a throwing `postMessage` finishes on the page path and latches', async () => {
    const page = stubPageCanvas()
    const worker = stubWorker('throw')
    const { createBrowserDeriver } = await freshDeriverModule()
    const deriver = createBrowserDeriver()

    await expect(deriver.deriveImage(blob(), ICON)).resolves.toEqual({ bytes: new Uint8Array([7, 7, 7]), source: { w: 3024, h: 4032 } })
    await deriver.deriveImage(blob(), ICON)

    expect(worker.constructed).toBe(1)
    expect(page.decodes).toHaveBeenCalledTimes(2)
  })
})

describe('a source failure is not retried on the page thread (B4)', () => {
  it('a Worker answering `source` rejects, the page path runs zero times, and the upload goes original-only', async () => {
    const page = stubPageCanvas()
    stubWorker(({ id }) => ({ id, ok: false, kind: 'source', error: 'InvalidStateError: undecodable' }))
    const { createBrowserDeriver, deriveTiers } = await freshDeriverModule()
    const deriver = createBrowserDeriver()

    await expect(deriver.deriveImage(blob(), ICON)).rejects.toThrow('undecodable')
    await expect(deriveTiers({ blob: blob(), mediaType: 'image/heic' }, deriver)).resolves.toEqual({ derivatives: [], dimensions: null })

    expect(page.decodes).not.toHaveBeenCalled()
  })
})
