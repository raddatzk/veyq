// The suite half of the wire assertion (cc.security.wire-assertion, ADR 0053): the **derivation** of canaries,
// and the seam to the orchestrator that sweeps with them.
//
// The oracle is canary *absence* — "this plaintext, which the client sealed, appears nowhere the operator can
// read". The canary set is not registered anywhere: it is whatever comes back from **unsealing the server's own
// responses** with the keys the suite already holds. So nothing has to be remembered and no value can be
// forgotten out of the set. `scripts/run_web_e2e.py` reads the file this writes and proves the absence against
// that suite's own `veyq_e2e_<n>` database — the oracle lives outside this process on purpose, so *passing*
// cannot be arranged from in here.
//
// **Hand it the unsealed envelope, never the row that carried it.** `recordCanaries(unsealNodeMeta(k, meta))`
// records date + location + cover in one call, including the leaves the suite never asserted on — that is the
// point. `recordCanaries(node)` on a server `TreeNode` would record `wrappedMeta`/`wrappedContentKey`, which
// are base64 ciphertext straight out of the database, and every sweep would then "find" them. That mistake is
// loud rather than silent (the sweep fails naming the canary), which is the direction a security sensor must
// fail in — but it is still a mistake, so: the argument is what an `unseal*`/`open*` returned, or a projection
// of it (`tree.map((n) => n.name)`), never the sealed carrier.
//
// **Why not a hook in the crypto primitive.** `@veyq/crypto`'s v1.ts is conformance-pinned to
// `contracts/crypto/vectors/v1.json` and sits in the Stryker mutation scope — the last file in the repo that
// should grow a test affordance. And `aeadSeal` is reached by `symWrap`/`asymWrap` too, so a hook there would
// record **key material**: searching a database for random bytes proves nothing while burying the strings that
// matter. ADR 0053 § Decision 2.
import { collidesWithCiphertext, MIN_CANARY_LENGTH } from '@veyq/crypto/canaryRule'
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname } from 'node:path'

/** Everything recorded so far this suite. A Set, so a value sealed twice is one canary. */
const collected = new Set<string>()

/**
 * Keep `string` leaves; drop everything else **by type**, not by a name filter.
 *
 * The type test is what keeps key material out without depending on what a function is called: `openContentKey`
 * and `openPersonalScope` return `Uint8Array`, `openOriginal` returns media bytes, and all of them are dropped
 * here even if a suite hands one over — while `unsealNodeMeta`'s `{ v: 1, location: { name, nameSource, … } }`
 * yields exactly its strings. Numbers (a crop's `x`/`width`, a media's `w`/`h`, `capturedAt`) go the same way.
 *
 * Two things it does not collect, both deliberate:
 * - **Object keys.** A key is a client-side field name fixed by this repo's own source (`nameSource`, `mode`),
 *   never user plaintext — the same argument that lets the sweep ignore DDL.
 * - **The empty string.** Not a value: it is a substring of every row in the dump, so recording it would make
 *   the sweep fire on everything. This is the one length-based drop, and it is total (`''` alone) — a *short*
 *   canary is never dropped: [recordCanaries] refuses it (below), so the noise is resolved in the test data,
 *   per ADR 0053's rule that the sensor fails toward noise and never toward silence.
 *
 * Only arrays and plain objects are walked; a `Map`/`Set` leaf contributes nothing (no envelope uses one today,
 * and a silent nothing here would be worse than a loud hit, so this is stated rather than handled).
 */
function walk(value: unknown, into: Set<string>): void {
  if (typeof value === 'string') {
    if (value.length > 0) into.add(value)
    return
  }
  if (Array.isArray(value)) {
    for (const entry of value) walk(entry, into)
    return
  }
  if (value === null || typeof value !== 'object') return // number, boolean, bigint, undefined, symbol, function
  if (ArrayBuffer.isView(value) || value instanceof ArrayBuffer) return // key material, blob bytes
  for (const entry of Object.values(value)) walk(entry, into)
}

/**
 * The shortest canary the sweep can tell from chance, when it is spelled only in the base64/base64url/hex alphabet
 * (inbox #323), and the test against it. ONE rule for both recorders — this one and the browser suite's
 * `recordTyped` (`tests/e2e/src/canaries.ts`) — so it lives in `@veyq/crypto/canaryRule`, the one package both may import;
 * re-exported here under the names this file always had. The measurements behind 10 are stated there.
 */
export { MIN_CANARY_LENGTH, collidesWithCiphertext } from '@veyq/crypto/canaryRule'

/**
 * The closed enum values an unsealed envelope carries — `MemoryDate.mode`, `MemoryLocation.nameSource` /
 * `derivedMode` / `nameAnchor.level`, `MemoryCoverSource.kind`, `MediaMeta.capturedAtSource`. They are not
 * recorded, by the argument that already keeps object keys out: each is fixed by this repo's own source, never user
 * plaintext, and every one is shorter than [MIN_CANARY_LENGTH]. What that leaves unsensed is one of them leaking
 * ALONE; a leaking envelope carries its name or day too, which stay canaries. A new enum value is not silently
 * dropped: until it is listed here, [recordCanaries] refuses it, loudly, the first time a suite records it.
 */
export const ENVELOPE_VOCABULARY: ReadonlySet<string> = new Set([
  'auto', 'single', 'range', 'ongoing',
  'geocoded', 'manual', 'fixed',
  'road', 'suburb', 'city', 'county', 'island', 'state', 'country',
  'media', 'upload',
  'exif', 'container', 'file',
])

/**
 * Record every string leaf of one or more **unsealed** values as canaries for the database sweep.
 *
 * **Refuses** — throws, recording nothing of the call — a leaf the sweep could match by chance
 * ([collidesWithCiphertext]) unless it is [ENVELOPE_VOCABULARY]: a flaky gate is a gate that gets waved through,
 * and the fix belongs in the test data (a longer name, or one with a space or an umlaut), never in a quieter sweep.
 *
 * Rewrites the whole accumulated set on every call rather than appending, so a suite that dies mid-way still
 * leaves on disk exactly what it had recovered up to that point. A no-op when `VEYQ_CANARY_FILE` is unset,
 * which is what keeps a hand-run `VEYQ_E2E=1 npx vitest run memoryCover.e2e` working with no orchestrator.
 */
export function recordCanaries(...values: unknown[]): void {
  const found = new Set<string>()
  for (const value of values) walk(value, found)
  const kept = [...found].filter((canary) => !ENVELOPE_VOCABULARY.has(canary))
  const noisy = kept.filter(collidesWithCiphertext)
  if (noisy.length > 0) {
    throw new Error(
      `recordCanaries: ${noisy.map((c) => JSON.stringify(c)).join(', ')} — shorter than ${MIN_CANARY_LENGTH} `
        + 'characters and spelled only in the base64/hex alphabet, so the statement-log sweep can find it inside '
        + 'random ciphertext (inbox #323). Use longer test data, or a value with a space or an umlaut; a closed '
        + 'enum value of an envelope belongs in ENVELOPE_VOCABULARY.',
    )
  }
  for (const canary of kept) collected.add(canary)
  const path = process.env.VEYQ_CANARY_FILE
  if (!path) return
  mkdirSync(dirname(path), { recursive: true })
  writeFileSync(path, JSON.stringify([...collected], null, 2), 'utf8')
}

/** Every address this suite signed an account up with. */
const addresses = new Set<string>()

/**
 * Record [address] — one an account is signed up with — as a `pii` needle, and hand it back. Not a canary: the
 * database, the recording and the mailbox hold an account's address by design (`x-veyq-visibility: pii`), so the
 * orchestrator searches these in the run's boot and container logs only, none of which may hold one (ADR 0092 § 4),
 * and requires each in a mail or a database dump of the run first. Written like [recordCanaries], to
 * `VEYQ_PII_FILE`; a no-op when that is unset.
 */
export function recordPii(address: string): string {
  if (!address.includes('@')) throw new Error(`recordPii: ${JSON.stringify(address)} is no email address`)
  addresses.add(address)
  const path = process.env.VEYQ_PII_FILE
  if (!path) return address
  mkdirSync(dirname(path), { recursive: true })
  writeFileSync(path, JSON.stringify([...addresses], null, 2), 'utf8')
  return address
}
