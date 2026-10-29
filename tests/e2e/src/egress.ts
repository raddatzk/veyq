// Self-hostable by default, checked from the browser (ADR 0090 decision 6): a page of this instance may reach the
// instance and nothing else. Every request to another origin is aborted and recorded, and the test fails on it —
// except the two third-party requests the product names and makes without opt-in (docs/crosscutting/security-model.md
// § third-party services), each answered here so the run needs no internet and tells no provider anything:
//  - Have I Been Pwned's k-anonymity range query (the breached-password check), answered with "no match";
//  - the day's table of the ECB's euro reference rates from Frankfurter (`expenses.exchange-rates`, ADR 0108 decision 8),
//    answered with a fixed table, `fixtures/ecb-rates-2026-10-23.json` — the ECB's rates of that Friday as Frankfurter's
//    ECB endpoint served them (source: European Central Bank; measured 2026-10-25), whatever date the device asks, as
//    a weekend or a holiday answers an earlier day's rows. Only the exact seeded address with a date is answered: a
//    request carrying anything more (a currency, a `base=`) is foreign like any other.
//
// What a page TRIED counts, not only what reached the network: the page's own Content-Security-Policy refuses most
// foreign requests inside the renderer, before any route sees them (measured when the lane was built — an injected
// `fetch('https://example.org/')` on admin-web never reached the route and the run stayed green). A refusal is the
// policy working, and still a finding here: the page wanted something that is not the instance. So every CSP
// violation a page reports is recorded beside the aborted requests.
//
// And two layers, so neither is the only thing between a page and the internet: the routes below decide per
// request, and the browser's resolver (playwright.config.ts) knows only the instance's names — a request that got
// past a route would find no address to go to.
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { expect, type BrowserContext } from '@playwright/test'
import { stack } from './stack'

// admin-web's default range API (ADR 0088 decision 7) plus the 5-hex-character SHA-1 prefix it appends.
const HIBP_RANGE = /^https:\/\/api\.pwnedpasswords\.com\/range\/[0-9A-F]{5}$/

// The seeded exchange-rate address (V202610251600__core_exchange_rate_service.sql) with the device's date in it, alone.
const ECB_RATES = /^https:\/\/api\.frankfurter\.dev\/v2\/providers\/ecb\/rates\?date=[0-9]{4}-[0-9]{2}-[0-9]{2}$/
const ECB_TABLE = readFileSync(fileURLToPath(new URL('../fixtures/ecb-rates-2026-10-23.json', import.meta.url)), 'utf8')

/** One rate-table request as the page sent it: its URL and the headers a page could add (a custom one is a finding). */
export interface RateQuery {
  href: string
  method: string
  headers: Record<string, string>
}

export interface Confinement {
  /** Fails the test, listing them, when a page asked for anything outside the instance. */
  assertConfined: () => void
  /** The range queries answered locally — a check that ran asked exactly once per finished password. */
  breachQueries: () => string[]
  /** The day's rate tables answered locally — a device asks at most once a day, whatever number of Pots it opens. */
  rateQueries: () => RateQuery[]
}

export async function confineToInstance(context: BrowserContext): Promise<Confinement> {
  const { adminOrigin, domain } = stack()
  const foreign: string[] = []
  const breach: string[] = []
  const rates: RateQuery[] = []

  await context.exposeBinding('__veyqCspViolation', ({ page }, violation: string) => {
    foreign.push(`refused by ${new URL(page.url()).origin}'s CSP: ${violation}`)
  })
  await context.addInitScript(() => {
    document.addEventListener('securitypolicyviolation', (e) => {
      const report = (window as unknown as { __veyqCspViolation: (v: string) => void }).__veyqCspViolation
      void report(`${e.effectiveDirective} ${e.blockedURI || '(no URI)'}`)
    })
  })
  const own = (url: URL) =>
    url.origin === adminOrigin || (url.protocol === 'https:' && (url.hostname === domain || url.hostname.endsWith(`.${domain}`)))

  await context.route('**/*', async (route) => {
    const url = new URL(route.request().url())
    if (url.protocol === 'data:' || url.protocol === 'blob:' || own(url)) return route.continue()
    if (HIBP_RANGE.test(url.href)) {
      breach.push(url.href)
      // A cross-origin fetch: without this header the browser would read the answer as a failed check, not a clean one.
      return route.fulfill({ status: 200, contentType: 'text/plain', headers: { 'access-control-allow-origin': '*' }, body: '' })
    }
    if (ECB_RATES.test(url.href)) {
      const request = route.request()
      rates.push({ href: url.href, method: request.method(), headers: request.headers() })
      return route.fulfill({ status: 200, contentType: 'application/json', headers: { 'access-control-allow-origin': '*' }, body: ECB_TABLE })
    }
    foreign.push(`${route.request().method()} ${url.href}`)
    return route.abort('blockedbyclient')
  })

  return {
    assertConfined: () => expect(foreign, 'requests to an origin that is not this instance').toEqual([]),
    breachQueries: () => [...breach],
    rateQueries: () => [...rates],
  }
}
