---
id: cr-wire-assertion-history-and-mail
title: The wire sweep reads what the stack kept, not only what it ended with — the SQL history and every mail
status: in-progress
satisfies: []
adrs: ["0053", "0049", "0040"]
invariants:
  - Operator-blind — this CR only ever ADDS observation. Nothing the backend writes, logs or mails changes to make
    a sweep pass; the only product-adjacent edit is a test helper that stops deleting mail mid-run.
  - The oracle is the wire, not the code — both new records (the database's statement log, the mailbox) are
    read by the orchestrator from the containers after the suites ran; no backend hook, no JDBC proxy, no test
    affordance in shipped code.
  - Self-hostable, both deployment targets untouched — the statement log is a RUNTIME toggle the orchestrator
    sets on the dev compose MariaDB for the run's window and restores; `infra/docker-compose.prod.yml` and
    `charts/veyq/` are not edited and have nothing to answer.
---

## Open for the owner

**Fourth round answered by the owner, 2026-10-01: every recommendation added since the third answer is taken too** (incl. the granter-lock questions Q1–Q3 and the rotation CR's Q8/Q9).

**Third round answered by the owner, 2026-10-01: every recommendation added since the second answer is taken too** (questions added by the third refine wave and by implementation hand-backs). Rows those questions proposed exist in `docs/inbox.md` (#233–#243).

**Answered by the owner, 2026-10-01: every recommendation below is taken, and implementation has the go.** Read each question's recommendation as its decision. Where a question proposed a separate inbox row, that row exists in `docs/inbox.md`.

Each question carries a recommendation; the CR below is written as if every recommendation is taken, and names
what changes where one is not. This CR builds on
`cr-wire-assertion-server-logs` (built 2026-10-01 and deleted; its state is the concern's log row and
`scripts/run_web_e2e.py`): it reuses that
CR's `canary_needles`, its run-level slot after the group loop (its D1) and its "armed by a positive control on
the same matcher" rule (its D5). **It is not folded into that CR** — see question 4.

1. **#204 — which record closes the "final state only" gap for the database?** Three candidates, weighed:
   - **(a) MariaDB's general query log, switched on at runtime for the run's window — recommended.** It is a
     dynamic global (`SET GLOBAL general_log`, `general_log_file`), so nothing in either compose file changes and
     the user's container is not restarted; it records **every statement text**, reads included, so it also sees
     a raw value used in a `WHERE` (which matters for
     [`cr-wire-assertion-capability-tokens`](cr-wire-assertion-capability-tokens.md): a lookup by a raw token
     never reaches any row). Cost: the log also captures whatever else hits that MariaDB during the window (the
     developer's own IntelliJ backend) — Decision D3 contains that.
   - **(b) The binary log (row images) — rejected.** `log_bin` is not dynamic: it needs `command:` flags on the
     dev compose's `mariadb` service (restarting the user's container, growing binlog files in their data volume
     for all dev use, and — per `infra/` convention — an answer for the prod compose and the chart that neither
     needs). It sees writes only, so it misses the raw-value lookup (a), and it needs `mariadb-binlog --verbose`
     decoding with its own escape model.
   - **(c) A per-statement recorder in the backend (Hibernate `StatementInspector`, a JDBC proxy datasource) —
     rejected.** It moves the oracle into the process under test — the thing ADR 0053 §3 and this concern's
     invariant forbid — and it adds either a test affordance to shipped code or a dependency.
   - **(d) Accept the limit — not recommended, because the gap is exercised every run.** Measured (Inventory
     H3): one run carries 7 `PATCH` of nodes, 18 `PUT`s of upserted state and 2 `DELETE`s, before counting what
     the server rewrites on its own (key rotation, refresh rotation, invite consumption). A plaintext written by
     any of those and then replaced is invisible to the end-of-suite dump today.
2. **#204 — and the object store?** *Recommended:* **accept the limit and state it**, with its measured size.
   Garage keeps no history of object bytes (**remembered**: no bucket versioning in Garage); the **keys** of
   deleted objects are already covered by the logs CR (Garage logs every request URI — its I5); the bytes of a
   client upload deleted within the same suite are the residue. Measured: 10 store `PUT`s and 1 media `DELETE`
   in the latest full run (H3). Closing it would mean recording binary upload bodies in the suite's wire
   recorder, which `wireRecorder.ts` `recordBody` deliberately refuses (it would put the whole store through a
   JSONL file). Re-open when a suite deletes media routinely.
3. **#202 — build the mail surface at all?** The value is narrower than the row suggests, and the CR says so:
   mail content is closed **by construction** (`MailContext(link, domain, expiry)` — the only three values
   either mail kind can interpolate, ADR 0049; Inventory M1), and the existing HTTP sweep **already** searches
   every mail a suite reads back, because the recorder wraps the suite's `fetch` of Mailpit too (M3: 7 mail reads
   recorded, `sweep_http` has no host filter). *Recommended:* **build it anyway, small** — mail is the one
   surface a **third party** (the tenant's SMTP provider) reads, ADR 0049's boundary has no traffic-level sensor,
   and a future mail kind (a notification) is exactly where a sealed name would slip in. The residual it closes
   today is concrete: mails no suite reads, and mails a suite **deletes** before the run ends (M4).
4. **Fold into the logs CR?** *Recommended:* **no.** The logs CR is approved with a go and scoped to "logs the
   operator reads"; both halves here need things it does not have — a runtime toggle with a restore obligation
   on a shared container (general log) and a change to a suite helper plus MIME decoding (mail) — and folding
   them in would reopen an approved work order. They reuse its needle function and its run-level slot, so the
   build order is **logs CR → this CR**; phase 1 here does not start before that CR's phase 1 is done.
5. **Suite helper: stop deleting mail?** `clearMailbox` deletes a recipient's messages so the next `waitForMail`
   cannot return an older one (M4). *Recommended:* replace deletion with a **cursor** — `mailCursor(to)` returns
   the newest message id/time before the action, `waitForMail(to, { after })` waits for a newer one — so nothing
   is deleted during a run and the run-level sweep sees every mail. The helper's own reason for being
   recipient-scoped (a developer's Mailpit holds their smoke mails) is kept: the run never deletes at all.

## Goal

The database and store sweeps read the **end state** of each suite (a `mariadb-dump` and a bucket mirror); a
plaintext written and then overwritten or deleted inside the suite leaves no trace in either. And mail — the
surface a tenant's SMTP provider reads — is swept only where a suite happens to fetch a message back. After this
CR, the orchestrator also reads two **histories** the e2e stack already keeps or can keep for free: every SQL
statement the database received during the run, and every mail Mailpit received during the run — each swept for
every canary of the run through the logs CR's `canary_needles`, each proven armed by a planted control, each
failing the run naming the line.

## Inventory (measured 2026-09-28 against `64c1f65` and the main checkout's retained run `.e2e-out/1790576564`, unless marked remembered)

| # | Fact | Locator | Kind |
|---|---|---|---|
| H1 | The DB sweep is one data-only `mariadb-dump` per suite, taken at suite end inside `run_member`; the store sweep mirrors the bucket at the same point. Neither sees an intermediate state | `grep -n 'def sweep(\|mariadb-dump\|def sweep_store\|def run_member' scripts/run_web_e2e.py` | measured |
| H2 | The dev compose `mariadb` service is `mariadb:11.4` with **no** `command:`; the prod compose and the chart pin the same image. Nothing enables a general or binary log anywhere | `grep -n 'mariadb:11.4\|general_log\|log.bin\|binlog' infra/docker-compose.yml infra/docker-compose.prod.yml charts/veyq/values.yaml` | measured |
| H3 | State that is replaced or removed within one run: `PATCH /api/scopes/{id}/nodes/{id}` ×7, `PUT …/mail-settings` ×11, `PUT /api/account/identity-pins` ×5, `PUT /api/account/device-register` ×2, `DELETE …/media/{id}` ×1, `DELETE …/members/{id}` ×1; store `PUT` ×10 | the refine's probe `delete_probe.py` (below) over `http-*.jsonl` | measured (one run) |
| H4 | **Measured 2026-10-01 (run `1790879108`, `memoryLocation.e2e`):** `general_log`, `general_log_file`, `log_output` are dynamic — set and restored at runtime, `@@global.general_log` back to `0` and the container's `/tmp` empty afterwards. Connector/J 3.5.8 prepares client-side: 0 `Prepare`/`Execute` entries, every bound value an inline literal (`… encrypted_name='AlwD…'`); its escaping (`javap -c …Writer.writeStringEscaped`) puts a backslash before `\`, `'`, `"` and NUL only. A `Connect` entry reads `veyq@192.168.65.1 on veyq_e2e_1 using TCP/IP`. **Unforeseen:** `set autocommit=0` occurs 380× in one suite's log and the migrations' comments carry `range`/`auto`, both real canaries — so the sweep searches **literals only** | `grep -a -c 'autocommit' .e2e-out/<run>/mariadb-general.log`; `awk -F'\t' 'NF>=3{split($2,a," "); print a[2]}' … \| sort \| uniq -c` | measured |
| M1 | Both mail kinds interpolate only `MailContext(link, domain, expiry)`; an invite template naming any other `{{tag}}` is refused at write time; the recovery body is fixed text over the same context | `grep -n 'data class MailContext\|VARIABLES = ' backend/src/main/kotlin/com/veyq/app/features/admin/tenant/InviteMailRenderer.kt`; `grep -n 'MailContext\|ctx\.' backend/src/main/kotlin/com/veyq/app/features/account/recovery/RecoveryMailer.kt` | measured |
| M2 | Every suite's tenant is pointed at Mailpit (`127.0.0.1:18025` API, `11025` SMTP); Mailpit has no volume and `restart: unless-stopped`, so it is one long-lived mailbox shared by every run and by the developer | `grep -n 'MAILPIT\b\|VEYQ_MAILPIT' scripts/run_web_e2e.py`; `grep -n -A12 '^  mailpit:' infra/docker-compose.yml` | measured |
| M3 | The wire recorder wraps the suite's global `fetch`, so a suite's reads of Mailpit are recorded and `sweep_http` (no host filter) already searches them: 7 `GET /api/v1/message/{id}` across 3 recordings, against 8 mail-sending requests (6 member invites, 2 recovery requests) | `grep -c ':18025' .e2e-out/<run>/http-*.jsonl`; the probe `mail_probe.py`; `grep -n 'def sweep_http' -A50 scripts/run_web_e2e.py` | measured (one run) |
| M4 | `clearMailbox` **deletes** a recipient's messages; it is called at 4 sites (`e2eBootstrap.ts`, `admission.e2e`, `recovery.e2e` ×2); the latest run issued 1 such `DELETE` | `grep -rn 'clearMailbox(' web/src --include='*.ts'` | measured |
| M5 | **Measured 2026-10-01 on a recovery mail's raw source:** Vert.x mail writes the subject as a `Q` encoded-word (`=?UTF-8?Q?…zur?=` folded onto `=C3=BCcksetzen?=`) and the body quoted-printable, with **soft line breaks that split even an ASCII token** (`…#llucEdM1bZn5oid-=` / `CKOmV3…`) — so a raw search misses ASCII values too, not only non-ASCII ones | `curl -s http://127.0.0.1:18025/api/v1/message/<id>/raw` | measured |
| M6 | Mailpit prunes the oldest messages beyond a default cap (500) | Mailpit documentation | **remembered** — the planted control (D6) is what makes a pruned mailbox fail loud instead of green |

The probes are the refine's scratch scripts, not shipped; re-derive them at implement time over the newest run.

## Decisions

- **D1 · Both sweeps are run-level, after the group loop,** in the logs CR's slot and for its reasons (its D1):
  the statement log and the mailbox are shared by every suite, so they have no per-suite owner; each is swept
  against the **union** of the run's canary files, and a hit names the canary files that carry the canary.
- **D2 · The statement log is on for exactly the run's window.** `compose_up` records the prior
  `@@global.general_log` / `@@global.general_log_file` / `@@global.log_output`, sets `log_output=FILE`,
  `general_log_file=/tmp/veyq-e2e-<stamp>.log` (inside the container, outside the data volume) and
  `general_log=ON`; a `finally` in `main()` restores all three. An orchestrator killed hard leaves it on — the
  next run's start line prints that it found the log already on, so the leak of disk is visible, not silent.
- **D3 · The retained copy is the run's own.** After the sweep, the file is copied into `.e2e-out/<stamp>/
  mariadb-general.log` and deleted in the container. It can hold the developer's own dev-backend statements from
  the same window; that is their data on their disk, in a gitignored directory — stated in the concern, not
  filtered (a filter by thread would be a second parser to be wrong in).
- **D4 · A literal is matched the way the driver wrote it.** The statement log holds SQL literals, not dump
  rows, so the needle set is `canary_needles` plus one `sql_literal` form modelling Connector/J's escaping
  (quote and backslash), measured in phase 1 on a real run and bitten with synthetic canaries in the buildless
  guard — real canaries carry no quote or backslash (logs CR I6), so the escape half has no live instance to
  prove it.
- **D5 · Armed, by two controls on the same matcher.** (i) A **planted sentinel at both ends of the window**:
  the orchestrator runs `DO '<sentinel>'` as root at start and just before reading, and both must be found —
  which proves the file is the one being written, and that nothing (a parallel run, a crash) switched the log
  off mid-run. (ii) **Every suite database of the run appears in a `Connect` entry** (`… on veyq_e2e_<n>`,
  derived from the run's own database list, never listed) — which proves backend traffic was captured, not only
  the orchestrator's. If H4's `Connect` form measures differently, the control anchors on whatever line names the
  schema; the phase says which.
- **D6 · The mailbox is read by the orchestrator through Mailpit's API, bounded by the run, decoded by the
  standard library.** Every message with `Created ≥ run start` (paged, never trusting a default page size), each
  fetched **raw** (`/api/v1/message/{id}/raw`) and parsed with Python's `email` package (`policy.default`): every
  header decoded, every text part's content decoded. The sweep searches the decoded text **and** the raw source
  with `canary_needles`. Control: the orchestrator plants one mail of its own over SMTP (`smtplib`, to the
  published `11025`) whose subject and body carry a run-unique sentinel with a space and a non-ASCII character;
  the sweep must find it **in the decoded text** — and B5 proves it is *not* found in the raw source, so the
  decoding is shown to be load-bearing rather than assumed.
- **D7 · No mail is deleted during a run** (question 5): `mailCursor` + `waitForMail(to, { after })` replace
  `clearMailbox` at its four call sites, and `clearMailbox` is removed — a helper that deletes is a helper the
  next suite calls.
- **D8 · The buildless half joins the logs CR's guard.** `validate_wire_log_matcher.py` (logs CR D8) gains the
  `sql_literal` needle and the MIME reader: synthetic canaries with a quote, a backslash, a newline and a
  non-ASCII character are found in a synthetic statement log and in a synthetic multipart mail with an
  encoded-word subject and a quoted-printable body. Its docstring's three fields grow accordingly.
- **No new ADR.** ADR 0053's table already puts "bytes with no schema" under canary absence; which records are
  read and which controls arm them is this concern's state.

## Phases

- [x] **1 · Measure H4, then the statement-log sweep** · Done when: H4's three facts are measured on a real run
      and written into this CR's Inventory (replacing *remembered*); `python3 scripts/validate_wire_log_matcher.py`
      green; one `scripts/run_web_e2e.py` run green printing the statement log's line count, both sentinels
      found, and every suite database's `Connect` found; B1, B2, B3 red first.
- [x] **2 · The mailbox sweep + the cursor** · Done when: a run green printing the message count swept and the
      planted mail found through the decoded read; `grep -rn 'clearMailbox' web/src` empty; `npm run check` in
      `web/` green; B4, B5 red first.
- [ ] **3 · State and harvest** · Done when: `python3 scripts/validate_doc_links.py`,
      `python3 scripts/validate_changes.py` green, and the whole
      `for f in scripts/validate_*.py; do python3 "$f"; done` glob green **reporting how many ran**.
  - Concern: two Surface rows → **live** (the statement log, the mailbox), each naming sensor, window, needles and
    controls; § *What a green sweep does not prove* gains: the object-store bytes of an object deleted within a
    suite (question 2, with H3's count); the statement log sees only statements, so a value the backend computes
    and never sends to the database is not there by construction; the mailbox sees what reached Mailpit, not what
    a production relay adds.
  - `docs/inbox.md` rows #202 and #204 → done.

## Bite tests, by kind

Every lever is snapshot-and-restore, asserts its own arrival before the run, and is scored by the failure it
**names** ([changes/README](README.md) § *a bite test must tell "the guard held" apart from "nothing ran"*).

- **B1 · injection — the red-first demonstration for #204.** Lever: in `run_web_e2e.py`, for one suite
  (`memoryLocation.e2e`), immediately before its dump, the orchestrator's root session writes one of that
  suite's real canaries into a text column of that suite's database and then writes the original value back. The
  end-state dump sweep stays **green** (run it first — that is the measurement that #204's gap is real, not
  read); the statement-log sweep goes red naming the file, the line, the needle variant and the canary. Not
  through the API: a canary in a request body is already red on `sweep_http`, which would score the wrong sensor.
- **B2 · injection — "nothing ran" is not "held".** Three levers, each red naming its control: skip the
  `SET GLOBAL general_log=ON` (both sentinels missing); switch it off mid-run from a second shell (the end
  sentinel missing); add to the `Connect` control's derived list a database the run never booted (its `Connect`
  missing — proves the control reads the log rather than echoing its input).
- **B3 · injection — the escape model.** Remove the `sql_literal` branch from the needle set; the buildless
  guard goes red naming the variant.
- **B4 · injection — the red-first demonstration for #202.** Two levers on D6's plant: plant a **real** canary
  of the run (a space-bearing one, so it crosses quoted-printable) instead of the sentinel → red naming the
  message, the header or part, and the canary; and plant under a key the sweep was not given → red naming the
  missing mailbox control. Not through an owner-authored invite template: that canary would cross the API and the
  database first and be scored by `sweep_http` and the dump sweep instead.
- **B5 · measurement — decoding is load-bearing.** Run the mailbox sweep over the raw source only: the planted
  sentinel (non-ASCII) must **not** be found, and the control must go red. If it is found raw, Vert.x mail sent
  8-bit and M5's encoding half is wrong; the retrospective says which, and the decoded read stays either way.
- **B6 · already live.** The dump, store and HTTP sweeps keep printing their per-suite counts;
  `python3 scripts/validate_e2e_canaries.py` stays green.

## Definition of done

`scripts/run_web_e2e.py` green over every suite with the statement-log and mailbox sensors armed, printing what
each swept and which controls were met; B1–B5 went red (or, for B5, measured) first, each naming what it planted;
no suite deletes mail; the guard glob green with its run count reported; the concern's two new rows **live** and
§ *What a green sweep does not prove* extended. `satisfies:` stays empty — a crosscutting concern with no
use-case, as its three earlier slices shipped. Proof is a local run over the committed tree: there is no remote,
so no CI job has run ([changes/README](README.md) § trust-chain guards, "there is no remote").

## Retrospective

<!-- filled at close -->
