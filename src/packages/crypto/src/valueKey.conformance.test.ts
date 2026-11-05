// A scope-level value's own key (ADR 0115, owner 2026-11-02) against contracts/crypto/vectors/v1.json#value_key and
// seal.sym['value-key'] — written by an independent implementation. A rotation re-wraps K_v and never rewrites the
// value, so what a rotator can do to a value is bounded here: a wrap moved onto another value's slot does not open, and
// the value under a K_v it substituted does not open either — unreadable, never different. Each refusal sits beside a
// positive control on the same inputs.
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createHash } from 'node:crypto'
import * as v1 from './v1'
import { FIELD_PURPOSES, SEAL_PURPOSES, VALUE_KEY_PURPOSES } from './seal'

const unhex = (s: string) => Uint8Array.from(Buffer.from(s, 'hex'))
const hex = (u: Uint8Array) => Buffer.from(u).toString('hex')
const here = dirname(fileURLToPath(import.meta.url))
const vectors = JSON.parse(readFileSync(resolve(here, '../../../../contracts/crypto/vectors/v1.json'), 'utf8'))
const vk = vectors.value_key
const wrap = vectors.seal.sym['value-key']
const name = vectors.field_seal['member-name']

/** A value purpose's vector — its plain seal's or its field seal's. */
const vectorOf = (purpose: string) => vectors.seal.sym[purpose] ?? vectors.field_seal[purpose]
const contextOf = (purpose: string): v1.ValueContext => ({ purpose, ...vectorOf(purpose).context }) as v1.ValueContext
const nameCtx = contextOf('member-name')
/** The same member-name address, for another member of the tenant. */
const otherMember = { ...nameCtx, userId: 'c0ffee00-0000-4000-8000-000000000012' } as v1.ValueContext
const slot = (value: v1.ValueContext = nameCtx, epoch: number = wrap.context.epoch): v1.ValueKeySlot => ({
  scopeId: wrap.context.scopeId,
  epoch,
  value,
})

describe('value key (#value_key, seal.sym[\'value-key\'], ADR 0115)', () => {
  it('every value purpose is a live seal or field purpose, and has an address pinned', () => {
    for (const p of VALUE_KEY_PURPOSES) expect(Object.hasOwn(SEAL_PURPOSES, p) || Object.hasOwn(FIELD_PURPOSES, p), p).toBe(true)
    expect(Object.keys(vk.addresses).sort()).toEqual([...VALUE_KEY_PURPOSES].sort())
  })

  it.each([...VALUE_KEY_PURPOSES])('%s: its address is SHA-256 of its own context bytes', (purpose) => {
    const expected = createHash('sha256').update(unhex(vectorOf(purpose).context_bytes)).digest('hex')
    expect(vk.addresses[purpose]).toBe(expected)
    expect(hex(v1.valueAddress(contextOf(purpose)))).toBe(expected)
  })

  it('the vector\'s wrap is addressed by the member name whose field key its K_v derives', () => {
    expect(vk.value).toBe('member-name')
    expect(wrap.context.valueAddress).toBe(vk.addresses['member-name'])
    expect(wrap.context.scopeId).toBe(name.context.scopeId)
    expect(wrap.plaintext).toBe(name.parent_key)
  })

  it('opens top-down: K_v under the scope key at the value\'s address, the name under K_v', () => {
    const kv = v1.unwrapValueKey(unhex(wrap.kek), slot(), unhex(wrap.blob))
    expect(hex(kv)).toBe(name.parent_key)
    expect(hex(v1.openField({ purpose: 'member-name', ...name.context }, kv, unhex(name.envelope)))).toBe(name.plaintext)
  })

  it('wrapValueKey seals the vector\'s context: a fresh wrap opens with the same slot and key', () => {
    const fresh = v1.wrapValueKey(unhex(wrap.kek), slot(), unhex(wrap.plaintext))
    expect(hex(fresh)).not.toBe(wrap.blob)
    expect(hex(v1.unwrapValueKey(unhex(wrap.kek), slot(), fresh))).toBe(wrap.plaintext)
  })

  it('the scope key itself does not open the value: only K_v does', () => {
    expect(() => v1.openField({ purpose: 'member-name', ...name.context }, unhex(wrap.kek), unhex(name.envelope)))
      .toThrow(new v1.OpenRefused('member-name'))
  })

  it('a wrap moved onto another value\'s slot does not open there (control: its own slot opens)', () => {
    expect(hex(v1.unwrapValueKey(unhex(wrap.kek), slot(), unhex(wrap.blob)))).toBe(wrap.plaintext)
    expect(() => v1.unwrapValueKey(unhex(wrap.kek), slot(otherMember), unhex(wrap.blob))).toThrow(new v1.OpenRefused('value-key'))
    // Another purpose of the same member — their picture's slot — is another address too.
    expect(() => v1.unwrapValueKey(unhex(wrap.kek), slot(contextOf('member-avatar')), unhex(wrap.blob)))
      .toThrow(new v1.OpenRefused('value-key'))
  })

  it('a wrap of another epoch does not open as this one', () => {
    expect(() => v1.unwrapValueKey(unhex(wrap.kek), slot(nameCtx, wrap.context.epoch + 1), unhex(wrap.blob)))
      .toThrow(new v1.OpenRefused('value-key'))
  })

  it('the value under a substituted wrap does not open: unreadable, not different', () => {
    // A rotator holds the scope key, so it can wrap a K_v of its choosing at this very slot — the wrap opens, the value
    // does not. Control: the genuine K_v at the same slot opens the same envelope.
    const scopeKey = unhex(wrap.kek)
    const genuine = v1.unwrapValueKey(scopeKey, slot(), unhex(wrap.blob))
    expect(hex(v1.openField({ purpose: 'member-name', ...name.context }, genuine, unhex(name.envelope)))).toBe(name.plaintext)
    const substituted = v1.wrapValueKey(scopeKey, slot(), v1.generateValueKey())
    const forged = v1.unwrapValueKey(scopeKey, slot(), substituted)
    expect(hex(forged)).not.toBe(name.parent_key)
    expect(() => v1.openField({ purpose: 'member-name', ...name.context }, forged, unhex(name.envelope)))
      .toThrow(new v1.OpenRefused('member-name'))
    // A K_v another value's wrap carries, re-wrapped into this slot, opens no envelope here either.
    const othersKey = v1.generateValueKey()
    const othersName = v1.sealField(otherMember as v1.FieldContext, othersKey, v1.utf8('Mallory'))
    const moved = v1.unwrapValueKey(scopeKey, slot(), v1.wrapValueKey(scopeKey, slot(), othersKey))
    expect(() => v1.openField({ purpose: 'member-name', ...name.context }, moved, unhex(name.envelope)))
      .toThrow(new v1.OpenRefused('member-name'))
    // ...and the other value's own envelope, moved here along with its key, does not open at this address.
    expect(() => v1.openField({ purpose: 'member-name', ...name.context }, moved, othersName)).toThrow(new v1.OpenRefused('member-name'))
  })

  it('a rotation re-wraps K_v under the next epoch\'s key and leaves the value\'s bytes as they were', () => {
    const r = vk.rewrap
    const kv = v1.unwrapValueKey(unhex(wrap.kek), slot(), unhex(wrap.blob))
    const ctx = { purpose: 'value-key', scopeId: wrap.context.scopeId, epoch: r.epoch, valueAddress: unhex(wrap.context.valueAddress) } as const
    expect(hex(v1.encodeSealContext(ctx))).toBe(r.context_bytes)
    expect(hex(v1.unwrapValueKey(unhex(r.kek), slot(nameCtx, r.epoch), unhex(r.blob)))).toBe(hex(kv))
    // The old key opens no wrap of the new epoch, and the new key none of the old one.
    expect(() => v1.unwrapValueKey(unhex(wrap.kek), slot(nameCtx, r.epoch), unhex(r.blob))).toThrow(new v1.OpenRefused('value-key'))
    expect(() => v1.unwrapValueKey(unhex(r.kek), slot(), unhex(wrap.blob))).toThrow(new v1.OpenRefused('value-key'))
    const reopened = v1.unwrapValueKey(unhex(r.kek), slot(nameCtx, r.epoch), unhex(r.blob))
    expect(hex(v1.openField({ purpose: 'member-name', ...name.context }, reopened, unhex(name.envelope)))).toBe(name.plaintext)
  })

  it('a purpose that is not sealed under a value key has no address', () => {
    const entry = { purpose: 'content-key', ...vectors.seal.sym['content-key'].context } as unknown as v1.ValueContext
    expect(() => v1.valueAddress(entry)).toThrow(new v1.SealContextInvalid('content-key is not sealed under a value key'))
    const wrapOfAWrap = { purpose: 'value-key', ...wrap.context } as unknown as v1.ValueContext
    expect(() => v1.valueAddress(wrapOfAWrap)).toThrow(new v1.SealContextInvalid('value-key is not sealed under a value key'))
    // Control: a value purpose with the same shape check passes.
    expect(v1.valueAddress(nameCtx)).toHaveLength(32)
  })

  it('a value key is 32 random bytes, fresh at every write; wrapValueKey refuses any other length', () => {
    const a = v1.generateValueKey()
    const b = v1.generateValueKey()
    expect(a).toHaveLength(v1.VALUE_KEY_BYTES)
    expect(hex(a)).not.toBe(hex(b))
    expect(() => v1.wrapValueKey(unhex(wrap.kek), slot(), a.slice(0, 31))).toThrow(new TypeError('value key: not 32 bytes'))
    expect(() => v1.wrapValueKey(unhex(wrap.kek), slot(), new Uint8Array(33))).toThrow(new TypeError('value key: not 32 bytes'))
  })
})
