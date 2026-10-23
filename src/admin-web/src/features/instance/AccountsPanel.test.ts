// @vitest-environment happy-dom
// uc.account.erase-account (admin-web) — Server → Accounts. Each case is one observable the Server Owner depends on:
//   - the pending erasures are listed with who asked, when, and when the account is erased; none says so;
//   - the erasure is refused until the address is typed twice the same (negative AND positive control on the one
//     submit path), then sent, said with its date, and the list read again;
//   - what happens is said before it happens (the Server Owner's own account, a last owner's tenant, what stays);
//   - each refusal the server can give is named apart;
//   - Stop reaches the erasure of that row's address, without a confirmation.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

// The confirmation rule and the requester enum stay real: the gate is what is asserted.
vi.mock('./accounts', async () => {
  const actual = await vi.importActual<typeof import('./accounts')>('./accounts')
  return { ...actual, listAccountErasures: vi.fn(), eraseAccount: vi.fn(), stopAccountErasure: vi.fn() }
})

import AccountsPanel from './AccountsPanel.vue'
import { eraseAccount, listAccountErasures, stopAccountErasure, type PendingAccountErasure } from './accounts'
import { httpError } from '../../test/httpError'
import { i18n } from '../../i18n'

const stubs = {
  VeyqMessage: { props: ['tone'], template: '<div class="msg" :data-severity="tone"><slot /></div>' },
  VeyqInput: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<input class="input" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  VeyqButton: {
    props: ['label', 'disabled'],
    emits: ['click'],
    template: '<button type="button" :disabled="disabled" @click="$emit(\'click\', $event)">{{ label }}</button>',
  },
}

const DUE = new Date('2026-11-17T10:00:00Z')
const row = (address: string, requestedBy: PendingAccountErasure['requestedBy']): PendingAccountErasure => ({
  address, requestedAt: new Date('2026-10-18T10:00:00Z'), requestedBy, dueAt: DUE,
})

async function mountPanel() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'login', component: { template: '<div/>' } }] })
  const wrapper = mount(AccountsPanel, { global: { plugins: [router, i18n], stubs } })
  await flushPromises()
  return wrapper
}

const inputs = (w: Awaited<ReturnType<typeof mountPanel>>) => w.findAll('form.account-erase input.input')
const eraseButton = (w: Awaited<ReturnType<typeof mountPanel>>) => w.findAll('button').find((b) => b.text() === 'Erase account')!
const submit = (w: Awaited<ReturnType<typeof mountPanel>>) => w.find('form.account-erase').trigger('submit')

describe('AccountsPanel (uc.account.erase-account, admin-web)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    i18n.global.locale.value = 'en'
    vi.mocked(listAccountErasures).mockResolvedValue([])
    vi.mocked(eraseAccount).mockResolvedValue(row('member@example.com', 'SERVER_OWNER'))
    vi.mocked(stopAccountErasure).mockResolvedValue(undefined)
  })

  it('says none is pending, and says what an erasure does before anything is typed', async () => {
    const w = await mountPanel()
    expect(w.text()).toContain('No erasure is pending.')
    expect(w.text()).toContain('The account stops at once')
    expect(w.text()).toContain('After 30 days it is erased for good')
    expect(w.text()).toContain("Your own account, the Server Owner's, can't be erased.")
    expect(w.text()).toContain('that tenant is left without an owner')
    expect(w.text()).toContain('Holds and moderation records that name the account stay')
  })

  it('lists each pending erasure with who asked and when it is erased, and a Stop per row', async () => {
    vi.mocked(listAccountErasures).mockResolvedValue([row('self@example.com', 'SELF'), row('asked@example.com', 'SERVER_OWNER')])
    const w = await mountPanel()
    const rows = w.findAll('.account-erasures__row')
    expect(rows).toHaveLength(2)
    expect(rows[0].text()).toContain('self@example.com')
    expect(rows[0].text()).toContain('Requested by the account itself')
    expect(rows[1].text()).toContain('Requested by you')
    expect(rows[1].text()).toContain(`To be erased on ${DUE.toLocaleString('en')}`)
    expect(rows.every((r) => r.find('button').text() === 'Stop erasure')).toBe(true)
  })

  it('refuses to erase until the address is typed twice the same, then erases it and reads the list again', async () => {
    const w = await mountPanel()
    expect(eraseButton(w).attributes('disabled')).toBeDefined()

    await inputs(w)[0].setValue('member@example.com')
    await submit(w) // typed once only
    await inputs(w)[1].setValue('member@example.co')
    await submit(w) // typed twice, differently
    await flushPromises()
    expect(eraseAccount).not.toHaveBeenCalled()
    expect(eraseButton(w).attributes('disabled')).toBeDefined()

    // the positive control on the same path: equal (case aside) arms it
    await inputs(w)[1].setValue('Member@Example.com')
    expect(eraseButton(w).attributes('disabled')).toBeUndefined()
    vi.mocked(listAccountErasures).mockResolvedValue([row('member@example.com', 'SERVER_OWNER')])
    await submit(w)
    await flushPromises()
    expect(eraseAccount).toHaveBeenCalledWith('member@example.com')
    expect(w.text()).toContain(`member@example.com will be erased on ${DUE.toLocaleString('en')}.`)
    expect(w.findAll('.account-erasures__row')).toHaveLength(1)
    expect(w.emitted('changed')).toHaveLength(1) // the console reads its tenant list again (an ownerless marker)
    expect((inputs(w)[0].element as HTMLInputElement).value).toBe('') // cleared, so a second erasure is typed afresh
  })

  it('names each refusal apart', async () => {
    const w = await mountPanel()
    for (const [status, code, expected] of [
      [404, 'account_not_found', 'No account has that address.'],
      [409, 'server_owner_account', "That is your own account, the Server Owner's."],
      [409, 'account_erasure_pending', 'An erasure of that account is already pending.'],
      [409, 'moderation_last_holder', 'the last moderator who holds the moderation key'],
      [500, undefined, 'Couldn’t erase the account.'],
    ] as const) {
      await inputs(w)[0].setValue('x@example.com')
      await inputs(w)[1].setValue('x@example.com')
      vi.mocked(eraseAccount).mockRejectedValueOnce(httpError(status, code))
      await submit(w)
      await flushPromises()
      expect(w.find('.msg[data-severity="error"]').text()).toContain(expected)
    }
    expect(w.emitted('changed')).toBeUndefined()
  })

  it('stops a pending erasure by its address, without asking, and reads the list again', async () => {
    vi.mocked(listAccountErasures).mockResolvedValue([row('self@example.com', 'SELF')])
    const w = await mountPanel()
    vi.mocked(listAccountErasures).mockResolvedValue([])
    await w.find('.account-erasures__row button').trigger('click')
    await flushPromises()
    expect(stopAccountErasure).toHaveBeenCalledWith('self@example.com')
    expect(w.text()).toContain('The erasure of self@example.com is stopped.')
    expect(w.text()).toContain('No erasure is pending.')
    expect(w.emitted('changed')).toHaveLength(1)
  })

  it('says a stop that failed', async () => {
    vi.mocked(listAccountErasures).mockResolvedValue([row('self@example.com', 'SELF')])
    vi.mocked(stopAccountErasure).mockRejectedValueOnce(httpError(404, 'account_erasure_not_found'))
    const w = await mountPanel()
    await w.find('.account-erasures__row button').trigger('click')
    await flushPromises()
    expect(w.find('.msg[data-severity="error"]').text()).toContain('Couldn’t stop the erasure.')
  })
})
