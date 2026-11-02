// A scope-level value's own key (ADR 0115, `valueKey.ts`): every write seals its value under a fresh K_v and wraps K_v
// under the scope key at the value's address; a reader opens the wrap, then the value; a rotation re-wraps the same K_v
// and never touches the value; a wrap it cannot open becomes one nobody opens. Each refusal beside a positive control.
import { describe, it, expect } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { fromB64std } from '@veyq/crypto/base64'
import { openValue, openValueKey, rewrapValueKey, sealValue, wrapValueKeyOf } from './valueKey'
import type { ScopeKey } from './epochs'

const TENANT = '5c09e000-0000-4000-8000-0000000000d1'
const ADA = '5c09e000-0000-4000-8000-0000000000d2'
const BOB = '5c09e000-0000-4000-8000-0000000000d3'
const OLD: ScopeKey = { epoch: 2, key: v1.randomBytes(32) }
const NEW: ScopeKey = { epoch: 3, key: v1.randomBytes(32) }
const nameOf = (userId: string) => ({ purpose: 'member-name', scopeId: TENANT, userId }) as const
const text = new TextDecoder()

/** A member's name as its writer seals it, at [scope]. */
const write = (userId: string, name: string, scope: ScopeKey = OLD) =>
  sealValue(scope, nameOf(userId), (k) => v1.sealField(nameOf(userId), k, v1.utf8(name)))
/** The name a reader opens through [wrapped] with [scope]. */
const read = (userId: string, sealed: string, wrapped: string, scope: ScopeKey = OLD) =>
  openValue(scope, nameOf(userId), wrapped, (k) => text.decode(v1.openField(nameOf(userId), k, fromB64std(sealed))))

describe('a value under a key of its own (ADR 0115)', () => {
  it('a writer\'s value opens through its value key — and never under the scope key itself', () => {
    const ada = write(ADA, 'Ada')
    expect(read(ADA, ada.sealed, ada.wrapped)).toBe('Ada') // control
    expect(() => v1.openField(nameOf(ADA), OLD.key, fromB64std(ada.sealed))).toThrow(v1.OpenRefused)
  })

  it('every write mints a fresh value key: two writes of the same value share no key', () => {
    const first = write(ADA, 'Ada')
    const second = write(ADA, 'Ada')
    expect(first.wrapped).not.toBe(second.wrapped)
    expect(openValueKey(OLD, nameOf(ADA), first.wrapped)).not.toEqual(openValueKey(OLD, nameOf(ADA), second.wrapped))
    expect(() => read(ADA, first.sealed, second.wrapped)).toThrow(v1.OpenRefused)
  })

  it('a substituted wrap reads as unreadable, not as another value', () => {
    const ada = write(ADA, 'Ada')
    const bob = write(BOB, 'Bob')
    // Bob's wrap moved onto Ada's slot does not open there (control: at Bob's own slot it does)
    expect(read(BOB, bob.sealed, bob.wrapped)).toBe('Bob')
    expect(() => read(ADA, ada.sealed, bob.wrapped)).toThrow(v1.OpenRefused)
    // a holder of the scope key wraps Bob's K_v at Ada's slot: the wrap opens, but it opens neither Ada's name …
    const bobsKey = openValueKey(OLD, nameOf(BOB), bob.wrapped)
    const forged = wrapValueKeyOf(OLD, nameOf(ADA), bobsKey)
    expect(openValueKey(OLD, nameOf(ADA), forged)).toEqual(bobsKey)
    expect(() => read(ADA, ada.sealed, forged)).toThrow(v1.OpenRefused)
    // … nor Bob's name served as Ada's: each envelope is bound to its own member
    expect(() => read(ADA, bob.sealed, forged)).toThrow(v1.OpenRefused)
    // nor does a fresh K_v of the forger's choosing open the genuine envelope
    expect(() => read(ADA, ada.sealed, wrapValueKeyOf(OLD, nameOf(ADA), v1.generateValueKey()))).toThrow(v1.OpenRefused)
    // a wrap is also bound to its epoch: OLD's key at another epoch's slot opens nothing
    expect(() => read(ADA, ada.sealed, ada.wrapped, { epoch: OLD.epoch + 1, key: OLD.key })).toThrow(v1.OpenRefused)
  })

  it('a rotation re-wraps the same key under the new one and leaves the value byte-identical', () => {
    const ada = write(ADA, 'Ada')
    const moved = rewrapValueKey(OLD, NEW, nameOf(ADA), ada.wrapped)
    expect(read(ADA, ada.sealed, moved, NEW)).toBe('Ada')
    expect(openValueKey(NEW, nameOf(ADA), moved)).toEqual(openValueKey(OLD, nameOf(ADA), ada.wrapped))
    expect(() => read(ADA, ada.sealed, moved, OLD)).toThrow(v1.OpenRefused) // the new wrap is shut to the old key
    expect(() => read(ADA, ada.sealed, ada.wrapped, NEW)).toThrow(v1.OpenRefused) // and the old wrap to the new key
  })

  it('an unopenable wrap stages the stand-in: at the same slot, of the same length, under no key anyone holds', () => {
    const ada = write(ADA, 'Ada')
    const stray = write(ADA, 'Ada', { epoch: OLD.epoch, key: v1.randomBytes(32) }) // under a key the rotator lacks
    const standIn = rewrapValueKey(OLD, NEW, nameOf(ADA), stray.wrapped)
    expect(fromB64std(standIn).length).toBe(fromB64std(stray.wrapped).length)
    for (const key of [NEW, OLD]) expect(() => openValueKey(key, nameOf(ADA), standIn)).toThrow(v1.OpenRefused)
    expect(() => read(ADA, stray.sealed, standIn, NEW)).toThrow(v1.OpenRefused)
    // a wrap of another value is refused the same way — never re-wrapped onto this slot
    const bob = write(BOB, 'Bob')
    expect(() => openValueKey(NEW, nameOf(ADA), rewrapValueKey(OLD, NEW, nameOf(ADA), bob.wrapped))).toThrow(v1.OpenRefused)
    // control: the genuine wrap beside them moves
    expect(read(ADA, ada.sealed, rewrapValueKey(OLD, NEW, nameOf(ADA), ada.wrapped), NEW)).toBe('Ada')
  })

  it('a missing wrap opens nothing, and a value key the reader opened is zeroed after', () => {
    const ada = write(ADA, 'Ada')
    expect(() => openValue(OLD, nameOf(ADA), null, () => 'x')).toThrow(v1.OpenRefused)
    let seen: Uint8Array | null = null
    openValue(OLD, nameOf(ADA), ada.wrapped, (k) => { seen = k })
    expect(seen!.every((b) => b === 0)).toBe(true)
  })
})
