// @vitest-environment happy-dom
// ADR 0067 — the Server-Owner "Object store" rubric. The behaviours here are the four wire states plus the two
// ways the read itself can fail, and each assertion is on the SPECIFIC observable rather than on "something
// rendered":
//   - PROVISIONED reads as ready;
//   - FAILED reads as a give-up, and must not read as "still working on it" — those are different operator acts;
//   - NOT_ATTEMPTED reads as information, never as a problem: it is the permanent, correct answer of a
//     `type: s3` instance, and telling that operator their store is broken is the lie phase 1 widened the
//     vocabulary to make impossible;
//   - IN_PROGRESS reads as in flight;
//   - a 403 renders the read-only notice, and a 500 says the READ failed — which must not be confused with the
//     store having failed, because one means "we do not know" and the other means "we know, and it is broken".
// Each negative is paired with the state it must not be mistaken for, on the same predicate.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('./storeProvisioning', async () => {
  const actual = await vi.importActual<typeof import('./storeProvisioning')>('./storeProvisioning')
  return { ...actual, getInstanceStoreProvisioning: vi.fn() }
})

import StoreProvisioningPanel from './StoreProvisioningPanel.vue'
import { getInstanceStoreProvisioning } from './storeProvisioning'
import { i18n } from '../../i18n'
import { httpError } from '../../test/httpError'

const stubs = {
  VeyqMessage: { props: ['tone'], template: '<div class="msg" :data-severity="tone"><slot /></div>' },
}

async function mountPanel() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'login', component: { template: '<div/>' } }] })
  const wrapper = mount(StoreProvisioningPanel, { global: { plugins: [router, i18n], stubs } })
  await flushPromises()
  return wrapper
}

describe('StoreProvisioningPanel (ADR 0067, admin-web)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    i18n.global.locale.value = 'en'
  })

  it('reports a usable store, and names the state verbatim', async () => {
    vi.mocked(getInstanceStoreProvisioning).mockResolvedValue({ state: 'PROVISIONED' })
    const wrapper = await mountPanel()
    expect(wrapper.text()).toContain(i18n.global.t('store.provisioned'))
    expect(wrapper.find('.store-state').text()).toBe('PROVISIONED')
    expect(wrapper.find('.msg').attributes('data-severity')).toBe('success')
  })

  it('reports a give-up as a give-up, not as work still in flight', async () => {
    vi.mocked(getInstanceStoreProvisioning).mockResolvedValue({ state: 'FAILED' })
    const wrapper = await mountPanel()
    expect(wrapper.text()).toContain(i18n.global.t('store.failed'))
    // The pairing: "wait and reload" is exactly the wrong advice once the retries are exhausted.
    expect(wrapper.text()).not.toContain(i18n.global.t('store.inProgress'))
    expect(wrapper.find('.msg').attributes('data-severity')).toBe('error')
  })

  it('reports a `type: s3` instance as correct, never as a problem', async () => {
    vi.mocked(getInstanceStoreProvisioning).mockResolvedValue({ state: 'NOT_ATTEMPTED' })
    const wrapper = await mountPanel()
    expect(wrapper.text()).toContain(i18n.global.t('store.notAttempted'))
    expect(wrapper.text()).not.toContain(i18n.global.t('store.failed'))
    // Severity is the assertion, not decoration: an operator who brought their own bucket must not be shown an
    // error about a store that is doing exactly what they configured.
    expect(wrapper.find('.msg').attributes('data-severity')).toBe('info')
  })

  it('reports a run still in flight', async () => {
    vi.mocked(getInstanceStoreProvisioning).mockResolvedValue({ state: 'IN_PROGRESS' })
    const wrapper = await mountPanel()
    expect(wrapper.text()).toContain(i18n.global.t('store.inProgress'))
    expect(wrapper.text()).not.toContain(i18n.global.t('store.provisioned'))
  })

  it('renders the owner-only notice, not a state, when the read is refused (403)', async () => {
    vi.mocked(getInstanceStoreProvisioning).mockRejectedValue(httpError(403, 'not_server_owner'))
    const wrapper = await mountPanel()
    expect(wrapper.text()).toContain(i18n.global.t('store.notOwner'))
    expect(wrapper.find('.store-state').exists()).toBe(false)
  })

  it('says the READ failed when it failed, rather than implying the store failed', async () => {
    vi.mocked(getInstanceStoreProvisioning).mockRejectedValue(httpError(500))
    const wrapper = await mountPanel()
    expect(wrapper.text()).toContain(i18n.global.t('store.loadFailed'))
    expect(wrapper.text()).not.toContain(i18n.global.t('store.failed'))
    expect(wrapper.find('.store-state').exists()).toBe(false)
  })
})
