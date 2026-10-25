// @vitest-environment happy-dom
// The editor's day rate (uc.expenses.exchange-rates, ADR 0108 decision 8): picking a currency other than the Pot's base
// fills the rate from the day's table and keeps it typeable — exactly while the Pot's session names a rate table, and
// none is asked otherwise (the switch off at either level, or no address: the session's `rateService` is null; control:
// one request with it on). A save never waits for an unreachable provider, a rate typed before the table arrives is never
// written over, and an entry opened with its saved rate is never refilled.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { computed, ref } from 'vue'
import * as v1 from '@veyq/crypto/v1'
import EntryEditor from './EntryEditor.vue'
import VeyqSelect from '../../../shared/ui/VeyqSelect.vue'
import { realI18n, t } from '../../../test/i18n'
import { buildLedger } from '../domain/ledger'
import { setRateSourceForTest } from '../exchangeRates'
import type { PotSession } from '../potSession'
import type { ReceiptSource } from '../receipts'
import { ANNA, BEN, EUR, PotServer, expense, person } from '../potTestUtil'

const TEMPLATE = 'https://api.frankfurter.dev/v2/providers/ecb/rates?date={date}'
const ANSWER = [
  { date: '2026-10-23', base: 'EUR', quote: 'EUR', rate: 1.0 },
  { date: '2026-10-23', base: 'EUR', quote: 'USD', rate: 1.1725 },
]
const DINNER = '0e000000-0000-4000-9000-000000000001'

let fetchSpy: ReturnType<typeof vi.fn<(url: string, init: RequestInit) => Promise<Response>>>
let server: PotServer
beforeEach(() => {
  document.body.innerHTML = ''
  fetchSpy = vi.fn(() => Promise.resolve(new Response(JSON.stringify(ANSWER), { status: 200 })))
  setRateSourceForTest({ fetch: fetchSpy, storage: () => null })
  server = new PotServer(v1.randomBytes(32))
  server.append([{ entryId: ANNA, content: person('Anna', 1) }, { entryId: BEN, content: person('Ben', 2) }])
})
afterEach(() => setRateSourceForTest(null))

function session(rateService: string | null, write = vi.fn(async () => {})): PotSession {
  return {
    held: ref(server.held()), receipts: ref([]), receiptsFailed: ref(false), rateService: computed(() => rateService), write,
    addReceipt: vi.fn(), writerName: () => null,
  } as unknown as PotSession
}

async function open(s: PotSession, entryId: string | null = null): Promise<VueWrapper> {
  const w = mount(EntryEditor, {
    props: {
      session: s, ledger: buildLedger(server.held(), EUR), meta: { base: EUR, categories: [] }, entryId, canWrite: true,
      writers: false, people: null, receiptSource: () => ({}) as unknown as ReceiptSource,
    },
    global: { plugins: [realI18n()] },
    attachTo: document.body,
  })
  await flushPromises()
  return w
}

const dialog = () => document.body.querySelector('.expenses-entry-dialog') as HTMLElement
const inputs = () => [...dialog().querySelectorAll('input')] as HTMLInputElement[]
const rateField = () => [...dialog().querySelectorAll('label')].find((l) => l.textContent?.startsWith('Rate: 1 USD in EUR'))?.querySelector('input') ?? null
async function pick(w: VueWrapper, currency: string) {
  // the currency select is the second select of the form (after the kind's toggle group): the one offering USD
  const select = (w.findAllComponents(VeyqSelect) as unknown as VueWrapper[]).find((c) => (c.props() as { options: { value: string }[] }).options.some((o) => o.value === 'USD'))!
  select.vm.$emit('update:modelValue', currency)
  await flushPromises()
}
async function type(el: HTMLInputElement, value: string) {
  el.value = value
  el.dispatchEvent(new Event('input'))
  await flushPromises()
}
const save = () => [...dialog().querySelectorAll('button')].find((b) => b.textContent?.trim() === t('common.save'))!

describe('the day rate in the editor', () => {
  it('fills a foreign currency\'s rate from the day\'s table, names the ECB and the rows\' date, and stays typeable', async () => {
    const w = await open(session(TEMPLATE))
    await pick(w, 'USD')
    expect(fetchSpy).toHaveBeenCalledTimes(1)
    expect(rateField()!.value).toBe('0.852878464819')
    expect(rateField()!.disabled).toBe(false)
    expect(dialog().textContent).toContain(t('expenses.entry.rateFilled', { date: 'Oct 23, 2026' }))
    await type(rateField()!, '0.85')
    expect(dialog().textContent).not.toContain('ECB') // the note speaks of the filled rate only
    // back to the base and again: the device's table, no second request
    await pick(w, 'EUR')
    await pick(w, 'USD')
    expect(fetchSpy).toHaveBeenCalledTimes(1)
    expect(rateField()!.value).toBe('0.852878464819')
  })

  it('asks nothing while the session names no rate table — off at either level, or no address (control above: one)', async () => {
    const w = await open(session(null))
    await pick(w, 'USD')
    expect(fetchSpy).not.toHaveBeenCalled()
    expect(rateField()!.value).toBe('')
    expect(dialog().textContent).not.toContain(t('expenses.entry.rateLoading'))
  })

  it('says so for a currency the table lacks, and leaves its rate to be typed', async () => {
    const w = await open(session(TEMPLATE))
    await pick(w, 'NOK')
    expect(fetchSpy).toHaveBeenCalledTimes(1)
    const field = [...dialog().querySelectorAll('label')].find((l) => l.textContent?.startsWith('Rate: 1 NOK in EUR'))!.querySelector('input')!
    expect(field.value).toBe('')
    expect(dialog().textContent).toContain(t('expenses.entry.rateMissing', { currency: 'NOK' }))
  })

  it('a save never waits for an unreachable provider, and a rate typed meanwhile is never written over', async () => {
    let answer: (r: Response) => void = () => {}
    fetchSpy.mockImplementation(() => new Promise((resolve) => (answer = resolve)))
    const write = vi.fn(async () => {})
    const w = await open(session(TEMPLATE, write))
    await pick(w, 'USD')
    expect(dialog().textContent).toContain(t('expenses.entry.rateLoading'))
    const [title, amount] = inputs()
    await type(title!, 'Taxi')
    await type(amount!, '20')
    await type(rateField()!, '0.9')
    save().click()
    await flushPromises()
    expect(write).toHaveBeenCalledTimes(1)
    const written = (write.mock.calls[0] as unknown as [{ content: { rate: string; currency: string } }[]])[0][0]!
    expect(written.content).toMatchObject({ currency: 'USD', rate: '0.9' })
    answer(new Response(JSON.stringify(ANSWER), { status: 200 })) // the table arrives after: nothing moves
    await flushPromises()
    expect(w.emitted('close')).toHaveLength(1)
    expect(rateField()!.value).toBe('0.9')
  })

  it('an entry opened with its saved rate is never refilled (a saved rate never moves)', async () => {
    server.append([{ entryId: DINNER, content: { ...expense('Dinner', 2000, ANNA, [ANNA, BEN]), currency: 'USD', rate: '0.8' } }])
    await open(session(TEMPLATE), DINNER)
    expect(rateField()!.value).toBe('0.8')
    expect(fetchSpy).not.toHaveBeenCalled()
  })
})
