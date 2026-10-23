// @vitest-environment happy-dom
// uc.account.erase-account (web) — the "Erase account" section: what the FORM asks before anything is sent. A choice
// for every space the person owns last that holds others, the spaces deleted along named, the shared danger dialog
// asked, and the Server Owner's account answered with an explanation instead of a form. The request itself is
// `eraseAccount.test.ts`'s; here it is a mock, and the error words come from the real `erasureErrorKey`.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { httpError } from '../../../test/httpError'

vi.mock('./eraseAccount', async (orig) => ({
  ...(await orig<typeof import('./eraseAccount')>()),
  loadErasurePreview: vi.fn(),
  eraseOwnAccount: vi.fn(),
}))
vi.mock('../../../core/people/directory', () => ({ loadTenantPeople: vi.fn() }))
const ask = vi.fn()
vi.mock('../../../shared/state/useConfirm', () => ({ useConfirm: () => ({ ask }) }))

import EraseAccountPanel from './EraseAccountPanel.vue'
import { eraseOwnAccount, loadErasurePreview, type ErasurePreview } from './eraseAccount'
import { loadTenantPeople, type Person } from '../../../core/people/directory'
import { realI18n, t } from '../../../test/i18n'

const stubs = {
  VeyqButton: { props: ['label', 'loading', 'disabled', 'type'], template: '<button :type="type ?? \'button\'" :disabled="disabled">{{ label }}</button>' },
  VeyqMessage: { template: '<div class="msg"><slot /></div>' },
  VeyqPassword: {
    props: ['modelValue', 'inputId'],
    template: '<input type="password" :id="inputId" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  VeyqSelect: {
    props: ['modelValue', 'options', 'inputId', 'placeholder'],
    template:
      '<select :id="inputId" :value="modelValue ?? \'\'" @change="$emit(\'update:modelValue\', $event.target.value)">' +
      '<option value="" disabled>{{ placeholder }}</option>' +
      '<option v-for="o in options" :key="o.value" :value="o.value">{{ o.label }}</option></select>',
  },
}

const SHARED = '7c3f0e2a-9b1d-4a6e-8b2c-1f2d3e4a5b6c'
const CLUB = '8d4f1f3b-0c2e-4b7f-9c3d-2e3f4a5b6c7d'
const ALONE = '9e5a2a4c-1d3f-4c8a-8d4e-3f4a5b6c7d8e'
const ANNA = '2b6e4c1a-8d3f-4e7a-9c2b-5f1e3d4a6b7c'
const STRANGER = '3c7f5d2b-9e4a-4f8b-8d3c-6a2f4e5b7c8d'

const ready = (): ErasurePreview => ({
  kind: 'ready',
  tenants: [
    { tenantId: SHARED, name: 'Familie Muster', otherMemberIds: [ANNA] },
    { tenantId: CLUB, name: 'Verein Beispiel', otherMemberIds: [STRANGER] },
    { tenantId: ALONE, name: 'Nur ich', otherMemberIds: [] },
  ],
})
const anna = (): Person => ({ userId: ANNA, username: 'anna', realName: 'Anna Muster', displayName: 'Anna Muster' })

async function mountPanel() {
  const w = mount(EraseAccountPanel, { global: { plugins: [realI18n()], stubs }, attachTo: document.body })
  await flushPromises()
  return w
}
type W = Awaited<ReturnType<typeof mountPanel>>
const choose = async (w: W, tenantId: string, value: string) => w.find(`#erasure-choice-${tenantId}`).setValue(value)
const submit = async (w: W) => {
  await w.find('form').trigger('submit')
  await flushPromises()
}
const submitButton = (w: W) => w.find('button[type="submit"]')

beforeEach(() => {
  vi.clearAllMocks()
  document.body.innerHTML = ''
  vi.mocked(loadErasurePreview).mockResolvedValue(ready())
  vi.mocked(loadTenantPeople).mockResolvedValue(new Map([[ANNA, anna()]]))
  vi.mocked(eraseOwnAccount).mockResolvedValue(undefined)
  ask.mockResolvedValue(true)
})

describe('EraseAccountPanel (uc.account.erase-account, web)', () => {
  it('says what goes and what stays before anything else', async () => {
    const w = await mountPanel()
    for (const key of ['erasure.factGoes', 'erasure.factSharedStays', 'erasure.factKept', 'erasure.factBackups', 'erasure.factWindow']) {
      expect(w.text()).toContain(t(key))
    }
  })

  it('offers a choice for each space with others, naming members from the directory and else by id', async () => {
    const w = await mountPanel()
    expect(w.findAll('[data-choice-tenant]').map((r) => r.attributes('data-choice-tenant'))).toEqual([SHARED, CLUB])
    const options = (id: string) => w.findAll(`#erasure-choice-${id} option`).map((o) => o.text())
    expect(options(SHARED)).toEqual([t('erasure.choicePlaceholder'), t('erasure.deleteAlong'), t('erasure.makeOwner', { name: 'Anna Muster' })])
    // A member of another space this client holds no directory for: shown by account id, never left out.
    expect(options(CLUB)).toContain(t('erasure.makeOwner', { name: STRANGER }))
  })

  it('names the sole-member spaces as deleted along, with no choice to make', async () => {
    const w = await mountPanel()
    expect(w.text()).toContain(t('erasure.soleTitle'))
    expect(w.findAll('[data-sole-tenant]').map((r) => r.text())).toEqual(['Nur ich'])
    expect(w.find(`#erasure-choice-${ALONE}`).exists()).toBe(false)
  })

  it('refuses without a choice for every space with others — and sends once each one has its choice', async () => {
    const w = await mountPanel()
    await w.find('#erasure-password').setValue('my password')
    await choose(w, SHARED, ANNA)
    expect(submitButton(w).attributes('disabled')).toBeDefined()
    // Enter submits the form whatever the button's state: the handler refuses too.
    await submit(w)
    expect(w.text()).toContain(t('erasure.errorChoiceRequired'))
    expect(ask).not.toHaveBeenCalled()
    expect(eraseOwnAccount).not.toHaveBeenCalled()

    await choose(w, CLUB, 'delete-along')
    expect(submitButton(w).attributes('disabled')).toBeUndefined()
    await submit(w)
    expect(ask).toHaveBeenCalledWith(expect.objectContaining({ header: t('erasure.confirmTitle'), acceptLabel: t('erasure.action') }))
    expect(eraseOwnAccount).toHaveBeenCalledWith('my password', [
      { tenantId: SHARED, newOwnerId: ANNA },
      { tenantId: CLUB, newOwnerId: null },
    ])
  })

  it('erases nothing when the confirmation is declined', async () => {
    ask.mockResolvedValue(false)
    const w = await mountPanel()
    await w.find('#erasure-password').setValue('my password')
    await choose(w, SHARED, 'delete-along')
    await choose(w, CLUB, 'delete-along')
    await submit(w)
    expect(ask).toHaveBeenCalledOnce()
    expect(eraseOwnAccount).not.toHaveBeenCalled()
  })

  it('with no space to decide about, the password alone is asked', async () => {
    vi.mocked(loadErasurePreview).mockResolvedValue({ kind: 'ready', tenants: [] })
    const w = await mountPanel()
    expect(w.find('[data-choice-tenant]').exists()).toBe(false)
    expect(submitButton(w).attributes('disabled')).toBeDefined()
    await w.find('#erasure-password').setValue('my password')
    await submit(w)
    expect(eraseOwnAccount).toHaveBeenCalledWith('my password', [])
  })

  it.each([
    [httpError(403, 'attestation_failed'), 'erasure.errorPassword'],
    [httpError(409, 'moderation_last_holder'), 'erasure.errorModerationLastHolder'],
    [httpError(409, 'last_owner_choice_required'), 'erasure.errorChoiceRequired'],
  ])('shows the refusal in the person’s words (%#)', async (err, key) => {
    vi.mocked(eraseOwnAccount).mockRejectedValue(err)
    vi.mocked(loadErasurePreview).mockResolvedValue({ kind: 'ready', tenants: [] })
    const w = await mountPanel()
    await w.find('#erasure-password').setValue('wrong')
    await submit(w)
    expect(w.text()).toContain(t(key))
    expect(submitButton(w).attributes('disabled')).toBeUndefined() // the form is usable again
  })

  it('the Server Owner’s account is explained, and no form is offered', async () => {
    vi.mocked(loadErasurePreview).mockResolvedValue({ kind: 'serverOwner' })
    const w = await mountPanel()
    expect(w.text()).toContain(t('erasure.serverOwner'))
    expect(w.find('form').exists()).toBe(false)
    expect(w.find('#erasure-password').exists()).toBe(false)
  })
})
