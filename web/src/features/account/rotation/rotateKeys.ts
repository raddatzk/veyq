// uc.account.rotate-keys (web) — end a compromise by replacing the Master Key and the identity keypair
// together (ADR 0060). Everything that matters happens on this device: the new keys are minted here, every
// wrap and sealed blob is rewritten here, and the server receives ciphertext, one public key and a list of
// scope ids it already routes on.
//
// **It is forward secrecy, not repair.** What the old identity already read stays read. Nothing in this file —
// and nothing in the surface above it — may suggest otherwise.
//
// **What this file does NOT do: re-admit the caller to a shared scope.** The server queues a `scope-rotation`
// job per shared scope for a *different* key-holder, and re-admission runs through that human's changed-key
// review (ADR 0060 Decision 4). A rotator who could drive their own re-grant would let an attacker holding the
// old identity re-admit only themselves, which turns the exclusion into the takeover's final step.
import * as v1 from '@veyq/crypto/v1'
import { unlockKekAsync, recoveryKekAsync } from '@veyq/crypto/argonWorker'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { accountApi, accountRotationApi, scopesApi } from '../../../api/client'
import { withRefresh } from '../login/login'
import { masterKey, setUnlocked } from '../../../app/vault'
import { MasterKeyWrapMethodEnum } from '../../../api/generated/account'
import { resealPinsUnderNewMasterKey } from '../identityPins'
import { resealRegisterUnderNewMasterKey } from '../deviceRegister'
import { openScopeById } from '../../spaces/scopes'
import { listScopeMembers } from '../../spaces/members'
import { writeKey, zeroEpochKeys, EPOCH_LINK } from '../../spaces/epochs'
import { wrapScopeKeyToMember } from '../../spaces/grant'

const ARGON = v1.ARGON

/** Why a rotation refused before it wrote anything. Each maps to one of uc.account.rotate-keys' exception flows. */
export type RotationRefusal =
  /** E1 — the password + Secret Key offered here do not open this account's `PASSWORD` floor. */
  | 'credentialRejected'
  /** E2 — there is no `PASSWORD` floor to prove against, so nothing can authorise a rotation. */
  | 'noFloorWrap'
  /** The vault is locked, so there is no current Master Key to prove the offered credential against. */
  | 'vaultLocked'

/** Thrown before any write. A rotation that refuses has changed nothing, anywhere. */
export class RotationRefused extends Error {
  constructor(public readonly reason: RotationRefusal) {
    super(`rotation refused: ${reason}`)
    this.name = 'RotationRefused'
  }
}

/** One scope after the rotation, and what still has to happen to it (uc.account.rotate-keys §9). */
export type ScopeOutcome = {
  scopeId: string
  /** `secured` — re-keyed here, and the new identity holds it. `waiting` — queued for another key-holder, and
   *  the caller is **outside** that scope until a human there accepts the new key. `failed` — the self-served
   *  re-key did not complete; the scope is unchanged and the old identity still reads it. */
  status: 'secured' | 'waiting' | 'failed'
}

/** What a completed rotation leaves behind — a per-scope status, never a success tick. */
export type RotationResult = { scopes: ScopeOutcome[] }

/**
 * The cold-credential gate (ADR 0060 Decision 2, uc.account.rotate-keys §2) — **a live proof, not a memory.**
 *
 * Derive the floor KEK from the password and Secret Key offered right now, open the stored `PASSWORD` wrap
 * with it, and require the result to equal the Master Key this vault is already holding. That is the binding
 * refusal, and it is unavoidable work rather than an extra check: rewriting the two cold wraps needs those very
 * secrets as material, and the vault keeps no KEK from unlock — so **every** path through this screen supplies
 * them, a cold one included.
 *
 * **How this vault was opened does not gate it, and that is E1 as written, not a softening.** A session opened
 * by a device wrap upgrades *in place* by supplying the cold credential, which is the same proof a cold session
 * gives; turning it away would add nothing over the proof and would send somebody already holding their
 * Emergency Kit back to the sign-in screen for no reason. `unlockOrigin()` is what the *screen* reads — to say
 * why the Secret Key is being asked for here — and the rotation marks the session cold afterwards, because it
 * has just proven exactly that.
 *
 * The attacker this defends against holds a `DEVICE_KEY` wrap and nothing colder. They fail here, on the
 * proof, whichever screen they reach it from.
 *
 * Neither refusal writes anything. Returns the current Master Key (a fresh copy the caller owns) once it passes.
 */
async function proveColdCredential(password: string, secretKeyB64: string): Promise<Uint8Array> {
  const held = masterKey()
  if (!held) throw new RotationRefused('vaultLocked')

  const material = await withRefresh(() => accountApi().getAccountKeyMaterial())
  const floor = material.wraps.find((w) => w.method === MasterKeyWrapMethodEnum.Password)
  if (!floor?.kdfParams) {
    held.fill(0)
    throw new RotationRefused('noFloorWrap')
  }

  const secretKey = fromB64std(secretKeyB64.trim())
  const { params, salt } = v1.decodeKdfParams(fromB64std(floor.kdfParams))
  const kek = await unlockKekAsync(secretKey, password, salt, params)
  let opened: Uint8Array
  try {
    opened = v1.symUnwrap(kek, fromB64std(floor.wrappedKey), 'master-key')
  } catch {
    held.fill(0)
    throw new RotationRefused('credentialRejected')
  }
  // A wrong Secret Key with a right password (or the reverse) fails the AEAD above; this compares what the
  // wrap yielded against what this session is holding, so a *stale* proof — a floor rewritten under us — is
  // refused too rather than silently rotating from a Master Key that is no longer the account's.
  const matches = opened.length === held.length && opened.every((b, i) => b === held[i])
  opened.fill(0)
  if (!matches) {
    held.fill(0)
    throw new RotationRefused('credentialRejected')
  }
  return held
}

/**
 * Rotate. Mints `MK'` and a fresh identity on-device, publishes the account half in one server transaction,
 * re-seals what the old Master Key sealed, re-keys every scope the caller is alone in, and finally swaps the
 * vault over.
 *
 * **The order is the design.** The publish is the irreversible commit, and everything after it is repair that a
 * re-run can redo (uc.account.rotate-keys §E7 — every step is idempotent against the published state, and the
 * client finishes rather than re-mints). So the re-seals run *after* it: doing them first would, on a failed
 * publish, have thrown the pin store away for a rotation that never happened. And the vault swap runs **last**,
 * because the sole-member re-key below opens the caller's existing grants — which are sealed to the **old**
 * identity, the private half of which lives only in the vault until this function replaces it.
 *
 * Both cold wraps are rewritten under a **fresh salt**. The Emergency Kit stays valid because the *Secret Key*
 * is unchanged, not because the salt is — and the `RECOVERY` wrap's stored salt is not even readable from here
 * (it is deliberately withheld from key material, ADR 0054), so a fresh one is the only shape available and
 * the right one anyway: re-using a salt leaks the rewrite to anyone diffing two dumps.
 */
export async function rotateKeys(password: string, secretKeyB64: string): Promise<RotationResult> {
  const oldMasterKey = await proveColdCredential(password, secretKeyB64)
  const secretKey = fromB64std(secretKeyB64.trim())

  const identity = v1.generateIdentity()
  const newMasterKey = v1.randomBytes(32)

  try {
    const wrappedPriv = b64std(
      v1.symWrap(newMasterKey, v1.serializeIdentityPrivate(identity), 'identity-priv', v1.randomBytes(12)),
    )
    const pwSalt = v1.randomBytes(16)
    const recSalt = v1.randomBytes(16)
    const pwKek = await unlockKekAsync(secretKey, password, pwSalt, ARGON)
    const recKek = await recoveryKekAsync(secretKey, recSalt, ARGON)

    const result = await withRefresh(() =>
      accountRotationApi().rotateAccountKeys({
        rotateAccountKeysRequest: {
          identityPublicKey: b64std(v1.serializeIdentityPublic(identity.pub)),
          wrappedIdentityPrivateKey: wrappedPriv,
          passwordWrap: {
            wrappedKey: b64std(v1.symWrap(pwKek, newMasterKey, 'master-key', v1.randomBytes(12))),
            kdfParams: b64std(v1.encodeKdfParams(ARGON, pwSalt)),
          },
          recoveryWrap: {
            wrappedKey: b64std(v1.symWrap(recKek, newMasterKey, 'master-key', v1.randomBytes(12))),
            kdfParams: b64std(v1.encodeKdfParams(ARGON, recSalt)),
          },
          recoveryMasterKeyTag: b64std(v1.recoveryAuthTag(newMasterKey)),
        },
      }),
    )

    // Master-Key-sealed blobs, re-sealed under MK'. Best-effort on purpose: both degrade to empty rather than
    // fail (a lost pin re-pins on first use, an unnamed device still revokes), so neither is worth failing a
    // rotation that has already published.
    await resealBestEffort(oldMasterKey, newMasterKey)

    // Self-served: alone in the scope, so there is nobody to be re-admitted by and nobody to exclude but the
    // old identity. Runs while the vault still holds that old identity — it is what opens the current grant.
    const secured: ScopeOutcome[] = []
    for (const scopeId of result.soleMemberScopeIds) {
      secured.push({ scopeId, status: (await rekeyAlone(scopeId, identity)) ? 'secured' : 'failed' })
    }

    // `cold` is a statement of fact: this rotation ran on a live proof of password + Secret Key moments
    // ago, so the session it leaves behind is cold however it was originally opened.
    setUnlocked(newMasterKey.slice(), identity.priv, 'cold')

    return {
      scopes: [...secured, ...result.queuedScopeIds.map((scopeId): ScopeOutcome => ({ scopeId, status: 'waiting' }))],
    }
  } finally {
    oldMasterKey.fill(0)
    secretKey.fill(0)
    newMasterKey.fill(0)
  }
}

/** Re-seal both Master-Key-sealed blobs, swallowing failures — see `rotateKeys` on why neither may fail it. */
async function resealBestEffort(oldMk: Uint8Array, newMk: Uint8Array): Promise<void> {
  try {
    await resealPinsUnderNewMasterKey(oldMk, newMk)
  } catch {
    /* an unreadable pin store is an empty one; every contact re-pins on first use */
  }
  try {
    await resealRegisterUnderNewMasterKey(oldMk, newMk)
  } catch {
    /* an unnamed device row still lists and still revokes (uc.account.sessions §E4) */
  }
}

/**
 * Mint the next epoch of a scope the caller is alone in and grant it to their **new** identity — the exclusion
 * of their own old identity, performed by the only person who can (uc.account.rotate-keys §6).
 *
 * It is `rotateGroupForRemoval`'s crypto with a one-member directory: chain-link `symWrap(E_{n+1}, E_n)` so the
 * new grant still peels back to everything already written, and wrap `E_{n+1}` forward.
 *
 * Two details that look like details and are not:
 *
 *  - **The wrap targets the locally minted public key, never the directory's copy of it.** The directory now
 *    serves the key this rotation just published, so the two agree — but trusting the round-trip would make an
 *    account's re-key of its own scope depend on the server returning its own key faithfully, which is exactly
 *    the substitution the TOFU model exists to catch and which no pin can catch for *yourself*. There is
 *    correspondingly no `checkAndPin` here: a pin store does not pin its owner.
 *  - **The member list is read anyway, for the caller's `userId`** — the grant is written onto a membership row
 *    and the server matches it by id. Finding anything other than exactly one member means the scope stopped
 *    being sole-member between the server's split and this call, and the honest answer is to refuse: a rotation
 *    that re-granted only itself in a scope that has since gained a member would lock that member out.
 *
 * Returns whether it completed. A failure is reported per scope rather than thrown: the account half has
 * already published, so the honest surface is *"this scope was not secured"* beside the ones that were, and a
 * retry re-runs exactly this step.
 */
async function rekeyAlone(scopeId: string, identity: v1.Identity): Promise<boolean> {
  const opened = await openScopeById(scopeId)
  if (!opened) return false
  const newKey = v1.randomBytes(32)
  try {
    const members = await listScopeMembers(scopeId)
    if (members.length !== 1) return false
    const wrappedPrev = b64std(v1.symWrap(newKey, writeKey(opened.epochs), EPOCH_LINK, v1.randomBytes(12)))
    const mine = b64std(v1.serializeIdentityPublic(identity.pub))
    await withRefresh(() =>
      scopesApi().rotateScope({
        scopeId,
        rotateScopeRequest: {
          wrappedPrev,
          grants: [{ userId: members[0].userId, wrappedGrant: wrapScopeKeyToMember(newKey, mine) }],
        },
      }),
    )
    return true
  } catch {
    return false
  } finally {
    newKey.fill(0)
    zeroEpochKeys(opened.epochs)
  }
}
