// One fact, stated once: Argon2id at production params (`v1.ARGON` — t=3, m=32 MiB) costs ~0.8s per
// derivation, and a test running several of them under vitest's parallel workers exceeds the 5000ms default
// `testTimeout` on a loaded machine. Measured 2026-08-04 on a 14-core Mac: 823ms/derivation unloaded, ~1.2s
// under contention — so `npm run check` failed intermittently, never on the same set of tests twice, while a
// re-run with a raised timeout was fully green. A timing problem, not a correctness one.
//
// Two remedies, and WHICH ONE APPLIES IS DECIDED BY WHO OWNS THE DERIVATION — never by which test failed today:
//
//  - **The test owns both sides → `TEST_ARGON`.** The cost params are an ordinary public argument of every v1
//    derivation (`kekPasswordSecret(sk, pw, salt, params)`, `deriveAuthKeypair(pw, salt, params)`) and travel on
//    the wire in each wrap's `kdfParams` — that is crypto agility (AD-25), not a test affordance. A test that
//    both builds the fixture and computes the expected value can pick cheap params on both sides and still run
//    REAL Argon2id over the real code path: 0.8ms instead of 823ms, with the assertion unchanged.
//  - **Production owns the derivation → `PRODUCTION_ARGON_TIMEOUT_MS`.** Where the code under test mints the
//    wrap itself (`login`, `createShare(…, password)`, `buildCreateServerOwnerRequest`, `trustThisDevice`), the
//    test cannot choose the params without production growing a params argument for a test's benefit. Nor where
//    it READS a served wrap (unlock, recovery, the cold-credential proof): `decodeKdfParams` refuses any profile
//    but `v1.ARGON` before deriving (ADR 0017 rule 5), so a TEST_ARGON wrap is — correctly — never opened. The cost
//    is inherent, so the honest fix is to state the real ceiling on exactly those tests — and NOT globally in
//    `vite.config.ts`: the other ~770 tests run in under 5ms each, and their 5000ms default is a live canary for
//    a hung promise or a missing mock. A global bump buys these few tests headroom by spending every other
//    test's fastest failure signal.
//
// What is never lightened, and why that costs no coverage: `v1.conformance.test.ts` pins the
// derivations against `contracts/crypto/vectors/v1.json` at the params the VECTORS carry, including an explicit
// production-params case — that is its whole job. And `v1.ts` grows no test affordance at all: it
// is conformance-pinned and sits in the Stryker mutation scope, the same argument `src/web/src/test/canaries.ts` makes
// for staying out of the crypto primitive.
import type { ArgonParams } from './v1'

/**
 * Real Argon2id at deliberately cheap cost — for tests that control BOTH the fixture and the expected value.
 * Not a mock and not a weaker algorithm: same primitive, same code path, ~1000× cheaper. 64 KiB / 1 pass is far
 * enough below anything production would use that it cannot be mistaken for a real cost setting.
 */
export const TEST_ARGON: ArgonParams = { t: 1, m: 64, p: 1, dkLen: 32 }

/**
 * Timeout for a test whose derivation runs at production params inside the code under test. Generous on
 * purpose — it is a ceiling for a machine under load, not an expectation (the slowest such test measures
 * ~3.7s here). Apply it to the Argon-paying tests only, never to a whole file of fast ones.
 */
export const PRODUCTION_ARGON_TIMEOUT_MS = 30_000
