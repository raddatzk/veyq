import { describe, it, expect, vi } from 'vitest'
import {
  PlaintextCache,
  PlaybackSession,
  buildRangeResponse,
  makeRegistry,
  handleMessage,
  handleFetch,
} from './mediaSw'
import { PLAINTEXT_CHUNK_SIZE, plaintextTotal } from './range'
import { PROTOCOL_VERSION } from './protocol'

/** A linked MessagePort pair: a message posted on one end fires `onmessage` on the other (async, like the real
 *  thing). `a` is handed to the SW-side PlaybackSession; the test drives the page side via `b`. */
function portPair(): { a: MessagePort; b: MessagePort } {
  const a = { onmessage: null } as unknown as MessagePort & { onmessage: ((e: MessageEvent) => void) | null }
  const b = { onmessage: null } as unknown as MessagePort & { onmessage: ((e: MessageEvent) => void) | null }
  a.postMessage = (data: unknown) => queueMicrotask(() => b.onmessage?.({ data } as MessageEvent))
  b.postMessage = (data: unknown) => queueMicrotask(() => a.onmessage?.({ data } as MessageEvent))
  a.start = () => {}
  b.start = () => {}
  a.close = () => {}
  b.close = () => {}
  return { a, b }
}

/** Simulate the page end: answer each chunk request with the plaintext of that chunk (a fresh transferable copy). */
function pageResponder(port: MessagePort, chunks: Uint8Array[]): { calls: number[] } {
  const calls: number[] = []
  port.onmessage = (e: MessageEvent) => {
    const req = e.data as { type: string; id: number; index: number }
    if (req.type !== 'veyq-chunk-req') return
    calls.push(req.index)
    const copy = chunks[req.index].slice()
    port.postMessage({ type: 'veyq-chunk-res', id: req.id, bytes: copy.buffer })
  }
  return { calls }
}

/** A session backed by an in-test page responder. `chunks` are the plaintext chunks; byteSize is derived. */
function buildSession(chunks: Uint8Array[], contentType = 'video/mp4') {
  const { a, b } = portPair()
  const responder = pageResponder(b, chunks)
  const byteSize = chunks.reduce((n, c) => n + c.length + 28, 0)
  const session = new PlaybackSession(chunks.length, byteSize, a, contentType)
  return { session, responder, byteSize }
}

const drain = async (res: Response) => new Uint8Array(await res.arrayBuffer())

/** A page load of `url`. The Fetch spec forbids constructing a `mode: 'navigate'` Request (it throws), so the SW's
 *  FetchEvent.request is modelled by the three fields handleFetch reads. */
const navigation = (url: string) => ({ url, mode: 'navigate', headers: new Headers() }) as unknown as Request

describe('PlaintextCache — bounded in-memory LRU (never persisted)', () => {
  it('evicts the least-recently-used chunk when over the byte cap', () => {
    const cache = new PlaintextCache(250) // room for ~2 chunks of 100
    cache.put(0, new Uint8Array(100))
    cache.put(1, new Uint8Array(100))
    expect(cache.size).toBe(2)
    cache.get(0) // touch 0 → 1 is now least-recently-used
    cache.put(2, new Uint8Array(100)) // over cap → evict LRU (chunk 1)
    expect(cache.get(1)).toBeUndefined()
    expect(cache.get(0)).toBeDefined()
    expect(cache.get(2)).toBeDefined()
  })

  it('ignores a duplicate put and keeps at least one entry even when a chunk exceeds the cap', () => {
    const cache = new PlaintextCache(50)
    const big = new Uint8Array(100)
    cache.put(0, big)
    cache.put(0, big) // duplicate → no double count
    expect(cache.size).toBe(1) // the sole entry is never evicted below 1, even over cap
  })
})

describe('PlaybackSession — port RPC + caching', () => {
  it('fetches a chunk over the port and caches it (no second request)', async () => {
    const chunks = [new Uint8Array([1, 2, 3]), new Uint8Array([4, 5, 6])]
    const { session, responder } = buildSession(chunks)
    expect(Array.from(await session.chunk(1))).toEqual([4, 5, 6])
    expect(Array.from(await session.chunk(1))).toEqual([4, 5, 6]) // served from cache
    expect(responder.calls).toEqual([1]) // requested exactly once
  })

  it('rejects pending reads when the session is closed (e.g. revoke mid-play)', async () => {
    const { a } = portPair()
    a.postMessage = () => {} // swallow — never answer, so the read stays pending
    const session = new PlaybackSession(1, 28, a)
    const pending = session.chunk(0)
    session.close()
    await expect(pending).rejects.toThrow(/playback_revoked/)
  })
})

describe('buildRangeResponse — 200 / 206 / 416 assembly', () => {
  // Two full 256 KiB chunks (filled with 1 then 2) + a short 50-byte tail (filled with 3).
  const chunkA = new Uint8Array(PLAINTEXT_CHUNK_SIZE).fill(1)
  const chunkB = new Uint8Array(PLAINTEXT_CHUNK_SIZE).fill(2)
  const chunkC = new Uint8Array(50).fill(3)
  const total = 2 * PLAINTEXT_CHUNK_SIZE + 50

  it('no Range → 200 with Accept-Ranges and the whole plaintext', async () => {
    const { session, byteSize } = buildSession([chunkA, chunkB, chunkC])
    expect(plaintextTotal(byteSize, 3)).toBe(total)
    const res = buildRangeResponse(session, null)
    expect(res.status).toBe(200)
    expect(res.headers.get('Accept-Ranges')).toBe('bytes')
    expect(res.headers.get('Content-Length')).toBe(String(total))
    const body = await drain(res)
    expect(body.length).toBe(total)
    expect(body[0]).toBe(1)
    expect(body[PLAINTEXT_CHUNK_SIZE]).toBe(2)
    expect(body[total - 1]).toBe(3)
  })

  it('the first probe bytes=0-1 → 206 with a 2-byte window', async () => {
    const { session } = buildSession([chunkA, chunkB, chunkC])
    const res = buildRangeResponse(session, 'bytes=0-1')
    expect(res.status).toBe(206)
    expect(res.headers.get('Content-Range')).toBe(`bytes 0-1/${total}`)
    expect(res.headers.get('Content-Length')).toBe('2')
    expect(res.headers.get('Content-Type')).toBe('video/mp4') // the sealed MIME the page passed at register
    expect(Array.from(await drain(res))).toEqual([1, 1])
  })

  it('a range crossing a chunk boundary stitches the two chunks', async () => {
    const { session, responder } = buildSession([chunkA, chunkB, chunkC])
    // last byte of chunk 0 (value 1) + first two of chunk 1 (value 2)
    const res = buildRangeResponse(session, `bytes=${PLAINTEXT_CHUNK_SIZE - 1}-${PLAINTEXT_CHUNK_SIZE + 1}`)
    expect(res.status).toBe(206)
    expect(Array.from(await drain(res))).toEqual([1, 2, 2])
    expect(responder.calls).toContain(0)
    expect(responder.calls).toContain(1)
  })

  it('an open range bytes=N- runs to the end', async () => {
    const { session } = buildSession([chunkA, chunkB, chunkC])
    const res = buildRangeResponse(session, `bytes=${total - 3}-`)
    expect(res.status).toBe(206)
    expect(res.headers.get('Content-Range')).toBe(`bytes ${total - 3}-${total - 1}/${total}`)
    expect(Array.from(await drain(res))).toEqual([3, 3, 3])
  })

  it('an unsatisfiable range → 416 with bytes */total', async () => {
    const { session } = buildSession([chunkA, chunkB, chunkC])
    const res = buildRangeResponse(session, `bytes=${total}-${total + 10}`)
    expect(res.status).toBe(416)
    expect(res.headers.get('Content-Range')).toBe(`bytes */${total}`)
  })
})

describe('handleFetch — routing', () => {
  const origin = 'https://veyq.localhost'
  it('returns null for a non-/__media/ request (falls through to network)', () => {
    const reg = makeRegistry()
    const req = new Request(`${origin}/api/whatever`)
    expect(handleFetch(reg, origin, req)).toBeNull()
  })

  it('returns null for a cross-origin /__media/ request', () => {
    const reg = makeRegistry()
    const req = new Request(`https://evil.example/__media/abc`)
    expect(handleFetch(reg, origin, req)).toBeNull()
  })

  it('404s an unknown handle', () => {
    const reg = makeRegistry()
    const res = handleFetch(reg, origin, new Request(`${origin}/__media/nope`))
    expect(res?.status).toBe(404)
  })

  it('serves a registered handle', async () => {
    const reg = makeRegistry()
    const { session } = buildSession([new Uint8Array([9, 8, 7])])
    reg.register('h1', session)
    const res = handleFetch(reg, origin, new Request(`${origin}/__media/h1`, { headers: { Range: 'bytes=0-1' } }))
    expect(res?.status).toBe(206)
    expect(Array.from(await drain(res!))).toEqual([9, 8])
  })

  // A <video> fetch is the shim's only caller, so a page load of a handle ("open video in new tab") is by
  // definition not one — and served, it would be decrypted bytes as a document on the app origin (ADR 0047 § The
  // shim serves inert responses). The control is the same live handle fetched as a subresource, which must serve.
  it('404s a navigation to a LIVE handle, while the same handle still serves a subresource fetch', async () => {
    const reg = makeRegistry()
    const { session } = buildSession([new Uint8Array([9, 8, 7])], 'video/mp4')
    reg.register('h1', session)
    const res = handleFetch(reg, origin, navigation(`${origin}/__media/h1`))
    expect(res?.status).toBe(404)
    expect(res?.headers.get('Content-Type')).toBeNull() // nothing of the medium's type leaks into the refusal
    const control = handleFetch(reg, origin, new Request(`${origin}/__media/h1`))
    expect(control?.status).toBe(200)
  })

  it('leaves a navigation outside /__media/ to the network', () => {
    const reg = makeRegistry()
    expect(handleFetch(reg, origin, navigation(`${origin}/memories`))).toBeNull()
  })
})

describe('every shim response is inert (ADR 0047 / ADR 0013 §6)', () => {
  const origin = 'https://veyq.localhost'
  const inert = (res: Response | null) => {
    expect(res).not.toBeNull()
    expect(res!.headers.get('X-Content-Type-Options')).toBe('nosniff')
    expect(res!.headers.get('Content-Security-Policy')).toBe("sandbox; default-src 'none'")
  }
  const live = () => {
    const reg = makeRegistry()
    reg.register('h1', buildSession([new Uint8Array([9, 8, 7])]).session)
    return reg
  }

  it('on a 200', () => inert(handleFetch(live(), origin, new Request(`${origin}/__media/h1`))))
  it('on a 206', () =>
    inert(handleFetch(live(), origin, new Request(`${origin}/__media/h1`, { headers: { Range: 'bytes=0-1' } }))))
  it('on a 416', () =>
    inert(handleFetch(live(), origin, new Request(`${origin}/__media/h1`, { headers: { Range: 'bytes=99-100' } }))))
  it('on an unknown handle', () => inert(handleFetch(makeRegistry(), origin, new Request(`${origin}/__media/nope`))))
  it('on a refused navigation', () => inert(handleFetch(live(), origin, navigation(`${origin}/__media/h1`))))
})

describe('handleMessage — handshake / register / revoke / end-session', () => {
  it('answers a handshake with an ack carrying the protocol version', () => {
    const reg = makeRegistry()
    const reply = { postMessage: vi.fn() } as unknown as MessagePort
    handleMessage(reg, { type: 'veyq-handshake', version: PROTOCOL_VERSION }, [reply])
    expect(reply.postMessage).toHaveBeenCalledWith({ type: 'veyq-handshake-ack', version: PROTOCOL_VERSION })
  })

  it('registers a playback from a port, then revokes it', () => {
    const reg = makeRegistry()
    const { a } = portPair()
    handleMessage(reg, { type: 'veyq-register-playback', handle: 'h', chunkCount: 1, byteSize: 28, mediaType: 'video/mp4' }, [a])
    expect(reg.get('h')).toBeDefined()
    handleMessage(reg, { type: 'veyq-revoke-playback', handle: 'h' }, [])
    expect(reg.get('h')).toBeUndefined()
  })

  // inbox #412: Files plays an `<audio>` through the same route.
  it.each(['audio/mpeg', 'audio/ogg', 'audio/mp4', 'video/webm'])('registers a playback under %s', (mediaType) => {
    const reg = makeRegistry()
    const { a } = portPair()
    handleMessage(reg, { type: 'veyq-register-playback', handle: 'h', chunkCount: 1, byteSize: 28, mediaType }, [a])
    expect(reg.get('h')).toBeDefined()
  })

  // The shim serves the registered mediaType as Content-Type, and that value was sealed by the uploader. Anything
  // but one well-formed `video/<subtype>` or `audio/<subtype>` never gets a handle — here, independently of the page's own gate.
  it.each([
    ['a listed audio type', 'audio/mpeg, text/html'],
    ['an audio type with a parameter', 'audio/mpeg; codecs="mp3"'],
    ['a listed type', 'video/mp4, text/html'],
    ['a parameter', 'video/mp4; codecs="avc1"'],
    ['a non-video type', 'text/html'],
    ['no type at all', undefined],
  ])('refuses to register %s', (_, mediaType) => {
    const reg = makeRegistry()
    const { a } = portPair()
    handleMessage(reg, { type: 'veyq-register-playback', handle: 'h', chunkCount: 1, byteSize: 28, mediaType }, [a])
    expect(reg.get('h')).toBeUndefined()
  })

  // uc.account.sign-out § The ending, step 2. The SW outlives every page, so a session that ended in one tab must
  // take the playbacks OTHER tabs registered with it too — and their pending reads, which would otherwise still
  // be answered with plaintext by a page that has not reloaded yet.
  it('drops every playback on end-session, rejects their pending reads, and acks', async () => {
    const reg = makeRegistry()
    const first = portPair()
    const second = portPair()
    first.a.postMessage = () => {} // never answered — the read stays pending until the session ends
    handleMessage(reg, { type: 'veyq-register-playback', handle: 'h1', chunkCount: 1, byteSize: 28, mediaType: 'video/mp4' }, [first.a])
    handleMessage(reg, { type: 'veyq-register-playback', handle: 'h2', chunkCount: 1, byteSize: 28, mediaType: 'video/mp4' }, [second.a])
    const pending = reg.get('h1')!.chunk(0)
    const reply = { postMessage: vi.fn() } as unknown as MessagePort

    handleMessage(reg, { type: 'veyq-end-session' }, [reply])

    expect(reg.get('h1')).toBeUndefined()
    expect(reg.get('h2')).toBeUndefined()
    await expect(pending).rejects.toThrow(/playback_revoked/)
    expect(reply.postMessage).toHaveBeenCalledWith({ type: 'veyq-end-session-ack' })
  })

  it('ignores a message that is not one of ours', () => {
    const reg = makeRegistry()
    expect(() => handleMessage(reg, { type: 'something-else' }, [])).not.toThrow()
    expect(() => handleMessage(reg, null, [])).not.toThrow()
  })
})
