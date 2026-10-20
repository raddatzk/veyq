// A blob's sealed metadata on the wire (inbox #397): what core's upload declare sends when `planBlob` is handed a meta,
// recorded at `fetch` — the JSON the real generated clients serialize (their `…ToJSON` drops a field the spec does not
// name, so a body asserted anywhere above `fetch` proves nothing; web/CLAUDE.md). No module of ours is mocked:
// `planBlob`, `reserveAndPump`, `authedReserve`/`authedWindow` (a member) and `guestReserve`/`guestWindow` (a guest on
// a link) all run. What it pins, for both doors:
//   - the declare item carries `wrappedBlobMeta`, standard base64, which opens under the blob's own K_blob (unwrapped
//     from the declared `wrappedBlobKey` under the owner's key) to the facts handed in;
//   - no request body — the declare, the windows, the PUT frames — carries the plaintext name or type;
//   - a plan without meta declares no `wrappedBlobMeta` key at all (the B0 Memories snapshots rely on it).
import { describe, it, expect, vi, beforeAll, afterEach } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { fromB64std } from '@veyq/crypto/base64'
import { planBlob, reserveAndPump, authedReserve, authedWindow, CHUNK_SIZE } from './transport'
import { guestReserve, guestWindow } from '../sharing/guestBlob'
import { session } from '../../app/session'

const SCOPE = '33333333-3333-4333-8333-333333333335'
const SHARE = 'Sh4reIdSh4reIdSh4reIdSh4reIdSh4reIdSh4re-_5'
const OWNER = { ownerId: '5c09e000-0000-4000-8000-0000000000c1', role: 'FILE_CONTENT' }
const NAME = 'Steuererklärung Geheim.pdf'
const TYPE = 'application/x-veyq-secret-type'
const MODIFIED = 1_700_000_000_000
const ownerKey = v1.randomBytes(32)

beforeAll(() => { session.accessToken = 'access-token' })
afterEach(() => vi.unstubAllGlobals())

type Sent = { method: string; path: string; text: string; json?: Record<string, unknown> }
const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })

/** Both control planes and the store: each start signs one PUT per chunk, every body is kept as sent. */
function fakeServer(): Sent[] {
  const sent: Sent[] = []
  let n = 0
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input), 'http://api.test')
    const method = init?.method ?? 'GET'
    if (url.host === 'store.test') {
      sent.push({ method, path: url.pathname, text: new TextDecoder('latin1').decode(init!.body as Uint8Array) })
      return new Response(null, { status: 200 })
    }
    const text = init?.body === undefined ? '' : String(init.body)
    const body = text ? JSON.parse(text) as Record<string, unknown> : undefined
    sent.push({ method, path: url.pathname, text, json: body })
    if (url.pathname.endsWith('/blob-uploads-batch') || url.pathname.endsWith('/blob-uploads')) {
      const items = body!.items as { chunkCount: number }[]
      return json({ results: items.map((it) => {
        const uploadId = `00000000-0000-4000-8000-00000000000${++n}`
        return { uploadId, presignedPuts: Array.from({ length: it.chunkCount }, (_, i) => ({ index: i, url: `https://store.test/${uploadId}/${i}` })), error: null }
      }) })
    }
    throw new Error(`unexpected request ${method} ${url}`)
  }))
  return sent
}

const file = () => new File([new Uint8Array(CHUNK_SIZE + 3).fill(7)], NAME, { type: TYPE, lastModified: MODIFIED })
const META = { type: TYPE, name: NAME, modifiedAt: MODIFIED }

/** The one declare item of a start, its meta opened under the K_blob the declared wrap unwraps to. */
function declared(sent: Sent[]) {
  const start = sent.find((s) => s.json && Array.isArray(s.json.items))!
  const [item] = start.json!.items as Record<string, unknown>[]
  const kBlob = v1.open(
    { purpose: 'blob-key', scopeId: SCOPE, ownerId: OWNER.ownerId, blobId: item.blobId as string, role: OWNER.role },
    ownerKey, fromB64std(item.wrappedBlobKey as string),
  )
  return { start, item, kBlob }
}

/** No body that left the client names the file or its type in plaintext (JSON-escaped forms included). */
function expectNoPlaintext(sent: Sent[]) {
  for (const s of sent) {
    for (const needle of [NAME, JSON.stringify(NAME).slice(1, -1), 'Steuererkl', TYPE, 'x-veyq-secret-type']) {
      expect(s.text, `${s.method} ${s.path}`).not.toContain(needle)
    }
  }
}

describe('a member\'s declare (startBlobUploads)', () => {
  it('carries the meta sealed under the blob\'s own key, and nothing of it in plaintext', async () => {
    const sent = fakeServer()
    const plan = planBlob(SCOPE, OWNER, ownerKey, file(), META)
    await reserveAndPump([plan], (d) => authedReserve(SCOPE, d), authedWindow(SCOPE))

    const { start, item, kBlob } = declared(sent)
    expect(start.path).toBe(`/api/scopes/${SCOPE}/blob-uploads-batch`)
    expect(item.wrappedBlobMeta).toMatch(/^[A-Za-z0-9+/]+={0,2}$/)
    expect(v1.openBlobMeta(kBlob, fromB64std(item.wrappedBlobMeta as string))).toEqual({ v: 1, ...META })
    expect(() => v1.openBlobMeta(ownerKey, fromB64std(item.wrappedBlobMeta as string)), 'under K_blob, not the owner key').toThrow(v1.OpenRefused)
    expect(sent.filter((s) => s.method === 'PUT')).toHaveLength(2)
    expectNoPlaintext(sent)
  })

  it('a plan without meta declares no wrappedBlobMeta key at all', async () => {
    const sent = fakeServer()
    await reserveAndPump([planBlob(SCOPE, OWNER, ownerKey, file())], (d) => authedReserve(SCOPE, d), authedWindow(SCOPE))
    const { item } = declared(sent)
    expect(Object.keys(item).sort()).toEqual(['blobId', 'byteSize', 'chunkCount', 'ownerId', 'role', 'wrappedBlobKey'])
    expect(declared(sent).start.text).not.toContain('wrappedBlobMeta')
  })
})

describe('a guest\'s declare on a link (startShareBlobUploads)', () => {
  it('carries the meta too — core\'s guest transport forwards the declare as planned', async () => {
    const sent = fakeServer()
    const to = { shareId: SHARE, contributorToken: 'tok-guest', nodeId: OWNER.ownerId }
    await reserveAndPump([planBlob(SCOPE, OWNER, ownerKey, file(), META)], (d) => guestReserve(to, d), guestWindow(to))

    const { start, item, kBlob } = declared(sent)
    expect(start.path).toBe(`/api/shares/${SHARE}/blob-uploads`)
    expect(v1.openBlobMeta(kBlob, fromB64std(item.wrappedBlobMeta as string))).toEqual({ v: 1, ...META })
    expectNoPlaintext(sent)
  })
})
