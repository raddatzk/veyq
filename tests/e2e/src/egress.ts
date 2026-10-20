// Self-hostable by default, checked from the browser (ADR 0090 decision 6): a page of this instance may reach the
// instance and nothing else. Every request to another origin is aborted and recorded, and the test fails on it —
// except the one third party the product names, Have I Been Pwned's k-anonymity range query
// (docs/crosscutting/security-model.md § the breached-password check), which is answered here with "no match", so
// the run needs no internet and sends no hash prefix anywhere.
//
// What a page TRIED counts, not only what reached the network: the page's own Content-Security-Policy refuses most
// foreign requests inside the renderer, before any route sees them (measured in cr-stack-e2e phase 2 — an injected
// `fetch('https://example.org/')` on admin-web never reached the route and the run stayed green). A refusal is the
// policy working, and still a finding here: the page wanted something that is not the instance. So every CSP
// violation a page reports is recorded beside the aborted requests.
//
// And two layers, so neither is the only thing between a page and the internet: the routes below decide per
// request, and the browser's resolver (playwright.config.ts) knows only the instance's names — a request that got
// past a route would find no address to go to.
import { expect, type BrowserContext } from '@playwright/test'
import { stack } from './stack'

// admin-web's default range API (ADR 0088 decision 7) plus the 5-hex-character SHA-1 prefix it appends.
const HIBP_RANGE = /^https:\/\/api\.pwnedpasswords\.com\/range\/[0-9A-F]{5}$/

export interface Confinement {
  /** Fails the test, listing them, when a page asked for anything outside the instance. */
  assertConfined: () => void
  /** The range queries answered locally — a check that ran asked exactly once per finished password. */
  breachQueries: () => string[]
}

export async function confineToInstance(context: BrowserContext): Promise<Confinement> {
  const { adminOrigin, domain } = stack()
  const foreign: string[] = []
  const breach: string[] = []

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
    foreign.push(`${route.request().method()} ${url.href}`)
    return route.abort('blockedbyclient')
  })

  return {
    assertConfined: () => expect(foreign, 'requests to an origin that is not this instance').toEqual([]),
    breachQueries: () => [...breach],
  }
}
