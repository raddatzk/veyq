---
id: cr-wire-assertion-store-and-http
title: The wire sweep reaches the object store and the HTTP bodies
status: planned
satisfies: []
adrs: ["0053", "0044"]
invariants:
  - Operator-blind — this CR only ever ADDS observation. No sensor may weaken a seal, and the canary
    derivation must keep taking string leaves by type, so key material cannot enter a canary file.
  - The oracle is the wire, not the code — an assertion an implementer can satisfy by editing the client is
    not this concern's assertion.
---

## Goal

[cc.security.wire-assertion](../docs/crosscutting/cc.security.wire-assertion.md) § Surfaces has one row
**live** (the database) and three *planned*. This closes two of them in one pass: the **object store** —
object keys and stored bytes, swept for canary absence exactly as the database is — and the **HTTP bodies** —
every field a spec declares opaque must not decode to readable text. The server log stays planned and is
explicitly not in scope; the S3 object key keeps the separate guard [ADR 0044](../docs/decisions/0044-field-visibility-declared-at-the-field.md)
promised it.

## Context & constraints

- [ADR 0053](../docs/decisions/0053-wire-assertion-canary-sweep.md) — the oracle (canary *absence*), the
  derivation (unseal the server's own responses), and **§3, which is what shapes this CR**: the surfaces split
  by *who can reach them*. The suite makes the calls, so only it sees path params, query strings, capability
  headers, presigned URLs and error bodies. The orchestrator holds Docker and the store credentials, so only it
  sees the bucket. **This is two sensors, and the earlier note in inbox #102 that "a single hook sees backend
  JSON and store blob bytes alike" is true of the wire and wrong as a sensor for the store** — a client-side
  hook sees what the client *sent*, never what is *stored*, and never the object keys.
- [cc.security.wire-assertion](../docs/crosscutting/cc.security.wire-assertion.md) § Surfaces — the table two
  of whose rows this flips, and § *What a green sweep does not prove*, which must gain this CR's own limits.
- **The e2e environment is `node`, not happy-dom** — measured: no `*.e2e.test.ts` carries an
  `@vitest-environment` docblock. Two consequences the implementer must not rediscover: a `globalThis.fetch`
  patch **does** fire here (the generated runtime resolves `fetch` at call time,
  `web/src/api/generated/auth/runtime.ts:205`), and happy-dom's `settings.fetch.interceptor` seam in
  `web/src/test/setup.ts` is **unavailable** — `globalThis.happyDOM` is undefined, so that whole block is
  skipped. A sensor built on it would never run and would report green.

## Phases

- [ ] **1 suite sensor — HTTP bodies, opacity** · Done when: `npm run check` green in `web/`, and one e2e
      suite run with the recorder armed reports a non-zero request count.
  - A recorder installed from `web/src/test/setup.ts`, armed only for `*.e2e.test.ts` (the file-name
    discriminator already there), wrapping `globalThis.fetch`: URL, method, request body, response body.
  - **`clone()` the response before reading it.** Reading the body consumes the stream and the caller then
    receives an empty one — the failure mode is the whole suite breaking in ways unrelated to this CR.
  - The opacity oracle needs the set of declared-opaque fields. It is derived from the specs, never listed:
    `scripts/validate_field_visibility.py` already locates them; reuse its derivation rather than a second one.
  - Recorded material is written to the run's output directory (ADR 0053 decision 4's `.e2e-out/<stamp>/`), so
    the assertion is made **outside** the process that produced it — same reason the DB sweep lives in the
    orchestrator, and the reason an implementer cannot satisfy it by relaxing an in-suite `expect`.

- [ ] **2 orchestrator sensor — object store, canary absence** · Done when: `python3 scripts/run_web_e2e.py`
      completes with the store sweep armed and reports a non-zero object count per suite.
  - After each suite, mirror that suite's bucket out and sweep **both** the object **keys** and the object
    **bytes** for that suite's canaries, joined by the canary file the DB sweep already uses.
  - `scripts/instance_backup.py`'s `Store.mirror_out()` already mirrors a bucket to a local directory, and the
    orchestrator already builds a store handle for `fresh_bucket` — locator:
    `grep -n "_store_for\|def mirror_out" scripts/run_web_e2e.py scripts/instance_backup.py`.

- [ ] **3 concern + coverage** · Done when: `for f in scripts/validate_*.py; do python3 "$f"; done` green —
      compared against a run of the same command on this CR's base commit, and against its own **ran=** count,
      never against a remembered tally.
  - Flip the **Object store** and **HTTP bodies** rows of § Surfaces to `live`, naming their sensors.
  - Extend § *What a green sweep does not prove* with this CR's own limits (below).

## Bite tests, by kind

- **B1 — injection, phase 1.** Lever: stop sealing one declared-opaque field in the client, run the suite,
  assert the opacity check reddens **naming that field**. Snapshot-and-restore; never `git checkout`.
- **B2 — injection, phase 2.** Lever: write a canary's plaintext into the bucket under a fresh key before the
  sweep, assert it reddens **naming the key**. A second lever on the same sweep: put the canary in the object
  **key** rather than the bytes, because a sweep that only reads bytes passes that and must not.
- **B3 — the measurement itself, both phases, and this is the one that is easy to skip.** A recorder that
  never armed, and a mirror that produced an empty directory, both report *"no canary found"* — which is
  indistinguishable from a clean run. So each sensor asserts a **non-zero population** (requests recorded,
  objects mirrored) and fails when it is zero. Bite it by arming the sweep against a suite that makes no calls
  and asserting it goes red. See [changes/README](README.md) § *a bite test must tell "the guard held" apart
  from "nothing ran"*.
- **B4 — already live.** The DB sweep must keep biting unchanged: `LoginSliceTest`-style regression is not
  the risk here, the canary file is. Prove it with the existing guard — `python3 scripts/validate_e2e_canaries.py`
  — and by one suite still reporting its DB canary count.

## Definition of done

`npm run check` green in `web/`; `python3 scripts/run_web_e2e.py` green with both sensors armed; the whole
guard glob green via `for f in scripts/validate_*.py; do python3 "$f"; done`, reporting how many it ran;
§ Surfaces carries two more `live` rows with their sensors named; and § *What a green sweep does not prove*
names, at least: that the HTTP oracle sees only fields a spec declares (a leak through an undeclared field or
a header is outside it), that the store sweep sees only the suite's own bucket, and that neither reaches the
server log.

Coverage: `satisfies:` is deliberately empty — this is a crosscutting concern with no use-case of its own, the
same shape the DB slice shipped under. The completeness half stays with
`scripts/validate_e2e_canaries.py`; if this CR's sensors need their own buildless completeness proof, that is a
new guard and joins the glob for free.

## Retrospective

<!-- filled at close -->
