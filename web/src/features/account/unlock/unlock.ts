// uc.account.unlock (web) — open the account Master Key on-device. The floor path uses the password AND the
// Secret Key (2SKD); a trusted device (uc.account.trust-device) opens the DEVICE wrap with only the password.
// Operator-blind: the client fetches only ciphertext, derives the Unlock KEK locally, and never sends the
// password, the Secret Key, the KEK, or any unwrapped key. Auth ≠ unlock — the fetch carries the bearer token.
import * as v1 from '@veyq/crypto/v1'
import { unlockKekAsync, deviceKekAsync } from '@veyq/crypto/argonWorker'
import { fromB64std } from '@veyq/crypto/base64'
import { accountApi } from '../../../api/client'
import { withRefresh } from '../login/login'
import { setUnlocked, type UnlockOrigin } from '../../../app/vault'
import { setAccount } from '../../../app/account'
import { MasterKeyWrapMethodEnum, type AccountKeyMaterial } from '../../../api/generated/account'
import * as device from '../trustDevice/deviceKey'
import { restoreDeviceKey } from '../rememberMe/deviceKeyWrap'
import { loadSessionVault, clearSessionVault } from '../rememberMe/sessionVault'
import { rememberThisDevice } from '../deviceRegister'
import { deviceId, guessDeviceLabel } from '../deviceIdentity'
import { localWrapId } from '../localWrap'
import { account } from '../../../app/account'
import { session } from '../../../app/session'
import { i18n } from '../../../i18n'

function fetchKeyMaterial(): Promise<AccountKeyMaterial> {
  return withRefresh(() => accountApi().getAccountKeyMaterial())
}

/** Unwrap the identity private key with the opened Master Key and store both in the in-memory vault.
 *
 *  `origin` is what opened it, and every path must state its own honestly — this choke point cannot infer it,
 *  which is why it is a parameter rather than something derived here. It is the input ADR 0060's cold gate
 *  reads (uc.account.rotate-keys §2), and the value that is easiest to get wrong is `none`: the boot restore
 *  and the pairing-grant path open **no wrap at all**, so naming a method for them would be a lie the gate
 *  then trusts. See {@link UnlockOrigin}.
 *
 *  `label` is the name this device was handed by whoever onboarded it (uc.account.onboard-device step 6). It is
 *  threaded through to write moment 1 rather than written afterwards, because a write after the unlock would be
 *  a **fourth** write moment — which en.account.device-register forbids — and would race the fire-and-forget
 *  one for the same key. Every other unlock path passes nothing and keeps the user-agent guess. */
function openVault(
  material: AccountKeyMaterial,
  masterKey: Uint8Array,
  origin: UnlockOrigin,
  label?: string,
): void {
  const identityPrivate = v1.deserializeIdentityPrivate(
    v1.symUnwrap(masterKey, fromB64std(material.wrappedIdentityPrivateKey), 'identity-priv'),
  )
  setUnlocked(masterKey, identityPrivate, origin)
  noteThisDevice(label)
}

/**
 * Write moment 1 of the device register (en.account.device-register): record this device, the family the
 * preceding login created, and the convenience wrap this device already holds — so the sessions view can name
 * it and, for a device that is not this one, offer ADR 0058's escalation.
 *
 * The wrap id is read from this device's **local** trust/remember record, never from the register and never
 * from the server's list. Re-asserting it here on every unlock is what makes the join self-healing: a row lost
 * to a prune, a failed write, or an operator that briefly withheld the wrap comes back on the next unlock. On a
 * first sign-in there is no local record yet — the wrap is enrolled moments later, and write moment 2 records
 * that id at the one instant it exists.
 *
 * Hooked **here** because [openVault] is the one choke point every unlock path passes through — floor, trusted
 * device, remembered device and boot restore alike — and because the register is Master-Key-sealed, so this is
 * the earliest moment it can be written at all. It is emphatically not "one write per login": at login there is
 * no Master Key (auth ≠ unlock).
 *
 * **Fire-and-forget, and that is the design, not laziness.** Unlocking must not wait on, or fail because of, a
 * cosmetic naming write. A write that never lands leaves the affected rows unnamed (uc.account.sessions §E4),
 * and the sessions view repairs it on next open — which is why there is no retry queue here.
 *
 * `session.familyId` is absent on a cold boot restore (no login happened in this page load); the register then
 * records the device without a family, and write moment 2 supplies the family from the server's own
 * current-marker.
 *
 * `label` is present only for a device onboarded by a grant, which was named during that ceremony and carries
 * the name in its envelope (ADR 0059 §9). `rememberThisDevice` writes `prior?.label ?? fallbackLabel`, and an
 * onboarded device has no prior entry — so on that one path the fallback IS the label, and passing the guess
 * there would list the new device under a user-agent string nobody chose.
 */
function noteThisDevice(label?: string): void {
  void localWrapId(account.email)
    .then((wrapId) =>
      rememberThisDevice(
        deviceId(),
        label?.trim() || guessDeviceLabel(i18n.global.t('sessions.unknownDevice')),
        session.familyId,
        wrapId,
      ),
    )
    .catch(() => {
      /* best-effort: an unnamed row is a supported state, not an error to surface */
    })
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
  // The 2SKD floor: password AND Secret Key. `cold` is what ADR 0060's rotation gate is looking for.
  openVault(material, masterKey, 'cold')
}

/**
 * First unlock on a device onboarded by a grant (uc.account.onboard-device step 6): the Master Key was handed
 * over inside the sealed envelope, so there is no factor to derive here — this device has never seen the
 * password or the Secret Key, and never will.
 *
 * It exists because every other export reaches [openVault] through a factor this device does not have. It adds
 * no second way **into** the vault — `openVault` stays the single choke point — only a second way to reach it.
 * `restoreVaultFromCache` is the template: `setAccount` runs **before** the open, because write moment 1 reads
 * `account.email` for the local wrap lookup and would otherwise look it up under an empty address.
 *
 * Requires a live session — the key material fetch is session-gated — which is why the caller adopts first.
 * Throws exactly as the floor path does when the handed-over key does not fit the served material.
 */
export async function unlockWithGrantedMasterKey(
  email: string,
  masterKey: Uint8Array,
  label?: string,
): Promise<void> {
  setAccount(email)
  const material = await fetchKeyMaterial()
  // `none`, not `device`: no wrap was opened here — the Master Key arrived inside the sealed envelope. This is
  // also the exact path a phished grant takes (ADR 0059), so it is the one that must never read as cold.
  openVault(material, masterKey, 'none', label)
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
    // The two tiers differ in what they opened, and only one of them opened anything: the session vault handed
    // back a cached Master Key (no wrap → `none`), the persistent tier unwrapped this device's DEVICE_KEY wrap.
    openVault(material, source.masterKey, sessionVault ? 'none' : 'device')
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
  // A DEVICE wrap mixes the password into a device-held factor — password, but no Secret Key, so not cold.
  openVault(material, masterKey, 'device')
  return true
}
