// uc.account.trust-device — platform glue: a per-account NON-EXTRACTABLE device key in IndexedDB, the
// device factor it produces, and the local trust record. The key is generated with `extractable: false`, so no
// script can export its bytes — and that is all it buys. The factor is an HMAC over a constant, so it never
// changes: an injected script that runs once can copy the factor, which is as good as the key for an offline
// attack on the DEVICE wrap. The password is what still protects the wrap then (ADR 0005 §5). This is the
// platform half of the DEVICE wrap; the conformance-pinned crypto (the KEK) lives in v1.ts.
import { idbRun } from '../../../shared/idb'

const DB_NAME = 'veyq-trust'
const STORE = 'devices'
const DEVICE_BIND_INFO = 'veyq/v1/device-bind'

/** A device's trust: its non-extractable key and the server wrap id (the join key used to find its own
 *  DEVICE wrap in key material, and to untrust). `cryptoKey` is stored as a structured-cloned CryptoKey —
 *  still non-extractable after a round-trip through IndexedDB. */
export type TrustRecord = { cryptoKey: CryptoKey; wrapId: string }

export function isTrustSupported(): boolean {
  return typeof indexedDB !== 'undefined' && typeof crypto !== 'undefined' && !!crypto.subtle
}

const run = <T>(mode: IDBTransactionMode, op: (store: IDBObjectStore) => IDBRequest<T>): Promise<T> =>
  idbRun(DB_NAME, STORE, mode, op)

const accountKey = (email: string): string => email.trim().toLowerCase()

/** A fresh non-extractable HMAC key — its raw bytes never leave the WebCrypto boundary. */
export function generateDeviceKey(): Promise<CryptoKey> {
  return crypto.subtle.generateKey({ name: 'HMAC', hash: 'SHA-256' }, false, ['sign'])
}

/** The device factor = HMAC(deviceKey, "veyq/v1/device-bind"); deterministic, so it reproduces across
 *  reloads and feeds the same KEK. */
export async function deviceFactor(cryptoKey: CryptoKey): Promise<Uint8Array> {
  const sig = await crypto.subtle.sign('HMAC', cryptoKey, new TextEncoder().encode(DEVICE_BIND_INFO))
  return new Uint8Array(sig)
}

export function saveTrust(email: string, record: TrustRecord): Promise<void> {
  return run('readwrite', (s) => s.put(record, accountKey(email))).then(() => undefined)
}

export async function loadTrust(email: string): Promise<TrustRecord | null> {
  return (await run<TrustRecord | undefined>('readonly', (s) => s.get(accountKey(email)))) ?? null
}

/** The accounts this device holds a trust record for: the store's keys, which are the normalised emails. */
export async function listTrustedEmails(): Promise<string[]> {
  return (await run<IDBValidKey[]>('readonly', (s) => s.getAllKeys())).map(String)
}

export function clearTrust(email: string): Promise<void> {
  return run('readwrite', (s) => s.delete(accountKey(email))).then(() => undefined)
}

/** Wipe EVERY local trust record on this device (local only — no server call). Used after admin setup, where
 *  the instance is fresh and any record left in this browser belongs to a previous account/instance. */
export function forgetAllTrust(): Promise<void> {
  return run('readwrite', (s) => s.clear()).then(() => undefined)
}
