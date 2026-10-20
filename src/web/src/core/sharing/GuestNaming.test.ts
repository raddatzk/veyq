// @vitest-environment happy-dom
// The guest id in a guest's session line (owner 2026-10-18, uc.admin.privacy-notice A3): a guest has no account, so the
// id it names in an objection to the guest-write record (GDPR Art. 21(1) with 12(2)) is shown to copy — while this
// link's writes are recorded, and only once the device holds an identity. Each absence beside its control.
import { describe, it, expect, vi, afterEach } from 'vitest'
import PrimeVue from 'primevue/config'
import { mount, flushPromises } from '@vue/test-utils'
import GuestNaming from './GuestNaming.vue'
import { realI18n, t } from '../../test/i18n'

const ID = '3f2b8c1e-6d4a-4e9b-a1c7-0b5d9e2f4a68'

function mountLine(props: Record<string, unknown>) {
  return mount(GuestNaming, { props, global: { plugins: [realI18n(), PrimeVue] } })
}

afterEach(() => vi.unstubAllGlobals())

describe('GuestNaming — the guest id for an objection', () => {
  it('shows the session’s guest id to copy while the link’s writes are recorded', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('navigator', { clipboard: { writeText } })
    const w = mountLine({ current: { displayName: 'Anna', identityId: ID }, recordDays: 7 })
    const line = w.find('[data-testid="guest-naming-guest-id"]')
    expect(line.text()).toContain(t('share.guest.guestId'))
    expect(line.find('code').text()).toBe(ID)
    await line.find('button').trigger('click')
    await flushPromises()
    expect(writeText).toHaveBeenCalledWith(ID)
    expect(line.find('button').text()).toBe(t('share.guest.guestIdCopied'))
  })

  it('shows none while nothing is recorded (control: the session line itself stays)', () => {
    const w = mountLine({ current: { displayName: 'Anna', identityId: ID }, recordDays: null })
    expect(w.find('[data-testid="guest-naming-guest-id"]').exists()).toBe(false)
    expect(w.find('.guest-naming-identity').text()).toContain('Anna')
  })

  it('shows none before the device holds an identity — a name typed, nothing sent under it yet', () => {
    const w = mountLine({ current: { displayName: 'Anna' }, recordDays: 7 })
    expect(w.find('[data-testid="guest-naming-guest-id"]').exists()).toBe(false)
    expect(w.find('[data-testid="guest-write-record-notice"]').exists()).toBe(true) // control: the notice is there
  })
})
