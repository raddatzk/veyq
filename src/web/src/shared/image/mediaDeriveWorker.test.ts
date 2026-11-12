// uc.memory.upload — the image pixel stage runs in a Worker where the browser offers one, and degrades to the page
// thread where it cannot. The real Worker never runs in this env (no `OffscreenCanvas`, no module Worker); these
// drive `createBrowserDeriver().deriveImage` against stub Workers and a stubbed page canvas, and count what each
// path did. The page path's decode (`createImageBitmap`) is the fallback counter: it runs once per fallback and
// never when the Worker answered. Which Worker exists is set per test with `vi.stubGlobal` (argonWorker.test.ts's
// pattern), never assumed from the environment.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

// ADR 0127: a WebP request is encoded by libwebp's module, never the canvas. A spy here (hoisted, so the one instance
// survives `vi.resetModules`); the real module runs in webp-encoder/encodeWebp.test.ts.
const encodeWebp = vi.hoisted(() => vi.fn<(rgba: Uint8Array | Uint8ClampedArray, width: number, height: number, quality: number) => Promise<Uint8Array>>())
vi.mock('../../../webp-encoder/encodeWebp', () => ({ encodeWebp }))
const WEBP_BYTES = new Uint8Array([5, 5, 5])

type Opts = { maxEdge: number; quality: number; mimeType?: string; background?: string }
type Listener = (e: unknown) => void

/** The page canvas, stubbed: each `toBlob` records its type, quality and the fill on that canvas, and each read of its
 *  pixels (what libwebp is handed) its size and that fill. */
function stubPageCanvas() {
  const decodes = vi.fn(async () => ({ width: 3024, height: 4032, close: () => {} }))
  const encodes: { type: string; quality: number; fill: string | null }[] = []
  const reads: { w: number; h: number; fill: string | null }[] = []
  vi.stubGlobal('createImageBitmap', decodes)
  vi.stubGlobal('document', {
    createElement: () => {
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
  return { decodes, encodes, reads }
}

/** The page path encoded the icon with libwebp, from the filled final canvas (3024×4032 at 512 → 384×512), and the canvas
 *  encoded nothing. */
function expectLibwebpEncoded(page: ReturnType<typeof stubPageCanvas>, times = 1) {
  expect(page.encodes).toEqual([])
  expect(page.reads).toEqual(Array(times).fill({ w: 384, h: 512, fill: '#123456' }))
  expect(encodeWebp.mock.calls.map(([rgba, ...rest]) => [rgba.length, ...rest])).toEqual(Array(times).fill([384 * 512 * 4, 384, 512, 0.7]))
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

beforeEach(() => {
  vi.resetModules()
  encodeWebp.mockReset()
  encodeWebp.mockResolvedValue(WEBP_BYTES)
})
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
    expectLibwebpEncoded(page) // a WebP: libwebp, never `toBlob` (ADR 0127)
    expect(out).toEqual({ bytes: WEBP_BYTES, source: { w: 3024, h: 4032 } })
  })

  it('the page path keeps a JPEG on the canvas: libwebp is for WebP alone', async () => {
    vi.stubGlobal('Worker', undefined)
    const page = stubPageCanvas()
    const { createBrowserDeriver } = await freshDeriverModule()

    const out = await createBrowserDeriver().deriveImage(blob(), { maxEdge: 512, quality: 0.8 })

    expect(page.encodes).toEqual([{ type: 'image/jpeg', quality: 0.8, fill: null }])
    expect(encodeWebp).not.toHaveBeenCalled()
    expect(out.bytes).toEqual(new Uint8Array([7, 7, 7]))
  })

  it('an encoder that fails to load rejects the derive: no other format is tried', async () => {
    vi.stubGlobal('Worker', undefined)
    const page = stubPageCanvas()
    encodeWebp.mockRejectedValue(new Error('webp_encoder_unavailable: 404'))
    const { createBrowserDeriver } = await freshDeriverModule()

    await expect(createBrowserDeriver().deriveImage(blob(), ICON)).rejects.toThrow('webp_encoder_unavailable')
    expect(page.encodes).toEqual([])
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

    await expect(deriver.deriveImage(blob(), ICON)).resolves.toEqual({ bytes: WEBP_BYTES, source: { w: 3024, h: 4032 } })
    await deriver.deriveImage(blob(), ICON)

    expect(worker.constructed).toBe(1)
    expect(page.decodes).toHaveBeenCalledTimes(2)
  })

  it('a Worker answering `infra` is retried on the page path and latched off (B5)', async () => {
    const page = stubPageCanvas()
    const worker = stubWorker(({ id }) => ({ id, ok: false, kind: 'infra', error: 'offscreen_canvas_unavailable' }))
    const { createBrowserDeriver } = await freshDeriverModule()
    const deriver = createBrowserDeriver()

    await expect(deriver.deriveImage(blob(), ICON)).resolves.toEqual({ bytes: WEBP_BYTES, source: { w: 3024, h: 4032 } })
    expectLibwebpEncoded(page)
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

    await expect(deriver.deriveImage(blob(), ICON)).resolves.toEqual({ bytes: WEBP_BYTES, source: { w: 3024, h: 4032 } })
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

describe('a Worker that existed and could not run says so, once per page (#267)', () => {
  const WARNING = '[veyq] media worker unavailable — deriving images on the page thread'

  beforeEach(() => {
    vi.spyOn(console, 'warn').mockImplementation(() => {})
  })
  afterEach(() => vi.restoreAllMocks())

  it('two requests in flight when the Worker dies fire `error` twice, and the warning once', async () => {
    stubPageCanvas()
    stubWorker('die')
    const { createBrowserDeriver } = await freshDeriverModule()
    const deriver = createBrowserDeriver()

    await Promise.all([deriver.deriveImage(blob(), ICON), deriver.deriveImage(blob(), ICON)])

    expect(console.warn).toHaveBeenCalledTimes(1)
    expect(console.warn).toHaveBeenCalledWith(WARNING)
  })

  it('a Worker answering `infra` warns', async () => {
    stubPageCanvas()
    stubWorker(({ id }) => ({ id, ok: false, kind: 'infra', error: 'offscreen_canvas_unavailable' }))
    const { createBrowserDeriver } = await freshDeriverModule()

    await createBrowserDeriver().deriveImage(blob(), ICON)

    expect(console.warn).toHaveBeenCalledTimes(1)
    expect(console.warn).toHaveBeenCalledWith(WARNING)
  })

  it('a Worker that cannot be constructed warns once, and finishes on the page path', async () => {
    const page = stubPageCanvas()
    class UnbuildableWorker {
      constructor() {
        throw new DOMException('refused', 'SecurityError')
      }
    }
    vi.stubGlobal('Worker', UnbuildableWorker as unknown as typeof Worker)
    const { createBrowserDeriver } = await freshDeriverModule()
    const deriver = createBrowserDeriver()

    await deriver.deriveImage(blob(), ICON)
    await deriver.deriveImage(blob(), ICON)

    expect(page.decodes).toHaveBeenCalledTimes(2)
    expect(console.warn).toHaveBeenCalledTimes(1)
    expect(console.warn).toHaveBeenCalledWith(WARNING)
  })

  it('stays silent where no Worker exists, where the Worker answered, and on a source failure', async () => {
    stubPageCanvas()
    vi.stubGlobal('Worker', undefined)
    const none = await freshDeriverModule()
    await none.createBrowserDeriver().deriveImage(blob(), ICON)

    stubWorker(({ id }) => ({ id, ok: true, bytes: new Uint8Array([9]), source: { w: 1, h: 1 } }))
    const ok = await freshDeriverModule()
    await ok.createBrowserDeriver().deriveImage(blob(), ICON)

    stubWorker(({ id }) => ({ id, ok: false, kind: 'source', error: 'undecodable' }))
    const bad = await freshDeriverModule()
    await expect(bad.createBrowserDeriver().deriveImage(blob(), ICON)).rejects.toThrow('undecodable')

    expect(console.warn).not.toHaveBeenCalled()
  })
})

describe('the Worker itself encodes a WebP with libwebp, never `convertToBlob` (ADR 0127)', () => {
  /** The Worker's globals, stubbed: `self` collects the message listener and every reply, `OffscreenCanvas` records its
   *  `convertToBlob` calls and its pixel reads. */
  function stubWorkerScope() {
    const listeners: ((e: { data: unknown }) => void)[] = []
    const replies: unknown[] = []
    const converts: string[] = []
    const reads: { w: number; h: number }[] = []
    vi.stubGlobal('self', {
      addEventListener: (_type: string, cb: (e: { data: unknown }) => void) => listeners.push(cb),
      postMessage: (message: unknown) => replies.push(message),
    })
    vi.stubGlobal('createImageBitmap', vi.fn(async () => ({ width: 3024, height: 4032, close: () => {} })))
    class StubOffscreenCanvas {
      constructor(public width: number, public height: number) {}
      getContext() {
        return {
          fillRect: () => {},
          drawImage: () => {},
          getImageData: (_x: number, _y: number, w: number, h: number) => {
            reads.push({ w, h })
            return { data: new Uint8ClampedArray(w * h * 4) }
          },
        }
      }
      async convertToBlob({ type }: { type: string }) {
        converts.push(type)
        return new Blob([new Uint8Array([7]) as BlobPart], { type })
      }
    }
    vi.stubGlobal('OffscreenCanvas', StubOffscreenCanvas)
    const send = async (data: unknown) => {
      listeners.forEach((cb) => cb({ data }))
      await vi.waitFor(() => expect(replies).toHaveLength(1))
      return replies[0]
    }
    return { send, converts, reads }
  }

  it('answers a WebP request with libwebp\'s bytes, read from the final canvas', async () => {
    const scope = stubWorkerScope()
    await import('./mediaDerive.worker')

    const reply = await scope.send({ id: 1, source: blob(), opts: ICON })

    expect(reply).toEqual({ id: 1, ok: true, bytes: WEBP_BYTES, source: { w: 3024, h: 4032 } })
    expect(scope.converts).toEqual([])
    expect(scope.reads).toEqual([{ w: 384, h: 512 }])
    expect(encodeWebp).toHaveBeenCalledTimes(1)
  })

  it('answers an encoder that cannot load as a `source` failure, so the page rejects rather than retrying it', async () => {
    const scope = stubWorkerScope()
    encodeWebp.mockRejectedValue(new Error('webp_encoder_unavailable: 404'))
    await import('./mediaDerive.worker')

    const reply = await scope.send({ id: 2, source: blob(), opts: ICON })

    expect(reply).toMatchObject({ id: 2, ok: false, kind: 'source', error: expect.stringContaining('webp_encoder_unavailable') })
    expect(scope.converts).toEqual([])
  })
})
