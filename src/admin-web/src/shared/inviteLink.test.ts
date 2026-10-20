// @vitest-environment happy-dom
import { describe, it, expect } from 'vitest'
import { buildInviteLink } from './inviteLink'

describe('buildInviteLink (cr-0080)', () => {
  it('always links the tenant over https, even from an http admin page (owner invite)', () => {
    // admin-web listens on http://localhost by default, and the link used to inherit that scheme — a plaintext
    // first hop for the one link that carries the owner's bootstrap secret. The precondition is asserted so a
    // test origin that happens to be https cannot turn this green by accident.
    expect(window.location.protocol).toBe('http:')
    expect(buildInviteLink('xyz', 'fam.example.com')).toBe('https://fam.example.com/tenant/accept#xyz')
  })

  it('puts the secret in the fragment (never the path/query — never sent to the server)', () => {
    const url = new URL(buildInviteLink('s3cr3t', 'h.example'))
    expect(url.hash).toBe('#s3cr3t')
    expect(url.pathname).toBe('/tenant/accept')
    expect(url.search).toBe('')
  })
})
