// Vitest global setup. Components now route transient feedback through `useToast` (cr-0157/ADR 0039), which
// injects PrimeVue's toast service. Install `ToastService` into the @vue/test-utils global config so every
// mounted component satisfies the `usePrimeToast()` injection without each test having to register it (tests
// that stub the shared `useToast` composable are unaffected).
import { config } from '@vue/test-utils'
import { beforeEach, afterEach } from 'vitest'
import ToastService from 'primevue/toastservice'

config.global.plugins.push(ToastService)

// ─────────────────────────────────────────────────────────────────────────────────────────────────────────
// The network net (inbox #149) — carried over from `src/web/src/test/setup.ts`, deliberately, not by accident.
//
// **What it does.** Every outbound request a unit test makes is blocked before a socket opens, recorded, and
// then fails that test by URL. An unmocked API call becomes a named failure instead of a silent one.
//
// **The defect that bought it happened in `src/web/`, not here** — `AppSidebar.test.ts` stubbed the tenant list
// its component fetches `onMounted` and missed the profile call beside it, so 14 real requests left that
// suite per run for as long as nobody looked; the suite stayed green because the component catches its own
// failure. **This package is clean today** (measured: `npx vitest run` → 18 files, 104 tests, zero connection
// attempts) and the net is here because the *shape* is: `AdminConsoleView.vue`'s `onMounted` is async and
// fetches, which is exactly the shape that bit the other side. The ui-web-styleguide's rule about this copy
// applies — nothing is automatically true of both packages, so it is carried over by hand or not at all.
//
// **Its limits, the same three, and the first reads differently here.**
//   (a) `*.e2e.test.ts` is not armed. This package has **no** such file today, so that branch is inert — it
//       is kept so both copies stay one shape, and so this package's first e2e test is covered the day it
//       lands rather than the day someone remembers.
//   (b) A **sync** request is recorded but not blocked: faking happy-dom's `ISyncResponse` is more machinery
//       than the case deserves while both suites make zero of them. The recording still fails the test.
//   (c) A request made at **module import time** is attributed to the first test of the file. The URL in the
//       message is still the real one.
// ─────────────────────────────────────────────────────────────────────────────────────────────────────────

type InterceptedRequest = { method: string; url: string }
type FetchSettings = { interceptor: unknown }
type HappyDOMGlobal = { happyDOM?: { settings?: { fetch?: FetchSettings } } }

const attempted: string[] = []
// Armed by default: a request made before the first `beforeEach` (module import time, limit (c)) is still
// caught rather than dropped.
let armed = true

const happyDOM = (globalThis as unknown as HappyDOMGlobal).happyDOM
if (happyDOM?.settings?.fetch) {
  happyDOM.settings.fetch.interceptor = {
    beforeAsyncRequest: ({ request }: { request: InterceptedRequest }) => {
      if (!armed) return undefined // (a) e2e: let the real request through
      attempted.push(`${request.method} ${request.url}`)
      // Returning a response is what keeps the socket closed; the status is arbitrary and never asserted on.
      return Promise.resolve(new Response('', { status: 599, statusText: 'blocked by the test network net' }))
    },
    beforeSyncRequest: ({ request }: { request: InterceptedRequest }) => {
      if (!armed) return undefined
      attempted.push(`${request.method} ${request.url} (sync — recorded, not blocked: limit (b))`)
      return undefined
    },
  }
}

beforeEach((ctx) => {
  armed = !ctx.task.file.name.endsWith('.e2e.test.ts')
})

afterEach(() => {
  if (!attempted.length) return
  const calls = attempted.splice(0).join('\n  - ')
  throw new Error(
    `This test reached the network. Unit tests must stub what their component fetches (inbox #149).\n` +
      `  - ${calls}\n` +
      `Stub the module that issues it, the way this file's neighbours stub theirs.`,
  )
})
