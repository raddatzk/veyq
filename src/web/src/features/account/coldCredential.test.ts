// The shared cold-credential check (ADR 0060 Decision 2) in its locked-vault form, `checkColdCredential` — what the
// change-email landing page runs before it prints a new Emergency Kit (uc.account.change-email step 5). The crypto is
// real and production-cost: the floor refuses any KDF profile other than the one the client writes.
import { describe, it, expect, vi, beforeAll, beforeEach } from 'vitest'
import { PRODUCTION_ARGON_TIMEOUT_MS } from '@veyq/crypto/testArgon'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'
import { formatSecretKey } from '@veyq/crypto/secretKey'
import { fakeApi } from '../../test/apiMock'
import type { AccountApi, MasterKeyWrap } from '../../api/generated/account'

const getAccountKeyMaterial = vi.fn<AccountApi['getAccountKeyMaterial']>()
vi.mock('../../api/client', () => ({ accountApi: vi.fn() }))
vi.mock('../../app/sessionRefresh', () => ({ withRefresh: (f: () => unknown) => f() }))
const held: { mk: Uint8Array | null } = vi.hoisted(() => ({ mk: null }))
vi.mock('../../app/vault', () => ({ masterKey: () => (held.mk ? held.mk.slice() : null) }))

import { checkColdCredential, ColdCredentialRefused } from './coldCredential'
import { accountApi } from '../../api/client'

const PASSWORD = 'the account password'
const SECRET_KEY = v1.randomBytes(32)
const MK = v1.randomBytes(32)

let floor: MasterKeyWrap
beforeAll(() => {
  const salt = v1.randomBytes(16)
  const kek = v1.kekPasswordSecret(SECRET_KEY, PASSWORD, salt, v1.ARGON)
  floor = {
    id: 'w1',
    method: 'PASSWORD',
    wrappedKey: b64std(v1.seal({ purpose: 'master-key' }, kek, MK)),
    kdfParams: b64std(v1.encodeKdfParams(v1.ARGON, salt)),
  }
}, PRODUCTION_ARGON_TIMEOUT_MS)

const refusal = (p: Promise<void>) => p.then(() => null, (e: unknown) => e)

describe('checkColdCredential (a locked vault, too)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    held.mk = null
    fakeApi(accountApi, { getAccountKeyMaterial })
    getAccountKeyMaterial.mockResolvedValue({ userId: 'u', suiteId: 'v1', wraps: [floor], wrappedIdentityPrivateKey: 'aWdub3JlZA==' })
  })

  it('passes the account’s own password and Secret Key with the vault locked', async () => {
    await expect(checkColdCredential(PASSWORD, ` ${formatSecretKey(SECRET_KEY)} `)).resolves.toBeUndefined()
  }, PRODUCTION_ARGON_TIMEOUT_MS)

  it('refuses a Secret Key that is not the account’s — and a wrong password with the right key', async () => {
    const wrongKey = await refusal(checkColdCredential(PASSWORD, formatSecretKey(v1.randomBytes(32))))
    expect(wrongKey).toBeInstanceOf(ColdCredentialRefused)
    expect((wrongKey as ColdCredentialRefused).reason).toBe('credentialRejected')
    const wrongPassword = await refusal(checkColdCredential('not the password', formatSecretKey(SECRET_KEY)))
    expect((wrongPassword as ColdCredentialRefused).reason).toBe('credentialRejected')
  }, PRODUCTION_ARGON_TIMEOUT_MS * 2)

  it('with an unlocked vault, also requires the floor to open THIS session’s Master Key', async () => {
    held.mk = MK
    await expect(checkColdCredential(PASSWORD, formatSecretKey(SECRET_KEY))).resolves.toBeUndefined()
    held.mk = v1.randomBytes(32)
    const stale = await refusal(checkColdCredential(PASSWORD, formatSecretKey(SECRET_KEY)))
    expect((stale as ColdCredentialRefused).reason).toBe('credentialRejected')
  }, PRODUCTION_ARGON_TIMEOUT_MS * 2)

  it('refuses when there is no floor to check against', async () => {
    getAccountKeyMaterial.mockResolvedValue({ userId: 'u', suiteId: 'v1', wraps: [], wrappedIdentityPrivateKey: 'aWdub3JlZA==' })
    const none = await refusal(checkColdCredential(PASSWORD, formatSecretKey(SECRET_KEY)))
    expect((none as ColdCredentialRefused).reason).toBe('noFloorWrap')
  })
})
