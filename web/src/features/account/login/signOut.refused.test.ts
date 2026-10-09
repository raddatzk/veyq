// inbox #322 on the sign-out path: a refused refresh says `refused` on the sign-in page, EXCEPT under the sign-out
// sequence itself — a session that was already over is what signing out wanted, so that page says nothing. The
// refresh, the ending and the notice run for real (signOut.test.ts stubs `withRefresh` away and cannot see this); only
// the endpoints and the steps that reach outside the page are stubbed.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { httpError } from '../../../test/httpError'
import { fakeApi } from '../../../test/apiMock'
import { memoryStorage } from '../../../test/memoryStorage'

vi.mock('../../../api/client', () => ({ tokenApi: vi.fn(), sessionApi: vi.fn() }))
vi.mock('../../../app/convenienceTiers', () => ({ dropConvenienceTiers: vi.fn() }))
vi.mock('../../../sw/register', () => ({ endAllPlaybacks: vi.fn() }))
vi.mock('../rememberMe/sessionVault', () => ({ clearSessionVault: vi.fn() }))
vi.mock('../rememberMe/deviceKeyWrap', () => ({ clearDeviceKey: vi.fn() }))

const replace = vi.fn()

beforeEach(() => {
  replace.mockClear()
  vi.stubGlobal('location', { replace })
  vi.stubGlobal('BroadcastChannel', undefined)
  vi.stubGlobal('sessionStorage', memoryStorage())
})
afterEach(() => {
  vi.unstubAllGlobals()
})

describe('a sign-out whose session the server already refuses (inbox #322)', () => {
  it('ends the session and leaves no notice: not `refused`, the sign-out was the person’s own', async () => {
    vi.resetModules()
    const { signOut, takeSignOutNotice } = await import('./signOut')
    const { session } = await import('../../../app/session')
    const { tokenApi, sessionApi } = await import('../../../api/client')
    vi.mocked((await import('../rememberMe/sessionVault')).clearSessionVault).mockResolvedValue(undefined)
    vi.mocked((await import('../rememberMe/deviceKeyWrap')).clearDeviceKey).mockResolvedValue(undefined)
    session.accessToken = 'a1'
    // The logout's own 401, then the refresh it triggers, refused: the family is gone already.
    fakeApi(sessionApi, { logout: () => Promise.reject(httpError(401, 'authentication_failed')) })
    fakeApi(tokenApi, { refreshSession: () => Promise.reject(httpError(401, 'refresh_rejected')) })

    await signOut()

    expect(replace).toHaveBeenCalledExactlyOnceWith('/login')
    expect(takeSignOutNotice()).toBeNull()
  })
})
