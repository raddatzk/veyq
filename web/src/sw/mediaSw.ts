// cr-inline-video-playback Slice 2 (ADR 0047) — the media Service Worker: a KEY-FREE range shim.
//
// It holds NO key, NO auth token and NO plaintext-at-rest. For a `/__media/<handle>` request it maps the <video>
// demuxer's plaintext byte-range to the covering chunk indices (range.ts), asks the owning PAGE for those chunks'
// decrypted plaintext over a registered MessagePort, assembles the window and answers 206. The page runs
// presign→openFrame in its own context; keys stay there. The only cache here is a small in-memory, size-bounded
// LRU of plaintext chunks — NEVER Cache Storage, NEVER IndexedDB: decrypted media on disk would regress
// operator-blindness (no plaintext is ever stored today), and it is the shortcut a later change is most tempted
// to take, so it is forbidden here explicitly.
//
// The file typechecks under the app's DOM lib (no WebWorker lib, which collides with DOM on `self`/`fetch`): the
// core (PlaintextCache, PlaybackSession, buildRangeResponse) uses only DOM-available types, and the sliver of the
// service-worker global scope we touch is declared locally as `MediaSwScope`. This is the deliberate resolution
// of the "module-SW dev-loop friction" the CR flagged — no separate tsconfig, no /// <reference lib> directive.

import {
  clampChunkToRange,
  contentRange,
  coveringChunks,
  parseRange,
  plaintextTotal,
  unsatisfiedRange,
  type ByteRange,
} from './range'
import { MEDIA_PATH_PREFIX, PROTOCOL_VERSION, type ChunkReply, type PageToSwMessage } from './protocol'

/** Max decrypted plaintext held in the read-ahead cache, per playback. A UX/bandwidth dial, NOT a privacy control
 *  (the ADR 0047 seek-leak is accepted) — re-tune after live-verify. Two 256 KiB chunks of headroom over the
 *  current window keeps contiguous playback smooth without hoarding decrypted bytes. */
export const CACHE_BYTES = 8 * 1024 * 1024

/** How many chunks past the served window to pre-warm into the cache. A UX dial (see CACHE_BYTES) — 0 disables. */
export const READ_AHEAD_CHUNKS = 2

// --- in-memory plaintext cache ----------------------------------------------

/** A size-bounded LRU of decrypted chunk plaintext, keyed by chunk index. In-memory ONLY — see the file header. */
export class PlaintextCache {
  private readonly map = new Map<number, Uint8Array>()
  private bytes = 0
  constructor(private readonly maxBytes: number) {}

  get(index: number): Uint8Array | undefined {
    const v = this.map.get(index)
    if (v) {
      this.map.delete(index) // LRU touch: re-insert so it becomes most-recently-used
      this.map.set(index, v)
    }
    return v
  }

  put(index: number, bytes: Uint8Array): void {
    if (this.map.has(index)) return
    this.map.set(index, bytes)
    this.bytes += bytes.length
    while (this.bytes > this.maxBytes && this.map.size > 1) {
      const oldest = this.map.keys().next().value as number
      this.bytes -= this.map.get(oldest)!.length
      this.map.delete(oldest)
    }
  }

  get size(): number {
    return this.map.size
  }

  clear(): void {
    this.map.clear()
    this.bytes = 0
  }
}

// --- one playback: the port RPC + its cache ---------------------------------

/** One registered playback: the MessagePort back to the page + a bounded plaintext cache. The SW asks the page
 *  for a chunk's plaintext by index and correlates the reply by a monotonic id. */
export class PlaybackSession {
  private readonly cache: PlaintextCache
  private readonly pending = new Map<number, { resolve: (b: Uint8Array) => void; reject: (e: Error) => void }>()
  private seq = 0

  constructor(
    readonly chunkCount: number,
    readonly byteSize: number,
    private readonly port: MessagePort,
    readonly contentType = 'application/octet-stream',
    cacheBytes = CACHE_BYTES,
  ) {
    this.cache = new PlaintextCache(cacheBytes)
    this.port.onmessage = (e: MessageEvent) => this.onReply(e.data as ChunkReply)
    this.port.start?.()
  }

  private onReply(msg: ChunkReply): void {
    const waiter = this.pending.get(msg.id)
    if (!waiter) return
    this.pending.delete(msg.id)
    if (msg.type === 'veyq-chunk-res') waiter.resolve(new Uint8Array(msg.bytes))
    else waiter.reject(new Error(msg.error))
  }

  /** Decrypted plaintext of chunk `index` — from cache, or fetched from the page over the port and then cached. */
  async chunk(index: number): Promise<Uint8Array> {
    const hit = this.cache.get(index)
    if (hit) return hit
    const id = this.seq++
    const bytes = await new Promise<Uint8Array>((resolve, reject) => {
      this.pending.set(id, { resolve, reject })
      this.port.postMessage({ type: 'veyq-chunk-req', id, index })
    })
    this.cache.put(index, bytes)
    return bytes
  }

  /** Fire-and-forget: pre-warm the next few chunks into the cache (contiguous-playback read-ahead). */
  prefetch(from: number, count: number): void {
    for (let i = from; i < from + count && i < this.chunkCount; i++) {
      if (i >= 0) void this.chunk(i).catch(() => {}) // a prefetch miss is harmless — the real read will retry
    }
  }

  close(): void {
    this.port.onmessage = null
    this.pending.forEach((w) => w.reject(new Error('playback_revoked')))
    this.pending.clear()
    this.cache.clear()
    this.port.close?.()
  }
}

// --- response assembly ------------------------------------------------------

/** A streaming body over `range`: pulls the covering chunks one at a time (backpressure caps over-fetch) and
 *  slices each to the window. `cancel` stops further page round-trips — an early demuxer abort (a seek) costs
 *  nothing beyond the chunk in flight. */
function rangeBody(session: PlaybackSession, range: ByteRange): ReadableStream<Uint8Array> {
  const { first, last } = coveringChunks(range)
  let i = first
  let cancelled = false
  return new ReadableStream<Uint8Array>({
    async pull(controller) {
      if (cancelled) return
      if (i > last) {
        controller.close()
        return
      }
      const index = i++
      const chunk = await session.chunk(index)
      const { from, to } = clampChunkToRange(index, chunk.length, range)
      if (to > from) controller.enqueue(chunk.subarray(from, to))
      if (i > last) session.prefetch(last + 1, READ_AHEAD_CHUNKS) // warm the next window for contiguous playback
    },
    cancel() {
      cancelled = true
    },
  })
}

/** Build the HTTP response for one `/__media/<handle>` request from its `Range` header. */
export function buildRangeResponse(session: PlaybackSession, rangeHeader: string | null): Response {
  const total = plaintextTotal(session.byteSize, session.chunkCount)
  const parsed = parseRange(rangeHeader, total)
  if (parsed.kind === 'unsatisfiable') {
    return new Response(null, {
      status: 416,
      headers: { 'Accept-Ranges': 'bytes', 'Content-Range': unsatisfiedRange(total) },
    })
  }
  const range: ByteRange = parsed.kind === 'full' ? { start: 0, end: Math.max(0, total - 1) } : parsed.range
  const headers: Record<string, string> = {
    'Accept-Ranges': 'bytes',
    'Content-Length': String(range.end - range.start + 1),
    'Content-Type': session.contentType, // the sealed MIME the page passed at register — lets the demuxer pick the container
  }
  if (parsed.kind === 'range') headers['Content-Range'] = contentRange(range, total)
  return new Response(rangeBody(session, range), { status: parsed.kind === 'full' ? 200 : 206, headers })
}

// --- the registry + event handlers (driven by the SW scope) ------------------

export type PlaybackRegistry = {
  register(handle: string, session: PlaybackSession): void
  revoke(handle: string): void
  get(handle: string): PlaybackSession | undefined
}

export function makeRegistry(): PlaybackRegistry {
  const sessions = new Map<string, PlaybackSession>()
  return {
    register: (handle, session) => {
      sessions.get(handle)?.close()
      sessions.set(handle, session)
    },
    revoke: (handle) => {
      sessions.get(handle)?.close()
      sessions.delete(handle)
    },
    get: (handle) => sessions.get(handle),
  }
}

/** Handle a `postMessage` to the SW: handshake, register, or revoke. Exported for unit testing. */
export function handleMessage(registry: PlaybackRegistry, data: unknown, ports: readonly MessagePort[]): void {
  const msg = data as PageToSwMessage
  if (!msg || typeof msg !== 'object') return
  if (msg.type === 'veyq-handshake') {
    ports[0]?.postMessage({ type: 'veyq-handshake-ack', version: PROTOCOL_VERSION })
  } else if (msg.type === 'veyq-register-playback') {
    if (ports[0]) {
      registry.register(msg.handle, new PlaybackSession(msg.chunkCount, msg.byteSize, ports[0], msg.mediaType))
    }
  } else if (msg.type === 'veyq-revoke-playback') {
    registry.revoke(msg.handle)
  }
}

/** Resolve a `/__media/<handle>` request to a Response, or null if the URL is not ours (caller falls through). */
export function handleFetch(registry: PlaybackRegistry, origin: string, request: Request): Response | null {
  const url = new URL(request.url)
  if (url.origin !== origin || !url.pathname.startsWith(MEDIA_PATH_PREFIX)) return null
  const handle = decodeURIComponent(url.pathname.slice(MEDIA_PATH_PREFIX.length))
  const session = registry.get(handle)
  if (!session) return new Response(null, { status: 404 })
  return buildRangeResponse(session, request.headers.get('Range'))
}

// --- the service-worker global scope (declared locally; see the file header) --

type SwLifecycleEvent = { waitUntil(p: Promise<unknown>): void }
type SwMessageEvent = { data: unknown; ports: readonly MessagePort[] }
type SwFetchEvent = { request: Request; respondWith(r: Response | Promise<Response>): void }
interface MediaSwScope {
  location: { origin: string }
  skipWaiting(): Promise<void>
  clients: { claim(): Promise<void> }
  addEventListener(type: 'install' | 'activate', listener: (e: SwLifecycleEvent) => void): void
  addEventListener(type: 'message', listener: (e: SwMessageEvent) => void): void
  addEventListener(type: 'fetch', listener: (e: SwFetchEvent) => void): void
}

/** Wire the SW lifecycle + fetch/message handlers onto a service-worker global scope. */
export function installMediaSw(scope: MediaSwScope): void {
  const registry = makeRegistry()
  scope.addEventListener('install', () => void scope.skipWaiting())
  scope.addEventListener('activate', (e) => e.waitUntil(scope.clients.claim()))
  scope.addEventListener('message', (e) => handleMessage(registry, e.data, e.ports))
  scope.addEventListener('fetch', (e) => {
    const res = handleFetch(registry, scope.location.origin, e.request)
    if (res) e.respondWith(res) // not ours → don't call respondWith, letting the request fall through to network
  })
}

// Self-install only when loaded as a real service worker. Guarded so importing this module in the page or in a
// node test (where `skipWaiting` is absent) is an inert no-op — the exported core above is what tests exercise.
const maybeScope = globalThis as unknown as { skipWaiting?: unknown }
if (typeof maybeScope.skipWaiting === 'function') {
  installMediaSw(globalThis as unknown as MediaSwScope)
}
