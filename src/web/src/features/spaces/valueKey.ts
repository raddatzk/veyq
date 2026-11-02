// A scope-level value's own key (ADR 0115, owner 2026-11-02): a rotation re-wraps keys and never rewrites a value. Every
// value that has an author — a member's tenant name, picture and join record, a verification, a Memories item mark, a
// group's name and a space's icon, a link's recovery copy (`v1.VALUE_KEY_PURPOSES`) — is sealed under a value key `K_v`
// of its own, 32 random bytes its author mints at EVERY write; only `K_v` is sealed under the scope key (`value-key`),
// bound to the scope, the epoch whose key wraps it and the value's address (SHA-256 of the value's own seal context). A
// field-sealed value derives its `K_field` from `K_v`; a plain-sealed one is sealed under `K_v` itself — the value's own
// purpose and binding are unchanged.
//
// So a rotator — owner or member alike — opens each wrap with the old key and wraps the same `K_v` under the new one
// ({@link rewrapValueKey}); a wrap it cannot open it replaces by one nobody opens, and the value reads as unreadable, as
// before. A wrap moved onto another value's slot does not open, and the value under a substituted `K_v` does not open
// either: a rotator can junk a value, never forge one.
//
// Every wrap is under the scope's current key, since a rotation re-wraps them all in its commit: a reader opens it with
// the one key it holds, at that key's epoch, and a wrap under any other key reads as unreadable. Crypto-only (no
// api/client, no Vue), so the dev scripts seal identically.
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import type { ScopeKey } from './epochs'

/** One write of a value as the wire carries it: its ciphertext under a fresh `K_v`, and that `K_v`'s wrap under the scope
 *  key — both base64. */
export type SealedValue = { sealed: string; wrapped: string }

/** Where [value]'s key is wrapped under the key of [epoch]: its scope (every value context names one) and its address. */
const slotOf = (epoch: number, value: v1.ValueContext): v1.ValueKeySlot => ({ scopeId: value.scopeId, epoch, value })

/**
 * Seal one write of [value]: a fresh `K_v`, the value sealed under it by [seal] (its own purpose and binding), and `K_v`
 * wrapped under [scope]'s key at its epoch. `K_v` is zeroed before this returns; one is never reused for another write.
 */
export function sealValue(scope: ScopeKey, value: v1.ValueContext, seal: (valueKey: Uint8Array) => Uint8Array): SealedValue {
  const valueKey = v1.generateValueKey()
  try {
    return { sealed: b64std(seal(valueKey)), wrapped: wrapValueKeyOf(scope, value, valueKey) }
  } finally {
    valueKey.fill(0)
  }
}

/** [valueKey] wrapped under [scope]'s key at its epoch, bound to [value]'s address (base64). For a writer that hands the
 *  same `K_v` on beside its wrap (a group name's pending invitees); everything else seals through {@link sealValue}. */
export function wrapValueKeyOf(scope: ScopeKey, value: v1.ValueContext, valueKey: Uint8Array): string {
  return b64std(v1.wrapValueKey(scope.key, slotOf(scope.epoch, value), valueKey))
}

/** Open [value]'s `K_v` from its [wrapped] key with the scope key the caller holds. Throws `OpenRefused` for a wrap under
 *  another key or epoch, or one of another value; the caller zeroes what it returns. */
export function openValueKey(scope: ScopeKey, value: v1.ValueContext, wrapped: string): Uint8Array {
  return v1.unwrapValueKey(scope.key, slotOf(scope.epoch, value), fromB64std(wrapped))
}

/** [open] run with [value]'s `K_v`, opened from [wrapped] and zeroed after. Throws when there is no wrap, when it does not
 *  open, and whatever [open] throws — a reader turns each into its "unreadable" fallback. */
export function openValue<T>(
  scope: ScopeKey, value: v1.ValueContext, wrapped: string | null | undefined, open: (valueKey: Uint8Array) => T,
): T {
  if (!wrapped) throw new v1.OpenRefused('value-key')
  const valueKey = openValueKey(scope, value, wrapped)
  try {
    return open(valueKey)
  } finally {
    valueKey.fill(0)
  }
}

/**
 * A rotation's move of one value (ADR 0115 decision 2): its `K_v` opened from [wrapped] with [oldKey] and wrapped, the
 * same bytes, under [newKey] at the new epoch — the value itself is never touched. A wrap that does not open under the old
 * key is replaced by a stand-in: a random `K_v` wrapped at the same slot under a random key dropped at once, of the same
 * length, so the value reads as unreadable, as it did before — never a wrap under the new key, which would open a key
 * nobody's value is under. The server cannot tell the stand-in from a re-wrap.
 */
export function rewrapValueKey(oldKey: ScopeKey, newKey: ScopeKey, value: v1.ValueContext, wrapped: string): string {
  let valueKey: Uint8Array
  try {
    valueKey = openValueKey(oldKey, value, wrapped)
  } catch {
    return standInWrap(newKey.epoch, value)
  }
  try {
    return wrapValueKeyOf(newKey, value, valueKey)
  } finally {
    valueKey.fill(0)
  }
}

/** A wrap at [value]'s slot of [epoch] that opens under no key anyone holds (see {@link rewrapValueKey}). */
function standInWrap(epoch: number, value: v1.ValueContext): string {
  const dropped = v1.randomBytes(32)
  const nobodys = v1.generateValueKey()
  try {
    return wrapValueKeyOf({ epoch, key: dropped }, value, nobodys)
  } finally {
    dropped.fill(0)
    nobodys.fill(0)
  }
}
