// @vitest-environment happy-dom
// uc.admin.tenant-mail-settings (web, cr-email-tenant-mail-settings-web) — the owner-gated Email rubric. Covers the
// four behaviours the CR names: the write-only password "leave blank to keep" pattern (omit vs send, then clear),
// the closed-variable-set template rejection surfaced understandably (400 invalid_mail_template), the honest
// KEK-unavailable message (503 mail_secret_unavailable), and honest send-readiness (enabled alone doesn't send —
// show what's missing). Plus the owner gate: a 403 read renders a read-only notice, not the editor.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { httpError } from '../../test/httpError'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('./tenantMailSettings', () => ({
  getTenantMailSettings: vi.fn(),
  updateTenantMailSettings: vi.fn(),
}))
vi.mock('../../shared/state/useToast', () => ({
  useToast: () => ({ success: vi.fn(), showUndo: vi.fn(), dismissUndo: vi.fn() }),
}))

import TenantMailSettingsPanel from './TenantMailSettingsPanel.vue'
import { getTenantMailSettings, updateTenantMailSettings } from './tenantMailSettings'
import { activeSpace, clearActiveSpace } from '../../app/activeSpace'
import { ScopeKind } from '../../api/generated/scopes'
import { realI18n, t } from '../../test/i18n'

const READY_VIEW = {
  host: 'smtp.example.com', port: 587, from: 'noreply@x.z', username: 'fam',
  tls: true, enabled: true, passwordSet: true, subjectTemplate: null, bodyTemplate: null, configSecretState: 'OK',
}

const stubs = {
  Button: { props: ['label', 'loading'], emits: ['click'], template: '<button :disabled="loading" @click="$emit(\'click\')">{{ label }}</button>' },
  Message: { props: ['severity'], template: '<div class="msg"><slot /></div>' },
  InputText: {
    props: ['modelValue', 'type', 'placeholder'],
    emits: ['update:modelValue'],
    template: '<input :type="type || \'text\'" :placeholder="placeholder" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  ToggleSwitch: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<input type="checkbox" :checked="modelValue" @change="$emit(\'update:modelValue\', $event.target.checked)" />',
  },
}

async function mountPanel() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'login', component: { template: '<div/>' } }] })
  const wrapper = mount(TenantMailSettingsPanel, { global: { plugins: [router, realI18n()], stubs } })
  await flushPromises()
  return wrapper
}

/** The Save button, found by its label (the only button in the panel). */
const saveButton = (w: Awaited<ReturnType<typeof mountPanel>>) =>
  w.findAll('button').find((b) => b.text() === t('mail.save'))!

describe('TenantMailSettingsPanel (uc.admin.tenant-mail-settings, web)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    clearActiveSpace()
    activeSpace.kind = ScopeKind.Tenant
    activeSpace.scopeId = 't-1'
    activeSpace.tenantId = 't-1'
    vi.mocked(updateTenantMailSettings).mockResolvedValue(READY_VIEW as never)
  })

  it('renders a read-only notice (not the editor) when the read is owner-gated 403', async () => {
    vi.mocked(getTenantMailSettings).mockRejectedValue(httpError(403, 'not_tenant_owner'))
    const wrapper = await mountPanel()
    expect(wrapper.text()).toContain(t('mail.notOwner'))
    expect(saveButton(wrapper)).toBeUndefined() // no editor for a non-owner
  })

  it('shows the leave-blank-keeps hint and OMITS the password when the field is left empty', async () => {
    vi.mocked(getTenantMailSettings).mockResolvedValue(READY_VIEW as never)
    const wrapper = await mountPanel()
    expect(wrapper.text()).toContain(t('mail.passwordSetHint'))

    await saveButton(wrapper).trigger('click')
    await flushPromises()
    const [, update] = vi.mocked(updateTenantMailSettings).mock.calls[0]
    expect('password' in update).toBe(false) // omitted ⇒ the backend keeps the stored ciphertext
  })

  it('sends a freshly typed password and then clears it from the field (no lingering plaintext)', async () => {
    vi.mocked(getTenantMailSettings).mockResolvedValue({ ...READY_VIEW, passwordSet: false } as never)
    const wrapper = await mountPanel()
    const pw = wrapper.find('input[type="password"]')
    await pw.setValue('hunter2')

    await saveButton(wrapper).trigger('click')
    await flushPromises()
    const [, update] = vi.mocked(updateTenantMailSettings).mock.calls[0]
    expect(update.password).toBe('hunter2')
    // Consumed on submit — the plaintext must not linger in the field.
    expect((wrapper.find('input[type="password"]').element as HTMLInputElement).value).toBe('')
  })

  it('surfaces a rejected template (400 invalid_mail_template) with the allowed-variables message', async () => {
    vi.mocked(getTenantMailSettings).mockResolvedValue(READY_VIEW as never)
    vi.mocked(updateTenantMailSettings).mockRejectedValue(httpError(400, 'invalid_mail_template'))
    const wrapper = await mountPanel()
    await saveButton(wrapper).trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain(t('mail.invalidTemplate', { vars: '{{link}}, {{domain}}, {{expiry}}' }))
    expect(wrapper.text()).not.toContain(t('mail.saveFailed'))
  })

  it('surfaces the KEK-unavailable case (503 mail_secret_unavailable) understandably, not as a generic error', async () => {
    vi.mocked(getTenantMailSettings).mockResolvedValue({ ...READY_VIEW, passwordSet: false } as never)
    vi.mocked(updateTenantMailSettings).mockRejectedValue(httpError(503, 'mail_secret_unavailable'))
    const wrapper = await mountPanel()
    await wrapper.find('input[type="password"]').setValue('hunter2')
    await saveButton(wrapper).trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain(t('mail.kekUnavailable'))
    expect(wrapper.text()).not.toContain(t('mail.saveFailed'))
  })

  it('shows the config is send-ready when enabled + host + a stored password are all present', async () => {
    vi.mocked(getTenantMailSettings).mockResolvedValue(READY_VIEW as never)
    const wrapper = await mountPanel()
    expect(wrapper.text()).toContain(t('mail.sendReady'))
  })

  it('proactively flags a changed config-secret key (MISMATCH) and is not send-ready', async () => {
    // The stored password was sealed under a key the server no longer has — a real, detected state (ADR 0050 amendment).
    vi.mocked(getTenantMailSettings).mockResolvedValue({ ...READY_VIEW, configSecretState: 'MISMATCH' } as never)
    const wrapper = await mountPanel()
    expect(wrapper.text()).toContain(t('mail.keyMismatch'))
    expect(wrapper.text()).not.toContain(t('mail.sendReady'))
  })

  it('proactively shows the KEK-unavailable message when the server can’t seal secrets (ABSENT)', async () => {
    vi.mocked(getTenantMailSettings).mockResolvedValue({ ...READY_VIEW, configSecretState: 'ABSENT' } as never)
    const wrapper = await mountPanel()
    expect(wrapper.text()).toContain(t('mail.kekUnavailable'))
    expect(wrapper.text()).not.toContain(t('mail.sendReady'))
  })

  it('is honest that enabled alone does not send — it lists what is still missing', async () => {
    // enabled is ticked, but no host and no stored password ⇒ nothing can be sent, and inviting is refused.
    vi.mocked(getTenantMailSettings).mockResolvedValue({ ...READY_VIEW, host: null, passwordSet: false } as never)
    const wrapper = await mountPanel()
    expect(wrapper.text()).toContain(t('mail.notSendReady'))
    expect(wrapper.text()).toContain(t('mail.missingHost'))
    expect(wrapper.text()).toContain(t('mail.missingPassword'))
    expect(wrapper.text()).not.toContain(t('mail.missingEnabled')) // enabled IS on
    expect(wrapper.text()).not.toContain(t('mail.sendReady'))
  })

  it('shows an auth-less host (no username, no password) as send-ready and never demands a password', async () => {
    // A legit auth-less MTA: enabled + host but NO username ⇒ the backend sends with no login (SmtpTenantInviteMailer
    // keys auth on `username`), so the panel must mirror that — send-ready, and a password is NOT listed as missing.
    vi.mocked(getTenantMailSettings).mockResolvedValue({ ...READY_VIEW, username: null, passwordSet: false } as never)
    const wrapper = await mountPanel()
    expect(wrapper.text()).toContain(t('mail.sendReady'))
    expect(wrapper.text()).not.toContain(t('mail.missingPassword'))
    expect(wrapper.text()).not.toContain(t('mail.notSendReady'))
  })

  it('does not let an ABSENT KEK block an auth-less host — the KEK is irrelevant with no password to unseal', async () => {
    // No username ⇒ nothing is ever unsealed, so the config-secret KEK state must not dominate the readiness view
    // (else the panel would show "KEK unavailable" while the backend happily sends). Send-ready, no KEK banner.
    vi.mocked(getTenantMailSettings).mockResolvedValue({ ...READY_VIEW, username: null, passwordSet: false, configSecretState: 'ABSENT' } as never)
    const wrapper = await mountPanel()
    expect(wrapper.text()).toContain(t('mail.sendReady'))
    expect(wrapper.text()).not.toContain(t('mail.kekUnavailable'))
  })
})
