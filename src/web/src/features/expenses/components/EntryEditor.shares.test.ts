// @vitest-environment happy-dom
// The editor's shares in the base currency (uc.expenses.record-entries main flow 4), through the real component and real
// DOM events: in a foreign currency with a typed rate, each person's share — and what each payer gave — is shown in the
// base currency exactly as the ledger computes it from the saved revision (`buildLedger` over the content the save
// sealed, and literal minor amounts for this case, a remainder at every step); while the entry does not add up, no share
// is shown (control: fixed, it is).
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { computed, ref } from 'vue'
import * as v1 from '@veyq/crypto/v1'
import EntryEditor from './EntryEditor.vue'
import VeyqSelect from '../../../shared/ui/VeyqSelect.vue'
import { realI18n, t } from '../../../test/i18n'
import { buildLedger } from '../domain/ledger'
import { formatMoney } from '../amounts'
import type { PotSession } from '../potSession'
import type { PotWrite } from '../domain/potApi'
import type { ReceiptSource } from '../receipts'
import { ANNA, BEN, CLEO, EUR, PotServer, person } from '../potTestUtil'

const SAVED = '0e000000-0000-4000-9000-0000000000aa'

let server: PotServer
let write: ReturnType<typeof vi.fn<(writes: PotWrite[]) => Promise<void>>>
let wrapper: VueWrapper
beforeEach(async () => {
  document.body.innerHTML = ''
  server = new PotServer(v1.randomBytes(32))
  server.append([{ entryId: ANNA, content: person('Anna', 1) }, { entryId: BEN, content: person('Ben', 2) }, { entryId: CLEO, content: person('Cleo', 3) }])
  write = vi.fn(async () => {})
  const session = {
    held: ref(server.held()), receipts: ref([]), receiptsFailed: ref(false), rateService: computed(() => null), write,
    addReceipt: vi.fn(), writerName: () => null,
  } as unknown as PotSession
  wrapper = mount(EntryEditor, {
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
const table = () => dialog().querySelector('.expenses-entry-shares') as HTMLTableElement | null
/** The shares table as text: a row per person (name, then its amounts), and the footer row last where there is one. */
const cells = (section: 'thead' | 'tbody' | 'tfoot') =>
  [...table()!.querySelectorAll(`${section} tr`)].map((tr) => [...tr.children].map((c) => c.textContent!.trim()))
async function type(el: HTMLInputElement, value: string) {
  el.value = value
  el.dispatchEvent(new Event('input'))
  await flushPromises()
}
async function click(el: HTMLElement) {
  el.click()
  await flushPromises()
}
async function pickCurrency(currency: string) {
  const select = (wrapper.findAllComponents(VeyqSelect) as unknown as VueWrapper[]).find((c) => (c.props() as { options: { value: string }[] }).options.some((o) => o.value === 'USD'))!
  select.vm.$emit('update:modelValue', currency)
  await flushPromises()
}
const rateField = () => [...dialog().querySelectorAll('label')].find((l) => l.textContent?.startsWith('Rate: 1 USD in EUR'))!.querySelector('input')!
const eur = (minor: bigint) => formatMoney(minor, 'EUR', 2, 'en')

/** Anna paid 10.00 and Ben 5.00 (USD); a 4.00 item Anna and Ben share 1 : 2, the 11.00 rest split equally among all three. */
async function typeDinner() {
  await type(byLabel(t('expenses.entry.payerAmount', { name: 'Anna' }))!, '10')
  await click(button(t('expenses.entry.addPayer'))!)
  await type(byLabel(t('expenses.entry.payerAmount', { name: 'Ben' }))!, '5')
  await click(button(t('expenses.entry.addItem'))!)
  const item = dialog().querySelector('.expenses-item:not(.expenses-rest)') as HTMLElement
  await type(item.querySelector(`input[aria-label="${t('expenses.entry.itemLabel')}"]`) as HTMLInputElement, 'Wine')
  await type(item.querySelector(`input[aria-label="${t('expenses.entry.itemAmount')}"]`) as HTMLInputElement, '4')
  // the item starts as its one line, everyone equally: adjusted, it is by shares, everyone at 1
  await click([...item.querySelectorAll('button')].find((b) => b.textContent?.trim() === t('expenses.split.adjust'))!)
  await click(item.querySelector(`button[aria-label="${t('expenses.split.removePerson', { name: 'Cleo' })}"]`) as HTMLElement)
  await type(item.querySelector(`input[aria-label="${t('expenses.entry.sharesOf', { name: 'Ben' })}"]`) as HTMLInputElement, '2')
}

describe('the shares in the base currency in the entry editor', () => {
  it('a foreign-currency entry shows each person\'s share in the base currency exactly as the ledger computes the saved entry', async () => {
    await pickCurrency('USD')
    await typeDinner()
    expect(table()).toBeNull() // no rate yet: the entry does not form
    await type(rateField(), '0.8533')

    // 15.00 USD at 0.8533 is 12.7995 EUR, rounded once to €12.80. The parts: the item 1280·400/1500 = 341.33 and the rest
    // 938.67 → 341 and 939 (the rest's remainder is larger). The item 1 : 2 → 113.67 and 227.33 → Anna 114, Ben 227; the
    // rest 939 / 3 = 313 each. The payers 1280·1000/1500 = 853.33 and 426.67 → Anna 853, Ben 427.
    expect(table()!.querySelector('caption')!.textContent).toBe(t('expenses.entry.sharesCaption', { currency: 'EUR' }))
    expect(cells('thead')).toEqual([[t('expenses.entry.personLabel'), t('expenses.entry.paidColumn'), t('expenses.entry.shareColumn')]])
    expect(cells('tbody')).toEqual([
      ['Anna', '€8.53', '€4.27'],
      ['Ben', '€4.27', '€5.40'],
      ['Cleo', '€0.00', '€3.13'],
    ])
    expect(cells('tfoot')).toEqual([[t('expenses.entry.amountLabel'), '€12.80', '€12.80']])

    // the title moves no money: the shares stood before it was typed, and do not move with it
    await type(dialog().querySelector('input') as HTMLInputElement, 'Dinner')
    const shown = cells('tbody')
    await click(button(t('common.save'))!)
    expect(write).toHaveBeenCalledTimes(1)
    const content = (write.mock.calls[0]![0] as PotWrite[])[0]!.content
    expect(content).toMatchObject({ currency: 'USD', rate: '0.8533', amount: 1500 })

    // the saved revision through the ledger every balance is computed by
    server.append([{ entryId: SAVED, content }])
    const effect = buildLedger(server.held(), EUR).entries.find((e) => e.id === SAVED)!.effect!
    expect(effect.base).toBe(1280n)
    expect([...effect.shares]).toEqual([[ANNA, 427n], [BEN, 540n], [CLEO, 313n]])
    expect([...effect.paid]).toEqual([[ANNA, 853n], [BEN, 427n]])
    const byName: Record<string, string> = { [ANNA]: 'Anna', [BEN]: 'Ben', [CLEO]: 'Cleo' }
    expect(shown).toEqual([...effect.shares].map(([id, share]) => [byName[id], eur(effect.paid.get(id) ?? 0n), eur(share)]))
  })

  it('an entry that does not add up shows no shares (control: corrected, they show — in the base currency, no paid column)', async () => {
    await type(byLabel(t('expenses.entry.payerAmount', { name: 'Anna' }))!, '10')
    await click(button(t('expenses.entry.addItem'))!)
    const item = dialog().querySelector('.expenses-item:not(.expenses-rest)') as HTMLElement
    await type(item.querySelector(`input[aria-label="${t('expenses.entry.itemLabel')}"]`) as HTMLInputElement, 'Ride')
    await type(item.querySelector(`input[aria-label="${t('expenses.entry.itemAmount')}"]`) as HTMLInputElement, '12,50')
    expect(dialog().textContent).toContain(t('expenses.entry.itemsOver', { amount: '€2.50' }))
    expect(table()).toBeNull()

    await type(item.querySelector(`input[aria-label="${t('expenses.entry.itemAmount')}"]`) as HTMLInputElement, '10')
    expect(table()).not.toBeNull()
    expect(cells('thead')).toEqual([[t('expenses.entry.personLabel'), t('expenses.entry.shareColumn')]])
    expect(cells('tbody')).toEqual([['Anna', '€3.34'], ['Ben', '€3.33'], ['Cleo', '€3.33']])
    expect(table()!.querySelector('tfoot')).toBeNull()
  })
})
