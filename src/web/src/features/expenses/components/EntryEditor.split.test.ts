// @vitest-environment happy-dom
// The editor's payers and items (uc.expenses.record-entries), through the real component and real DOM events: the amount is
// the payers' sum, shown and never typed; a new entry has no items, so its whole amount is the rest, split equally among
// everyone; an added item takes its exact amount and its own split (equally, by shares, by percent — the Reka toggle answers
// a click); the rest item shows the amount left with a split of its own and leaves once the items fill the amount; items
// above the amount say by how much and nothing is saved.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { computed, ref } from 'vue'
import * as v1 from '@veyq/crypto/v1'
import EntryEditor from './EntryEditor.vue'
import { realI18n, t } from '../../../test/i18n'
import { buildLedger } from '../domain/ledger'
import type { PotSession } from '../potSession'
import type { PotWrite } from '../domain/potApi'
import type { ReceiptSource } from '../receipts'
import { ANNA, BEN, EUR, PotServer, person } from '../potTestUtil'

let server: PotServer
let write: ReturnType<typeof vi.fn<(writes: PotWrite[]) => Promise<void>>>
beforeEach(async () => {
  document.body.innerHTML = ''
  server = new PotServer(v1.randomBytes(32))
  server.append([{ entryId: ANNA, content: person('Anna', 1) }, { entryId: BEN, content: person('Ben', 2) }])
  write = vi.fn(async () => {})
  const session = {
    held: ref(server.held()), receipts: ref([]), receiptsFailed: ref(false), rateService: computed(() => null), write,
    addReceipt: vi.fn(), writerName: () => null,
  } as unknown as PotSession
  mount(EntryEditor, {
    props: {
      session, ledger: buildLedger(server.held(), EUR), meta: { base: EUR, categories: [] }, entryId: null, canWrite: true,
      writers: false, people: null, receiptSource: () => ({}) as unknown as ReceiptSource,
    },
    global: { plugins: [realI18n()] },
    attachTo: document.body,
  })
  await flushPromises()
})

const dialog = () => document.body.querySelector('.expenses-entry-dialog') as HTMLElement
const byLabel = (label: string) => dialog().querySelector(`input[aria-label="${label}"]`) as HTMLInputElement | null
const button = (text: string) => [...dialog().querySelectorAll('button')].find((b) => b.textContent?.trim() === text) ?? null
const items = () => [...dialog().querySelectorAll('.expenses-item:not(.expenses-rest)')] as HTMLElement[]
const rest = () => dialog().querySelector('.expenses-rest') as HTMLElement | null
async function type(el: HTMLInputElement, value: string) {
  el.value = value
  el.dispatchEvent(new Event('input'))
  await flushPromises()
}
async function click(el: HTMLElement) {
  el.click()
  await flushPromises()
}
const written = () => (write.mock.calls[0]![0] as PotWrite[])[0]!.content as { amount: number; payers: unknown; allocation: unknown }

describe('the payers and the items in the entry editor', () => {
  it('a new entry: the amount is the payers\' sum, the whole of it rest, split equally among everyone', async () => {
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
      allocation: { items: [], rest: { mode: 'equal', participants: [ANNA, BEN] } },
    })
  })

  it('an item by percent and the rest by shares; the rest leaves once the items fill the amount', async () => {
    await type(dialog().querySelector('input') as HTMLInputElement, 'Bar')
    await type(byLabel(t('expenses.entry.payerAmount', { name: 'Anna' }))!, '30')
    await click(button(t('expenses.entry.addItem'))!)
    const item = items()[0]!
    await type(item.querySelector(`input[aria-label="${t('expenses.entry.itemLabel')}"]`) as HTMLInputElement, 'Wine')
    await type(item.querySelector(`input[aria-label="${t('expenses.entry.itemAmount')}"]`) as HTMLInputElement, '20')
    const percentMode = [...item.querySelectorAll('.veyq-toggle-group__item')].find((b) => b.textContent?.trim() === t('expenses.split.percent')) as HTMLElement
    await click(percentMode)
    expect(byLabel(t('expenses.entry.percentOf', { name: 'Anna' }))).not.toBeNull() // the click switched the mode
    await type(byLabel(t('expenses.entry.percentOf', { name: 'Anna' }))!, '25')
    expect(item.textContent).toContain(t('expenses.entry.percentTotal', { total: '25.00' }))
    await type(byLabel(t('expenses.entry.percentOf', { name: 'Ben' }))!, '75')
    expect(item.textContent).not.toContain(t('expenses.entry.percentTotal', { total: '100.00' }))

    expect(rest()!.textContent).toContain(t('expenses.entry.restLabel'))
    expect(rest()!.querySelector('output')!.textContent).toBe('€10.00')
    const sharesMode = [...rest()!.querySelectorAll('.veyq-toggle-group__item')].find((b) => b.textContent?.trim() === t('expenses.split.shares')) as HTMLElement
    await click(sharesMode)
    await type(rest()!.querySelector(`input[aria-label="${t('expenses.entry.sharesOf', { name: 'Ben' })}"]`) as HTMLInputElement, '3')
    await click(button(t('common.save'))!)
    expect(written()).toMatchObject({
      amount: 3000,
      allocation: {
        items: [{ label: 'Wine', amount: 2000, split: { mode: 'percent', percents: [{ participant: ANNA, percent: 2500 }, { participant: BEN, percent: 7500 }] } }],
        rest: { mode: 'shares', shares: [{ participant: BEN, shares: 3 }] },
      },
    })

    await type(item.querySelector(`input[aria-label="${t('expenses.entry.itemAmount')}"]`) as HTMLInputElement, '30')
    expect(rest()).toBeNull()
  })

  it('items above the amount say by how much, and nothing is saved (control: at the amount it saves)', async () => {
    await type(dialog().querySelector('input') as HTMLInputElement, 'Taxi')
    await type(byLabel(t('expenses.entry.payerAmount', { name: 'Anna' }))!, '10')
    await click(button(t('expenses.entry.addItem'))!)
    const item = items()[0]!
    await type(item.querySelector(`input[aria-label="${t('expenses.entry.itemLabel')}"]`) as HTMLInputElement, 'Ride')
    await type(item.querySelector(`input[aria-label="${t('expenses.entry.itemAmount')}"]`) as HTMLInputElement, '12,50')
    expect(dialog().textContent).toContain(t('expenses.entry.itemsOver', { amount: '€2.50' }))
    expect(rest()).toBeNull()
    await click(button(t('common.save'))!)
    expect(write).not.toHaveBeenCalled()
    expect(dialog().textContent).toContain(t('expenses.entry.problem.itemsAboveTotal'))

    await type(item.querySelector(`input[aria-label="${t('expenses.entry.itemAmount')}"]`) as HTMLInputElement, '10')
    expect(dialog().textContent).not.toContain(t('expenses.entry.itemsOver', { amount: '€0.00' }))
    await click(button(t('common.save'))!)
    expect(write).toHaveBeenCalledTimes(1)
    expect(written().allocation).toEqual({ items: [{ label: 'Ride', amount: 1000, split: { mode: 'equal', participants: [ANNA, BEN] } }] })
  })
})
