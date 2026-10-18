// uc.account.erase-account (web) — the request an erasure sends and what this browser does after the server's answer.
//
// The proof is held to the contract, not to itself: the auth key the derivation hands back is the
// `auth_attestation.erase_account` vector's, the account id and the challenge's nonce are its inputs, so the signature
// on the wire must be the vector's byte for byte. Each refusal is paired with the success it mirrors: a `200` clears
// this browser's records for the account and ends the session saying `erased`; a refusal touches nothing.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { fakeApi, stubApi } from '../../../test/apiMock'
import { httpError } from '../../../test/httpError'
import type { LoginApi } from '../../../api/generated/auth'
import type { AccountErasureApi } from '../../../api/generated/account'

const vectors = JSON.parse(readFileSync(resolve(__dirname, '../../../../../contracts/crypto/vectors/v1.json'), 'utf8')) as {
  auth_attestation: {
    erase_account: { auth_private_key: string; auth_public_key: string; user_id: string; nonce_b64: string; signature: string }
  }
}
const V = vectors.auth_attestation.erase_account
const unhex = (h: string) => Uint8Array.from(h.match(/../g)!.map((b) => parseInt(b, 16)))
const hex = (u: Uint8Array) => Array.from(u, (b) => b.toString(16).padStart(2, '0')).join('')

const INSTANCE_SALT = v1.randomBytes(32)
const PASSWORD = 'correct horse battery staple'
const MK = v1.randomBytes(32)

vi.mock('../../../api/client', () => ({ loginApi: vi.fn(), accountErasureApi: vi.fn() }))
vi.mock('../../../app/sessionRefresh', () => ({ withRefresh: (f: () => unknown) => f() }))
vi.mock('../../../app/sessionEnd', () => ({ endSession: vi.fn() }))
vi.mock('../rememberMe/sessionVault', () => ({ clearSessionVault: vi.fn() }))
vi.mock('../rememberMe/deviceKeyWrap', () => ({ clearDeviceKey: vi.fn() }))
vi.mock('../trustDevice/deviceKey', () => ({ clearTrust: vi.fn() }))
vi.mock('../trustMarks', () => ({ forgetMarks: vi.fn() }))
vi.mock('@veyq/crypto/argonWorker', () => ({ deriveAuthKeypairAsync: vi.fn() }))

const held: { mk: Uint8Array | null } = vi.hoisted(() => ({ mk: null }))
vi.mock('../../../app/vault', () => ({ masterKey: () => (held.mk ? held.mk.slice() : null) }))
const acct = vi.hoisted(() => ({ email: '', userId: '' }))
vi.mock('../../../app/account', () => ({ account: acct }))

import { eraseOwnAccount, erasureErrorKey, loadErasurePreview, EraseAccountRefused } from './eraseAccount'
import { accountErasureApi, loginApi } from '../../../api/client'
import { endSession } from '../../../app/sessionEnd'
import { clearSessionVault } from '../rememberMe/sessionVault'
import { clearDeviceKey } from '../rememberMe/deviceKeyWrap'
import { clearTrust } from '../trustDevice/deviceKey'
import { forgetMarks } from '../trustMarks'
import { deriveAuthKeypairAsync } from '@veyq/crypto/argonWorker'

const requestLoginChallenge = vi.fn<LoginApi['requestLoginChallenge']>()
const eraseCall = vi.fn<AccountErasureApi['eraseOwnAccount']>()
const sent = () => eraseCall.mock.calls[0][0].eraseOwnAccountRequest
const CHOICES = [
  { tenantId: '7c3f0e2a-9b1d-4a6e-8b2c-1f2d3e4a5b6c', newOwnerId: '2b6e4c1a-8d3f-4e7a-9c2b-5f1e3d4a6b7c' },
  { tenantId: '8d4f1f3b-0c2e-4b7f-9c3d-2e3f4a5b6c7d', newOwnerId: null },
]
const localClears = () => [clearSessionVault, clearDeviceKey, clearTrust, forgetMarks, endSession]

beforeEach(() => {
  vi.clearAllMocks()
  held.mk = MK.slice()
  acct.email = 'erase.me@example.com'
  acct.userId = V.user_id
  fakeApi(loginApi, { requestLoginChallenge })
  fakeApi(accountErasureApi, { eraseOwnAccount: eraseCall })
  requestLoginChallenge.mockResolvedValue({ instanceSalt: b64std(INSTANCE_SALT), nonce: V.nonce_b64 })
  eraseCall.mockResolvedValue({ dueAt: new Date('2026-11-17T10:00:00Z') })
  // The vector's auth key, fresh each call: the flow zeroes the private half after signing.
  vi.mocked(deriveAuthKeypairAsync).mockImplementation(async () => ({
    privateKey: unhex(V.auth_private_key),
    publicKey: unhex(V.auth_public_key),
  }))
  vi.mocked(clearSessionVault).mockResolvedValue(undefined)
  vi.mocked(clearDeviceKey).mockResolvedValue(undefined)
  vi.mocked(clearTrust).mockResolvedValue(undefined)
  vi.mocked(forgetMarks).mockResolvedValue(undefined)
  vi.mocked(endSession).mockResolvedValue(undefined)
})

describe('eraseOwnAccount — the request', () => {
  it('proves the password over the account and the challenge: the contract vector’s signature, byte for byte', async () => {
    await eraseOwnAccount(PASSWORD, CHOICES)
    expect(requestLoginChallenge).toHaveBeenCalledWith({ loginChallengeRequest: { email: acct.email } })
    // Derived as sign-in derives it: the password under the account address's salt, at production cost.
    expect(deriveAuthKeypairAsync).toHaveBeenCalledWith(PASSWORD, v1.deriveAuthSalt(INSTANCE_SALT, acct.email), v1.ARGON)
    expect(sent().nonce).toBe(V.nonce_b64)
    expect(hex(fromB64std(sent().attestationSignature))).toBe(V.signature)
    expect(sent().lastOwnerChoices).toEqual(CHOICES)
  })

  it('a proof for another account is another signature (the vector pins the id it binds)', async () => {
    acct.userId = 'c0ffee00-0000-4000-8000-0000000000ab'
    await eraseOwnAccount(PASSWORD, CHOICES)
    expect(hex(fromB64std(sent().attestationSignature))).not.toBe(V.signature)
  })

  it('never sends the password or the Master Key', async () => {
    await eraseOwnAccount(PASSWORD, CHOICES)
    const wire = JSON.stringify(sent())
    expect(wire).not.toContain(PASSWORD)
    expect(wire).not.toContain(b64std(MK))
  })

  it('refuses before any request while the account id is unknown (the proof binds it)', async () => {
    acct.userId = ''
    await expect(eraseOwnAccount(PASSWORD, CHOICES)).rejects.toBeInstanceOf(EraseAccountRefused)
    expect(requestLoginChallenge).not.toHaveBeenCalled()
    expect(eraseCall).not.toHaveBeenCalled()
  })
})

describe('eraseOwnAccount — after the answer', () => {
  it('200: clears this browser’s records for the account, then ends the session saying `erased`', async () => {
    let marksKey = ''
    vi.mocked(forgetMarks).mockImplementation(async (mk) => {
      marksKey = hex(mk)
    })
    await eraseOwnAccount(PASSWORD, CHOICES)
    expect(clearSessionVault).toHaveBeenCalledOnce()
    // Local only: the server's wraps go with the purge, and the session that could delete one is over.
    expect(clearDeviceKey).toHaveBeenCalledWith(false)
    expect(clearTrust).toHaveBeenCalledWith(acct.email)
    // The marks of THIS account's slot (its Master Key), and the copy is zeroed once they are gone.
    expect(marksKey).toBe(hex(MK))
    expect(hex(vi.mocked(forgetMarks).mock.calls[0][0])).toBe(hex(new Uint8Array(32)))
    expect(endSession).toHaveBeenCalledWith('erased')
    const last = (f: unknown) => vi.mocked(f as typeof endSession).mock.invocationCallOrder[0]
    for (const clear of [clearSessionVault, clearDeviceKey, clearTrust, forgetMarks]) expect(last(clear)).toBeLessThan(last(endSession))
  })

  it('200 with a store that fails: the session still ends', async () => {
    vi.mocked(clearTrust).mockRejectedValue(new Error('idb denied'))
    await eraseOwnAccount(PASSWORD, CHOICES)
    expect(endSession).toHaveBeenCalledWith('erased')
  })

  it.each([
    ['403 attestation_failed', httpError(403, 'attestation_failed')],
    ['409 last_owner_choice_required', httpError(409, 'last_owner_choice_required')],
    ['409 moderation_last_holder', httpError(409, 'moderation_last_holder')],
    ['500', httpError(500)],
  ])('%s: rejects and clears nothing — the session stays', async (_, err) => {
    eraseCall.mockRejectedValue(err)
    await expect(eraseOwnAccount(PASSWORD, CHOICES)).rejects.toBe(err)
    for (const f of localClears()) expect(f).not.toHaveBeenCalled()
  })
})

describe('erasureErrorKey', () => {
  it('names each refusal by the contract’s one meaning for it', async () => {
    expect(await erasureErrorKey(new EraseAccountRefused('vaultLocked'))).toBe('erasure.errorLocked')
    expect(await erasureErrorKey(httpError(403, 'attestation_failed'))).toBe('erasure.errorPassword')
    expect(await erasureErrorKey(httpError(409, 'last_owner_choice_required'))).toBe('erasure.errorChoiceRequired')
    expect(await erasureErrorKey(httpError(409, 'moderation_last_holder'))).toBe('erasure.errorModerationLastHolder')
    expect(await erasureErrorKey(httpError(409, 'server_owner_account'))).toBe('erasure.serverOwner')
    expect(await erasureErrorKey(httpError(409))).toBe('erasure.errorFailed')
    expect(await erasureErrorKey(httpError(500))).toBe('erasure.errorFailed')
  })
})

describe('loadErasurePreview', () => {
  it('lists the tenants the erasure decides about', async () => {
    const tenants = [{ tenantId: CHOICES[0].tenantId, name: 'Familie Muster', otherMemberIds: ['2b6e4c1a-8d3f-4e7a-9c2b-5f1e3d4a6b7c'] }]
    stubApi(accountErasureApi, { getOwnAccountErasurePreview: { tenants } })
    expect(await loadErasurePreview()).toEqual({ kind: 'ready', tenants })
  })

  it('reads the Server Owner’s 409 as an answer, and any other failure as one', async () => {
    fakeApi(accountErasureApi, {
      getOwnAccountErasurePreview: vi.fn<AccountErasureApi['getOwnAccountErasurePreview']>().mockRejectedValue(httpError(409, 'server_owner_account')),
    })
    expect(await loadErasurePreview()).toEqual({ kind: 'serverOwner' })
    const err = httpError(500)
    fakeApi(accountErasureApi, {
      getOwnAccountErasurePreview: vi.fn<AccountErasureApi['getOwnAccountErasurePreview']>().mockRejectedValue(err),
    })
    await expect(loadErasurePreview()).rejects.toBe(err)
  })
})
