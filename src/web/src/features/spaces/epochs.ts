// A scope's key (ADR 0115 decision 1): a scope has ONE key at a time, its current epoch's. A rotation mints a fresh
// random key at epoch `n + 1`, moves everything that was under the old one to it in its commit — each authored value's
// own key re-wrapped (`valueKey.ts`), each scope-root entry re-sealed — and seals nothing older under the new one — so a
// member holds exactly the key its grant carries, and everything under the scope key (a scope-root entry; the value
// keys of a group's name and icon, a recovery copy, a tenant profile, a verification, an item mark) opens under it. What proves a newer key genuine is a succession statement, never a link (decision 5, `scopes.ts`).
// Operator-blind: the key is opened on-device from the caller's own grant; the server stores only ciphertext.

/** The scope key a caller holds: the epoch it is at and its 32 bytes. New content seals under it, and so does every
 *  read of what is sealed under the scope key. */
export type ScopeKey = {
  /** The epoch the key is at — what every scope-level write states (ADR 0115 decision 3) and a new node's `scopeEpoch`. */
  readonly epoch: number
  readonly key: Uint8Array
}

/** The scope key a grant carries at `grantedEpoch` — the one key the caller holds; nothing older is reachable from it. */
export function scopeKeyOf(grantKey: Uint8Array, grantedEpoch: number): ScopeKey {
  return { epoch: grantedEpoch, key: grantKey }
}

/** Best-effort wipe of the held key (on scope leave). */
export function zeroScopeKey(sk: ScopeKey): void {
  sk.key.fill(0)
}

/** A copy of the held key, for work that outlives the holder's own (which may be zeroed meanwhile); the caller zeroes
 *  the copy with {@link zeroScopeKey}. */
export function copyScopeKey(sk: ScopeKey): ScopeKey {
  return { epoch: sk.epoch, key: sk.key.slice() }
}
