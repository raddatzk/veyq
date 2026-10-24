// uc.spaces.set-profile-picture (ADR 0084): the sealed member avatar. The constant length is load-bearing in two places - this
// module's pad and the contract's `maxLength` of every `sealedAvatar` - and the contract pin below reads the YAML itself, so a
// drift on either side is red.
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import {
  sealAvatar, openAvatar, AVATAR_PADDED_LEN, AVATAR_ENCODED_BUDGET, AVATAR_SEALED_B64_LEN, AVATAR_EDGE,
} from './memberAvatar'
import { sealName } from './memberName'
import { ICON_PADDED_LEN } from '../../spaces/spaceIcon'
import { ICON_EDGE } from '../../spaces/spaceIconImage'

const KEY = new Uint8Array(32).fill(7)
const AT = { scopeId: '5c09e000-0000-4000-8000-0000000000a1', userId: '5c09e000-0000-4000-8000-0000000000a2' }

describe('member avatar (a field under K_tenant, purpose member-avatar)', () => {
  it('seals a flat and a dense image to byte-identical length (a photo cannot be told from a drawing)', () => {
    const flat = new Uint8Array(200).fill(0xff)
    const dense = new Uint8Array(6000)
    for (let i = 0; i < dense.length; i++) dense[i] = (i * 2654435761) & 0xff
    expect(sealAvatar(KEY, AT, flat).length).toBe(sealAvatar(KEY, AT, dense).length)
  })

  it('round-trips and pads to the constant 16 KiB plaintext (budget = pad - 4-byte header)', () => {
    const img = new Uint8Array([1, 2, 3, 250, 0, 128])
    expect([...openAvatar(KEY, AT, sealAvatar(KEY, AT, img))!]).toEqual([...img])
    expect(AVATAR_PADDED_LEN).toBe(ICON_PADDED_LEN)
    expect(AVATAR_PADDED_LEN).toBe(16384)
    expect(AVATAR_ENCODED_BUDGET).toBe(AVATAR_PADDED_LEN - 4)
    expect(AVATAR_EDGE).toBe(ICON_EDGE)
    expect(AVATAR_EDGE).toBe(256)
  })

  it('refuses an image over the budget instead of sealing a longer value', () => {
    expect(() => sealAvatar(KEY, AT, new Uint8Array(AVATAR_ENCODED_BUDGET + 1))).toThrow()
    expect(() => sealAvatar(KEY, AT, new Uint8Array(AVATAR_ENCODED_BUDGET))).not.toThrow()
  })

  it('is exactly 21908 base64 characters = 16429 bytes (1 type + 16 salt + 12 nonce + 16384 + 16 tag)', () => {
    const sealed = sealAvatar(KEY, AT, new Uint8Array([1]))
    expect(sealed.length).toBe(AVATAR_SEALED_B64_LEN)
    expect(AVATAR_SEALED_B64_LEN).toBe(21908)
    expect(fromB64std(sealed).length).toBe(16429)
    expect(fromB64std(sealed)[0]).toBe(0x03)
  })

  it('equals the maxLength of every sealedAvatar field in the contract (a mismatch is red)', () => {
    const lengths: number[] = []
    for (const file of ['account.openapi.yaml', 'tenants.openapi.yaml']) {
      const yaml = readFileSync(new URL(`../../../../../../contracts/api/${file}`, import.meta.url), 'utf8')
      for (const m of yaml.matchAll(/ {8}sealedAvatar:\n([\s\S]*?)(?=\n {8}\S|\n {4}\S|\n\S)/g)) {
        const max = /maxLength: (\d+)/.exec(m[1])
        if (max) lengths.push(Number(max[1]))
      }
    }
    // SetAvatarRequest, MemberAvatar and the two accept bodies
    expect(lengths.length).toBeGreaterThanOrEqual(4)
    expect(new Set(lengths)).toEqual(new Set([AVATAR_SEALED_B64_LEN]))
  })

  it('a picture sealed with the old 8 KiB pad opens to null (the letter), never as a picture', () => {
    const old = b64std(v1.sealField({ purpose: 'member-avatar', scopeId: AT.scopeId, userId: AT.userId }, KEY, v1.padTo(new Uint8Array([1, 2, 3]), 8192)))
    expect(old.length).toBe(10984)
    expect(openAvatar(KEY, AT, old)).toBeNull()
    expect(openAvatar(KEY, AT, sealAvatar(KEY, AT, new Uint8Array([1, 2, 3])))).not.toBeNull() // control
  })

  it('opens to null - never throws - on a wrong key, corrupt or empty input', () => {
    const sealed = sealAvatar(KEY, AT, new Uint8Array([1, 2, 3]))
    expect(openAvatar(new Uint8Array(32).fill(9), AT, sealed)).toBeNull()
    expect(openAvatar(KEY, AT, 'not-valid-base64!!')).toBeNull()
    expect(openAvatar(KEY, AT, '')).toBeNull()
    expect(openAvatar(KEY, AT, null)).toBeNull()
    expect(openAvatar(KEY, AT, undefined)).toBeNull()
  })

  it("another member's blob on this row, or another tenant's, does not open (bound to scope and member)", () => {
    const sealed = sealAvatar(KEY, AT, new Uint8Array([1, 2, 3]))
    expect(openAvatar(KEY, { ...AT, userId: '5c09e000-0000-4000-8000-0000000000a3' }, sealed)).toBeNull()
    expect(openAvatar(KEY, { ...AT, scopeId: '5c09e000-0000-4000-8000-0000000000a4' }, sealed)).toBeNull()
    expect(openAvatar(KEY, AT, sealed)).not.toBeNull() // control
  })

  it('has its own purpose: a sealed name never opens as an avatar, nor an avatar as a name', () => {
    const name = sealName(KEY, AT, { firstName: 'A', lastName: 'B' })
    expect(openAvatar(KEY, AT, name)).toBeNull()
    // and the reverse, at the primitive: the avatar's ciphertext does not open under the member-name purpose
    const avatar = fromB64std(sealAvatar(KEY, AT, new Uint8Array([1])))
    expect(() => v1.openField({ purpose: 'member-name', scopeId: AT.scopeId, userId: AT.userId }, KEY, avatar)).toThrow()
  })
})
