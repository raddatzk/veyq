// The cold-credential proof (ADR 0060 Decision 2) — **a live proof, not a memory.**
//
// Every screen that MINTS a cold credential runs this first: rotation, which rewrites the `PASSWORD` and
// `RECOVERY` wraps, and change-password, which rewrites the `PASSWORD` floor and the auth key. Both hand an
// attacker a permanent hold on the account if they can be reached without the material the attacker lacks —
// a linked device receives the Master Key, never the Secret Key — so both gate on the same thing, and the
// gate lives in ONE place so the two cannot drift.
import * as v1 from '@veyq/crypto/v1'
import { unlockKekAsync } from '@veyq/crypto/argonWorker'
import { fromB64std } from '@veyq/crypto/base64'
import { accountApi } from '../../api/client'
import { withRefresh } from '../../app/sessionRefresh'
import { masterKey } from '../../app/vault'
import { MasterKeyWrapMethodEnum } from '../../api/generated/account'

/** Why the proof refused. Nothing is written on any of them. */
export type ColdCredentialRefusal =
  /** The password + Secret Key offered here do not open this account's `PASSWORD` floor. */
  | 'credentialRejected'
  /** There is no `PASSWORD` floor to prove against, so nothing can authorise minting a new one. */
  | 'noFloorWrap'
  /** The vault is locked, so there is no current Master Key to prove the offered credential against. */
  | 'vaultLocked'

/** Thrown before any write. A caller that catches this has changed nothing, anywhere. */
export class ColdCredentialRefused extends Error {
  constructor(public readonly reason: ColdCredentialRefusal) {
    super(`cold credential refused: ${reason}`)
    this.name = 'ColdCredentialRefused'
  }
}

/**
 * Derive the floor KEK from the password and Secret Key offered right now, open the stored `PASSWORD` wrap
 * with it, and require the result to equal the Master Key this vault is already holding. That is the binding
 * refusal, and for rotation it is unavoidable work rather than an extra check: rewriting the two cold wraps
 * needs those very secrets as material, and the vault keeps no KEK from unlock — so **every** path through
 * that screen supplies them, a cold one included.
 *
 * **How this vault was opened does not gate it, and that is uc.account.rotate-keys §E1 as written, not a
 * softening.** A session opened by a device wrap upgrades *in place* by supplying the cold credential, which
 * is the same proof a cold session gives; turning it away would add nothing over the proof and would send
 * somebody already holding their Emergency Kit back to the sign-in screen for no reason. `unlockOrigin()` is
 * what the *screen* reads — to say why the Secret Key is being asked for here — and a caller marks the
 * session cold afterwards, because it has just proven exactly that.
 *
 * The attacker this defends against holds a `DEVICE_KEY` wrap and nothing colder. They fail here, on the
 * proof, whichever screen they reach it from.
 *
 * Neither refusal writes anything. Returns the current Master Key (a fresh copy the caller owns) once it passes.
 */
export async function proveColdCredential(password: string, secretKeyB64: string): Promise<Uint8Array> {
  const held = masterKey()
  if (!held) throw new ColdCredentialRefused('vaultLocked')
  let opened: Uint8Array
  try {
    opened = await openFloor(password, secretKeyB64)
  } catch (e) {
    held.fill(0)
    throw e
  }
  // A wrong Secret Key with a right password (or the reverse) fails the AEAD in `openFloor`; this compares what the
  // wrap yielded against what this session is holding, so a *stale* proof — a floor rewritten under us — is
  // refused too rather than silently minting a credential from a Master Key that is no longer the account's.
  const matches = opened.length === held.length && opened.every((b, i) => b === held[i])
  opened.fill(0)
  if (!matches) {
    held.fill(0)
    throw new ColdCredentialRefused('credentialRejected')
  }
  return held
}

/**
 * The same proof where the vault may be LOCKED (uc.account.change-email step 5: the page a mail link opens is not
 * behind the unlock gate). It opens the stored `PASSWORD` floor with what was typed now — which only the account's
 * password AND its Secret Key do — and, where this session does hold a Master Key, runs [proveColdCredential] itself.
 * It returns nothing: its use is to refuse a Secret Key before anything is built from it (a new Emergency Kit printed
 * from a mistyped key fails exactly on the day it is needed).
 */
export async function checkColdCredential(password: string, secretKeyB64: string): Promise<void> {
  if (masterKey()) {
    ;(await proveColdCredential(password, secretKeyB64)).fill(0)
    return
  }
  ;(await openFloor(password, secretKeyB64)).fill(0)
}

/** Open the stored `PASSWORD` floor with the offered password and Secret Key: the Master Key it holds, or a refusal. */
async function openFloor(password: string, secretKeyB64: string): Promise<Uint8Array> {
  const material = await withRefresh(() => accountApi().getAccountKeyMaterial())
  const floor = material.wraps.find((w) => w.method === MasterKeyWrapMethodEnum.Password)
  if (!floor?.kdfParams) throw new ColdCredentialRefused('noFloorWrap')

  const secretKey = fromB64std(secretKeyB64.trim())
  try {
    // A served profile this client does not write throws `v1.KdfProfileRefused` here (ADR 0017 rule 5). It is the
    // server's fault, not the credential's, so it stays OUTSIDE the refusals: the screens then say "nothing was
    // changed, try again", never "that password or Secret Key didn't match".
    const { params, salt } = v1.decodeKdfParams(fromB64std(floor.kdfParams))
    const kek = await unlockKekAsync(secretKey, password, salt, params)
    try {
      return v1.open({ purpose: 'master-key' }, kek, fromB64std(floor.wrappedKey))
    } catch {
      throw new ColdCredentialRefused('credentialRejected')
    }
  } finally {
    secretKey.fill(0)
  }
}
