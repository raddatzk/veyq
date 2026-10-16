// @vitest-environment happy-dom
// uc.admin.capability-requirements (web) — the drop box's share dialog builds no password and no end date of its own;
// where the space requires them (ADR 0088 decision 8) it asks for both, waits for them, and sends them with the link.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('../dropBox', () => ({ mintDropBoxLink: vi.fn() }))
vi.mock('../../../core/sharing/manageShares', () => ({
  deleteShare: vi.fn(), isReissueRequired: vi.fn(), listSharesFor: vi.fn(), reactivateShare: vi.fn(), revokeShare: vi.fn(),
}))
vi.mock('../../../core/sharing/shareLink', () => ({ buildShareLink: vi.fn(() => 'https://x/s/new#k=a'), recoverShareLink: vi.fn() }))

import DropBoxShareDialog from './DropBoxShareDialog.vue'
import { mintDropBoxLink } from '../dropBox'
import { listSharesFor } from '../../../core/sharing/manageShares'
import { setTenantCapabilities } from '../../../shared/state/useCapabilities'
import { realI18n, t } from '../../../test/i18n'

const stubs = {
  Dialog: { template: '<div><slot /></div>' },
  Button: { props: ['label', 'disabled'], emits: ['click'], template: '<button :disabled="disabled" @click="$emit(\'click\')">{{ label }}</button>' },
  Message: { template: '<div><slot /></div>' },
  InputText: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<input class="input" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  DatePicker: { props: ['modelValue'], template: '<input class="datepicker" />' },
}

function mountDialog() {
  return mount(DropBoxShareDialog, {
    props: { scopeId: 's', nodeId: 'n', nodeKey: new Uint8Array(32), epochs: { current: 0, keys: new Map() }, nodeName: 'Box', nodeKind: 'DEPOT_DROPBOX' },
    global: { plugins: [realI18n()], stubs },
  })
}
const createButton = (w: ReturnType<typeof mountDialog>) => w.findAll('button').find((b) => b.text() === t('depot.dropBox.shareCreate'))!

describe('DropBoxShareDialog — the space\'s link requirements', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listSharesFor).mockResolvedValue([])
    vi.mocked(mintDropBoxLink).mockResolvedValue({ shareId: 'new', kShare: new Uint8Array(32) })
  })
  afterEach(() => setTenantCapabilities(null))

  it('asks for a password and an end date when required, waits for them, and sends both', async () => {
    setTenantCapabilities([{ id: 'core.public-shares', linkPassword: true, linkMaxDays: 7 }])
    const w = mountDialog()
    await flushPromises()
    expect(w.text()).toContain(t('share.passwordRequiredByRule'))
    expect(w.text()).toContain(t('share.expiryRequiredByRule', { days: 7 }))
    expect(createButton(w).attributes('disabled')).toBeDefined() // no password yet
    await w.find('#dropbox-share-password').setValue('a-long-password')
    expect(createButton(w).attributes('disabled')).toBeUndefined()
    await createButton(w).trigger('click')
    await flushPromises()
    const call = vi.mocked(mintDropBoxLink).mock.calls[0]!
    expect(call[6]).toBe('a-long-password')
    expect((call[7] as Date).getTime()).toBeLessThanOrEqual(Date.now() + 7 * 24 * 3600 * 1000)
  })

  it('control: without requirements it asks for neither and mints at once', async () => {
    const w = mountDialog()
    await flushPromises()
    expect(w.find('#dropbox-share-password').exists()).toBe(false)
    await createButton(w).trigger('click')
    await flushPromises()
    const call = vi.mocked(mintDropBoxLink).mock.calls[0]!
    expect(call[6]).toBe('')
    expect(call[7]).toBeNull()
  })
})
