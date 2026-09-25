import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../api/client', () => ({ tokenApi: vi.fn() }))

import { refreshSession, withRefresh } from './sessionRefresh'
import { tokenApi } from '../api/client'
import { session } from './session'

beforeEach(() => {
  session.accessToken = null
  session.refreshToken = null
  session.familyId = null
})

describe('session refresh (uc.account.login)', () => {
  it('clears the dead tokens when a refresh is rejected (no wedged session)', async () => {
    session.accessToken = 'a1'
    session.refreshToken = 'r1'
    session.familyId = 'f1'
    vi.mocked(tokenApi).mockReturnValue({
      refreshSession: () => Promise.reject(new Error('refresh_rejected')),
    } as unknown as ReturnType<typeof tokenApi>)

    await expect(refreshSession()).rejects.toThrow()

    // tokens dropped → next authed call won't loop 401→refresh→throw
    expect(session.accessToken).toBeNull()
    expect(session.refreshToken).toBeNull()
  })

  it('shares one in-flight refresh between callers that arrive together', async () => {
    // A page load fires several authed calls that all 401 at once, and each one reaches for a refresh. Without
    // a shared promise they present the SAME token concurrently — which the server answers by rotating once
    // and treating the rest as re-presentations, i.e. a rotation wasted per extra caller (ADR 0004 § the
    // one-generation grace covers the damage; this stops the waste).
    session.accessToken = 'a1'
    let calls = 0
    let release: (v: { accessToken: string; refreshToken: string }) => void = () => {}
    const inFlight = new Promise<{ accessToken: string; refreshToken: string }>((r) => (release = r))
    vi.mocked(tokenApi).mockReturnValue({
      refreshSession: () => {
        calls++
        return inFlight
      },
    } as unknown as ReturnType<typeof tokenApi>)

    const unauthorized = { response: { status: 401 } }
    let inner = 0
    const call = () => {
      inner++
      return inner <= 2 ? Promise.reject(unauthorized) : Promise.resolve('ok')
    }
    const both = Promise.all([withRefresh(call), withRefresh(call)])
    await Promise.resolve()
    release({ accessToken: 'a2', refreshToken: 'r2' })

    expect(await both).toEqual(['ok', 'ok'])
    expect(calls).toBe(1)
  })

  it('lets a later caller start a fresh refresh once the shared one has settled', async () => {
    // The shared promise must not become a cache: a refresh minutes later is a different question, and a
    // settled promise that is still handed out would leave the session wedged on a stale answer.
    session.accessToken = 'a1'
    let calls = 0
    vi.mocked(tokenApi).mockReturnValue({
      refreshSession: () => {
        calls++
        return Promise.resolve({ accessToken: `a${calls + 1}`, refreshToken: `r${calls + 1}` })
      },
    } as unknown as ReturnType<typeof tokenApi>)

    await refreshSession()
    await refreshSession()

    expect(calls).toBe(2)
  })

  it('rotates the tokens on refresh', async () => {
    session.accessToken = 'a1'
    session.refreshToken = 'r1'
    vi.mocked(tokenApi).mockReturnValue({
      refreshSession: () => Promise.resolve({ accessToken: 'a2', refreshToken: 'r2' }),
    } as unknown as ReturnType<typeof tokenApi>)

    await refreshSession()

    expect(session.accessToken).toBe('a2')
    expect(session.refreshToken).toBe('r2')
  })

  it('rotates a remembered session through the cookie, never inventing a body token', async () => {
    // "Remember me" keeps no refresh token in memory: the httpOnly cookie carries it. The request must go out
    // with an empty body, and a response without a body token must leave it null — a stale body token sent
    // alongside the cookie would be presented as a second, older credential.
    session.accessToken = 'a1'
    session.refreshToken = null
    let sent: { refreshToken?: string } | undefined
    vi.mocked(tokenApi).mockReturnValue({
      refreshSession: (req: { refreshRequest: { refreshToken?: string } }) => {
        sent = req.refreshRequest
        return Promise.resolve({ accessToken: 'a2' })
      },
    } as unknown as ReturnType<typeof tokenApi>)

    await refreshSession()

    expect(sent).toEqual({ refreshToken: undefined })
    expect(session.accessToken).toBe('a2')
    expect(session.refreshToken).toBeNull()
  })
})

describe('withRefresh — a suspended tenant mid-session (uc.spaces.scope-access A7)', () => {
  it('hands a 403 to the suspension handler and still rethrows it to the caller', async () => {
    const spy = vi.spyOn(await import('./tenantSuspension'), 'handleTenantSuspended').mockResolvedValue('other-tenant')
    const refused = { response: new Response(JSON.stringify({ code: 'tenant_suspended' }), { status: 403 }) }
    await expect(withRefresh(() => Promise.reject(refused))).rejects.toBe(refused)
    expect(spy).toHaveBeenCalledWith(refused)
    spy.mockRestore()
  })
})
