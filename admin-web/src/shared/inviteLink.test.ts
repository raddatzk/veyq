// @vitest-environment happy-dom
import { describe, it, expect } from 'vitest'
import { buildInviteLink } from './inviteLink'

describe('buildInviteLink (cr-0080)', () => {
  it('uses the current origin for a same-host invite (member into the current tenant)', () => {
    expect(buildInviteLink('abc')).toBe(`${window.location.origin}/tenant/accept#abc`)
  })

  it('targets another host when a domain is given, keeping the current protocol (owner invite)', () => {
    expect(buildInviteLink('xyz', 'fam.example.com')).toBe(`${window.location.protocol}//fam.example.com/tenant/accept#xyz`)
  })

  it('puts the secret in the fragment (never the path/query — never sent to the server)', () => {
    const url = new URL(buildInviteLink('s3cr3t', 'h.example'))
    expect(url.hash).toBe('#s3cr3t')
    expect(url.pathname).toBe('/tenant/accept')
    expect(url.search).toBe('')
  })
})
