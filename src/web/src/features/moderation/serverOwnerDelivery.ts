// The last holder's hand-over (ADR 0101 decision 9, ADR 0099 § 5, uc.account.erase-account A8): when the account that
// erases itself is the last moderator holding the moderation key, its erasure carries every live epoch it holds to the
// Server Owner, who is reappointed by that delivery. This is the client half — the delivery of `unlockModeration.ts`
// (step 3) aimed at one recipient the erasure preview names:
//
//   1. anchor the Server Owner's identity by the head the preview served beside it (ADR 0028 Decision 2, as every wrap
//      to another account's key is judged) — an anomaly delivers nothing;
//   2. open this account's grants with the identity private key of the vault the settings already hold, and check each
//      against its published bundle (`openGrants`: a substituted bundle opens nothing);
//   3. seal each epoch the Server Owner misses to the Server Owner's identity (`moderation-grant` [epoch]) and zero
//      every opened key.
//
// It uploads nothing: the grants go in the erasure request itself (`EraseOwnAccountRequest.serverOwnerDelivery`), so
// the delivery and the erasure are one transaction — a refused erasure reappoints nobody. Unlike the moderation surface
// it asks for no Secret Key: it opens no case and keeps no key, and the erasure proves the password besides.
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { moderationApi } from '../../api/client'
import type { ServerOwnerKeyDelivery, ServerOwnerKeyDeliveryUpload } from '../../api/generated/account'
import { withRefresh } from '../../app/sessionRefresh'
import { isUnlocked } from '../../app/vault'
import { anchorIdentity } from '../account/identityPins'
import { TrustStateRefused } from '../account/trustMarks'
import { openGrants } from './unlockModeration'

/** Why the hand-over could not be built; nothing was sent. */
export class ServerOwnerDeliveryRefused extends Error {
  constructor(public readonly reason: 'locked' | 'unanchored' | 'notHeld') {
    super(`server owner delivery refused: ${reason}`)
    this.name = 'ServerOwnerDeliveryRefused'
  }
}

/**
 * Seal every epoch [delivery] names to the Server Owner's identity, once it is anchored. Rejects with
 * {@link ServerOwnerDeliveryRefused} `locked` while this page holds no unlocked vault (the pins and the grants need it),
 * `unanchored` when the Server Owner's key is not reached from what this device pinned
 * (or this device refuses its own pin store), `notHeld` when this account does not hold an epoch it is asked for.
 */
export async function sealForServerOwner(delivery: ServerOwnerKeyDelivery): Promise<ServerOwnerKeyDeliveryUpload> {
  if (!isUnlocked()) throw new ServerOwnerDeliveryRefused('locked')
  let anchor
  try {
    anchor = await anchorIdentity(delivery.serverOwnerId, {
      identityPublicKey: delivery.identityPublicKey,
      keyChainHead: delivery.keyChainHead,
    })
  } catch (e) {
    if (e instanceof TrustStateRefused) throw new ServerOwnerDeliveryRefused('unanchored')
    throw e
  }
  if (anchor === 'anomaly') throw new ServerOwnerDeliveryRefused('unanchored')

  const grants = await withRefresh(() => moderationApi().listMyModerationGrants())
  const opened = openGrants(grants.epochs)
  try {
    if (delivery.epochs.some((epoch) => !opened.has(epoch))) throw new ServerOwnerDeliveryRefused('notHeld')
    const recipient = v1.deserializeIdentityPublic(fromB64std(delivery.identityPublicKey))
    return {
      identityPublicKey: delivery.identityPublicKey,
      grants: delivery.epochs.map((epoch) => ({
        epoch,
        sealedGrant: b64std(v1.sealTo({ purpose: 'moderation-grant', epoch }, recipient.kem, opened.get(epoch)!)),
      })),
    }
  } finally {
    for (const m of opened.values()) m.fill(0)
  }
}
