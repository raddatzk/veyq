import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

vi.mock('../api/client', () => ({ tokenApi: vi.fn() }))
// The ending runs for real — its has-a-session check is half of what E1/E2 assert here — with only the two steps
// that reach outside the page stubbed. Its document load is observed on `location.replace`.
vi.mock('./convenienceTiers', () => ({ dropConvenienceTiers: vi.fn() }))
vi.mock('../sw/register', () => ({ endAllPlaybacks: vi.fn() }))

import { refreshSession, withRefresh } from './sessionRefresh'
import { tokenApi } from '../api/client'
import { session } from './session'

const replace = vi.fn()

beforeEach(() => {
  session.accessToken = null
  session.refreshToken = null
  session.familyId = null
  replace.mockClear()
  vi.stubGlobal('location', { replace })
  vi.stubGlobal('BroadcastChannel', undefined)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

/** The refresh endpoint answers with `failure`. */
function refreshFails(failure: unknown) {
  vi.mocked(tokenApi).mockReturnValue({
    refreshSession: () => Promise.reject(failure),
  } as unknown as ReturnType<typeof tokenApi>)
}

const rejected = (status: number) => ({ response: { status } })

describe('a rejected refresh ends the session (uc.account.sign-out E1, E2)', () => {
  it('a 401 in a tab that holds a session drops the tokens and runs the ending', async () => {
    session.accessToken = 'a1'
    session.refreshToken = 'r1'
    session.familyId = 'f1'
    refreshFails(rejected(401))

    await expect(refreshSession()).rejects.toEqual(rejected(401))

    // tokens dropped → the next authed call won't loop 401→refresh→throw
    expect(session.accessToken).toBeNull()
    expect(session.refreshToken).toBeNull()
    // …and the page that held them is gone: the family was revoked from another device, or expired.
    expect(replace).toHaveBeenCalledExactlyOnceWith('/login')
  })

  it.each([
    ['a network failure', new TypeError('Failed to fetch')],
    ['a 503', rejected(503)],
  ])('%s keeps the session and its tokens, and ends nothing', async (_, failure) => {
    // Losing the connection for a moment must not cost an unlock: the next call tries again.
    session.accessToken = 'a1'
    session.refreshToken = 'r1'
    refreshFails(failure)

    await expect(refreshSession()).rejects.toBe(failure)

    expect(session.accessToken).toBe('a1')
    expect(session.refreshToken).toBe('r1')
    expect(replace).not.toHaveBeenCalled()
  })

  it('a 401 at boot, before this tab holds a session, is the ordinary not-signed-in path (E2)', async () => {
    // Without this the sign-in page would reload itself forever: every boot there refreshes with no cookie.
    refreshFails(rejected(401))

    await expect(refreshSession()).rejects.toEqual(rejected(401))

    expect(replace).not.toHaveBeenCalled()
  })
})

describe('session refresh (uc.account.login)', () => {

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
