// Which plaintext the wire-assertion sweeps could find inside this package's own OUTPUT by chance (ADR 0053, inbox
// #323). The sweeps (`scripts/e2e_sweeps.py`) match a canary as a plain substring over everything an operator could
// read — most of it base64 ciphertext, hex hashes and base64url tokens, the alphabets this package encodes in — so a
// short canary spelled only in those alphabets is found in random bytes now and then, and a gate that goes red on
// nothing is a gate that gets waved through.
//
// It lives here, beside `base64.ts`, because it is a property of what this package emits, and because the two
// recorders that must agree on it are in two packages that share nothing else: web/'s `recordCanaries`
// (`web/src/test/canaries.ts`, what an API-level suite unsealed) and tests/e2e/'s `recordTyped` (`tests/e2e/src/canaries.ts`,
// what the browser suite typed — ADR 0090 decision 4 lets tests/e2e/ import this package and nothing of web/). Two copies
// would let the two sweeps disagree about what is noise. A test affordance, like `testArgon.ts`: no product code
// imports it, and it is out of the mutation scope for base64.ts's reason (`stryker.mutate.json`).

/**
 * The shortest canary the sweep can tell from chance, when it is spelled only in the base64/base64url/hex alphabet.
 * Such a canary of length L lands in random bytes at 64^-L per position: the 4-character `city` and `auto` did, twice
 * in a week (`…jicityF8…`, `…WZWautoqvJLM…`), and each time the run went red on nothing. At 10 it is 64^-10 ≈
 * 9·10^-19 — under 10^-6 per run even for 10^9 swept characters and a thousand canaries — and an ISO day
 * (`2026-05-01`, user plaintext with no longer spelling) is exactly 10. A canary with ANY other character (a space,
 * `.`, an umlaut) cannot occur inside base64 or hex at all, so it is exempt at every length.
 */
export const MIN_CANARY_LENGTH = 10

const ENCODING_ALPHABET = /^[A-Za-z0-9+/=_-]+$/

/** Whether the sweep could find [canary] inside random encoded bytes by chance — a recorder refuses such a value. */
export function collidesWithCiphertext(canary: string): boolean {
  return canary.length < MIN_CANARY_LENGTH && ENCODING_ALPHABET.test(canary)
}
