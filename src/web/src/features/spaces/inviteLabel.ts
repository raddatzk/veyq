// A link member invite's private label (ADR 0129 decision 7): the owners' own name for whom a link is for ("Oma"),
// sealed as a tenant value — under a value key of its own whose wrap `K_tenant` holds (ADR 0115) — and bound to the
// tenant and the invite, so a server that serves one invite's label beside another's gets one that does not open. Every
// owner of the tenant opens it; the server stores two opaque blobs and hands them out only in the owners' pending list.
// A re-issue mints a new invite id, so the label is sealed anew for it. Crypto-only (no api/client, no Vue).
import * as v1 from '@veyq/crypto/v1'
import { fromB64std } from '@veyq/crypto/base64'
import type { ScopeKey } from './epochs'
import { openValue, sealValue } from './valueKey'

/** Which invite a label names: the tenant scope it is sealed in and the invite (ADR 0072). */
export type InviteLabelAddress = { scopeId: string; inviteId: string }

/** The longest label the owner may type, in characters — what fits the contract's 512 base64 characters with room. */
export const INVITE_LABEL_MAX_LENGTH = 80

/** A label's own seal context — what the wrap of its value key is addressed by (`value-key`, ADR 0115). */
export const inviteLabelContext = (a: InviteLabelAddress) =>
  ({ purpose: 'member-invite-label', scopeId: a.scopeId, inviteId: a.inviteId }) as const

/** A label as the wire carries it: sealed under a fresh value key, and that key's wrap under `K_tenant`. */
export type SealedInviteLabel = { sealedLabel: string; wrappedLabelValueKey: string }

const encoder = new TextEncoder()
const decoder = new TextDecoder()

/** The label as it is stored: trimmed, at most {@link INVITE_LABEL_MAX_LENGTH} characters, or null for none. */
export function normalizeInviteLabel(label: string | null | undefined): string | null {
  const trimmed = (label ?? '').trim()
  return trimmed ? Array.from(trimmed).slice(0, INVITE_LABEL_MAX_LENGTH).join('') : null
}

/** Seal [label] for the invite at [address] under a fresh value key wrapped under [tenant]'s key at its epoch. */
export function sealInviteLabel(tenant: ScopeKey, address: InviteLabelAddress, label: string): SealedInviteLabel {
  const context = inviteLabelContext(address)
  const sealed = sealValue(tenant, context, (valueKey) => v1.sealField(context, valueKey, encoder.encode(label)))
  return { sealedLabel: sealed.sealed, wrappedLabelValueKey: sealed.wrapped }
}

/**
 * The label of the invite at [address], opened with the tenant key the caller holds — or null when it has none, or it
 * does not open: another invite's label, a wrap under another key, one a rotator could not open, a corrupt blob. Never a
 * label this client could not verify as this invite's (uc.spaces.manage-member-invites E2).
 */
export function openInviteLabel(
  tenant: ScopeKey, address: InviteLabelAddress, sealedLabel: string | null | undefined, wrapped: string | null | undefined,
): string | null {
  if (!sealedLabel) return null
  const context = inviteLabelContext(address)
  try {
    const plaintext = openValue(tenant, context, wrapped, (valueKey) => v1.openField(context, valueKey, fromB64std(sealedLabel)))
    return normalizeInviteLabel(decoder.decode(plaintext))
  } catch {
    return null
  }
}
