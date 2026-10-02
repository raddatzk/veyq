import { describe, it, expect, vi, beforeAll } from 'vitest'

// Mock the contracts client so registerServerOwner's network call is controllable. The build path
// (buildCreateServerOwnerRequest) is pure and untouched by this mock.
vi.mock('../../api/client', () => ({ setupApi: vi.fn() }))

import { buildCreateServerOwnerRequest, registerServerOwner } from './setupServer'
import { setupApi } from '../../api/client'
import * as v1 from '@veyq/crypto/v1'
import { TEST_ARGON, PRODUCTION_ARGON_TIMEOUT_MS } from '@veyq/crypto/testArgon'

// Standard base64 (padded or not) — the encoding the operator-blind setup contract pins for its wire fields
// (`[A-Za-z0-9+/]*={0,2}`). A base64url value (with `-`/`_`) would be rejected by the backend at the boundary.
const STD_B64 = /^[A-Za-z0-9+/]*={0,2}$/
const INSTANCE_SALT = new Uint8Array(16).fill(7)

describe('setupServer — Model B request (uc.admin.setup-server)', () => {
  let request: Awaited<ReturnType<typeof buildCreateServerOwnerRequest>>['request']
  let emergencyKit: Awaited<ReturnType<typeof buildCreateServerOwnerRequest>>['emergencyKit']
  // One birth for the whole block: three production-params Argon2id derivations, inherent to the code under test.
  beforeAll(async () => {
    ;({ request, emergencyKit } = await buildCreateServerOwnerRequest(
      'Owner@Example.com ',
      'correct horse battery',
      INSTANCE_SALT,
    ))
  }, PRODUCTION_ARGON_TIMEOUT_MS)

  it('carries the contract metadata', () => {
    expect(request.email).toBe('Owner@Example.com ')
    // The account carries no display name — identity is the per-tenant username (ADR 0023).
    expect(request.suiteId).toBe('v1')
  })

  it('never sends the raw password (operator-blind, ADR 0003)', () => {
    expect(JSON.stringify(request)).not.toContain('correct horse battery')
    expect((request as unknown as Record<string, unknown>).password).toBeUndefined()
  })

  it('registers only a standard-base64 auth public key', () => {
    expect(request.authPublicKey).toMatch(STD_B64)
    // 32-byte Ed25519 public key → 44 chars padded base64
    expect(request.authPublicKey.length).toBe(44)
  })

  it('emits standard-base64 (not base64url) for every opaque field', () => {
    for (const field of [request.identityPublicKey, request.wrappedIdentityPrivateKey, request.secretKeyProofPublicKey]) {
      expect(field).toMatch(STD_B64)
    }
    for (const wrap of request.masterKeyWraps) {
      expect(wrap.wrappedKey).toMatch(STD_B64)
      expect(wrap.kdfParams).toMatch(STD_B64)
    }
    expect(emergencyKit.secretKey).toMatch(STD_B64)
  })

  it('wraps the master key under PASSWORD and RECOVERY, each carrying kdfParams', () => {
    const methods = request.masterKeyWraps.map((w) => w.method)
    expect(methods).toEqual(['PASSWORD', 'RECOVERY'])
    for (const wrap of request.masterKeyWraps) expect(wrap.kdfParams).toBeTruthy()
  })
})

// Every derivation here is the test's own (nothing under test mints a wrap), so it runs at TEST_ARGON — the
// properties asserted are determinism and separation, which hold at any cost params, and the production params
// are pinned against the vectors by v1.conformance.test.ts. See packages/crypto/src/testArgon.ts.
describe('Model B auth derivation (ADR 0003)', () => {
  it('derives a deterministic salt + keypair from email+password', () => {
    const salt = v1.deriveAuthSalt(INSTANCE_SALT, 'user@example.com')
    expect(v1.deriveAuthSalt(INSTANCE_SALT, 'user@example.com')).toEqual(salt)
    // normalization: trim + lowercase
    expect(v1.deriveAuthSalt(INSTANCE_SALT, ' User@Example.com ')).toEqual(salt)

    // a different account (email) → different salt → different keypair (non-enumerable per-account)
    expect(v1.deriveAuthSalt(INSTANCE_SALT, 'other@example.com')).not.toEqual(salt)
    // a different instance (salt) → different salt, even for the same email (cross-instance separation)
    expect(v1.deriveAuthSalt(new Uint8Array(16).fill(9), 'user@example.com')).not.toEqual(salt)

    const a = v1.deriveAuthKeypair('pw-abc', salt, TEST_ARGON)
    const b = v1.deriveAuthKeypair('pw-abc', salt, TEST_ARGON)
    expect(b.publicKey).toEqual(a.publicKey)
    expect(v1.deriveAuthKeypair('different', salt, TEST_ARGON).publicKey).not.toEqual(a.publicKey)
  })
})

describe('registerServerOwner — API error propagation', () => {
  it('rejects when the server rejects the registration (drives the setup-failed UX)', async () => {
    vi.mocked(setupApi).mockReturnValue({
      getSetupStatus: () => Promise.resolve({ setupRequired: true, suites: ['v1'], instanceSalt: 'AAAAAAAAAAAAAAAAAAAAAA==' }),
      createServerOwner: () => Promise.reject(new Error('setup_already_complete')),
    } as unknown as ReturnType<typeof setupApi>)
    await expect(
      registerServerOwner('owner@example.com', 'correct horse battery'),
    ).rejects.toThrow('setup_already_complete')
    // registerServerOwner mints the whole account, so it pays three production-params Argon2id derivations
    // before it ever reaches the rejecting call — inherent cost, not a hang. See packages/crypto/src/testArgon.ts.
  }, PRODUCTION_ARGON_TIMEOUT_MS)
})
