// @vitest-environment happy-dom
// The member lists' identity-trust badge (ADR 0111 decision 15): what `tenantBadges` says of a member in the active
// tenant - verified, new, and an anomaly with its note; "not verified", the ordinary state, and a member not read show
// nothing in a list.
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import MemberTrustBadge from './MemberTrustBadge.vue'
import type { MemberBadge } from '../account/tenantTrust'
import { realI18n, t } from '../../test/i18n'

const badge = (b?: MemberBadge) => mount(MemberTrustBadge, { props: { badge: b }, global: { plugins: [realI18n()] } })

describe('MemberTrustBadge', () => {
  it('a member someone verified in this tenant reads "Verified"', () => {
    const w = badge('verified')
    expect(w.get('[data-testid="member-trust-verified"]').text()).toBe(t('safety.stateVerified'))
    expect(w.find('[data-testid="member-trust-anomaly"]').exists()).toBe(false)
    expect(w.find('[data-testid="member-trust-new"]').exists()).toBe(false)
  })

  it('a member first seen under two weeks ago reads "New"', () => {
    const w = badge('new')
    expect(w.get('[data-testid="member-trust-new"]').text()).toBe(t('safety.stateNew'))
    expect(w.find('[data-testid="member-trust-verified"]').exists()).toBe(false)
  })

  it('an anomaly reads "Can’t confirm this key", with the note that nothing is shared with it — and no accept', () => {
    const w = badge('anomaly')
    expect(w.get('[data-testid="member-trust-anomaly"]').text()).toBe(t('trust.anomaly'))
    expect(w.get('[data-testid="member-trust-anomaly-note"]').text()).toBe(t('trust.anomalyNote'))
    expect(w.find('button').exists()).toBe(false)
  })

  it.each([
    ['not verified', 'unverified' as const],
    ['not read', undefined],
  ])('%s shows nothing', (_label, b) => {
    expect(badge(b).html()).toBe('<!--v-if-->')
  })
})
