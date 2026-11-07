// A Pot's receipts on the wire (uc.expenses.attach-receipts, ADR 0108 decision 7) — core's blob service's (ADR 0112),
// recorded at `fetch` against a fake control plane and store — no module of ours mocked: core's `planBlob`,
// `reserveAndPump`, the authed and guest reserve and window, the service's transport, the presign and the frame open all
// run. What it pins:
//   - a member's upload: start → PUTs → `completeApplicationBlob`, the declare in the role `EXPENSES_RECEIPT` with owner = the
//     Pot, its `K_blob` wrap opening under the Pot's key bound to (blob, Pot, role) and nowhere else, the file's type and
//     name sealed in its meta (no request names them in plaintext), and the completion carrying ids and the epoch only;
//   - a guest's: the link's start with the guest's token and the Pot as the upload's node, the link's completion with the
//     token, the password token on each, and no bearer;
//   - the read: the first page conditional on its tag (`304` → nothing new), later pages `after` the cursor;
//   - the bytes read back open under the Pot's key — and a row the server moved here from another Pot does not.
import { describe, it, expect, vi, afterEach } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { fromB64std } from '@veyq/crypto/base64'
import { blobKeyContext } from '../../core/blob/keys'
import { potReceiptSource, readMemberReceipts, RECEIPT_ROLE, receiptChunks, readAll, uploadReceipt, type HeldReceipt } from './receipts'
import type { PotTarget } from './domain/potApi'
import { memberAccessOf } from '../../test/models'

const SCOPE = '22222222-2222-4222-8222-222222222222'
const POT = '0e000000-0000-4000-b000-000000000001'
const OTHER_POT = '0e000000-0000-4000-b000-000000000002'
const SHARE = 'Sh4reIdSh4reIdSh4reIdSh4reIdSh4reIdSh4re-_8'
const ENTRY = '0e000000-0000-4000-9000-000000000001'
const RECEIPT = '0e000000-0000-4000-a000-000000000001'
const potKey = v1.randomBytes(32)
const MEMBER: PotTarget = { kind: 'member', scopeId: SCOPE, nodeId: POT }
const GUEST: PotTarget = { kind: 'guest', scopeId: SCOPE, nodeId: POT, shareId: SHARE, apiBase: '', contributorToken: 'dG9rZW4=', pwToken: 'cHc=' }
const NAME = 'Hotel-Rechnung.pdf'

afterEach(() => vi.unstubAllGlobals())

type Req = { method: string; path: string; headers: Record<string, string>; json?: Record<string, unknown>; search: URLSearchParams }
const json = (body: unknown, status = 200, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } })

/** The control plane and the store: what was declared, what was PUT, and each completion answered as the server would. */
function fakeServer() {
  const requests: Req[] = []
  const declared = new Map<string, Record<string, unknown>>()
  const stored = new Map<string, Uint8Array[]>()
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input), 'http://api.test')
    const method = init?.method ?? 'GET'
    if (url.host === 'store.test') {
      const [, uploadId, index] = url.pathname.split('/')
      if (method === 'PUT') {
        const frame = new Uint8Array(await new Response(init!.body as BodyInit).arrayBuffer())
        const frames = stored.get(uploadId!) ?? []
        frames[Number(index)] = frame
        stored.set(uploadId!, frames)
        return new Response(null, { status: 200 })
      }
      return new Response(stored.get(uploadId!)![Number(index)] as BodyInit, { status: 200 })
    }
    const headers = Object.fromEntries(new Headers(init?.headers).entries())
    const body = init?.body === undefined ? undefined : (JSON.parse(String(init.body)) as Record<string, unknown>)
    requests.push({ method, path: url.pathname, headers, json: body, search: url.searchParams })
    const start = (items: Record<string, unknown>[]) =>
      json({ results: items.map((it, i) => {
        const uploadId = `00000000-0000-4000-8000-00000000010${declared.size + i + 1}`
        declared.set(uploadId, it)
        return { uploadId, presignedPuts: Array.from({ length: it.chunkCount as number }, (_, c) => ({ index: c, url: `https://store.test/${uploadId}/${c}` })), error: null }
      }) })
    if (method === 'POST' && (url.pathname === `/api/scopes/${SCOPE}/blob-uploads-batch` || url.pathname === `/api/shares/${SHARE}/blob-uploads`)) {
      return start(body!.items as Record<string, unknown>[])
    }
    if (method === 'POST' && url.pathname.endsWith(`/nodes/${POT}/application-blobs`)) {
      const d = declared.get(body!.uploadId as string)!
      return json({
        id: body!.id, ownerId: body!.ownerId, role: body!.role, blobId: d.blobId, chunkCount: d.chunkCount, byteSize: d.byteSize,
        wrappedBlobKey: d.wrappedBlobKey, wrappedBlobMeta: d.wrappedBlobMeta ?? null, writer: 'user:u-anna', createdAt: '2026-10-25T10:00:00Z',
        keptAt: null, releasedAt: null, purgeAfter: null, blockedByOperator: false,
      })
    }
    if (method === 'POST' && url.pathname === `/api/scopes/${SCOPE}/blob-downloads-batch`) {
      const items = body!.items as { blobId: string }[]
      return json({ results: items.map(({ blobId }) => {
        const uploadId = [...declared].find(([, d]) => d.blobId === blobId)![0]
        return { blobId, presignedGets: stored.get(uploadId)!.map((_, index) => ({ index, url: `https://store.test/${uploadId}/${index}` })), error: null }
      }) })
    }
    throw new Error(`unexpected ${method} ${url.pathname}`)
  }))
  return { requests, declared }
}

const fileOf = (n: number) => new File([new Uint8Array(n).map((_, i) => i % 251)], NAME, { type: 'application/pdf', lastModified: 1_700_000_000_000 })

describe('a member uploads a receipt', () => {
  it('declares it in the receipt role under the Pot, sealed under the Pot\'s key, and completes it against the entry', async () => {
    const { requests } = fakeServer()
    const r = await uploadReceipt(MEMBER, { key: potKey, epoch: 3 }, ENTRY, RECEIPT, fileOf(300_000))
    expect(requests.map((q) => `${q.method} ${q.path}`)).toEqual([
      `POST /api/scopes/${SCOPE}/blob-uploads-batch`,
      `POST /api/scopes/${SCOPE}/nodes/${POT}/application-blobs`,
    ])
    const declare = (requests[0]!.json!.items as Record<string, string>[])[0]!
    expect(declare).toMatchObject({ role: RECEIPT_ROLE, ownerId: POT })
    // the wrap opens under the Pot's key bound to this blob, this Pot and the receipt role — and not as another Pot's
    const kBlob = v1.open(blobKeyContext(SCOPE, { blobId: declare.blobId!, ownerId: POT, role: RECEIPT_ROLE }), potKey, fromB64std(declare.wrappedBlobKey!))
    expect(() => v1.open(blobKeyContext(SCOPE, { blobId: declare.blobId!, ownerId: OTHER_POT, role: RECEIPT_ROLE }), potKey, fromB64std(declare.wrappedBlobKey!))).toThrow()
    expect(v1.openBlobMeta(kBlob, fromB64std(declare.wrappedBlobMeta!))).toMatchObject({ type: 'application/pdf', name: NAME })
    expect(requests[1]!.json).toEqual({ nodeEpoch: 3, ownerId: ENTRY, id: RECEIPT, role: RECEIPT_ROLE, uploadId: expect.any(String) })
    // no request names the file or its type in plaintext
    expect(JSON.stringify(requests.map((q) => q.json))).not.toMatch(/Hotel|application\/pdf/)
    expect(r).toMatchObject({ receiptId: RECEIPT, entryId: ENTRY, blobId: declare.blobId })
  })

  it('reads its bytes back under the Pot\'s key; a row the server moved here from another Pot does not open', async () => {
    fakeServer()
    const plain = new Uint8Array(300_000).map((_, i) => i % 251)
    const r = await uploadReceipt(MEMBER, { key: potKey, epoch: 3 }, ENTRY, RECEIPT, fileOf(300_000))
    const source = potReceiptSource(memberAccessOf({ scopeId: SCOPE }), SCOPE, POT, potKey, r)
    expect(source.name).toBe(NAME)
    expect(source.size).toBe(300_000)
    expect(await source.read()).toEqual(plain)
    expect(() => receiptChunks(SCOPE, potKey, OTHER_POT, r, async () => [])).toThrow()
  })
})

describe('a guest uploads a receipt', () => {
  it('starts and completes it through the link, with its token and the Pot as the upload\'s node, and no bearer', async () => {
    const { requests } = fakeServer()
    await uploadReceipt(GUEST, { key: potKey, epoch: 0 }, ENTRY, RECEIPT, fileOf(10))
    expect(requests.map((q) => `${q.method} ${q.path}`)).toEqual([`POST /api/shares/${SHARE}/blob-uploads`, `POST /api/shares/${SHARE}/nodes/${POT}/application-blobs`])
    expect(requests[0]!.json).toMatchObject({ contributorToken: 'dG9rZW4=', nodeId: POT })
    expect((requests[0]!.json!.items as Record<string, string>[])[0]).toMatchObject({ role: RECEIPT_ROLE, ownerId: POT })
    expect(requests[1]!.json).toEqual({ nodeEpoch: 0, contributorToken: 'dG9rZW4=', ownerId: ENTRY, id: RECEIPT, role: RECEIPT_ROLE, uploadId: expect.any(String) })
    for (const q of requests) {
      expect(q.headers['x-share-password']).toBe('cHc=')
      expect(q.headers.authorization).toBeUndefined()
    }
  })
})

describe('a guest of another instance\'s link (a bookmark\'s page, ADR 0121)', () => {
  it('starts and completes the upload at that instance\'s origin', async () => {
    const away = 'https://photos.example.org'
    fakeServer()
    await uploadReceipt({ ...GUEST, apiBase: away }, { key: potKey, epoch: 0 }, ENTRY, RECEIPT, fileOf(10))
    const api = vi.mocked(fetch).mock.calls.map(([input]) => String(input)).filter((u) => !u.startsWith('https://store.test/'))
    expect(api).toEqual([`${away}/api/shares/${SHARE}/blob-uploads`, `${away}/api/shares/${SHARE}/nodes/${POT}/application-blobs`])
  })
})

describe("a member's read of the receipts", () => {
  const row = (id: string): Record<string, unknown> => ({
    id, ownerId: ENTRY, role: RECEIPT_ROLE, blobId: id, chunkCount: 1, byteSize: 40, wrappedBlobKey: 'AAAA', wrappedBlobMeta: null, writer: 'user:u',
    createdAt: '2026-10-25T10:00:00Z', keptAt: null, releasedAt: null, purgeAfter: '2026-11-25T10:00:00Z', blockedByOperator: true,
  })
  it('asks its first page on its tag, pages after the cursor, and an unchanged listing is nothing new', async () => {
    const calls: Req[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input), 'http://api.test')
      const headers = Object.fromEntries(new Headers(init?.headers).entries())
      calls.push({ method: 'GET', path: url.pathname, headers, search: url.searchParams })
      if (headers['if-none-match'] === '"same"') return new Response(null, { status: 304 })
      return url.searchParams.get('after') === 'r1'
        ? json({ blobs: [row('r2')], more: false, cursor: 'r2' })
        : json({ blobs: [row('r1')], more: true, cursor: 'r1' }, 200, { ETag: '"tag"' })
    }))
    const read = await readMemberReceipts(SCOPE, POT, '"old"')
    expect(calls).toHaveLength(2)
    expect(calls[0]!.path).toBe(`/api/scopes/${SCOPE}/nodes/${POT}/application-blobs`)
    expect(calls[0]!.headers['if-none-match']).toBe('"old"')
    expect(calls[1]!.headers['if-none-match']).toBeUndefined()
    expect(calls[1]!.search.get('after')).toBe('r1')
    expect(read!.etag).toBe('"tag"')
    const [first] = read!.receipts as HeldReceipt[]
    expect(first).toMatchObject({ receiptId: 'r1', blockedByOperator: true, purgeAfter: new Date('2026-11-25T10:00:00Z'), droppedAt: null })
    expect(await readMemberReceipts(SCOPE, POT, '"same"')).toBeNull()
  })

  it('reads a blob released by name as a dropped receipt, and one released with its entry as none', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({
      blobs: [
        { ...row('dropped'), keptAt: null, releasedAt: '2026-10-26T10:00:00Z' },
        { ...row('deleted'), keptAt: '2026-10-25T10:00:00Z', releasedAt: '2026-10-26T10:00:00Z' },
        { ...row('kept'), keptAt: '2026-10-25T10:00:00Z' },
        { ...row('other'), role: 'SOME_OTHER_ROLE' },
      ],
      more: false,
      cursor: null,
    })))
    const read = await readMemberReceipts(SCOPE, POT)
    expect(read!.receipts.map((r) => [r.receiptId, r.droppedAt])).toEqual([
      ['dropped', new Date('2026-10-26T10:00:00Z')], ['deleted', null], ['kept', null],
    ])
  })

  it('reads a whole provider in order', async () => {
    const chunks = [new Uint8Array([1, 2]), new Uint8Array([3])]
    expect(await readAll({ chunkCount: 2, getPlaintextChunk: async (i: number) => chunks[i]! })).toEqual(new Uint8Array([1, 2, 3]))
  })
})
