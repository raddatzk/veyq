# ADR 0085 — One major for the platform, moved on a yearly train; a client speaks every major since the train before last

- Status: Accepted
- Built: partly (not built: decision 5's client window, lane L3's archive holding decision 10's major entry)
- Date: 2026-10-15
- Model: [release-and-deployment](../crosscutting/release-and-deployment.md)

## Context

[ADR 0032](0032-capability-discovery-version-gating.md) gates a client on `version.major`, compared for
**equality** with the major the client was compiled against. That holds for the two clients built today: the web
client is served by the backend it talks to ([ADR 0069](0069-backend-serves-the-web-app-shell.md)), and
`admin-web` ships in the same release, so neither can drift from its server.

Native clients change that. The direction the owner set (native work itself stays parked until the web V1 stands) is one installable app per application, each able to talk to **several instances** at once. A store
ships the app, and every operator upgrades on their own schedule. Under equality two things break: an app update
locks its user out of every instance that has not upgraded yet, and one operator who upgrades late blocks users
who also use other instances. The cost of drift lands on the people who cannot fix it.

Two majors exist today and answer the same question, *"did something an older side relies on go away?"*:
`VERSION` holds the release major ([ADR 0057](0057-release-identity-and-deployment-recipe.md) §1), and
`veyq.version.major` the protocol major. Measured
(`cat VERSION; grep -n 'veyq.version.major' backend/core/src/main/resources/application.properties`): `0` and `1`.

Sealed payloads add a third axis. The server cannot migrate ciphertext, so every format ever written stays
readable ([ADR 0079](0079-veyq-is-a-platform-memories-is-its-first-application.md) decision 7). Writing a new
format, though, is a question between **clients**: a member whose app writes a format another member's app cannot
read has broken a shared object without any server noticing.

## Decision

**1. One major.** `VERSION` holds the platform's only major: it is the release major of ADR 0057 and the protocol
major of ADR 0032 at once, and `veyq.version.major` is derived from it rather than set by hand. Only the major
is bundled. The pre-auth `/api/setup` still advertises the major and nothing more precise. The release
`<major>.<minor>` stays Server-Owner information, because a precise release number tells any visitor which
CVEs an instance still carries (ADR 0057 §4, unchanged). `build` stays what ADR 0057 §4 makes it, a hand-kept
diagnostic that gates nothing.

**2. The major moves only when something is removed.** Everything additive is a minor. Once a removal is decided,
what will go is marked `deprecated: true` in its contract, so the generated clients flag every use of it, and it
leaves with the next train. There is no minimum deprecation period. An operator has to upgrade either way, and
the clients are not who a period would protect, because the window of decision 5 keeps the old major's adapter in
every client for two more trains. What has to come first is the release order: the clients that speak the new
major are published **before** the server major, so on 1 May the apps in the stores already speak it. A change
an operator must act on (a removed config key or variable) is named in that release's notes.

**3. The train leaves on 1 May.** Once a year, on 1 May, a new major is released **if** a removal is pending.
A year with nothing to remove has no major. Operators can plan for one date instead of watching for one.

**4. A security problem is fixed inside the major first.** A minor fix is the default whenever the API shape and
the write formats existing clients rely on stay intact, which holds for most server-side fixes. Only when that is
impossible is an **unscheduled major** released. It removes the insecure piece and nothing else, so other pending
removals wait for the train.

**5. A client speaks every major released since the train before last.** Planned, that is the current major and
the two before it (N, N-1, N-2). An unscheduled major does not move the window, it widens it: with one emergency
inside the window the client speaks four majors, without the rule changing. So an operator keeps the planned
two years after their major stops being the current one. Per major the client carries an **adapter**: the client
generated from that major's contracts, plus the write formats of decision 7. Which majors a client speaks is fixed
when it is built; the rule only says when a train lets it drop the oldest adapter.

**6. The window is the client's, never the server's.** A server speaks its own major only. A client older than the
server's major shows "update this app" before login, as ADR 0032 does today, because updating an app is cheap for
its user and updating a server is not cheap for its operator. The web client and `admin-web` come from the
server's own image and always match it exactly.

**7. A client does not police operators.**
- **A flaw in the server:** the client keeps talking to it. Closing it is the operator's job, and self-hosting
  means the operator decides when.
- **A flaw in what the client does** (a broken crypto suite, a protocol shape that hands the server something it
  must not see): the fix lands in the client, in **every** adapter it carries, so the client stays connected to
  every server in its window, only safely. This is not the operator's domain: the client trust tiers assume the
  operator may be actively malicious ([ADR 0026](0026-client-trust-tiers.md)), and here the operator is who the
  client protects its user from.
- **Only if an old major cannot be spoken safely at all** (it accepts nothing but the broken suite) does that
  adapter refuse it, with "this server needs an update before you can use it safely". That is the client declining
  to break its own user's confidentiality, not a judgement about the instance.

**8. A payload's write format follows the server's major.** Every format stays readable forever (ADR 0079
decision 7). A client writes the format that belongs to the major of the server it writes to. A compatible format
change (a field old readers can ignore; readers ignore unknown fields) can land in a minor. An incompatible one
lands only with a major. Because a client accepts a major only if it was built knowing it (decision 5), every
client that can reach an instance reads the format that instance's clients write: decision 6's gate rules out the
member who could not open an object, and no separate read-ahead rule is needed. Crypto suites follow the same
rule.

**9. Not a version in this sense:** a node's concurrency counter ([ADR 0077](0077-a-write-carries-the-version-it-read.md)),
a scope's tree revision ([ADR 0081](0081-a-scopes-tree-revision-is-the-level-views-change-signal.md)) and the
database migrations. They answer other questions and stay independent of the major
([ADR 0086](0086-one-word-per-meaning.md) names them revisions).

**10. The previous major keeps a release branch until the next train** (owner 2026-10-30). A security fix decision 4
keeps inside a major reaches the operators who have not left the previous major yet, without an upgrade:
- **When:** the old major's last commit on `main` is released before `VERSION` is bumped, so that release is the
  bump's parent. `release/<old>.x` is cut from its tag `v<old>.<n>`. The branch counts its own history since `VERSION`
  last changed (ADR 0057 § 1), so its first fix is `<old>.<n+1>`, a number `main` never reached in that major, and no
  unreleased work of `main` rides along with a fix. `ci.yml` already publishes from `release/<major>.x`, signed, with
  `<major>` and without `latest`.
- **What:** only a fix decision 4 allows inside a major — never a feature, never a removal. It lands on `main` first
  and is cherry-picked onto the branch (`git cherry-pick -x`). **It carries no migration.** An instance on the fix
  release would hold a migration the new major's schema history does not have at that place, and its upgrade would
  meet a history no tested path covers. A fix that needs one is made in the current major only, and the old major's
  operators upgrade to get it.
- **How long:** until the next train. A train ends every branch cut before it and cuts the new one, so one branch
  is maintained beside `main`. An unscheduled major (decision 4) cuts a branch too, kept until the same train, so a
  year with one has two. The client window (decision 5) is longer and stays so: an app keeps talking to an N-2
  server, but that server gets no more fixes.
- **The release order:** the branch release first, then a release of the current major. Each fix release moves the
  release the new major is entered from (ADR 0096 decision 5; ADR 0098 decision 3: the gate admits a major entered
  only from the release the target was tested from). The current major's next release is proved from the fix release
  and bakes it as its major entry. Until it exists, an instance on the fix release cannot enter the new major, but it
  runs the fixed release while it waits.

## Consequences

- **Built: one major (decision 1).** buildSrc's `GeneratePlatformMajor` compiles `VERSION` into `:core`'s
  `PlatformMajor.MAJOR`, which `GET /api/setup` advertises; there is no `veyq.version.major` setting any more, so no
  instance can be configured to advertise a major its code does not speak, and neither runtime target needs a value.
  The web client's `EXPECTED_MAJOR` is read from the same file at build time (`src/web/vite.config.ts`); its gate still
  compares for equality (decision 6). `CapabilityManifestSliceTest` and `src/web/src/shared/state/capabilities.test.ts`
  each read `VERSION` from disk and hold the served and the compiled major to it. The `setup.openapi.yaml`
  `Version.major` text names the train and the window.
- **Written into the release procedure (decisions 2, 3 and 10):** the 1 May train, its release order and the release
  branch, in [`infra/README.md`](../../infra/README.md) § Cutting a release. The manifest's deprecation lifecycle stays
  deferred (ADR 0032 § Consequences) and is not needed for this.
- **Built: a fix release moves the major entry (decision 10).** Every release of major N is proved from its MAJOR
  ENTRY — the newest release tag of major N−1 among all the repository's tags, `main`'s and every `release/<old>.x`
  branch's, by version order — beside its default predecessor (the newest tag its first parent reaches, which never
  sees a branch's tag), and its tools image bakes that entry for the gate (ADR 0098 decision 3). One derivation,
  `run_web_e2e.major_entry_ref()`: `ci.yml`'s `upgrade-proof` runs `run_web_e2e.py --upgrade-major-entry`, its `chart`
  job `chart_upgrade_check.py --upgrade-major-entry`, and `release_assets.py --major-entry` stamps the tools image —
  so the gate never admits an entry the pipeline did not prove. No tag of major N−1 (major 0, or while the pin file
  says `release: none`): no entry and no second run; an entry equal to the default predecessor — a major's first
  release, cut before any fix release — is proved once. `upgrade_proof_selftest.py` holds the derivation on a
  fabricated history with a `release/1.x` branch. Like the whole upgrade proof, the second run is disabled until the
  first release, and no CI run has executed either.
- **Not built.** Each is open work tracked in the inbox (#436):
  - **lane L3's archive holding the major entry** (ADR 0096): the previous major's last release archived, so that a
    backup of it restores into the new major. L3 has no lane yet (ADR 0091 decision 6). Needed before the first branch
    release, so not before the first train;
  - no CI job runs a client against older server majors. None can be needed before a native client exists.
- **Major 0 is pre-release** (ADR 0057). The window counts released majors only, so no client carries a `0`
  adapter, and at V1 `VERSION` and the advertised major both become `1`. Until then the advertised major is `0`,
  not floored to `1`: a floor would be a second rule, the two counters this decision removes.
- **An operator-breaking change is major-level too.** A deployment change that needs the operator to act (a new
  required variable, a removed service) waits for the train like a protocol removal, or forces an unscheduled
  major. When the protocol is unchanged, that major's adapter is an alias of the previous one and costs nothing.
- **What it costs:** one adapter and one older server image in the client's test matrix per major inside the
  window. Every major is a signed image (ADR 0057), so the older images exist. The job does not exist yet.
- **One platform major, not one per application.** With a two-year window a removal in one application costs the
  others an alias, not a lockout. A major per application would multiply the window logic for every application and gain
  nothing a user can notice.

## Alternatives considered

- **Equality, as ADR 0032 decided.** Kept for the web client and `admin-web`, rejected for store-shipped clients:
  it makes users pay for operators' upgrade schedules (Context).
- **A window counted in majors (always N-2).** Rejected: an unscheduled major would cut an operator's planned time
  short as a side effect of a security fix elsewhere. Counting trains keeps 1 May the only date an operator has to
  plan for.
- **The server speaking older clients.** Rejected: it puts the compatibility cost on the side whose update is
  expensive (decision 6).
- **The whole release version on the pre-auth surface.** Rejected for ADR 0057 §4's reason: it publishes every
  instance's patch level, and with it the CVEs it still carries.
- **The two majors kept apart.** Rejected: both count the same event, a removal, and two counters for one event
  drift. They already differ (`0` and `1`).
- **A client denylist of servers with a known flaw.** Rejected: it decides for operators what is theirs to decide
  (decision 7). Where the flaw is the client's own, the fix is in the client, not a list.
- **A minimum deprecation period (six months, with a cutoff date for the next train).** Rejected: it buys an
  operator nothing, since they upgrade either way, and the clients are already covered by the window. The only
  ordering that matters is that clients ship before the server (decision 2).
- **No release branch: an old major is fixed only by upgrading.** Rejected (owner 2026-10-30): a security fix
  would then cost an operator a major upgrade, with its removals, on the day the fix is published.
- **A release branch for every major in the client window (N-1 and N-2).** Rejected (owner 2026-10-30): two
  branches beside `main`, each with every lane on both architectures and every backport, for operators who have had a
  year since their major stopped being current. The client window stays longer because updating an app costs its user
  nothing, and that does not hold for an operator's server.
- **A year as the major (calendar versioning).** Rejected: a year with no removal has no major, and the window
  counts trains, not years.
- **Bundling the payload format into the major.** Rejected: formats are per node kind, owned by the application,
  and readable forever. Only *writing* an incompatible format is tied to a major (decision 8).

For Server Owners, on the docs site: [Upgrade](../../site/src/content/docs/run/upgrade.mdx) (§ Versions and compatibility).
