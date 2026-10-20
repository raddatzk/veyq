// First-run setup mints epoch 1 of the moderation key (ADR 0099 § 3; owner 2026-10-17): the request that creates the
// Server Owner carries the epoch's public bundle and M_1 sealed to the identity born in that same request — so reporting
// is on from the first day, with exactly one grant, the Server Owner's. The birth is stubbed to a known identity so the
// test can open what the setup sealed (setupServer.test.ts runs the real birth for everything else).
import { describe, it, expect, vi } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'

const born = v1.generateIdentity()
vi.mock('@veyq/crypto/accountBirth', () => ({ buildAccount: vi.fn() }))
vi.mock('../../api/client', () => ({ setupApi: vi.fn(), instanceModerationApi: vi.fn() }))

import { buildAccount } from '@veyq/crypto/accountBirth'
import { buildCreateServerOwnerRequest } from './setupServer'

describe('setup mints moderation epoch 1 (uc.moderation.appoint-moderators)', () => {
  it('seals M_1 to the identity born in the same request, and the bundle is that key’s', async () => {
    vi.mocked(buildAccount).mockResolvedValue({
      identity: born,
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
    const epoch = request.moderationEpoch
    const m = v1.openAs({ purpose: 'moderation-grant', epoch: 1 }, v1.hybridSecret(born.priv), fromB64std(epoch.sealedGrant))
    const bundle = v1.moderationBundle(m)
    expect(b64std(bundle.kem)).toBe(epoch.kemPublicKey)
    expect(b64std(bundle.sign)).toBe(epoch.signPublicKey)
    // Sealed for epoch 1 and nothing else.
    expect(() => v1.openAs({ purpose: 'moderation-grant', epoch: 2 }, v1.hybridSecret(born.priv), fromB64std(epoch.sealedGrant))).toThrow()
  })
})
