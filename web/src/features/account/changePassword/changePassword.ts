// uc.account.change-password (web) — set a new password from a live, unlocked session, without going through
// the mailed recovery flow. One mechanism, one entry point: the caller proves the credential they are
// replacing, then it is replaced (ADR 0003's auth keypair, the same PASSWORD-wrap KEK recovery uses).
//
// **This screen mints a cold credential, so it is gated exactly as rotation is** (ADR 0060 Decision 2). The
// proof has two halves and they are checked in two places, because only one of them is checkable by a blind
// server: the attestation below — an Ed25519 signature the server verifies against the stored
// `auth_public_key` — and `proveColdCredential`, which opens the account's current `PASSWORD` floor with what
// was typed *now*. Without the second half a `DEVICE_KEY` holder wraps the victim's Master Key under their
// OWN Secret Key and walks away with a cold credential; that was this flow's shipped defect, and the "I don't
// remember my current password" entry point was its door. There is no such entry point any more: a forgotten
// password goes through uc.account.recover-access, which proves address control instead.
import * as v1 from '@veyq/crypto/v1'
import { deriveAuthKeypairAsync, unlockKekAsync } from '@veyq/crypto/argonWorker'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { loginApi, accountPasswordApi } from '../../../api/client'
import { withRefresh } from '../../../app/sessionRefresh'
import { identityPrivate, setUnlocked } from '../../../app/vault'
import { account } from '../../../app/account'
import { ColdCredentialRefused, proveColdCredential } from '../coldCredential'
import type { ColdCredentialRefusal } from '../coldCredential'

const ARGON = v1.ARGON

/** Thrown before any write. Each reason is one of the shared proof's — this flow adds none of its own. */
export class ChangePasswordRefused extends Error {
  constructor(public readonly reason: ColdCredentialRefusal) {
    super(`change password refused: ${reason}`)
    this.name = 'ChangePasswordRefused'
  }
}

/**
 * Change the password.
 *
 * `currentPassword` and the Secret Key are proven **together, against the stored floor**, before anything is
 * derived or sent — the same live proof rotation runs, in the same shared implementation. A caller who fails
 * it has written nothing. From the OLD password the attestation is then derived: a signature over the new
 * auth key's raw bytes, proof to the server that the caller controls the password being replaced (not a
 * server nonce; the attestation signs what is being installed).
 *
 * The new floor wraps the vault's *current* Master Key — the very copy the proof returned, so what is
 * re-wrapped is what was just proven — with a fresh salt, `unlockKekAsync(secretKey, newPassword, salt)`,
 * `symWrap`. The instance salt comes from a fresh login challenge, read only for that public constant; this
 * session has no cached auth salt to reuse.
 *
 * Afterwards the session is marked `cold`: it has just proven password + Secret Key, whatever wrap originally
 * opened it — the same statement of fact rotation makes for the same reason.
 */
export async function changePassword(newPassword: string, secretKeyB64: string, currentPassword: string): Promise<void> {
  const mk = await proveColdCredential(currentPassword, secretKeyB64).catch((e: unknown) => {
    if (e instanceof ColdCredentialRefused) throw new ChangePasswordRefused(e.reason)
    throw e
  })
  const secretKey = fromB64std(secretKeyB64.trim())

  try {
    const challenge = await loginApi().requestLoginChallenge({ loginChallengeRequest: { email: account.email } })
    const authSalt = v1.deriveAuthSalt(fromB64std(challenge.instanceSalt), account.email)

    const newAuth = await deriveAuthKeypairAsync(newPassword, authSalt, ARGON)
    const oldAuth = await deriveAuthKeypairAsync(currentPassword, authSalt, ARGON)
    const attestationSignature = b64std(v1.authSign(oldAuth.privateKey, newAuth.publicKey))

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

    // The Master Key is unchanged — only its wrap is — so the vault needs no new material; what changed is
    // what this session has PROVEN. A device-opened session that just supplied password + Secret Key is cold
    // from here on, and a later gate reading `unlockOrigin()` must see that rather than the stale `device`.
    const priv = identityPrivate()
    if (priv) setUnlocked(mk.slice(), priv, 'cold')
  } finally {
    mk.fill(0)
    secretKey.fill(0)
  }
}
