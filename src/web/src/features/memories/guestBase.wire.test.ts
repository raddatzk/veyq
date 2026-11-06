// @vitest-environment happy-dom
// Memories' guest reads and writes go where the share lives (ADR 0121 decision 5), recorded at `fetch` through the real
// generated clients: a guest of another instance's link - a bookmark's page shows it in place - lists, deletes its own and
// names itself at that instance's origin, and what this device keeps of it (the session, its own uploads) is that link's,
// never this origin's link of the same id.
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64url } from '@veyq/crypto/base64'
import { ShareRole } from '../../api/generated/sharing'
import { saveGuestSession } from '../../core/sharing/guestIdentity'
import { guestAccessOf } from '../../test/models'
import type { PublicShareHead } from '../../core/sharing/resolvePublicShare'
import { createContributorIdentity, deleteSharePhoto, listShareMedia, readShareMedia } from './share'
import { guestMemorySource } from './source'
import { rememberOwnShareMedia } from './ownShareMedia'

const AWAY = 'https://photos.example.org'
const SHARE_ID = 'Sh4reIdSh4reIdSh4reIdSh4reIdSh4reIdSh4re-_0'
const SCOPE_ID = '33333333-3333-4333-8333-333333333333'
const MEMORY = '6e5a0000-0000-4000-8000-0000000000b1'
const at = { shareId: SHARE_ID, apiBase: AWAY }

let sent: { url: string; method: string; headers: Headers }[] = []
beforeEach(() => {
  localStorage.clear()
  sent = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    sent.push({ url: String(input), method: init?.method ?? 'GET', headers: new Headers(init?.headers) })
    const url = String(input)
    if (url.endsWith('/identities')) return new Response(JSON.stringify({ contributorToken: 'tok' }), { status: 201, headers: { 'Content-Type': 'application/json' } })
    if (url.includes('/media/')) return new Response(null, { status: 204 })
    return new Response(JSON.stringify({ media: [], identities: [], more: false }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }))
})
afterEach(() => vi.unstubAllGlobals())

const quiet = (p: Promise<unknown>) => p.then(() => undefined, () => undefined)
const everyAway = () => {
  expect(sent.length).toBeGreaterThan(0)
  for (const s of sent) {
    expect(s.url.startsWith(`${AWAY}/api/shares/${SHARE_ID}`), s.url).toBe(true)
    expect(s.headers.get('authorization')).toBeNull()
  }
}

describe('Memories\' guest calls of another instance\'s link', () => {
  it('the listing, its conditional read, the delete of its own, the naming', async () => {
    await listShareMedia(at, ShareRole.Read)
    await readShareMedia(at, ShareRole.Read, undefined, MEMORY)
    await deleteSharePhoto(at, 'tok', 'm-1')
    await createContributorIdentity(at, SCOPE_ID, { kind: 'read', memoryKey: v1.randomBytes(32), rootEpoch: 0 }, 'Ben', b64url(v1.randomBytes(32)))
    expect(sent.map((s) => s.method)).toEqual(['GET', 'GET', 'DELETE', 'POST'])
    everyAway()
  })

  it('the guest\'s source lists and deletes through the access\'s base, and its session and own uploads are that link\'s', async () => {
    saveGuestSession(SHARE_ID, { identityId: 'here', contributorToken: 'tok-here' }) // this origin's link of the same id
    saveGuestSession(at, { identityId: 'away', contributorToken: 'tok-away' })
    rememberOwnShareMedia(at, 'away', 'm-away')
    const access = guestAccessOf(ShareRole.ReadWrite, { shareId: SHARE_ID, apiBase: AWAY, head: { scopeId: SCOPE_ID } as PublicShareHead })
    const source = guestMemorySource(() => access, { rootId: MEMORY })
    await quiet(source.listMedia(MEMORY))
    expect(source.deleteMedia?.reach).toBe('own')
    const del = source.deleteMedia as { isOwn: (id: string) => boolean; purge: (id: string) => Promise<void> }
    expect(del.isOwn('m-away')).toBe(true)
    await del.purge('m-away')
    everyAway()
    const purge = sent.find((s) => s.method === 'DELETE')!
    expect(purge.headers.get('x-contributor-token'), 'the foreign link\'s own identity, never this origin\'s').toBe('tok-away')
  })
})
