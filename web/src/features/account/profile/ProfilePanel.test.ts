// @vitest-environment happy-dom
// uc.spaces.edit-profile (web) — the "Your profile" rubric, mounted. `./profile` is mocked at its I/O functions
// (`getMyProfile`, `updateMyProfile`, `openOwnNameKey`) while `sealName` / `unsealName` stay REAL: every assertion
// about the name unseals the blob the panel sent, under the test key at the member's own address, instead of
// comparing it to a string — so a name sealed at the wrong address (another member's row) reddens here.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import * as v1 from '@veyq/crypto/v1'
import { httpError } from '../../../test/httpError'
import { realI18n, t } from '../../../test/i18n'

vi.mock('./profile', async (orig) => ({
  ...(await orig<typeof import('./profile')>()),
  getMyProfile: vi.fn(),
  updateMyProfile: vi.fn(),
  openOwnNameKey: vi.fn(),
}))
vi.mock('../../../shared/state/useToast', () => ({
  useToast: () => ({ success: vi.fn(), showUndo: vi.fn(), dismissUndo: vi.fn() }),
}))

import ProfilePanel from './ProfilePanel.vue'
import { getMyProfile, updateMyProfile, openOwnNameKey, sealName, unsealName } from './profile'

const K_TENANT = v1.randomBytes(32)
const ADDRESS = { scopeId: '5c09e000-0000-4000-8000-0000000000d1', userId: '5c09e000-0000-4000-8000-0000000000d2' }
const STORED = { username: 'kevin', sealedName: sealName(K_TENANT, ADDRESS, { firstName: 'Kevin', lastName: 'Raddatz' }), avatarVersion: 0, requireGroupAddConsent: false }

const stubs = {
  Button: { props: ['label', 'loading'], template: '<button type="submit">{{ label }}</button>' },
  Message: { template: '<div class="msg"><slot /></div>' },
  Checkbox: { props: ['modelValue'], template: '<input type="checkbox" :checked="modelValue" />' },
  // `disabled` is not a prop here, so it falls through to the <input> exactly as the panel binds it.
  InputText: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
}

async function mountPanel() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'login', component: { template: '<div/>' } }] })
  const wrapper = mount(ProfilePanel, { global: { plugins: [router, realI18n()], stubs } })
  await flushPromises()
  return wrapper
}
type Panel = Awaited<ReturnType<typeof mountPanel>>

const input = (w: Panel, autocomplete: string) => w.find(`input[autocomplete="${autocomplete}"]`)
async function save(w: Panel) {
  await w.find('form').trigger('submit')
  await flushPromises()
}
/** The one update the panel sent. */
const sent = () => {
  expect(updateMyProfile).toHaveBeenCalledTimes(1)
  return vi.mocked(updateMyProfile).mock.calls[0][0]
}

describe('ProfilePanel (uc.spaces.edit-profile, web)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(getMyProfile).mockResolvedValue(STORED)
    vi.mocked(updateMyProfile).mockResolvedValue(STORED)
    vi.mocked(openOwnNameKey).mockResolvedValue({ key: K_TENANT, address: ADDRESS })
  })

  it('loads and shows the stored name, unsealed on the device', async () => {
    const wrapper = await mountPanel()
    expect((input(wrapper, 'username').element as HTMLInputElement).value).toBe('kevin')
    expect((input(wrapper, 'given-name').element as HTMLInputElement).value).toBe('Kevin')
    expect((input(wrapper, 'family-name').element as HTMLInputElement).value).toBe('Raddatz')
    expect(input(wrapper, 'given-name').attributes('disabled')).toBeUndefined() // the key is held: editable
  })

  it('saves the handle trimmed and lower-cased, with the name sealed at the member’s own address', async () => {
    const wrapper = await mountPanel()
    await input(wrapper, 'username').setValue('  Kevin ')
    await input(wrapper, 'given-name').setValue('Kev')
    await input(wrapper, 'family-name').setValue('Raddatz')
    await save(wrapper)

    const update = sent()
    expect(update.username).toBe('kevin')
    expect(update.requireGroupAddConsent).toBe(false)
    expect(unsealName(K_TENANT, ADDRESS, update.sealedName)).toEqual({ firstName: 'Kev', lastName: 'Raddatz' })
    expect(wrapper.text()).not.toContain(t('profile.saveFailed'))
  })

  it('E1: a 409 username_taken says the handle is taken', async () => {
    vi.mocked(updateMyProfile).mockRejectedValue(httpError(409, 'username_taken'))
    const wrapper = await mountPanel()
    await save(wrapper)
    expect(wrapper.text()).toContain(t('profile.usernameTaken'))
    expect(wrapper.text()).not.toContain(t('profile.saveFailed'))
  })

  it('E2: without a grant the name inputs are disabled and only the username is saved', async () => {
    vi.mocked(openOwnNameKey).mockResolvedValue(null)
    const wrapper = await mountPanel()
    expect(wrapper.text()).toContain(t('profile.noNameYet'))
    expect(input(wrapper, 'given-name').attributes('disabled')).toBeDefined()
    expect(input(wrapper, 'family-name').attributes('disabled')).toBeDefined()
    expect(input(wrapper, 'username').attributes('disabled')).toBeUndefined()

    await input(wrapper, 'username').setValue('newhandle')
    await save(wrapper)
    const update = sent()
    expect(update.username).toBe('newhandle')
    expect(update.sealedName).toBeUndefined()
  })

  it('A1: emptying both name fields sends a sealed empty name, so the name is removed', async () => {
    const wrapper = await mountPanel()
    await input(wrapper, 'given-name').setValue('')
    await input(wrapper, 'family-name').setValue('  ')
    await save(wrapper)

    const update = sent()
    expect(update.sealedName).toBeDefined()
    expect(unsealName(K_TENANT, ADDRESS, update.sealedName)).toEqual({ firstName: '', lastName: '' })
  })
})
