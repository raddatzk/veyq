// An authority's order in the moderation surface (uc.moderation.authority-order; ADR 0093 § 12, ADR 0099 § 6) — the
// moderator's door, key-proved with no case (`enterModerationOrder`); the Server Owner's twin is admin-web's.
//   - A pasted link leaves as its id alone: its fragment (`/s/{id}#k=…`, core/sharing/shareLink.ts) is the share's key,
//     and an order acts on the id (`parseShareReference`; `orders.test.ts` bites it by sending the URL whole).
//   - The authority's reference is sealed to the current epoch, bound to the order id this client mints
//     (`moderation-order` [epoch, orderId]), so a retry within the hour is the same order; it opens with that epoch's key.
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { moderationApi } from '../../../api/client'
import type { AuthorityOrder, AuthorityOrderKind } from '../../../api/generated/moderation'
import { withRefresh } from '../../../app/sessionRefresh'
import { currentModerationKey, moderationKeyOf } from '../moderationKeys'
import { ModerationKeyMissing, proofFor } from '../proof'

export type { AuthorityOrder, AuthorityOrderKind } from '../../../api/generated/moderation'

/** A share id: 43 characters of base64url (`contracts/api/sharing.openapi.yaml` § ShareId). */
const SHARE_ID = /^[A-Za-z0-9_-]{43}$/

/**
 * The share id an order names, from what the authority sent: a share URL (`https://host/s/{id}#k=…`, its fragment — the
 * share's key — dropped here and never sent) or a bare id. Null when neither.
 */
export function parseShareReference(input: string): string | null {
  const text = input.trim()
  if (SHARE_ID.test(text)) return text
  const withoutFragment = text.split('#')[0]
  let path = withoutFragment
  try {
    path = new URL(withoutFragment).pathname
  } catch {
    // not an absolute URL: read it as a path
  }
  const m = /(?:^|\/)s\/([A-Za-z0-9_-]{43})(?:\/|$)/.exec(path)
  return m ? m[1] : null
}

/** What the moderator typed. */
export interface OrderEntry {
  kind: AuthorityOrderKind
  share: string
  reference: string
  receivedAt: Date
  holdDays?: number | null
  deferStatementUntil?: Date | null
}

export class OrderInvalid extends Error {
  constructor(readonly reason: 'share' | 'reference') {
    super(`order_invalid:${reason}`)
  }
}

/** Enter an order with the key proof: the share reduced to its id, the reference sealed to the current epoch. */
export async function enterOrder(entry: OrderEntry, newId: () => string = () => crypto.randomUUID()): Promise<AuthorityOrder> {
  const shareId = parseShareReference(entry.share)
  if (!shareId) throw new OrderInvalid('share')
  const reference = entry.reference.trim()
  if (!reference) throw new OrderInvalid('reference')
  const held = currentModerationKey()
  if (!held) throw new ModerationKeyMissing()
  const id = newId()
  let sealed: string
  try {
    sealed = b64std(v1.sealOrderReference(held.epoch, id, v1.moderationBundle(held.key).kem, reference))
  } finally {
    held.key.fill(0)
  }
  const body = {
    id,
    kind: entry.kind,
    shareId,
    receivedAt: entry.receivedAt,
    reference: { epoch: held.epoch, sealedReference: sealed },
    holdDays: entry.kind === 'INFORMATION' ? (entry.holdDays ?? null) : null,
    deferStatementUntil: entry.kind === 'REMOVAL' ? (entry.deferStatementUntil ?? null) : null,
  }
  const proof = await proofFor('enterModerationOrder')
  return withRefresh(() => moderationApi().enterModerationOrder({ authorityOrderRequest: body, ...proof }))
}

/** The orders recorded on this instance, newest first. */
export async function listOrders(): Promise<AuthorityOrder[]> {
  const proof = await proofFor('listModerationOrders')
  return (await withRefresh(() => moderationApi().listModerationOrders({ ...proof }))).orders
}

/** An order's reference, opened with its epoch's key; null when this page holds no key that opens it. */
export function openReferenceOf(order: Pick<AuthorityOrder, 'id' | 'epoch' | 'sealedReference'>): string | null {
  const m = moderationKeyOf(order.epoch)
  if (!m) return null
  try {
    return v1.openOrderReference(order.epoch, order.id, v1.moderationKemSecret(m), fromB64std(order.sealedReference))
  } catch {
    return null
  } finally {
    m.fill(0)
  }
}
