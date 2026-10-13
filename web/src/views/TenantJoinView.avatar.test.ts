// @vitest-environment happy-dom
// The optional profile picture at join (ADR 0084 decision 7, uc.spaces.set-profile-picture A2/A3). The OWNER holds `K_tenant` at
// accept (cold birth), so the picture is sealed there - under that born key, at (tenant scope, the new member) - and sent as
// `sealedAvatar` next to `sealedName`. A MEMBER invite holds no key: the picker is not offered and nothing is sealed or sent.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('../features/tenant/tenantInvite', () => ({
  previewInvite: vi.fn(),
  acceptInvite: vi.fn(),
  registerAndAccept: vi.fn(),
  bornTenantScope: vi.fn(),
  tenantError: vi.fn(),
}))
vi.mock('../features/account/login/login', () => ({ login: vi.fn() }))
vi.mock('../app/sessionRefresh', () => ({ withRefresh: vi.fn((call: () => unknown) => call()) }))
vi.mock('../features/account/unlock/unlock', () => ({ unlockMasterKey: vi.fn() }))
vi.mock('../features/account/profile/profile', () => ({ sealName: vi.fn(), sealAvatar: vi.fn(), myUserId: vi.fn() }))
// The kit screen is not this file's subject: a stub that is "saved" at once.
vi.mock('../features/account/emergencyKit/EmergencyKitSave.vue', () => ({
  default: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    mounted() { this.$emit('update:modelValue', true) },
    template: '<div class="stub-kit" />',
  },
}))
// The picker is the dialog's / cropper's subject (their own tests): here it hands the view its bytes.
vi.mock('../features/account/profile/JoinPicturePicker.vue', () => ({
  default: {
    props: ['modelValue', 'name'],
    emits: ['update:modelValue'],
    setup: (_: unknown, { emit }: { emit: (e: string, v: Uint8Array) => void }) => ({ pick: () => emit('update:modelValue', new Uint8Array([9, 9, 9])) }),
    template: '<button type="button" class="stub-picker" @click="pick">picker</button>',
  },
}))
vi.mock('@veyq/crypto/accountBirth', () => ({ buildAccount: vi.fn() }))
vi.mock('../shared/pwned', () => ({ pwnedCount: vi.fn() }))
vi.mock('../api/client', () => ({ setupApi: vi.fn() }))
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
  useRoute: () => ({ hash: '#the-bootstrap-secret' }),
}))

import TenantJoinView from './TenantJoinView.vue'
import { setupApi } from '../api/client'
import { stubApi } from '../test/apiMock'
import { previewInvite, registerAndAccept, bornTenantScope, tenantError } from '../features/tenant/tenantInvite'
import { sealName, sealAvatar } from '../features/account/profile/profile'
import { InviteRole, TenantStatus } from '../api/generated/tenants'
import { buildAccount } from '@veyq/crypto/accountBirth'
import { vaultState } from '../app/vault'
import { realI18n } from '../test/i18n'
import { invitePreview, setupStatus } from '../test/models'

const BORN_KEY = new Uint8Array(32).fill(3)
const TENANT_ID = '5c09e000-0000-4000-8000-0000000000e1'

const stubs = {
  Card: { template: '<div><slot name="title" /><slot name="content" /></div>' },
  Button: { props: ['label', 'loading', 'disabled', 'type'], template: '<button :type="type" :disabled="disabled">{{ label }}</button>' },
  Message: { template: '<div class="msg"><slot /></div>' },
  InputText: { props: ['modelValue'], template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  Password: { props: ['modelValue'], template: '<input type="password" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  Checkbox: { props: ['modelValue'], template: '<input type="checkbox" :checked="modelValue" />' },
}

async function registerThrough(role: InviteRole, pickPicture: boolean) {
  vi.mocked(previewInvite).mockResolvedValue(invitePreview({ tenantId: TENANT_ID, ownerEmail: 'owner@acme.example', hasAccount: false, role }))
  const w = mount(TenantJoinView, { global: { plugins: [realI18n()], stubs } })
  await flushPromises()
  await w.find('input[name="username"]').setValue('kevin')
  await w.find('input[type="password"]').setValue('a long enough password')
  const pickerOffered = w.find('.stub-picker').exists() // read on the form, before the kit screen replaces it
  if (pickPicture && pickerOffered) await w.find('.stub-picker').trigger('click')
  await w.find('form').trigger('submit')
  await flushPromises()
  const join = w.findAll('button').find((b) => b.text() !== 'picker')
  await join!.trigger('click')
  await flushPromises()
  return { w, pickerOffered }
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(registerAndAccept).mockResolvedValue({ tenantId: TENANT_ID, status: TenantStatus.Active, userId: 'user-1' })
  vi.mocked(bornTenantScope).mockReturnValue({
    birth: { grant: 'AQ==', grantSignature: 'AQ==', admissionId: 'admission-1', admissionEpoch: 0, admissionTag: 'AQ==' },
    scopeRootKey: BORN_KEY,
  })
  vi.mocked(buildAccount).mockResolvedValue({
    identity: {}, material: {}, emergencyKit: { secretKey: 'SK' },
  } as unknown as Awaited<ReturnType<typeof buildAccount>>)
  vi.mocked(sealName).mockReturnValue('SEALED-NAME')
  vi.mocked(sealAvatar).mockReturnValue('SEALED-AVATAR')
  vi.mocked(tenantError).mockResolvedValue({ status: 0, code: null })
  stubApi(setupApi, { getSetupStatus: setupStatus() })
  vaultState.unlocked = false
})

describe('TenantJoinView - the profile picture at join', () => {
  it('OWNER: seals the chosen picture under the born tenant key at (tenant scope, the new member) and sends sealedAvatar', async () => {
    const { pickerOffered } = await registerThrough(InviteRole.Owner, true)
    expect(pickerOffered).toBe(true)
    expect(sealAvatar).toHaveBeenCalledTimes(1)
    const [key, address, bytes] = vi.mocked(sealAvatar).mock.calls[0]
    expect(key).toBe(BORN_KEY)
    expect(address.scopeId).toBe(TENANT_ID)
    expect(address.userId).toMatch(/^[0-9a-f-]{36}$/)
    expect([...bytes]).toEqual([9, 9, 9])
    const args = vi.mocked(registerAndAccept).mock.calls[0]
    expect(args[6]).toBe('SEALED-AVATAR')
    expect(args[1]).toBe(address.userId) // the very id the account is born with
  })

  it('OWNER without a chosen picture sends no sealedAvatar', async () => {
    await registerThrough(InviteRole.Owner, false)
    expect(sealAvatar).not.toHaveBeenCalled()
    expect(vi.mocked(registerAndAccept).mock.calls[0][6]).toBeUndefined()
  })

  it('MEMBER: the picker is not offered and nothing is sealed or sent (no K_tenant at join)', async () => {
    const { pickerOffered } = await registerThrough(InviteRole.Member, true)
    expect(pickerOffered).toBe(false)
    expect(sealAvatar).not.toHaveBeenCalled()
    expect(bornTenantScope).not.toHaveBeenCalled()
    const args = vi.mocked(registerAndAccept).mock.calls[0]
    expect(args[6]).toBeUndefined()
    expect(args[5]).toBeUndefined() // and no sealed name either
  })
})
