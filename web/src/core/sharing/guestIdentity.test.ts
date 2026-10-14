// @vitest-environment happy-dom
// A guest's identity on a READ_WRITE link, the platform's half (cr-node-key-hierarchy 5b.4a): the name sealed under the
// shared ROOT's key bound to (scope, share, identity), the link proven by its fragment key (ADR 0076), and the session
// kept on the device under the one key both share bodies read.
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../api/client', () => ({ publicSharesApi: vi.fn() }))

import * as v1 from '@veyq/crypto/v1'
import { b64std, b64url, fromB64std } from '@veyq/crypto/base64'
import { publicSharesApi } from '../../api/client'
import { stubApi } from '../../test/apiMock'
import { createGuestIdentity, identityNameContext, loadGuestSession, openIdentityName, saveGuestSession } from './guestIdentity'

const SCOPE_ID = '22222222-2222-4222-8222-222222222222'
const SHARE_ID = 'Sh4reIdSh4reIdSh4reIdSh4reIdSh4reIdSh4re-_0' // a 43-char base64url id, as `newShareId` mints

beforeEach(() => localStorage.clear())

describe('createGuestIdentity', () => {
  it('seals the name under the root key, bound to (scope, share, identity), and proves the link key', async () => {
    const { createShareIdentity } = stubApi(publicSharesApi, { createShareIdentity: { identityId: 'ignored', contributorToken: 'tok-1' } })
    const rootKey = v1.randomBytes(32)
    const kShare = v1.randomBytes(32)

    const identity = await createGuestIdentity({ shareId: SHARE_ID, scopeId: SCOPE_ID }, rootKey, { name: 'Oma', fragmentKey: b64url(kShare), pwToken: 'pw' })

    const call = createShareIdentity.mock.calls[0][0]
    const req = call.createShareIdentityRequest
    expect(identity).toEqual({ identityId: req.identityId, contributorToken: 'tok-1' })
    expect(req.wrappedIdentityKey).toBeUndefined()
    expect(req.sealedDisplayName).not.toContain('Oma')
    const ctx = identityNameContext(SCOPE_ID, SHARE_ID, req.identityId)
    expect(new TextDecoder().decode(v1.open(ctx, rootKey, fromB64std(req.sealedDisplayName)))).toBe('Oma')
    expect(() => v1.open(ctx, v1.randomBytes(32), fromB64std(req.sealedDisplayName)), 'only under the root key').toThrow(v1.OpenRefused)
    expect(() => v1.open({ ...ctx, identityId: crypto.randomUUID() }, rootKey, fromB64std(req.sealedDisplayName))).toThrow(v1.OpenRefused)
    expect(call.xShareWrite).toBe(b64std(v1.shareWriteToken(kShare, SHARE_ID)))
    expect(call.xSharePassword).toBe('pw')
  })
})

// inbox #431: how a MEMBER reads what `createGuestIdentity` sealed - every application's one reader (Memories' buckets,
// Files' uploader). The seal here is the real write path's, so a reader that drifts from it is red.
describe('openIdentityName', () => {
  async function named(rootKey: Uint8Array, name: string) {
    const { createShareIdentity } = stubApi(publicSharesApi, { createShareIdentity: { identityId: 'ignored', contributorToken: 'tok-1' } })
    await createGuestIdentity({ shareId: SHARE_ID, scopeId: SCOPE_ID }, rootKey, { name, fragmentKey: b64url(v1.randomBytes(32)) })
    const { identityId, sealedDisplayName } = createShareIdentity.mock.calls[0][0].createShareIdentityRequest
    return { identityId, sealed: sealedDisplayName }
  }

  it('opens under the first of the keys it is sealed under - the node\'s own, or an ancestor\'s further up', async () => {
    const root = v1.randomBytes(32)
    const { identityId, sealed } = await named(root, 'Oma')
    expect(openIdentityName(SCOPE_ID, SHARE_ID, identityId, sealed, [root])).toBe('Oma')
    expect(openIdentityName(SCOPE_ID, SHARE_ID, identityId, sealed, [v1.randomBytes(32), v1.randomBytes(32), root]), 'the root two levels up').toBe('Oma')
  })

  it('stops at the key that opens: the keys above it are never asked for', async () => {
    const root = v1.randomBytes(32)
    const { identityId, sealed } = await named(root, 'Oma')
    const asked: number[] = []
    function* keys() {
      for (const [i, k] of [v1.randomBytes(32), root, v1.randomBytes(32)].entries()) {
        asked.push(i)
        yield k
      }
    }
    expect(openIdentityName(SCOPE_ID, SHARE_ID, identityId, sealed, keys())).toBe('Oma')
    expect(asked).toEqual([0, 1])
  })

  it('null where it does not open: no key holds it, another identity or share is addressed, no keys, no base64', async () => {
    const root = v1.randomBytes(32)
    const { identityId, sealed } = await named(root, 'Oma')
    expect(openIdentityName(SCOPE_ID, SHARE_ID, identityId, sealed, [root]), 'control').toBe('Oma')
    expect(openIdentityName(SCOPE_ID, SHARE_ID, identityId, sealed, [v1.randomBytes(32)])).toBeNull()
    expect(openIdentityName(SCOPE_ID, SHARE_ID, crypto.randomUUID(), sealed, [root])).toBeNull()
    expect(openIdentityName(SCOPE_ID, 'Oth3rIdOth3rIdOth3rIdOth3rIdOth3rIdOth3r-_0', identityId, sealed, [root])).toBeNull()
    expect(openIdentityName(SCOPE_ID, SHARE_ID, identityId, sealed, [])).toBeNull()
    expect(openIdentityName(SCOPE_ID, SHARE_ID, identityId, '%%%', [root])).toBeNull()
  })
})

describe('the guest session on the device', () => {
  it('round-trips under veyq:share-contributor:<shareId>, keeping what an application adds', () => {
    expect(loadGuestSession(SHARE_ID)).toBeNull()
    const session = { identityId: 'i-1', contributorToken: 't-1', uploaderKey: 'k' }
    saveGuestSession(SHARE_ID, session)
    expect(JSON.parse(localStorage.getItem(`veyq:share-contributor:${SHARE_ID}`)!)).toEqual({ identityId: 'i-1', contributorToken: 't-1', uploaderKey: 'k' })
    expect(loadGuestSession<{ identityId: string; contributorToken: string; uploaderKey?: string }>(SHARE_ID)?.uploaderKey).toBe('k')
  })

  it('reads an unreadable entry as no session', () => {
    localStorage.setItem(`veyq:share-contributor:${SHARE_ID}`, '{not json')
    expect(loadGuestSession(SHARE_ID)).toBeNull()
  })
})
