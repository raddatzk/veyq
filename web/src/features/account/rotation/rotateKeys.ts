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
import { accountRotationApi } from '../../../api/client'
import { withRefresh } from '../../../app/sessionRefresh'
import { identityPrivate, setUnlocked } from '../../../app/vault'
import { ColdCredentialRefused, proveColdCredential } from '../coldCredential'
import { resealPinsUnderNewMasterKey } from '../identityPins'
import { resealFavoritesUnderNewMasterKey } from '../../memory/favorites'
import { resealRegisterUnderNewMasterKey } from '../deviceRegister'
import { commitRotation } from '../../spaces/scopes'
import { listScopeMembers } from '../../spaces/members'
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
  // The gate is shared with change-password (`../coldCredential`) — one implementation, because two screens
  // minting a cold credential behind two copies of the same proof is how one of them ends up without it. Its
  // three refusals are this file's three by construction; they are re-thrown in this flow's vocabulary so the
  // panel keeps reading one error type.
  const oldMasterKey = await proveColdCredential(password, secretKeyB64).catch((e: unknown) => {
    if (e instanceof ColdCredentialRefused) throw new RotationRefused(e.reason)
    throw e
  })
  // The identity this rotation replaces, as the server's statement binds it. Read from the vault's own private
  // key rather than the directory, for the same reason the sole-member re-key below trusts no round-trip.
  const currentPriv = identityPrivate()
  if (!currentPriv) {
    oldMasterKey.fill(0)
    throw new RotationRefused('vaultLocked')
  }
  const currentIdentityPublicKey = v1.serializeIdentityPublic(v1.identityPublicFromPrivate(currentPriv))
  const secretKey = fromB64std(secretKeyB64.trim())

  const identity = v1.generateIdentity()
  const newMasterKey = v1.randomBytes(32)

  try {
    const wrappedPriv = b64std(
      v1.seal({ purpose: 'identity-priv' }, newMasterKey, v1.serializeIdentityPrivate(identity)),
    )
    const pwSalt = v1.randomBytes(16)
    const recSalt = v1.randomBytes(16)
    const pwKek = await unlockKekAsync(secretKey, password, pwSalt, ARGON)
    const recKek = await recoveryKekAsync(secretKey, recSalt, ARGON)
    const identityPublicKey = v1.serializeIdentityPublic(identity.pub)
    const passwordWrap = {
      wrappedKey: v1.seal({ purpose: 'master-key' }, pwKek, newMasterKey),
      kdfParams: v1.encodeKdfParams(ARGON, pwSalt),
    }
    const recoveryWrap = {
      wrappedKey: v1.seal({ purpose: 'master-key' }, recKek, newMasterKey),
      kdfParams: v1.encodeKdfParams(ARGON, recSalt),
    }

    // The gate's server half (ADR 0060 Decision 2): a signature under the key only the Secret Key derives, over
    // the identity this replaces and everything it installs. The proof key itself survives the rotation — the
    // Secret Key does not change — so nothing is re-registered.
    const proof = v1.deriveSecretKeyProofKeypair(secretKey)
    const secretKeyProofSignature = b64std(
      v1.secretKeyProofSign(
        proof.privateKey,
        v1.rotationProofStatement({
          currentIdentityPublicKey,
          identityPublicKey,
          wrappedIdentityPrivateKey: fromB64std(wrappedPriv),
          passwordWrap,
          recoveryWrap,
        }),
      ),
    )
    proof.privateKey.fill(0)

    const wire = (w: { wrappedKey: Uint8Array; kdfParams: Uint8Array }) => ({
      wrappedKey: b64std(w.wrappedKey),
      kdfParams: b64std(w.kdfParams),
    })
    const result = await withRefresh(() =>
      accountRotationApi().rotateAccountKeys({
        rotateAccountKeysRequest: {
          identityPublicKey: b64std(identityPublicKey),
          wrappedIdentityPrivateKey: wrappedPriv,
          passwordWrap: wire(passwordWrap),
          recoveryWrap: wire(recoveryWrap),
          secretKeyProofSignature,
        },
      }),
    )

    // Master-Key-sealed blobs, re-sealed under MK'. Best-effort on purpose: all three degrade to empty rather
    // than fail (a lost contact-pin re-pins on first use, an unnamed device still revokes, an unpinned Memory
    // is still there to re-pin), so none is worth failing a rotation that has already published.
    //
    // Memory pins joined this list when they moved off `K_SC` onto the Master Key (ADR 0068). Their two lists
    // partition the caller's scopes, so the union is every scope a pin set can exist in.
    await resealBestEffort(oldMasterKey, newMasterKey, [
      ...result.soleMemberScopeIds,
      ...result.queuedScopeIds,
    ])

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

/** Re-seal every Master-Key-sealed blob, swallowing failures — see `rotateKeys` on why none may fail it. */
async function resealBestEffort(oldMk: Uint8Array, newMk: Uint8Array, scopeIds: string[]): Promise<void> {
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
  try {
    await resealFavoritesUnderNewMasterKey(oldMk, newMk, scopeIds)
  } catch {
    /* an unreadable pin set is an empty one; the Memories are all still there, just no longer pinned */
  }
}

/**
 * Mint the next epoch of a scope the caller is alone in and grant it to their **new** identity — the exclusion
 * of their own old identity, performed by the only person who can (uc.account.rotate-keys §6).
 *
 * It is `rotateGroupForRemoval`'s crypto with a one-member directory, through the same `commitRotation`: chain-link
 * `E_n` under `E_{n+1}` so the new grant still peels back to everything already written, wrap `E_{n+1}` forward,
 * and state the epoch it creates (a stale answer rebuilds once from a fresh key, ADR 0024 §4).
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
  try {
    const members = await listScopeMembers(scopeId)
    if (members.length !== 1) return false
    const mine = b64std(v1.serializeIdentityPublic(identity.pub))
    await commitRotation(scopeId, async (newKey, epoch) => [{ userId: members[0].userId, wrappedGrant: wrapScopeKeyToMember(newKey, scopeId, epoch, mine) }])
    return true
  } catch {
    return false
  }
}
