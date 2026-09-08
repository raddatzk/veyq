// uc.account.change-password (web) — set a new password from a live, unlocked session, without going through
// the mailed recovery flow. One server-side mechanism serves both entry points (ADR 0003's auth keypair, the
// same PASSWORD-wrap KEK recovery uses); this file's only decision is whether an attestation is produced,
// which is exactly the presence of `currentPassword`.
//
// The cold-credential gate — which entry point a caller may reach — lives in the SCREEN, not here (ADR 0060
// Decision 2's precedent, the same one rotation follows): the server cannot tell "deliberate" from "forgotten"
// itself and must not try, so there is nothing to prove client-side beyond producing the attestation when a
// current password is offered. A wrong current password simply produces a signature the server rejects
// (403 attestation_failed) — nothing here needs to anticipate that.
import * as v1 from '@veyq/crypto/v1'
import { deriveAuthKeypairAsync, unlockKekAsync } from '@veyq/crypto/argonWorker'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { loginApi, accountPasswordApi } from '../../../api/client'
import { withRefresh } from '../login/login'
import { masterKey } from '../../../app/vault'
import { account } from '../../../app/account'

const ARGON = v1.ARGON

/** Thrown before any write. This flow needs the vault's current Master Key to build the new floor — a locked
 *  device has none, so there is nothing to re-wrap. */
export class ChangePasswordRefused extends Error {
  constructor() {
    super('change password refused: vaultLocked')
    this.name = 'ChangePasswordRefused'
  }
}

/**
 * Change the password.
 *
 * `currentPassword` present → the **deliberate** path: derive the OLD auth keypair from it and sign the new
 * auth key's raw bytes — proof the caller still controls the password being replaced (not a server nonce; the
 * attestation signs what is being installed). Absent → the **forgotten** path: no attestation, and none is
 * attempted server-side either.
 *
 * The new floor wraps the vault's *current* Master Key (already unlocked — this is a live session, not a
 * recovery), the same way `completeRecovery` wraps a just-opened one: a fresh salt, `unlockKekAsync(secretKey,
 * newPassword, salt)`, `symWrap`. The instance salt comes from a fresh login challenge, read only for that
 * public constant — this session has no cached auth salt to reuse.
 */
export async function changePassword(newPassword: string, secretKeyB64: string, currentPassword?: string): Promise<void> {
  const mk = masterKey()
  if (!mk) throw new ChangePasswordRefused()
  const secretKey = fromB64std(secretKeyB64.trim())

  try {
    const challenge = await loginApi().requestLoginChallenge({ loginChallengeRequest: { email: account.email } })
    const authSalt = v1.deriveAuthSalt(fromB64std(challenge.instanceSalt), account.email)

    const newAuth = await deriveAuthKeypairAsync(newPassword, authSalt, ARGON)
    let attestationSignature: string | undefined
    if (currentPassword) {
      const oldAuth = await deriveAuthKeypairAsync(currentPassword, authSalt, ARGON)
      attestationSignature = b64std(v1.authSign(oldAuth.privateKey, newAuth.publicKey))
    }

    const salt = v1.randomBytes(16)
    const kek = await unlockKekAsync(secretKey, newPassword, salt, ARGON)
    const wrapped = v1.symWrap(kek, mk, 'master-key', v1.randomBytes(12))

    await withRefresh(() =>
      accountPasswordApi().changePassword({
        changePasswordRequest: {
          passwordWrap: { wrappedKey: b64std(wrapped), kdfParams: b64std(v1.encodeKdfParams(ARGON, salt)) },
          authPublicKey: b64std(newAuth.publicKey),
          attestationSignature,
        },
      }),
    )
  } finally {
    mk.fill(0)
    secretKey.fill(0)
  }
}
