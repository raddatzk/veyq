// The policy-violation fixture (ADR 0117): every test of lane L1 fails when a page it drove reported a
// Content-Security-Policy violation. The web client's policy ENFORCES Trusted Types, and a refusal there is runtime-only -
// a library writing `innerHTML` from a string, a worker started from a plain URL - so no unit test can see one: the only
// proof is this suite clicking the release candidate with a violation counted as a failure.
//
// How: `test` here is `@playwright/test`'s with two fixtures. The worker's `browser` arms every context it creates
// (`armContext`): an init script, run in every document and frame before the page's own scripts, forwards each
// `securitypolicyviolation` event to the test process, and the browser's console - where Chromium also reports a
// violation inside a worker, which no document event sees - is read for its CSP and Trusted Types messages. An automatic
// test fixture then asserts that none was reported since the previous test ended, so what a `beforeAll` provoked fails
// the first test after it; the worker's teardown fails on what an `afterAll` left. A browser a spec launches itself
// (`safety-number-qr.spec.ts`' camera) is armed by `newDevice`, which arms every context it makes - arming is idempotent.
//
// Every spec imports `test` from here, never from `@playwright/test` (eslint.config.js refuses that import under tests/).
// Bite (snapshot-and-restore, never `git checkout`): add `document.body.innerHTML = location.hash` to src/web's
// `main.ts`, build the images, run one spec - RED, naming `require-trusted-types-for`; restore - green.
import { test as base, expect, type Browser, type BrowserContext, type ConsoleMessage, type Page } from '@playwright/test'

/** One violation as the page saw it (`SecurityPolicyViolationEvent`), or a console report of one. */
export interface Violation {
  directive: string
  blocked: string
  sample: string
  document: string
  source: string
  disposition: string
}

const BINDING = '__veyqCspViolation'
// Chromium's own wording for a refusal: "Refused to … because it violates the following Content Security Policy
// directive", "This document requires 'TrustedHTML' assignment", "… requires 'TrustedScriptURL' assignment".
const CONSOLE_VIOLATION = /Content Security Policy|requires 'Trusted(HTML|Script|ScriptURL)' assignment|Trusted Type/

const reported: Violation[] = []
const armed = new WeakSet<BrowserContext>()

function fromConsole(message: ConsoleMessage, where: string): void {
  if (message.type() === 'error' && CONSOLE_VIOLATION.test(message.text()))
    reported.push({ directive: 'console', blocked: '', sample: message.text(), document: where, source: message.location().url, disposition: '' })
  // Not a violation and no failure, but worth a line in browser.log: Chromium says so when it does not know a feature
  // the Permissions-Policy header names.
  else if (/Permissions-Policy/.test(message.text())) console.log(`[permissions-policy] ${message.text()} (${where})`)
}

/** Report every policy violation [context]'s pages, frames and workers see to the running test. Idempotent. */
export async function armContext(context: BrowserContext): Promise<void> {
  if (armed.has(context)) return
  armed.add(context)
  await context.exposeBinding(BINDING, (_source, violation: Violation) => void reported.push(violation))
  await context.addInitScript((binding: string) => {
    addEventListener(
      'securitypolicyviolation',
      (e) => {
        const report = (window as unknown as Record<string, (v: unknown) => void>)[binding]
        report({
          directive: e.effectiveDirective,
          blocked: e.blockedURI,
          sample: e.sample,
          document: e.documentURI,
          source: `${e.sourceFile}:${e.lineNumber}:${e.columnNumber}`,
          disposition: e.disposition,
        })
      },
      true,
    )
  }, BINDING)
  const watchPage = (page: Page) => {
    page.on('console', (m) => fromConsole(m, page.url()))
    page.on('worker', (worker) => worker.on('console', (m) => fromConsole(m, worker.url())))
  }
  for (const page of context.pages()) watchPage(page)
  context.on('page', watchPage)
}

/** What was reported since the last call, taken off the list. */
function drain(): Violation[] {
  return reported.splice(0)
}

const describe = (found: Violation[]) =>
  found.map((v) => `${v.directive} ${v.disposition} - ${v.sample || v.blocked} (at ${v.source} in ${v.document})`).join('\n')

export const test = base.extend<{ cspViolations: void }, { browser: Browser; cspViolationsLeft: void }>({
  browser: [
    async ({ browser }, use) => {
      const newContext = browser.newContext.bind(browser)
      // `newPage` makes its context through `this.newContext`, so a page made that way is armed too.
      browser.newContext = async (options) => {
        const context = await newContext(options)
        await armContext(context)
        return context
      }
      await use(browser)
    },
    { scope: 'worker' },
  ],
  cspViolations: [
    async ({}, use) => {
      await use()
      const found = drain()
      expect(found, `the pages reported Content-Security-Policy violations:\n${describe(found)}`).toEqual([])
    },
    { auto: true },
  ],
  cspViolationsLeft: [
    async ({}, use) => {
      await use()
      const found = drain()
      if (found.length) throw new Error(`Content-Security-Policy violations after the last test:\n${describe(found)}`)
    },
    { scope: 'worker', auto: true },
  ],
})
