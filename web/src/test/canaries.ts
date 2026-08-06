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
// **Why not a hook in the crypto primitive.** `shared/crypto/v1.ts` is conformance-pinned to
// `contracts/crypto/vectors/v1.json` and sits in the Stryker mutation scope — the last file in the repo that
// should grow a test affordance. And `aeadSeal` is reached by `symWrap`/`asymWrap` too, so a hook there would
// record **key material**: searching a database for random bytes proves nothing while burying the strings that
// matter. ADR 0053 § Decision 2.
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
 *   canary is kept, and a noisy one is resolved in the test data, per ADR 0053's rule that the sensor fails
 *   toward noise and never toward silence.
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
 * Record every string leaf of one or more **unsealed** values as canaries for the database sweep.
 *
 * Rewrites the whole accumulated set on every call rather than appending, so a suite that dies mid-way still
 * leaves on disk exactly what it had recovered up to that point. A no-op when `VEYQ_CANARY_FILE` is unset,
 * which is what keeps a hand-run `VEYQ_E2E=1 npx vitest run memoryCover.e2e` working with no orchestrator.
 */
export function recordCanaries(...values: unknown[]): void {
  for (const value of values) walk(value, collected)
  const path = process.env.VEYQ_CANARY_FILE
  if (!path) return
  mkdirSync(dirname(path), { recursive: true })
  writeFileSync(path, JSON.stringify([...collected], null, 2), 'utf8')
}
