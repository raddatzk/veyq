import { describe, it, expect } from 'vitest'
import { fromB64std } from '@veyq/crypto/base64'
import { deriveSpaceIconKey, sealIcon, openIcon, pad, unpad, ICON_PADDED_LEN } from './spaceIcon'

describe('space icon primitives (uc.spaces.set-space-icon)', () => {
  const key = new Uint8Array(32).fill(7)
  // Where the icon is sealed: its space and the epoch K_icon derives from (ADR 0072).
  const AT = { scopeId: '5c09e000-0000-4000-8000-0000000000a9', iconEpoch: 0 }

  it('seals two very different images to byte-identical ciphertext length (the constant-length invariant)', () => {
    // A flat 200-byte "logo" vs a dense 9 KB "photo": their encoded sizes differ wildly, but the padded seal is a
    // constant, so the operator cannot tell one from the other by length — and the tenant overview handing one
    // length per tenant leaks nothing. This is the CR's promoted permanent guard: it replaces a one-shot "I
    // checked two icons", so a future quality tweak that reintroduced a variable length reds it here.
    const flat = new Uint8Array(200).fill(0xff)
    const dense = new Uint8Array(9000)
    for (let i = 0; i < dense.length; i++) dense[i] = (i * 2654435761) & 0xff
    expect(sealIcon(key, AT, flat).length).toBe(sealIcon(key, AT, dense).length)
  })

  it('round-trips openIcon(sealIcon(x)) === x', () => {
    const img = new Uint8Array([1, 2, 3, 4, 250, 0, 128])
    const opened = openIcon(key, AT, sealIcon(key, AT, img))
    expect(opened).not.toBeNull()
    expect([...opened!]).toEqual([...img])
  })

  it('pad is always the constant length and unpad(pad(x)) === x', () => {
    const img = new Uint8Array([9, 8, 7])
    expect(pad(img).length).toBe(ICON_PADDED_LEN)
    expect([...unpad(pad(img))]).toEqual([...img])
  })

  it('openIcon returns null — never throws — on a wrong key or corrupt ciphertext (E2 → the initial)', () => {
    const sealed = sealIcon(key, AT, new Uint8Array([1, 2, 3]))
    expect(openIcon(new Uint8Array(32).fill(9), AT, sealed)).toBeNull() // wrong key
    expect(openIcon(key, AT, 'not-valid-base64!!')).toBeNull()
    expect(openIcon(key, AT, '')).toBeNull()
  })

  it("an icon served for another space or icon epoch does not open — the initial, not another space's image", () => {
    const sealed = sealIcon(key, AT, new Uint8Array([1, 2, 3]))
    expect(openIcon(key, { ...AT, scopeId: '5c09e000-0000-4000-8000-0000000000aa' }, sealed)).toBeNull()
    expect(openIcon(key, { ...AT, iconEpoch: 1 }, sealed)).toBeNull()
    expect(openIcon(key, AT, sealed)).not.toBeNull() // control
  })

  it('rejects an over-budget image (the caller steps quality down until it fits)', () => {
    expect(() => pad(new Uint8Array(ICON_PADDED_LEN))).toThrow()
  })

  it('the padded seal is exactly 21884 base64 chars — inside TEXT (65535) and the server @Size cap', () => {
    // The number the contract's `@Size(max = 21884)` and the V40 `TEXT` column both rest on: sealed = 1 + 12 +
    // 16384 + 16 = 16413 bytes → base64 = 21884 chars. If ICON_PADDED_LEN changes, this test and the contract
    // must move together — that is the whole point of pinning it here (#94's class, made impossible for the icon).
    const sealed = sealIcon(key, AT, new Uint8Array([1]))
    expect(sealed.length).toBe(21884)
    expect(fromB64std(sealed).length).toBe(16413)
  })

  it('a different epoch key derives a different K_icon (forward-secret across a re-key)', () => {
    const a = deriveSpaceIconKey(new Uint8Array(32).fill(1))
    const b = deriveSpaceIconKey(new Uint8Array(32).fill(2))
    expect([...a]).not.toEqual([...b])
  })
})
