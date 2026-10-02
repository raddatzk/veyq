// Async front door to the heavy Argon2id derivations. Runs them in a Web Worker (argon.worker.ts) so the
// main thread doesn't freeze during login/unlock, and falls back to the synchronous conformance-pinned
// suite when no Worker is available (vitest/node, SSR, or worker construction failure). The crypto itself
// (v1.ts) is untouched and still runs synchronously under the conformance test — this is execution
// placement only, not a behaviour change. It is the ONLY way production code reaches Argon2id: the
// `veyq/argon-through-the-worker` lint door (../eslint-argon-door.js) refuses a direct call anywhere else.
import * as v1 from './v1'
import type { ArgonParams, AuthKeypair } from './v1'
import type { ArgonOp } from './argonOps'

// `fallback` is the synchronous derivation for this exact request, so a worker that dies mid-flight can be
// finished on the main thread instead of failing the user — the worker is a pure optimisation.
type Pending = { resolve: (value: unknown) => void; reject: (reason: unknown) => void; fallback: () => unknown }

let worker: Worker | null = null
let workerUnavailable = false
let warned = false
let seq = 0
const pending = new Map<number, Pending>()

/**
 * Say once per page that a Worker which EXISTED could not run, so the fallback is visible in devtools: without it a
 * mis-bundled or CSP-refused worker costs every sign-in its blocked page thread and nothing anywhere says so. Not
 * called where there is no `Worker` at all (node: vitest, the e2e suites, the admin CLI — the designed path), and
 * not on a per-request `postMessage` throw, which does not retire the worker. Carries no secret, no parameter and
 * no error text.
 */
function warnOnce(): void {
  if (warned) return
  warned = true
  console.warn('[veyq] Argon2 worker unavailable — deriving on the page thread')
}

/** The shared worker, or null when Workers aren't available (→ caller uses the sync fallback). */
function getWorker(): Worker | null {
  if (workerUnavailable) return null
  if (worker) return worker
  if (typeof Worker === 'undefined') {
    workerUnavailable = true
    return null
  }
  try {
    // ONE expression, on purpose: Vite emits the worker chunk only for `new Worker(new URL('…', import.meta.url))`
    // spelled together. Hoist the URL into a variable and the build stays green, emits no chunk, and every
    // derivation falls back to the page thread — `verify:argon-worker` (scripts/assert-argon-worker-in-dist.mjs)
    // is what turns that red.
    worker = new Worker(new URL('./argon.worker.ts', import.meta.url), { type: 'module' })
    worker.addEventListener('message', (event: MessageEvent) => {
      const { id, ok, result, error } = event.data as { id: number; ok: boolean; result?: unknown; error?: string }
      const p = pending.get(id)
      if (!p) return
      pending.delete(id)
      if (ok) p.resolve(result)
      else p.reject(new Error(error ?? 'argon_worker_error'))
    })
    worker.addEventListener('error', () => {
      // A worker-level failure (load error, runtime crash, OOM) is treated as fatal: retire the worker so
      // every future call takes the sync path, and finish each outstanding request on the sync fallback
      // rather than leaving it to hang. The worker is an optimisation; failure degrades, never breaks.
      // This listener, not the constructor's catch, is where a chunk that 404s or a URL the CSP refuses lands:
      // measured in Chromium 152 (2026-10-02), both construct fine and fire `error`.
      workerUnavailable = true
      worker = null
      warnOnce()
      for (const p of pending.values()) {
        try {
          p.resolve(p.fallback())
        } catch (err) {
          p.reject(err)
        }
      }
      pending.clear()
    })
    return worker
  } catch {
    workerUnavailable = true
    warnOnce()
    return null
  }
}

function dispatch<T>(w: Worker, message: { op: ArgonOp } & Record<string, unknown>, fallback: () => T): Promise<T> {
  const id = (seq += 1)
  return new Promise<T>((resolve, reject) => {
    pending.set(id, { resolve: resolve as (v: unknown) => void, reject, fallback })
    try {
      w.postMessage({ id, ...message })
    } catch {
      // A synchronous postMessage failure (un-cloneable payload, terminated worker) — finish on the sync
      // path and drop the leaked pending entry.
      pending.delete(id)
      resolve(fallback())
    }
  })
}

/** Derive the Ed25519 auth keypair (ADR 0003) — off the main thread when possible. */
export async function deriveAuthKeypairAsync(
  password: string,
  authSalt: Uint8Array,
  params?: ArgonParams,
): Promise<AuthKeypair> {
  const w = getWorker()
  if (!w) return v1.deriveAuthKeypair(password, authSalt, params)
  return dispatch<AuthKeypair>(w, { op: 'authKeypair', password, salt: authSalt, params }, () =>
    v1.deriveAuthKeypair(password, authSalt, params),
  )
}

/** Derive the PASSWORD-wrap Unlock KEK = Argon2id(secretKey + password) — off the main thread when possible. */
export async function unlockKekAsync(
  secretKey: Uint8Array,
  password: string,
  salt: Uint8Array,
  params?: ArgonParams,
): Promise<Uint8Array> {
  const w = getWorker()
  if (!w) return v1.kekPasswordSecret(secretKey, password, salt, params)
  return dispatch<Uint8Array>(w, { op: 'unlockKek', secretKey, password, salt, params }, () =>
    v1.kekPasswordSecret(secretKey, password, salt, params),
  )
}

/**
 * Derive the RECOVERY-wrap KEK = Argon2id(secretKey) — off the main thread when possible.
 *
 * The one derivation here that takes **no password**: the recovery lock is opened by the Secret Key alone
 * (ADR 0054), which is exactly why the server withholds that wrap from the ordinary key-material read.
 */
export async function recoveryKekAsync(
  secretKey: Uint8Array,
  salt: Uint8Array,
  params?: ArgonParams,
): Promise<Uint8Array> {
  const w = getWorker()
  if (!w) return v1.kekRecovery(secretKey, salt, params)
  return dispatch<Uint8Array>(w, { op: 'recoveryKek', secretKey, salt, params }, () =>
    v1.kekRecovery(secretKey, salt, params),
  )
}

/** Derive the DEVICE-wrap Unlock KEK = Argon2id(deviceFactor + password) — off the main thread when possible. */
export async function deviceKekAsync(
  deviceFactor: Uint8Array,
  password: string,
  salt: Uint8Array,
  params?: ArgonParams,
): Promise<Uint8Array> {
  const w = getWorker()
  if (!w) return v1.kekDevicePassword(deviceFactor, password, salt, params)
  return dispatch<Uint8Array>(w, { op: 'deviceKek', deviceFactor, password, salt, params }, () =>
    v1.kekDevicePassword(deviceFactor, password, salt, params),
  )
}

/**
 * Derive the share-password guest token = Argon2id(password, pwSalt) (ADR 0037) — off the main thread when possible.
 * Both the curator setting a link's password and the anonymous viewer opening it pay this.
 */
export async function sharesPwTokenAsync(password: string, salt: Uint8Array, params?: ArgonParams): Promise<Uint8Array> {
  const w = getWorker()
  if (!w) return v1.sharesPwToken(password, salt, params)
  return dispatch<Uint8Array>(w, { op: 'sharesPwToken', password, salt, params }, () =>
    v1.sharesPwToken(password, salt, params),
  )
}
