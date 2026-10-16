// The retry-on-401 every authed call runs through (inbox #449). An access token lives 15 minutes; a console left
// open past that answered `401` to the next click and showed nothing. These cases drive the REAL generated clients
// through the factories in `api/client.ts`, with only `fetch` stubbed, so what they pin is what the browser does:
//   - an expired token is rotated once and the call is sent again, and the caller never sees the 401;
//   - calls that 401 together share ONE rotation (two would present the same refresh token twice);
//   - a refused rotation leaves the 401 standing and the session dropped, for the console to end it;
//   - a call that presented no token (the sign-in itself) is never retried.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { setInstanceCapability, listInstanceCapabilities } from '../features/instance/capabilities'
import { listTenants } from '../features/tenants/tenants'
import { login } from '../features/auth/login'
import { clearTokens, session, setTokens } from './session'

const REFRESH = '/api/token/refresh'

type Call = { url: string; method: string; auth: string | null }

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
}

/** A fake server: `access-2` is the only bearer it accepts (none at all with `acceptsNothing`); a refresh answers
 *  `rotation`, handing out `access-2`. */
function server(opts: { rotation: 'ok' | 'refused'; acceptsNothing?: boolean }) {
  const calls: Call[] = []
  const fresh = 'access-2'
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const auth = new Headers(init?.headers).get('Authorization')
    calls.push({ url, method: init?.method ?? 'GET', auth })
    if (url.endsWith(REFRESH)) {
      if (opts.rotation === 'refused') return json(401, { code: 'invalid_refresh_token' })
      return json(200, { accessToken: fresh, refreshToken: 'refresh-2', expiresIn: 900 })
    }
    if (url.includes('/api/login')) return json(401, { code: 'unauthorized' })
    if (opts.acceptsNothing || auth !== `Bearer ${fresh}`) return json(401, { code: 'unauthorized' })
    if (url.includes('/api/instance/capabilities/')) {
      return json(200, { id: 'core.remember-device', title: { en: 'e', de: 'd' }, purpose: { en: 'e', de: 'd' }, whenOff: { en: 'e', de: 'd' }, off: true, effective: false })
    }
    if (url.endsWith('/api/instance/capabilities')) return json(200, [])
    return json(200, [])
  })
  return { calls, fetchMock }
}

describe('an expired access token (inbox #449)', () => {
  beforeEach(() => setTokens('access-1', 'refresh-1', 'family-1'))
  afterEach(() => {
    vi.unstubAllGlobals()
    clearTokens()
  })

  it('is rotated once and the call is sent again — the switch the owner pressed is applied', async () => {
    const srv = server({ rotation: 'ok' })
    vi.stubGlobal('fetch', srv.fetchMock)

    const entry = await setInstanceCapability('core.remember-device', true)

    expect(entry.off).toBe(true)
    expect(srv.calls.map((c) => `${c.method} ${c.url.replace(/^.*\/api/, '/api')} ${c.auth ?? '-'}`)).toEqual([
      'PUT /api/instance/capabilities/core.remember-device Bearer access-1',
      'POST /api/token/refresh -',
      'PUT /api/instance/capabilities/core.remember-device Bearer access-2',
    ])
    expect(session.accessToken).toBe('access-2')
    expect(session.refreshToken).toBe('refresh-2')
    expect(session.familyId).toBe('family-1')
  })

  it('rotates ONCE for calls that 401 together', async () => {
    const srv = server({ rotation: 'ok' })
    vi.stubGlobal('fetch', srv.fetchMock)

    await Promise.all([listTenants(), listInstanceCapabilities()])

    expect(srv.calls.filter((c) => c.url.endsWith(REFRESH))).toHaveLength(1)
  })

  it('does not rotate again for a 401 to a token that was already replaced', async () => {
    const srv = server({ rotation: 'ok' })
    vi.stubGlobal('fetch', srv.fetchMock)
    // The call goes out with access-1; by the time its 401 arrives, another call has already rotated to access-2.
    const original = srv.fetchMock.getMockImplementation()!
    srv.fetchMock.mockImplementationOnce(async (input, init) => {
      setTokens('access-2', 'refresh-2')
      return original(input, init)
    })

    await listTenants()

    expect(srv.calls.filter((c) => c.url.endsWith(REFRESH))).toHaveLength(0)
    expect(srv.calls.at(-1)?.auth).toBe('Bearer access-2')
  })

  it('leaves the 401 standing and the session dropped when the rotation is refused', async () => {
    const srv = server({ rotation: 'refused' })
    vi.stubGlobal('fetch', srv.fetchMock)

    const failure = await setInstanceCapability('core.remember-device', true).catch((e: unknown) => e)

    expect((failure as { response?: Response }).response?.status).toBe(401)
    expect(srv.calls.filter((c) => c.url.includes('/capabilities/'))).toHaveLength(1) // not retried without a token
    expect(session.accessToken).toBeNull()
    expect(session.refreshToken).toBeNull()
  })

  it('never retries a call that presented no token — a refused sign-in stays a refused sign-in', async () => {
    // A session is still in memory (the CLI signs in per run; the console could hold a stale one): the sign-in
    // carries no bearer, so its 401 is about the password and must not borrow the session's token for a retry.
    const srv = server({ rotation: 'ok' })
    vi.stubGlobal('fetch', srv.fetchMock)

    await expect(login('owner@example.test', 'wrong')).rejects.toBeTruthy()

    expect(srv.calls.map((c) => `${c.url.replace(/^.*\/api/, '/api')} ${c.auth ?? '-'}`)).toEqual(['/api/login/challenge -'])
  })

  it('takes a second 401 after the rotation as final — one rotation per call, never a loop', async () => {
    const srv = server({ rotation: 'ok', acceptsNothing: true })
    vi.stubGlobal('fetch', srv.fetchMock)

    const failure = await listTenants().catch((e: unknown) => e)

    expect((failure as { response?: Response }).response?.status).toBe(401)
    expect(srv.calls.map((c) => c.url.replace(/^.*\/api/, '/api'))).toEqual(['/api/tenants', '/api/token/refresh', '/api/tenants'])
  })
})
