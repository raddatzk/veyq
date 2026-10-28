// @vitest-environment happy-dom
// The member lists' identity-trust badge (ADR 0028 Decision 3, ADR 0028 Decision 2): verified, and the one
// key state a list shows until cr-trust-records-in-the-tenant — an anomaly, with its note; the ordinary states (pinned —
// a first use or a key the chain reached — unpinned, not read) show nothing.
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import MemberTrustBadge from './MemberTrustBadge.vue'
import type { ContactTrust } from '../account/identityPins'
import { realI18n, t } from '../../test/i18n'

const badge = (trust?: ContactTrust) => mount(MemberTrustBadge, { props: { trust }, global: { plugins: [realI18n()] } })

describe('MemberTrustBadge', () => {
  it('a verified contact reads "Verified"', () => {
    const w = badge({ state: 'verified' })
    expect(w.get('[data-testid="member-trust-verified"]').text()).toBe(t('safety.stateVerified'))
    expect(w.find('[data-testid="member-trust-anomaly"]').exists()).toBe(false)
  })

  it('an anomaly reads "Can’t confirm this key", with the note that nothing is shared with it — and no accept', () => {
    const w = badge({ state: 'anomaly' })
    expect(w.get('[data-testid="member-trust-anomaly"]').text()).toBe(t('trust.anomaly'))
    expect(w.get('[data-testid="member-trust-anomaly-note"]').text()).toBe(t('trust.anomalyNote'))
    expect(w.find('button').exists()).toBe(false)
  })

  it.each([
    ['pinned', { state: 'pinned' } as ContactTrust],
    ['unpinned', { state: 'unpinned' } as ContactTrust],
    ['not read', undefined],
  ])('%s shows nothing', (_label, trust) => {
    expect(badge(trust).html()).toBe('<!--v-if-->')
  })
})
