// Test-only helper: a scope key at epoch 0 as a {@link ScopeKey} — what a grant of a scope that has never rotated
// carries. Production code takes the ScopeKey from the grant; tests use this shorthand.
import type { ScopeKey } from './epochs'

export function epochsOf(key: Uint8Array, epoch = 0): ScopeKey {
  return { epoch, key }
}
