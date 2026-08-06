# Concern: The wire assertion — is the ciphertext actually ciphertext?

- **ID:** cc.security.wire-assertion
- **Type:** **Cross-cutting concern** — a horizontal *verification* mechanism for the operator-blindness
  invariant; it has no standalone end-user goal (not a use-case) and is not a feature substrate (not an
  enabler — it serves every feature that seals anything). Recorded here so its oracle + rationale +
  verification are traceable from the threat model.
- **Status:** **the database is swept; the other surfaces are planned.** [ADR 0053](../decisions/0053-wire-assertion-canary-sweep.md)
  settled the oracle; the surfaces land one change-request at a time (§ Surfaces below), database first — and
  that first slice is live, armed in the `web-e2e` job. The remaining rows are still *planned*.

## What it asserts, and against what

Operator-blindness is a claim about **bytes the operator can read**, and until this sensor exists no automated
check has ever looked at those bytes. [ADR 0044](../decisions/0044-field-visibility-declared-at-the-field.md)
classifies every field in `contracts/` and names its own limit in one line — *"This checks the contract, not
the traffic."* A field carrying `contentEncoding: base64` is **permitted** to be opaque; a client that writes
plaintext into it satisfies that guard completely. The surfaces with no contract at all — 67 DB columns,
path/query params, `X-Contributor-Token`, presigned-URL query strings, the S3 object key, error `message` free
text, the server log — were assigned to nobody.

The threat this closes is therefore **not** a hostile operator and **not** broken crypto. It is a *client
regression*: a value that should have been sealed reaching a surface the operator can read, in a build that
passes every other check. That regression is invisible to a unit test (which sees the client's own intent),
invisible to the contract guards (which see the schema), and visible only in the stored bytes.

## The oracle — canary *absence*

The sweep proves a **positive** fact about specific values: *this plaintext, which the client sealed, appears
nowhere the operator can read.* It does not attempt the negative *nothing unaccounted-for is readable*. ADR 0053
records why the negative is unbuildable here (there is no mapping from an observable byte back to a declared
field for the surface that most needs one) and what is used instead where a mapping does exist:

| Surface | Structure | Oracle |
|---|---|---|
| DB columns, S3 object keys + bytes, server log | bytes with no schema | canary absence |
| JSON request/response bodies | every field carries a visibility marker | opacity of the declared-opaque fields |

**Canaries are derived, never hand-listed.** The canary set is every string leaf recovered by *unsealing the
server's own responses* with the keys the e2e suite already holds — so nothing is registered by hand and no
value can be forgotten out of the set. This is also what keeps the sensor from flagging **declared plaintext**:
the owner email (`x-veyq-visibility: pii`) and the tenant name (`public`, operational cleartext) are never
unsealed from anything, so they are never canaries. A sensor that flagged them is a sensor that gets switched
off — measured on the spike that preceded ADR 0053, where a suite's sealed values appeared **0** times in its
instance database while `e2e.test` appeared 3×, all of it declared.

**Two sensors, disjoint surfaces, one seam.** The e2e *suite* makes the calls, so only it sees path params,
query strings, capability headers, presigned URLs and error bodies. The *orchestrator*
([`scripts/run_web_e2e.py`](../../scripts/run_web_e2e.py)) holds Docker, the database root credential, the store
credentials and the captured boot log, so only it sees the DB, the object store and the log. They are joined by
a canary file the suite writes and the orchestrator reads; `docker exec` stays on the orchestrator side, because
reaching for it from a Vitest suite is exactly the coupling that made the previous seeded bootstrap unrunnable
in CI.

## Surfaces

| Surface | Sensor | State |
|---|---|---|
| **Database** — every column of every table of that file's own `veyq_e2e_<n>` | canary absence, swept by the orchestrator | **live** — `scripts/run_web_e2e.py` `sweep()`, a data-only `mariadb-dump` searched for each suite's dump-escaped canaries; every e2e suite feeds `src/test/canaries.ts`, pinned buildless by `scripts/validate_e2e_canaries.py` |
| **Object store** — object keys and stored bytes | canary absence | planned |
| **Server log** — the boot log, where an error `message` or a stack trace would carry plaintext | canary absence | planned; the orchestrator must first **retain** the log it currently discards |
| **HTTP bodies** — the `contentEncoding: base64` fields | opacity: a declared-opaque field must not decode to readable text | planned |
| **S3 object key** | the separate guard ADR 0044 promised it | planned, outside this concern's sweep |

## What a green sweep does not prove

Load-bearing, and repeated in the guard's own docstring rather than only here:

- **It proves absence for the values this flow sealed, on the surfaces this sensor reads.** Not that the flow
  sealed everything it *should* have — a value the client never sealed is not a canary, correctly, and is
  therefore invisible. That case belongs to the opacity check and to
  [`scripts/validate_field_visibility.py`](../../scripts/validate_field_visibility.py), not here.
- **The residual gap is coverage, not correctness.** The sweep sees only flows some suite drives, and only four
  e2e suites exist — tracked as debt in [architecture §11](../architecture/README.md). The sensor gets stronger
  for free as suites are added; it does not need revisiting for that.
- **Passing cannot be arranged from inside the client, but a canary can still be removed.** The oracle is
  outside the code — the bytes are in a database the client process does not write — so an agent cannot satisfy
  the sweep by weakening an assertion. Deleting a canary is a visible diff, exactly like widening an allowlist
  would be. That is weaker than tamper-proof, and should not be stated as more.

## Verification

Its enforcement is the **`web-e2e` CI job**, not the buildless `validate_*` glob: like the orchestrator it needs
Docker and a booted backend. The same trade [ADR 0040](../decisions/0040-coverage-gaps-and-http-not-a-surface.md)
governs applies — a red job is the guard, and it makes a coverage claim only once a CI run has executed it.

The part that *is* decidable without Docker — that every e2e suite actually contributes canaries, so the sweep
cannot report green having swept an empty set — belongs in the buildless glob, the same split
[`scripts/validate_e2e_discovery.py`](../../scripts/validate_e2e_discovery.py) already makes for discovery.
