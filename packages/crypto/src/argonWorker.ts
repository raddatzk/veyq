// Async front door to the heavy Argon2id derivations. Runs them in a Web Worker (argon.worker.ts) so the
// main thread doesn't freeze during login/unlock, and falls back to the synchronous conformance-pinned
// suite when no Worker is available (vitest/node, SSR, or worker construction failure). The crypto itself
// (v1.ts) is untouched and still runs synchronously under the conformance test — this is execution
// placement only, not a behaviour change.
import * as v1 from './v1'
import type { ArgonParams, AuthKeypair } from './v1'

// `fallback` is the synchronous derivation for this exact request, so a worker that dies mid-flight can be
// finished on the main thread instead of failing the user — the worker is a pure optimisation.
type Pending = { resolve: (value: unknown) => void; reject: (reason: unknown) => void; fallback: () => unknown }

let worker: Worker | null = null
let workerUnavailable = false
let seq = 0
const pending = new Map<number, Pending>()

/** The shared worker, or null when Workers aren't available (→ caller uses the sync fallback). */
function getWorker(): Worker | null {
  if (workerUnavailable) return null
  if (worker) return worker
  if (typeof Worker === 'undefined') {
    workerUnavailable = true
    return null
  }
  try {
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
      workerUnavailable = true
      worker = null
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
    return null
  }
}

function dispatch<T>(w: Worker, message: Record<string, unknown>, fallback: () => T): Promise<T> {
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
