// @vitest-environment happy-dom
// uc.admin.identify-instance-release (web, cr-release-identity) — the Server-Owner Release rubric. Three
// behaviours, and each one is a criterion of the use-case rather than a rendering detail:
//   - a stamped instance shows the version verbatim (the string an operator pins and rolls back to);
//   - an UNSTAMPED build says so, and shows no version at all (A1 — absence is the answer, not a placeholder);
//   - a non-owner read (403) renders the read-only notice, not the value (A2).
// The last two are negatives, so each is paired with the stamped render on the same predicate: the assertions
// are "the version text is absent HERE while it is present THERE", never "something didn't show up".
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('./release', () => ({ getInstanceRelease: vi.fn() }))

import ReleaseVersionPanel from './ReleaseVersionPanel.vue'
import { getInstanceRelease } from './release'
import { i18n } from '../../i18n'
import { httpError } from '../../test/httpError'

const stubs = {
  Message: { props: ['severity'], template: '<div class="msg"><slot /></div>' },
}

async function mountPanel() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'login', component: { template: '<div/>' } }] })
  const wrapper = mount(ReleaseVersionPanel, { global: { plugins: [router, i18n], stubs } })
  await flushPromises()
  return wrapper
}

describe('ReleaseVersionPanel (uc.admin.identify-instance-release, web)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    i18n.global.locale.value = 'en'
  })

  it('shows the running release verbatim to the Server Owner', async () => {
    vi.mocked(getInstanceRelease).mockResolvedValue({ release: '1.7' })
    const wrapper = await mountPanel()
    expect(wrapper.text()).toContain(i18n.global.t('release.running'))
    expect(wrapper.find('.release-value').text()).toBe('1.7')
    expect(wrapper.text()).not.toContain(i18n.global.t('release.unstamped'))
  })

  it('reports the absence, and no version, when the build carries no release stamp', async () => {
    vi.mocked(getInstanceRelease).mockResolvedValue({ release: null })
    const wrapper = await mountPanel()
    expect(wrapper.text()).toContain(i18n.global.t('release.unstamped'))
    // The specific observable: the value element the stamped case renders does not exist at all, so there is no
    // empty box that reads as "a version we failed to load".
    expect(wrapper.find('.release-value').exists()).toBe(false)
    expect(wrapper.text()).not.toContain(i18n.global.t('release.running'))
  })

  it('renders the owner-only notice, not a version, when the read is refused (403)', async () => {
    vi.mocked(getInstanceRelease).mockRejectedValue(httpError(403, 'not_server_owner'))
    const wrapper = await mountPanel()
    expect(wrapper.text()).toContain(i18n.global.t('release.notOwner'))
    expect(wrapper.find('.release-value').exists()).toBe(false)
    expect(wrapper.text()).not.toContain(i18n.global.t('release.unstamped'))
  })

  it('says the read failed when it failed, rather than implying an unstamped build', async () => {
    // The trap this pins: a load error and an unstamped build must not render the same. One means "there is no
    // version"; the other means "we do not know" — and an operator choosing an image needs them apart.
    vi.mocked(getInstanceRelease).mockRejectedValue(httpError(500))
    const wrapper = await mountPanel()
    expect(wrapper.text()).toContain(i18n.global.t('release.loadFailed'))
    expect(wrapper.text()).not.toContain(i18n.global.t('release.unstamped'))
  })
})
