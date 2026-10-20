import { describe, expect, it } from 'vitest'
import { buildCsp } from './csp'
import { DEV_BREACH_ORIGIN, DEV_GEOCODER_ORIGIN, DEV_TILE_ORIGIN } from '../state/mapDefaults'

// Guard the CSP the app ships with (cr-0133). These pins exist so a directive can't be silently loosened or a
// third-party origin baked in without a failing test — the policy is a security backstop, not incidental config.

// The built policy names no outside service: the backend appends the configured ones per response (ADR 0088 decision
// 7). The dev server passes the reference providers' origins, as `vite.config.ts` does.
const BUILT = { tileOrigin: null, geocoderOrigin: null, breachOrigin: null }
const DEV = { tileOrigin: DEV_TILE_ORIGIN, geocoderOrigin: DEV_GEOCODER_ORIGIN, breachOrigin: DEV_BREACH_ORIGIN, dev: true }

describe('buildCsp', () => {
  const csp = buildCsp(BUILT)

  it('locks scripts/objects/frames down — the key-exfil + XSS backstop', () => {
    expect(csp).toContain("script-src 'self'")
    expect(csp).toContain("default-src 'self'")
    expect(csp).toContain("object-src 'none'")
    expect(csp).toContain("frame-src 'none'")
    expect(csp).toContain("base-uri 'self'")
    expect(csp).toContain("form-action 'self'")
    expect(csp).toContain("worker-src 'self'")
    // no unsafe script execution anywhere
    expect(csp).not.toContain("script-src 'self' 'unsafe")
    expect(csp).not.toMatch(/script-src[^;]*unsafe-eval/)
  })

  it("allows only what the app genuinely needs (data:/blob:, style 'unsafe-inline')", () => {
    expect(csp).toContain("img-src 'self' data: blob:")
    expect(csp).toContain("media-src 'self' blob:")
    expect(csp).toContain("style-src 'self' 'unsafe-inline'")
  })

  it('bakes NO third-party origin into the build: the backend appends the services in use (ADR 0088 decision 7)', () => {
    expect(csp).toContain("img-src 'self' data: blob:;")
    expect(csp).toContain("connect-src 'self';")
    expect(csp).not.toMatch(/openstreetmap|pwnedpasswords/)
  })

  it('the dev server allows the reference providers a new instance is seeded with', () => {
    const dev = buildCsp(DEV)
    expect(dev).toMatch(/img-src[^;]*https:\/\/tile\.openstreetmap\.org/)
    expect(dev).toMatch(/connect-src[^;]*https:\/\/nominatim\.openstreetmap\.org/)
    expect(dev).toMatch(/connect-src[^;]*https:\/\/api\.pwnedpasswords\.com/)
  })

  it("forbids framing in dev and production alike (the policy is always a header, never a <meta>)", () => {
    expect(csp).toContain("frame-ancestors 'none'")
    expect(buildCsp(DEV)).toContain("frame-ancestors 'none'")
  })

  it('adds the Vite HMR websocket only in dev', () => {
    expect(buildCsp(DEV)).toMatch(/connect-src[^;]*\bws:/)
    expect(csp).not.toMatch(/connect-src[^;]*\bws:/)
  })
})
