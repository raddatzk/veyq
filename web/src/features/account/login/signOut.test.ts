// uc.account.sign-out § Main flow + E4 — the one sign-out sequence. What it owns: the convenience wraps go and the
// family is revoked WHILE the session works, the ending runs after them whether or not they succeeded, and a
// revoke that never reached the server is carried to the sign-in page as one word, read once.
import { describe, it, expect, vi, beforeEach } from 'vitest'

const { endSession, clearSessionVault, clearDeviceKey, logout } = vi.hoisted(() => ({
  endSession: vi.fn(),
  clearSessionVault: vi.fn(),
  clearDeviceKey: vi.fn(),
  logout: vi.fn(),
}))
vi.mock('../../../app/sessionEnd', () => ({ endSession }))
vi.mock('../rememberMe/sessionVault', () => ({ clearSessionVault }))
vi.mock('../rememberMe/deviceKeyWrap', () => ({ clearDeviceKey }))
vi.mock('../../../api/client', () => ({ sessionApi: () => ({ logout }) }))
// The refresh-and-retry is `sessionRefresh`'s own test; here a failed call is the failure the sequence sees.
vi.mock('../../../app/sessionRefresh', () => ({ withRefresh: <T>(call: () => Promise<T>) => call() }))

import { signOut, takeSignOutNotice } from './signOut'

/** `sessionStorage` is per tab and survives the document load the ending starts — that is what the notice rides. */
function fakeSessionStorage() {
  const map = new Map<string, string>()
  return {
    getItem: (k: string) => map.get(k) ?? null,
    setItem: (k: string, v: string) => void map.set(k, v),
    removeItem: (k: string) => void map.delete(k),
  }
}

const order: string[] = []
const status = (s: number) => ({ response: { status: s } })

beforeEach(() => {
  vi.clearAllMocks()
  vi.stubGlobal('sessionStorage', fakeSessionStorage())
  order.length = 0
  clearSessionVault.mockImplementation(async () => void order.push('session-vault'))
  clearDeviceKey.mockImplementation(async () => void order.push('device-key'))
  logout.mockImplementation(async () => void order.push('revoke'))
  // The notice must be in place BEFORE the ending: the ending's last step is the document load.
  endSession.mockImplementation(async () => void order.push(`ending(notice=${takeSignOutNoticePeek()})`))
})

/** Read the notice without consuming it, for the order assertion. */
function takeSignOutNoticePeek(): string | null {
  return sessionStorage.getItem('veyq.sign-out-notice')
}

describe('signOut (uc.account.sign-out)', () => {
  it('deletes the convenience wraps and revokes the family, then ends the session', async () => {
    await signOut()

    expect(order).toEqual(['session-vault', 'device-key', 'revoke', 'ending(notice=null)'])
    // The server half of the remember-me wrap goes too: no argument is clearDeviceKey's `deleteServerWrap = true`.
    expect(clearDeviceKey).toHaveBeenCalledWith()
    expect(takeSignOutNotice()).toBeNull()
  })

  it('still ends the session when the server is out of reach, and leaves the E4 notice (E4)', async () => {
    clearDeviceKey.mockRejectedValue(new TypeError('Failed to fetch'))
    logout.mockRejectedValue(new TypeError('Failed to fetch'))

    await signOut()

    expect(order).toEqual(['session-vault', 'ending(notice=unconfirmed)'])
    expect(takeSignOutNotice()).toBe('unconfirmed')
  })

  it('treats a 5xx like an unreachable server — the cookie is still live', async () => {
    logout.mockRejectedValue(status(503))

    await signOut()

    expect(endSession).toHaveBeenCalledTimes(1)
    expect(takeSignOutNotice()).toBe('unconfirmed')
  })

  it('leaves no notice when the server answered that the session is already gone (a 4xx)', async () => {
    // Revoked or expired: what signing out wanted, so the sign-in page has nothing to warn about.
    logout.mockRejectedValue(status(401))

    await signOut()

    expect(endSession).toHaveBeenCalledTimes(1)
    expect(takeSignOutNotice()).toBeNull()
  })

  it('says so when the locked screen signed every device out, this one included', async () => {
    await signOut({ everywhere: true })
    expect(takeSignOutNotice()).toBe('everywhere')
  })

  it('says the other devices are gone but this one is not, when that half did not reach the server', async () => {
    logout.mockRejectedValue(new TypeError('Failed to fetch'))
    await signOut({ everywhere: true })
    expect(takeSignOutNotice()).toBe('everywhereUnconfirmed')
  })

  it('hands the notice over once: reading it removes it', async () => {
    logout.mockRejectedValue(new TypeError('Failed to fetch'))
    await signOut()

    expect(takeSignOutNotice()).toBe('unconfirmed')
    expect(takeSignOutNotice()).toBeNull()
  })

  it('reads nothing it did not write — a foreign value is no notice', () => {
    sessionStorage.setItem('veyq.sign-out-notice', '<b>anything</b>')
    expect(takeSignOutNotice()).toBeNull()
  })
})
