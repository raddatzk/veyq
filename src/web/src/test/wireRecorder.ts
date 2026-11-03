// The suite half of the wire assertion's HTTP surface (cc.security.wire-assertion, ADR 0053 § Decision 3).
// Its sibling is `canaries.ts`, which derives WHAT to look for; this derives WHERE the suite could have leaked
// it — every request this process made and every response it received: URL, request headers, textual bodies, and
// every response header (`Set-Cookie` included, the only place a web sign-in's refresh token crosses the wire).
// The orchestrator reads the same file twice: for canaries, and for the capability tokens the contract marks
// `token` (cc.security.wire-assertion § Capability tokens).
//
// **Why the suite and not the orchestrator.** ADR 0053 §3 splits the surfaces by who can reach them. Path
// params, query strings, capability headers, presigned URLs and error bodies exist only in the calling
// process: by the time anything reaches the database or the bucket, the URL that carried it is gone. The
// converse is why this file does NOT record store bytes — see the limit below.
//
// **Why a `globalThis.fetch` wrapper, and why that is not the mistake it looks like.** Inbox #149 measured
// that patching `globalThis.fetch` in `setup.ts` never fires — true **under happy-dom**, which installs its
// own `window.fetch` that the client uses instead. The e2e suites declare no `@vitest-environment`, so they
// run under vitest's default **node**, where `fetch` is the global undici one and the generated client
// resolves it at call time (`src/api/generated/*/runtime.ts`: `(this.configuration.fetchApi || fetch)(…)`).
// Media's presigned store PUT/GET reach the same binding (`features/memories/media.ts`). For the same reason
// the happy-dom `settings.fetch.interceptor` seam that `setup.ts` uses for unit tests is unavailable here —
// `globalThis.happyDOM` is undefined in this process — and a sensor built on it would never run and report
// green.
//
// **The assertion is not here.** This file only records; `scripts/run_web_e2e.py` reads the file and decides.
// That is the same separation the database sweep has, and the reason it exists: an oracle inside the process
// under test can be satisfied by relaxing it.
import { appendFileSync, mkdirSync } from 'node:fs'
import { dirname } from 'node:path'

/** What one call contributes to the recording. Bodies are text; see [recordBody] for what binary becomes. */
type WireEntry = {
  method: string
  url: string
  requestHeaders: Record<string, string>
  requestBody: string | null
  status: number
  /** Every response header; `set-cookie` as the array [responseHeadersOf] reads — see there for why. */
  responseHeaders: Record<string, string | string[]>
  responseBody: string | null
}

/**
 * A body the sweep can search, or null.
 *
 * **Binary request bodies are deliberately NOT recorded, and this is a stated limit rather than an oversight.**
 * A media upload's body is an encrypted frame of arbitrary size; writing it here would put the whole object
 * store through a JSONL file for a sweep that already reads those bytes at their real surface — the bucket,
 * swept by the orchestrator, where objects the *server* wrote are also visible and where the object keys are.
 * So a binary body contributes its length and nothing else, and the store surface carries that evidence.
 */
function recordBody(body: unknown): string | null {
  if (typeof body === 'string') return body
  if (body === null || body === undefined) return null
  if (ArrayBuffer.isView(body) || body instanceof ArrayBuffer) {
    const bytes = ArrayBuffer.isView(body) ? body.byteLength : body.byteLength
    return `[binary ${bytes} bytes — swept at the store, not here]`
  }
  return null
}

function headersOf(init: RequestInit | undefined): Record<string, string> {
  const out: Record<string, string> = {}
  const raw = init?.headers
  if (!raw) return out
  if (raw instanceof Headers) raw.forEach((value, key) => (out[key] = value))
  else if (Array.isArray(raw)) for (const [key, value] of raw) out[key] = value
  else Object.assign(out, raw)
  return out
}

/**
 * Every header of a response, for the token sweep (a refresh-token `Set-Cookie`) and the canary sweep alike.
 *
 * **`set-cookie` is read ONLY through `getSetCookie()`, never through `forEach` into the object** — the shape
 * [headersOf] uses, and the trap. A response may carry several `Set-Cookie`s, and an object keeps one value per
 * key: `SessionCookie.issue` sends the refresh-token cookie FIRST and the clearing cookie for the other lifetime
 * second, so the obvious loop records only `veyq_refresh=` (empty) on every sign-in, and the sweep stays green
 * with nothing to look for (measured under node 23; `wireRecorder.test.ts` pins it).
 */
function responseHeadersOf(response: Response): Record<string, string | string[]> {
  const out: Record<string, string | string[]> = {}
  response.headers.forEach((value, key) => {
    if (key !== 'set-cookie') out[key] = value
  })
  const cookies = response.headers.getSetCookie()
  if (cookies.length > 0) out['set-cookie'] = cookies
  return out
}

/**
 * Wrap `globalThis.fetch` so every call lands in `VEYQ_HTTP_RECORD_FILE` as one JSON line.
 *
 * A no-op when the variable is unset — which is what keeps a hand-run `VEYQ_E2E=1 pnpm exec vitest run media.e2e`
 * working with no orchestrator, and what keeps this out of every unit test's way. Appended per call rather
 * than rewritten, so a suite that dies mid-flow still leaves on disk exactly what it had sent up to then.
 *
 * **The response is cloned before it is read.** A `Response` body is a stream and reading it consumes it; the
 * caller would then receive an empty one and the failure would look like anything except this recorder.
 */
export function installWireRecorder(): void {
  const path = process.env.VEYQ_HTTP_RECORD_FILE
  if (!path) return
  mkdirSync(dirname(path), { recursive: true })
  const original = globalThis.fetch
  globalThis.fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const response = await original(input, init)
    // **Only a body the server calls textual is read as text.** A media GET returns an encrypted frame, and
    // `text()` on it yields half a megabyte of mojibake per suite — useless to search, and it carries code
    // points (U+0085, U+2028) that a reader splitting on line boundaries will cut a JSON record in half. Those
    // bytes have a better surface anyway: the bucket, where the orchestrator sweeps keys and bytes both.
    const contentType = response.headers.get('content-type') ?? ''
    const textual = /^(application\/(json|.*\+json)|text\/)/i.test(contentType)
    let responseBody: string | null = null
    if (textual) {
      try {
        responseBody = await response.clone().text()
      } catch {
        responseBody = null // already disturbed — nothing to record, and nothing to hide
      }
    } else {
      responseBody = `[non-text ${contentType || 'unknown type'} — swept at the store, not here]`
    }
    const entry: WireEntry = {
      method: init?.method ?? 'GET',
      url: typeof input === 'string' ? input : input instanceof URL ? input.href : input.url,
      requestHeaders: headersOf(init),
      requestBody: recordBody(init?.body),
      status: response.status,
      responseHeaders: responseHeadersOf(response),
      responseBody,
    }
    appendFileSync(path, `${JSON.stringify(entry)}\n`, 'utf8')
    return response
  }
}
