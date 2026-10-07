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
import { createGuestIdentity, identityNameContext, loadGuestSession, saveGuestSession } from './guestIdentity'

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
