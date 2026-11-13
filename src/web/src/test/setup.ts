// Vitest global setup.
import { enableAutoUnmount } from '@vue/test-utils'
import { beforeAll, beforeEach, afterEach, expect, vi } from 'vitest'
import { installWireRecorder } from './wireRecorder'
import { byteArrayEquality } from './byteEquality'
import { registerApplicationMessages } from '../applicationMessages'

// Byte arrays compare as bytes, in one native pass, not element by element through vitest's generic equality: a
// multi-MiB read-back asserted with `toEqual` otherwise costs seconds and passed the test timeout on a 4-vCPU CI
// runner (byteEquality.ts says what it measured, byteEquality.test.ts proves this line is in force).
expect.addEqualityTesters([byteArrayEquality])

// The applications' strings (inbox #513): core's catalog holds core's namespaces only, and each application's arrive
// through the composition root's catalog half, as in `main.ts` — for EVERY test file, the node-environment suites too
// (they translate `memory.*` keys without rendering). A static import, unlike the registry's below: the catalog half
// loads data, vue and vue-i18n, which no test mocks, so it cannot defeat a test file's `vi.mock`, and the strings are
// there before a test file's collection runs. Once per file: the module graph is per file, so the write-once catalog
// starts with core's alone each time.
registerApplicationMessages()

// The shell asks the application registry what to render (ADR 0079); fill it as `main.ts` does, once per test file
// (the module graph is per file, so the write-once registry starts empty each time).
//
// **Why a dynamic import in `beforeAll`, not a static one at the top.** A static import here would evaluate every
// registered application component (and the whole module tree under it) BEFORE the test file runs, so a
// `vi.mock('<any module the application imports>')` hoisted in the test file would find that module already
// loaded for real and stub nothing — measured: 119 of 186 files red the first time this was tried. In `beforeAll`
// the test file's mocks are registered, so the application's modules load under them, as in the file's own imports.
// A node-environment file (the pure-logic suites and the e2e suites) renders nothing and has no `window` for the
// application's map library to load into, so it is not given the registry.
beforeAll(async () => {
  if (typeof window === 'undefined') return
  const { registerApplications } = await import('../applications')
  registerApplications()
})

// Unmount every wrapper after each test. A component left mounted keeps its library timers alive past the file's
// teardown, and one that lands after vitest has deleted the DOM globals throws an unhandled error that fails the
// whole run while every test is green (a library tab row's ink-bar timer did, under load). Unmounting clears the
// refs those timers read, so they return without touching the DOM. Proven by `autoUnmount.test.ts`.
enableAutoUnmount(afterEach)

// A new instance's outside services (uc.admin.configure-external-services): the map tiles, the geocoder and the breach
// check, as the backend's migration seeds them and the manifest carries them. A test about a server without one clears
// it (`test/services.ts`); before each test the defaults stand again.
beforeEach(async () => {
  const { defaultServices } = await import('./services')
  defaultServices()
  // TEMPORARY: every test runs the music as built (musicReleased, ADR 0131 decision 8); the tests of the dark release
  // switch it off. Removal: delete these two lines with the flag.
  ;(await import('../shared/state/capabilities')).capabilityState.musicReleased = true
})

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

// The e2e half of the same concern, and the opposite arrangement: the net below BLOCKS a unit test's request,
// while this RECORDS an e2e suite's (cc.security.wire-assertion, ADR 0053 §3). They never both apply — the net
// needs `globalThis.happyDOM`, which the node-environment e2e process does not have, and the recorder needs
// `VEYQ_HTTP_RECORD_FILE`, which only the orchestrator sets. See `wireRecorder.ts` for why that is not an
// accident of two mechanisms but the consequence of one measured fact about the environments.
installWireRecorder()

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

// Every level, header and share page reads the published moderation key when it mounts, to show "Report" only while
// the instance has one (uc.moderation.report). A unit test sees no key — no report action, no request — unless it
// tests reporting and takes the real module back with `vi.unmock` (core/report/moderationKey.ts).
vi.mock('../core/report/moderationKey', () => ({
  readModerationKey: () => Promise.reject(new Error('no moderation key in a unit test (src/test/setup.ts)')),
}))

// Every blocked placeholder reads the caller's statements of reasons when it mounts (uc.moderation.object-to-a-measure).
// A unit test sees none — no statement, no request — unless it tests the notice and takes the real module back.
vi.mock('../features/moderation/statement/statementsRead', () => ({
  readMyStatements: () => Promise.resolve([]),
}))

// …and so does every placeholder and notice on a guest's page, what the link tells this device's guest uploader (ADR 0093
// §§ 7, 12): nothing to tell, no request, unless a test of the notice takes the real module back.
vi.mock('../core/sharing/guestNoticeRead', () => ({
  readGuestNotice: () => Promise.resolve(null),
}))

afterEach(() => {
  if (!attempted.length) return
  const calls = attempted.splice(0).join('\n  - ')
  throw new Error(
    `This test reached the network. Unit tests must stub what their component fetches (inbox #149).\n` +
      `  - ${calls}\n` +
      `Stub the module that issues it, the way this file's neighbours stub theirs.`,
  )
})
