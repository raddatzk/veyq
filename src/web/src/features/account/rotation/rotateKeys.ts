// uc.account.rotate-keys (web) — securing the account (cr-secure-the-account, ADR 0111 decisions 6–10, ADR 0060):
// the identity, the Master Key and the Secret Key are replaced together, in one step of the account's signed key chain,
// and the person stays inside every space they were in. Everything that matters happens on this device: the new keys
// are minted here, every wrap, self-grant and sealed blob is written here, and the server receives ciphertext, public
// keys, signatures and the scope ids it already routes on.
//
// **It is forward secrecy, not repair.** What the old identity already read stays read. Nothing in this file — and
// nothing in the surface above it — may suggest otherwise.
//
// **Access first, security after** (decision 1). Before the publish, the account's current epoch of EVERY scope it
// holds a grant on — every tenant, sole and shared — is granted to the new identity, signed by it, and staged on the
// server, inert. The publish is one transaction that rewrites the account, appends the doubly signed chain step and
// applies exactly the staged set the Secret Key's proof names. From that moment no state can lose access: the new
// identity reads every scope, whatever happens to this tab. Then the account mints each scope's next epoch itself
// (`finishRotation`, from this tab right away and from every device's worker after), which is what closes the old
// identity out of what comes next. No private key of the old identity is kept anywhere.
//
// **What the account rooted survives it, re-signed** (cr-trust-anchors-survive-rotation decisions 6 and 12): the same
// stage holds, from the NEW identity, a re-signature of every open member invite this account signed and an endorsement
// of every join record it rooted — so the invites keep working, the members they brought in keep counting, and the
// retired key roots nothing afterwards. What the server lists that the outgoing key never signed is declared, never
// vouched for.
import * as v1 from '@veyq/crypto/v1'
import * as invite from '@veyq/crypto/invite'
import { deriveAuthKeypairAsync, recoveryKekAsync, unlockKekAsync } from '@veyq/crypto/argonWorker'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { accountApi, accountRotationApi, loginApi } from '../../../api/client'
import type {
  RotationPlanInvite,
  RotationPlanJoinRecord,
  RotationPlanScope,
  RotationScopeState,
  StagedInviteResign,
  StagedJoinEndorsement,
  StagedSelfGrant,
} from '../../../api/generated/account'
import { account } from '../../../app/account'
import { withRefresh } from '../../../app/sessionRefresh'
import { clearVault, identityPrivate, masterKey, setUnlocked } from '../../../app/vault'
import { registry } from '../../../core/applications/registry'
import { resealFavoritesUnderNewMasterKey } from '../../../core/favorites/favorites'
import { decodeHttpError, HttpStatus, isCodedError, isHttpStatus } from '../../../shared/httpError'
import { me } from '../../spaces/grantTrust'
import { grantSignatureVerifies, recipientIdentityDigest, sealGrant, signGrant } from '../../spaces/grant'
import { openUnderHeldEpochs, zeroEpochKeys, type EpochKeys } from '../../spaces/epochs'
import { isUnreadableGrant, openPlanGrantKey, planOpener } from '../../spaces/scopes'
import { collectMyVerifications, resignVerifications, type CarriedVerification } from '../tenantTrust'
import { ColdCredentialRefused, proveColdCredential } from '../coldCredential'
import { registerDocument } from '../deviceRegister'
import { fetchKeyChain, linkOf, pinStoreDocument, type PinStoreWrite } from '../identityPins'
import { carryMarks, raiseMarks, TrustStateRefused, type AccountDocumentStore } from '../trustMarks'
import { unlockMasterKey } from '../unlock/unlock'
import { carrySessionVault, clearSessionVault } from '../rememberMe/sessionVault'
import { finishRotation, readRotationPlan } from './finishRotation'

const ARGON = v1.ARGON

/** Bounds the publish's resubmits: a `409 stale_revision` (another device wrote a sealed document meanwhile) and a
 *  `409 rotation_plan_changed` (a membership arrived or a scope rotated between stage and publish) each cost one. The
 *  documents' own writes use the same bound (`MAX_WRITE_ATTEMPTS` in `identityPins.ts` / `deviceRegister.ts`). */
const MAX_PUBLISH_ATTEMPTS = 5

/** At most this many self-grants go up in one `putRotationStageGrants` (the contract's `maxItems`: 256 × 8,501 bytes at
 *  every field cap ≈ 2.2 MB, a fifth of the request limit — decision 3) — and as many re-signed invites or join-record
 *  endorsements in one `putRotationStageInvites` / `putRotationStageJoinEndorsements` (their own `maxItems`). */
export const STAGE_CHUNK = 256

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
  /** E10 — the publish is larger than the server accepts (`413`, answered before the body is read). Nothing was
   *  written, and the same body fails the same way again: the remedy is the Server Owner's request limit. */
  | 'requestTooLarge'

/** Thrown before any write. A rotation that refuses has changed nothing, anywhere. */
export class RotationRefused extends Error {
  constructor(public readonly reason: RotationRefusal) {
    super(`rotation refused: ${reason}`)
    this.name = 'RotationRefused'
  }
}

/**
 * The publish failed without a declared refusal — no answer, a `5xx`, a proxy's `502`/`504` — and reading the account
 * back did not settle whether it committed (decision 12). **The vault is locked** when this is thrown: it can be on
 * neither identity with certainty. The person unlocks again — with the NEW Emergency Kit, which opens only if the
 * publish committed (and the worker then finishes), or with the old one, which opens only if it did not. Never read as
 * "nothing was changed".
 */
export class RotationOutcomeUnknown extends Error {
  constructor(readonly failure: unknown) {
    super('rotation outcome unknown')
    this.name = 'RotationOutcomeUnknown'
  }
}

/** What a rotation left, from the server's word on every scope. */
export type RotationResult = {
  /** `published` — this call's publish committed (its answer said so, or a re-read found the identity it installed);
   *  `resumed` — an earlier publish had already committed (the credential opened the account's NEW Master Key while
   *  this vault held the old one): this call minted nothing, re-opened the vault and finished. The Secret Key the
   *  caller passed in `next` was then never published — the one that opened is the account's. */
  outcome: 'published' | 'resumed'
  /** Every scope of the rotation as the server reads it after the finish (decision 8). Empty when that read failed. */
  states: RotationScopeState[]
  /** The scopes this device could not open and so declared (decision 3): each keeps its grant and gets no new lock. */
  unreadableScopeIds: string[]
}

/** The credential the rotation installs: the new Secret Key (base64, the Emergency Kit's encoding — the caller has the
 *  kit saved and confirmed before it calls, decision 7), and a new password when one was chosen (else the password is
 *  kept, and so is the auth key it derives). */
export type NextCredential = { secretKeyB64: string; password?: string }

/** A fresh 32-byte Secret Key in the Emergency Kit's encoding — what the ceremony prints into the new kit before it
 *  passes it to {@link rotateKeys} as `next.secretKeyB64`. */
export function mintSecretKey(): string {
  const key = v1.randomBytes(32)
  try {
    return b64std(key)
  } finally {
    key.fill(0)
  }
}

/**
 * Secure the account. `password` and `secretKeyB64` are the CURRENT credential — the gate (ADR 0060 Decision 2); `next`
 * is what it installs.
 *
 *  1. **Mint**, all in memory: the cold proof, `MK'`, the new identity, the wraps `PASSWORD` =
 *     `kekPasswordSecret(SK', password')` and `RECOVERY` = `kekRecovery(SK', salt)` under fresh salts, the new proof key
 *     and the attestation of the auth key (the old auth key signs the new; a kept password derives the same one), and
 *     the key-chain step old → new, signed by the old identity AND the old proof key.
 *  2. **Stage** (decision 3): from the plan — every tenant — each scope's grant is opened with the OLD identity (still
 *     the vault's) through `verifyOwnGrant` and the epoch floor, and its current epoch key is granted to the NEW
 *     identity, signed by it — never by the old one. A grant this device refuses is declared in `unreadableScopeIds`,
 *     never dropped. Uploaded in chunks of {@link STAGE_CHUNK}.
 *  3. **Publish**: one request naming the stage and its digest, both under the proof statement. On `409
 *     rotation_plan_changed` the plan is re-read and only the delta re-staged — into the same stage, or a fresh one when
 *     that one is gone — and the same keys go up re-signed; on `409 stale_revision` the documents are re-sealed. Both
 *     bounded by {@link MAX_PUBLISH_ATTEMPTS}.
 *  4. **After**: the vault swaps the moment the publish is known committed — before anything else, and never to the
 *     old identity again; then the marks, the best-effort re-seals and {@link finishRotation}. Nothing after the commit
 *     throws: a failure there is the worker's to finish.
 *
 * Failures (decision 12): a refusal before the publish, and a declared refusal of it, wrote nothing (`RotationRefused`,
 * or the error as it is). Any other publish failure re-reads the account: the identity the rotation minted, served under
 * `MK'`, means committed (and the call completes as if answered); the old one under `MK` means not committed (the error
 * is rethrown); anything else locks the vault and throws {@link RotationOutcomeUnknown}. A retry after a committed
 * publish — the floor opens to a Master Key the vault does not hold, and that key opens the served identity — re-opens
 * the vault and finishes; it never mints a second set of keys.
 */
export async function rotateKeys(password: string, secretKeyB64: string, next: NextCredential): Promise<RotationResult> {
  // Fail closed BEFORE anything is proven, minted or written: material an application sealed under the Master Key
  // (Memories' pins) is only recoverable through its `onMasterKeyRotated` hook, so a build whose composition root
  // registered none must not rotate at all. A wiring defect, not a user refusal: it throws, it is not a RotationRefused.
  registry.assertRotationHooks()
  const newSecretKey = fromB64std(next.secretKeyB64.trim())
  if (newSecretKey.length !== 32) {
    newSecretKey.fill(0)
    throw new Error('rotateKeys: the new Secret Key must be 32 bytes')
  }
  // The gate is shared with change-password (`../coldCredential`) — one implementation, because two screens minting a
  // cold credential behind two copies of the same proof is how one of them ends up without it.
  let oldMasterKey: Uint8Array
  try {
    oldMasterKey = await proveColdCredential(password, secretKeyB64)
  } catch (e) {
    newSecretKey.fill(0)
    if (!(e instanceof ColdCredentialRefused)) throw e
    if (e.reason === 'vaultStale') return resumeCommitted(password, secretKeyB64)
    throw new RotationRefused(e.reason)
  }
  // The identity this rotation replaces, as the server's statements bind it. Read from the vault's own private key,
  // never from the directory: a pin store does not pin its owner.
  const currentPriv = identityPrivate()
  if (!currentPriv) {
    oldMasterKey.fill(0)
    newSecretKey.fill(0)
    throw new RotationRefused('vaultLocked')
  }
  const secretKey = fromB64std(secretKeyB64.trim())
  const identity = v1.generateIdentity()
  const newMasterKey = v1.randomBytes(32)
  const oldProof = v1.deriveSecretKeyProofKeypair(secretKey)
  const newProof = v1.deriveSecretKeyProofKeypair(newSecretKey)
  const authKeys: v1.AuthKeypair[] = []
  let carried: CarriedVerification[] = []

  try {
    const self = await me(currentPriv)
    const currentIdentityPublicKey = v1.serializeIdentityPublic(self.pub)
    const identityPublicKey = v1.serializeIdentityPublic(identity.pub)
    const identityPublicKeyB64 = b64std(identityPublicKey)

    // The key-chain step (decision 2): its index and predecessor are the server's stored chain, walked here so the
    // step names the head this account's contacts pinned. Signed twice — by the identity it retires and by the proof
    // key the CURRENT Secret Key derives — so a stolen signed-in device, which never holds the Secret Key, cannot.
    const keyChainStep = await nextChainStep(self.userId, {
      identityPublicKey: currentIdentityPublicKey,
      secretKeyProofPublicKey: oldProof.publicKey,
    }, { identityPublicKey, secretKeyProofPublicKey: newProof.publicKey })
    const stepSignatures = v1.signKeyChainStep(keyChainStep, currentPriv.sign, oldProof.privateKey)

    const wrappedPriv = b64std(v1.seal({ purpose: 'identity-priv' }, newMasterKey, v1.serializeIdentityPrivate(identity)))
    const newPassword = next.password ?? password
    const pwSalt = v1.randomBytes(16)
    const recSalt = v1.randomBytes(16)
    // Both cold wraps open under the NEW Secret Key only (CR invariant): the old Emergency Kit opens nothing once this
    // publishes. Fresh salts: re-using one leaks the rewrite to anyone diffing two dumps.
    const pwKek = await unlockKekAsync(newSecretKey, newPassword, pwSalt, ARGON)
    const recKek = await recoveryKekAsync(newSecretKey, recSalt, ARGON)
    const passwordWrap = { wrappedKey: v1.seal({ purpose: 'master-key' }, pwKek, newMasterKey), kdfParams: v1.encodeKdfParams(ARGON, pwSalt) }
    const recoveryWrap = { wrappedKey: v1.seal({ purpose: 'master-key' }, recKek, newMasterKey), kdfParams: v1.encodeKdfParams(ARGON, recSalt) }
    pwKek.fill(0)
    recKek.fill(0)

    // The password half of the gate (decision 7): the current auth key signs the one the account keeps — the same key
    // when the password is kept, so the request is the same shape either way and no mode hides in an omission.
    const challenge = await withRefresh(() => loginApi().requestLoginChallenge({ loginChallengeRequest: { email: account.email } }))
    const authSalt = v1.deriveAuthSalt(fromB64std(challenge.instanceSalt), account.email)
    const currentAuth = await deriveAuthKeypairAsync(password, authSalt, ARGON)
    authKeys.push(currentAuth)
    const nextAuth = newPassword === password ? currentAuth : await deriveAuthKeypairAsync(newPassword, authSalt, ARGON)
    if (nextAuth !== currentAuth) authKeys.push(nextAuth)
    const attestationSignature = b64std(v1.authSign(currentAuth.privateKey, nextAuth.publicKey))

    const stage = new SelfGrantStage(self.userId, identity, identityPublicKeyB64)
    const rooted = new RootedStage(self, identity)
    const plan = await readRotationPlan()
    await stage.stageAll(plan)
    await stageRooted(stage, rooted, plan)
    // The outgoing key's verifications, per tenant (ADR 0111 decision 13): read NOW, while the old identity is still the
    // account's, so a statement a thief signs with it after the publish is never among them; re-signed by the new
    // identity once the publish committed. A tenant this device cannot open carries none.
    const tenantRows = plan.filter((r) => r.kind === 'TENANT' && !r.blocked)
    carried = await collectMyVerifications(
      self, b64std(keyChainStep.previousHead),
      tenantRows.map((row) => ({ tenantId: row.tenantId, open: planOpener(row, async () => row) })),
    )

    const wire = (w: { wrappedKey: Uint8Array; kdfParams: Uint8Array }) => ({ wrappedKey: b64std(w.wrappedKey), kdfParams: b64std(w.kdfParams) })
    let sealed!: { pins: PinStoreWrite; register: { blob: string; revision: number } }
    for (let attempt = 1; ; attempt++) {
      // The pin store and the device register ride inside the publish, re-sealed under MK' (ADR 0060 § Consequences):
      // one left under the retired key would be refused by every device (ADR 0028 Decision 7), so a rotation that cannot
      // write them does not happen. A document this device refuses refuses the rotation before any write (E8).
      sealed = {
        pins: await resealUnderNewKey(pinStoreDocument, oldMasterKey, newMasterKey),
        register: await resealUnderNewKey(registerDocument, oldMasterKey, newMasterKey),
      }
      // The proof statement binds the stage and its digest, so it is re-signed whenever a re-stage moved them — by the
      // CURRENT proof key (the gate) and by the NEW one (possession: a client bug cannot register a key nobody holds).
      const stagedGrantsDigest = stage.digest()
      const statement = v1.rotationProofStatement({
        currentIdentityPublicKey,
        identityPublicKey,
        wrappedIdentityPrivateKey: fromB64std(wrappedPriv),
        passwordWrap,
        recoveryWrap,
        keyChainStep,
        currentAuthPublicKey: currentAuth.publicKey,
        authPublicKey: nextAuth.publicKey,
        secretKeyProofPublicKey: newProof.publicKey,
        stageId: stage.id,
        stagedGrantsDigest,
      })
      try {
        await withRefresh(() =>
          accountRotationApi().rotateAccountKeys({
            rotateAccountKeysRequest: {
              identityPublicKey: identityPublicKeyB64,
              wrappedIdentityPrivateKey: wrappedPriv,
              passwordWrap: wire(passwordWrap),
              recoveryWrap: wire(recoveryWrap),
              secretKeyProofSignature: b64std(v1.secretKeyProofSign(oldProof.privateKey, statement)),
              identityPins: sealed.pins,
              deviceRegister: sealed.register,
              keyChainStep: {
                identitySignature: b64std(stepSignatures.identitySignature),
                proofSignature: b64std(stepSignatures.proofSignature),
              },
              stageId: stage.id,
              stagedGrantsDigest: b64std(stagedGrantsDigest),
              unreadableScopeIds: stage.unreadableIds(),
              authPublicKey: b64std(nextAuth.publicKey),
              attestationSignature,
              secretKeyProofPublicKey: b64std(newProof.publicKey),
              secretKeyProofPossession: b64std(v1.secretKeyProofSign(newProof.privateKey, statement)),
            },
          }),
        )
        break
      } catch (e) {
        const retry = attempt < MAX_PUBLISH_ATTEMPTS
        if (retry && (await isCodedError(e, HttpStatus.conflict, 'stale_revision'))) continue
        if (retry && (await isCodedError(e, HttpStatus.conflict, 'rotation_plan_changed'))) {
          const now = await readRotationPlan()
          const rootedNow = await rooted.read()
          await stage.restage(now, rooted.moved(stage.id, rootedNow))
          await stageRooted(stage, rooted, now, rootedNow)
          continue
        }
        // By status alone: a proxy in front answers its own limit with a bare 413 and no veyq body, and the remedy
        // is the same. A fetch that rejected is NOT this — the client cannot know why it got no answer.
        if (isHttpStatus(e, HttpStatus.contentTooLarge)) throw new RotationRefused('requestTooLarge')
        if (await isDeclaredRefusal(e)) throw e
        const outcome = await readOutcome(
          { masterKey: newMasterKey, identityPublicKey },
          { masterKey: oldMasterKey, identityPublicKey: currentIdentityPublicKey },
        )
        if (outcome === 'committed') break
        if (outcome === 'notCommitted') throw e
        // Neither identity can be held with certainty — nor can this tab's session vault, which seals the Master Key
        // that may just have been retired: a reload must not restore it. Both go; the person unlocks again.
        clearVault()
        await clearSessionVault()
        throw new RotationOutcomeUnknown(e)
      }
    }

    // Committed. The vault takes the published identity NOW — before the re-seals and the finish, so every later step
    // opens the self-grants the publish wrote, and nothing after this line can put the old identity back.
    setUnlocked(newMasterKey.slice(), { kemSeed: identity.priv.kemSeed.slice(), sign: identity.priv.sign.slice() }, 'cold')
    // This tab's session vault sealed the retired Master Key: re-sealed under the published one, so a reload neither
    // restores a retired key nor finds one cached (the CR's invariant holds in the browser too).
    await carrySessionVault(newMasterKey)
    await afterCommit(oldMasterKey, newMasterKey, sealed, stage.planScopeIds())
    await resignVerifications(carried, self.userId, identity.priv.sign, v1.keyChainHead(keyChainStep))
    const states = await finishRotation().catch((): RotationScopeState[] => [])
    return { outcome: 'published', states, unreadableScopeIds: stage.unreadableIds() }
  } finally {
    // The old identity, both Master Keys, both Secret Keys and every private key derived here leave memory with this
    // call; the vault zeroed its own copy of the old identity when it took the new one.
    currentPriv.kemSeed.fill(0)
    currentPriv.sign.fill(0)
    identity.priv.kemSeed.fill(0)
    identity.priv.sign.fill(0)
    oldMasterKey.fill(0)
    newMasterKey.fill(0)
    secretKey.fill(0)
    newSecretKey.fill(0)
    oldProof.privateKey.fill(0)
    newProof.privateKey.fill(0)
    for (const k of authKeys) k.privateKey.fill(0)
    for (const c of carried) c.key.fill(0)
  }
}

/**
 * The step this rotation appends to the account's own key chain: the chain as the server serves it, walked by
 * `verifyKeyChain`, must end at exactly the keys this account holds now (the vault's identity, the proof key of the
 * Secret Key just proven) — else nothing is staged or published. The server rebuilds the same step from its stored
 * chain and refuses any other (`422 key_chain_step_invalid` / `403`), so this is the client's own consistency check,
 * not a trust judgment.
 */
async function nextChainStep(
  userId: string, current: v1.KeyChainKeys, installed: v1.KeyChainKeys,
): Promise<v1.KeyChainStepStatement> {
  const chain = await fetchKeyChain(userId)
  if (!chain) throw new Error('rotation: the server serves no key chain for this account')
  const walked = v1.verifyKeyChain({ userId, links: chain.steps.map(linkOf) }, null)
  if (
    !walked.ok ||
    !sameBytes(walked.identityPublicKey, current.identityPublicKey) ||
    !sameBytes(walked.secretKeyProofPublicKey, current.secretKeyProofPublicKey)
  ) {
    throw new Error('rotation: the served key chain does not end at this account’s keys')
  }
  return v1.keyChainStepStatement({ userId, index: walked.index + 1, previousHead: walked.head, retired: current, installed })
}

/**
 * The self-grants of one rotation and the server's stage that holds them (decision 3). `staged` mirrors exactly what
 * the server holds for the stage — the digest the publish names is over it, the union of every chunk ever put, a row a
 * later chunk replaced counted once — and `unreadable` the scopes declared instead.
 */
class SelfGrantStage {
  id = ''
  private readonly staged = new Map<string, { epoch: number; wrappedGrant: string }>()
  private readonly unreadable = new Set<string>()
  private plan: RotationPlanScope[] = []

  constructor(
    private readonly userId: string,
    private readonly identity: v1.Identity,
    private readonly identityPublicKeyB64: string,
  ) {}

  /** A fresh stage — a second open replaces the account's first — holding a self-grant of every scope of `plan`. */
  async stageAll(plan: RotationPlanScope[]): Promise<void> {
    this.plan = plan
    const opened = await withRefresh(() =>
      accountRotationApi().openRotationStage({ openRotationStageRequest: { identityPublicKey: this.identityPublicKeyB64 } }),
    )
    this.id = opened.stageId
    this.staged.clear()
    this.unreadable.clear()
    await this.put(plan)
  }

  /**
   * After `409 rotation_plan_changed`: only what moved goes up again — a scope not staged (a membership that arrived),
   * one whose epoch moved on (a rotation since), one declared unreadable before (re-judged). When nothing moved — here
   * nor among the invites and join records ([rootedMoved], {@link RootedStage.moved}) — the stage itself is what the
   * server no longer has (expired, or replaced by another tab's open), and so is a stage that answers `404
   * rotation_stage_not_found`: then a fresh stage is opened and everything staged into it.
   */
  async restage(plan: RotationPlanScope[], rootedMoved = false): Promise<void> {
    const delta = plan.filter((row) => this.staged.get(row.scopeId)?.epoch !== row.currentEpoch)
    if (!delta.length && rootedMoved) {
      this.plan = plan
      return
    }
    if (!delta.length) return this.stageAll(plan)
    this.plan = plan
    try {
      await this.put(delta)
    } catch (e) {
      if (await isCodedError(e, HttpStatus.notFound, 'rotation_stage_not_found')) return this.stageAll(plan)
      throw e
    }
  }

  /** `rotationStageDigest` over every row the server holds for this stage. */
  digest(): Uint8Array {
    return v1.rotationStageDigest(
      [...this.staged].map(([scopeId, g]) => ({ scopeId, epoch: g.epoch, wrappedGrant: fromB64std(g.wrappedGrant) })),
    )
  }

  /** The declared scopes, as the plan read last still lists them (a membership that ended needs no declaring). */
  unreadableIds(): string[] {
    return this.plan.map((r) => r.scopeId).filter((id) => this.unreadable.has(id))
  }

  /** Every scope of the last plan read — what a Master-Key-sealed blob can exist for. */
  planScopeIds(): string[] {
    return this.plan.map((r) => r.scopeId)
  }

  private async put(rows: RotationPlanScope[]): Promise<void> {
    const grants: StagedSelfGrant[] = []
    for (const row of rows) {
      const grant = await this.selfGrant(row)
      if (grant) grants.push(grant)
    }
    for (let i = 0; i < grants.length; i += STAGE_CHUNK) {
      const chunk = grants.slice(i, i + STAGE_CHUNK)
      await withRefresh(() =>
        accountRotationApi().putRotationStageGrants({ stageId: this.id, rotationStageGrants: { grants: chunk } }),
      )
      for (const g of chunk) this.staged.set(g.scopeId, { epoch: g.epoch!, wrappedGrant: g.wrappedGrant })
    }
  }

  /**
   * The scope's current epoch key, opened with the OLD identity (the vault's, until the publish) and granted to the NEW
   * one, signed by the NEW one, naming it (`recipientIdentityDigest`). Null — and the scope declared — when this device
   * refuses the grant: its signature or its granter's key (ADR 0028 Decision 6), the epoch floor, or a grant behind the
   * current epoch, whose key is not the one the self-grant must carry.
   */
  private async selfGrant(row: RotationPlanScope): Promise<StagedSelfGrant | null> {
    const key = await openOrDeclare(row)
    if (!key) {
      this.unreadable.add(row.scopeId)
      return null
    }
    this.unreadable.delete(row.scopeId)
    try {
      const to = { userId: this.userId, identityPublicKeyB64: this.identityPublicKeyB64 }
      const scope = { scopeId: row.scopeId, kind: row.kind, epoch: row.currentEpoch }
      return {
        scopeId: row.scopeId,
        epoch: row.currentEpoch,
        ...sealGrant(key, scope, to, this.identity.priv.sign),
        recipientIdentityDigest: recipientIdentityDigest(this.identityPublicKeyB64),
      }
    } finally {
      key.fill(0)
    }
  }
}

/** The two lists the server serves of what this account rooted: its open invites and the join records it signed. */
type RootedPlan = { invites: RotationPlanInvite[]; records: RotationPlanJoinRecord[] }

/**
 * What this account rooted, re-signed by the NEW identity into the rotation's stage (cr-trust-anchors-survive-rotation
 * decisions 6 and 12) — the plan is the server's word, so nothing is vouched for that the OUTGOING key did not sign:
 *  - an **open invite** is re-signed — its `invite-key` statement and its anchor grant's `grant` statement — only when
 *    both its current signatures verify under the outgoing identity; any other is declared (both `null`), and the
 *    publish ends it rather than leave it signed by a retired key;
 *  - a **join record** it rooted is endorsed only when it opens under the tenant's epoch keys (the plan opener),
 *    verifies, names this account as its signer, and either names the outgoing key or carries an endorsement that
 *    verifies under it; any other — one this device cannot open, one a retired key signed without endorsement — is
 *    declared (`endorsement: null`).
 * Each list mirrors what the server's stage holds (by a mark of the plan entry it was judged from), so a re-read after
 * `409 rotation_plan_changed` puts up only what moved; a stage the self-grants re-opened starts both over.
 */
class RootedStage {
  private stageId = ''
  private readonly invites = new Map<string, string>()
  private readonly records = new Map<string, string>()

  constructor(
    private readonly self: { userId: string; pub: v1.Identity['pub'] },
    private readonly next: v1.Identity,
  ) {}

  /** Both lists as the server serves them now. */
  async read(): Promise<RootedPlan> {
    const invites = await withRefresh(() => accountRotationApi().getRotationPlanInvites())
    const records = await withRefresh(() => accountRotationApi().getRotationPlanJoinRecords())
    return { invites, records }
  }

  /** Whether [now] holds an entry the stage [stageId] does not hold as judged — something to stage again. */
  moved(stageId: string, now: RootedPlan): boolean {
    if (stageId !== this.stageId) return true
    return now.invites.some((i) => this.invites.get(i.inviteId) !== inviteMark(i))
      || now.records.some((r) => this.records.get(recordKey(r)) !== recordMark(r))
  }

  /** Stage every entry of [now] the stage [stageId] does not hold as judged, in chunks of {@link STAGE_CHUNK}. */
  async put(stageId: string, plan: RotationPlanScope[], now: RootedPlan): Promise<void> {
    if (stageId !== this.stageId) {
      this.stageId = stageId
      this.invites.clear()
      this.records.clear()
    }
    const invites = now.invites.filter((i) => this.invites.get(i.inviteId) !== inviteMark(i))
    for (let i = 0; i < invites.length; i += STAGE_CHUNK) {
      const chunk = invites.slice(i, i + STAGE_CHUNK)
      const staged = chunk.map((inv) => this.resignOrDeclare(inv))
      await withRefresh(() => accountRotationApi().putRotationStageInvites({ stageId, rotationStageInvites: { invites: staged } }))
      for (const inv of chunk) this.invites.set(inv.inviteId, inviteMark(inv))
    }
    const records = now.records.filter((r) => this.records.get(recordKey(r)) !== recordMark(r))
    if (!records.length) return
    const staged = await this.endorseOrDeclare(records, plan)
    for (let i = 0; i < staged.length; i += STAGE_CHUNK) {
      const chunk = staged.slice(i, i + STAGE_CHUNK)
      await withRefresh(() =>
        accountRotationApi().putRotationStageJoinEndorsements({ stageId, rotationStageJoinEndorsements: { endorsements: chunk } }),
      )
      for (let j = i; j < i + chunk.length; j++) this.records.set(recordKey(records[j]), recordMark(records[j]))
    }
  }

  /** The invite re-signed by the NEW identity, when both its current signatures are the outgoing identity's; else
   *  declared. Never a throw: every byte here is the server's. */
  private resignOrDeclare(i: RotationPlanInvite): StagedInviteResign {
    const declared: StagedInviteResign = { inviteId: i.inviteId, anchorEpoch: i.anchorEpoch, inviteKeySignature: null, anchorGrantSignature: null }
    const scope = { scopeId: i.tenantId, kind: 'TENANT', epoch: i.anchorEpoch } as const
    const to = { userId: i.inviteId, identityPublicKeyB64: i.invitePublicKey }
    let signed: invite.InviteKeyInput
    try {
      signed = { tenantId: i.tenantId, inviteId: i.inviteId, invitePublicKey: fromB64std(i.invitePublicKey), expiresAt: Math.floor(i.expiresAt.getTime() / 1000) }
      const mine =
        invite.inviteKeySignatureVerifies(this.self.pub.sign, signed, fromB64std(i.inviteKeySignature)) &&
        grantSignatureVerifies(this.self.pub.sign, scope, to, i.anchorWrappedGrant, i.anchorGrantSignature)
      if (!mine) return declared
    } catch {
      return declared
    }
    return {
      inviteId: i.inviteId,
      anchorEpoch: i.anchorEpoch,
      inviteKeySignature: b64std(invite.signInviteKey(this.next.priv.sign, signed)),
      anchorGrantSignature: signGrant(this.next.priv.sign, scope, to, i.anchorWrappedGrant),
    }
  }

  /** Each record endorsed by the NEW identity or declared, in [records]' order — every tenant opened once through the
   *  plan's row of its tenant scope, its keys zeroed after. */
  private async endorseOrDeclare(records: RotationPlanJoinRecord[], plan: RotationPlanScope[]): Promise<StagedJoinEndorsement[]> {
    const out = new Map<string, StagedJoinEndorsement>()
    for (const tenantId of new Set(records.map((r) => r.tenantId))) {
      const row = plan.find((r) => r.kind === 'TENANT' && r.scopeId === tenantId)
      const opened = row ? await planOpener(row, async () => row)(tenantId).catch(() => null) : null
      try {
        for (const r of records.filter((x) => x.tenantId === tenantId)) {
          out.set(recordKey(r), { tenantId, userId: r.userId, endorsement: opened ? this.endorsement(r, opened.epochs) : null })
        }
      } finally {
        if (opened) zeroEpochKeys(opened.epochs)
      }
    }
    return records.map((r) => out.get(recordKey(r))!)
  }

  /** The NEW identity's endorsement of [r], or null to declare it. */
  private endorsement(r: RotationPlanJoinRecord, epochs: EpochKeys): string | null {
    const address = { tenantId: r.tenantId, userId: r.userId }
    let sealed: Uint8Array
    try {
      sealed = fromB64std(r.sealedJoinRecord)
    } catch {
      return null
    }
    const record = openUnderHeldEpochs(epochs, (key) => invite.openJoinRecord(key, address, sealed))?.value
    if (!record || !invite.joinRecordVerifies(address, record) || record.signerUserId !== this.self.userId) return null
    const statement = { ...address, signerUserId: this.self.userId, sealedJoinRecord: sealed }
    const namesOutgoing = sameBytes(record.signerIdentityPublicKey, v1.serializeIdentityPublic(this.self.pub))
    if (!namesOutgoing && !endorsedUnder(this.self.pub.sign, statement, r.joinEndorsement)) return null
    return b64std(invite.signJoinEndorsement(this.next.priv.sign, statement))
  }
}

/** What an invite entry was judged from: a change to any of it is a new judgment. */
const inviteMark = (i: RotationPlanInvite) =>
  [i.tenantId, i.invitePublicKey, i.expiresAt.getTime(), i.inviteKeySignature, i.anchorEpoch, i.anchorWrappedGrant, i.anchorGrantSignature].join('|')
const recordKey = (r: RotationPlanJoinRecord) => `${r.tenantId}:${r.userId}`
const recordMark = (r: RotationPlanJoinRecord) => `${r.sealedJoinRecord}|${r.joinEndorsement ?? ''}`

/** Whether [endorsement] (base64, the server's) verifies under [signPublicKey] over [statement]. Never a throw. */
function endorsedUnder(signPublicKey: Uint8Array, statement: invite.JoinEndorsementInput, endorsement: string | null | undefined): boolean {
  if (!endorsement) return false
  try {
    return invite.joinEndorsementVerifies(signPublicKey, statement, fromB64std(endorsement))
  } catch {
    return false
  }
}

/**
 * Stage what this account rooted ({@link RootedStage}) into [stage]'s current stage. A stage the server no longer holds
 * (`404 rotation_stage_not_found`) is opened afresh with every self-grant, and both lists go up whole; a list that
 * changed under a chunk (`409 rotation_plan_changed`: an invite consumed, a record written, nothing of the chunk kept)
 * is read again and its delta staged — each bounded by {@link MAX_PUBLISH_ATTEMPTS}. [now] is a read the caller holds.
 */
async function stageRooted(stage: SelfGrantStage, rooted: RootedStage, plan: RotationPlanScope[], now?: RootedPlan): Promise<void> {
  let read = now
  for (let attempt = 1; ; attempt++) {
    try {
      return await rooted.put(stage.id, plan, read ?? (await rooted.read()))
    } catch (e) {
      const retry = attempt < MAX_PUBLISH_ATTEMPTS
      read = undefined
      if (retry && (await isCodedError(e, HttpStatus.notFound, 'rotation_stage_not_found'))) {
        await stage.stageAll(plan)
        continue
      }
      if (retry && (await isCodedError(e, HttpStatus.conflict, 'rotation_plan_changed'))) continue
      throw e
    }
  }
}

/**
 * The one rule both the stage and the ceremony's entry judge a plan row by: the row's current epoch key as this device
 * opens it with the vault's identity ({@link openPlanGrantKey}), or null when this device refuses the grant — the scope
 * is then declared unreadable, never dropped. Any other failure (a read that did not answer) is rethrown.
 */
async function openOrDeclare(row: RotationPlanScope): Promise<Uint8Array | null> {
  try {
    return await openPlanGrantKey(row)
  } catch (e) {
    if (isUnreadableGrant(e)) return null
    throw e
  }
}

/**
 * The scopes of `plan` this device cannot open (decision 3), judged exactly as the stage will judge them — a dry run for
 * the ceremony's entry (decision 11), which lists them before any field as "this device can't open it, so it is
 * already closed to you". Nothing is staged or kept: each key opened is zeroed at once. Needs an unlocked vault; a
 * failure that says nothing about a grant rejects, and the entry then lists none rather than guessing.
 */
export async function unreadableOnThisDevice(plan: RotationPlanScope[]): Promise<string[]> {
  const unreadable: string[] = []
  for (const row of plan) {
    const key = await openOrDeclare(row)
    if (key) key.fill(0)
    else unreadable.push(row.scopeId)
  }
  return unreadable
}

/**
 * The refusals the publish declares — each means the server wrote nothing (decision 12): `400`, `401`, `403`
 * (`secret_key_proof_failed`, `attestation_failed`), `409 rotation_unavailable` / `stale_revision` /
 * `rotation_plan_changed`, `413`, `422`. Anything else — no answer, a `5xx`, a proxy's `502`/`504`, an undeclared `409`
 * — is an unknown outcome.
 */
async function isDeclaredRefusal(e: unknown): Promise<boolean> {
  const { status, code } = await decodeHttpError(e)
  if (DECLARED_STATUSES.includes(status)) return true
  return status === HttpStatus.conflict && DECLARED_CONFLICTS.includes(code ?? '')
}

const DECLARED_STATUSES: number[] = [
  HttpStatus.badRequest, HttpStatus.unauthorized, HttpStatus.forbidden, HttpStatus.contentTooLarge, HttpStatus.unprocessable,
]
const DECLARED_CONFLICTS = ['rotation_unavailable', 'stale_revision', 'rotation_plan_changed']

/**
 * Whether a publish that got no declared answer committed, read from the account itself: the wrapped identity it serves
 * opens under `MK'` to the identity this rotation minted → `committed`; under `MK` to the one it replaces →
 * `notCommitted`; anything else, or a read that fails → `unknown`.
 */
async function readOutcome(
  minted: { masterKey: Uint8Array; identityPublicKey: Uint8Array },
  replaced: { masterKey: Uint8Array; identityPublicKey: Uint8Array },
): Promise<'committed' | 'notCommitted' | 'unknown'> {
  let wrapped: string
  try {
    wrapped = (await withRefresh(() => accountApi().getAccountKeyMaterial())).wrappedIdentityPrivateKey
  } catch {
    return 'unknown'
  }
  if (opensTo(minted.masterKey, wrapped, minted.identityPublicKey)) return 'committed'
  if (opensTo(replaced.masterKey, wrapped, replaced.identityPublicKey)) return 'notCommitted'
  return 'unknown'
}

/** Whether `wrapped` opens under `mk` to the identity whose public bundle is `bundle`. */
function opensTo(mk: Uint8Array, wrapped: string, bundle: Uint8Array): boolean {
  try {
    const priv = v1.deserializeIdentityPrivate(v1.open({ purpose: 'identity-priv' }, mk, fromB64std(wrapped)))
    const pub = v1.serializeIdentityPublic(v1.identityPublicFromPrivate(priv))
    priv.kemSeed.fill(0)
    priv.sign.fill(0)
    return sameBytes(pub, bundle)
  } catch {
    return false
  }
}

/**
 * An earlier publish committed and this vault still holds the Master Key it replaced (`vaultStale`: another tab or
 * device secured the account, or this tab's answer was lost before it could swap). Nothing is minted: the vault is
 * re-opened through the cold unlock with the credential just proven against the account's current floor, and the
 * rotation is finished from there.
 */
async function resumeCommitted(password: string, secretKeyB64: string): Promise<RotationResult> {
  await unlockMasterKey(password, secretKeyB64, { noteDevice: false })
  const published = masterKey()
  if (published) {
    try {
      await carrySessionVault(published) // the session vault sealed the retired key, as after a publish
    } finally {
      published.fill(0)
    }
  }
  const states = await finishRotation().catch((): RotationScopeState[] => [])
  return { outcome: 'resumed', states, unreadableScopeIds: [] }
}

/**
 * The bookkeeping a committed publish leaves, each step best-effort — the rotation is published, and a failure here is
 * the next unlock's or the worker's to repair, never a reason to report the rotation failed:
 *  - this device's trust-state marks move to the new Master Key's slot (ADR 0028 Decision 7), the two documents at the
 *    revision the publish stored them at — this device wrote them, so it has held them;
 *  - the Master-Key-sealed blobs outside the request re-seal (the node pins and the item marks' self-tags, ADR 0068):
 *    one blob per scope, an unreadable one degrades to empty, so failing a published rotation over it would buy
 *    nothing. They run with the vault on the new identity, which opens the self-grants the publish wrote.
 */
async function afterCommit(
  oldMk: Uint8Array, newMk: Uint8Array,
  sealed: { pins: PinStoreWrite; register: { revision: number } }, scopeIds: string[],
): Promise<void> {
  await raiseMarks(newMk, { pins: sealed.pins.revision + 1, register: sealed.register.revision + 1 }).catch(() => undefined)
  await carryMarks(oldMk, newMk).catch(() => undefined)
  try {
    await resealFavoritesUnderNewMasterKey(oldMk, newMk, scopeIds) // core's: the node pins of every scope
  } catch {
    /* an unreadable pin set is an empty one (the nodes are all still there, just no longer pinned) */
  }
  try {
    await registry.masterKeyRotated(oldMk, newMk, scopeIds)
  } catch {
    /* each application's hook is best-effort for its own material (Memories': an item mark whose tag the new key does
       not verify counts as someone else's set — ADR 0068 §9) */
  }
}

/**
 * Read one sealed account document, open it under the OLD Master Key through this device's marks, and seal it
 * under the new one for the revision the rotation will store it at. A refusal there — rolled back, missing after a
 * mark, unreadable — is a refusal of the rotation: re-sealing a rolled-back copy under MK' would make the rollback
 * the rotation's own, and the honest way back from a refused document is the user's reset, not this.
 */
async function resealUnderNewKey<T, W extends { revision: number }>(
  doc: AccountDocumentStore<T, W>,
  oldMk: Uint8Array,
  newMk: Uint8Array,
): Promise<W> {
  const served = await doc.fetch()
  let value: T
  try {
    value = await doc.open(oldMk, served)
  } catch (e) {
    if (e instanceof TrustStateRefused) throw new RotationRefused('trustStateRefused')
    throw e
  }
  return { ...doc.seal(newMk, value, served.revision + 1), revision: served.revision } as W
}

function sameBytes(a: Uint8Array, b: Uint8Array): boolean {
  return a.length === b.length && a.every((x, i) => x === b[i])
}
