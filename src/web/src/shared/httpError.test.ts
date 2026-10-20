import { describe, expect, it } from 'vitest'
import {
  decodeHttpError,
  httpStatus,
  isAuthoritativeRejection,
  isCodedError,
  isHttpStatus,
  isUnauthorized,
} from './httpError'

/** A stand-in for a generated-client `ResponseError`: any package's error carries a `.response: Response`. */
function responseError(status: number, body?: unknown): { name: string; response: Response } {
  const init = { status, headers: { 'content-type': 'application/json' } }
  const res = body === undefined ? new Response(null, init) : new Response(JSON.stringify(body), init)
  return { name: 'ResponseError', response: res }
}

describe('httpError', () => {
  it('httpStatus reads the response status, 0 for a non-HTTP failure', () => {
    expect(httpStatus(responseError(409))).toBe(409)
    expect(httpStatus(new Error('network'))).toBe(0)
    expect(httpStatus(null)).toBe(0)
    expect(httpStatus(undefined)).toBe(0)
    expect(httpStatus({ response: {} })).toBe(0) // no numeric status → not an HTTP failure
  })

  it('isHttpStatus / isUnauthorized match exactly', () => {
    expect(isHttpStatus(responseError(304), 304)).toBe(true)
    expect(isHttpStatus(responseError(404), 304)).toBe(false)
    expect(isUnauthorized(responseError(401))).toBe(true)
    expect(isUnauthorized(responseError(403))).toBe(false)
    expect(isUnauthorized(new Error('network'))).toBe(false)
  })

  it('isAuthoritativeRejection is a 4xx that says "no", never a network failure, a 5xx, or a "not now"', () => {
    for (const status of [400, 401, 403, 404, 410]) expect(isAuthoritativeRejection(responseError(status))).toBe(true)
    for (const status of [408, 429, 500, 502, 503]) expect(isAuthoritativeRejection(responseError(status))).toBe(false)
    expect(isAuthoritativeRejection(new TypeError('Failed to fetch'))).toBe(false)
  })

  it('decodeHttpError returns status + body code', async () => {
    expect(await decodeHttpError(responseError(409, { code: 'domain_taken' }))).toEqual({
      status: 409,
      code: 'domain_taken',
    })
  })

  it('decodeHttpError leaves code null for an empty or non-JSON body', async () => {
    expect(await decodeHttpError(responseError(403))).toEqual({ status: 403, code: null })
    const notJson = { name: 'ResponseError', response: new Response('<html>', { status: 500 }) }
    expect(await decodeHttpError(notJson)).toEqual({ status: 500, code: null })
  })

  it('decodeHttpError returns status 0 for a non-HTTP failure', async () => {
    expect(await decodeHttpError(new Error('network'))).toEqual({ status: 0, code: null })
  })

  it('isCodedError matches status AND code, short-circuiting on status', async () => {
    expect(await isCodedError(responseError(409, { code: 'stale_epoch' }), 409, 'stale_epoch')).toBe(true)
    expect(await isCodedError(responseError(409, { code: 'other' }), 409, 'stale_epoch')).toBe(false)
    expect(await isCodedError(responseError(404, { code: 'stale_epoch' }), 409, 'stale_epoch')).toBe(false)
    expect(await isCodedError(new Error('network'), 409, 'stale_epoch')).toBe(false)
  })
})
