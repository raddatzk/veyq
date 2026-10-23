// @vitest-environment happy-dom
// A drop-box guest on the wire (cr-files-application F3, ADR 0080 decision 11), recorded at `fetch` against a fake
// server, with REAL crypto and no module of ours mocked. What it pins:
//   - naming: `createShareIdentity` carries a `K_up` wrap that the drop box's member opens through the drop private key
//     (sealed under the drop box's key), and the name sealed under that `K_up`; the session keeps `K_up`;
//   - the folder: `ensureShareDropFolder` sends a name and a content key wrap that open under `K_up` and under nothing
//     the drop box holds; the guest opens the STORED wrap the server answers (a second call's), not the one it sent;
//   - the upload: the guest job creates the file under its folder (`parentId`) with the file's key wrapped under the
//     folder's key `K_F` — the member's chain drop key → K_up → K_F → the file key → K_blob, under which the file's
//     type, name and modified time ride the declare (`wrappedBlobMeta`, inbox #397), never in plaintext.
import { describe, it, expect, vi, beforeAll, afterEach } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { dropKeysForShare, openDropKey, openIdentityKey } from '../../core/sharing/dropKey'
import { openContentKey, openName, sealName, wrapContentKey } from '../../core/node/tree'
import { identityNameContext } from '../../core/sharing/guestIdentity'
import { registry } from '../../core/applications/registry'
import type { PublicShareHead } from '../../core/sharing/resolvePublicShare'
import { fileNameSlug } from '../../shared/naming'
import { dropGuestContext, dropGuestJob, ensureDropFolder, forgetDropGuest, loadDropGuest, nameDropGuest, saveDropGuest } from './dropBox'
import { guestFileUploadJob } from './shareUpload'
import { FILE_KIND } from './kinds'

const SCOPE = '33333333-3333-4333-8333-333333333334'
const SHARE = 'Dr0pB0xDr0pB0xDr0pB0xDr0pB0xDr0pB0xDr0pB0x_'
const BOX = '5c09e000-0000-4000-8000-0000000000d0'
const STORED = '5c09e000-0000-4000-8000-0000000000d1'
const boxKey = v1.randomBytes(32)
const kShare = v1.randomBytes(32)
const drop = dropKeysForShare(SCOPE, BOX, boxKey, kShare, null)
const head = { shareId: SHARE, scopeId: SCOPE, kShare, name: 'Einsendungen', share: { nodeId: BOX, sealedDropPublicKey: drop.sealedDropPublicKey } } as unknown as PublicShareHead

type Req = { method: string; path: string; json?: Record<string, unknown> }
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

beforeAll(() => {
  if (!registry.kind(FILE_KIND).supported) registry.registerKind({ kind: FILE_KIND, icon: 'file', shareable: false, slug: fileNameSlug })
})
afterEach(() => vi.unstubAllGlobals())

/** A fake server: identities, the drop folder (answering `stored` once one exists), guest creates and the guest upload. */
function fakeServer(stored?: () => { nodeId: string; wrappedContentKey: string }) {
  const requests: Req[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input), 'http://api.test')
    const method = init?.method ?? 'GET'
    if (url.host === 'store.test') return new Response(null, { status: 200 })
    const body = init?.body === undefined ? undefined : JSON.parse(String(init.body)) as Record<string, unknown>
    requests.push({ method, path: url.pathname, json: body })
    const path = url.pathname
    if (path === `/api/shares/${SHARE}/identities`) return json({ identityId: body!.identityId, contributorToken: 'tok-drop' }, 201)
    if (path === `/api/shares/${SHARE}/files/drop-folder`) {
      const s = stored?.()
      return json({ nodeId: s?.nodeId ?? body!.nodeId, epoch: 0, placementRevision: 0, wrappedContentKey: s?.wrappedContentKey ?? body!.wrappedContentKey, created: !s })
    }
    if (path === `/api/shares/${SHARE}/nodes`) return json({ nodeId: body!.nodeId }, 201)
    if (path === `/api/shares/${SHARE}/blob-uploads`) {
      const items = body!.items as { blobId: string; chunkCount: number }[]
      return json({ results: items.map((it, i) => ({ uploadId: `00000000-0000-4000-8000-00000000020${i}`, presignedPuts: Array.from({ length: it.chunkCount }, (_, c) => ({ index: c, url: `https://store.test/${i}/${c}` })), error: null })) })
    }
    if (/\/files\/[^/]+\/content$/.test(path)) {
      const item = requests.find((r) => r.path.endsWith('/blob-uploads'))!.json!.items as { blobId: string; chunkCount: number; byteSize: number; wrappedBlobKey: string }[]
      return json({ ...item[0], manifest: null })
    }
    return new Response(JSON.stringify({ code: 'unexpected' }), { status: 500 })
  }))
  return requests
}

const at = (nodeId: string) => ({ scopeId: SCOPE, nodeId, epoch: 0, placementRevision: 0 })

describe('a drop-box guest on the wire', () => {
  it('names itself with a K_up the member opens through the drop key, and keeps it on the device', async () => {
    const requests = fakeServer()
    const session = await nameDropGuest(head, { name: 'Adelgunde' })
    const sent = requests.find((r) => r.path.endsWith('/identities'))!.json!
    const kUp = openIdentityKey(SCOPE, BOX, openDropKey(SCOPE, BOX, boxKey, drop.sealedDropPrivateKey!), String(sent.identityId), String(sent.wrappedIdentityKey))
    expect(b64std(kUp)).toBe(session.identityKey)
    expect(new TextDecoder().decode(v1.openField(identityNameContext(SCOPE, SHARE, String(sent.identityId)), kUp, fromB64std(String(sent.sealedDisplayName))))).toBe('Adelgunde')
    // the name stays on the device for the page ("Sending as …", the folder's name at the first file) — never on the wire
    expect(session.displayName).toBe('Adelgunde')
    expect(requests.map((r) => JSON.stringify(r.json ?? {})).join('')).not.toContain('Adelgunde')
    saveDropGuest(SHARE, session)
    expect(loadDropGuest(SHARE)?.identityKey).toBe(session.identityKey)
    // "Not you?" forgets it on this device
    forgetDropGuest(SHARE)
    expect(loadDropGuest(SHARE)).toBeNull()
  })

  it('seals its folder under K_up, opens the stored wrap, and uploads a file into the folder under K_F', async () => {
    fakeServer()
    const session = await nameDropGuest(head, { name: 'Adelgunde' })
    const kUp = fromB64std(session.identityKey)
    // the server already holds this identity's folder: the guest opens THAT wrap, not the one it sent
    const storedKey = v1.randomBytes(32)
    const stored = { nodeId: STORED, wrappedContentKey: wrapContentKey(kUp, at(STORED), storedKey) }
    const requests = fakeServer(() => stored)
    const folder = await ensureDropFolder(head, session, 'Adelgunde')
    expect(folder).toMatchObject({ nodeId: STORED, created: false })
    expect(folder.key).toEqual(storedKey)
    const sent = requests.find((r) => r.path.endsWith('/drop-folder'))!.json!
    const sentAt = at(String(sent.nodeId))
    expect(openName(kUp, sentAt, String(sent.encryptedName))).toBe('Adelgunde')
    expect(() => openName(boxKey, sentAt, String(sent.encryptedName))).toThrow() // never under the drop box's key
    expect(openContentKey(SCOPE, kUp, { id: sentAt.nodeId, epoch: 0, placementRevision: 0, wrappedContentKey: String(sent.wrappedContentKey) })).toHaveLength(32)
    expect(sent.contributorToken).toBe('tok-drop')

    const file = new File([new Uint8Array(300).map((_, i) => i % 251)], 'Bericht.pdf', { type: 'application/pdf' })
    const result = await guestFileUploadJob({ ctx: dropGuestContext(head, session, folder), parentId: folder.nodeId }, file)
      .run({ onProgress: () => {}, signal: new AbortController().signal })
    const create = requests.find((r) => r.path === `/api/shares/${SHARE}/nodes`)!.json!
    expect(create).toMatchObject({ parentId: STORED, kind: FILE_KIND, nodeId: result.nodeId, epoch: 0 })
    expect(openName(storedKey, at(result.nodeId), String(create.encryptedName))).toBe('Bericht.pdf')
    const fileKey = openContentKey(SCOPE, storedKey, { id: result.nodeId, epoch: 0, placementRevision: 0, wrappedContentKey: String(create.wrappedContentKey) })
    expect(fileKey).toHaveLength(32)
    expect(requests.find((r) => r.path.endsWith('/blob-uploads'))!.json!.nodeId).toBe(result.nodeId)
    // the file's meta rides the declare, sealed under the blob's own K_blob — down the chain the member opens (inbox #397)
    const [item] = requests.find((r) => r.path.endsWith('/blob-uploads'))!.json!.items as { blobId: string; wrappedBlobKey: string; wrappedBlobMeta: string }[]
    const kBlob = v1.open({ purpose: 'blob-key', scopeId: SCOPE, ownerId: result.nodeId, blobId: item.blobId, role: 'FILE_CONTENT' }, fileKey, fromB64std(item.wrappedBlobKey))
    expect(v1.openBlobMeta(kBlob, fromB64std(item.wrappedBlobMeta))).toEqual({ v: 1, type: 'application/pdf', name: 'Bericht.pdf', modifiedAt: file.lastModified })
    expect(requests.map((r) => JSON.stringify(r.json ?? {})).join('')).not.toContain('Bericht.pdf')
    // control: a seal under the drop box key is what the member's own nodes use, and it is not what the guest sent
    expect(sealName(boxKey, at(STORED), 'x')).not.toBe(String(sent.encryptedName))
  })

  // A refused upload into a full drop box leaves nothing behind either: the page's wrapper keeps the job's take-back, so
  // the queue's `abandon` still reaches `abandonShareNode` (shareUpload.wire.test.ts holds what it sends).
  it("the page's job wrapper keeps a given-up job's take-back", async () => {
    const abandon = vi.fn(async () => {})
    const job = { name: 'a.pdf', sizeBytes: 1, run: vi.fn(), abandon, extra: {} }
    await dropGuestJob(job, { gone: false }).abandon?.()
    expect(abandon).toHaveBeenCalledTimes(1)
  })
})
