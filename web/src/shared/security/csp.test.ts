import { describe, expect, it } from 'vitest'
import { buildCsp, providerOrigin } from './csp'
import { DEFAULT_GEOCODER_URL, DEFAULT_TILE_URL } from '../state/mapDefaults'

// Guard the CSP the app ships with (cr-0133). These pins exist so a directive can't be silently loosened or a
// third-party origin baked in without a failing test — the policy is a security backstop, not incidental config.

const OSM = {
  tileOrigin: providerOrigin(DEFAULT_TILE_URL),
  geocoderOrigin: providerOrigin(DEFAULT_GEOCODER_URL),
}

describe('providerOrigin', () => {
  it('reduces a provider URL to its origin', () => {
    expect(providerOrigin(DEFAULT_TILE_URL)).toBe('https://tile.openstreetmap.org')
    expect(providerOrigin(DEFAULT_GEOCODER_URL)).toBe('https://nominatim.openstreetmap.org')
    expect(providerOrigin('https://tiles.example.org/a/b')).toBe('https://tiles.example.org')
  })

  it('returns null for absent / relative / malformed values (caller falls back, never injects garbage)', () => {
    expect(providerOrigin(undefined)).toBeNull()
    expect(providerOrigin(null)).toBeNull()
    expect(providerOrigin('')).toBeNull()
    expect(providerOrigin('/local/tiles/{z}/{x}/{y}.png')).toBeNull()
    expect(providerOrigin('not a url')).toBeNull()
  })
})

describe('buildCsp', () => {
  const csp = buildCsp(OSM)

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

  it("allows only what the app genuinely needs (data:/blob:, style 'unsafe-inline', pwned)", () => {
    expect(csp).toContain("img-src 'self' data: blob:")
    expect(csp).toContain("media-src 'self' blob:")
    expect(csp).toContain("style-src 'self' 'unsafe-inline'")
    expect(csp).toContain('https://api.pwnedpasswords.com')
  })

  it('allows the configured map providers (tiles → img-src, geocoder → connect-src)', () => {
    expect(csp).toMatch(/img-src[^;]*https:\/\/tile\.openstreetmap\.org/)
    expect(csp).toMatch(/connect-src[^;]*https:\/\/nominatim\.openstreetmap\.org/)
  })

  it('bakes NO third-party origin when providers are unset (self-hosted / no-maps deployment)', () => {
    const bare = buildCsp({ tileOrigin: null, geocoderOrigin: null })
    expect(bare).toContain("img-src 'self' data: blob:")
    expect(bare).not.toContain('openstreetmap')
    expect(bare).toContain("connect-src 'self' https://api.pwnedpasswords.com")
  })

  it("forbids framing in dev and production alike (the policy is always a header, never a <meta>)", () => {
    expect(csp).toContain("frame-ancestors 'none'")
    expect(buildCsp({ ...OSM, dev: true })).toContain("frame-ancestors 'none'")
  })

  it('adds the Vite HMR websocket only in dev', () => {
    expect(buildCsp({ ...OSM, dev: true })).toMatch(/connect-src[^;]*\bws:/)
    expect(csp).not.toMatch(/connect-src[^;]*\bws:/)
  })
})
