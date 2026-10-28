// The vault owns what it is handed: the key material it replaces is zeroed (uc.account.rotate-keys — the
// identity a secured account retired must not linger in the heap), and what it still holds is never touched.
import { describe, it, expect, afterEach } from 'vitest'
import { clearVault, identityPrivate, masterKey, setUnlocked } from './vault'

const filled = (n: number) => new Uint8Array(32).fill(n)
const zeroed = (b: Uint8Array) => b.every((x) => x === 0)

afterEach(() => clearVault())

describe('setUnlocked', () => {
  it('zeroes the Master Key and identity it replaces, and holds the new ones', () => {
    const oldMk = filled(1)
    const oldPriv = { kemSeed: filled(2), sign: filled(3) }
    setUnlocked(oldMk, oldPriv, 'cold')
    setUnlocked(filled(4), { kemSeed: filled(5), sign: filled(6) }, 'cold')
    expect(zeroed(oldMk) && zeroed(oldPriv.kemSeed) && zeroed(oldPriv.sign)).toBe(true)
    expect(masterKey()).toEqual(filled(4))
    expect(identityPrivate()).toEqual({ kemSeed: filled(5), sign: filled(6) })
  })

  it('control: handed the very buffers it holds, it zeroes nothing', () => {
    const mk = filled(7)
    const priv = { kemSeed: filled(8), sign: filled(9) }
    setUnlocked(mk, priv, 'device')
    setUnlocked(mk, priv, 'cold')
    expect(masterKey()).toEqual(filled(7))
    expect(identityPrivate()).toEqual({ kemSeed: filled(8), sign: filled(9) })
  })
})
