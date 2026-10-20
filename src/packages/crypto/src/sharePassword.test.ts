// A password-protected share's guest credential (cr-0118, ADR 0037): the token is Argon2id(password, salt), the
// server keeps only its SHA-256 as the verifier. `src/web/` calls both; the mutation gate only sees this package's suite.
// Argon runs at TEST_ARGON — real Argon2id, ~1000× cheaper — because the test owns both sides (./testArgon.ts), and
// at production params Stryker would pay ~1 s per mutant this case covers.
import { describe, expect, it } from 'vitest'
import * as v1 from './v1'
import { TEST_ARGON } from './testArgon'

const hex = (u: Uint8Array) => Buffer.from(u).toString('hex')
const salt = new Uint8Array(16).fill(0x5a)

describe('share-password token', () => {
  it('is the 32-byte Argon2id of the password under the salt, at the given params', () => {
    const token = v1.sharesPwToken('correct horse', salt, TEST_ARGON)
    expect(token.length).toBe(32)
    // The same derivation the auth keypair's seed is (ADR 0003), so the two pin each other at equal inputs.
    expect(hex(token)).toBe(hex(v1.deriveAuthKeypair('correct horse', salt, TEST_ARGON).privateKey))
  })

  it('is bound to the salt and to the password', () => {
    const token = hex(v1.sharesPwToken('correct horse', salt, TEST_ARGON))
    expect(hex(v1.sharesPwToken('correct horse', new Uint8Array(16).fill(0x5b), TEST_ARGON))).not.toBe(token)
    expect(hex(v1.sharesPwToken('correct horsf', salt, TEST_ARGON))).not.toBe(token)
  })
})

describe('sha256Digest (the token\'s server-side verifier)', () => {
  it('is SHA-256 (FIPS 180-2 "abc")', () => {
    expect(hex(v1.sha256Digest(new TextEncoder().encode('abc')))).toBe(
      'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad',
    )
  })
})
