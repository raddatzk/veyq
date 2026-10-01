# Concern: The wire assertion — is the ciphertext actually ciphertext?

- **ID:** cc.security.wire-assertion
- **Type:** **Cross-cutting concern** — a horizontal *verification* mechanism for the operator-blindness
  invariant; it has no standalone end-user goal (not a use-case) and is not a feature substrate (not an
  enabler — it serves every feature that seals anything). Recorded here so its oracle + rationale +
  verification are traceable from the threat model.
- **Status:** **four surfaces are swept — the database, the object store, the HTTP bodies and the server log**,
  each armed in the `web-e2e` job. [ADR 0053](../decisions/0053-wire-assertion-canary-sweep.md) settled the
  oracle; the surfaces landed one change-request at a time (§ Surfaces below). Only the S3 object key's own guard
  is still *planned*.

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
| **Object store** — object keys and stored bytes | canary absence | **live** — `scripts/run_web_e2e.py` `sweep_store()`, the suite's bucket mirrored out with the operator tool's own rclone plumbing (`instance_backup.Store.mirror_out`) and both the object **keys** and the object **bytes** searched for that suite's canaries. A suite that stored nothing is skipped **out loud**, and the discriminator is its own `veyq_blob` count rather than a list of which suites upload |
| **Server log** — every boot log the run retains, and the log of every container of the stack, where an error `message`, a stack trace or a request URI would carry plaintext | canary absence, run-level, after every group, against the **union** of the run's canary files | **live** — `scripts/run_web_e2e.py` `sweep_logs()` over each `backend-*.log` (derived by a recursive glob), and `sweep_containers()` over every service `docker compose ps` reports, read from the run's start (MariaDB, Garage, Traefik, Mailpit). One needle set for every text surface, `canary_needles()`: raw, JSON-escaped (both `ensure_ascii` forms) and percent-encoded (`%20` and `+`), a plain case-sensitive substring with no word boundary. **Armed by positive controls on the same matcher**, never by a count of zero: each boot log must be non-empty, name the database its boot used (Flyway's JDBC line) and carry Quarkus' shutdown line; the store's log must carry a key the run plants at its start — an unsigned, refused request with a space and a non-ASCII character, which Garage logs percent-encoded and the sweep must find through the `pct` needle. The boot pins the JVM's output to UTF-8 (`-Dstdout.encoding`), because under a `C` locale it writes a non-ASCII character as `?` (measured). The no-Docker half is `scripts/validate_wire_log_matcher.py` |
| **HTTP bodies** — the `contentEncoding: base64` fields | opacity **and** canary absence: a declared-opaque field must not decode to readable text *and* must not be implausibly short; no canary may appear in a URL, a header or a body, in any spelling `canary_needles()` models — the recorder keeps a URL as the client built it, percent-encoded | **live** — `scripts/run_web_e2e.py` `sweep_http()`, over a recording `web/src/test/wireRecorder.ts` makes in the suite process. This is the only surface where path params, query strings, capability headers and presigned URLs exist at all (ADR 0053 §3) |
| **S3 object key** | the separate guard ADR 0044 promised it | planned, outside this concern's sweep |

## What a green sweep does not prove

Load-bearing, and repeated in the guard's own docstring rather than only here:

- **It proves absence for the values this flow sealed, on the surfaces this sensor reads.** Not that the flow
  sealed everything it *should* have — a value the client never sealed is not a canary, correctly, and is
  therefore invisible. That case belongs to the opacity check and to
  [`scripts/validate_field_visibility.py`](../../scripts/validate_field_visibility.py), not here.
- **The residual gap is coverage, not correctness.** The sweep sees only flows some suite drives
  (`web/src/**/*.e2e.test.ts`) — tracked as debt in [architecture §11](../architecture/README.md). The sensor gets
  stronger for free as suites are added; it does not need revisiting for that.
- **The opacity oracle is two tests, and it needs both.** Readability alone has a hole that biting it found:
  it needs a length floor, because short random strings do decode to printable UTF-8 now and then (measured:
  7/5000 at 8 bytes, 0 in 80 000 at 24 and above) — and a floor is exactly where short *plaintext* hides. An
  unsealed Memory name went over the wire as **23 bytes** and a readability-only check never looked at it. So
  length is the second test and on this wire it is not a heuristic: every declared-opaque field a real suite
  sends decodes to **at least 32 bytes**, and an AEAD value cannot be under 28 (a 12-byte nonce, a 16-byte tag).
- **The HTTP sensor sees only what a spec DECLARES, and only what it can read as text.** A leak through an
  *undeclared* field, or through a response the server does not mark textual, is outside it — binary bodies are
  elided by content-type and their evidence is the bucket instead. A header is searched for canaries but never
  for opacity: it carries no schema.
- **The store sensor sees one bucket, and it is usually shared.** Only a group with a hook gets its own, so a
  hit found while sweeping for suite B may have been left by suite A. That is still a true leak and it is still
  reported — but the suite it is reported *under* is not necessarily the one that caused it.
- **The log sensor reads the e2e topology, which is not the deployed one.** The suites call the backend and the
  store directly, so the reverse proxy carries no e2e traffic, and the dev stack has no admin container at all.
  Neither compose file nor the chart enables a Traefik access log, so that one is empty by default. **The admin
  nginx is not**: its base image logs every request line — method, URI and query string — to stdout
  (`access_log … main`, linked to `/dev/stdout`), and nothing here reads it.
- **Only the store's container log carries a control.** MariaDB, Traefik and Mailpit are swept because the rule
  that includes them is derived, not because anything proves they could show a leak: across the first runs each
  wrote **0** lines in the run's window (MariaDB's general log is off; Mailpit logs deliveries, not bodies).
- **A log records only what its process chose to write, at the level it shipped with (INFO).** No veyq log
  statement echoes request input today, so on the boot logs a green is mostly the proof that none started to.
  The encoding pin is the e2e boot's; an operator's locale is theirs, and a leak it writes as `Gr??e` still leaks
  everything ASCII in it.
- **The container logs are shared — by every suite of the run, and by a developer's own traffic in the same
  window.** A hit there names the canary files that carry the value; the suite is inferred from the canary, not
  from the log. The same holds for a restored instance's boot log, which owes the member before it.
- **The needles model the encoders this stack uses, not every encoder.** `json-ascii` is Python's lower-case
  `\uXXXX`; the two URL forms are `encodeURIComponent`'s and a form's. A server writing a value in another
  spelling (upper-case hex escapes, base64) is outside them — base64 deliberately, because it is the opacity
  oracle's case.
- **Passing cannot be arranged from inside the client, but a canary can still be removed.** The oracle is
  outside the code — the bytes are in a database the client process does not write — so an agent cannot satisfy
  the sweep by weakening an assertion. Deleting a canary is a visible diff, exactly like widening an allowlist
  would be. That is weaker than tamper-proof, and should not be stated as more.

## Verification

Its enforcement is the **`web-e2e` CI job**, not the buildless `validate_*` glob: like the orchestrator it needs
Docker and a booted backend. The same trade [ADR 0040](../decisions/0040-coverage-gaps-and-http-not-a-surface.md)
governs applies — a red job is the guard, and it makes a coverage claim only once a CI run has executed it.

The parts that *are* decidable without Docker belong in the buildless glob, the same split
[`scripts/validate_e2e_discovery.py`](../../scripts/validate_e2e_discovery.py) already makes for discovery: that
every e2e suite actually contributes canaries, so the sweep cannot report green having swept an empty set
([`scripts/validate_e2e_canaries.py`](../../scripts/validate_e2e_canaries.py)); and that the log sweep's needles
find every spelling of a canary carrying a quote, a backslash, a newline or a non-ASCII character — which no real
canary does yet, so no run can prove it — and that its controls fail by name on an unarmed log
([`scripts/validate_wire_log_matcher.py`](../../scripts/validate_wire_log_matcher.py)).
