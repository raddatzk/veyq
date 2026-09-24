import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import * as v1 from '@veyq/crypto/v1'

const vectors = JSON.parse(
  readFileSync(resolve(__dirname, '../../../../contracts/crypto/vectors/v1.json'), 'utf8'),
) as { kdf_params: { params: v1.ArgonParams } }

// `v1.ARGON` is the Argon2id cost of every KDF web writes: account birth, recovery, change password, key
// rotation and trust-device all derive at it and upload it as the wrap's kdfParams. packages/crypto pins the
// same constant against the same vector, but that job has never run, so ADR 0040 admits none of its suites;
// this is the pin on an admitted row. Its limit: it holds the constant, not a call site that stops reading it.
describe('the Argon2id cost web derives at', () => {
  it("v1.ARGON is the contract's production kdf_params (cost is not silently downgradable)", () => {
    // toEqual on the whole object, so a removed field fails as well as a changed one.
    expect(v1.ARGON).toEqual(vectors.kdf_params.params)
  })
})
