---
title: How veyq is tested
description: veyq's code is written by an AI coding agent and not reviewed line by line by a person. This page says which checks stand in for that review, what each one proves, what none of them can, and which of them have actually run.
sources:
  - docs/crosscutting/release-and-deployment.md
  - docs/decisions/0091-one-pipeline-per-commit-a-release-promotes-a-tested-snapshot.md
  - docs/decisions/0090-end-to-end-tests-boot-the-release-candidate.md
  - docs/decisions/0040-coverage-gaps-and-http-not-a-surface.md
  - docs/ai/README.md
  - docs/architecture/README.md
---

veyq's code is written by an AI coding agent working under the owner's direction. The owner decides what is built
and how, in conversation, before the work starts; no person reads each change line by line afterwards. So the
question a reviewer would answer — *is this change correct, and did it break anything?* — has to be answered by
checks that run on every change. What you can rely on is what those checks show, and only that. This page lists
them, says what each one proves and where it stops, and marks which are built and which are only designed
([ADR 0091](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0091-one-pipeline-per-commit-a-release-promotes-a-tested-snapshot.md)).

:::caution[CI has only just started running]
The repository's first push and so its first CI run were on 2026-11-05; until then every check described below as
*built* had run only on a developer machine. That first run was red in several jobs on the CI machines' own terms (a
cold cache over the budgets, a newer Python, a slower runner), and the fixes land before a run shows them green. The
project's own rule is that a test no CI run executes makes no coverage claim
([ADR 0040](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0040-coverage-gaps-and-http-not-a-surface.md)),
and this page follows it: a stage moves to *runs in CI* only after a CI log shows it executed. Until then, what the
pipeline would run in CI is read from its workflow files, a statement about a file, not about a run.
:::

## Why checks, and how they are kept honest

A reviewer catches what the author did not think of. Without one, a defect is caught by a check or it ships. Four
habits make the checks worth relying on:

- **The rules are written down first.** The API contracts under `contracts/` are the source the server and the
  clients are generated from, so no hand-written copy can drift from them. Every feature has use-cases with numbered
  acceptance criteria, and every use-case has a coverage file that names the test proving each criterion — or says,
  with a reason, that none does yet ([`docs/coverage/`](https://github.com/raddatzk/veyq/tree/main/docs/coverage)).
- **A check is made to fail before it is trusted.** The working rules require that a new guard, or a test that
  carries weight, is first run against a case that must make it fail — a check that has never been red has not shown
  that it can be. That is a rule for each change, not a property re-checked across the whole tree; the guard-gap stage
  re-checks it for the guards.
- **Assertion strength is measured, not assumed.** A test that asserts nothing still passes. Mutation testing changes
  the code under test on purpose and requires the tests to notice; it covers the cryptographic and policy code where a
  weak assertion would matter most
  ([ADR 0045](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0045-mutation-testing-trust-chain-unit-layer.md)).
- **The operator-blind claim is tested from the outside.** End-to-end runs record every plaintext value their client
  sealed and then search everything an operator could read for it
  ([wire assertion](https://github.com/raddatzk/veyq/blob/main/docs/crosscutting/cc.security.wire-assertion.md)).

## The stages

Each stage proves one thing. The table says what, what it cannot see, and whether it is built; [where and when
each one runs](#where-and-when-each-stage-runs) follows it.

| Stage | What it proves | What it cannot see | Status |
|---|---|---|---|
| **Guards** (`scripts/validate_*.py`) | The tree keeps its written rules: every reference resolves, every contract field declares what the server may read, every image is pinned by digest, the production recipe is hardened. | That what a reference points at carries weight — a cited test that asserts nothing passes. | Built. |
| **Guard gaps** (`validate_guard_gaps.py`) | The guards still reject the forgeries they claim to reject, and their documented limits are still exactly where they are written. | A guard's limit that was never documented. | Built. |
| **Surface checks** (`./gradlew check`, `pnpm run check` per package, the chart check) | Each part's unit and integration tests: the backend against an in-memory database and in-process fakes, the web client, the admin console and the crypto package in their test runners; the Helm chart's renders keep the production recipe's structural rules, and an upgrade from the previous chart changes no field Kubernetes refuses to change. | The real database, the real object store, the real browser. | Built. |
| **Crypto conformance** | The bytes of the cryptographic suite cannot move silently: every primitive is pinned to published test vectors. | Whether a correct primitive is used correctly. | Built, inside the surface checks; see [Verify it yourself](/security/verify-it-yourself/). |
| **Mutation** | The tests of the scoped crypto and policy files fail when that code changes; on the backend, each scoped class also on its own, within a budget of one surviving change, so a weak class cannot pass on the score of the others. | Files outside the scope. | Built. |
| **Races on MariaDB** | Concurrent writes behave on the production database as they do in the in-memory one. | Races no test provokes. | Built. |
| **API end-to-end** (`scripts/run_web_e2e.py`) | Real client cryptography against a live backend, database and object store, a fresh instance per suite; backup and restore round trips; no sealed plaintext or capability token readable in the database, the store, the HTTP traffic, the logs, the SQL statements or the mail. | Only the values the suite unsealed; no browser, no production image. | Built. |
| **API fuzzing** | Every operation of the API contracts gets requests generated from the contract itself ([Schemathesis](https://schemathesis.readthedocs.io/)): a request the contract does not allow — an out-of-range path, query or header value included — is refused, a request it allows is never refused as malformed, every answer is a status the operation declares with a body its schema allows, a refusal repeats nothing it was sent, and nothing answers with a server error. Reads and writes run against a Memory with a photo, a link to it and a group space the run creates with the real client encryption; sequences of calls follow the links between operations; and a second member of the same server, asking for what the first keeps in their own space, gets exactly the answer for something that does not exist. | Writes that would change how the run signs in, the server's or the tenant's settings (those are only sent malformed); requests the server refuses for a rule the contract states only in its prose (open and named); moderation cases (the run creates none); anything Traefik or the production image adds. | Built ([ADR 0091](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0091-one-pipeline-per-commit-a-release-promotes-a-tested-snapshot.md) decision 9). Its first runs found server errors (on malformed input, and on an id or a date the database cannot store), accepted malformed requests, undeclared answers, valid requests refused, and HTTP methods an operation does not have answered without the `Allow` header the HTTP standard requires — fixed; the rules stated only in prose are open and named. |
| **Image build** | The three images that ship — the app, the admin console and the tools image a Kubernetes instance's backup runs in — build from the commit, for amd64 and arm64. | Whether they run: lane L1 installs the first two; no lane starts the tools image, and no lane boots the Helm chart — the chart is checked by its renders only. | Built. |
| **Upgrade proof** (`run_web_e2e.py --upgrade`) | Content sealed by the previous version opens after a restore onto this one, which migrates it. | The upgrade of a running instance; the shipped images. | Built; its CI job is off until the first release. |
| **Stack lane L1** — the candidate, fresh (`scripts/run_stack_e2e.py`) | The two images that ship, installed the way a Server Owner installs them — `veyqctl install` from the candidate's own release files — with a real ACME certificate from a test CA, driven in Chromium: the domain says it is not set up, the Server Owner is created and its Emergency Kit read back, the bundled store connected and checked, the first tenant created; its owner accepts on the tenant's own domain and certificate, writes a Memory with a photo and shares it read-only; a second browser signs in with only the password and the Secret Key and sees the photo decrypted; an anonymous visitor opens the link. Then the first feature scenario: the Server Owner switches drop boxes, adding a device from another one, unlocking without a password and remembered devices off and on again in the console, and the web client loses and regains each of them; a trusted and a remembered device ask for the password and the Secret Key while their switch is off, delete or replace none of their keys on the server, and open as before once it is back on. Every text field and the profile picture written once — a folder renamed and moved, a Memory's date, a sub-bucket, a link's label and note, a guest's name, the member's own name and picture — and read back after a reload and on a second device. A guest's upload the link's limit refuses leaves no empty file behind, and the same guest cannot take back a member's empty folder. The Server Owner points the release check elsewhere and switches it off, and sets up instance mail against a test mailbox, confirming it with the code the instance mailed; then the check, pointed at a stand-in release mirror the run serves, finds the newer releases there: the console lists them with an unscheduled major apart as urgent, each is mailed once, the planned major's mail carries its date as a calendar file, and the check is switched off. Every flow runs in English; a second language is checked below this stage, where the translations are held to the same keys in both languages; here each application gets one short German pass for what only a browser shows in German (numbers and dates, an amount typed with a decimal comma, long words in the real layout, the language switch), and the privacy notice and the terms are read in both languages, since their exact wording is the claim. A rule a lower-level test already proves with a simulated clock is not waited out here again in real time. Every test names the acceptance criterion it covers, and a check holds each name to a criterion that exists. No page request to any origin but the instance — the two requests a page makes by itself are answered locally: the breached-password query with no match, Expenses' day exchange-rate table with a fixed table of ECB rates. Afterwards every value the browser typed (the password, the Secret Key, the names, the photo's name and the text inside it) is searched for, and must be absent, in the instance's database, its bucket, every container log and every SQL statement the database received — the same sensors as the API end-to-end suites. A run that executes no test is red, and so is every test in which a page reported a Content-Security-Policy violation — a Trusted Types refusal above all, which only a real browser shows. | Only what the browser typed, not everything the page sealed (the API suites cover that half); no HTTP traffic recorded, so no capability token searched for here; one tenant, a few Memories, folders and links; no browser restart. The release notices come from a stand-in: the candidate of a push is a snapshot, and only a release-stamped build looks for releases, so the run stamps its own instance with the release it installed, has it look every few seconds rather than daily, and serves the newer releases itself; veyq's real GitHub Releases are never read. The certificate comes from a test CA that is told every challenge is valid. The images are local builds installed unsigned, so `veyqctl`'s signature check ([ADR 0096](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0096-veyqctl-manages-an-instance-from-immutable-releases.md)) is not exercised here. | Built. |
| **Stack lanes L2 and L3** — upgrade in place, backup import | The previous release upgraded in place with `veyqctl upgrade`; every archived backup of the major restored into the candidate with `veyqctl restore`. | Anything the scenarios do not do. | Designed ([ADR 0090](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0090-end-to-end-tests-boot-the-release-candidate.md), ADR 0091); not built. |
| **Pentests** — scanners and attack paths against a fresh candidate | The release candidate, installed fresh before each path, withstands the attempts recorded: the API fuzzing's configuration and a web scanner pointed at it with its production limits, and attack paths across features, on rights and roles, and as the operator, each run by an agent and recorded command by command. Run on a developer machine, never in CI: a run takes hours, and a finding must not appear in a public log before it is fixed. | Anything no path attempts; a run reports attempts that failed against one commit, never that veyq is safe. | Designed ([ADR 0100](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0100-pentests-attack-a-reset-candidate-locally-and-publish-every-runs-record.md)); not built. |
| **Profiling** (`scripts/profile_hot_paths.py`) | Unlike every stage above, nothing: it is a reading, and nothing in it can turn red because veyq got slower. It shows where the time goes inside four hot paths — unlock, an upload of 50 photos, opening a Chapter of 1,000, importing a download of 50 photos — on the client and on the server, on the candidate's two images in the production recipe, and each hotspot it finds becomes a tracked item with its number. | Other browsers, real phones, other machines and real content; [its limits](#profiling-its-files-and-its-reading) are below. | Built; read by hand. |

### Profiling: its files and its reading

**A profile file is a file of a signed-in session, so it never leaves the machine.** The browser's CPU profile and
trace name the account's objects, and a heap snapshot would hold the decrypted content and the unwrapped keys of the
session, so none is taken, on either side. On the server, Java Flight Recorder by default writes the process
environment and the system properties into the recording, and the production recipe hands the app its database
password and the store's admin token in its environment. The driver switches those two events off and proves it on
every run: a random canary placed in the app's environment must be absent from the recording, and present in a control
recording taken without the switch. On its first runs the control held the canary, and the database password and the
store's admin token beside it, which is the reason for the switch. The files stay in the run's directory, which git
ignores; they are never committed and never attached to an issue, and only numbers move into the docs. None of this
touches operator-blindness: the server holds no content key a recording could leak.

**What the first reading showed** (2026-10-23 and 2026-10-24, an Apple M4 Pro with 14 cores, Chromium 156, three runs
per scenario at CPU throttle 1 and 4):

- **Unlock** takes 2.9–3.5 s, and the page's main thread is about 94 % idle meanwhile: it waits on the Argon2id worker,
  whose cost is the password hardening itself
  ([ADR 0017](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0017-crypto-primitives-and-post-quantum-strategy.md)).
- **An upload** of 50 photos of about 5.2 MB each took about 41 s, and the network was 9.5 % of it. The content encryption
  ran in JavaScript on the page's main thread: 12.4 s of its 24 s of work at throttle 1, about 51 s at throttle 4. In
  the same browser, the browser's own AES-256-GCM is about 18 times faster on a whole buffer and about 35 times on
  veyq's framed chunks, both matching the published test vectors. veyq keeps one implementation of each primitive and
  did not switch ([ADR 0017](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0017-crypto-primitives-and-post-quantum-strategy.md)).
  Instead, a blob's chunks are now sealed and opened, by the same code, in a worker beside the page. Read again on
  2026-10-27, on a busier machine (load average 8 to 17, Microsoft Defender busy throughout): the same upload takes
  30.2–31.1 s at throttle 1 and 90–96 s at throttle 4, against 133–135 s before. The main thread's share of the
  encryption fell to under 0.1 s, and the worker spends 5.1–5.4 s on it. The CPU profiler samples only the main
  thread, so a profiled reading could credit a move into a worker with the profiler's own cost; read again the same
  day without it, three runs before and after, interleaved, the upload took 42.5 s before and 32.0 s after at
  throttle 1 (medians): the same 25 %. Then each original's SHA-256, which the main thread had spent 3.1–3.2 s on, moved
  into a worker of its own as well, and now runs while the photo's smaller versions are made instead of before them.
  Without the profiler, the upload takes 25.8 s at throttle 1 (25.7–26.7 s) and 72.9 s at throttle 4 (72.7–73.3 s),
  against 32.0 s (31.4–32.9 s) and 93.4 s (88.5–93.7 s) just before; with it, 24.6–27.2 s and 71.1–78.5 s. The main
  thread no longer hashes, the hashing worker spends 4.4–4.5 s on it, about what the main thread had spent, and the
  network is now 18 % of the upload at throttle 1 and 8 % at throttle 4. Opening a 20 MB original or downloading a
  64 MB file put no task longer than 50 ms on the main thread. Read on 2026-10-28 by the main thread's tasks, sorted by
  the code each one ran, sending the chunks held most of what was left: each sealed chunk came back from the worker and
  the page started its upload request, 41–44 s of the main thread's 63–67 s at throttle 4. The worker now reads, seals
  and sends each chunk itself, handing the browser the chunk as a file object, which cost the sending thread about a
  seventh of what the same bytes cost as an array. On a machine at load 25 to 40, three runs each without the profiler
  beside two passes of the version before: 25.9 s against 29.8–32.2 s at throttle 1, and 37.5 s against 73.4–75.4 s at
  throttle 4. What held the main thread then was the gallery laid out again on each chunk's progress, about a quarter of
  the upload at throttle 4. A chunk now redraws only its own tile, which a unit test proves by counting the layouts.
  Read on 2026-10-29 with nothing else running on the machine, that took the upload at throttle 4 from 21.4 s to 20.0 s,
  and the main thread was busy only 3.0 s of 18.2 s at throttle 1: each photo was prepared (its type, its digest, its
  smaller versions, its EXIF read) and then sent, one after the other, both mostly in workers and on the network. The
  next photo is now prepared while the current one is sent, never touching a key or the network before its turn: 10.8 s
  against 17.6–19.2 s at throttle 1 and 13.4 s against 19.9–20.3 s at throttle 4, three runs each beside two passes of
  the version before, and in every run each next photo's digest ran while the previous photo was being sent.
  Each reading now also records the browser's memory. Picking the 50 photos decoded every one of them at once, only to
  learn each tile's shape: the tab's renderer reached 2.30–2.79 GiB at throttle 4 within 1.6 s, against 1.0–1.1 GiB for
  the rest of the upload. A tile now reads its photo's size from the first bytes of the file, and the highest the
  renderer reaches is the upload's own, 1.14–1.27 GiB (2026-10-29, three runs each at throttle 1 and 4). Most of what
  stayed was the tiles themselves: each showed the whole photo, and without them the renderer held 0.44–0.46 GiB. A tile
  near the screen now shows its photo shrunk to the thumbnail's size, one at a time, and the renderer stays at
  0.49–0.53 GiB, in the same time.
- **Uploading 20 videos** (1080p, 6 s each, about as many bytes as the 50 photos), read since 2026-10-29, also counts
  the video players the page opens. Picking them opened one player for every tile's still picture at once, each drawing
  its first frame at the full preview size: 18–20 decoding together, the renderer at up to 1.51–1.78 GiB against
  0.94–0.96 GiB without those pictures, and the upload 14.3–14.7 s at throttle 4 against 10.8–10.9 s. A video's tile
  now gets its picture in the photos' queue, thumbnail-sized and one at a time: 2 players decode at once, the renderer
  reaches 1.09–1.12 GiB, and the upload takes 8.3–8.8 s at throttle 1 and 12.4–12.5 s at throttle 4 (8.7–9.1 s and
  14.3–14.7 s before), three runs each. The page also opened a player per video just to read its size, at the pick and
  again at its upload: 22 players were alive at once. The size now comes from the file's own header, as a photo's does:
  2 players at once, and the upload takes 12.0–12.3 s at throttle 4 against 12.8–13.5 s. What the browser holds through
  the upload is unchanged by it; the tiles' still pictures account for it (0.85–0.89 GiB against 0.71–0.72 GiB without
  them).
- **Importing a download** of the same 50 photos, a fourth path read since 2026-10-29, took 20.2 s at throttle 1 and
  35.1 s at throttle 4. Before each photo was sent, the page itself checked it against the download's list of
  checksums: 4.0 s of its main thread at throttle 1, about 17 s at throttle 4. That check now runs in the worker that
  hashes uploads, one photo ahead, while the previous one is sent: 15.8 s and 17.2 s, three runs each. Each photo was
  then still prepared for sending (its smaller copies made, its details read) only after the one before it was sent;
  that now happens while the one before it is sent, as for an upload: 9.3–9.8 s and 10.7–13.3 s, as long as the upload
  of the same photos.
- **Opening a Chapter of 1,000 photos** shows every tile in the viewport after about 0.43 s. Its three requests sent
  about 116 SQL statements each, because the request for the thumbnails' download addresses read the database twice
  for every photo it named. It now reads each batch once, so the three send 13 to 17 statements however many photos
  the Chapter holds (counted by the backend's tests, which fail if the number grows with the photos again).
- **The server** spent under 1 % CPU on average across each recording, and its heap stayed under 180 MB.

**Its limits.** One machine, which Microsoft Defender kept busy during several runs (each reading is marked); Chromium
only; synthetic content; a loopback network through Docker's virtual machine. Chromium's CPU throttle slows the page's
main thread and never a worker: the Argon2id worker took 5,354 ms at throttle 1 and 5,413 ms at throttle 4 while the
main thread grew 4.5 times, so a throttle-4 number is a slower main thread, never a slower device. A reading without
the CPU profiler still records the trace, whose own sampler visits every thread every 0.2 ms. A reading checks itself
before it is used: the page's main-thread tasks in the trace must account for the CPU time the operating system
counted for that thread, and may take longer than their own CPU time only as much as they did on a quiet machine;
otherwise the reading is refused. Those ranges are this machine's. Java Flight Recorder
samples only the server's own process, and the SQL counts are the database's global ones.

## Where and when each stage runs

Each stage is a job in a workflow file under `.github/workflows/`. The file says when the job runs, and the job's
`timeout-minutes` is the stage's budget: the *Budget* column below holds the values (what a budget is for, and what a
stage does when it outgrows one, is ADR 0091 decision 1), and a guard fails any job that has none. The *In
CI* column is read from those files; where a CI run has measured a stage, its *Local run* cell says so. The *Local
run* column was measured on the development machine (a 14-core arm64 Mac) on 2026-10-15 and 2026-10-16, under
whatever load other work put on it, so it says little about a CI runner's time.

`ci.yml` is the pipeline. It runs on every push to `main` or a `release/<major>.x` branch and on every pull request
into one, unless the change touches only docs, Markdown or the site, and once more when a publish is started by hand.
Its stages start together; only lane L1 waits for others: the image build, and the frontend's surface check, the one
install that asks the package registry about the lockfile the images install. *Every commit* below means those
triggers.

| Stage | In CI: workflow › job, and when | Budget | Local run |
|---|---|---|---|
| Guards | `ci.yml › guards`, every commit; and `trust-chain.yml › trust-chain`, every push and pull request to `main`, docs-only ones included — the only place a docs-only change is checked | 3 min | 1.5 min in the pipeline run; 97 s timed in sequence |
| Guard gaps | `trust-chain.yml › guard-gaps`, always in full: when a push or pull request to `main` changes `scripts/`, the trust-chain skill or that workflow, nightly on `main`, and by hand. Locally it skips while nothing it reads changed since its last green full run | 10 min | 2.5–7 min with six workers (147–431 s over six runs, load average 13 to 99) |
| Surface checks, crypto conformance | `ci.yml › backend` (`./gradlew check`), `› frontend` (crypto, web, admin-web), `› chart`; every commit | backend 30 min, frontend and chart 15 each | backend 8.1 min, frontend 5.0, chart 0.4. The backend's first CI run (2026-11-05, an empty Gradle cache, a 4-vCPU runner) outran its first budget of 15: 5.5 min to resolve the dependencies and build the build logic, 2 more to compile, the tests started at minute 7.7, and `:core`'s were still running at 15. Estimated at 18–21 min cold, from the share of the local test time each project had run; a cold cache is every dependency bump's, so the budget is set for it |
| Races on MariaDB | `ci.yml › backend-races`, every commit | 20 min | 9.5 min beside the backend's check, 7.0 alone; once, under a heavier load, over its budget beside it. Locally the jobs share one machine; in CI each has its own runner. The first CI run (2026-11-05, cold) outran its first budget of 10 with three of its four projects green, inside `:core`'s; each of the three took about 17 s plus at most 1.24 times its classes' local time (a local run of 2026-11-02), which puts `:core`'s at 6 min and the job at 13–17 min cold |
| API end-to-end | `ci.yml › web-e2e`, every commit | 30 min | 9.2 min for its 26 suites, run directly (2026-10-15); 13.8 min in the local pipeline, all 27 suites green, run alone while other work kept the machine busy (2026-10-16); on GitHub's first run (2026-11-05) a cold cache added 3.5 min of jar and install before the suites |
| API fuzzing | `ci.yml › api-fuzz`, every commit | 20 min | 5.7 to 7.3 min for a whole run with the image already built, the jar and the dependency install not counted (2026-10-19, with writes, sequences and the second member); on GitHub's first run (2026-11-05) a cold cache added 5.1 min of jar and install before it |
| Image build | `ci.yml › build`, every image (app, admin, tools) on amd64 and arm64, every commit; it writes to a registry only on a publish | 15 min | the app image 2.5–4.6 min uncached on arm64; on GitHub's first run (2026-11-05) 6.4 min on amd64 and 7.1 on arm64 |
| Stack lane L1 | `ci.yml › stack`, per architecture after the image build, in one leg per domain of the suite (account, Depot, Expenses, Memories, moderation, platform), each with an instance of its own: a push on both architectures, a pull request on amd64 only; on a publish it installs the images the build pushed, pulled by digest | 15 min per leg | 2026-10-26, the images built, arm64: the slowest leg 4.0 min (Depot, its browser 3.5 min), the fastest 2.9 min (Memories), 20.9 min for the six together — each runs first run, the round trip and the instance-wide scenarios; the whole suite in one run 14.0 min (13.3, 165 tests); **the local pipeline cannot run this job** (below) |
| Mutation | `mutation.yml` when a push or pull request to `main` changes the crypto package or web's sources, `mutation-backend.yml` when one changes a scoped backend file or its test; both when their own configuration changes, and by hand | 45 min for `mutation.yml`'s one job (both web-side gates), 30 for the backend's | a full run of the crypto package's gate 3.6 min and of web's 16.7 min, under load (2026-11-05); an incremental run on a pull request is estimated at 1–3 min |
| Upgrade proof | `ci.yml › upgrade-proof`, switched off until the first release, one leg per predecessor: the default one, and the release's major entry where it is another | 15 min per leg, an estimate | 3.5 min for one leg, run directly (2026-10-15) |
| Stack lanes L2, L3 | no job | — | — |
| Pentests | no job, by design: run by hand on a developer machine | — | — |
| Profiling | no job: run by hand on one quiet machine, and never a verdict | — | unlock, the Chapter and the encryption reading 8 min at `87266fe3` (2026-10-23); the upload's six runs 12 min at `d4eab6be` (2026-10-24); the upload's and the import's twelve runs, the import's download made first, 8 min at `3a44065b` (2026-10-29), the same twelve again, 8 min each, at `0d48e569` and with the import and the tiles changed (2026-10-29); the video upload's six runs, 5 min each, before, without the tiles' posters and after (2026-10-29), and again before, with the size probe's player let go, without the probe, after, without the tiles' posters and with them drawn but not shown (2026-10-29) |

The jobs that are no stage carry a budget too, so that none runs on GitHub's 360-minute default: reading the version
5 min, the publish 15, a path-filter job 5, CodeQL's analysis 30, the docs site's check 10 and its deploy 5,
the dependency graph's resolve 15 and its submit 5. A budget no
run has measured is an estimate until the first CI run replaces it.

Per commit in CI, the longest path — the surface checks, or the image build and then the lanes — is *estimated* at
20–25 minutes; the first CI run replaces the estimate. Three other workflows check the repository without being a stage:
CodeQL's static analysis (`codeql.yml`), the docs site's build (`site.yml`) and the backend's resolved dependency
graph, submitted on every push to `main` so Dependabot's alerts reach the transitive Gradle dependencies
(`dependency-submission.yml`).

### One command runs the pipeline locally

`python3 scripts/run_pipeline.py` reads `ci.yml` from the commit and runs it: every job this machine can run, each in
its own clone of the commit, independent jobs in parallel, each under its budget. It keeps no list of stages of its
own; the workflow is the list. Where a stage's work sits in a step only GitHub runs, the workflow carries the local
stand-in beside it, such as the image build with the machine's own `docker build`. Per job it prints green, red,
skipped by the workflow's own condition, or *not run* with the reason, and nothing it did not run counts as green: its
verdict is green only when every job ran green. It runs `ci.yml` only; the guard gaps and the mutation gates have
their own commands ([Re-run it](#re-run-it)).

Its one recorded full run (commit `04c9f6d1`, 2026-10-16) was **not green, and nothing in it was red**. Every job it ran
passed, and it named what it did not run:

- `web-e2e`, left out on purpose: it drives the machine's one local compose stack, which other work was using;
- the amd64 legs of the image build, since the machine is arm64;
- the steps only GitHub runs: the check of the Gradle wrapper against Gradle's published checksums, and the image
  build's own action with its provenance and SBOM;
- `upgrade-proof` and `publish`, skipped by their own conditions on a push, as they are in CI.

The `stack` job came after that run. The local pipeline cannot run it on any one machine: it waits for every leg of the
image build, and one machine builds only its own architecture.

`api-fuzz` came after that run too. Run through it alone (`--job api-fuzz`, 2026-10-19), it was green but for the
one step only GitHub runs, the check of the Gradle wrapper: 4.2 minutes with the jar build and the dependency install — before its
writes, sequences and second member were added the same day, which made the run itself 3 to 4.5 minutes longer.

## How a release will be gated

A publish is started by hand from `main` or a `release/<major>.x` branch and runs the whole pipeline once more. The
image build pushes each image to the registry under a staging tag, unsigned and carrying no release name; lane L1
installs exactly those images, pulled by digest; and the `publish` job runs only when every other job of that run is
green (the switched-off upgrade proof excepted). It then names those same digests — a snapshot `<major>.<minor>-SNAPSHOT`
by default, a release `<major>.<minor>` when asked — signs them and the Helm chart keylessly, and for a release creates
the tag `v<major>.<minor>` and the immutable GitHub Release with its files. All of that is a workflow file. It has
never run: there is no remote, and a registry write and keyless signing cannot run on a developer machine at all.

The rest is designed, not built (ADR 0091): every green push publishes its images as a signed snapshot on exactly the
bytes that were tested, with each stack lane's result attached as a signed attestation, and a release does not build
again — it gives that snapshot's bytes the release name and signature, after checking the attestations. So a release
is always bytes every test saw, and anyone can check which release they run against its signature
([Verify a release](/run/verify-a-release/)).

## What no test here can prove

- **That a test's assertion is the right one.** A check can show a test exists, runs and fails when the code changes;
  it cannot show the test asks the right question. Mutation testing narrows this for the scoped files and nowhere else.
- **That a test ran.** Whether a test executed is a fact about a run, not about a file. That is why nothing on this
  page claims CI.
- **A rule nobody wrote.** When two features interact in a way no document describes, the docs and the code agree and
  the defect is still real.
- **History.** The checks read the current tree; a defect that only shows across versions is caught by the upgrade and
  backup stages or not at all.
- **Anything against an actively malicious operator.** These checks run on the server's side of the line. The
  operator is the server, so no server-side check closes a row of
  [What holds today](https://github.com/raddatzk/veyq/blob/main/docs/crosscutting/security-model.md#what-holds-today-on-a-client-the-operator-does-not-serve);
  see [Start here](/security/start-here/).
- **The absence of defects.** Tests show that defects they look for are absent. There has been no external audit
  ([Known limitations](/security/known-limitations/)).

## Re-run it

Every built stage runs from a checkout with the repository's own commands. The pipeline as a whole runs with
`python3 scripts/run_pipeline.py` (`--dry-run` prints what it would run and what not; `--skip-job web-e2e` leaves out
the job that needs the local compose stack and reports it as not run). Stage by stage: the guards with
`for f in scripts/validate_*.py; do python3 "$f"; done`, the guard gaps with
`python3 scripts/validate_guard_gaps.py --force`, each part's tests with `./gradlew check` in `src/backend/` and
`pnpm run check` in `src/web/`, `src/admin-web/` and `src/packages/crypto/`, the end-to-end suites with
`python3 scripts/run_web_e2e.py` (Docker required), and lane L1 with
`python3 scripts/run_stack_e2e.py --app <app image> --admin <admin image>` (Docker, the repository's `pnpm install`, and
ports 80 and 443 free), and the profile with
`python3 scripts/profile_hot_paths.py --app <app image> --admin <admin image>` (the same, ffmpeg on the path for the
synthetic photos, and a machine whose one-minute load is at most 3). How to reproduce the security-specific checks, and what each pins,
is on [Verify it yourself](/security/verify-it-yourself/).
