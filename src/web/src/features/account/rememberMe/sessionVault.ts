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
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { idbRun } from '../../../shared/idb'
import { SESSION_VAULT, dropSessionVault } from '../../../app/convenienceTiers'

const { db: DB_NAME, store: STORE, record: RECORD, storageKey: SS_KEY } = SESSION_VAULT

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

/**
 * The purpose this seal authenticates, as AES-GCM `additionalData`. Outside the typed seal union on purpose
 * (ADR 0072 Decision 2): the key is a non-extractable per-device WebCrypto `CryptoKey` that `@noble` cannot take,
 * and it is unique to this one record, so the key is the object binding and the purpose is all that is left to bind.
 */
const SESSION_VAULT_AD = new TextEncoder().encode('veyq/v1/session-vault')

/** Seal the unlocked Master Key under a fresh non-extractable key for the browser session (best-effort — a
 *  storage failure just means the device re-asks the password on the next reload). */
export async function cacheSessionVault(email: string, masterKey: Uint8Array): Promise<void> {
  try {
    const key = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt'])
    await idbRun(DB_NAME, STORE, 'readwrite', (s) => s.put(key, RECORD))
    const iv = crypto.getRandomValues(new Uint8Array(12))
    const ciphertext = await crypto.subtle.encrypt({ name: 'AES-GCM', iv, additionalData: SESSION_VAULT_AD }, key, masterKey as BufferSource)
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
      { name: 'AES-GCM', iv: fromB64std(v.iv) as BufferSource, additionalData: SESSION_VAULT_AD },
      key,
      fromB64std(v.ciphertext) as BufferSource,
    )
    return { email: v.email, masterKey: new Uint8Array(plain) }
  } catch {
    return null
  }
}

/** Carry the session vault over to the account's new sign-in address (uc.account.change-email), iff it is
 *  [oldEmail]'s — the twin of `readdressRemembered`, for the same reason. Best-effort, like the cache itself. */
export function readdressSessionVault(oldEmail: string, newEmail: string): void {
  try {
    const raw = sessionStorage.getItem(SS_KEY)
    if (!raw) return
    const v = JSON.parse(raw) as StoredVault
    if (v?.email !== oldEmail.trim().toLowerCase()) return
    sessionStorage.setItem(SS_KEY, JSON.stringify({ ...v, email: newEmail.trim().toLowerCase() }))
  } catch {
    /* storage unavailable — best-effort */
  }
}

/**
 * The vault took a NEW Master Key (a secured account's publish, or a retry that found it committed — cr-secure-the-account
 * decision 12): a session vault this tab holds still seals the RETIRED one, and the CR's invariant keeps no retired
 * private material in the browser either. So the record is re-sealed under a fresh key for the new Master Key, at the
 * address it was cached for — a reload keeps opening this tab with no prompt, now on the published key. A tab with no
 * session vault (the persistent tier, or none) gets none: this never opts a device into a tier. A re-seal that fails
 * drops the record instead (the next reload asks for the password), so the retired key never outlives the swap.
 */
export async function carrySessionVault(masterKey: Uint8Array): Promise<void> {
  let email: string | undefined
  try {
    const raw = sessionStorage.getItem(SS_KEY)
    if (!raw) return
    email = (JSON.parse(raw) as Partial<StoredVault>)?.email
  } catch {
    /* unreadable record — dropped below */
  }
  // Drop first: the retired key's ciphertext and its key K are gone before anything else can fail.
  await dropSessionVault()
  if (email) await cacheSessionVault(email, masterKey)
}

/** Drop the session vault (logout, switching to the persistent tier, or a no-session boot): both the
 *  sessionStorage ciphertext and the IndexedDB key. */
export async function clearSessionVault(): Promise<void> {
  await dropSessionVault()
}
