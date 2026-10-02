# Concern: The wire assertion — is the ciphertext actually ciphertext?

- **ID:** cc.security.wire-assertion
- **Type:** **Cross-cutting concern** — a horizontal *verification* mechanism for the operator-blindness
  invariant; it has no standalone end-user goal (not a use-case) and is not a feature substrate (not an
  enabler — it serves every feature that seals anything). Recorded here so its oracle + rationale +
  verification are traceable from the threat model.
- **Status:** **six surfaces are swept — the database, the object store, the HTTP bodies, the server log, the
  database's statement log and the mailbox**, each armed in the `web-e2e` job, for two needle sets: the sealed
  plaintext (canaries) and every capability token the run handled (§ Capability tokens). [ADR 0053](../decisions/0053-wire-assertion-canary-sweep.md) settled the
  oracle; the surfaces landed one change-request at a time (§ Surfaces below). Only the S3 object key's own guard
  is still *planned*.

## What it asserts, and against what

Operator-blindness is a claim about **bytes the operator can read**, and until this sensor exists no automated
check has ever looked at those bytes. [ADR 0044](../decisions/0044-field-visibility-declared-at-the-field.md)
classifies every field in `contracts/` and names its own limit in one line — *"This checks the contract, not
the traffic."* A field carrying `contentEncoding: base64` is **permitted** to be opaque; a client that writes
plaintext into it satisfies that guard completely. The surfaces with no contract at all — 67 DB columns,
path/query params, presigned-URL query strings, the S3 object key, error `message` free text, the server log —
were assigned to nobody.

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
| **HTTP bodies** — the `contentEncoding: base64` fields | opacity **and** canary absence: a declared-opaque field must not decode to readable text *and* must not be implausibly short; no canary may appear in a URL, a request or response header or a body, in any spelling `canary_needles()` models — the recorder keeps a URL as the client built it, percent-encoded, and records every response header (`Set-Cookie` as the array `getSetCookie()` returns) | **live** — `scripts/run_web_e2e.py` `sweep_http()`, over a recording `web/src/test/wireRecorder.ts` makes in the suite process (a recording without response headers fails). This is the only surface where path params, query strings, capability headers and presigned URLs exist at all (ADR 0053 §3), and the one the token needles are derived from (§ Capability tokens) |
| **Statement log** — every SQL statement the database received during the run, reads and since-undone writes included, so a plaintext the end-state dump cannot see (written, then overwritten or deleted inside a suite) | canary absence, run-level, against the **union** of the run's canary files | **live** — `scripts/run_web_e2e.py` `StatementLog` switches MariaDB's general query log on for exactly the run's window (three dynamic `SET GLOBAL`s, no compose or chart change), restores the prior globals from a `finally` (SIGTERM/SIGHUP included; a killed run's leftover is healed by the next run), retains the file as `.e2e-out/<stamp>/mariadb-general.log` and deletes it in the container; `sweep_statements()` searches **only the SQL string literals** — the backend's driver (Connector/J) prepares client-side, so every bound value is an inline literal, and outside them `set autocommit=0` and the migrations' comments would collide with real canaries (`auto`, `range`) — with `canary_needles()` plus `sql-literal`, the driver's own escaping (a backslash before `\`, `'`, `"`, NUL; measured from the driver). **Armed** by a sentinel statement at each end of the window, carrying an apostrophe so only `sql-literal` can find it (the log was on throughout, and literals are still escaped as modelled), and by a `Connect` entry for every database a boot of the run used (derived from the boot logs), so the backend's traffic was captured |
| **Mailbox** — every mail the run sent, the one surface a third party (the tenant's SMTP provider) reads | canary absence, run-level, against the **union** of the run's canary files | **live** — `scripts/run_web_e2e.py` `sweep_mailbox()` reads every message Mailpit received since the run's start through its API (paged explicitly, fetched raw, retained under `.e2e-out/<stamp>/mailbox/`), parses it with Python's `email` package and searches each **decoded** header and text part and the raw source with `canary_needles()`. Decoding is load-bearing: the product's mail travels as a `Q` encoded-word subject and a quoted-printable body whose soft line breaks split even an ASCII token (measured). **Armed** by a mail the run plants over SMTP whose sentinel (a space, a non-ASCII character) must be found decoded — and is printed as absent from the raw source. No suite deletes mail: `web/src/test/mailpit.ts` waits behind a cursor (`mailCursor` + `waitForMail(to, { after })`) |
| **S3 object key** | the separate guard ADR 0044 promised it | planned, outside this concern's sweep |

## Capability tokens — the second needle set

[ADR 0004](../decisions/0004-session-tokens.md) keeps only a hash of each opaque token so that *"a DB dump yields no
usable session"*, and every later capability secret (contributor, invite, recovery, pairing) copied that shape. A
raw token is not a sealed value, so the canaries never covered it. Every surface above is therefore swept for a
second set of needles: **every capability token the run handled**, reported as `token:<kind>` so a leaked
capability is never read as a leaked canary.

- **Derived by marker, never listed** — ADR 0053 §2's rule, applied to tokens. `token_needles()` matches each
  backend call of the suite's HTTP recording to its contract operation and takes every value the `token` class
  ([ADR 0044](../decisions/0044-field-visibility-declared-at-the-field.md)) marks: body properties (walked through
  `$ref`s, so the scalar `InviteBootstrapSecret` counts), header parameters (`X-Contributor-Token`,
  `X-Share-Password`), response headers — a `Set-Cookie` contributes its non-empty cookie values, which is the only
  way a web sign-in's refresh token ever crosses the wire — and the bearer of `Authorization`. The kind is the
  marked thing's name. A backend call no operation matches fails the suite: its tokens would be invisible.
  `validate_field_visibility.py` requires a marker on every declared header and scalar component, so a new token
  cannot go unclassified quietly; one *removed* is a visible diff, as for canaries — and often not even a gap: a
  token that crosses the wire in several marked places (a contributor token is minted in one response and sent in
  later requests) is still derived from the others. What a removal shows is a drop in the printed count by kind.
- **One needle per token.** Tokens are base64url, base64 or hex with no space, quote or backslash, so every
  spelling `canary_needles()` models collapses to the raw one (asserted buildless). A presigned URL is the one
  URL-valued token: its needle is its request target (`/bucket/key?X-Amz-…`), a substring of the URL and the form
  its addressee logs.
- **The hash is the control.** Per suite with a non-empty token set, at least one token's hex SHA-256 must be in
  that suite's dump — proof that the dump was read and that the tokens belong to that database. A suite where it
  is not met fails by name; zero raw hits on its own proves nothing. The dump is retained as
  `.e2e-out/<stamp>/database-<tag>.sql`.
- **Two exemptions, both derived and printed with what they exempted.** *The mailbox*, for a token the recording
  shows the suite first read out of a mail — member-invite and recovery secrets, whose channel *is* mail
  ([ADR 0049](../decisions/0049-email-delivery-boundary.md), [ADR 0054](../decisions/0054-password-recovery-two-factor-release.md));
  every other token in a mail is a hit. *A presigned URL in the store's own request log*: the store is its verifier
  and logs the URI it was handed; the capability expires with the presign TTL, and the same URL is swept everywhere
  else.

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
  wrote **0** lines in the run's window (MariaDB writes its general log to the file the statement-log sweep reads,
  not to its container log; Mailpit logs deliveries, not bodies — the mailbox sweep reads the bodies).
- **The store keeps no history of object bytes.** An object a client uploaded and deleted inside one suite leaves
  its key in the store's request log (swept) but its bytes nowhere this sensor reads — Garage keeps no versions,
  and recording upload bodies in the suite's recorder would put every byte of the store through a JSONL file.
  Measured on one full run: 10 store `PUT`s and 1 media `DELETE`, so the residue is one object's bytes. Re-open
  when a suite deletes media routinely.
- **The statement log sees statements, in their literals.** A value the backend computes and never sends to the
  database is not there by construction, and a value spliced into SQL text outside a quote would not be searched
  (none is today — every value is a bound parameter). Its window is the shared `veyq-mariadb`, so the retained file
  also holds whatever else reached that MariaDB during the run — a developer's own backend or database tool: their
  data on their disk, in the gitignored `.e2e-out/`, deleted from the container when the window closes. Only a
  SIGKILL of the orchestrator leaves the log on (until the next run heals it, or the container restarts).
- **The mailbox sees what reached Mailpit**, not what a production relay adds, keeps or logs. It is shared with a
  developer's own mail of the same window, and Mailpit prunes its oldest messages past its cap — a run that pushed
  its own planted mail out fails on the control, loud. Non-text parts are searched raw only.
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
- **The token sweep sees the tokens some suite handled, through the calls some suite made.** Every web sign-in
  yields a refresh token through its `Set-Cookie`; no suite drives `token/refresh` (node's `fetch` keeps no cookie
  jar, so a suite would have to forward the cookie by hand) and none drives a pairing, so a rotated refresh token
  and a pairing `handle` are swept for nothing — both are hashed in code, which is a code-level fact, not a
  measured one. A suite that drives either extends the set for free.
- **The read/contributor share id is not a token, by decision.** It is stored raw as `veyq_share.id`, half the
  capability ([ADR 0029](../decisions/0029-public-viewer-share.md); the other half is the fragment key), and is
  classified `id`; the token sweep does not look for it.
- **A presigned URL is searched by its request target, not by its signature alone.** A server that logged the
  signature without the path in front of it would not be matched.
- **The run's own artifacts carry the raw tokens.** Each `.e2e-out/<stamp>/http-*.jsonl` holds every token the suite
  handled, refresh cookies included — throwaway credentials of throwaway instances, gitignored, and the reason the
  derivation can exist at all. Once `.e2e-out/` is uploaded from CI they leave the machine; whoever arranges that
  arranges where they go. The retained `database-<tag>.sql` hold only the hashes.
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
canary does yet, so no run can prove it — and that its controls fail by name on an unarmed log; that the
statement log is searched in its literals, in the driver's spelling, with its controls red by name; that the
mailbox is read decoded; and that the token needles are derived by marker from every place the contract puts one,
searched raw and armed by their hashes ([`scripts/validate_wire_log_matcher.py`](../../scripts/validate_wire_log_matcher.py)).
The recorder's own half — every `Set-Cookie` recorded, the token cookie not masked by the clearing one sent after
it — is a web unit test, `web/src/test/wireRecorder.test.ts`.
