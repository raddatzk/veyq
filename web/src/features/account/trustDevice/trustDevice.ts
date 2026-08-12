// uc.account.trust-device (web) — enrol / remove a device-local DEVICE wrap so this device unlocks with only
// the password next time. Operator-blind: only the ciphertext wrap + opaque KDF params reach the server; the
// password, the device key, the KEK and the Master Key never leave the device.
import * as v1 from '../../../shared/crypto/v1'
import { b64std } from '../../../shared/base64'
import { accountWrapsApi } from '../../../api/client'
import { AddWrapRequestMethodEnum } from '../../../api/generated/account'
import { withRefresh } from '../login/login'
import * as device from './deviceKey'

export { isTrustSupported, forgetAllTrust } from './deviceKey'

export async function isTrusted(email: string): Promise<boolean> {
  return (await device.loadTrust(email)) !== null
}

/**
 * Trust this device. Invoked **at unlock time** with the just-entered (known-correct) password and the
 * unlocked Master Key: generates a non-extractable device key, derives the device KEK, wraps the Master Key,
 * uploads the DEVICE wrap, and stores the local trust record. **Re-trust replaces** — an existing trust's
 * server wrap is removed first so a device never accumulates duplicate wraps.
 *
 * **Returns the new wrap id**, because this call is the only moment it exists anywhere: every enrolment mints a
 * fresh one (ADR 0005 §9). The caller records the device↔wrap join in the sealed device register — this module
 * deliberately knows nothing about the register, which is what keeps the register off every unlock path.
 */
export async function trustThisDevice(email: string, password: string, masterKey: Uint8Array): Promise<string> {
  const existing = await device.loadTrust(email)
  if (existing) {
    try {
      await withRefresh(() => accountWrapsApi().deleteAccountWrap({ id: existing.wrapId }))
    } catch {
      /* best-effort: a stale server wrap is harmless ciphertext; we replace the local record regardless */
    }
    await device.clearTrust(email)
  }

  const cryptoKey = await device.generateDeviceKey()
  const deviceFactor = await device.deviceFactor(cryptoKey)
  const salt = v1.randomBytes(16)
  const kek = v1.kekDevicePassword(deviceFactor, password, salt)
  const wrap = v1.symWrap(kek, masterKey, 'master-key', v1.randomBytes(12))
  const kdfParams = b64std(v1.encodeKdfParams(v1.ARGON, salt))

  const result = await withRefresh(() =>
    accountWrapsApi().addAccountWrap({
      addWrapRequest: { method: AddWrapRequestMethodEnum.Device, wrappedKey: b64std(wrap), kdfParams },
    }),
  )
  try {
    await device.saveTrust(email, { cryptoKey, wrapId: result.id })
    return result.id
  } catch (e) {
    // The server wrap landed but the local key didn't persist — roll the wrap back so it isn't an orphan,
    // then signal the failure (the caller tells the user trust didn't take, rather than silently misleading).
    try {
      await withRefresh(() => accountWrapsApi().deleteAccountWrap({ id: result.id }))
    } catch {
      /* best-effort rollback */
    }
    throw e
  }
}

/** Untrust this device: remove the server wrap (best-effort) and the local key. */
export async function untrustThisDevice(email: string): Promise<void> {
  const existing = await device.loadTrust(email)
  if (!existing) return
  try {
    await withRefresh(() => accountWrapsApi().deleteAccountWrap({ id: existing.wrapId }))
  } catch {
    /* best-effort */
  }
  await device.clearTrust(email)
}
