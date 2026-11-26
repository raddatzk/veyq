// The Server Owner's birth carries its kit record (ADR 0157 decision 4): the kit setup shows next is recorded with the
// account, sealed under the new Master Key before setup zeroes it — `{"layout":KIT_LAYOUT}`, purpose `kit-record`, the
// shape src/web/ reads (`features/account/emergencyKit/kitRecord.ts`). The request is posted only once the kit is
// confirmed (SetupView). The birth is stubbed to a known Master Key so the test can open what setup sealed
// (setupServer.test.ts runs the real birth for everything else).
import { describe, it, expect, vi } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'

vi.mock('@veyq/crypto/accountBirth', () => ({ buildAccount: vi.fn() }))
vi.mock('../../api/client', () => ({ setupApi: vi.fn(), instanceModerationApi: vi.fn() }))

import { buildAccount } from '@veyq/crypto/accountBirth'
import { buildCreateServerOwnerRequest, sealKitRecord } from './setupServer'
import { KIT_LAYOUT } from './emergencyKit'

const MK = v1.randomBytes(32)
const born = v1.generateIdentity()
const open = (mk: Uint8Array, blob: string) => new TextDecoder().decode(v1.open({ purpose: 'kit-record' }, mk, fromB64std(blob)))

describe('the Server Owner’s birth carries its kit record (ADR 0157 decision 4)', () => {
  it('sealed under the new Master Key, compact {"layout":KIT_LAYOUT}, and the key is zeroed afterwards', async () => {
    const handed = MK.slice()
    vi.mocked(buildAccount).mockResolvedValue({
      identity: born,
      masterKey: handed,
      emergencyKit: { secretKey: 'c2VjcmV0' },
      material: {
        suiteId: 'v1',
        identityPublicKey: b64std(v1.serializeIdentityPublic(born.pub)),
        wrappedIdentityPrivateKey: 'd3JhcHBlZA==',
        authPublicKey: 'YXV0aA==',
        masterKeyWraps: [{ method: 'PASSWORD', wrappedKey: 'd3JhcA==', kdfParams: 'a2Rm' }],
        secretKeyProofPublicKey: 'cHJvb2Y=',
      },
    })
    const { request } = await buildCreateServerOwnerRequest('owner@example.com', 'pw', new Uint8Array(16))
    expect(request.kitRecord).toMatch(/^[A-Za-z0-9+/]*={0,2}$/)
    expect(open(MK, request.kitRecord!)).toBe(`{"layout":${KIT_LAYOUT}}`)
    expect(() => open(v1.randomBytes(32), request.kitRecord!)).toThrow() // the new account's key alone opens it
    expect(handed.every((b) => b === 0)).toBe(true)
  })

  it('sealKitRecord writes exactly the plaintext src/web/ reads — and the contract vector’s', () => {
    expect(open(MK, sealKitRecord(MK))).toBe('{"layout":2}')
  })
})
