// @vitest-environment happy-dom
// uc.admin.create-tenant (web Slice A) — the create-dialog UI flow: it shapes the createTenant call
// (domain lowercased, fields trimmed), emits `created` + closes on success, and surfaces domain_taken
// distinctly. Component-level acceptance for `create-pending-tenant` (the manual browser smoke, automated).
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

vi.mock('./tenants', () => ({
  createTenant: vi.fn().mockResolvedValue({ tenantId: 't-1', bootstrapSecret: 's' }),
  tenantError: vi.fn().mockResolvedValue({ status: 0, code: null }),
}))

import CreateTenantDialog from './CreateTenantDialog.vue'
import { createTenant, tenantError } from './tenants'

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
        ownerEmail: 'Owner email', domainTaken: 'That domain is already taken', createFailed: 'Could not create',
        created: 'Tenant created',
      },
      members: { copyLinkIntro: 'copy it', copyLink: 'Copy link', copied: 'Copied' },
    },
  },
})

const stubs = {
  Dialog: { props: ['visible'], template: '<div v-if="visible"><slot /></div>' },
  InputText: {
    props: ['modelValue'],
    template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  Button: {
    props: ['type', 'label', 'disabled'],
    template: '<button :type="type || \'button\'" :disabled="disabled" @click="$emit(\'click\', $event)">{{ label }}</button>',
  },
  Message: { template: '<div class="msg"><slot /></div>' },
}

function mountDialog() {
  return mount(CreateTenantDialog, {
    props: { visible: true },
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
  beforeEach(() => vi.clearAllMocks())

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
})
