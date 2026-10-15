// @vitest-environment happy-dom
// ADR 0050 — the Server-Owner "Config-secret key" rubric. The three wire states plus the two ways the read itself
// can fail, each asserted on the SPECIFIC observable and paired with the state it must not be mistaken for:
//   - OK reads as ready;
//   - MISMATCH reads as the lost/replaced key it is, and must not read as ABSENT — restoring the key file and
//     making an unreadable one readable are different repairs;
//   - ABSENT names the unreadable/missing key;
//   - a 403 renders the read-only notice, and a 500 says the READ failed, never that the key is wrong.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('./configSecret', async () => {
  const actual = await vi.importActual<typeof import('./configSecret')>('./configSecret')
  return { ...actual, getInstanceConfigSecret: vi.fn() }
})

import ConfigSecretPanel from './ConfigSecretPanel.vue'
import { getInstanceConfigSecret } from './configSecret'
import { i18n } from '../../i18n'
import { httpError } from '../../test/httpError'

const stubs = {
  Message: { props: ['severity'], template: '<div class="msg" :data-severity="severity"><slot /></div>' },
}

async function mountPanel() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'login', component: { template: '<div/>' } }] })
  const wrapper = mount(ConfigSecretPanel, { global: { plugins: [router, i18n], stubs } })
  await flushPromises()
  return wrapper
}

describe('ConfigSecretPanel (ADR 0050, admin-web)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    i18n.global.locale.value = 'en'
  })

  it('reports a key that opens the sealed secrets, and names the state verbatim', async () => {
    vi.mocked(getInstanceConfigSecret).mockResolvedValue({ state: 'OK' })
    const wrapper = await mountPanel()
    expect(wrapper.text()).toContain(i18n.global.t('configSecret.ok'))
    expect(wrapper.find('.config-secret-state').text()).toBe('OK')
    expect(wrapper.find('.msg').attributes('data-severity')).toBe('success')
  })

  it('reports a key that is not the one the secrets were sealed under, not a missing one', async () => {
    vi.mocked(getInstanceConfigSecret).mockResolvedValue({ state: 'MISMATCH' })
    const wrapper = await mountPanel()
    expect(wrapper.text()).toContain(i18n.global.t('configSecret.mismatch'))
    expect(wrapper.text()).not.toContain(i18n.global.t('configSecret.absent'))
    expect(wrapper.find('.config-secret-state').text()).toBe('MISMATCH')
    expect(wrapper.find('.msg').attributes('data-severity')).toBe('error')
  })

  it('reports a missing or unreadable key, not a mismatched one', async () => {
    vi.mocked(getInstanceConfigSecret).mockResolvedValue({ state: 'ABSENT' })
    const wrapper = await mountPanel()
    expect(wrapper.text()).toContain(i18n.global.t('configSecret.absent'))
    expect(wrapper.text()).not.toContain(i18n.global.t('configSecret.mismatch'))
    expect(wrapper.find('.config-secret-state').text()).toBe('ABSENT')
  })

  it('renders the owner-only notice, not a state, when the read is refused (403)', async () => {
    vi.mocked(getInstanceConfigSecret).mockRejectedValue(httpError(403, 'not_server_owner'))
    const wrapper = await mountPanel()
    expect(wrapper.text()).toContain(i18n.global.t('configSecret.notOwner'))
    expect(wrapper.find('.config-secret-state').exists()).toBe(false)
  })

  it('says the READ failed when it failed, rather than implying the key is wrong', async () => {
    vi.mocked(getInstanceConfigSecret).mockRejectedValue(httpError(500))
    const wrapper = await mountPanel()
    expect(wrapper.text()).toContain(i18n.global.t('configSecret.loadFailed'))
    expect(wrapper.text()).not.toContain(i18n.global.t('configSecret.mismatch'))
    expect(wrapper.find('.config-secret-state').exists()).toBe(false)
  })
})
