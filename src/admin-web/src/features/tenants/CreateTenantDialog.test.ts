// @vitest-environment happy-dom
// uc.admin.create-tenant (web Slice A) — the create-dialog UI flow: it shapes the createTenant call
// (domain lowercased, fields trimmed), emits `created` + closes on success, and surfaces domain_taken
// distinctly. Component-level acceptance for `create-pending-tenant` (the manual browser smoke, automated).
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

vi.mock('./tenants', () => ({ createTenant: vi.fn(), tenantError: vi.fn() }))

import CreateTenantDialog from './CreateTenantDialog.vue'
import { createTenant, tenantError, type TenantCreated } from './tenants'

const CREATED: TenantCreated = {
  tenantId: 't-1', status: 'PENDING', inviteExpiresAt: new Date('2026-10-10T12:00:00.000Z'), bootstrapSecret: 's',
  delivery: 'FALLBACK_COPYLINK',
}

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  missingWarn: false,
  fallbackWarn: false,
  messages: {
    en: {
      common: { cancel: 'Cancel', create: 'Create', done: 'Done' },
      tenant: {
        create: 'Create a tenant', name: 'Name', domain: 'Domain', domainHint: 'hint',
        ownerEmail: 'Owner email', domainTaken: 'That domain is already taken', createFailed: 'Could not create', storeNotReady: 'Needs a checked store', openStore: 'Open the store',
        created: 'Tenant created',
      },
      members: { copyLinkIntro: 'copy it', copyLink: 'Copy link', copied: 'Copied' },
    },
  },
})

const stubs = {
  VeyqDialog: { props: ['visible'], template: '<div v-if="visible"><slot /></div>' },
  VeyqInput: {
    props: ['modelValue'],
    template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  VeyqButton: {
    props: ['type', 'label', 'disabled'],
    template: '<button :type="type || \'button\'" :disabled="disabled" @click="$emit(\'click\', $event)">{{ label }}</button>',
  },
  VeyqMessage: { template: '<div class="msg"><slot /></div>' },
}

function mountDialog(extra: { storeBlocked?: boolean } = {}) {
  return mount(CreateTenantDialog, {
    props: { visible: true, ...extra },
    global: { plugins: [i18n], stubs },
  })
}

async function fillForm(wrapper: ReturnType<typeof mountDialog>, name: string, domain: string, email: string) {
  const inputs = wrapper.findAll('input')
  await inputs[0].setValue(name)
  await inputs[1].setValue(domain)
  await inputs[2].setValue(email)
}

describe('CreateTenantDialog (uc.admin.create-tenant, web Slice A)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(createTenant).mockResolvedValue(CREATED)
    vi.mocked(tenantError).mockResolvedValue({ status: 0, code: null })
  })

  it('submits a trimmed name/email + lowercased domain, then emits created and shows the copy-link view', async () => {
    const wrapper = mountDialog()
    await fillForm(wrapper, '  Acme  ', '  ACME.example  ', '  owner@acme.example  ')
    await wrapper.find('form').trigger('submit.prevent')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    expect(createTenant).toHaveBeenCalledWith('Acme', 'acme.example', 'owner@acme.example')
    expect(wrapper.emitted('created')).toHaveLength(1)
    // Stays open showing the owner-invite link (built with the domain + returned secret) for the owner to copy.
    expect(wrapper.emitted('update:visible')).toBeUndefined()
    expect((wrapper.find('.invite-link-input').element as HTMLInputElement).value).toContain('acme.example/tenant/accept#s')
  })

  it('does not submit until every field is filled', async () => {
    const wrapper = mountDialog()
    await fillForm(wrapper, 'Acme', '', 'owner@acme.example')
    await wrapper.find('form').trigger('submit.prevent')
    await wrapper.vm.$nextTick()
    expect(createTenant).not.toHaveBeenCalled()
    expect(wrapper.emitted('created')).toBeUndefined()
  })

  it('surfaces domain_taken distinctly and stays open', async () => {
    vi.mocked(createTenant).mockRejectedValueOnce(new Error('409'))
    vi.mocked(tenantError).mockResolvedValueOnce({ status: 409, code: 'domain_taken' })
    const wrapper = mountDialog()
    await fillForm(wrapper, 'Acme', 'acme.example', 'owner@acme.example')
    await wrapper.find('form').trigger('submit.prevent')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    expect(wrapper.text()).toContain('That domain is already taken')
    expect(wrapper.emitted('created')).toBeUndefined()
    expect(wrapper.emitted('update:visible')).toBeUndefined()
  })

  // ADR 0089 decision 6 — closed WITH the reason, not silently disabled.
  it('is closed with the reason while no store has passed its check, and sends nothing', async () => {
    const wrapper = mountDialog({ storeBlocked: true })
    await fillForm(wrapper, 'Acme', 'acme.example', 'owner@acme.example')
    expect(wrapper.text()).toContain('Needs a checked store')
    await wrapper.find('form').trigger('submit.prevent')
    await wrapper.vm.$nextTick()
    expect(createTenant).not.toHaveBeenCalled()
    // the way out is one click: it asks the console to open the store
    await wrapper.findAll('button').find((b) => b.text() === 'Open the store')!.trigger('click')
    // (the Button stub fires both the native click and its own `click` emit, so "at least once" is the claim)
    expect(wrapper.emitted('openStore')?.length).toBeGreaterThanOrEqual(1)
  })

  it('stays open for an unblocked store (the paired case: the reason is absent, the create goes through)', async () => {
    const wrapper = mountDialog({ storeBlocked: false })
    expect(wrapper.text()).not.toContain('Needs a checked store')
    await fillForm(wrapper, 'Acme', 'acme.example', 'owner@acme.example')
    await wrapper.find('form').trigger('submit.prevent')
    await wrapper.vm.$nextTick()
    expect(createTenant).toHaveBeenCalledOnce()
  })

  it("shows the server's own store_not_ready refusal when the console's flag was stale", async () => {
    vi.mocked(createTenant).mockRejectedValueOnce(new Error('409'))
    vi.mocked(tenantError).mockResolvedValueOnce({ status: 409, code: 'store_not_ready' })
    const wrapper = mountDialog()
    await fillForm(wrapper, 'Acme', 'acme.example', 'owner@acme.example')
    await wrapper.find('form').trigger('submit.prevent')
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('Needs a checked store')
    expect(wrapper.emitted('openStore')).toHaveLength(1)
    expect(wrapper.emitted('created')).toBeUndefined()
  })
})
