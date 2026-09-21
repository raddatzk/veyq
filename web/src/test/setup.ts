// Vitest global setup. Components now route transient feedback through `useToast` (cr-0157/ADR 0039), which
// injects PrimeVue's toast service. Install `ToastService` into the @vue/test-utils global config so every
// mounted component satisfies the `usePrimeToast()` injection without each test having to register it (tests
// that stub the shared `useToast` composable are unaffected).
import { config } from '@vue/test-utils'
import { beforeEach, afterEach } from 'vitest'
import ToastService from 'primevue/toastservice'

config.global.plugins.push(ToastService)

// ─────────────────────────────────────────────────────────────────────────────────────────────────────────
// The network net (inbox #149).
//
// **What it does.** Every outbound request a unit test makes is blocked before a socket opens, recorded, and
// then fails that test by URL. An unmocked API call becomes a named, attributable failure instead of a
// silent one.
//
// **The defect that bought it.** `AppSidebar.test.ts` stubbed the tenant list its component fetches
// `onMounted` — its comment says so — and missed `getMyProfile()` beside it. Every mount therefore sent a
// real `GET /api/account/profile` out of the process: 14 per suite run, one per test, for as long as nobody
// looked. The suite stayed green because the component wraps that call in `try/catch` for its `~me` fallback,
// so nothing failed and nothing reported it. `session.apiBase` is `''` by design (same-origin), so the
// request is relative and happy-dom resolves it against its own origin, `http://localhost:3000` — the most
// common dev-server port there is. On a machine running some other project there, those 14 refused
// connections are 14 answered ones, and the suite reads a stranger's bytes with nothing saying so.
//
// **Why here and not in that test file.** The anchor is "a test reached the network", not those two mocks:
// any component fetching `onMounted` reopens it, and the next one will be as quiet as this one was.
//
// **Its limits, all three deliberate.**
//   (a) `*.e2e.test.ts` is NOT armed. Those tests drive a real backend through the same config
//       (`scripts/run_web_e2e.py`), so blocking them would be blocking the point. The suffix is the
//       discriminator because it is already what separates them — nothing is listed here.
//   (b) A **sync** request is recorded but not blocked. Faking happy-dom's `ISyncResponse` to block one is
//       more machinery than the case deserves while the suite makes zero of them; the recording still fails
//       the test, so a new one cannot arrive quietly — it just opens its socket on the way out.
//   (c) A request made at **module import time**, before any test runs, is attributed to the first test of
//       the file rather than to the import. The URL in the message is still the real one, which is the part
//       a reader needs.
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
      // Returning a response is what keeps the socket closed; the status is arbitrary and never asserted on,
      // because the test is going to fail on the record below regardless of how the caller handles it.
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
