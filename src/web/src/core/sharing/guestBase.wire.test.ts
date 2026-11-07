// @vitest-environment happy-dom
// Every guest read and write of core's goes where the share lives (ADR 0121 decision 5), recorded at `fetch` through the
// real generated clients: a link of another instance - a bookmark's page shows it in place - is asked at that instance's
// origin, with no credential of this one (no Authorization header, no cookie-bearing `credentials`), and a link of this
// origin exactly as before (a relative `/api/...`). Each seam is one row, so a base dropped in one of them is red here.
import { describe, it, expect, vi, afterEach } from 'vitest'

vi.unmock('./guestNoticeRead') // `src/test/setup.ts` stands in for it in every other test: here the read itself is under test

import * as v1 from '@veyq/crypto/v1'
import { b64std, b64url } from '@veyq/crypto/base64'
import { FOLDER_KIND } from '../node/kind'
import { SHARE_KEY } from './shareTree'
import { fetchShareTerms, pollPublicShare, resolvePublicShare, SHARE_MEMORY_NAME, type PublicShareHead } from './resolvePublicShare'
import { abandonShareNodeAsGuest, createShareNodeAsGuest, guestTreeContext, placeShareNodeAsGuest } from './guestTree'
import { nameShareGuest } from './guestIdentity'
import { guestReserve, guestWindow, shareChunkUrls } from './guestBlob'
import { readGuestNotice } from './guestNoticeRead'
import { guestAccess } from './guestAccess'
import { readGuestApplicationBlobs } from '../blob/applicationBlobs'
import { readShareInstance } from './shareInstance'
import { ShareRole, type ShareNode } from '../../api/generated/sharing'
import type { ShareAt } from './shareAt'
import type { BlobDeclare } from '../blob/types'

const AWAY = 'https://photos.example.org'
const SHARE_ID = 'Sh4reIdSh4reIdSh4reIdSh4reIdSh4reIdSh4re-_0'
const SCOPE_ID = '22222222-2222-4222-8222-222222222222'
const ROOT = '6e5a0000-0000-4000-8000-0000000000a0'
const CHILD = '6e5a0000-0000-4000-8000-0000000000a1'
const kShare = v1.randomBytes(32)
const kRoot = v1.randomBytes(32)
const FRAGMENT = b64url(kShare)

afterEach(() => vi.unstubAllGlobals())

type Sent = { url: string; method: string; headers: Headers; credentials: RequestCredentials | undefined }

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

const nodes = (): ShareNode[] => [
  { blockedByOperator: false, id: ROOT, parentId: null, kind: FOLDER_KIND, epoch: 0, scopeEpoch: 0, placementRevision: 0 },
]
/** The served share: unlocked, a folder root whose key opens under `K_share`. */
const served = () => ({
  role: ShareRole.ReadWrite, passwordRequired: false, scopeId: SCOPE_ID, rootKind: FOLDER_KIND, rootEpoch: 0, rootScopeEpoch: 0,
  sealedName: b64std(v1.sealField(SHARE_MEMORY_NAME, kShare, v1.utf8('Fotos'))),
  wrap: b64std(v1.seal(SHARE_KEY, kShare, kRoot)), nodes: nodes(),
})

/** Record every request; answer by path, with a refusal for anything not served (a seam's own error handling is not what
 *  this file is about - where it went is). */
function recordFetch(): Sent[] {
  const sent: Sent[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    sent.push({ url, method: init?.method ?? 'GET', headers: new Headers(init?.headers), credentials: init?.credentials })
    const path = new URL(url, 'http://here.test').pathname
    if (path === `/api/shares/${SHARE_ID}` && (init?.method ?? 'GET') === 'GET') return json(served())
    if (path.endsWith('/terms')) return json({ tenantId: 't', termsRevision: 0 })
    if (path.endsWith('/identities')) return json({ contributorToken: 'tok' }, 201)
    if (path.endsWith('/blob-uploads')) return json({ results: [{ uploadId: 'u', presignedPuts: [], error: null }] })
    if (path.endsWith('/blob-upload-windows')) return json({ results: [{ presignedPuts: [], error: null }] })
    if (path.includes('/blobs/')) return json({ presignedGets: [{ index: 0, url: 'https://store.example/x' }] })
    return json({ code: 'share_not_found' }, 404)
  }))
  return sent
}

/** The head as the resolve built it for `apiBase`. */
async function headAt(apiBase: string): Promise<PublicShareHead> {
  recordFetch()
  const head = await resolvePublicShare(apiBase ? { shareId: SHARE_ID, apiBase } : SHARE_ID, FRAGMENT)
  vi.unstubAllGlobals()
  return head
}

const DECLARE: BlobDeclare = { blobId: 'b', ownerId: ROOT, role: 'X', chunkCount: 1, byteSize: 1, wrappedBlobKey: 'AAAA' }
const at = (apiBase: string): ShareAt => ({ shareId: SHARE_ID, apiBase })
const quiet = (p: Promise<unknown>) => p.then(() => undefined, () => undefined)

/** Each seam, run against the share at `apiBase`. */
const SEAMS: [string, (apiBase: string) => Promise<unknown>][] = [
  ['the resolve', (b) => resolvePublicShare(at(b), FRAGMENT)],
  ['the live poll', (b) => pollPublicShare(at(b), FRAGMENT, undefined, null)],
  ['the terms', (b) => fetchShareTerms(at(b))],
  ['the guest notice', (b) => readGuestNotice(at(b), 'tok')],
  ['a blob\'s presign', (b) => shareChunkUrls(at(b), `blob-${b.length}`)],
  ['an upload\'s reserve', (b) => guestReserve({ shareId: SHARE_ID, apiBase: b, contributorToken: 'tok' }, [DECLARE])],
  ['an upload\'s window', (b) => guestWindow({ shareId: SHARE_ID, apiBase: b, contributorToken: 'tok' })('u', 1)],
  ['an application\'s blob listing', (b) => readGuestApplicationBlobs(at(b), ROOT)],
  ['the abandon of a placeholder', (b) => abandonShareNodeAsGuest({ shareId: SHARE_ID, apiBase: b, contributorToken: 'tok' }, CHILD)],
  ['the instance\'s setup', (b) => readShareInstance(b)],
]

describe('a guest call goes where the share lives', () => {
  it.each(SEAMS)('%s: another instance\'s link is asked at its origin, with no credential of this one', async (_name, run) => {
    const sent = recordFetch()
    await quiet(run(AWAY))
    expect(sent.length).toBeGreaterThan(0)
    for (const s of sent.filter((x) => !x.url.startsWith('https://store.example/'))) {
      expect(s.url.startsWith(`${AWAY}/api/`), s.url).toBe(true)
      expect(s.headers.get('authorization')).toBeNull()
      expect(s.credentials === 'include').toBe(false)
    }
  })

  it.each(SEAMS)('%s: this origin\'s link is asked as before, a relative /api/ path', async (_name, run) => {
    const sent = recordFetch()
    await quiet(run(''))
    expect(sent.length).toBeGreaterThan(0)
    for (const s of sent.filter((x) => !x.url.startsWith('https://store.example/'))) expect(s.url.startsWith('/api/'), s.url).toBe(true)
  })
})

describe('what starts from a head goes where the head was resolved', () => {
  it('the resolve stamps the base on the head and the guest access; this origin\'s head carries none', async () => {
    const away = await headAt(AWAY)
    expect(away.apiBase).toBe(AWAY)
    expect(guestAccess({ head: away, fragmentKey: FRAGMENT, requestGuest: async () => null, reload: async () => {} }).apiBase).toBe(AWAY)
    const here = await headAt('')
    expect('apiBase' in here, 'the share page\'s head is exactly as before').toBe(false)
    expect(guestAccess({ head: here, fragmentKey: FRAGMENT, requestGuest: async () => null, reload: async () => {} }).apiBase).toBe('')
  })

  it('a tree context of a foreign head creates, places and re-resolves there', async () => {
    const head = await headAt(AWAY)
    const ctx = guestTreeContext(head, { contributorToken: 'tok', fragmentKey: FRAGMENT })
    expect(ctx.apiBase).toBe(AWAY)
    const sent = recordFetch()
    await quiet(createShareNodeAsGuest(ctx, { parentId: ROOT, kind: FOLDER_KIND, name: 'Neu' }))
    await quiet(placeShareNodeAsGuest(ctx, { nodeId: CHILD, newParentId: ROOT }))
    await quiet(ctx.reresolve())
    expect(sent.map((s) => s.method)).toContain('POST')
    expect(sent.length).toBeGreaterThan(0)
    for (const s of sent) expect(s.url.startsWith(`${AWAY}/api/shares/${SHARE_ID}`), s.url).toBe(true)
  })

  it('naming the guest of a foreign head creates its identity there', async () => {
    const head = await headAt(AWAY)
    const sent = recordFetch()
    await nameShareGuest(head, { name: 'Ben', fragmentKey: FRAGMENT })
    expect(sent.map((s) => s.url)).toEqual([`${AWAY}/api/shares/${SHARE_ID}/identities`])
    expect(sent[0].headers.get('authorization')).toBeNull()
  })
})
