// @vitest-environment node
// Pins the recorder's response-header half (cc.security.wire-assertion § Capability tokens): the web refresh token
// crosses the wire ONLY as a `Set-Cookie`, and the orchestrator derives it as a needle from what this file records.
//
// **The trap it exists for (B5).** `SessionCookie.issue` sends the token cookie FIRST and the clearing cookie for the
// other lifetime second. Flattening a `Headers` the obvious way — `forEach` into an object, the shape `headersOf`
// uses for request headers — keeps only the LAST `set-cookie`, i.e. the empty clearing one, so every sign-in would
// record `veyq_refresh=` and the token sweep would stay green with nothing to look for. Only `getSetCookie()`
// returns both (measured under node 23: also on a `clone()`).
//
// Node environment on purpose: the e2e suites run under node, where `fetch` is undici's — the one this wraps.
import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { installWireRecorder } from './wireRecorder'

// In `SessionCookie.issue`'s order for a SESSION sign-in: the token cookie, then the clearing one.
const TOKEN_COOKIE = 'veyq_refresh_session=b5-refresh-token-value; Path=/api/token/refresh; HttpOnly; SameSite=Strict'
const CLEARING_COOKIE = 'veyq_refresh=; Path=/api/token/refresh; Max-Age=0; HttpOnly; SameSite=Strict'

describe('installWireRecorder — response headers', () => {
  const originalFetch = globalThis.fetch
  const originalRecordFile = process.env.VEYQ_HTTP_RECORD_FILE
  let dir = ''
  let recordFile = ''

  beforeEach(() => {
    dir = mkdtempSync(join(tmpdir(), 'veyq-wire-recorder-'))
    recordFile = join(dir, 'http.jsonl')
    process.env.VEYQ_HTTP_RECORD_FILE = recordFile
    globalThis.fetch = async () =>
      new Response('{"accessToken":"b5-access"}', {
        status: 200,
        headers: [
          ['content-type', 'application/json'],
          ['set-cookie', TOKEN_COOKIE],
          ['set-cookie', CLEARING_COOKIE],
        ],
      })
  })

  // The restore is written with the mutation (changes/README § a test that mutates shared global state): the
  // global `fetch` and the environment variable are process-wide, and a leaked recorder would wrap every later test.
  afterEach(() => {
    globalThis.fetch = originalFetch
    if (originalRecordFile === undefined) delete process.env.VEYQ_HTTP_RECORD_FILE
    else process.env.VEYQ_HTTP_RECORD_FILE = originalRecordFile
    rmSync(dir, { recursive: true, force: true })
  })

  function recorded(): Record<string, unknown>[] {
    return readFileSync(recordFile, 'utf8')
      .split('\n')
      .filter((line) => line.trim())
      .map((line) => JSON.parse(line) as Record<string, unknown>)
  }

  it('records every Set-Cookie, the token cookie first, and the other response headers', async () => {
    installWireRecorder()
    await fetch('http://veyq.localhost:8080/api/login/verify', { method: 'POST', body: '{}' })

    const [entry] = recorded()
    expect(entry).toHaveProperty('responseHeaders')
    const headers = entry!.responseHeaders as Record<string, string | string[]>
    expect(headers['set-cookie']).toEqual([TOKEN_COOKIE, CLEARING_COOKIE])
    expect(headers['content-type']).toBe('application/json')
  })

  it('leaves the caller a readable body — the recorder reads a clone, never the response it returns', async () => {
    installWireRecorder()
    const response = await fetch('http://veyq.localhost:8080/api/login/verify', { method: 'POST', body: '{}' })

    expect(await response.json()).toEqual({ accessToken: 'b5-access' })
    expect(response.headers.getSetCookie()).toEqual([TOKEN_COOKIE, CLEARING_COOKIE])
    expect(recorded()[0]!.responseBody).toBe('{"accessToken":"b5-access"}')
  })
})
