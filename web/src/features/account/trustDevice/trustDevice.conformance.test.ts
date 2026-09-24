import { describe, it, expect, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

vi.mock('../../../api/client', () => ({
  accountWrapsApi: vi.fn(),
  accountApi: vi.fn(),
  loginApi: vi.fn(),
  tokenApi: vi.fn(),
  sessionApi: vi.fn(),
}))
vi.mock('./deviceKey', () => ({
  loadTrust: vi.fn(),
  saveTrust: vi.fn(),
  clearTrust: vi.fn(),
  generateDeviceKey: vi.fn(),
  deviceFactor: vi.fn(),
  isTrustSupported: () => true,
}))
// The real suite, with two seams: the salt draw and the KEK's cost parameters (both set per test below).
vi.mock('@veyq/crypto/v1', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@veyq/crypto/v1')>()
  return {
    ...actual,
    randomBytes: vi.fn(actual.randomBytes),
    kekDevicePassword: vi.fn(actual.kekDevicePassword),
  }
})

import { trustThisDevice } from './trustDevice'
import * as device from './deviceKey'
import { accountWrapsApi } from '../../../api/client'
import * as v1 from '@veyq/crypto/v1'
import { fromB64std } from '@veyq/crypto/base64'

const vectors = JSON.parse(
  readFileSync(resolve(__dirname, '../../../../../contracts/crypto/vectors/v1.json'), 'utf8'),
) as {
  kek: {
    device_password_argon2id: {
      params: v1.ArgonParams
      device_factor: string
      password_utf8: string
      salt: string
      kek: string
    }
  }
}
const K = vectors.kek.device_password_argon2id
const unhex = (h: string) => Uint8Array.from(h.match(/../g)!.map((b) => parseInt(b, 16)))
const hex = (u: Uint8Array) => Array.from(u, (b) => b.toString(16).padStart(2, '0')).join('')

// uc.account.trust-device `device-kek-conformance`, pinned on web's own enrolment path. unlock.device.test.ts
// builds its wrap with kekDevicePassword and opens it with the same function, so a wrong derivation round-trips
// there; this test holds the KEK web derives to the shared vector instead.
describe('trustThisDevice derives the DEVICE-wrap KEK of the shared crypto vector', () => {
  it('derives kek.device_password_argon2id from the vector inputs and seals the wrap under it', async () => {
    const real = await vi.importActual<typeof import('@veyq/crypto/v1')>('@veyq/crypto/v1')
    vi.mocked(device.loadTrust).mockResolvedValue(null)
    vi.mocked(device.generateDeviceKey).mockResolvedValue({} as CryptoKey)
    vi.mocked(device.deviceFactor).mockResolvedValue(unhex(K.device_factor))
    // The salt is trustThisDevice's first random draw; any later draw stays random, so a derivation under a
    // different draw than the one uploaded as kdfParams fails here instead of passing on a repeated salt.
    vi.mocked(v1.randomBytes).mockImplementationOnce(() => unhex(K.salt))
    // Web derives at the production default (v1.ARGON), the vector at its own cheaper params; only the cost is
    // swapped, and the production params are pinned by packages/crypto's `kdf_params` test.
    vi.mocked(v1.kekDevicePassword).mockImplementation((factor, password, salt) =>
      real.kekDevicePassword(factor, password, salt, K.params),
    )
    const add = vi.fn().mockResolvedValue({ id: 'wrap-1' })
    vi.mocked(accountWrapsApi).mockReturnValue({
      addAccountWrap: add,
      deleteAccountWrap: vi.fn(),
    } as unknown as ReturnType<typeof accountWrapsApi>)
    const masterKey = real.randomBytes(32)

    await trustThisDevice('user@example.com', K.password_utf8, masterKey)

    expect(v1.kekDevicePassword).toHaveBeenCalledOnce()
    expect(hex(vi.mocked(v1.kekDevicePassword).mock.results[0].value)).toBe(K.kek)
    const req = add.mock.calls[0][0].addWrapRequest
    expect(real.symUnwrap(unhex(K.kek), fromB64std(req.wrappedKey), 'master-key')).toEqual(masterKey)
    expect(hex(real.decodeKdfParams(fromB64std(req.kdfParams)).salt)).toBe(K.salt)
  })
})
