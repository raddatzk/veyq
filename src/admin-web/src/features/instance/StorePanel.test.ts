// @vitest-environment happy-dom
// ADR 0089 — the Server → Object store page. Each case is one observable the owner depends on:
//   - a fresh bundled recipe starts on the bundled store and saves exactly `{ bundled: true }`;
//   - an own store sends the whole setting, and a secret left empty is OMITTED (keep), not sent empty;
//   - a secret that cannot be opened is asked for again, and Save stays closed until it is typed;
//   - the check shows EVERY step, so a wrong bucket reads as that step failing, not as "failed";
//   - an unchecked setting says tenants are closed; a passed one says they are open;
//   - a saved change waits as PENDING beside the store in use (decision 5): the page says so, the form edits it, a
//     failed check of it says the store in use is unchanged, and it can be discarded;
//   - once blobs exist the type, endpoint and bucket cannot be edited.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('./store', async () => {
  const actual = await vi.importActual<typeof import('./store')>('./store')
  return {
    ...actual,
    getInstanceStore: vi.fn(),
    setInstanceStore: vi.fn(),
    checkInstanceStore: vi.fn(),
    discardPendingInstanceStore: vi.fn(),
  }
})
vi.mock('./StoreProvisioningPanel.vue', () => ({ default: { template: '<div class="provisioning" />' } }))
vi.mock('./StoreReconcilePanel.vue', () => ({ default: { template: '<div class="reconcile" />' } }))
vi.mock('../../shared/state/useToast', () => ({ useToast: () => ({ success: vi.fn() }) }))

import StorePanel from './StorePanel.vue'
import { checkInstanceStore, discardPendingInstanceStore, getInstanceStore, setInstanceStore } from './store'
import type { InstanceStore, InstanceStoreCheck, InstanceStoreSetting } from './store'
import { i18n } from '../../i18n'
import { httpError } from '../../test/httpError'

const stubs = {
  Message: { props: ['severity'], template: '<div class="msg" :data-severity="severity"><slot /></div>' },
  Select: {
    props: ['modelValue', 'options', 'disabled'],
    template:
      '<select class="select" :value="modelValue" :disabled="disabled" @change="$emit(\'update:modelValue\', $event.target.value)"><option v-for="o in options" :key="o.value" :value="o.value">{{ o.label }}</option></select>',
  },
  Button: {
    props: ['type', 'label', 'disabled'],
    emits: ['click'], // declared, or the listener also falls through to the native button and fires twice
    template: '<button :type="type || \'button\'" :disabled="disabled" @click="$emit(\'click\', $event)">{{ label }}</button>',
  },
  InputText: {
    props: ['modelValue', 'disabled', 'name', 'placeholder'],
    template:
      '<input :name="name" :value="modelValue" :disabled="disabled" :placeholder="placeholder" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
}

const own: InstanceStoreSetting = {
  bundled: false, type: 'S3', endpoint: 'https://s3.example.com', publicEndpoint: undefined, bucket: 'b', region: 'eu', accessKey: 'AK',
  garageAdminEndpoint: undefined, secretKeySet: true, garageAdminTokenSet: false, unreadable: [],
}
const read = (over: Partial<InstanceStore> = {}): InstanceStore => ({
  bundledAvailable: false, blobsStored: false, setting: undefined, lastCheck: undefined, pending: undefined, ...over,
})
const check = (passed: boolean): InstanceStoreCheck => ({
  passed,
  checkedAt: new Date('2026-10-15T12:00:00Z'),
  steps: passed
    ? [{ step: 'CREDENTIALS', outcome: 'PASSED' }, { step: 'BUCKET', outcome: 'PASSED' }]
    : [
        { step: 'CREDENTIALS', outcome: 'PASSED' },
        { step: 'BUCKET', outcome: 'FAILED', detail: 'The bucket nope does not exist (HTTP 404).' },
        { step: 'PROBE_PUT', outcome: 'SKIPPED' },
      ],
})

async function mountPanel(initial: InstanceStore) {
  vi.mocked(getInstanceStore).mockResolvedValue(initial)
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'login', component: { template: '<div/>' } }] })
  const wrapper = mount(StorePanel, { global: { plugins: [router, i18n], stubs } })
  await flushPromises()
  return wrapper
}
const t = (k: string, p?: Record<string, unknown>) => i18n.global.t(k, p ?? {})
const saveButton = (w: Awaited<ReturnType<typeof mountPanel>>) => w.find('.store-save')

describe('StorePanel (ADR 0089, admin-web)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    i18n.global.locale.value = 'en'
  })

  it('starts a fresh bundled recipe on the bundled store and saves exactly { bundled: true }', async () => {
    vi.mocked(setInstanceStore).mockResolvedValue(read({ bundledAvailable: true }))
    const w = await mountPanel(read({ bundledAvailable: true }))
    expect((w.find('select').element as HTMLSelectElement).value).toBe('BUNDLED')
    expect(w.find('input[name=endpoint]').exists()).toBe(false) // nothing to enter
    await w.find('form').trigger('submit.prevent')
    await flushPromises()
    expect(setInstanceStore).toHaveBeenCalledWith({ bundled: true })
  })

  it('says the server is setting the bundled store itself while nothing is saved yet, not that no store is set', async () => {
    // Only the seconds after a start before the boot worker adopts it (ADR 0089 decision 3).
    const w = await mountPanel(read({ bundledAvailable: true }))
    expect(w.find('.store-not-set').text()).toBe(t('store.adopting'))
    // control: where the deployment brings no store, nothing is coming
    const none = await mountPanel(read({ bundledAvailable: false }))
    expect(none.find('.store-not-set').text()).toBe(t('store.notSet'))
  })

  it('says what a first setting means while it waits for its check — there is no store in use beside it', async () => {
    const bundled: InstanceStoreSetting = { ...own, bundled: true, type: 'GARAGE' }
    const w = await mountPanel(read({ bundledAvailable: true, pending: { setting: bundled, lastCheck: undefined } }))
    expect(w.find('.store-pending').text()).toBe(t('store.pendingFirst'))
    expect(w.find('.store-unchecked').text()).toBe(t('store.pendingFirstUnchecked'))
  })

  it('reads a failed check of a first setting as no store in use, not as an unchanged one', async () => {
    const bundled: InstanceStoreSetting = { ...own, bundled: true, type: 'GARAGE' }
    const w = await mountPanel(read({ bundledAvailable: true, pending: { setting: bundled, lastCheck: check(false) } }))
    expect(w.find('.store-verdict').attributes('data-severity')).toBe('error')
    expect(w.find('.store-verdict').text()).toBe(t('store.pendingFirstCheckFailedAt'))
    expect(w.find('[data-step=BUCKET]').classes()).toContain('store-step--failed')
  })

  it('offers no bundled choice where the deployment brings none', async () => {
    const w = await mountPanel(read({ bundledAvailable: false }))
    expect(w.findAll('option').map((o) => o.text())).toEqual([t('store.sourceS3'), t('store.sourceGarage')])
    const b = await mountPanel(read({ bundledAvailable: true }))
    expect(b.findAll('option')).toHaveLength(3)
  })

  it('keeps Save closed until an own store is complete, then sends the whole setting', async () => {
    vi.mocked(setInstanceStore).mockResolvedValue(read({ setting: own }))
    const w = await mountPanel(read())
    expect(saveButton(w).attributes('disabled')).toBeDefined()
    await w.find('input[name=endpoint]').setValue('https://s3.example.com')
    await w.find('input[name=bucket]').setValue('b')
    await w.find('input[name=region]').setValue('eu')
    await w.find('input[name=accessKey]').setValue('AK')
    expect(saveButton(w).attributes('disabled')).toBeDefined() // the secret key is still missing
    await w.find('input[name=secretKey]').setValue('sek')
    expect(saveButton(w).attributes('disabled')).toBeUndefined()
    await w.find('form').trigger('submit.prevent')
    await flushPromises()
    expect(setInstanceStore).toHaveBeenCalledWith({
      bundled: false, type: 'S3', endpoint: 'https://s3.example.com', publicEndpoint: null, bucket: 'b', region: 'eu', accessKey: 'AK',
      secretKey: 'sek',
    })
  })

  it('keeps a stored secret when its field is left empty — omitted, not sent empty — and says so', async () => {
    vi.mocked(setInstanceStore).mockResolvedValue(read({ setting: own }))
    const w = await mountPanel(read({ setting: own }))
    expect(w.find('input[name=secretKey]').attributes('placeholder')).toBe(t('store.secretKept'))
    expect(saveButton(w).attributes('disabled')).toBeUndefined()
    await w.find('form').trigger('submit.prevent')
    await flushPromises()
    expect(vi.mocked(setInstanceStore).mock.calls[0][0]).not.toHaveProperty('secretKey')
  })

  it('asks for a secret the current key cannot open, and will not save without it (the paired case above keeps it)', async () => {
    const w = await mountPanel(read({ setting: { ...own, unreadable: ['SECRET_KEY'] } }))
    expect(w.find('.store-unreadable').text()).toContain(t('store.secret.SECRET_KEY'))
    expect(saveButton(w).attributes('disabled')).toBeDefined()
    expect(w.find('input[name=secretKey]').attributes('placeholder')).toBe('')
    await w.find('input[name=secretKey]').setValue('again')
    expect(saveButton(w).attributes('disabled')).toBeUndefined()
  })

  it('shows every check step, naming the failed one with the server\'s detail, and the later ones as skipped', async () => {
    vi.mocked(checkInstanceStore).mockResolvedValue(check(false))
    const w = await mountPanel(read({ setting: own }))
    vi.mocked(getInstanceStore).mockResolvedValue(read({ setting: own, lastCheck: check(false) })) // the read after the check
    expect(w.find('.store-unchecked').exists()).toBe(true) // never checked: tenants closed, said so
    await w.find('.store-check').trigger('click')
    await flushPromises()
    expect(w.find('.store-verdict').attributes('data-severity')).toBe('error')
    expect(w.find('[data-step=BUCKET]').classes()).toContain('store-step--failed')
    expect(w.find('[data-step=BUCKET]').text()).toContain('The bucket nope does not exist')
    expect(w.find('[data-step=PROBE_PUT]').classes()).toContain('store-step--skipped')
    expect(w.find('.store-unchecked').exists()).toBe(false)
  })

  it('reports a passed check as opening tenant creation, and tells the console on every change', async () => {
    vi.mocked(checkInstanceStore).mockResolvedValue(check(true))
    const w = await mountPanel(read({ setting: own }))
    vi.mocked(getInstanceStore).mockResolvedValue(read({ setting: own, lastCheck: check(true) }))
    await w.find('.store-check').trigger('click')
    await flushPromises()
    expect(w.find('.store-verdict').attributes('data-severity')).toBe('success')
    expect(w.find('.store-verdict').text()).toBe(t('store.checkPassed'))
    const changed = w.emitted('changed')!
    expect((changed[changed.length - 1][0] as InstanceStore).lastCheck?.passed).toBe(true)
  })

  it('says a saved change is not in use yet, and edits it rather than the store in use', async () => {
    const changed = { ...own, endpoint: 'https://typo.example.com' }
    const w = await mountPanel(read({ setting: own, lastCheck: check(true), pending: { setting: changed, lastCheck: undefined } }))
    expect(w.find('.store-pending').text()).toBe(t('store.pendingNote'))
    expect((w.find('input[name=endpoint]').element as HTMLInputElement).value).toBe('https://typo.example.com')
    expect(w.find('.store-unchecked').text()).toBe(t('store.pendingUnchecked'))
  })

  it('reads a failed check of the saved change as leaving the store in use unchanged', async () => {
    const changed = { ...own, endpoint: 'https://typo.example.com' }
    vi.mocked(checkInstanceStore).mockResolvedValue(check(false))
    const w = await mountPanel(read({ setting: own, lastCheck: check(true), pending: { setting: changed, lastCheck: undefined } }))
    vi.mocked(getInstanceStore).mockResolvedValue(
      read({ setting: own, lastCheck: check(true), pending: { setting: changed, lastCheck: check(false) } }),
    )
    await w.find('.store-check').trigger('click')
    await flushPromises()
    expect(w.find('.store-verdict').attributes('data-severity')).toBe('error')
    expect(w.find('.store-verdict').text()).toBe(t('store.pendingCheckFailedAt'))
    expect(w.find('[data-step=BUCKET]').classes()).toContain('store-step--failed')
    // the console's tenant gate reads the store in use, which still passed
    const emitted = w.emitted('changed')!
    expect((emitted[emitted.length - 1][0] as InstanceStore).lastCheck?.passed).toBe(true)
  })

  it('shows the store in use once a passing check put the saved change in use', async () => {
    const changed = { ...own, endpoint: 'https://new.example.com' }
    vi.mocked(checkInstanceStore).mockResolvedValue(check(true))
    const w = await mountPanel(read({ setting: own, lastCheck: check(true), pending: { setting: changed, lastCheck: undefined } }))
    vi.mocked(getInstanceStore).mockResolvedValue(read({ setting: changed, lastCheck: check(true) }))
    await w.find('.store-check').trigger('click')
    await flushPromises()
    expect(w.find('.store-pending').exists()).toBe(false)
    expect(w.find('.store-verdict').text()).toBe(t('store.checkPassed'))
  })

  it('discards a saved change and shows the store in use again', async () => {
    const changed = { ...own, endpoint: 'https://typo.example.com' }
    vi.mocked(discardPendingInstanceStore).mockResolvedValue(read({ setting: own, lastCheck: check(true) }))
    const w = await mountPanel(read({ setting: own, lastCheck: check(true), pending: { setting: changed, lastCheck: undefined } }))
    await w.find('.store-discard').trigger('click')
    await flushPromises()
    expect(discardPendingInstanceStore).toHaveBeenCalledOnce()
    expect(w.find('.store-pending').exists()).toBe(false)
    expect(w.find('.store-discard').exists()).toBe(false)
    expect((w.find('input[name=endpoint]').element as HTMLInputElement).value).toBe('https://s3.example.com')
  })

  it('fixes type, endpoint and bucket once blobs exist, but leaves credentials editable', async () => {
    const w = await mountPanel(read({ setting: own, blobsStored: true }))
    expect(w.find('select').attributes('disabled')).toBeDefined()
    expect(w.find('input[name=endpoint]').attributes('disabled')).toBeDefined()
    expect(w.find('input[name=bucket]').attributes('disabled')).toBeDefined()
    expect(w.find('input[name=accessKey]').attributes('disabled')).toBeUndefined()
    expect(w.find('input[name=secretKey]').attributes('disabled')).toBeUndefined()
  })

  it('maps the server refusals to their own sentences', async () => {
    vi.mocked(setInstanceStore).mockRejectedValue(httpError(409, 'store_in_use'))
    const w = await mountPanel(read({ setting: own }))
    await w.find('form').trigger('submit.prevent')
    await flushPromises()
    expect(w.find('.store-error').text()).toBe(t('store.saveInUse'))
  })

  it('renders the owner-only notice, not a form, when the read is refused (403)', async () => {
    vi.mocked(getInstanceStore).mockRejectedValue(httpError(403, 'not_server_owner'))
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'login', component: { template: '<div/>' } }] })
    const w = mount(StorePanel, { global: { plugins: [router, i18n], stubs } })
    await flushPromises()
    expect(w.text()).toContain(t('store.notOwner'))
    expect(w.find('form').exists()).toBe(false)
  })
})
