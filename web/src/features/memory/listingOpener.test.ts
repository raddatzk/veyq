// The listing opener (ADR 0105 decision 3, inbox #489): the Worker's handler opens what the page would open, holds a
// Memory's keys only while the Memory is open, and refuses everything for a Memory it was told to forget; the page's
// front door uses the Worker where there is one and finishes on the page thread where there is none or it fails. The
// real Worker never runs here (no module Worker in this env): the handler is driven directly, and the front door
// against a stub Worker that runs the REAL handler on a structured clone of each message — so the protocol under test
// is the one the Worker speaks. Which Worker exists is set per case with `vi.stubGlobal`, never assumed.
//
// Bites: drop the handler's close (keep the keys) and "refused after close" goes red; make the front door reject where
// it has no Worker instead of opening on the page, and the fall-back cases go red.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'
import { createListingOpener, MEMORY_NOT_OPEN, type OpenItem, type OpenerRequest } from './listingOpenerCore'
import { mediaMetaContext, openMediaMeta } from './mediaOpen'
import { openFrames } from '../../core/blob/keys'

const SCOPE = '5c0e0000-0000-4000-8000-000000000001'
const M1 = '11111111-1111-4111-8111-111111111111'
const M2 = '22222222-2222-4222-8222-222222222222'
const sealMeta = (key: Uint8Array, mediaId: string, meta: object) =>
  b64std(v1.sealField(mediaMetaContext(SCOPE, mediaId), key, new TextEncoder().encode(JSON.stringify(meta))))
const sealedFrames = (blobKey: Uint8Array, parts: number[][]) =>
  parts.map((p, i) => v1.sealFrame(blobKey, i, parts.length, new Uint8Array(p), v1.randomBytes(12)))
const item = (mediaId: string, wrappedMeta: string, keyId = 'node'): OpenItem => ({
  mediaId,
  keyId,
  openMeta: true,
  wrappedMeta,
})

describe('the Worker handler', () => {
  it('opens a listed tier exactly as the page does, and only what was asked', () => {
    const key = v1.randomBytes(32)
    const sealed = sealMeta(key, M1, { v: 1, w: 4000, h: 3000, capturedAt: 1700000000000, gpsLat: 52.5, gpsLng: 13.4 })
    const opener = createListingOpener()
    opener.handle({ id: 1, op: 'open-memory', memory: 7, keys: [['node', key]] })
    const { response } = opener.handle({
      id: 2, op: 'open-items', memory: 7, scopeId: SCOPE,
      items: [item(M1, sealed), { mediaId: M2, keyId: 'node', openMeta: false }],
    })
    expect(response).toEqual({
      id: 2, ok: true,
      items: [{ mediaId: M1, meta: openMediaMeta(SCOPE, key, M1, sealed) }, { mediaId: M2 }],
    })
    expect(response.ok && response.items?.[0].meta?.w).toBe(4000)
  })

  it('opens frames under the K_blob it is handed, and answers a frame that does not open as a source failure', () => {
    const blobKey = v1.randomBytes(32)
    const frames = sealedFrames(blobKey, [[1, 2, 3], [4, 5]])
    const opener = createListingOpener()
    opener.handle({ id: 1, op: 'open-memory', memory: 1, keys: [] })
    const ok = opener.handle({ id: 2, op: 'open-frames', memory: 1, blobKey, frames })
    expect(ok.response).toEqual({ id: 2, ok: true, bytes: new Uint8Array([1, 2, 3, 4, 5]) })
    expect(ok.transfer).toHaveLength(1) // the plaintext's buffer moves to the page, it is not copied
    const bad = opener.handle({ id: 3, op: 'open-frames', memory: 1, blobKey: v1.randomBytes(32), frames })
    expect(bad.response).toMatchObject({ id: 3, ok: false, kind: 'source' })
  })

  it('forgets a Memory on close: its keys are zeroed and every later request for it is refused', () => {
    const key = v1.randomBytes(32)
    const held = key.slice()
    const opener = createListingOpener()
    opener.handle({ id: 1, op: 'open-memory', memory: 3, keys: [['node', held]] })
    opener.handle({ id: 2, op: 'open-memory', memory: 4, keys: [['node', v1.randomBytes(32)]] })
    expect(opener.held()).toEqual([3, 4])

    opener.handle({ id: 3, op: 'close-memory', memory: 3 })

    expect(opener.held()).toEqual([4]) // the other open Memory is untouched
    expect([...held].every((b) => b === 0)).toBe(true)
    const items = opener.handle({ id: 4, op: 'open-items', memory: 3, scopeId: SCOPE, items: [item(M1, sealMeta(key, M1, { v: 1 }))] })
    const frames = opener.handle({ id: 5, op: 'open-frames', memory: 3, blobKey: v1.randomBytes(32), frames: [] })
    for (const { response } of [items, frames]) expect(response).toEqual({ id: response.id, ok: false, kind: 'refused', error: MEMORY_NOT_OPEN })
  })

  it('refuses a Memory it never opened, and a key the page never sent is no answer under another key', () => {
    const opener = createListingOpener()
    expect(opener.handle({ id: 1, op: 'open-items', memory: 9, scopeId: SCOPE, items: [] }).response).toMatchObject({ kind: 'refused' })
    opener.handle({ id: 2, op: 'open-memory', memory: 9, keys: [['node', v1.randomBytes(32)]] })
    expect(opener.handle({ id: 3, op: 'open-items', memory: 9, scopeId: SCOPE, items: [item(M1, 'x', 'uploader-1')] }).response)
      .toMatchObject({ ok: false, kind: 'source', error: 'key_not_sent' })
  })
})

type Listener = (e: unknown) => void

/** A stub Worker that runs the REAL handler on a structured clone of each message, or fails as asked. */
function stubWorker(mode: 'real' | 'die' | 'throw-construct' = 'real') {
  const stats = { constructed: 0, posted: [] as OpenerRequest[] }
  const opener = createListingOpener()
  class StubWorker {
    private handlers: Record<string, Listener[]> = {}
    constructor() {
      if (mode === 'throw-construct') throw new Error('blocked by CSP')
      stats.constructed += 1
    }
    addEventListener(type: string, cb: Listener): void {
      ;(this.handlers[type] ??= []).push(cb)
    }
    postMessage(msg: OpenerRequest): void {
      stats.posted.push(msg)
      const copy = structuredClone(msg)
      queueMicrotask(() => {
        if (mode === 'die') this.handlers['error']?.forEach((cb) => cb({}))
        else this.handlers['message']?.forEach((cb) => cb({ data: opener.handle(copy).response }))
      })
    }
    terminate(): void {}
  }
  vi.stubGlobal('Worker', StubWorker as unknown as typeof Worker)
  return stats
}

async function freshFrontDoor() {
  vi.resetModules() // the Worker and its latch are module-level: each case starts with neither
  return import('./listingOpener')
}

beforeEach(() => vi.resetModules())
afterEach(() => {
  vi.unstubAllGlobals()
  vi.resetModules()
})

describe('the page front door', () => {
  it('a Worker answers a batch, and the page opens nothing itself', async () => {
    const worker = stubWorker()
    const door = await freshFrontDoor()
    const key = v1.randomBytes(32)
    const memory = door.newMemoryHandle()
    door.openMemory(memory, [['node', key]])
    const onPage = vi.fn(() => [])
    const opened = await door.openItems(memory, SCOPE, [item(M1, sealMeta(key, M1, { v: 1, w: 10, h: 20 }))], onPage)
    expect(opened).toEqual([{ mediaId: M1, meta: expect.objectContaining({ w: 10, h: 20 }) }])
    expect(onPage).not.toHaveBeenCalled() // positive control for the fall-back cases' calls: the counter is not stuck
    expect(worker.posted.map((m) => m.op)).toEqual(['open-memory', 'open-items'])
  })

  it('with no Worker global, a batch and a frame read are opened on the page thread', async () => {
    vi.stubGlobal('Worker', undefined)
    const door = await freshFrontDoor()
    const onPage = vi.fn(() => [{ mediaId: M1, meta: null }])
    expect(await door.openItems(door.newMemoryHandle(), SCOPE, [item(M1, 'x')], onPage)).toEqual([{ mediaId: M1, meta: null }])
    expect(onPage).toHaveBeenCalledTimes(1)
    const blobKey = v1.randomBytes(32)
    expect(await door.openFramesFor(1, blobKey, sealedFrames(blobKey, [[7, 8]]))).toEqual(new Uint8Array([7, 8]))
  })

  it('a Worker that cannot be constructed (CSP, a mis-bundled URL) leaves every open on the page thread', async () => {
    const worker = stubWorker('throw-construct')
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const door = await freshFrontDoor()
    const onPage = vi.fn(() => [])
    await door.openItems(door.newMemoryHandle(), SCOPE, [item(M1, 'x')], onPage)
    expect(onPage).toHaveBeenCalledTimes(1)
    expect(worker.constructed).toBe(0)
    expect(warn).toHaveBeenCalledTimes(1) // said once, so a page-thread gallery is visible in devtools
  })

  it('a Worker that fails finishes the outstanding requests on the page and is not built again', async () => {
    const worker = stubWorker('die')
    vi.spyOn(console, 'warn').mockImplementation(() => {})
    const door = await freshFrontDoor()
    const blobKey = v1.randomBytes(32)
    const onPage = vi.fn(() => [])
    const [, bytes] = await Promise.all([
      door.openItems(door.newMemoryHandle(), SCOPE, [item(M1, 'x')], onPage),
      door.openFramesFor(1, blobKey, sealedFrames(blobKey, [[1]])),
    ])
    expect(onPage).toHaveBeenCalledTimes(1)
    expect(bytes).toEqual(new Uint8Array([1]))
    await door.openItems(door.newMemoryHandle(), SCOPE, [item(M2, 'x')], onPage)
    expect(onPage).toHaveBeenCalledTimes(2)
    expect(worker.constructed).toBe(1)
  })

  it('a frame read after the Memory was closed is refused as stale (AbortError), never opened', async () => {
    stubWorker()
    const door = await freshFrontDoor()
    const memory = door.newMemoryHandle()
    door.openMemory(memory, [])
    const blobKey = v1.randomBytes(32)
    const frames = sealedFrames(blobKey, [[5]])
    expect(await door.openFramesFor(memory, blobKey, frames)).toEqual(openFrames(blobKey, frames))
    door.closeMemory(memory)
    await expect(door.openFramesFor(memory, blobKey, frames)).rejects.toMatchObject({ name: 'AbortError' })
  })
})
