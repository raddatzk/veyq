// uc.account.remember-this-device / auto-unlock (web "remember me", cr-0054). The password-less DEVICE_KEY
// wrap: a NON-EXTRACTABLE AES-GCM device key lives in IndexedDB and seals the Master Key, but the **ciphertext**
// is uploaded to the blind server as a DEVICE_KEY MasterKeyWrap — not parked locally (the cr-0052 model). So
// the remembered vault is portable + server-revocable; only the device key (never its bytes — XSS can decrypt
// while it runs but cannot carry the key off-device) and the wrap id stay local. Boot: fetch key material →
// find the DEVICE_KEY wrap → decrypt with the local key → Master Key, no password (ADR 0005 §18-20).
//
// This is the platform + server-wrap half; the wrap KEK is the device key alone (no KDF), so the conformance-
// pinned v1 suite isn't involved — WebCrypto seals directly with a key whose bytes never enter JS. The 12-byte
// AEAD iv rides the wrap's kdfParams (opaque to the server); the ciphertext rides wrappedKey.
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { idbRun } from '../../../shared/idb'
import { accountWrapsApi } from '../../../api/client'
import { AddWrapRequestMethodEnum, MasterKeyWrapMethodEnum, type AccountKeyMaterial } from '../../../api/generated/account'
import { withRefresh } from '../../../app/sessionRefresh'
import { REMEMBER_KEY, dropRememberKey } from '../../../app/convenienceTiers'

const { db: DB_NAME, store: STORE, record: RECORD_KEY } = REMEMBER_KEY

/** This device's remember-me state: the email (the server wrap is operator-blind, so boot has no email
 *  otherwise), the non-extractable AES-GCM key that opens the wrap, and the server wrap's id (the join key
 *  used to find this device's own DEVICE_KEY wrap in key material, and to replace/clear it). The CryptoKey is
 *  stored as a structured clone — still non-extractable after the IndexedDB round-trip. One remembered account
 *  per browser profile. */
type RememberRecord = { email: string; key: CryptoKey; wrapId: string }

export function isRememberSupported(): boolean {
  return typeof indexedDB !== 'undefined' && typeof crypto !== 'undefined' && !!crypto.subtle
}

const run = <T>(mode: IDBTransactionMode, op: (store: IDBObjectStore) => IDBRequest<T>): Promise<T> =>
  idbRun(DB_NAME, STORE, mode, op)

function loadRecord(): Promise<RememberRecord | null> {
  return run<RememberRecord | undefined>('readonly', (s) => s.get(RECORD_KEY)).then((r) => r ?? null)
}

/**
 * Enrol this device for password-less remember-me. Generates a non-extractable AES-GCM key, seals the
 * (in-memory) Master Key under it, uploads the ciphertext as a DEVICE_KEY wrap, and stores the key + wrap id
 * locally. **Re-enrol replaces** (one DEVICE_KEY wrap per device, ADR 0005 §9): the new wrap is uploaded +
 * recorded FIRST, then the prior server wrap is dropped — so a transient upload failure leaves the prior wrap
 * intact rather than the device with no remember-me wrap. On a local-save failure the just-uploaded wrap is
 * rolled back so it isn't an orphan. (The Secret Key is never involved — the caller holds the unlocked Master
 * Key.)
 *
 * **Returns the new wrap id**, because this call is the only moment it exists anywhere: every enrolment mints a
 * fresh one (ADR 0005 §9). The caller records the device↔wrap join in the sealed device register — this module
 * deliberately knows nothing about the register, which is what keeps the register off every unlock path.
 */
/**
 * The purpose this seal authenticates, as AES-GCM `additionalData`. Outside the typed seal union on purpose
 * (ADR 0072 Decision 2): the key is a non-extractable per-device WebCrypto `CryptoKey` that `@noble` cannot take,
 * unique to this one wrap, so the key is the object binding and the purpose is all that is left to bind.
 */
const DEVICE_KEY_WRAP_AD = new TextEncoder().encode('veyq/v1/device-key-wrap')

export async function enrolDeviceKey(email: string, masterKey: Uint8Array): Promise<string> {
  const prior = await loadRecord()

  const key = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt'])
  const iv = crypto.getRandomValues(new Uint8Array(12))
  const ciphertext = await crypto.subtle.encrypt({ name: 'AES-GCM', iv, additionalData: DEVICE_KEY_WRAP_AD }, key, masterKey as BufferSource)

  const result = await withRefresh(() =>
    accountWrapsApi().addAccountWrap({
      addWrapRequest: {
        method: AddWrapRequestMethodEnum.DeviceKey,
        wrappedKey: b64std(new Uint8Array(ciphertext)),
        kdfParams: b64std(iv), // no KDF for DEVICE_KEY — this field carries only the AEAD iv
      },
    }),
  )
  const record: RememberRecord = { email: email.trim().toLowerCase(), key, wrapId: result.id }
  try {
    await run('readwrite', (s) => s.put(record, RECORD_KEY))
  } catch (e) {
    // The server wrap landed but the local key didn't persist — roll the wrap back so it isn't an orphan,
    // then signal the failure (the caller falls back rather than silently leaving a half-enrolment).
    try {
      await withRefresh(() => accountWrapsApi().deleteAccountWrap({ id: result.id }))
    } catch {
      /* best-effort rollback */
    }
    throw e
  }

  // The new wrap is live and recorded — now drop the prior one (best-effort; a stale server wrap is harmless
  // ciphertext, and the local record already points at the new wrap).
  if (prior && prior.wrapId !== result.id) {
    try {
      await withRefresh(() => accountWrapsApi().deleteAccountWrap({ id: prior.wrapId }))
    } catch {
      /* best-effort */
    }
  }
  return result.id
}

/**
 * The wrap id this device holds for `email` under remember-me, or null. Read from the **local** record, which is
 * what makes it a fact this client can verify on its own — the register is never consulted for it, and the
 * server's list is never treated as authority over it (ADR 0058 §5).
 */
export async function rememberedWrapId(email: string): Promise<string | null> {
  const record = await loadRecord()
  if (!record || record.email !== email.trim().toLowerCase()) return null
  return record.wrapId
}

/**
 * Boot restore (no prompt): given the already-fetched key material, decrypt the Master Key from this device's
 * DEVICE_KEY wrap with the local key. Returns null when this device isn't enrolled, the matching wrap is gone
 * server-side (orphan — don't destroy the local key on a single server response, mirrors unlockWithDevice), or
 * the ciphertext won't open under the local key (tampered/rotated — that local record is dropped). The caller
 * holds the live session (boot revives it from the cookie first) and does the openVault.
 */
export async function restoreDeviceKey(
  material: AccountKeyMaterial,
): Promise<{ email: string; masterKey: Uint8Array } | null> {
  const record = await loadRecord()
  if (!record) return null
  const wrap = material.wraps.find(
    (w) => w.method === MasterKeyWrapMethodEnum.DeviceKey && w.id === record.wrapId,
  )
  if (!wrap?.kdfParams) return null // wrap withdrawn server-side — fall back, but keep the (harmless) local key
  try {
    const plain = await crypto.subtle.decrypt(
      { name: 'AES-GCM', iv: fromB64std(wrap.kdfParams) as BufferSource, additionalData: DEVICE_KEY_WRAP_AD },
      record.key,
      fromB64std(wrap.wrappedKey) as BufferSource,
    )
    return { email: record.email, masterKey: new Uint8Array(plain) }
  } catch {
    await run('readwrite', (s) => s.delete(RECORD_KEY)) // can't open it under our key → stale record, drop it
    return null
  }
}

/**
 * Forget remember-me on this device. Removes the server DEVICE_KEY wrap (best-effort — needs a live token, so
 * call BEFORE revoking the session) and drops the local key. Pass `deleteServerWrap=false` when there is no
 * usable session (e.g. a boot with no cookie) to drop only the local record.
 */
export async function clearDeviceKey(deleteServerWrap = true): Promise<void> {
  if (deleteServerWrap) {
    const record = await loadRecord()
    if (record) {
      try {
        await withRefresh(() => accountWrapsApi().deleteAccountWrap({ id: record.wrapId }))
      } catch {
        /* best-effort: orphan ciphertext is harmless; the local key is what makes it un-openable */
      }
    }
  }
  await dropRememberKey()
}

/** Is this device currently enrolled for password-less remember-me? */
export async function isRemembered(): Promise<boolean> {
  return (await loadRecord()) !== null
}
