// Cold account birth (operator-blind, Model B) — the one place the client mints a new account's key
// material: an identity keypair, the Master Key with a PASSWORD floor + RECOVERY wrap, and the Ed25519 auth
// keypair (ADR 0003, derived from password + the account's auth salt). Shared by first-run setup (the Server
// Owner) and invite-gated registration (a tenant owner, cr-0010). The raw password is NEVER sent — only the
// derived public key + ciphertext leave the device.
import * as v1 from '../../../shared/crypto/v1'
import type { Identity } from '../../../shared/crypto/v1'
import { b64std } from '../../../shared/base64'

const ARGON = v1.ARGON

/** The operator-blind wire material for a new account (suiteId + public key + ciphertext wraps). */
export type AccountMaterial = {
  suiteId: 'v1'
  identityPublicKey: string
  wrappedIdentityPrivateKey: string
  authPublicKey: string
  masterKeyWraps: { method: 'PASSWORD' | 'RECOVERY'; wrappedKey: string; kdfParams: string }[]
  recoveryMasterKeyTag: string
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
 */
export function buildAccount(email: string, password: string, instanceSalt: Uint8Array): BuiltAccount {
  const id = v1.generateIdentity()
  const masterKey = v1.randomBytes(32)
  const wrappedPriv = v1.symWrap(masterKey, v1.serializeIdentityPrivate(id), 'identity-priv', v1.randomBytes(12))

  const secretKey = v1.randomBytes(32)
  const salt = v1.randomBytes(16)
  const masterWrap = v1.symWrap(v1.kekPasswordSecret(secretKey, password, salt, ARGON), masterKey, 'master-key', v1.randomBytes(12))

  const recSalt = v1.randomBytes(16)
  const recWrap = v1.symWrap(v1.kekRecovery(secretKey, recSalt, ARGON), masterKey, 'master-key', v1.randomBytes(12))

  const auth = v1.deriveAuthKeypair(password, v1.deriveAuthSalt(instanceSalt, email), ARGON)

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
      recoveryMasterKeyTag: b64std(v1.recoveryAuthTag(masterKey)),
    },
  }
}
