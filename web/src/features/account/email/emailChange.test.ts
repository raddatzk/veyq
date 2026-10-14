// uc.account.change-email (web) — the request a change sends. The API, vault and account state are stubbed; the
// crypto is real and production-cost (emailChange.ts derives at `v1.ARGON`, with no params argument to lighten it).
//
// The derivation is held to the contract, not to itself: the new address and the password are the `auth` vectors'
// own inputs, so the key the request carries must be byte-for-byte the vector's auth public key — derived under the
// NEW address's salt, with the address normalized as the server stores it.
import { describe, it, expect, vi, beforeAll, beforeEach } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { PRODUCTION_ARGON_TIMEOUT_MS } from '@veyq/crypto/testArgon'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { fakeApi } from '../../../test/apiMock'
import { httpError } from '../../../test/httpError'
import type { LoginApi } from '../../../api/generated/auth'
import type { AccountApi, AccountEmailChangeApi, MasterKeyWrap } from '../../../api/generated/account'

const requestLoginChallenge = vi.fn<LoginApi['requestLoginChallenge']>()
const requestEmailChangeCall = vi.fn<AccountEmailChangeApi['requestEmailChange']>().mockResolvedValue(undefined)
const getAccountKeyMaterial = vi.fn<AccountApi['getAccountKeyMaterial']>()

vi.mock('../../../api/client', () => ({
  loginApi: vi.fn(),
  accountEmailChangeApi: vi.fn(),
  accountApi: vi.fn(),
}))
vi.mock('../../../app/sessionRefresh', () => ({ withRefresh: (f: () => unknown) => f() }))

const held: { mk: Uint8Array | null } = vi.hoisted(() => ({ mk: null }))
vi.mock('../../../app/vault', () => ({ masterKey: () => held.mk }))
const acct = vi.hoisted(() => ({ email: 'old.address@example.com', userId: 'c0ffee00-0000-4000-8000-0000000000aa' }))
vi.mock('../../../app/account', () => ({ account: acct }))

import { requestEmailChange, requestErrorKey, EmailChangeRefused } from './emailChange'
import { accountApi, accountEmailChangeApi, loginApi } from '../../../api/client'

const vectors = JSON.parse(readFileSync(resolve(__dirname, '../../../../../contracts/crypto/vectors/v1.json'), 'utf8')) as {
  auth: {
    auth_salt: { instance_salt: string; email_utf8: string; auth_salt: string }
    auth_keypair: { password_utf8: string; public_key: string }
  }
}
const unhex = (h: string) => Uint8Array.from(h.match(/../g)!.map((b) => parseInt(b, 16)))
const hex = (u: Uint8Array) => Array.from(u, (b) => b.toString(16).padStart(2, '0')).join('')

const INSTANCE_SALT = unhex(vectors.auth.auth_salt.instance_salt)
const PASSWORD = vectors.auth.auth_keypair.password_utf8
// The vector's address, typed the way a person might: the request must normalize it before deriving.
const NEW_EMAIL_TYPED = `  ${vectors.auth.auth_salt.email_utf8.toUpperCase()} `
const SECRET_KEY = v1.randomBytes(32)
const SECRET_KEY_B64 = b64std(SECRET_KEY)
const MK = v1.randomBytes(32)

function storedFloor(): MasterKeyWrap {
  const salt = v1.randomBytes(16)
  const kek = v1.kekPasswordSecret(SECRET_KEY, PASSWORD, salt, v1.ARGON)
  return {
    id: 'w1',
    method: 'PASSWORD',
    wrappedKey: b64std(v1.seal({ purpose: 'master-key' }, kek, MK)),
    kdfParams: b64std(v1.encodeKdfParams(v1.ARGON, salt)),
  }
}

let floor: MasterKeyWrap
// The current auth key, as the server holds it — derived under the OLD address.
let currentAuthPublicKey: Uint8Array
beforeAll(() => {
  floor = storedFloor()
  currentAuthPublicKey = v1.deriveAuthKeypair(PASSWORD, v1.deriveAuthSalt(INSTANCE_SALT, acct.email), v1.ARGON).publicKey
}, PRODUCTION_ARGON_TIMEOUT_MS * 2)

const sent = () => requestEmailChangeCall.mock.calls[0][0].emailChangeRequest

describe('requestEmailChange', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    held.mk = MK.slice()
    acct.userId = 'c0ffee00-0000-4000-8000-0000000000aa'
    fakeApi(loginApi, { requestLoginChallenge })
    fakeApi(accountEmailChangeApi, { requestEmailChange: requestEmailChangeCall })
    fakeApi(accountApi, { getAccountKeyMaterial })
    requestLoginChallenge.mockResolvedValue({ instanceSalt: b64std(INSTANCE_SALT), nonce: b64std(new Uint8Array(32)) })
    getAccountKeyMaterial.mockResolvedValue({ userId: acct.userId, suiteId: 'v1', wraps: [floor], wrappedIdentityPrivateKey: 'aWdub3JlZA==' })
  })

  it('derives the new auth key under the NEW address’s salt — the contract’s vector key, byte for byte', async () => {
    await requestEmailChange(NEW_EMAIL_TYPED, PASSWORD, SECRET_KEY_B64)
    expect(hex(fromB64std(sent().authPublicKey))).toBe(vectors.auth.auth_keypair.public_key)
    expect(sent().newEmail).toBe(NEW_EMAIL_TYPED.trim())
    // The salt is read for the CURRENT address — the challenge is for the account as it signs in today.
    expect(requestLoginChallenge).toHaveBeenCalledWith({ loginChallengeRequest: { email: acct.email } })
  }, PRODUCTION_ARGON_TIMEOUT_MS * 3)

  it('attests with the CURRENT auth key over the account, the new address and the new key', async () => {
    await requestEmailChange(NEW_EMAIL_TYPED, PASSWORD, SECRET_KEY_B64)
    const body = sent()
    const stmt = v1.emailChangeAttestationStatement({
      userId: acct.userId,
      email: body.newEmail,
      authPublicKey: fromB64std(body.authPublicKey),
    })
    const sig = fromB64std(body.attestationSignature)
    expect(v1.authVerify(currentAuthPublicKey, stmt, sig)).toBe(true)
    expect(v1.authVerify(fromB64std(body.authPublicKey), stmt, sig)).toBe(false)
  }, PRODUCTION_ARGON_TIMEOUT_MS * 3)

  it('proves the Secret Key over the key it replaces, the one it installs and the address', async () => {
    await requestEmailChange(NEW_EMAIL_TYPED, PASSWORD, SECRET_KEY_B64)
    const body = sent()
    const stmt = v1.emailChangeProofStatement({
      currentAuthPublicKey,
      authPublicKey: fromB64std(body.authPublicKey),
      email: body.newEmail,
    })
    const sig = fromB64std(body.secretKeyProofSignature)
    expect(v1.authVerify(v1.deriveSecretKeyProofKeypair(SECRET_KEY).publicKey, stmt, sig)).toBe(true)
    expect(v1.authVerify(v1.deriveSecretKeyProofKeypair(v1.randomBytes(32)).publicKey, stmt, sig)).toBe(false)
  }, PRODUCTION_ARGON_TIMEOUT_MS * 3)

  it('never sends the password, the Secret Key or the Master Key', async () => {
    await requestEmailChange(NEW_EMAIL_TYPED, PASSWORD, SECRET_KEY_B64)
    const wire = JSON.stringify(sent())
    expect(wire).not.toContain(PASSWORD)
    expect(wire).not.toContain(SECRET_KEY_B64)
    expect(wire).not.toContain(b64std(MK))
  }, PRODUCTION_ARGON_TIMEOUT_MS * 3)

  it('refuses before any request when the Secret Key does not open the floor', async () => {
    const err = await requestEmailChange(NEW_EMAIL_TYPED, PASSWORD, b64std(v1.randomBytes(32))).catch((e: unknown) => e)
    expect(err).toBeInstanceOf(EmailChangeRefused)
    expect((err as EmailChangeRefused).reason).toBe('credentialRejected')
    expect(requestEmailChangeCall).not.toHaveBeenCalled()
  }, PRODUCTION_ARGON_TIMEOUT_MS * 2)

  it('refuses before any request when the vault is locked', async () => {
    held.mk = null
    await expect(requestEmailChange(NEW_EMAIL_TYPED, PASSWORD, SECRET_KEY_B64)).rejects.toBeInstanceOf(EmailChangeRefused)
    expect(requestEmailChangeCall).not.toHaveBeenCalled()
    expect(requestLoginChallenge).not.toHaveBeenCalled()
  })

  it('refuses before any request when the account id is not known yet (the attestation binds it)', async () => {
    acct.userId = ''
    await expect(requestEmailChange(NEW_EMAIL_TYPED, PASSWORD, SECRET_KEY_B64)).rejects.toBeInstanceOf(EmailChangeRefused)
    expect(requestEmailChangeCall).not.toHaveBeenCalled()
  }, PRODUCTION_ARGON_TIMEOUT_MS * 2)
})

describe('requestErrorKey', () => {
  it('names each refusal by what the person can do about it', () => {
    expect(requestErrorKey(new EmailChangeRefused('credentialRejected'))).toBe('emailChange.errorCredential')
    expect(requestErrorKey(new EmailChangeRefused('vaultLocked'))).toBe('emailChange.errorLocked')
    expect(requestErrorKey(httpError(403, 'secret_key_proof_failed'))).toBe('emailChange.errorCredential')
    expect(requestErrorKey(httpError(503, 'mail_unavailable'))).toBe('emailConfirm.mailUnavailable')
    expect(requestErrorKey(httpError(404, 'tenant_not_found'))).toBe('emailConfirm.notMember')
    expect(requestErrorKey(httpError(429, 'rate_limited'))).toBe('emailConfirm.rateLimited')
    expect(requestErrorKey(httpError(500))).toBe('emailChange.errorFailed')
  })
})
