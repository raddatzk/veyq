// uc.moderation.authority-order (admin-web, ADR 0093 § 12, ADR 0099 § 6) — the Server Owner's door to an authority's
// order: Server → Moderation's order form and `veyq-admin order remove|preserve|inform` both call it, so the rules below
// hold for the UI and the CLI alike.
//
// What it keeps honest:
//   - a pasted share URL is reduced to its id HERE, before anything leaves: the fragment carries the share's key
//     (`/s/{id}#k=…`), and an order needs no key — it acts on the id (`parseShareReference`; `orders.test.ts` bites it
//     by sending the URL whole);
//   - the authority's reference is sealed to the moderation key's PUBLIC half (`moderation-order` [epoch, orderId]) with
//     the order id this client mints, so a retry within the hour is the same order; admin-web holds no private half and
//     opens nothing (`moderation.noOpen.test.ts` holds that for every file of the CLI);
//   - the server answers what it did; the Annex texts are rendered from that answer (`orderForms.ts`).
import { instanceModerationApi } from '../../api/client'
import type { AuthorityOrder, AuthorityOrderKind } from '../../api/generated/instance'
import { sealOrderReference } from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'

export type { AuthorityOrder } from '../../api/generated/instance'
export type OrderKind = AuthorityOrderKind

/** A share id: 43 characters of base64url (`contracts/api/sharing.openapi.yaml` § ShareId). */
const SHARE_ID = /^[A-Za-z0-9_-]{43}$/

/**
 * The share id an order names, from what the authority sent: a share URL (`https://host/s/{id}#k=…`, its fragment —
 * the share's key — dropped here and never sent) or a bare id. Null when neither.
 */
export function parseShareReference(input: string): string | null {
  const text = input.trim()
  if (SHARE_ID.test(text)) return text
  const withoutFragment = text.split('#')[0]
  const path = (() => {
    try {
      return new URL(withoutFragment).pathname
    } catch {
      return withoutFragment
    }
  })()
  const m = /(?:^|\/)s\/([A-Za-z0-9_-]{43})(?:\/|$)/.exec(path)
  return m ? m[1] : null
}

/** What the person entering the order typed. */
export interface OrderEntry {
  kind: OrderKind
  share: string
  reference: string
  receivedAt: Date
  holdDays?: number | null
  deferStatementUntil?: Date | null
}

export class OrderInvalid extends Error {
  constructor(readonly reason: 'share' | 'reference' | 'moderationOff') {
    super(`order_invalid:${reason}`)
  }
}

/**
 * Enter an order on the admin channel (`enterInstanceModerationOrder`): the share reduced to its id, the reference
 * sealed to the current epoch's public half. Refusals: the server's (`order_exists`, `moderation_epoch_changed`,
 * `deferral_out_of_bounds`, …) as an HTTP error; this client's as [OrderInvalid], with nothing sent.
 */
export async function enterOrder(entry: OrderEntry, newId: () => string = () => crypto.randomUUID()): Promise<AuthorityOrder> {
  const shareId = parseShareReference(entry.share)
  if (!shareId) throw new OrderInvalid('share')
  const reference = entry.reference.trim()
  if (!reference) throw new OrderInvalid('reference')
  const moderation = await instanceModerationApi().getInstanceModeration()
  if (moderation.currentEpoch == null || !moderation.currentKemPublicKey) throw new OrderInvalid('moderationOff')
  const id = newId()
  const sealed = sealOrderReference(moderation.currentEpoch, id, fromB64std(moderation.currentKemPublicKey), reference)
  return instanceModerationApi().enterInstanceModerationOrder({
    authorityOrderRequest: {
      id,
      kind: entry.kind,
      shareId,
      receivedAt: entry.receivedAt,
      reference: { epoch: moderation.currentEpoch, sealedReference: b64std(sealed) },
      holdDays: entry.kind === 'INFORMATION' ? (entry.holdDays ?? null) : null,
      deferStatementUntil: entry.kind === 'REMOVAL' ? (entry.deferStatementUntil ?? null) : null,
    },
  })
}
