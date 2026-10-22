// Where the refresh token lives after a sign-in. The console used to send no `sessionScope`, so the server answered
// in the body, the token lived only in memory, and every reload landed on /login: `boot()` asks for a rotation over
// the httpOnly cookie, and none had ever been set. The CLI must keep the body transport — Node has no cookie jar.
//
// The request is asserted at `fetch`, not through the generated client: its `…ToJSON` drops what the spec does not
// name, so only the wire body says what the server was actually asked for.
import { describe, it, expect, vi, afterEach } from 'vitest'
import { PRODUCTION_ARGON_TIMEOUT_MS } from '@veyq/crypto/testArgon'
import { b64std } from '@veyq/crypto/base64'
import { login } from './login'
import { refreshSession } from '../../app/sessionRefresh'
import { clearTokens, session } from '../../app/session'

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
}

/** A fake server that answers the way the contract says it does: a request that chose a `sessionScope` gets its
 *  refresh token as a cookie and none in the body; one that chose none gets it in the body. */
function server() {
  const bodies: Record<string, unknown> = {}
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input).replace(/^.*\/api/, '/api')
    const body = init?.body ? (JSON.parse(String(init.body)) as Record<string, unknown>) : {}
    bodies[url] = body
    if (url === '/api/login/challenge') {
      return json(200, { nonce: b64std(new Uint8Array(32).fill(7)), instanceSalt: b64std(new Uint8Array(16).fill(3)) })
    }
    if (url === '/api/login/verify') {
      const cookie = body.sessionScope !== undefined
      return json(200, { accessToken: 'access-1', ...(cookie ? {} : { refreshToken: 'refresh-1' }), family: { id: 'family-1' } })
    }
    if (url === '/api/token/refresh') return json(200, { accessToken: 'access-2', expiresIn: 900 })
    return json(404, {})
  })
  return { bodies, fetchMock }
}

afterEach(() => {
  vi.unstubAllGlobals()
  clearTokens()
})

describe('sign-in — the refresh token\'s transport', () => {
  it('the console asks for a session cookie, keeps no refresh token, and a reload revives it', async () => {
    const srv = server()
    vi.stubGlobal('fetch', srv.fetchMock)

    await login('owner@example.test', 'pw', 'cookie')

    expect(srv.bodies['/api/login/verify']).toMatchObject({ sessionScope: 'SESSION' })
    expect(session.accessToken).toBe('access-1')
    expect(session.refreshToken).toBeNull()
    expect(session.familyId).toBe('family-1')

    // The reload: memory is gone, and the rotation presents no token of its own — the browser attaches the cookie.
    clearTokens()
    await refreshSession()
    expect(srv.bodies['/api/token/refresh']).toEqual({})
    expect(session.accessToken).toBe('access-2')
  }, PRODUCTION_ARGON_TIMEOUT_MS)

  it('the CLI asks for no cookie and keeps the refresh token in memory', async () => {
    const srv = server()
    vi.stubGlobal('fetch', srv.fetchMock)

    await login('owner@example.test', 'pw', 'body')

    expect(srv.bodies['/api/login/verify']).not.toHaveProperty('sessionScope')
    expect(session.refreshToken).toBe('refresh-1')
  }, PRODUCTION_ARGON_TIMEOUT_MS)
})
