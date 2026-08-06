// Test-only helper: wrap a single scope content key as an unrotated {@link EpochKeys} set (epoch 0 only). Mirrors
// what `deriveEpochKeys(key, 0, [])` yields for a scope that has never rotated, so pre-epoch tests keep passing
// unchanged (their key IS E_0). Production code derives EpochKeys from the grant; tests use this shorthand.
import type { EpochKeys } from './epochs'

export function epochsOf(key: Uint8Array): EpochKeys {
  return { current: 0, keys: new Map([[0, key]]) }
}
