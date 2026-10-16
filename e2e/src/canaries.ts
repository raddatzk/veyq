// The suite half of the wire assertion (ADR 0053, cc.security.wire-assertion) at browser level. web/'s API-level
// suites record what they UNSEAL from the server's own responses (`web/src/test/canaries.ts`); this suite has no keys
// in the test process — the keys live in the page — so it records what it TYPED, and what it handed the page to
// upload: the same plaintext, known from the other end. After the browser stage `scripts/run_stack_e2e.py` sweeps the
// stack with it — the database, the bucket, every container log, every statement the database received — and the run
// is red if any of it is readable there (ADR 0090 decision 5).
//
// **Record only what the product promises to seal or never to send**: a password, a Secret Key, a Memory's name, a
// photo's name and the text inside its bytes. A value the contract DECLARES plaintext (an email is `pii`, a tenant's
// name `public`, a tenant username `pii`) is not a canary — the sweep would find it, correctly, and the fix would be
// to stop recording it, never to quieten the sweep.
//
// **One collision rule with web/'s recorder** (`@veyq/crypto/canaryRule`): a short value spelled only in the
// base64/hex alphabet is refused, because the sweep's plain substring match would find it inside random ciphertext.
import { test } from '@playwright/test'
import { collidesWithCiphertext, MIN_CANARY_LENGTH } from '@veyq/crypto/canaryRule'
import { writeFileSync } from 'node:fs'
import { basename } from 'node:path'
import { stack } from './stack'

/** Per spec file, so a worker that restarted after a failure never overwrites what an earlier file recorded. */
const recorded = new Map<string, Set<string>>()

/**
 * Record [value] as a canary of the current test file, then hand it back — so a scenario writes
 * `fill(recordTyped(name))` and no typed value can skip the record.
 *
 * Refuses (throws) a value the sweep could match by chance, and the empty string, which is a substring of everything.
 * Writes the file's whole set on every call (`canaries-<spec>.json` in the run directory), so a test that dies
 * mid-way leaves on disk exactly what it had typed up to then.
 */
export function recordTyped(value: string): string {
  if (value.length === 0) throw new Error('recordTyped: the empty string is a substring of every row — not a canary')
  if (collidesWithCiphertext(value)) {
    throw new Error(
      `recordTyped: ${JSON.stringify(value)} is shorter than ${MIN_CANARY_LENGTH} characters and spelled only in the `
        + 'base64/hex alphabet, so the sweep can find it inside random ciphertext (inbox #323). Type longer test data, '
        + 'or a value with a space or an umlaut.',
    )
  }
  const spec = basename(test.info().file).replace(/\.ts$/, '')
  const set = recorded.get(spec) ?? new Set<string>()
  set.add(value)
  recorded.set(spec, set)
  writeFileSync(`${stack().outDir}/canaries-${spec}.json`, JSON.stringify([...set], null, 2), 'utf8')
  return value
}
