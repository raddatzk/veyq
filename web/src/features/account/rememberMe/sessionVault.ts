// uc.account.remember-this-device (web "off" tier, cr-0055 / cr-0057). The session-scoped vault: a page reload
// re-opens the vault with no prompt, and it is gone when the browser closes — paired with the session refresh
// cookie (ADR 0004 addendum). This is the conventional web-session experience for "not remembered" sign-ins.
//
// How (cr-0057, ADR 0005 §23): a NON-EXTRACTABLE AES-GCM key K lives in IndexedDB (like the other device tiers),
// and only the **ciphertext** of the Master Key (+ iv) sits in `sessionStorage`. So K's bytes never enter JS —
// an XSS payload can decrypt while it runs but cannot carry K off-device, and nothing in `sessionStorage` is
// plaintext key material (the browser's session-restore may persist `sessionStorage` to disk — only ciphertext
// ever lands there). Browser close clears `sessionStorage` → the ciphertext is gone and the orphaned K (useless
// without it, and un-exfiltratable) is dropped on the next boot. Operator-blind is untouched (nothing here is
// server-side).
import { b64std, fromB64std } from '../../../shared/base64'
import { idbRun } from '../../../shared/idb'

const DB_NAME = 'veyq-session'
const STORE = 'key'
const RECORD = 'current'
const SS_KEY = 'veyq.session-vault'

/** The sessionStorage record: the account email + the Master Key sealed under the IndexedDB key K (iv +
 *  ciphertext, base64). No plaintext key material. */
type StoredVault = { email: string; iv: string; ciphertext: string }

export function isSessionVaultSupported(): boolean {
  try {
    return (
      typeof sessionStorage !== 'undefined' &&
      typeof indexedDB !== 'undefined' &&
      typeof crypto !== 'undefined' &&
      !!crypto.subtle
    )
  } catch {
    return false // some privacy modes throw on access
  }
}

/** Delete the IndexedDB key K (best-effort) — on clear, or to clean up an orphan when the ciphertext is gone. */
function dropKey(): Promise<void> {
  return idbRun(DB_NAME, STORE, 'readwrite', (s) => s.delete(RECORD)).then(
    () => undefined,
    () => undefined,
  )
}

/** Seal the unlocked Master Key under a fresh non-extractable key for the browser session (best-effort — a
 *  storage failure just means the device re-asks the password on the next reload). */
export async function cacheSessionVault(email: string, masterKey: Uint8Array): Promise<void> {
  try {
    const key = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt'])
    await idbRun(DB_NAME, STORE, 'readwrite', (s) => s.put(key, RECORD))
    const iv = crypto.getRandomValues(new Uint8Array(12))
    const ciphertext = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, masterKey as BufferSource)
    const v: StoredVault = { email: email.trim().toLowerCase(), iv: b64std(iv), ciphertext: b64std(new Uint8Array(ciphertext)) }
    sessionStorage.setItem(SS_KEY, JSON.stringify(v))
  } catch {
    /* storage / crypto unavailable — best-effort */
  }
}

/** Read + decrypt the session vault (email + Master Key), or null when absent/unusable. Drops the orphaned key
 *  when the ciphertext is gone (e.g. after a browser close cleared sessionStorage). */
export async function loadSessionVault(): Promise<{ email: string; masterKey: Uint8Array } | null> {
  let raw: string | null
  try {
    raw = sessionStorage.getItem(SS_KEY)
  } catch {
    return null
  }
  if (!raw) {
    await dropKey() // ciphertext gone → the IndexedDB key is an orphan; clean it up
    return null
  }
  try {
    const v = JSON.parse(raw) as StoredVault
    if (!v?.email || !v?.iv || !v?.ciphertext) return null
    const key = await idbRun<CryptoKey | undefined>(DB_NAME, STORE, 'readonly', (s) => s.get(RECORD))
    if (!key) return null
    const plain = await crypto.subtle.decrypt(
      { name: 'AES-GCM', iv: fromB64std(v.iv) as BufferSource },
      key,
      fromB64std(v.ciphertext) as BufferSource,
    )
    return { email: v.email, masterKey: new Uint8Array(plain) }
  } catch {
    return null
  }
}

/** Drop the session vault (logout, switching to the persistent tier, or a no-session boot): both the
 *  sessionStorage ciphertext and the IndexedDB key. */
export async function clearSessionVault(): Promise<void> {
  try {
    sessionStorage.removeItem(SS_KEY)
  } catch {
    /* ignore */
  }
  await dropKey()
}
