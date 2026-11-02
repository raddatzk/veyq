// uc.spaces.set-profile-picture (ADR 0084, ADR 0115): the sealed member avatar. The constant length is load-bearing in two
// places - this module's pad and the contract's `maxLength` of every `sealedAvatar` - and the contract pin below reads the
// YAML itself, so a drift on either side is red. The picture is sealed under a fresh value key K_v whose wrap the tenant key
// holds: it opens only through that wrap.
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import {
  sealAvatar, openMemberAvatar, memberAvatarContext, AVATAR_PADDED_LEN, AVATAR_ENCODED_BUDGET, AVATAR_SEALED_B64_LEN, AVATAR_EDGE,
  type SealedMemberAvatar,
} from './memberAvatar'
import { sealName } from './memberName'
import { openValueKey, wrapValueKeyOf } from '../../spaces/valueKey'
import { ICON_PADDED_LEN } from '../../spaces/spaceIcon'
import { ICON_EDGE } from '../../spaces/spaceIconImage'

const KEY = new Uint8Array(32).fill(7)
const TENANT = { epoch: 0, key: KEY }
const AT = { scopeId: '5c09e000-0000-4000-8000-0000000000a1', userId: '5c09e000-0000-4000-8000-0000000000a2' }
const open = (s: SealedMemberAvatar, at = AT, tenant = TENANT) => openMemberAvatar(tenant, at, s.sealedAvatar, s.wrappedAvatarValueKey)

describe('member avatar (a field under its own value key K_v, purpose member-avatar, K_v wrapped under K_tenant)', () => {
  it('seals a flat and a dense image to byte-identical length (a photo cannot be told from a drawing)', () => {
    const flat = new Uint8Array(200).fill(0xff)
    const dense = new Uint8Array(6000)
    for (let i = 0; i < dense.length; i++) dense[i] = (i * 2654435761) & 0xff
    expect(sealAvatar(TENANT, AT, flat).sealedAvatar.length).toBe(sealAvatar(TENANT, AT, dense).sealedAvatar.length)
  })

  it('round-trips and pads to the constant 16 KiB plaintext (budget = pad - 4-byte header)', () => {
    const img = new Uint8Array([1, 2, 3, 250, 0, 128])
    expect([...open(sealAvatar(TENANT, AT, img))!]).toEqual([...img])
    expect(AVATAR_PADDED_LEN).toBe(ICON_PADDED_LEN)
    expect(AVATAR_PADDED_LEN).toBe(16384)
    expect(AVATAR_ENCODED_BUDGET).toBe(AVATAR_PADDED_LEN - 4)
    expect(AVATAR_EDGE).toBe(ICON_EDGE)
    expect(AVATAR_EDGE).toBe(256)
  })

  it('refuses an image over the budget instead of sealing a longer value', () => {
    expect(() => sealAvatar(TENANT, AT, new Uint8Array(AVATAR_ENCODED_BUDGET + 1))).toThrow()
    expect(() => sealAvatar(TENANT, AT, new Uint8Array(AVATAR_ENCODED_BUDGET))).not.toThrow()
  })

  it('is exactly 21908 base64 characters = 16429 bytes (1 type + 16 salt + 12 nonce + 16384 + 16 tag)', () => {
    const sealed = sealAvatar(TENANT, AT, new Uint8Array([1])).sealedAvatar
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

  it('every write mints a fresh K_v: the same picture twice is two different picture blobs and two different wraps', () => {
    const a = sealAvatar(TENANT, AT, new Uint8Array([1, 2, 3]))
    const b = sealAvatar(TENANT, AT, new Uint8Array([1, 2, 3]))
    expect(a.sealedAvatar).not.toBe(b.sealedAvatar)
    expect(a.wrappedAvatarValueKey).not.toBe(b.wrappedAvatarValueKey)
    const ka = openValueKey(TENANT, memberAvatarContext(AT), a.wrappedAvatarValueKey)
    const kb = openValueKey(TENANT, memberAvatarContext(AT), b.wrappedAvatarValueKey)
    expect(b64std(ka)).not.toBe(b64std(kb))
  })

  it('the tenant key alone does not open the picture: only the K_v its wrap carries does', () => {
    const s = sealAvatar(TENANT, AT, new Uint8Array([1, 2, 3]))
    expect(() => v1.openField(memberAvatarContext(AT), KEY, fromB64std(s.sealedAvatar))).toThrow()
    const kv = openValueKey(TENANT, memberAvatarContext(AT), s.wrappedAvatarValueKey)
    expect(v1.openField(memberAvatarContext(AT), kv, fromB64std(s.sealedAvatar)).length).toBe(AVATAR_PADDED_LEN) // control
  })

  it('a substituted wrap - a K_v of the rotator\'s choosing at this very slot - reads as the letter, not as another picture', () => {
    const mine = sealAvatar(TENANT, AT, new Uint8Array([1, 2, 3]))
    expect(open(mine)).not.toBeNull() // control
    const substituted = wrapValueKeyOf(TENANT, memberAvatarContext(AT), v1.generateValueKey())
    expect(open({ ...mine, wrappedAvatarValueKey: substituted })).toBeNull()
    // Another member's picture with its own K_v, re-wrapped onto this slot: it opens as no picture here either.
    const other = { ...AT, userId: '5c09e000-0000-4000-8000-0000000000a3' }
    const theirs = sealAvatar(TENANT, other, new Uint8Array([9, 9, 9]))
    const theirKey = openValueKey(TENANT, memberAvatarContext(other), theirs.wrappedAvatarValueKey)
    const moved = { sealedAvatar: theirs.sealedAvatar, wrappedAvatarValueKey: wrapValueKeyOf(TENANT, memberAvatarContext(AT), theirKey) }
    expect(open(moved)).toBeNull()
    expect(open(theirs, other)).not.toBeNull() // control: theirs opens at their own address
  })

  it('a wrap of another epoch, or one missing, opens to the letter', () => {
    const s = sealAvatar(TENANT, AT, new Uint8Array([1, 2, 3]))
    expect(open(s, AT, { epoch: 1, key: KEY })).toBeNull()
    expect(openMemberAvatar(TENANT, AT, s.sealedAvatar, null)).toBeNull()
    expect(openMemberAvatar(TENANT, AT, s.sealedAvatar, undefined)).toBeNull()
    expect(open(s)).not.toBeNull() // control
  })

  it('a picture sealed with the old 8 KiB pad opens to null (the letter), never as a picture', () => {
    const kv = v1.generateValueKey()
    const old = b64std(v1.sealField(memberAvatarContext(AT), kv, v1.padTo(new Uint8Array([1, 2, 3]), 8192)))
    expect(old.length).toBe(10984)
    expect(open({ sealedAvatar: old, wrappedAvatarValueKey: wrapValueKeyOf(TENANT, memberAvatarContext(AT), kv) })).toBeNull()
    expect(open(sealAvatar(TENANT, AT, new Uint8Array([1, 2, 3])))).not.toBeNull() // control
  })

  it('opens to null - never throws - on a wrong key, corrupt or empty input', () => {
    const s = sealAvatar(TENANT, AT, new Uint8Array([1, 2, 3]))
    expect(open(s, AT, { epoch: 0, key: new Uint8Array(32).fill(9) })).toBeNull()
    expect(open({ ...s, sealedAvatar: 'not-valid-base64!!' })).toBeNull()
    expect(open({ ...s, wrappedAvatarValueKey: 'not-valid-base64!!' })).toBeNull()
    expect(openMemberAvatar(TENANT, AT, '', s.wrappedAvatarValueKey)).toBeNull()
    expect(openMemberAvatar(TENANT, AT, null, s.wrappedAvatarValueKey)).toBeNull()
    expect(openMemberAvatar(TENANT, AT, undefined, s.wrappedAvatarValueKey)).toBeNull()
  })

  it("another member's blob and wrap on this row, or another tenant's, does not open (bound to scope and member)", () => {
    const s = sealAvatar(TENANT, AT, new Uint8Array([1, 2, 3]))
    expect(open(s, { ...AT, userId: '5c09e000-0000-4000-8000-0000000000a3' })).toBeNull()
    expect(open(s, { ...AT, scopeId: '5c09e000-0000-4000-8000-0000000000a4' })).toBeNull()
    expect(open(s)).not.toBeNull() // control
  })

  it('has its own purpose: a sealed name never opens as an avatar, nor an avatar as a name', () => {
    const name = sealName(TENANT, AT, { firstName: 'A', lastName: 'B' })
    expect(open({ sealedAvatar: name.sealedName, wrappedAvatarValueKey: name.wrappedNameValueKey })).toBeNull()
    // and the reverse, at the primitive: the avatar's ciphertext does not open under the member-name purpose
    const avatar = sealAvatar(TENANT, AT, new Uint8Array([1]))
    const kv = openValueKey(TENANT, memberAvatarContext(AT), avatar.wrappedAvatarValueKey)
    expect(() => v1.openField({ purpose: 'member-name', scopeId: AT.scopeId, userId: AT.userId }, kv, fromB64std(avatar.sealedAvatar))).toThrow()
  })
})
