// The day's exchange rates (ADR 0108 decision 8, uc.expenses.exchange-rates): the request itself — one per device and day
// for any number of Pots, a URL that carries the date and nothing else, kept across a reload, never repeated for a day
// whose request failed — and the cross rate, exact in fractions with the euro as 1.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { crossRate, dayTable, parseTable, setRateSourceForTest, tableUrl, type RateTable } from './exchangeRates'

const TEMPLATE = 'https://api.frankfurter.dev/v2/providers/ecb/rates?date={date}'
const DAY = '2026-10-25'
/** The ECB's table as Frankfurter's ECB endpoint answers it (measured 2026-10-25): a Sunday asked, Friday's rows. */
const ANSWER = [
  { date: '2026-10-23', base: 'EUR', quote: 'EUR', rate: 1.0 },
  { date: '2026-10-23', base: 'EUR', quote: 'USD', rate: 1.1725 },
  { date: '2026-10-23', base: 'EUR', quote: 'GBP', rate: 0.8731 },
  { date: '2026-10-23', base: 'EUR', quote: 'JPY', rate: 177.84 },
  { date: '2026-10-23', base: 'EUR', quote: 'CNY', rate: 7.466 },
]

/** A `Storage` in memory — a device's local storage across "reloads". */
class MemoryStorage implements Storage {
  private map = new Map<string, string>()
  get length() {
    return this.map.size
  }
  clear() {
    this.map.clear()
  }
  getItem(k: string) {
    return this.map.get(k) ?? null
  }
  key(i: number) {
    return [...this.map.keys()][i] ?? null
  }
  removeItem(k: string) {
    this.map.delete(k)
  }
  setItem(k: string, v: string) {
    this.map.set(k, v)
  }
  keys() {
    return [...this.map.keys()]
  }
}

let storage: MemoryStorage
let fetchSpy: ReturnType<typeof vi.fn<(url: string, init: RequestInit) => Promise<Response>>>
const ok = () => Promise.resolve(new Response(JSON.stringify(ANSWER), { status: 200, headers: { 'content-type': 'application/json' } }))

beforeEach(() => {
  storage = new MemoryStorage()
  fetchSpy = vi.fn(ok)
  setRateSourceForTest({ fetch: fetchSpy, storage: () => storage })
})
afterEach(() => setRateSourceForTest(null))

describe('the request', () => {
  it('is one per device and day, for any number of Pots and editors, kept across a reload', async () => {
    const tables = await Promise.all([1, 2, 3, 4, 5].map(() => dayTable(TEMPLATE, DAY)))
    expect(fetchSpy).toHaveBeenCalledTimes(1)
    expect(tables.every((t) => t?.USD?.rate === '1.1725')).toBe(true)
    await dayTable(TEMPLATE, DAY)
    setRateSourceForTest({ fetch: fetchSpy, storage: () => storage }) // a reload: the page's copy gone, the device's kept
    expect((await dayTable(TEMPLATE, DAY))?.JPY?.rate).toBe('177.84')
    expect(fetchSpy).toHaveBeenCalledTimes(1)
    // control: the next day asks once more, and the device keeps that day's table alone
    await dayTable(TEMPLATE, '2026-10-26')
    expect(fetchSpy).toHaveBeenCalledTimes(2)
    expect(storage.keys()).toEqual(['veyq:expenses:rates:2026-10-26'])
  })

  it('carries the date and nothing else: no currency, no amount, no header, no credentials, no Referer', async () => {
    await dayTable(TEMPLATE, DAY)
    const [url, init] = fetchSpy.mock.calls[0]!
    expect(url).toBe('https://api.frankfurter.dev/v2/providers/ecb/rates?date=2026-10-25')
    expect([...new URL(url).searchParams.keys()]).toEqual(['date'])
    expect(url).not.toMatch(/EUR|USD|base|quote|symbols|amount/i)
    expect(init).toEqual({ method: 'GET', credentials: 'omit', referrerPolicy: 'no-referrer' })
  })

  it('is not repeated for a day whose request failed: the rate is typed until the next day (at most once a day)', async () => {
    fetchSpy.mockImplementationOnce(() => Promise.reject(new TypeError('unreachable')))
    expect(await dayTable(TEMPLATE, DAY)).toBeNull()
    setRateSourceForTest({ fetch: fetchSpy, storage: () => storage })
    expect(await dayTable(TEMPLATE, DAY)).toBeNull()
    expect(fetchSpy).toHaveBeenCalledTimes(1)
    expect(await dayTable(TEMPLATE, '2026-10-26')).not.toBeNull() // control: a new day asks
  })

  it('asks nothing for a template without {date}', async () => {
    expect(tableUrl('https://rates.example/latest', DAY)).toBeNull()
    expect(await dayTable('https://rates.example/latest', DAY)).toBeNull()
    expect(fetchSpy).not.toHaveBeenCalled()
  })
})

describe('the table and the cross rate', () => {
  const table = parseTable(ANSWER)!

  it('from X to the base B is rate(B) / rate(X), exact, the euro 1, rounded half away from zero at 12 places', () => {
    expect(crossRate(table, 'USD', 'EUR')).toEqual({ rate: '0.852878464819', date: '2026-10-23' }) // 1 / 1.1725
    expect(crossRate(table, 'EUR', 'USD')).toEqual({ rate: '1.1725', date: '2026-10-23' })
    expect(crossRate(table, 'USD', 'JPY')).toEqual({ rate: '151.675906183369', date: '2026-10-23' }) // 177.84 / 1.1725
    expect(crossRate(table, 'GBP', 'USD')?.rate).toBe('1.342916046272') // 1.1725 / 0.8731 = 1.34291604627190…
    // the euro is 1 whether or not the table names it
    const noEuroRow: RateTable = { USD: { rate: '1.1725', date: '2026-10-23' } }
    expect(crossRate(noEuroRow, 'USD', 'EUR')?.rate).toBe('0.852878464819')
    // never through a float: 1 / 3 of a unit, where a double would print 0.3333333333333333
    expect(crossRate({ XXX: { rate: '3', date: '2026-10-23' } }, 'XXX', 'EUR')?.rate).toBe('0.333333333333')
  })

  it('names the rows\' date, which may be earlier than the day asked', () => {
    const mixed = parseTable([...ANSWER, { date: '2026-10-22', base: 'EUR', quote: 'CHF', rate: 0.9227 }])!
    expect(crossRate(mixed, 'CHF', 'USD')?.date).toBe('2026-10-22')
  })

  it('offers no currency the table lacks, and keeps no row that is no ISO currency, no plain decimal or another base', () => {
    const odd = parseTable([
      ...ANSWER,
      { date: '2026-10-23', base: 'EUR', quote: 'BTC', rate: 0.0000123 },
      { date: '2026-10-23', base: 'EUR', quote: 'XAU', rate: 0.00031 },
      { date: '2026-10-23', base: 'USD', quote: 'CAD', rate: 1.37 },
      { date: '2026-10-23', base: 'EUR', quote: 'SEK', rate: 1e-7 },
    ], new Set(['EUR', 'USD', 'GBP', 'JPY', 'CNY', 'CAD', 'SEK']))!
    expect(Object.keys(odd).sort()).toEqual(['CNY', 'EUR', 'GBP', 'JPY', 'USD'])
    expect(crossRate(odd, 'CAD', 'EUR')).toBeNull()
    expect(crossRate(odd, 'BTC', 'EUR')).toBeNull()
    expect(odd.CNY!.rate).toBe('7.466')
    expect(parseTable({ rates: {} })).toBeNull()
  })
})
