---
id: cr-wire-assertion-server-logs
title: The wire sweep reaches the logs the operator reads
status: in-progress
satisfies: []
adrs: ["0053", "0044", "0040"]
invariants:
  - Operator-blind — this CR only ever ADDS observation. It proves that no plaintext the client sealed is
    readable in a log the operator holds; it must never change what the backend logs to make that true.
  - The oracle is the wire, not the code — every log is read by the orchestrator, after the process that wrote
    it has exited; nothing in the suite or the backend decides whether the sweep passes.
---

## Open for the owner

**Third round answered by the owner, 2026-10-01: every recommendation added since the second answer is taken too** (questions added by the third refine wave and by implementation hand-backs). Rows those questions proposed exist in `docs/inbox.md` (#233–#243).

**Second round answered by the owner, 2026-10-01: every recommendation added since the first answer is taken too** (questions added by the 2026-09-28 second refine wave). Rows those questions proposed exist as #222–#232.

**Answered by the owner, 2026-09-28: every recommendation below is taken, and implementation has the go.** Read each question's recommendation as its decision. Where a question proposed a separate inbox row, that row exists (#197–#221, `docs/inbox.md`).

Each question carries a recommendation; the CR below is written as if every recommendation is taken, and names
what changes where one is not.

1. **Which logs count?** *Recommended:* **every boot log the run retains** (each suite's
   `backend-<tag>.log`, plus `backend-store-init.log` and a group's `backend-install-<n>.log` — derived by glob,
   not listed) **and every container of the compose project the run brought up**, over the run's own time window
   (MariaDB, Garage, Traefik, Mailpit — derived from `docker compose ps`, not listed). **Out**, each with the
   reason: the admin container's nginx (not in the dev stack at all — Inventory row I7); the operator tool's
   output (its only inputs are the database and the bucket, both already swept at the source); the vitest output
   (the client's own console — not an operator surface). The value is lopsided and the CR says so: **Garage is
   the container that matters** (it logs every request URI at INFO by default — I5), the other three are
   near-silent and ride along because the rule that includes them is derived and costs nothing.
2. **How is a canary matched in a log — which encodings?** *Recommended:* one function,
   `canary_needles(canary)`, returning the **raw** form, the **JSON-escaped** form (both `ensure_ascii`
   spellings) and the **percent-encoded** form (both `%20` and `+` for a space), matched case-sensitively as a
   plain substring over the **whole file** (a canary with a newline spans lines; the hit's line number is
   computed from the offset). **No base64 variant:** the only way base64 of a plaintext reaches the server is a
   client sending an unsealed value in a base64 field, which is the HTTP opacity oracle's case (readability at
   ≥ 28 bytes, shortness below — `opacity_failure`), and the server never base64-encodes plaintext it does not
   have. **No word-boundary rule** either — see Decision D4 for the measured reason and the noise it accepts.
3. **Must the sweep prove the sensor is armed?** *Recommended:* **yes, with a positive control on the same
   matcher, per log**, never a count of zero hits read as success. For a boot log: the orchestrator-chosen
   database name (Flyway prints it — I3), the shutdown line (the log is complete — I3) and a non-empty file.
   For the containers: a **planted canary every run** — the orchestrator sends one unsigned request to Garage for
   an object key built from a run-unique sentinel containing a space and a non-ASCII character; Garage logs the
   request URI as sent — percent-encoded — at the start of handling (read from its source, I5; phase 2 confirms
   it on a refused request), so the sweep must find it **through the percent-encoded needle**. This is
   [docs/ai](../docs/ai/README.md)'s *every negative assertion owes a positive control on the same predicate*,
   run on every run rather than once as a bite: a sensor that cannot find the value it was handed cannot be
   trusted to find one it was not. (ADR 0053 itself carries no bite rule — measured, `grep -c bite` is 0; the
   habit lives in [changes/README](README.md) § *a control's LEVER must itself be bitten*.) No per-run plant for the backend log: no backend log
   statement echoes request input (I4), so there is nothing to plant through without adding a test affordance
   to shipped code — the database-name control stands in for it, and bite B5 covers the JVM path once.
4. **What does a hit mean?** *Recommended:* **the run fails**, as one more entry in the existing summary,
   and **each hit is printed in full**: file (or container), line number, needle variant, the canary, the
   suite(s) whose canary file carries it, and the line itself truncated to 240 characters. Printing the line is
   required, not decoration: CI does not upload `.e2e-out/` (I8), so the job log is the only evidence that
   survives. **A failed suite's log is swept too** — it is the log most likely to hold a stack trace, and a hit
   there only adds to a run that is already red. Never an allowlist: a hit is a leak or a canary colliding with
   declared plaintext, resolved per value, exactly as the database sweep resolves it.
5. **Fold the needle function into the HTTP sweep?** *Recommended:* **yes, in this CR.** `sweep_http` matches
   the raw canary only (`run_web_e2e.py`, `if canary in haystack` in `sweep_http`), while the recorder stores
   the URL as the client built it — percent-encoded. Measured: **129 of 220** canary instances across the
   retained runs contain a space (I6), so each of them would cross a URL as `%20` and be invisible to the HTTP
   sensor today. One definition of "how a canary can appear" for every text surface; the database keeps its own
   (`dump_escape`, which models the dump rather than a transport).

## Goal

[cc.security.wire-assertion](../docs/crosscutting/cc.security.wire-assertion.md) § Surfaces has three rows
live — database, object store, HTTP bodies — and one *planned*: the **server log**, where an error `message` or a
stack trace would carry plaintext. The owner decided on 2026-09-28 that it is built before V1 rather than parked.
This CR makes it live: after the run, the orchestrator sweeps every log an operator of this stack would read for
every canary the run derived, proves per log that the sensor was armed, and fails the run naming the line on any
hit. Its retention is already paid ([ADR 0053](../docs/decisions/0053-wire-assertion-canary-sweep.md) Decision 4,
`.e2e-out/<stamp>/`), so this CR starts at the sweep.

**What the log adds that the other three do not — the reason it is worth building.** A canary can only reach a
server-side log if it reached the server in plaintext, and the HTTP sweep already forbids that on the client
side. The log is still a distinct sensor for two measured reasons: the server is where a value is **decoded**
(a percent-encoded path segment, a JSON-escaped string) and then written raw, which a raw match on the client's
encoded bytes does not see (question 5); and Garage's request log is the **only surviving record** of an object
key whose object was deleted or never completed, which the bucket mirror cannot see because the object is gone.

## Inventory (measured 2026-09-28 against `c14abe8`, unless marked remembered)

| # | Fact | Locator | Kind |
|---|---|---|---|
| I1 | Each boot's stdout+stderr goes to `<run>/backend-<tag>.log` (and `backend-store-init.log`, `group-<n>-<name>/backend-install-<n>.log`); the run directory is retained | `grep -n 'boot_log = \|log_path.open\|RUN_ROOT' scripts/run_web_e2e.py` | measured |
| I2 | The three live sweeps run inside `run_member`'s `try`, **before** `kill(proc)` in its `finally` — a log swept there is read before the JVM's shutdown lines exist | `grep -n 'sweep(database\|sweep_http(record\|sweep_store(inst\|kill(proc)' scripts/run_web_e2e.py` | measured |
| I3 | Log format is Quarkus' default plain text; no profile, chart or compose file sets any `quarkus.log.*`. Across **28 retained runs / 105 boot logs** in the main checkout's `.e2e-out/` (top-level logs only): every suite log carries its database name (94/94; Flyway's `Database: jdbc:mariadb://…/veyq_e2e_<n>` line), every log carries `stopped in` (105/105), and the only levels present are INFO and WARN — **no ERROR line, no stack trace**. In the latest run the store-init, install and restored-member logs carry theirs too (`veyq_e2e_store_init`, `veyq_e2e_restore_<n>`) | `grep -rn 'quarkus.log' backend/src/main/resources charts infra`; the probe below | measured |
| I4 | The backend has **34** log call sites at info/warn/error and **0** at debug/trace; none echoes request input — the interpolated values are ids, domains, emails (declared `pii`), config values and `e.message` | locator L1 below the table | measured |
| I5 | Garage v2.0.0 logs **every request** at INFO as `<addr> <method> <uri>`, the URI with its query string; the server's default filter is `garage=info` when `RUST_LOG` is unset, and the dev compose sets no `RUST_LOG` | Garage source `src/api/common/generic_server.rs` and `src/garage/main.rs` at tag `v2.0.0` (read via the GitHub mirror); `grep -n RUST_LOG infra/docker-compose*.yml` | measured (source read, not a run) |
| I6 | Canaries across the retained runs: 80 distinct, 220 instances; **129 contain a space**, **0** contain a quote, backslash, newline or non-ASCII character; the shortest are `auto`, `city`, `fixed`, `manual`, `range` (envelope vocabulary, not test data) and `Teneriffa`. A prototype sweep of all 220 against their own logs in raw, JSON and percent-encoded forms: **0 hits**; of all 80 raw against **every** retained log (cross-suite collision with boilerplate): **0 hits** | the probe below | measured |
| I7 | The e2e path touches neither Traefik nor the admin nginx: suites call the backend on `:8080` and its admin listener on `127.0.0.1:8090` directly, `%e2e` leaves `public-endpoint` unset so presigned URLs go to Garage's `:19000`, and the dev compose has no admin service. Neither compose file enables a Traefik access log | `grep -n '^  [a-z-]*:$' infra/docker-compose.yml`; `grep -n 'public-endpoint\|endpoint=' backend/src/main/resources/application-e2e.properties`; `grep -n 'BASE = \|ADMIN_BASE = ' scripts/run_web_e2e.py`; `grep -rn -i accesslog infra charts` | measured |
| I8 | CI runs the orchestrator bare and uploads nothing: `.e2e-out/` dies with the runner | `grep -n 'run_web_e2e\|upload-artifact' .github/workflows/ci.yml` | measured |
| I9 | The JVM is 21; when stdout is a file, `stdout.encoding` follows the host locale, so a non-UTF-8 locale writes a non-ASCII canary as `?` and the raw needle silently misses it. Today's logs carry `—` intact (the admin-listener line), so this Mac is UTF-8; CI's runner locale is not measured | `grep -n languageVersion backend/build.gradle.kts`; `grep -c '—' <any backend-*.log>` | JVM default **remembered**; the Mac's output measured |
| I10 | Stale state this CR's own surfaces carry: the concern's **Status** line still says only the database is swept, its log row says the log must first be *retained*, and both it and `sweep()`'s docstring say **four** e2e suites exist (there are 12 files) | `grep -n 'Status:\|retain\|four' docs/crosscutting/cc.security.wire-assertion.md scripts/run_web_e2e.py`; `find web/src -name '*.e2e.test.ts'` | measured |
| I11 | The buildless precedent for importing the orchestrator: `validate_e2e_discovery.py` loads it by path with `importlib` (so module scope must stay side-effect free — `RUN_ROOT` is a constant for that reason) | `grep -n 'spec_from_file_location\|RUN_ROOT' scripts/validate_e2e_discovery.py scripts/run_web_e2e.py` | measured |

L1 — kept out of the table, where its pipes would split the cell:

```sh
grep -rn '\b\(Log\|LOG\|log\|logger\)\.\(debug\|trace\|info\|warn\|error\)f\?(' --include='*.kt' backend/src/main
```

The probe is the refine's own scratch script, not shipped. It reads every `.e2e-out/*/backend-*.log` and its
`canaries-<tag>.json`, and reports canary character classes, the two controls' presence, and hits per needle
variant. Re-derive it at implement time rather than trusting the numbers above: the retained runs change.

## Decisions

- **D1 · The log sweep is run-level, after every group, not per suite.** It sweeps every `backend-*.log` under
  the run directory (recursive glob) against the **union** of every canary file of the run. Three reasons, each
  with its locator: the logs must be complete, and a per-suite sweep inside `run_member`'s `try` would read them
  before shutdown (I2); a restored instance holds the previous member's data, so its boot log owes that member's
  canaries too, which a per-suite pairing would miss (`HOOKS` in `run_web_e2e.py`); and the containers are shared
  by every suite of the run, so their logs have no per-suite owner anyway. Attribution is per canary instead: a hit
  names the canary files that carry it. It runs even when suites failed and even when a group aborted mid-way
  (anything that does not end the process); an aborted *process* skips it, and the run is red regardless.
- **D2 · Container logs are read once per run, bounded by the run.** `docker compose logs --no-color --timestamps
  --since <run start>` over every service the project reports, from the orchestrator (ADR 0053 §3: Docker is its
  side). The lower bound keeps a developer's earlier traffic on these long-lived containers out; the upper bound is
  "now". A non-zero exit is a `SweepError`, never an empty log.
- **D3 · One needle function for every text surface** (question 2 and 5): `canary_needles(canary)` → raw,
  JSON-escaped ×2, percent-encoded ×2, de-duplicated. The log sweep and `sweep_http` both use it; `sweep()` keeps
  `dump_escape`, `sweep_store` keeps raw bytes and rclone-decoded keys. Each hit reports which variant matched.
- **D4 · Plain substring, case-sensitive, no length floor, no word boundary.** The canary set contains envelope
  vocabulary (`auto`, `range`, …; I6) that ordinary log prose could contain, and a boundary rule is the obvious
  guard — it is also a threshold, and [changes/README](README.md) § *a threshold added against false positives
  is where the false NEGATIVES hide* applies. Measured: 0 collisions across 105 logs with 80 canaries (I6). If a
  real run collides, the hit is **explained, not excluded** (§ *a false positive in a measurement*): it goes back
  to refinement with the line, and the options then are a distinctive test value or a derived vocabulary rule —
  never a list.
- **D5 · Armed, per log, by positive controls on the same matcher** (question 3). A boot log must be non-empty,
  contain its database name, and contain the Quarkus shutdown line. The database name is derived from the file
  (`backend-<tag>.log` ↔ `veyq_e2e_<n>`, `veyq_e2e_restore_<n>`, `veyq_e2e_store_init`), never listed. The
  shutdown line is a framework string, and anchoring on it is chosen knowingly: a Quarkus upgrade that rewords it
  fails **loud**, which is the direction this sensor fails in. Garage's log must contain the run's planted
  sentinel, found through the percent-encoded needle. The other three containers carry no control — Traefik carries no e2e traffic
  and has no access log (I7); MariaDB's general log is off in the stock image and Mailpit logs deliveries, not
  bodies (both **remembered** — the first real run's per-container line counts are the measurement) — and the
  concern says so rather than implying they are proven.
- **D6 · The boot pins the JVM's output encoding.** `boot()` adds `-Dstdout.encoding=UTF-8
  -Dstderr.encoding=UTF-8`, and the sweep reads logs as UTF-8. The sensor must not depend on the host's locale
  (I9); a production operator's locale is theirs, and a leak written as `Gr??e` is still a leak of everything
  ASCII in it, which the sweep then sees in full rather than in part.
- **D7 · The level observed is the level shipped: INFO.** No profile, chart or compose file sets a log level
  (I3), so an e2e boot writes exactly what a production boot writes; and there is no veyq debug statement to
  raise (I4). Raising the root to DEBUG would add framework volume, not veyq surface — revisit only when a veyq
  debug statement appears.
- **D8 · The buildless half ships as a guard** — `scripts/validate_wire_log_matcher.py`, which imports the
  orchestrator the way `validate_e2e_discovery.py` does (I11) and runs its needle function and its log sweep over
  synthetic files in a temporary directory: every variant of a synthetic canary set containing a space, a quote,
  a backslash, a newline and a non-ASCII character is found; an empty log, a log without its database name and a
  log without its shutdown line each fail **naming the control**. This is [changes/README](README.md)
  § *split an expensive live proof so its no-daemon half runs anywhere* — and it is where B2 and B3 live
  permanently, because the real canaries exercise none of the JSON-escape cases (I6). Its docstring carries the
  three fields § *the trust-chain guards stay green* requires.
- **No new ADR.** ADR 0053's table already names the server log with canary absence as its oracle; which logs,
  which needles and which controls are this concern's state, and land in its § Surfaces and § *What a green
  sweep does not prove*.

## Phases

- [x] **1 · The needle function, the boot-log sweep, the encoding pin** · Done when: `python3
      scripts/validate_wire_log_matcher.py` green; one `scripts/run_web_e2e.py` run (at least `media.e2e` and
      the `restore` group — a group is what exercises D1's union) green, printing per boot log the canary count
      swept and the three controls met; B1, B2, B3 red first.
  - `canary_needles`, `sweep_logs(workdir, canary_files)` called from `main()` after the group loop, its failure
    appended to `failures` under a name that says it is the run's log sweep.
  - D6's two flags in `boot()`.
  - Worktree note for the implementer: run the **worktree's** orchestrator with `VEYQ_INFRA_DIR=<main
    checkout>/infra` (the `compose_up` docstring owns why).
- [x] **2 · Container logs + the Garage plant** · Done when: a run green, printing per container the line count
      swept and, for Garage, the sentinel found and through which variant; B4 red first.
  - The plant is one plain HTTP request from the orchestrator to the store's published S3 port for
    `/<bucket>/<sentinel>` — unsigned, refused, and logged. **Confirm at implement time that the refused request
    is logged** (I5 was read from source, not run); if it is not, the plant becomes a signed HEAD through the
    operator tool's store handle, and the concern says which.
- [x] **3 · `sweep_http` uses the needles** (question 5) · Done when: a run green; B6 red first.
- [ ] **4 · State and harvest** · Done when: `python3 scripts/validate_doc_links.py`,
      `python3 scripts/validate_changes.py` and `python3 scripts/validate_e2e_canaries.py` green, and the whole
      `for f in scripts/validate_*.py; do python3 "$f"; done` glob green **reporting how many ran**.
  - Concern: the log row → **live**, naming the sensor, the logs, the needles and the controls; the Status line
    and the stale "retain" and "four suites" sentences fixed (I10).
  - § *What a green sweep does not prove* gains, at least: the sweep observes the e2e topology, which bypasses
    the reverse proxy and has no admin nginx (I7), so those two logs are unexercised — both default to no
    request log in both deployment targets; MariaDB, Traefik and Mailpit are swept but carry no control;
    container logs are shared, so a hit's suite is inferred from the canary, not from the log; a log records only
    what the process chose to write, at INFO.
  - `sweep()`'s docstring: the "boot log is merely retained here" sentence goes (I10).
  - `docs/inbox.md` row #102 → done for the log surface; the V1 line's #102 entry (currently "the server log …
    is not owed") follows the owner's 2026-09-28 call.

## Bite tests, by kind

Every lever is applied by snapshot-and-restore, asserts its own arrival **before** the run (the new text is
present and the file's bytes differ from the snapshot), and is scored by the failure it **names**, never by "the
run went red" ([changes/README](README.md) § *a bite test must tell "the guard held" apart from "nothing ran"*).

- **B1 · injection — the red-first demonstration.** Lever: in `run_web_e2e.py`, immediately before
  `sweep_logs`, append one line holding a real canary (the first entry of `canaries-1.json`) to
  `backend-1.log`. Red must name `backend-1.log`, the appended line's number, `raw`, and that canary.
- **B2 · injection — encodings.** Same lever, writing a space-bearing real canary (I6: 129 exist) in its
  percent-encoded form → red naming `pct`. The JSON-escape forms have no real canary to carry them (I6), so they
  are bitten in `validate_wire_log_matcher.py` with synthetic canaries — and that guard is itself bitten once by
  removing the JSON branch from `canary_needles` and watching it go red naming the variant.
- **B3 · injection — "nothing ran" is not "held".** Three levers, each red naming its control: truncate
  `backend-1.log` to zero bytes; delete its `Database:` line; delete its `stopped in` line. Plus the one that is
  easy to skip: point `sweep_logs` at an empty directory → red, never "0 logs, 0 hits".
- **B4 · injection — the container plant.** Two levers: change the plant's key to a string the sweep was not
  given → red naming the missing Garage control; and plant a **real** canary instead of the sentinel → red naming
  the container, the line, `pct`, and the canary.
- **B5 · measurement — the JVM path, once.** Remove D6's flags and boot one suite under `LC_ALL=C`; record
  whether the log's `—` survives. If it degrades, D6 is load-bearing and the retrospective says so; if it does
  not, D6 is belt-and-braces and the retrospective says that instead. Either answer is written down, not
  assumed.
- **B6 · injection — HTTP needles.** Lever: in one suite, add a request whose query string carries a
  space-bearing canary (`encodeURIComponent`) to an endpoint that ignores it. Red must name `pct` and the
  request. On the untouched `sweep_http` the same lever stays **green** — run that first, because it is the
  measurement that proves question 5's gap is real rather than read.
- **B7 · already live.** The database, store and HTTP sweeps keep biting: a phase-1 run still prints each
  suite's DB canary count, its request count and its object count, and `python3
  scripts/validate_e2e_canaries.py` stays green.

## Definition of done

`scripts/run_web_e2e.py` green over every suite with all four sensors armed, printing per boot log and per
container what was swept and which controls were met; B1–B6 went red first, each naming what it planted; the
guard glob green with its run count reported; the concern's log row **live** and § *What a green sweep does not
prove* extended as phase 4 lists. `satisfies:` stays empty — a crosscutting concern with no use-case, the shape
the three earlier slices shipped under; the buildless half is `validate_wire_log_matcher.py` and joins the glob
by existing.

## Retrospective

<!-- filled at close -->
