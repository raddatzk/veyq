// uc.account.rotate-keys (web) — end a compromise by replacing the Master Key and the identity keypair
// together (ADR 0060). Everything that matters happens on this device: the new keys are minted here, every
// wrap and sealed blob is rewritten here, and the server receives ciphertext, one public key and a list of
// scope ids it already routes on.
//
// **It is forward secrecy, not repair.** What the old identity already read stays read. Nothing in this file —
// and nothing in the surface above it — may suggest otherwise.
//
// **What this file does NOT do: re-admit the caller to a shared scope.** The server queues a `scope-rotation`
// job per shared scope for another *owner* of it, and re-admission runs through that human's changed-key
// review (ADR 0060 Decision 4). A rotator who could drive their own re-grant would let an attacker holding the
// old identity re-admit only themselves, which turns the exclusion into the takeover's final step.
import * as v1 from '@veyq/crypto/v1'
import { unlockKekAsync, recoveryKekAsync } from '@veyq/crypto/argonWorker'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { accountRotationApi } from '../../../api/client'
import type { RotationScopeState } from '../../../api/generated/account'
import { withRefresh } from '../../../app/sessionRefresh'
import { identityPrivate, setUnlocked } from '../../../app/vault'
import { isCodedError, HttpStatus } from '../../../shared/httpError'
import { ColdCredentialRefused, proveColdCredential } from '../coldCredential'
import { pinStoreDocument } from '../identityPins'
import { registry } from '../../../core/applications/registry'
import { registerDocument } from '../deviceRegister'
import { carryMarks, raiseMarks, TrustStateRefused, type AccountDocumentStore } from '../trustMarks'
import { commitRotation } from '../../spaces/scopes'
import { listScopeMembers } from '../../spaces/members'
import { resealFavoritesUnderNewMasterKey } from '../../../core/favorites/favorites'

const ARGON = v1.ARGON

/** Bounds the re-read/re-seal/resubmit loop on a `409 stale_revision` — the documents' own writes use the same
 *  bound (`MAX_WRITE_ATTEMPTS` in `identityPins.ts` / `deviceRegister.ts`); a real conflict is another device
 *  writing a document while this one rotates. */
const MAX_PUBLISH_ATTEMPTS = 5

/** Why a rotation refused before it wrote anything. Each maps to one of uc.account.rotate-keys' exception flows. */
export type RotationRefusal =
  /** E1 — the password + Secret Key offered here do not open this account's `PASSWORD` floor. */
  | 'credentialRejected'
  /** E2 — there is no `PASSWORD` floor to prove against, so nothing can authorise a rotation. */
  | 'noFloorWrap'
  /** The vault is locked, so there is no current Master Key to prove the offered credential against. */
  | 'vaultLocked'
  /** E8 — this device refuses the pin store or the device register (ADR 0028 Decision 7). Re-sealing it under the
   *  new key would launder a rollback into it, or carry an unreadable one across as empty; the way back is the
   *  user's reset in the settings hub, not a rotation. */
  | 'trustStateRefused'

/** Thrown before any write. A rotation that refuses has changed nothing, anywhere. */
export class RotationRefused extends Error {
  constructor(public readonly reason: RotationRefusal) {
    super(`rotation refused: ${reason}`)
    this.name = 'RotationRefused'
  }
}

/** One scope this device re-keyed alone, and how that went (uc.account.rotate-keys §6/§9). */
export type ScopeOutcome = {
  scopeId: string
  /** `secured` — re-keyed here, and the new identity holds it. `failed` — the self-served re-key did not
   *  complete; the scope is unchanged and the old identity still reads it. */
  status: 'secured' | 'failed'
}

/** What a completed rotation re-keyed here — a per-scope status, never a success tick. The shared scopes it queued
 *  are not in it: how each of those stands is the server's to say, at any time ({@link listQueuedRotations}). */
export type RotationResult = { scopes: ScopeOutcome[] }

/**
 * Where every rotation this account's own rotation queued stands, read from the server on every call (uc.account.
 * rotate-keys §9, ADR 0060 Decision 6): `waiting`, `stalled` (no owner may complete it — make someone else an
 * owner), `secured`, or `failed` (it dead-lettered: the old key still reads what is new there). Empty for an
 * account that never rotated.
 */
export async function listQueuedRotations(): Promise<RotationScopeState[]> {
  return withRefresh(() => accountRotationApi().listRotationScopeStates())
}

/** Queue a dead-lettered rotation of one scope again, this account still excluded from it; answers the new state. */
export async function requeueQueuedRotation(scopeId: string): Promise<RotationScopeState> {
  return withRefresh(() => accountRotationApi().requeueRotationScope({ scopeId }))
}

/**
 * Rotate. Mints `MK'` and a fresh identity on-device, publishes the account half — the pin store and the device
 * register re-sealed under `MK'` included — in one server transaction, re-seals the node pins, re-keys
 * every scope the caller is alone in, and finally swaps the vault over.
 *
 * **The order is the design.** The publish is the irreversible commit, and everything after it is repair that a
 * re-run can redo (uc.account.rotate-keys §E7 — every step is idempotent against the published state, and the
 * client finishes rather than re-mints). The two trust documents are therefore INSIDE the publish, not after it:
 * a document left under the retired key is refused by every device (ADR 0028 Decision 7), so it is not repair a
 * re-run could do — once this function returns the old key is zeroed and no device can ever open it again. The
 * favorites' re-seal runs after, because an unreadable favorites set is only empty. And the vault swap runs **last**,
 * because the sole-member re-key below opens the caller's existing grants — which are sealed to the **old**
 * identity, the private half of which lives only in the vault until this function replaces it.
 *
 * Both cold wraps are rewritten under a **fresh salt**. The Emergency Kit stays valid because the *Secret Key*
 * is unchanged, not because the salt is — and the `RECOVERY` wrap's stored salt is not even readable from here
 * (it is deliberately withheld from key material, ADR 0054), so a fresh one is the only shape available and
 * the right one anyway: re-using a salt leaks the rewrite to anyone diffing two dumps.
 */
export async function rotateKeys(password: string, secretKeyB64: string): Promise<RotationResult> {
  // Fail closed BEFORE anything is proven, minted or written: material an application sealed under the Master Key
  // (Memories' pins) is only recoverable through its `onMasterKeyRotated` hook, so a build whose composition root
  // registered none must not rotate at all. A wiring defect, not a user refusal: it throws, it is not a RotationRefused.
  registry.assertRotationHooks()
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
    // The publish, carrying the pin store and the device register re-sealed under MK' (ADR 0060 § Consequences):
    // the server writes them in the same transaction as the account rewrite, so a rotation that cannot write
    // them does not happen — where re-sealing them after the publish left them under the retired key whenever
    // that write failed, and every device refuses a document that does not open (ADR 0028 Decision 7).
    //
    // A `409 stale_revision` means another device wrote a document meanwhile and NOTHING was written, so the
    // documents are re-read and re-sealed against what is now stored, and the same keys go up again under the
    // same signature (the documents are not part of the proof's statement). A document this device refuses —
    // older than its mark, missing after it held one, unreadable — refuses the rotation before any write.
    const sealed = { pins: { blob: '', revision: 0 }, register: { blob: '', revision: 0 } }
    let result: Awaited<ReturnType<ReturnType<typeof accountRotationApi>['rotateAccountKeys']>>
    for (let attempt = 1; ; attempt++) {
      sealed.pins = await resealUnderNewKey(pinStoreDocument, oldMasterKey, newMasterKey)
      sealed.register = await resealUnderNewKey(registerDocument, oldMasterKey, newMasterKey)
      try {
        result = await withRefresh(() =>
          accountRotationApi().rotateAccountKeys({
            rotateAccountKeysRequest: {
              identityPublicKey: b64std(identityPublicKey),
              wrappedIdentityPrivateKey: wrappedPriv,
              passwordWrap: wire(passwordWrap),
              recoveryWrap: wire(recoveryWrap),
              secretKeyProofSignature,
              identityPins: sealed.pins,
              deviceRegister: sealed.register,
            },
          }),
        )
        break
      } catch (e) {
        if (attempt < MAX_PUBLISH_ATTEMPTS && (await isCodedError(e, HttpStatus.conflict, 'stale_revision'))) continue
        throw e
      }
    }
    // This device wrote both at `revision + 1` under MK' — it has held them, in the slot the account reads from
    // once the vault switches (the rest of its marks are carried across below).
    await raiseMarks(newMasterKey, { pins: sealed.pins.revision + 1, register: sealed.register.revision + 1 })

    // Node pins are re-sealed after the publish, best-effort on purpose, and stay out of the request: one
    // blob per scope, and an unreadable set degrades to empty by ADR 0068 (an unpinned Memory is still there to
    // re-pin) — it is not trust state, so failing a published rotation over it would buy nothing. Their two lists
    // partition the caller's scopes, so the union is every scope a pin set can exist in. Item marks ride here too:
    // sealed under `K_SC`, but their self-tag's key derives from the Master Key (ADR 0068 §9). They open each
    // scope's `E_0` through the caller's grant, which is why this runs while the vault still holds the old identity.
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

    // This device's trust-state marks are keyed by the Master Key (ADR 0028 Decision 7), so they move with it —
    // last, because the re-seals and the re-keys above still ran under the old key and raised its marks.
    await carryMarks(oldMasterKey, newMasterKey)

    // `cold` is a statement of fact: this rotation ran on a live proof of password + Secret Key moments
    // ago, so the session it leaves behind is cold however it was originally opened.
    setUnlocked(newMasterKey.slice(), identity.priv, 'cold')

    return { scopes: secured }
  } finally {
    oldMasterKey.fill(0)
    secretKey.fill(0)
    newMasterKey.fill(0)
  }
}

/**
 * Read one sealed account document, open it under the OLD Master Key through this device's marks, and seal it
 * under the new one for the revision the rotation will store it at. A refusal there — rolled back, missing after a
 * mark, unreadable — is a refusal of the rotation: re-sealing a rolled-back copy under MK' would make the rollback
 * the rotation's own, and the honest way back from a refused document is the user's reset, not this.
 */
async function resealUnderNewKey<T>(
  doc: AccountDocumentStore<T>,
  oldMk: Uint8Array,
  newMk: Uint8Array,
): Promise<{ blob: string; revision: number }> {
  const served = await doc.fetch()
  let value: T
  try {
    value = await doc.open(oldMk, served)
  } catch (e) {
    if (e instanceof TrustStateRefused) throw new RotationRefused('trustStateRefused')
    throw e
  }
  return { blob: doc.seal(newMk, value, served.revision + 1), revision: served.revision }
}

/**
 * The Master-Key-sealed blobs re-sealed AFTER the publish, each swallowing its failure — see `rotateKeys` on why
 * none may fail it: the node pins and the item marks' self-tags. Trust state never belongs here: the pin store and the
 * device register ride inside the publish, because one left under the retired key is refused, not empty.
 */
async function resealBestEffort(oldMk: Uint8Array, newMk: Uint8Array, scopeIds: string[]): Promise<void> {
  try {
    await resealFavoritesUnderNewMasterKey(oldMk, newMk, scopeIds) // core's: the node pins of every scope
  } catch {
    /* an unreadable pin set is an empty one (the nodes are all still there, just no longer pinned) */
  }
  try {
    await registry.masterKeyRotated(oldMk, newMk, scopeIds)
  } catch {
    /* each application's hook is best-effort for its own material (Memories': an unreadable pin set is an empty one,
       an item mark whose tag the new key does not verify counts as someone else's set — ADR 0068 §9); the rotation
       itself is already committed and a re-run repairs */
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
    // Signed by the NEW identity (ADR 0028 Decision 6): the vault still holds the old one, which is what opens the
    // current grant — but the grant this writes is opened later under the new one, so it must verify there. The
    // caller's own admission is carried forward like any other (ADR 0020 move 6).
    await commitRotation(scopeId, members, { userId: members[0].userId, identityPublicKeyB64: mine, signPrivateKey: identity.priv.sign })
    return true
  } catch {
    return false
  }
}
