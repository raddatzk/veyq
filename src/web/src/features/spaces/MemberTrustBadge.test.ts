// @vitest-environment happy-dom
// The member lists' identity-trust badge (ADR 0028 Decision 3): verified, changed, and a VERIFIED contact's change said
// louder; the ordinary states (pinned, unpinned, not read) show nothing.
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import MemberTrustBadge from './MemberTrustBadge.vue'
import type { ContactTrust } from '../account/identityPins'
import { realI18n, t } from '../../test/i18n'

const badge = (trust?: ContactTrust) => mount(MemberTrustBadge, { props: { trust }, global: { plugins: [realI18n()] } })

describe('MemberTrustBadge', () => {
  it('a verified contact reads "Verified"', () => {
    const w = badge({ state: 'verified', pinVerified: true })
    expect(w.get('[data-testid="member-trust-verified"]').text()).toBe(t('safety.stateVerified'))
    expect(w.find('[data-testid="member-trust-changed"]').exists()).toBe(false)
  })

  it('a changed key reads "Key changed"', () => {
    expect(badge({ state: 'changed', pinVerified: false }).get('[data-testid="member-trust-changed"]').text()).toBe(t('verify.badgeChanged'))
  })

  it('a VERIFIED contact’s changed key says so - louder than an ordinary change', () => {
    const text = badge({ state: 'changed', pinVerified: true }).get('[data-testid="member-trust-changed"]').text()
    expect(text).toBe(t('safety.badgeVerifiedChanged'))
    expect(text).not.toBe(t('verify.badgeChanged'))
  })

  it.each([
    ['pinned', { state: 'pinned', pinVerified: false } as ContactTrust],
    ['unpinned', { state: 'unpinned', pinVerified: false } as ContactTrust],
    ['not read', undefined],
  ])('%s shows nothing', (_label, trust) => {
    expect(badge(trust).html()).toBe('<!--v-if-->')
  })
})
