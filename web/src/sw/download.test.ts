// The streamed download through the Service Worker (inbox #371; ADR 0047 § Addendum), end to end in one process: the
// page's `registerDownload` talks to a fake `ServiceWorker` that hands its messages to the REAL SW-side `handleMessage`,
// the "navigation" is the REAL `handleFetch`, and the bytes cross a real `MessageChannel`. What is pinned: the bytes
// arrive whole and in order with the attachment headers; the handle serves one navigation; a page failure, a short or
// overlong stream and an ended session error the body (the browser fails the download) instead of closing it; a browser
// cancel reaches the page as an AbortError; a filename cannot inject into the header.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { handleFetch, handleMessage, makeRegistry, type PlaybackRegistry } from './mediaSw'
import { DownloadSession, contentDisposition, makeDownloadRegistry, safeFilename } from './downloadRoute'
import { DOWNLOAD_PATH_PREFIX, PROTOCOL_VERSION } from './protocol'
import { endAllPlaybacks, registerDownload } from './register'
import { streamBlob, type ChunkSource } from '../core/blob/stream'

const origin = 'https://veyq.test'
const navigation = (url: string) => ({ url, mode: 'navigate', method: 'GET', headers: new Headers() }) as unknown as Request

let reg: PlaybackRegistry
let swVersion = PROTOCOL_VERSION
let lastResponse: Response | null = null
const navigate = (url: string) => {
  lastResponse = handleFetch(reg, origin, navigation(origin + url))
}

/** A controlling ServiceWorker whose messages run the real SW handler — except a handshake at a stale version. */
const fakeSw = {
  postMessage(data: unknown, transfer: Transferable[] = []) {
    const ports = transfer.filter((t): t is MessagePort => t instanceof MessagePort)
    if ((data as { type?: string }).type === 'veyq-handshake' && swVersion !== PROTOCOL_VERSION) {
      ports[0]?.postMessage({ type: 'veyq-handshake-ack', version: swVersion })
      return
    }
    handleMessage(reg, data, ports)
  },
}

beforeEach(() => {
  reg = makeRegistry()
  swVersion = PROTOCOL_VERSION
  lastResponse = null
  vi.stubGlobal('navigator', {
    serviceWorker: {
      register: async () => ({}),
      ready: Promise.resolve(),
      controller: fakeSw,
      addEventListener: () => {},
      removeEventListener: () => {},
      getRegistration: async () => ({ active: fakeSw }),
    },
  })
})
afterEach(() => vi.unstubAllGlobals())

const chunksOf = (...sizes: number[]) => sizes.map((n, c) => new Uint8Array(n).map((_, i) => (c * 31 + i) % 251))
const source = (chunks: Uint8Array[], failAt = -1): ChunkSource => ({
  chunkCount: chunks.length,
  getPlaintextChunk: async (i) => {
    if (i === failAt) throw new Error('OpenRefused')
    return chunks[i]
  },
})
const sizeOf = (chunks: Uint8Array[]) => chunks.reduce((n, c) => n + c.length, 0)
const concat = (chunks: Uint8Array[]) => Array.from(chunks.flatMap((c) => Array.from(c)))

async function startDownload(name: string, size: number) {
  const sink = await registerDownload({ name, size }, { navigate })
  expect(sink).not.toBeNull()
  expect(lastResponse).not.toBeNull()
  return { sink: sink!, res: lastResponse! }
}

describe('a streamed download through the SW', () => {
  it('delivers the bytes whole, in order, as an inert attachment of the exact length', async () => {
    const chunks = chunksOf(300, 300, 17)
    const { sink, res } = await startDownload('Rom 2024.mp4', sizeOf(chunks))
    expect(res.status).toBe(200)
    expect(res.headers.get('Content-Type')).toBe('application/octet-stream')
    expect(res.headers.get('Content-Disposition')).toBe(`attachment; filename="Rom 2024.mp4"; filename*=UTF-8''Rom%202024.mp4`)
    expect(res.headers.get('Content-Length')).toBe(String(sizeOf(chunks)))
    expect(res.headers.get('X-Content-Type-Options')).toBe('nosniff')
    expect(res.headers.get('Content-Security-Policy')).toBe("sandbox; default-src 'none'")
    expect(res.headers.get('Cache-Control')).toBe('no-store')
    const [, body] = await Promise.all([streamBlob(source(chunks), sink), res.arrayBuffer()])
    expect(Array.from(new Uint8Array(body))).toEqual(concat(chunks))
  })

  it('an empty file completes with an empty body', async () => {
    const { sink, res } = await startDownload('empty', 0)
    const [, body] = await Promise.all([streamBlob(source([new Uint8Array(0)]), sink), res.arrayBuffer()])
    expect(body.byteLength).toBe(0)
  })

  it('a page failure mid-stream errors the body — never a clean, truncated file', async () => {
    const chunks = chunksOf(10, 10, 10)
    const { sink, res } = await startDownload('a', sizeOf(chunks))
    const [page, body] = await Promise.allSettled([streamBlob(source(chunks, 1), sink), res.arrayBuffer()])
    expect(page.status).toBe('rejected')
    expect(body.status).toBe('rejected')
  })

  it('a handle serves exactly one navigation; a second one leaves the tab where it is (204)', async () => {
    let first: Response | null = null
    let again: Response | null = null
    const sink = await registerDownload({ name: 'a', size: 1 }, {
      navigate: (u) => {
        first = handleFetch(reg, origin, navigation(origin + u))
        again = handleFetch(reg, origin, navigation(origin + u)) // the same URL, replayed
      },
    })
    expect(first!.status).toBe(200)
    expect(again!.status).toBe(204)
    expect(again!.headers.get('Content-Disposition')).toBeNull()
    expect(again!.body).toBeNull()
    await Promise.all([streamBlob(source([new Uint8Array([1])]), sink!), first!.arrayBuffer()])
  })

  it('only a navigation is served: a fetch() of a handle is 404 and does not use it up', async () => {
    let probe: Response | null = null
    let real: Response | null = null
    const sink = await registerDownload({ name: 'a', size: 1 }, {
      navigate: (u) => {
        probe = handleFetch(reg, origin, new Request(origin + u))
        real = handleFetch(reg, origin, navigation(origin + u))
      },
    })
    expect(probe!.status).toBe(404)
    expect(real!.status).toBe(200)
    await Promise.all([streamBlob(source([new Uint8Array([9])]), sink!), real!.arrayBuffer()])
  })

  it('the browser cancelling the download reaches the page as an AbortError (silent), and stops it', async () => {
    const chunks = chunksOf(10, 10, 10, 10)
    const { sink, res } = await startDownload('a', sizeOf(chunks))
    const reader = res.body!.getReader()
    const page = streamBlob(source(chunks), sink)
    await reader.read()
    await reader.cancel()
    const e = await page.catch((x: unknown) => x)
    expect(e).toBeInstanceOf(DOMException)
    expect((e as DOMException).name).toBe('AbortError') // what the save path treats as the user's choice: no toast
  })

  it('an ended session drops a streaming download: the body errors and the page write fails', async () => {
    const chunks = chunksOf(10, 10, 10)
    const { sink, res } = await startDownload('a', sizeOf(chunks))
    const reader = res.body!.getReader()
    await sink.write(chunks[0])
    await reader.read()
    handleMessage(reg, { type: 'veyq-end-session' }, [])
    await expect(reader.read()).rejects.toThrow('download_ended')
    await expect(sink.write(chunks[1])).rejects.toThrow('download_ended')
  })

  it('sign-out (endAllPlaybacks) ends a running download on the page and in the SW', async () => {
    const chunks = chunksOf(10, 10)
    const { sink, res } = await startDownload('a', sizeOf(chunks))
    const reading = res.arrayBuffer().then(() => 'closed', () => 'errored')
    await endAllPlaybacks()
    await expect(sink.write(chunks[0])).rejects.toThrow('download_ended')
    expect(await reading).toBe('errored')
  })

  it('a handle never navigated to expires, and the page is told', async () => {
    reg = makeRegistry(makeDownloadRegistry(20))
    const sink = await registerDownload({ name: 'a', size: 1 }, { navigate: () => {} })
    await expect(sink!.write(new Uint8Array([1]))).rejects.toThrow('download_expired')
    // and a late navigation finds nothing
    expect(handleFetch(reg, origin, navigation(`${origin}${DOWNLOAD_PATH_PREFIX}whatever`))?.status).toBe(204)
  })

  it('a controlling SW at another protocol version gets no download (null → the caller falls back)', async () => {
    swVersion = PROTOCOL_VERSION - 1
    const navigateSpy = vi.fn()
    expect(await registerDownload({ name: 'a', size: 1 }, { navigate: navigateSpy })).toBeNull()
    expect(navigateSpy).not.toHaveBeenCalled()
  })
})

describe('the SW side guards what the page sends', () => {
  function session(size: number) {
    const ch = new MessageChannel()
    const s = new DownloadSession(size, 'a', ch.port2)
    const page = ch.port1
    page.onmessage = (e: MessageEvent) => {
      if ((e.data as { type: string }).type === 'veyq-dl-pull') pulls.push(1)
    }
    const pulls: number[] = []
    return { body: s.body(), page, pulls }
  }
  const tick = () => new Promise((r) => setTimeout(r, 5))

  it('a stream that ends short of the registered size errors', async () => {
    const { body, page } = session(4)
    const reading = new Response(body).arrayBuffer()
    await tick()
    const two = new Uint8Array([1, 2])
    page.postMessage({ type: 'veyq-dl-data', bytes: two.buffer }, [two.buffer])
    await tick()
    page.postMessage({ type: 'veyq-dl-end' })
    await expect(reading).rejects.toThrow('download_short')
  })

  it('a stream that runs past the registered size errors', async () => {
    const { body, page } = session(1)
    const reading = new Response(body).arrayBuffer()
    await tick()
    const two = new Uint8Array([1, 2])
    page.postMessage({ type: 'veyq-dl-data', bytes: two.buffer }, [two.buffer])
    await expect(reading).rejects.toThrow('download_overrun')
  })

  it('refuses a registration without a port, with a negative or fractional size, or without a handle', () => {
    const r = makeRegistry()
    const ch = new MessageChannel()
    handleMessage(r, { type: 'veyq-register-download', handle: 'h', size: 1, name: 'a' }, [])
    handleMessage(r, { type: 'veyq-register-download', handle: 'h', size: -1, name: 'a' }, [ch.port2])
    handleMessage(r, { type: 'veyq-register-download', handle: 'h', size: 1.5, name: 'a' }, [ch.port2])
    handleMessage(r, { type: 'veyq-register-download', handle: '', size: 1, name: 'a' }, [ch.port2])
    expect(handleFetch(r, origin, navigation(`${origin}${DOWNLOAD_PATH_PREFIX}h`))?.status).toBe(204)
    ch.port1.close()
  })

  it('a malformed handle escape is no handle — answered, never thrown (a throw would fall through to the network)', () => {
    expect(handleFetch(makeRegistry(), origin, navigation(`${origin}${DOWNLOAD_PATH_PREFIX}%E0%A4%A`))?.status).toBe(204)
  })
})

describe('the Content-Disposition of a download', () => {
  it.each([
    ['a "quoted" name.txt', `attachment; filename="a _quoted_ name.txt"; filename*=UTF-8''a%20%22quoted%22%20name.txt`],
    ['evil\r\nSet-Cookie: x=1.txt', `attachment; filename="evilSet-Cookie: x=1.txt"; filename*=UTF-8''evilSet-Cookie%3A%20x%3D1.txt`],
    ['../../etc/passwd', `attachment; filename=".._.._etc_passwd"; filename*=UTF-8''.._.._etc_passwd`],
    ['C:\\Users\\a.txt', `attachment; filename="C:_Users_a.txt"; filename*=UTF-8''C%3A_Users_a.txt`],
    ['Grüße 😀.pdf', `attachment; filename="Gr__e __.pdf"; filename*=UTF-8''Gr%C3%BC%C3%9Fe%20%F0%9F%98%80.pdf`],
    ['invoice\u202etxt.exe', `attachment; filename="invoicetxt.exe"; filename*=UTF-8''invoicetxt.exe`],
    ["it's (1)*.txt", `attachment; filename="it's (1)*.txt"; filename*=UTF-8''it%27s%20%281%29%2A.txt`],
    ['100%.txt', `attachment; filename="100_.txt"; filename*=UTF-8''100%25.txt`],
    ['', `attachment; filename="download"; filename*=UTF-8''download`],
    ['..', `attachment; filename="download"; filename*=UTF-8''download`],
    ['\ud800lone', `attachment; filename="_lone"; filename*=UTF-8''%EF%BF%BDlone`],
  ])('%j', (name, expected) => {
    const value = contentDisposition(name)
    expect(value).toBe(expected)
    expect(() => new Headers({ 'Content-Disposition': value })).not.toThrow()
    expect(value).not.toMatch(/[\r\n]/)
  })

  it('bounds a very long name', () => {
    expect(Array.from(safeFilename('x'.repeat(10_000))).length).toBe(200)
  })
})
