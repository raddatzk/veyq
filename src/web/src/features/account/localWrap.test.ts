// "Which convenience wrap does THIS device hold?" — the local-truth reader behind write moment 1 of the device
// register and behind ADR 0058 §5's exclusion-shaped bulk action. Both callers are places where consulting the
// server's list instead would be the mistake, so the property under test is *where the answer comes from* as
// much as what it is.
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('./trustDevice/deviceKey', () => ({ loadTrust: vi.fn() }))
vi.mock('./rememberMe/deviceKeyWrap', () => ({ rememberedWrapId: vi.fn() }))

import { localWrapId } from './localWrap'
import { loadTrust } from './trustDevice/deviceKey'
import { rememberedWrapId } from './rememberMe/deviceKeyWrap'

const EMAIL = 'a@b.com'

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(loadTrust).mockResolvedValue(null)
  vi.mocked(rememberedWrapId).mockResolvedValue(null)
})

describe('localWrapId', () => {
  it('answers with the password DEVICE wrap when this device is trusted', async () => {
    vi.mocked(loadTrust).mockResolvedValue({ cryptoKey: {} as CryptoKey, wrapId: 'w-trust' })
    expect(await localWrapId(EMAIL)).toBe('w-trust')
    expect(rememberedWrapId).not.toHaveBeenCalled() // one tier per device (ADR 0005 §9)
  })

  it('falls through to the password-less DEVICE_KEY wrap when this device is remembered', async () => {
    vi.mocked(rememberedWrapId).mockResolvedValue('w-remember')
    expect(await localWrapId(EMAIL)).toBe('w-remember')
  })

  it('a floor-only device holds none', async () => {
    expect(await localWrapId(EMAIL)).toBeNull()
  })

  // "None" is the safe answer for both callers: write moment 1 writes no join row, and the exclusion-shaped bulk
  // action excludes nothing — never "delete everything I cannot verify", which is the inverted, dangerous shape.
  it('answers "none" rather than throwing when local storage is unusable', async () => {
    vi.mocked(loadTrust).mockRejectedValue(new Error('idb_denied'))
    vi.mocked(rememberedWrapId).mockRejectedValue(new Error('idb_denied'))
    expect(await localWrapId(EMAIL)).toBeNull()
  })

  it('asks nothing at all without an account to ask about', async () => {
    expect(await localWrapId('')).toBeNull()
    expect(loadTrust).not.toHaveBeenCalled()
  })
})
