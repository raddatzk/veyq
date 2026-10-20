// The LOCAL halves of this device's password-less convenience tiers (uc.account.remember-this-device): where the
// session vault and the remember-me key live, and how to drop them. The features that enrol and open them keep
// that logic; only the storage names and the drop live here, in `app/`, because the session ending
// (`sessionEnd.ts`) has to drop them and imports no feature (ADR 0070 § Decision 1). The remember-me module
// calls `withRefresh`, so reaching it from the refresh path would close a cycle.
//
// What is NOT here is deliberate: the password device-trust wrap (`veyq-trust`) and the device id stay on the
// device after a session ends (uc.account.sign-out § What stays on the device).
import { idbRun } from '../shared/idb'

/** The session vault ("off" tier): the Master Key's ciphertext in `sessionStorage`, its key K in IndexedDB. */
export const SESSION_VAULT = { db: 'veyq-session', store: 'key', record: 'current', storageKey: 'veyq.session-vault' } as const

/** The remember-me key ("on" tier): the device key + server wrap id in IndexedDB. Its server wrap is not here. */
export const REMEMBER_KEY = { db: 'veyq-remember', store: 'device-key', record: 'current' } as const

/** DEV-only (`devAutoUnlock.ts`): the persisted `?devlogin` opt-in. It is a convenience tier too — left set, the
 *  sign-in page a session ends on would log straight back in. Never set in a production build. */
export const DEV_AUTOLOGIN_FLAG = 'veyq.dev-autologin'

/** Drop the session vault: the `sessionStorage` ciphertext and the IndexedDB key. Best-effort, never throws. */
export async function dropSessionVault(): Promise<void> {
  try {
    sessionStorage.removeItem(SESSION_VAULT.storageKey)
  } catch {
    /* storage denied — nothing to drop */
  }
  await idbRun(SESSION_VAULT.db, SESSION_VAULT.store, 'readwrite', (s) => s.delete(SESSION_VAULT.record)).catch(
    () => undefined,
  )
}

/** Drop the local remember-me key. Without it the server wrap can no longer be opened. Throws on a storage
 *  failure, so the caller decides whether that matters. */
export async function dropRememberKey(): Promise<void> {
  await idbRun(REMEMBER_KEY.db, REMEMBER_KEY.store, 'readwrite', (s) => s.delete(REMEMBER_KEY.record))
}

/** Drop every local convenience tier. Best-effort, never throws: an ending must not stop at a storage error. */
export async function dropConvenienceTiers(): Promise<void> {
  await dropSessionVault()
  await dropRememberKey().catch(() => undefined)
  try {
    localStorage.removeItem(DEV_AUTOLOGIN_FLAG)
  } catch {
    /* storage denied — nothing to drop */
  }
}
