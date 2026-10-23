// One helper of the volume seeder (`seed-volume.mts`), kept apart because it has no side effect on import: the seeder's
// other modules install the instance's transport when imported, so this is the part a check can load on its own.
// Temporary, with the rest of the harness (`cr-volume-measurement-cax` deletes it).
import { httpStatus } from '../src/shared/httpError'

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

/** How long a 429 asks to wait: its `Retry-After` in seconds (the server's limiters send one), a minute if it names none. */
export function retryAfterMs(e: unknown): number {
  const header = (e as { response?: Response } | null)?.response?.headers?.get('retry-after')
  const seconds = header ? Number(header) : NaN
  return (Number.isFinite(seconds) && seconds > 0 ? seconds : 60) * 1000 + 250
}

/**
 * [step] again after each `429 rate_limited`, waiting as long as the answer's `Retry-After` says. The per-IP limiter
 * (`veyq.ratelimit.auth.*`, 60 requests a minute by default) counts every sign-in this one host makes, and the profile
 * phase signs in once per member, faster than that: the collection run of 2026-10-23 stopped there at ~500 members.
 * The seeder waits; it never asks the server for a higher limit. A step must be safe to run again from its start.
 * [tries] bounds it, so a limiter that never opens again still ends the run, saying so.
 */
export async function backingOff<T>(
  what: string, step: () => Promise<T>, tries = 30, wait: (ms: number) => Promise<unknown> = sleep,
): Promise<T> {
  for (let attempt = 1; ; attempt++) {
    try {
      return await step()
    } catch (e) {
      if (httpStatus(e) !== 429 || attempt >= tries) throw e
      const ms = retryAfterMs(e)
      console.log(`${what}: 429 rate_limited, waiting ${Math.round(ms / 1000)} s (attempt ${attempt} of ${tries})`)
      await wait(ms)
    }
  }
}
