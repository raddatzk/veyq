// Cold account birth (operator-blind, Model B) — the one place the client mints a new account's key
// material: an identity keypair, the Master Key with a PASSWORD floor + RECOVERY wrap, the Ed25519 auth
// keypair (ADR 0003, derived from password + the account's auth salt), and the Secret-Key proof key's public
// half (ADR 0060 Decision 2, derived from the Secret Key alone). Shared by first-run setup (the Server
// Owner) and invite-gated registration (a tenant owner, cr-0010). The raw password is NEVER sent — only the
// derived public key + ciphertext leave the device.
import * as v1 from './v1'
import type { Identity } from './v1'
import { b64std } from './base64'
import { deriveAuthKeypairAsync, recoveryKekAsync, unlockKekAsync } from './argonWorker'

const ARGON = v1.ARGON

/** The operator-blind wire material for a new account (suiteId + public key + ciphertext wraps). */
export type AccountMaterial = {
  suiteId: 'v1'
  identityPublicKey: string
  wrappedIdentityPrivateKey: string
  authPublicKey: string
  masterKeyWraps: { method: 'PASSWORD' | 'RECOVERY'; wrappedKey: string; kdfParams: string }[]
  /** The Secret-Key proof key (ADR 0060 Decision 2). Rotation, change-password and recovery must each carry a
   *  signature under it, so it is registered here, at the one birth both clients share. */
  secretKeyProofPublicKey: string
}

export type BuiltAccount = {
  /** The freshly born identity — keep on-device (its public half seals the owner's first scope grant). */
  identity: Identity
  material: AccountMaterial
  emergencyKit: { secretKey: string }
}

/**
 * Mint a new account's material. `email` feeds only the auth-salt derivation (ADR 0003); it is not part of
 * the returned material (the caller supplies it to the server — at setup the owner types it, at invite
 * acceptance it comes from the invite). The Secret Key in the Emergency Kit + the password (never stored)
 * are both needed to open the PASSWORD wrap (2SKD).
 *
 * `opts.secretKey` **re-uses an existing Emergency Kit** instead of minting a fresh Secret Key. Everything
 * else is still born fresh (identity, Master Key, every salt and nonce), so the account is new — only the
 * kit the human already wrote down stays valid. Its **one** supported caller is the local dev bootstrap
 * (`src/web/scripts/bootstrap-user.mts`), which re-seeds the same throwaway principal after a `down -v` and would
 * otherwise invalidate the Secret Key in `.run/.env` on every run. Never pass it on a real registration path:
 * a Secret Key must be 32 bytes of fresh entropy per account, and reusing one links the accounts' PASSWORD
 * KEKs to a single kit — if it leaks, it opens all of them. Rejected unless it is exactly 32 bytes.
 */
export async function buildAccount(
  email: string,
  password: string,
  instanceSalt: Uint8Array,
  opts?: { secretKey?: Uint8Array },
): Promise<BuiltAccount> {
  if (opts?.secretKey && opts.secretKey.length !== 32) throw new Error('buildAccount: secretKey must be 32 bytes')
  const id = v1.generateIdentity()
  const masterKey = v1.randomBytes(32)
  const wrappedPriv = v1.seal({ purpose: 'identity-priv' }, masterKey, v1.serializeIdentityPrivate(id))

  const secretKey = opts?.secretKey ?? v1.randomBytes(32)
  const salt = v1.randomBytes(16)
  // The three Argon2id derivations go through the worker door, one after another on the one worker (a pool to run
  // them side by side is not worth it for a once-per-account birth). Where no Worker exists (node: the seed scripts,
  // vitest) the door derives on the calling thread, byte-identically.
  const masterWrap = v1.seal({ purpose: 'master-key' }, await unlockKekAsync(secretKey, password, salt, ARGON), masterKey)

  const recSalt = v1.randomBytes(16)
  const recWrap = v1.seal({ purpose: 'master-key' }, await recoveryKekAsync(secretKey, recSalt, ARGON), masterKey)

  const auth = await deriveAuthKeypairAsync(password, v1.deriveAuthSalt(instanceSalt, email), ARGON)

  return {
    identity: id,
    emergencyKit: { secretKey: b64std(secretKey) },
    material: {
      suiteId: 'v1',
      identityPublicKey: b64std(v1.serializeIdentityPublic(id.pub)),
      wrappedIdentityPrivateKey: b64std(wrappedPriv),
      authPublicKey: b64std(auth.publicKey),
      masterKeyWraps: [
        { method: 'PASSWORD', wrappedKey: b64std(masterWrap), kdfParams: b64std(v1.encodeKdfParams(ARGON, salt)) },
        { method: 'RECOVERY', wrappedKey: b64std(recWrap), kdfParams: b64std(v1.encodeKdfParams(ARGON, recSalt)) },
      ],
      secretKeyProofPublicKey: b64std(v1.deriveSecretKeyProofKeypair(secretKey).publicKey),
    },
  }
}
