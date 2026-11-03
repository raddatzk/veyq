// @vitest-environment happy-dom
// The editor's payers and items (uc.expenses.record-entries), through the real component and real DOM events: the amount is
// the payers' sum, shown and never typed; a new entry has no items, so its whole amount is the rest, split equally among
// everyone — shown as ONE line with "Adjust" (owner 2026-11-03); adjusted, a split is by shares, by percent or by amount
// (the Reka toggle answers a click), its people rows with a person each (`VeyqSelect`, driven by its `update:modelValue` —
// a Reka select opens on `pointerdown`, src/web/CLAUDE.md), added and taken off one by one, "Split equally among all"
// putting everyone back at 1 share; shares of 1 each are saved as `equal`. An entry saved equal over everyone opens as
// that one line again, any other split as its rows. A split's percents or amounts beside what they must make, and items
// above the amount, say so live in red where they are; what the save finds wrong is said in red at its field — "Paid by",
// the title, the split it is in — never in the message at the bottom, which keeps only what belongs to no field.
import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { computed, ref } from 'vue'
import * as v1 from '@veyq/crypto/v1'
import EntryEditor from './EntryEditor.vue'
import VeyqSelect from '../../../shared/ui/VeyqSelect.vue'
import { realI18n, t } from '../../../test/i18n'
import { buildLedger } from '../domain/ledger'
import type { ExpenseBody } from '../domain/format'
import type { PotSession } from '../potSession'
import type { PotWrite } from '../domain/potApi'
import type { ReceiptSource } from '../receipts'
import { ANNA, BEN, CLEO, EUR, PotServer, expense, person } from '../potTestUtil'

const SAVED = '0e000000-0000-4000-9000-0000000000aa'

let write: ReturnType<typeof vi.fn<(writes: PotWrite[]) => Promise<void>>>
let wrapper: VueWrapper
/** The editor over a Pot of Anna, Ben and Cleo: a new entry, or [saved] opened. */
async function open(saved?: ExpenseBody) {
  wrapper?.unmount()
  document.body.innerHTML = ''
  const server = new PotServer(v1.randomBytes(32))
  server.append([{ entryId: ANNA, content: person('Anna', 1) }, { entryId: BEN, content: person('Ben', 2) }, { entryId: CLEO, content: person('Cleo', 3) }])
  if (saved) server.append([{ entryId: SAVED, content: saved }])
  write = vi.fn(async () => {})
  const session = {
    held: ref(server.held()), receipts: ref([]), receiptsFailed: ref(false), rateService: computed(() => null), write,
    addReceipt: vi.fn(), writerName: () => null,
  } as unknown as PotSession
  wrapper = mount(EntryEditor, {
    props: {
      session, ledger: buildLedger(server.held(), EUR), meta: { base: EUR, categories: [] }, entryId: saved ? SAVED : null, canWrite: true,
      writers: false, people: null, receiptSource: () => ({}) as unknown as ReceiptSource,
    },
    global: { plugins: [realI18n()] },
    attachTo: document.body,
  })
  await flushPromises()
}

const dialog = () => document.body.querySelector('.expenses-entry-dialog') as HTMLElement
const byLabel = (label: string, scope: ParentNode = dialog()) => scope.querySelector(`input[aria-label="${label}"]`) as HTMLInputElement | null
const button = (text: string, scope: ParentNode = dialog()) => [...scope.querySelectorAll('button')].find((b) => b.textContent?.trim() === text) ?? null
const buttonNamed = (name: string, scope: ParentNode = dialog()) => scope.querySelector(`button[aria-label="${name}"]`) as HTMLElement | null
const items = () => [...dialog().querySelectorAll('.expenses-item:not(.expenses-rest)')] as HTMLElement[]
const rest = () => dialog().querySelector('.expenses-rest') as HTMLElement | null
const payers = () => dialog().querySelector('.expenses-entry-fieldset') as HTMLElement
/** The red lines in [scope]: inline field errors. */
const redIn = (scope: ParentNode) => [...scope.querySelectorAll('.veyq-form-error')].map((p) => p.textContent!.trim())
const bottomMessage = () => dialog().querySelector('.veyq-message--error')
async function type(el: HTMLInputElement, value: string) {
  el.value = value
  el.dispatchEvent(new Event('input'))
  await flushPromises()
}
async function click(el: HTMLElement) {
  el.click()
  await flushPromises()
}
async function mode(scope: HTMLElement, label: string) {
  await click([...scope.querySelectorAll('.veyq-toggle-group__item')].find((b) => b.textContent?.trim() === label) as HTMLElement)
}
/** The people of a split's rows, as their selects hold them. */
function rowSelects(scope: HTMLElement): VueWrapper[] {
  return (wrapper.findAllComponents(VeyqSelect) as unknown as VueWrapper[]).filter((c) => {
    const field = c.find('.veyq-select') // the select's field, where its aria-label lands
    return field.exists() && scope.contains(field.element) && field.attributes('aria-label') === t('expenses.split.person')
  })
}
const rowPeople = (scope: HTMLElement) => rowSelects(scope).map((c) => (c.props() as { modelValue: string }).modelValue)
const optionsOf = (c: VueWrapper) => (c.props() as { options: { label: string }[] }).options.map((o) => o.label)
const written = () => (write.mock.calls[0]![0] as PotWrite[])[0]!.content as { amount: number; payers: unknown; allocation: unknown }

describe('a new entry\'s split: one line until adjusted', () => {
  it('starts as "everyone, equally"; the amount is the payers\' sum, the whole of it the rest, saved equal among everyone', async () => {
    await open()
    expect(rest()!.textContent).toContain(t('expenses.split.everyone', { count: 3 }, 3))
    expect(rest()!.querySelector('.veyq-toggle-group')).toBeNull() // no modes, no rows until "Adjust"
    expect(rowSelects(rest()!)).toEqual([])
    await type(dialog().querySelector('input') as HTMLInputElement, 'Groceries')
    await type(byLabel(t('expenses.entry.payerAmount', { name: 'Anna' }))!, '20')
    await click(button(t('expenses.entry.addPayer'))!)
    await type(byLabel(t('expenses.entry.payerAmount', { name: 'Ben' }))!, '10,01')
    expect(dialog().querySelector('.expenses-entry-total output')!.textContent).toBe('€30.01')
    expect(rest()!.textContent).toContain(t('expenses.entry.wholeLabel'))
    expect(rest()!.querySelector('output')!.textContent).toBe('€30.01')
    await click(button(t('common.save'))!)
    expect(write).toHaveBeenCalledTimes(1)
    expect(written()).toMatchObject({
      amount: 3001,
      payers: [{ participant: ANNA, amount: 2000 }, { participant: BEN, amount: 1001 }],
      allocation: { items: [], rest: { mode: 'equal', participants: [ANNA, BEN, CLEO] } },
    })
  })

  it('adjusted: by shares, a row per person with a person picked; taken off and added again; 1 share each is still saved `equal`', async () => {
    await open()
    await type(dialog().querySelector('input') as HTMLInputElement, 'Taxi')
    await type(byLabel(t('expenses.entry.payerAmount', { name: 'Anna' }))!, '9')
    await click(button(t('expenses.split.adjust'), rest()!)!)
    expect([...rest()!.querySelectorAll('.veyq-toggle-group__item')].map((b) => b.textContent!.trim())).toEqual([
      t('expenses.split.shares'), t('expenses.split.percent'), t('expenses.split.amounts'),
    ])
    expect(rowPeople(rest()!)).toEqual([ANNA, BEN, CLEO])
    expect(button(t('expenses.split.addPerson'), rest()!)).toBeNull() // everyone is on it
    expect(optionsOf(rowSelects(rest()!)[1]!)).toEqual(['Ben']) // a row offers no one another row has picked

    await click(buttonNamed(t('expenses.split.removePerson', { name: 'Cleo' }), rest()!)!)
    expect(rowPeople(rest()!)).toEqual([ANNA, BEN])
    expect(optionsOf(rowSelects(rest()!)[1]!)).toEqual(['Ben', 'Cleo'])
    rowSelects(rest()!)[1]!.vm.$emit('update:modelValue', CLEO) // Ben's row now Cleo's
    await flushPromises()
    expect(rowPeople(rest()!)).toEqual([ANNA, CLEO])
    await click(button(t('expenses.split.addPerson'), rest()!)!)
    expect(rowPeople(rest()!)).toEqual([ANNA, CLEO, BEN])
    await click(button(t('common.save'))!)
    expect(written().allocation).toEqual({ items: [], rest: { mode: 'equal', participants: [ANNA, CLEO, BEN] } })
  })

  it('shares other than 1 are saved as shares; "split equally among all" puts everyone back at 1 share', async () => {
    await open()
    await type(dialog().querySelector('input') as HTMLInputElement, 'Hut')
    await type(byLabel(t('expenses.entry.payerAmount', { name: 'Anna' }))!, '9')
    await click(button(t('expenses.split.adjust'), rest()!)!)
    await click(buttonNamed(t('expenses.split.removePerson', { name: 'Ben' }), rest()!)!)
    await type(byLabel(t('expenses.entry.sharesOf', { name: 'Anna' }), rest()!)!, '2')
    await click(button(t('common.save'))!)
    expect(written().allocation).toEqual({ items: [], rest: { mode: 'shares', shares: [{ participant: ANNA, shares: 2 }, { participant: CLEO, shares: 1 }] } })

    await click(button(t('expenses.split.everyoneEqually'), rest()!)!)
    expect(rowPeople(rest()!)).toEqual([ANNA, BEN, CLEO])
    expect(byLabel(t('expenses.entry.sharesOf', { name: 'Anna' }), rest()!)!.value).toBe('1')
    await click(button(t('common.save'))!)
    expect((write.mock.calls[1]![0] as PotWrite[])[0]!.content).toMatchObject({ allocation: { items: [], rest: { mode: 'equal', participants: [ANNA, BEN, CLEO] } } })
  })
})

describe('splits by percent and by amount, and items', () => {
  it('an item by percent and the rest by amount, each saying live in red what it adds up to; the rest leaves once the items fill the amount', async () => {
    await open()
    await type(dialog().querySelector('input') as HTMLInputElement, 'Bar')
    await type(byLabel(t('expenses.entry.payerAmount', { name: 'Anna' }))!, '30')
    await click(button(t('expenses.entry.addItem'))!)
    const item = items()[0]!
    expect(item.textContent).toContain(t('expenses.split.everyone', { count: 3 }, 3)) // a new item: one line too
    await type(item.querySelector(`input[aria-label="${t('expenses.entry.itemLabel')}"]`) as HTMLInputElement, 'Wine')
    await type(item.querySelector(`input[aria-label="${t('expenses.entry.itemAmount')}"]`) as HTMLInputElement, '20')
    await click(button(t('expenses.split.adjust'), item)!)
    await mode(item, t('expenses.split.percent'))
    expect(byLabel(t('expenses.entry.percentOf', { name: 'Anna' }), item)).not.toBeNull() // the click switched the mode
    expect(redIn(item)).toEqual([t('expenses.entry.percentTotal', { total: '0.00' })])
    await click(buttonNamed(t('expenses.split.removePerson', { name: 'Cleo' }), item)!)
    await type(byLabel(t('expenses.entry.percentOf', { name: 'Anna' }), item)!, '25')
    expect(redIn(item)).toEqual([t('expenses.entry.percentTotal', { total: '25.00' })])
    await type(byLabel(t('expenses.entry.percentOf', { name: 'Ben' }), item)!, '75')
    expect(redIn(item)).toEqual([])

    expect(rest()!.textContent).toContain(t('expenses.entry.restLabel'))
    expect(rest()!.querySelector('output')!.textContent).toBe('€10.00')
    await click(button(t('expenses.split.adjust'), rest()!)!)
    await mode(rest()!, t('expenses.split.amounts'))
    expect(redIn(rest()!)).toEqual([t('expenses.entry.amountsTotal', { total: '€0.00', amount: '€10.00' })])
    await type(byLabel(t('expenses.entry.amountOf', { name: 'Anna' }), rest()!)!, '2,50')
    await type(byLabel(t('expenses.entry.amountOf', { name: 'Ben' }), rest()!)!, '7.50')
    expect(redIn(rest()!)).toEqual([]) // they make the rest; Cleo, still on it with no amount, is the save's to find
    await click(button(t('common.save'))!)
    expect(write).not.toHaveBeenCalled() // Cleo is on it with no amount
    expect(redIn(rest()!)).toEqual([t('expenses.entry.problem.splitAmounts')])
    await click(buttonNamed(t('expenses.split.removePerson', { name: 'Cleo' }), rest()!)!)
    await click(button(t('common.save'))!)
    expect(written()).toMatchObject({
      amount: 3000,
      allocation: {
        items: [{ label: 'Wine', amount: 2000, split: { mode: 'percent', percents: [{ participant: ANNA, percent: 2500 }, { participant: BEN, percent: 7500 }] } }],
        rest: { mode: 'amounts', amounts: [{ participant: ANNA, amount: 250 }, { participant: BEN, amount: 750 }] },
      },
    })

    await type(item.querySelector(`input[aria-label="${t('expenses.entry.itemAmount')}"]`) as HTMLInputElement, '30')
    expect(rest()).toBeNull()
  })

  it('amounts beside the part say by how much live, and the save adds no second line for it (control: matched, it saves)', async () => {
    await open()
    await type(dialog().querySelector('input') as HTMLInputElement, 'Snacks')
    await type(byLabel(t('expenses.entry.payerAmount', { name: 'Anna' }))!, '10')
    await click(button(t('expenses.split.adjust'), rest()!)!)
    await mode(rest()!, t('expenses.split.amounts'))
    await click(buttonNamed(t('expenses.split.removePerson', { name: 'Cleo' }), rest()!)!)
    await type(byLabel(t('expenses.entry.amountOf', { name: 'Anna' }), rest()!)!, '4')
    await type(byLabel(t('expenses.entry.amountOf', { name: 'Ben' }), rest()!)!, '5')
    const live = t('expenses.entry.amountsTotal', { total: '€9.00', amount: '€10.00' })
    expect(redIn(rest()!)).toEqual([live])
    await click(button(t('common.save'))!)
    expect(write).not.toHaveBeenCalled()
    expect(redIn(rest()!)).toEqual([live])
    expect(bottomMessage()).toBeNull()
    await type(byLabel(t('expenses.entry.amountOf', { name: 'Ben' }), rest()!)!, '6')
    expect(redIn(rest()!)).toEqual([])
    await click(button(t('common.save'))!)
    expect(written().allocation).toEqual({ items: [], rest: { mode: 'amounts', amounts: [{ participant: ANNA, amount: 400 }, { participant: BEN, amount: 600 }] } })
  })

  it('items above the amount say by how much in red, and nothing is saved (control: at the amount it saves)', async () => {
    await open()
    await type(dialog().querySelector('input') as HTMLInputElement, 'Taxi')
    await type(byLabel(t('expenses.entry.payerAmount', { name: 'Anna' }))!, '10')
    await click(button(t('expenses.entry.addItem'))!)
    const item = items()[0]!
    await type(item.querySelector(`input[aria-label="${t('expenses.entry.itemLabel')}"]`) as HTMLInputElement, 'Ride')
    await type(item.querySelector(`input[aria-label="${t('expenses.entry.itemAmount')}"]`) as HTMLInputElement, '12,50')
    const over = t('expenses.entry.itemsOver', { amount: '€2.50' })
    expect(redIn(dialog())).toEqual([over])
    expect(rest()).toBeNull()
    await click(button(t('common.save'))!)
    expect(write).not.toHaveBeenCalled()
    expect(redIn(dialog())).toEqual([over]) // said once, where it is
    expect(bottomMessage()).toBeNull()

    await type(item.querySelector(`input[aria-label="${t('expenses.entry.itemAmount')}"]`) as HTMLInputElement, '10')
    expect(redIn(dialog())).toEqual([])
    await click(button(t('common.save'))!)
    expect(write).toHaveBeenCalledTimes(1)
    expect(written().allocation).toEqual({ items: [{ label: 'Ride', amount: 1000, split: { mode: 'equal', participants: [ANNA, BEN, CLEO] } }] })
  })
})

describe('what the save finds wrong is said at its field', () => {
  it('"Paid by" with no amount: red under the payers, not at the bottom (control: typed, it saves)', async () => {
    await open()
    await type(dialog().querySelector('input') as HTMLInputElement, 'Dinner')
    await click(button(t('common.save'))!)
    expect(write).not.toHaveBeenCalled()
    expect(redIn(payers())).toEqual([t('expenses.entry.problem.payers')])
    expect(bottomMessage()).toBeNull()
    await type(byLabel(t('expenses.entry.payerAmount', { name: 'Anna' }))!, '12')
    await click(button(t('common.save'))!)
    expect(write).toHaveBeenCalledTimes(1)
    expect(redIn(dialog())).toEqual([])
  })

  it('no title: red under the title; a bad value in an item\'s split: red under that item', async () => {
    await open()
    await type(byLabel(t('expenses.entry.payerAmount', { name: 'Anna' }))!, '12')
    await click(button(t('common.save'))!)
    const title = dialog().querySelector('input') as HTMLInputElement
    expect(title.getAttribute('aria-invalid')).toBe('true')
    expect((title.closest('label')!.nextElementSibling as HTMLElement).textContent!.trim()).toBe(t('expenses.entry.problem.title'))
    expect(bottomMessage()).toBeNull()

    await type(title, 'Dinner')
    await click(button(t('expenses.entry.addItem'))!)
    const item = items()[0]!
    await type(item.querySelector(`input[aria-label="${t('expenses.entry.itemLabel')}"]`) as HTMLInputElement, 'Wine')
    await type(item.querySelector(`input[aria-label="${t('expenses.entry.itemAmount')}"]`) as HTMLInputElement, '6')
    await click(button(t('expenses.split.adjust'), item)!)
    await type(byLabel(t('expenses.entry.sharesOf', { name: 'Ben' }), item)!, '1,5')
    await click(button(t('common.save'))!)
    expect(write).not.toHaveBeenCalled()
    expect(redIn(item)).toEqual([t('expenses.entry.problem.splitShares')])
    expect(redIn(dialog())).toEqual([t('expenses.entry.problem.splitShares')]) // the title's line went with the save that found this
  })
})

describe('an entry opened again', () => {
  it('equal over everyone offered: its one line again', async () => {
    await open(expense('Dinner', 900, ANNA, [ANNA, BEN, CLEO]))
    expect(rest()!.textContent).toContain(t('expenses.split.everyone', { count: 3 }, 3))
    expect(rowSelects(rest()!)).toEqual([])
  })

  it('equal over some, or by shares: its modes and rows', async () => {
    await open(expense('Dinner', 900, ANNA, [ANNA, CLEO]))
    expect(rest()!.querySelector('.veyq-toggle-group__item[data-state="on"]')!.textContent!.trim()).toBe(t('expenses.split.shares'))
    expect(rowPeople(rest()!)).toEqual([ANNA, CLEO])

    await open({ ...expense('Dinner', 900, ANNA, []), allocation: { items: [], rest: { mode: 'amounts', amounts: [{ participant: BEN, amount: 300 }, { participant: CLEO, amount: 600 }] } } })
    expect(rest()!.querySelector('.veyq-toggle-group__item[data-state="on"]')!.textContent!.trim()).toBe(t('expenses.split.amounts'))
    expect(rowPeople(rest()!)).toEqual([BEN, CLEO])
    expect(byLabel(t('expenses.entry.amountOf', { name: 'Cleo' }), rest()!)!.value).toBe('6.00')
  })
})
