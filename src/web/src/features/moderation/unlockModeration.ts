// The moderation surface's unlock (ADR 0099 §§ 3, 6,
// uc.moderation.serve-as-moderator steps 4-6). The vault is opened ONLY to open the moderation grants:
//
//   1. sign in if this page holds no session of this account, and open the vault with the password AND the Secret Key —
//      never a device wrap (owner decision Q2), and recording nothing about this device;
//   2. open every moderation grant with the identity private key, derive each epoch's public bundle from the opened
//      M_n and refuse on a published bundle that differs (ADR 0099 § 3: moderators watch the published key);
//   3. deliver every live epoch this account holds to each accepted moderator who misses it — after the pin check of
//      ADR 0028, as a scope grant's delivery does (`grantDeliveryWorker`): the pin store is sealed under the Master
//      Key, so this runs here, inside the same unlock, before step 4;
//   4. zero the Master Key and the identity private key (`clearVault`) whatever happened, keeping only the M_n.
//
// It fetches no scope grant and touches no scope, content or member client: the vault test records every client call.
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { moderationApi } from '../../api/client'
import { LoginVerifyRequestSessionScopeEnum } from '../../api/generated/auth'
import type { ModerationEpochGrant, ModerationPendingDelivery } from '../../api/generated/moderation'
import { account } from '../../app/account'
import { session } from '../../app/session'
import { withRefresh } from '../../app/sessionRefresh'
import { clearVault, identityPrivate } from '../../app/vault'
import { anchorIdentity, type Anchor } from '../account/identityPins'
import { login } from '../account/login/login'
import { TrustStateRefused } from '../account/trustMarks'
import { unlockMasterKey } from '../account/unlock/unlock'
import { holdModerationKeys } from './moderationKeys'
import { proofFor } from './proof'

/** The published bundle of an epoch is not the one its opened key derives: the unlock stops (ADR 0099 § 3). */
export class ModerationBundleMismatch extends Error {
  constructor(readonly epoch: number) {
    super(`moderation_bundle_mismatch: epoch ${epoch}`)
    this.name = 'ModerationBundleMismatch'
  }
}

export type ModerationUnlock = {
  /** Whether this account holds the current epoch — what every key-proved operation needs. */
  holdsCurrent: boolean
  /** Moderators the key was delivered to in this unlock. */
  delivered: number
  /** Deliveries declined because the recipient's key is not anchored from the head this account pinned (decision 9). */
  declined: number
}

export async function unlockModeration(email: string, password: string, secretKey: string): Promise<ModerationUnlock> {
  clearVault() // whatever the page held before, this unlock is the only way the vault opens here
  try {
    if (session.accessToken === null || account.email.toLowerCase() !== email.trim().toLowerCase()) {
      await login(email.trim(), password, LoginVerifyRequestSessionScopeEnum.Session)
    }
    await unlockMasterKey(password, secretKey, { noteDevice: false })
    const grants = await withRefresh(() => moderationApi().listMyModerationGrants())
    const opened = openGrants(grants.epochs)
    const holdsCurrent = opened.has(grants.currentEpoch)
    holdModerationKeys(opened, grants.currentEpoch)
    const { delivered, declined } = holdsCurrent ? await deliverPending(opened) : { delivered: 0, declined: 0 }
    return { holdsCurrent, delivered, declined }
  } finally {
    clearVault()
  }
}

/** Open every grant this account holds and check it against the published bundle; a mismatch opens nothing. */
function openGrants(epochs: ModerationEpochGrant[]): Map<number, Uint8Array> {
  const priv = identityPrivate()
  if (!priv) throw new Error('moderation unlock requires an unlocked vault')
  const opened = new Map<number, Uint8Array>()
  try {
    for (const e of epochs) {
      if (!e.sealedGrant) continue
      const m = v1.openAs({ purpose: 'moderation-grant', epoch: e.epoch }, priv.kemSeed, fromB64std(e.sealedGrant))
      const published = { kem: fromB64std(e.kemPublicKey), sign: fromB64std(e.signPublicKey) }
      if (!v1.moderationBundleEquals(v1.moderationBundle(m), published)) {
        m.fill(0)
        throw new ModerationBundleMismatch(e.epoch)
      }
      opened.set(e.epoch, m)
    }
    return opened
  } catch (err) {
    for (const m of opened.values()) m.fill(0)
    throw err
  } finally {
    priv.kemSeed.fill(0)
    priv.sign.fill(0)
  }
}

/** Seal what each pending moderator misses to their identity, once it is anchored; any other is declined.
 *
 * A pending delivery serves the moderator's key beside its key-chain head (`ModerationPendingDelivery.keyChainHead`), so
 * it is judged as every wrap to another account is ({@link anchorIdentity}, ADR 0028 Decision 2): the pinned
 * head matches with no fetch; any other head must be one the chain reaches from the pinned head, ending at the served key
 * — a retired key never does — and a moderator this device never met is pinned at first use through their chain, which
 * the server serves between two accepted moderators even where they share no tenant. An anomaly, or a chain the server
 * does not serve, is declined here, for another holder to deliver. */
async function deliverPending(opened: Map<number, Uint8Array>): Promise<{ delivered: number; declined: number }> {
  const list = await withRefresh(async () =>
    moderationApi().listPendingModerationDeliveries(await proofFor('listPendingModerationDeliveries')),
  )
  let delivered = 0
  let declined = 0
  for (const d of list.deliveries) {
    let anchor: Anchor
    try {
      anchor = await anchorIdentity(d.accountId, { identityPublicKey: d.identityPublicKey, keyChainHead: d.keyChainHead })
    } catch (e) {
      if (e instanceof TrustStateRefused) break // the local trust state cannot vouch: deliver nothing more, decline nothing
      throw e
    }
    if (anchor === 'anomaly') {
      declined++
      continue
    }
    await deliver(d, opened)
    delivered++
  }
  return { delivered, declined }
}

async function deliver(d: ModerationPendingDelivery, opened: Map<number, Uint8Array>): Promise<void> {
  const recipient = v1.deserializeIdentityPublic(fromB64std(d.identityPublicKey))
  const grants = d.epochs
    .filter((epoch) => opened.has(epoch))
    .map((epoch) => ({
      epoch,
      sealedGrant: b64std(v1.sealTo({ purpose: 'moderation-grant', epoch }, recipient.kem, opened.get(epoch)!)),
    }))
  if (grants.length === 0) return
  await withRefresh(async () =>
    moderationApi().putModerationDelivery({
      accountId: d.accountId,
      moderationDelivery: { identityPublicKey: d.identityPublicKey, grants },
      ...(await proofFor('putModerationDelivery')),
    }),
  )
}
