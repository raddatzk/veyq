// uc.account.unlock (web) — open the account Master Key on-device. The floor path uses the password AND the
// Secret Key (2SKD); a trusted device (uc.account.trust-device) opens the DEVICE wrap with only the password.
// Operator-blind: the client fetches only ciphertext, derives the Unlock KEK locally, and never sends the
// password, the Secret Key, the KEK, or any unwrapped key. Auth ≠ unlock — the fetch carries the bearer token.
import * as v1 from '../../../shared/crypto/v1'
import { unlockKekAsync, deviceKekAsync } from '../../../shared/crypto/argonWorker'
import { fromB64std } from '../../../shared/base64'
import { accountApi } from '../../../api/client'
import { withRefresh } from '../login/login'
import { setUnlocked } from '../../../app/vault'
import { setAccount } from '../../../app/account'
import { MasterKeyWrapMethodEnum, type AccountKeyMaterial } from '../../../api/generated/account'
import * as device from '../trustDevice/deviceKey'
import { restoreDeviceKey } from '../rememberMe/deviceKeyWrap'
import { loadSessionVault, clearSessionVault } from '../rememberMe/sessionVault'

function fetchKeyMaterial(): Promise<AccountKeyMaterial> {
  return withRefresh(() => accountApi().getAccountKeyMaterial())
}

/** Unwrap the identity private key with the opened Master Key and store both in the in-memory vault. */
function openVault(material: AccountKeyMaterial, masterKey: Uint8Array): void {
  const identityPrivate = v1.deserializeIdentityPrivate(
    v1.symUnwrap(masterKey, fromB64std(material.wrappedIdentityPrivateKey), 'identity-priv'),
  )
  setUnlocked(masterKey, identityPrivate)
}

/**
 * Unlock with the password + Secret Key (PASSWORD floor). Fetches the caller's key material (refreshing the
 * session on a 401), derives the Unlock KEK, AEAD-unwraps the Master Key, then the identity private key.
 * Throws on a wrong password / Secret Key (AEAD tag mismatch) or a missing PASSWORD wrap — one generic
 * failure, server uninvolved.
 */
export async function unlockMasterKey(password: string, secretKeyB64: string): Promise<void> {
  const material = await fetchKeyMaterial()
  const passwordWrap = material.wraps.find((w) => w.method === MasterKeyWrapMethodEnum.Password)
  if (!passwordWrap?.kdfParams) throw new Error('unlock_unavailable') // E2 — no floor wrap
  const secretKey = fromB64std(secretKeyB64.trim())
  const { params, salt } = v1.decodeKdfParams(fromB64std(passwordWrap.kdfParams))
  const kek = await unlockKekAsync(secretKey, password, salt, params)
  const masterKey = v1.symUnwrap(kek, fromB64std(passwordWrap.wrappedKey), 'master-key')
  openVault(material, masterKey)
}

/**
 * Unlock on a trusted device with only the password (DEVICE wrap). Returns **false** when this device can't
 * use the DEVICE method — no local device key, or the matching wrap is gone server-side (orphan trust,
 * cleared here) — so the caller falls back to the PASSWORD floor. **Throws** on a wrong password (AEAD tag
 * mismatch) → the same generic failure as the floor path.
 */
/**
 * Boot path (web, cr-0054/cr-0055): re-open the vault with **no prompt** after the session cookie revives the
 * session. Fetches the session-gated key material, then opens the Master Key from whichever device tier is
 * present: the **session vault** (`sessionStorage`, the "off" tier — survives reloads, gone on browser close) or
 * the persistent **DEVICE_KEY** wrap (the "on" tier — survives a restart). Returns false (→ the user lands on
 * the floor) when neither is usable. Requires a live session (the caller revives it from the cookie first).
 */
export async function restoreVaultFromCache(): Promise<boolean> {
  const material = await fetchKeyMaterial()
  // Open the Master Key from whichever device tier is present: the session vault (sessionStorage, the "off"
  // tier) or the persistent DEVICE_KEY wrap (the "on" tier).
  const sessionVault = await loadSessionVault()
  const source = sessionVault ?? (await restoreDeviceKey(material))
  if (!source) return false
  setAccount(source.email)
  try {
    openVault(material, source.masterKey)
    return true
  } catch {
    // The cached Master Key no longer fits the served key material (e.g. rotated) — don't keep retrying the
    // same broken vault on every reload. Drop the session-vault cache and fall back to the floor. The DEVICE_KEY
    // local key is intentionally left (anti-withholding, like unlockWithDevice/restoreDeviceKey); a genuine
    // rotation re-enrols it on the next floor unlock.
    if (sessionVault) await clearSessionVault()
    return false
  }
}

export async function unlockWithDevice(email: string, password: string): Promise<boolean> {
  const trust = await device.loadTrust(email)
  if (!trust) return false
  const material = await fetchKeyMaterial()
  // Match this device's own wrap by its server **id** (now exposed in key material) — the authoritative,
  // unique join, not a salt-equality guess.
  const wrap = material.wraps.find(
    (w) => w.method === MasterKeyWrapMethodEnum.Device && w.id === trust.wrapId,
  )
  // No matching wrap → fall back to the floor, but DON'T destroy the local key on a single server response:
  // the operator is blind but could withhold the wrap to force every trusted device to wipe its key. A stale
  // local key is harmless (it opens nothing without the wrap + password); the user clears it via explicit
  // untrust or a re-trust replaces it.
  if (!wrap?.kdfParams) return false
  const deviceFactor = await device.deviceFactor(trust.cryptoKey)
  const { params, salt } = v1.decodeKdfParams(fromB64std(wrap.kdfParams))
  const kek = await deviceKekAsync(deviceFactor, password, salt, params)
  const masterKey = v1.symUnwrap(kek, fromB64std(wrap.wrappedKey), 'master-key')
  openVault(material, masterKey)
  return true
}
