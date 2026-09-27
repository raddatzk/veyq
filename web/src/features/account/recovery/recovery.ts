// uc.account.recover-access (web) — reset a forgotten password without losing anything (ADR 0054).
//
// The whole flow is two factors that meet on the device: a mailed token authorizes the server to hand over the
// withheld RECOVERY wrap, and the Secret Key from the Emergency Kit is what actually opens it. The server never
// sees the Secret Key, the Master Key or either password — it receives a signature under the account's
// Secret-Key proof key (ADR 0054 Decision 1), which it verifies against a public key, and ciphertext it cannot
// read.
//
// Nothing here re-encrypts content: the Master Key is unwrapped and re-wrapped under a new KEK, so every scope
// key, every sealed blob and the identity keypair are untouched. That is also why the Emergency Kit stays valid
// afterwards — the Secret Key does not change.
import * as v1 from '@veyq/crypto/v1'
import { deriveAuthKeypairAsync, recoveryKekAsync, unlockKekAsync } from '@veyq/crypto/argonWorker'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { loginApi, recoveryApi } from '../../../api/client'
import type { RecoveryMaterial } from '../../../api/generated/auth'

const ARGON = v1.ARGON

/** The opened account: the Master Key the released wrap yielded — proof, on this device, that the kit is right. */
export type OpenedRecovery = { masterKey: Uint8Array }

/**
 * Ask for a recovery link. Resolves the same way for a registered and an unknown address — the endpoint is
 * deliberately not an oracle, so there is nothing here to branch on and nothing to report back beyond "we tried".
 */
export async function requestRecovery(email: string): Promise<void> {
  await recoveryApi().requestPasswordRecovery({ recoveryRequest: { email } })
}

/**
 * Exchange the token from the link for the withheld RECOVERY wrap.
 *
 * Throws when the token is unknown, expired or already spent — one indistinguishable case, by design. The token
 * is *not* consumed by this call, so a wrong Secret Key at the next step costs nothing but a retry.
 */
export async function releaseRecoveryMaterial(token: string): Promise<RecoveryMaterial> {
  return recoveryApi().releaseRecoveryMaterial({ recoveryTokenRequest: { token } })
}

/**
 * Open the released wrap with the Secret Key alone — no password, which is the whole point of this wrap.
 *
 * Derives with the salt **carried by the wrap**, and only under a profile this client writes: a served cost
 * profile is refused before deriving (`v1.KdfProfileRefused`, ADR 0017 rule 5). A wrong Secret Key fails here, on
 * the device, as an AEAD error — nothing is sent and the token stays live.
 */
export async function openWithSecretKey(material: RecoveryMaterial, secretKeyB64: string): Promise<OpenedRecovery> {
  const secretKey = fromB64std(secretKeyB64)
  const { params, salt } = v1.decodeKdfParams(fromB64std(material.kdfParams))
  const kek = await recoveryKekAsync(secretKey, salt, params)
  const masterKey = v1.symUnwrap(kek, fromB64std(material.wrappedKey), 'master-key')
  return { masterKey }
}

/**
 * Set the new password.
 *
 * Two things are derived from it, and they must land together or the account is stuck: the **auth keypair**
 * (what logs in, ADR 0003) and the **PASSWORD wrap's KEK** (what unlocks). The server applies both in one
 * transaction; this function's job is to make sure it is handed a consistent pair.
 *
 * The address comes from the released material, not from the person: they reached this point by reading a mail
 * sent to it, so asking them to type it again adds a step that can only go wrong. The instance salt comes from a
 * fresh login challenge — the client has no session to read it from, and the endpoint serves it to anyone, which
 * is safe: it is a salt, not a secret.
 */
export async function completeRecovery(
  token: string,
  material: RecoveryMaterial,
  opened: OpenedRecovery,
  secretKeyB64: string,
  newPassword: string,
): Promise<void> {
  const secretKey = fromB64std(secretKeyB64)

  // A fresh salt + nonce for the new floor. Re-using the old wrap's salt would leak that the password changed
  // to anyone diffing two dumps, and costs nothing to avoid.
  const salt = v1.randomBytes(16)
  // The 2SKD floor takes the password; the recovery KEK deliberately does not. Different function, on purpose.
  const kek = await unlockKekAsync(secretKey, newPassword, salt, ARGON)
  const wrapped = v1.symWrap(kek, opened.masterKey, 'master-key', v1.randomBytes(12))

  const challenge = await loginApi().requestLoginChallenge({ loginChallengeRequest: { email: material.email } })
  const authSalt = v1.deriveAuthSalt(fromB64std(challenge.instanceSalt), material.email)
  const auth = await deriveAuthKeypairAsync(newPassword, authSalt, ARGON)
  const kdfParams = v1.encodeKdfParams(ARGON, salt)

  // The second factor, as the server checks it: a signature under the key only the Secret Key derives, over the
  // token being spent and exactly what this reset installs.
  const proof = v1.deriveSecretKeyProofKeypair(secretKey)
  const secretKeyProofSignature = b64std(
    v1.secretKeyProofSign(
      proof.privateKey,
      v1.recoveryProofStatement({ token, authPublicKey: auth.publicKey, passwordWrap: { wrappedKey: wrapped, kdfParams } }),
    ),
  )
  proof.privateKey.fill(0)

  await recoveryApi().completePasswordRecovery({
    recoveryCompleteRequest: {
      token,
      secretKeyProofSignature,
      wrappedKey: b64std(wrapped),
      kdfParams: b64std(kdfParams),
      authPublicKey: b64std(auth.publicKey),
    },
  })
}
