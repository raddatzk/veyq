// @vitest-environment node
import { describe, it, expect } from 'vitest'
import { CliError, describeFailure, renderResult } from './output'

describe('veyq-admin output', () => {
  it('renders a result as indented JSON', () => {
    expect(JSON.parse(renderResult([{ tenantId: 't1' }]))).toEqual([{ tenantId: 't1' }])
  })

  it('renders a bodiless success explicitly, because printing nothing reads as a silent failure', () => {
    expect(JSON.parse(renderResult(undefined))).toEqual({ ok: true })
  })

  it('reports a CLI-diagnosed failure in its own words', async () => {
    expect(await describeFailure(new CliError('VEYQ_ADMIN_PASSWORD is not set')))
      .toBe('VEYQ_ADMIN_PASSWORD is not set')
  })

  it('reports an API failure as status + code, and never the response body', async () => {
    const body = { code: 'domain_taken', detail: 'family.example.com belongs to tenant t9' }
    const msg = await describeFailure({ response: new Response(JSON.stringify(body), { status: 409 }) })
    expect(msg).toBe('request failed: HTTP 409 domain_taken')
    expect(msg).not.toContain('t9')
  })

  it('reports a status with no machine-readable code without inventing one', async () => {
    expect(await describeFailure({ response: new Response('', { status: 502 }) }))
      .toBe('request failed: HTTP 502')
  })

  it('names a transport failure as unreachable rather than as HTTP 0', async () => {
    expect(await describeFailure(new TypeError('fetch failed'))).toContain('could not be reached')
  })
})
