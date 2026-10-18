// The instance moderation key as the moderation surface holds it (ADR 0099 §§ 2, 6): one `M_n` per epoch this account
// holds a grant of, opened at unlock and kept in memory only — never stored, never in the vault. It is ALL the surface
// holds once the unlock has dropped the Master Key and the identity private key (`unlockModeration.ts`).

const keys = new Map<number, Uint8Array>()
let current: number | null = null

/** Hold the opened keys; [currentEpoch] is the epoch reports seal to and a key proof signs with. */
export function holdModerationKeys(opened: Map<number, Uint8Array>, currentEpoch: number): void {
  clearModerationKeys()
  for (const [epoch, m] of opened) keys.set(epoch, m)
  current = currentEpoch
}

/** A copy of the current epoch's `M_n`, or null when this account does not hold it (yet). The caller zeroes it. */
export function currentModerationKey(): { epoch: number; key: Uint8Array } | null {
  if (current === null) return null
  const m = keys.get(current)
  return m ? { epoch: current, key: m.slice() } : null
}

/** A copy of [epoch]'s `M_n` — what opens a report or a note sealed to it — or null when not held. The caller zeroes it. */
export function moderationKeyOf(epoch: number): Uint8Array | null {
  return keys.get(epoch)?.slice() ?? null
}

/** Whether any key is held. */
export const holdsModerationKeys = (): boolean => keys.size > 0

/** Zero and forget every key (sign-out, step-down, leaving the page). */
export function clearModerationKeys(): void {
  for (const m of keys.values()) m.fill(0)
  keys.clear()
  current = null
}
