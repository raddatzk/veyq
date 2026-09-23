import { describe, expect, it } from 'vitest'
import { ADMIN_CSP, PWNED_ORIGIN } from './csp'

// Guard the CSP the admin console ships with. The policy is a security backstop, not incidental config, so a
// loosened directive or a new third-party origin must fail here rather than land quietly.

const directives = new Map(
  ADMIN_CSP.split(';').map((d) => {
    const [name, ...sources] = d.trim().split(/\s+/)
    return [name, sources] as const
  }),
)

describe('ADMIN_CSP', () => {
  it('keeps scripts strictly same-origin, with no unsafe-* escape', () => {
    expect(directives.get('script-src')).toEqual(["'self'"])
    expect(directives.get('default-src')).toEqual(["'self'"])
  })

  it('embeds nothing and lets nothing re-base or re-target the page', () => {
    expect(directives.get('object-src')).toEqual(["'none'"])
    expect(directives.get('base-uri')).toEqual(["'self'"])
    expect(directives.get('form-action')).toEqual(["'self'"])
  })

  it('talks to its own origin and the pwned-password range API — nothing else', () => {
    expect(directives.get('connect-src')).toEqual(["'self'", PWNED_ORIGIN])
  })

  it('names no other third-party origin in any directive', () => {
    const origins = [...directives.values()].flat().filter((s) => /^https?:/.test(s))
    expect(origins).toEqual([PWNED_ORIGIN])
  })

  it('carries no frame-ancestors — a <meta> policy ignores it; nginx X-Frame-Options is the real guard', () => {
    // Present, it reads as an anti-framing guarantee this delivery cannot give — the claim the nginx comment
    // once leaned on.
    expect(directives.has('frame-ancestors')).toBe(false)
  })
})
