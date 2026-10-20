import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import * as v1 from '@veyq/crypto/v1'

const vectors = JSON.parse(
  readFileSync(resolve(__dirname, '../../../../../contracts/crypto/vectors/v1.json'), 'utf8'),
) as { kdf_params: { params: v1.ArgonParams; salt: string; encoded: string } }
const K = vectors.kdf_params
const unhex = (h: string) => Uint8Array.from(h.match(/../g)!.map((b) => parseInt(b, 16)))
const hex = (u: Uint8Array) => Array.from(u, (b) => b.toString(16).padStart(2, '0')).join('')

// The Argon2id params every KDF web writes carries with it: account birth, recovery, change password, key
// rotation and trust-device all derive at `v1.ARGON` and upload `encodeKdfParams(v1.ARGON, salt)` as the wrap's
// kdfParams. packages/crypto pins both against the same vector, but that job has never run, so ADR 0040 admits
// none of its suites; these are the pins on an admitted row.
describe('the Argon2id params web writes', () => {
  // Its limit: it holds the constant, not a call site that stops reading it.
  it("v1.ARGON is the contract's production kdf_params (cost is not silently downgradable)", () => {
    // toEqual on the whole object, so a removed field fails as well as a changed one.
    expect(v1.ARGON).toEqual(K.params)
  })

  // Web decodes its own kdfParams with decodeKdfParams, so a layout change made to both halves round-trips
  // through every other web test. Mobile decodes these bytes at login, so the pinned bytes are the check.
  it('encodeKdfParams lays the params and salt out as the contract bytes (u32be t || m || p || dkLen || salt)', () => {
    expect(hex(v1.encodeKdfParams(K.params, unhex(K.salt)))).toBe(K.encoded)
  })
})
