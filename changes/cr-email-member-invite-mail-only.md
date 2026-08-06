---
id: cr-email-member-invite-mail-only
title: The e2e harness reads the invite out of a mailbox, and the refusal reaches a screen
status: done
satisfies: [uc.account.recover-access, uc.spaces.add-member, uc.spaces.manage-member-invites, uc.admin.tenant-mail-settings]
adrs: ["0055", "0054", "0048", "0040", "0053"]
invariants: [operator-blind-e2ee, self-hostable-by-default]
---

## Goal

[ADR 0055](../docs/decisions/0055-mail-is-a-precondition-not-a-capability.md) is built at the wire and in the
server (`cr-email-refuse-without-mailer`); this CR is everything that decision left standing on the client and
test side. Three things, and they are one change: the live e2e harness has to obtain a member invite the way a
member does — out of a mailbox — because the response no longer carries it; `/recover` has to *show* the `503`
it now receives instead of dropping it on the floor; and the published wiki has to stop telling users the
opposite of what the code does. It closes inbox #118 and the display half of #113.

This CR has **no contracts phase and no backend phase** — both landed with the predecessor. `npm run
generate:api` is therefore not required, and a bare `npm run check` is honest here for once.

## Why now, and what it actually repairs

The harness half is not a convenience. **Ten coverage refs currently point at two suites that cannot pass:**

- `scopeRotation.e2e.test.ts` (4 refs) fails in setup — `onboardMember` throws by construction
  (`e2eBootstrap.ts:127`), naming this CR.
- `recovery.e2e.test.ts` (6 refs) needs Mailpit, and the orchestrator does not start it:
  `COMPOSE = [… "--profile", "s3"]` is a module constant (`run_web_e2e.py:79`)
  while `mailpit` sits behind `profiles: ["mail"]` (`docker-compose.yml:100`).

Both refs sets were added on the strength of hand runs. The last CI run of any kind on this branch was
**2026-07-30** (`gh run list`, `workflow_dispatch`); `recovery.e2e.test.ts` landed **2026-08-05** (`42a708b4`).
So no CI run has ever executed either suite. That is precisely the state
[`docs/ai/README.md` § What counts as verified](../docs/ai/README.md) describes and ADR 0040 forbids — *a test
that no CI run executes makes no coverage claim*. See § Open risk: this CR repairs the **cause** and, by the
user's explicit call, does **not** carry the confirming CI run.

## What already exists (measured 2026-08-06 on `starting-something-new`, clean tree — do not rebuild)

| Piece | Where | State |
|---|---|---|
| Mailpit container, MIT, in-memory | `infra/docker-compose.yml:97-108`, profile `mail`, SMTP `11025`, API `18025` | complete; `MP_SMTP_AUTH_ACCEPT_ANY=1`, so an auth-less tenant config is enough |
| A readiness signal for Mailpit | the **image** declares `HEALTHCHECK CMD /mailpit readyz`, interval 15 s (`docker inspect veyq-mailpit --format '{{json .Config.Healthcheck}}'`) | complete — nothing to build; see Decision 3 |
| Mail polling against Mailpit's HTTP API | `recovery.e2e.test.ts:36-51` (`waitForMail`) | works, but **file-local** — nothing shared exists to extend |
| Pointing a tenant at Mailpit | `recovery.e2e.test.ts:58-65` via `updateTenantMailSettings` | works; `username: null` deliberately dodges the `mail_secret_unavailable` KEK path |
| The accept link the mail carries | `https://$domain/tenant/accept#$token` — `SmtpTenantInviteMailer.kt:50` | complete; token in the **fragment**, `https` and no port |
| The member-side accept | `registerAndAccept` — `tenants.ts:111` | complete; `onboardMember` used it before ADR 0055 and only lost its *source of the token* |
| `HttpStatus.serviceUnavailable` | `web/src/shared/httpError.ts:29` | complete, added by the predecessor |
| The `503` display idiom to copy | `TenantMailSettingsPanel.vue:121-134` (the `mail_secret_unavailable` precedent) | complete |
| Raised e2e rate budgets | `application-e2e.properties:57-60` (invite per-recipient + per-tenant at 10⁶) | complete — repeated invites in one run will not trip |
| Both member dialogs' `mail_unavailable` / `mail_send_failed` branches | `InviteMemberDialog.vue:44-56`, `TenantMembersPanel.vue:140-146` | **complete — not in this CR's scope** |

Absent: any shared mail helper, Mailpit in the orchestrator, any handling of a failed recovery *request*, and
any wiki sentence that matches ADR 0055.

## Locators — run these, do not trust the table

```bash
# L1 — the harness work list. Angle A (the symbols) vs angle B (the infrastructure address, a different key).
grep -rn "onboardMember\|VEYQ_MAILPIT\|waitForMail\|updateTenantMailSettings" web/src --include="*.ts" \
  | grep -v "^web/src/api/generated"                                                    # angle A
grep -rn "11025\|18025\|mailpit" . --include="*.ts" --include="*.py" --include="*.yml" --include="*.mts" \
  | grep -v node_modules                                                                # angle B
```

**The disagreement is the finding, twice.** Angle A alone reads as "three call sites in one suite"; angle B adds
`infra/docker-compose.yml` and shows the two port numbers are **hardcoded in two trees that must agree**. It also
exposes a live divergence angle A cannot see: the compose comment (`:93`, `:107`) documents the tenant host as
`localhost`, while `recovery.e2e.test.ts:59` configures `HOST` (`veyq.localhost`). Both are loopback so both
work — which is exactly why nobody noticed, and why Decision 1 below dissolves it into one site rather than
correcting the comment.

Angle B is also incomplete on its own: it is keyed on *numbers*, so it misses the prose. A third angle keyed on
the **profile** finds `infra/README.md:9` (`mail` called "opt-in") and `:62-63` (*"with none configured the
invite falls back to the copy-link"* — false for the member path since ADR 0055 §2):

```bash
# L2 — the prose that goes false, in-repo and published
grep -rn "profile\|copy-link\|copylink\|optional" infra/README.md
grep -rniE "mail|smtp|recover|wiederherstell|passwor|invit|einlad|member|mitglied|optional" wiki/
```

Note the measured negative, so nobody re-greps it: `grep -rniE "invit|einlad|member|mitglied" wiki/` returns
**nothing**. The published wiki has never mentioned invites at all, so the invite page in Phase 4 is net-new
prose, not a correction.

```bash
# L3 — every guard that anchors on what this reshapes, before choosing the shape
grep -rln "run_web_e2e\|e2eBootstrap\|e2e.test\|recordCanaries" scripts/
```

Returns `run_web_e2e.py`, `validate_e2e_discovery.py`, `validate_e2e_canaries.py`. Two constraints fall out and
both are shape constraints, not afterthoughts: `validate_e2e_discovery.py` asserts **textually** that
`run_web_e2e.py` still matches `else\s+discover\(\)` and that every `except NoE2eFilesError:` block contains
`SystemExit` — so the orchestrator edit must not restructure those two spots. `validate_e2e_canaries.py`
requires every file `discover()` returns to both import **and** call `recordCanaries` — so if Phase 1 adds any
new `*.e2e.test.ts`, it must record canaries or the buildless trust chain goes red.

## Decisions

**1. `birthInstance` points every birthed tenant at Mailpit** (chosen with the user over an opt-in
`useMailpit(tenantId)` helper). ADR 0055 §1 makes a mailer a base function, so a tenant without one is
*misconfigured*, not a valid variant — and a bootstrap helper whose job is "a usable instance" should not
produce one. It also gives the Mailpit host exactly one site, dissolving the `localhost` vs `veyq.localhost`
divergence L1 found. **The cost, stated because it is real:** all six suites now depend on Mailpit being up,
not two. A dead Mailpit becomes a whole-run failure rather than a two-suite failure — which is the honest
signal, since the harness now requires it either way.

**2. Mailpit is unconditional in the orchestrator, not a flag.** Add `mail` to the `COMPOSE` constant. Repeated
`--profile` is supported, run rather than assumed:
`docker compose -f infra/docker-compose.yml --profile s3 --profile mail config --services` lists all five
services including `mailpit`. `run_web_e2e.py` has **no argparse at all** — every argv entry
is a test path — and adding one to gate something that must always be on is ceremony. `run_web_e2e.py` also
**exports `VEYQ_MAILPIT` explicitly** alongside `VEYQ_BASE`/`VEYQ_HOST` rather than leaving the suite on its
`?? 'http://localhost:18025'` default: the substrate a test reads should be named by the thing that starts it.

**3. Wait for Mailpit with `up -d --wait mailpit` — there is nothing to build.** The first cut of this decision
said Mailpit declares no healthcheck and specified a hand-rolled `GET /api/v1/messages` poll. **That was wrong,
and measured wrong:** the compose file declares none, but the *image* does —
`docker inspect veyq-mailpit --format '{{json .Config.Healthcheck}}'` returns
`{"Test":["CMD-SHELL","/mailpit readyz"],"Interval":15000000000}`. So the existing targeted-wait idiom already
covers it, in exactly the shape `compose_up` uses for mariadb. Note the 15 s interval with no `start_period`:
the first probe lands ~15 s in, so a cold `--wait` genuinely blocks that long. That is the correct trade against
a first-suite flake, and it is the reason not to substitute a snappier poll of our own.

**4. The shared helper is `web/src/test/mailpit.ts`, it returns the NEWEST matching mail, and it never clears
the whole mailbox.** Extract `waitForMail` out of `recovery.e2e.test.ts` (which then imports it) and add a
`clearMailbox(to)`. Two things were measured against the running container rather than assumed:

- **Ordering.** `GET /api/v1/messages` on the pinned `v1.20.0` **does** return newest-first, and every message
  carries `Created` with millisecond precision (two probe mails 1.2 s apart came back at positions 0 and 1,
  newest first). The helper still **sorts by `Created` descending itself** — one line, and it makes the helper
  correct without anyone re-checking this on an image bump. The ordering matters because Mailpit is one shared
  mailbox for the whole run and a **re-issue sends a second mail to the same address**: a first-match helper
  hands back the superseded token, and the suite then fails far downstream or, worse, passes.
- **`clearMailbox` is recipient-scoped, never global.** `DELETE /api/v1/messages` with `{"IDs":[…]}` deletes
  exactly the listed messages (verified: `200`, and the eight unrelated messages in the mailbox survived). A
  global clear would be destructive on a developer's machine, which is not hypothetical — the local `veyq-mailpit`
  is `restart: unless-stopped` and was found up **18 hours** holding **8** unrelated messages. E2E recipients are
  stamped per run (`VEYQ_STAMP`), so scoping the delete to one recipient is both safe and sufficient.

**5. `onboardMember` returns to its pre-ADR-0055 body with the token read from the mail.** Its only lost piece
was the token's *source*; `registerAndAccept` still exists and its import merely needs restoring. The regex
anchors on the **fragment**, not on the base URL: the mail's link is `https://$domain/tenant/accept#$token`
(`https`, no port), while the suite talks to `http://veyq.localhost:8080` — a regex written against the suite's
own base URL will match nothing. Delete the throw and the "currently unusable" paragraph of the doc comment;
keep the paragraph explaining why the dev-invite file is **not** an alternative, which is still true.

**6. `RecoverView.askForLink` gets a hand-rolled `catch`, not `useAsyncAction`.** Today it is
`try/finally` with no `catch` (`RecoverView.vue:81`), so the `503` becomes
an unhandled rejection: `sent` stays false, `error` stays empty, the form silently reappears. The file's other
three handlers already catch by hand (`:69`, `:106`, `:125`); switching this one to `useAsyncAction` would be
churn *and* walks into that composable's `shallowRef` requirement (a deep-reactive proxy breaks `Response.clone()`
inside `decodeHttpError` — see the comment at `useAsyncAction.ts:22`). **The catch must also handle the generic
case**: fixing only `mail_unavailable` would leave every *other* failure rendering the same blank screen, which
is the actual defect. Two new i18n keys, both locales.

**7. The wiki gets a new EN+DE page** (chosen with the user over corrections-only), because ADR 0055's most
user-visible consequence — *your invite is emailed to the person, there is no link for you to pass on* — has no
published page to land on. Under the single-source rule it is **derived from** the bilingual sections of
`uc.spaces.add-member.md` and `uc.spaces.manage-member-invites.md`, which the predecessor already corrected;
this CR writes no new product claim into the wiki that its use-case does not already carry. Phase 3 exists
because one such source sentence is **missing** and must be written first.

## Phases

No contracts, no backend. Each phase ends with the repo verifiable.

### Phase 1 — the harness

`scripts/run_web_e2e.py`: Decisions 2 + 3. `web/src/test/mailpit.ts`: Decision 4. `web/src/test/e2eBootstrap.ts`:
Decisions 1 + 5. `recovery.e2e.test.ts`: drop its local `waitForMail` and its local mail-settings wiring, import
both instead. Leave `infra/docker-compose.yml` alone — the profile is right; only who asks for it changes.

**Done when:** both mail-dependent suites pass locally, run through the orchestrator rather than by hand
(`python3 scripts/run_web_e2e.py src/features/tasks/scopeRotation.e2e.test.ts` and
`… src/features/account/recovery/recovery.e2e.test.ts`), then the full no-arg run is green, **and** its log is
read to confirm six suites actually executed — a run that ran nothing and a run that passed everything render
identically. `for f in scripts/validate_*.py; do python3 "$f"; done` green.

**Bite it before trusting it — and `docker stop veyq-mailpit` is NOT the lever.** `main()` calls `compose_up()`
unconditionally, before the first suite and on every invocation (`run_web_e2e.py:461`), and `compose_up`'s
`docker compose up -d` **restarts a stopped container**. So a pre-run stop is repaired by the tool itself and
the injection silently becomes a green run — the exact failure `changes/README.md` § *check the lever survives
the tool* names, arrived at here by the same route.

Drive the **configuration** instead: snapshot-and-restore the Mailpit URL constant in `run_web_e2e.py`, point it
at a dead port, and require the run to fail **at the wait**, naming the mailbox — not with an undefined token
three calls downstream. An env override will not do it, because Decision 2 makes `run_web_e2e.py` the thing that
*sets* `VEYQ_MAILPIT`. Restore with `command cp -f`, and confirm with `git diff --quiet -- scripts/run_web_e2e.py`.

**What this bite does not prove, stated rather than implied:** it shows the wait fails legibly when the mailbox
is unreachable. It does **not** prove the wait prevents the cold-start race it exists for — that is a timing
window, and forging it reliably is not worth the machinery. The 15 s health interval (Decision 3) is the reason
to believe the race is real; the bite only pins the error message.

### Phase 2 — the refusal reaches a screen

`RecoverView.askForLink` per Decision 6; `recovery.mailUnavailable` + a generic `recovery.requestFailed` in
**both** the `en` and `de` object literals of `web/src/i18n/index.ts`; a `RecoverView.test.ts` case per branch.
There is currently **no test at all** on a failing recovery request — the happy path is the only one pinned.

**Done when:** `npm run check` green in `web/`. (No `generate:api`: no contract changed. Stated because the
web check would otherwise be run against a stale client for the wrong reason.)

### Phase 3 — the internal prose the wiki will be derived from, which is not yet true

**a. The missing source sentence.** `uc.account.recover-access.md`'s bilingual *"What this means for you / Was
das für dich bedeutet"* section is **silent on the mailer-less case** — the predecessor corrected the section's
neighbours but not this one. The wiki page that currently answers the question answers it *wrongly*, so
correcting the wiki from an absent source would invent a product claim in the published tree. One sentence,
EN+DE: the reset link is always offered, and on an instance that cannot send mail the request fails with a
clear message instead of pretending.

**b. `uc.spaces.manage-member-invites.md` — three false claims, not the one line first scoped.** The earlier
read of this file found only `:60` (a re-issue reports `SENT / FALLBACK_COPYLINK / FAILED`, where ADR 0055 §7
leaves only `SENT` reachable) and routed it to the inbox as a stale line. Reading the whole section instead of
the grep hit shows two more, and they are the worse two because they sit in the **security-property** bullets:

- `:46` — *"a re-issue returns the new secret only to the owner-gated caller over TLS"*
- `:49` — *"the redeemable bootstrap token reaches the invitee via the re-issue API response or the opt-in
  dev-file"*

Both describe the response carrying `bootstrapSecret`, which the predecessor removed from the contract. As
written, the *Operator-blind* bullet now asserts that the server hands a named person's admission secret back
to the owner — precisely the routing ADR 0055 §2 abolished, stated as a property of the system. That is not a
stale line to file; it is a live doc claiming the opposite of the invariant, so it is fixed here.

**c. `infra/README.md`.** More sites than the third locator angle first surfaced — read the whole `## Mail`
section, not the grep hits:
- `:3` + `:9` — "Three profiles" / `mail` **(opt-in)**: false once Phase 1 makes the harness require it.
- `:62-63` — *"with none configured the invite falls back to the copy-link"*: false for the member path.
- `:79` — a lost config-secret key leaves the server *"on the copy-link until the key is restored"*: false the
  same way, and this one is about a **failure** state, where being wrong costs the most.
- `:80-81` — *"this profile is for a manual, eyes-on smoke"*: false once `run_web_e2e.py` requires it. (The
  neighbouring claim that `./gradlew check` needs no container stays true — backend coverage is still GreenMail.)

**Done when:** `python3 scripts/validate_feature_format.py` + `validate_doc_links.py` green, and
`grep -rn "copy-link\|copylink\|FALLBACK_COPYLINK" docs/features/spaces/ infra/README.md` returns only
ServerOwner-bootstrap contexts.

### Phase 4 — the published wiki

Derived from the use-case sections, never invented here.

- **New page, EN+DE**, translated slugs per the existing convention (`en/…` ↔ `de/…`, not a `-de` suffix): who
  gets into your space, and that an invitation is mailed to the person rather than handed to you.
- **`Home.md`** — both language bullets on `:11` and `:12`, edited in lockstep. Nothing checks this.
- **Corrections** (the list L2 produces; verify each still reads as described before editing):
  `en/account-keys-and-recovery.md:77-78` and `de/konto-schluessel-und-wiederherstellung.md:80-82` both claim the
  reset route *"isn't available there at all"* / *"steht gar nicht zur Verfügung"* on a mailer-less server —
  ADR 0055 §4 says the opposite, and Phase 2 is what makes the opposite true.
  `en/running-your-instance.md:12-13` ("optional throwaway mailbox"), `:27-32` (the closed *"Nothing else"*
  list of what an operator owns, which now omits a working mailer — the most important addition on the page),
  `:50-51` ("the test mailbox").
- **Additions**: `en/is-my-data-safe.md:90-92` + `de/sind-meine-daten-sicher.md:99-101` describe only the
  Emergency-Kit leg of a reset and never mention the mailed link.
- **Do not touch** `en/account-keys-and-recovery.md:65` / `de/…:67` (*"A link we mail you"*) — ADR 0055
  § Consequences explicitly protects it — nor any `link`/`Link` passage about **public shares**, which
  ADR 0054 §4 keeps by design.
- `running-your-instance.md` is **EN-only by design** (`Home.md:16-18`). Do not invent a German operator page.

**Done when:** read through, both languages, against ADR 0055. **Stated limit, not an omission:** no guard
covers `wiki/` at all — `validate_doc_links.py`'s `DOC_ROOTS` is `docs/` + `changes/` only, and `wiki-sync.yml`
rsyncs rather than builds. Inbox #120 is the open item for exactly this gap; this CR does **not** close it.

### Phase 5 — coverage

- `uc.account.recover-access`: `no-mail-is-refused-not-swallowed` loses its `gaps.web` (`:58-59`) and gains the
  `RecoverView.test.ts` ref; the trailing note at `:73-74` naming this CR goes.
- `uc.spaces.add-member`: `invite-travels-by-mail-only` gains the e2e ref that Phase 1 makes true.
- `uc.spaces.manage-member-invites`, `uc.admin.tenant-mail-settings`: check whether Phase 1 turns any existing
  gap into a ref; leave what it does not.

**Done when:** `python3 scripts/validate_coverage.py` green.

### Phase 6 — harvest the predecessor, and close the inbox

`changes/cr-email-refuse-without-mailer.md` is `status: done` and still in the tree, against *deleted when
done*. Harvest before deleting: verify each lesson already binds where it bites — the JUnit trap (a `fun x() =
… .use { }` expression body is silently skipped) is claimed to be written at the line in `TestMailbox`; confirm
it is, and move anything that is not. Then delete the file. Update inbox #118 and #113 to name this CR as the
closer. (No inbox row for `uc.spaces.manage-member-invites.md` any more — re-reading it moved all three of its
false claims into Phase 3b, where they belong.)

## Definition of done

- `npm run check` green in `web/`.
- `python3 scripts/run_web_e2e.py` (no args) green, **and its log read** to confirm six suites executed.
- `for f in scripts/validate_*.py; do python3 "$f"; done` green over the **committed** tree.
- The Mailpit bite test of Phase 1 watched failing first, by snapshot-and-restore — never `git checkout`.
- No live e2e path reads an invite token off an HTTP response:
  `grep -rn "bootstrapSecret" web/src/test/ web/src/features/tasks/` returns only the ServerOwner bootstrap.
- `changes/cr-email-refuse-without-mailer.md` deleted, its lessons verified in place.

**No permanent guard is promoted here, and that is a choice with a reason.** The two structural invariants this
CR could rest on are already guarded by files that exist: `validate_e2e_canaries.py` (every suite contributes
canaries) and `validate_e2e_discovery.py` (the orchestrator cannot run zero files). The one invariant genuinely
left unguarded — *a published wiki page agrees with its source use-case* — is not decidable by any guard
(inbox #120 states that limit up front) and its buildable half is its own work item, not a side effect of this
one.

## Open risk — accepted knowingly

**The ten coverage refs stay unbacked by CI.** Nothing in this CR's done-when is a CI run: `ci.yml` triggers
only on `main` and on PRs targeting it, so this branch runs nothing without
`gh workflow run ci.yml --ref starting-something-new`. That dispatch is available — `ci.yml` **is** on the
default branch (`git ls-tree origin/main --name-only .github/workflows/`), which is what `workflow_dispatch`
resolves against, and the 2026-07-30 run on this branch was one. It was offered and **declined**, so the
harness is proven by a local orchestrator run only.

Under ADR 0040 those are not the same claim — a local green says the suites *can* pass, not that the pipeline
executes them — and here they diverge for a concrete reason rather than a doctrinal one: `main()` aborts if
port 8080 is already serving (`port_is_taken()`, `run_web_e2e.py:454-459`), which on this machine means the
full local run requires stopping the IntelliJ backend first. So the local run is the one that needs a hand and
a clean machine; the CI run is the clean-room one. That inversion is worth a second look before this CR is
called done, because the CR's own headline — *ten refs made honest* — is the thing the missing run is about.

## Not in scope

The two member dialogs (done). Any contracts or backend change (done). Inbox #120's wiki-anchor guard.
A German translation of `running-your-instance.md`.

## Retrospective

**Planning.** This CR was implementable from the doc alone — the clean-room test passed. Every decision that
mattered was pre-made and every one held on contact with the code. Two specifics earned their keep:

- **Decision 5's warning about the regex** (anchor on the fragment, not the base URL) would otherwise have cost
  a debugging round: the mail carries `https://veyq.localhost/tenant/accept#…` while the suite talks to
  `http://veyq.localhost:8080`.
- **The bite-test's own warning that `docker stop veyq-mailpit` is not the lever.** Correct, and it went further
  than the CR knew — see below.

**One decision needed reconciling at implement time, and the CR contained both halves of the contradiction.**
Decision 3 chose `up -d --wait mailpit` and explicitly rejected a hand-rolled poll; the Phase 1 bite then required
"snapshot the Mailpit **URL constant**, point it at a dead port, and require the run to fail **at the wait**". But
`--wait` probes the container's own healthcheck from *inside* the container — it never reads `MAILPIT`, so with
Decision 3 alone the injection would have produced a **green run**: exactly the failure mode the bite exists to
detect, one level up. Resolved by keeping both and giving each its own job: `--wait mailpit` for readiness,
plus a bounded `wait_mailbox()` that confirms the address the orchestrator **exports** is the one that answers.
That second check is not redundant — the healthcheck cannot see a wrong port or a lost publish — and it is what
makes the constant load-bearing. **The lesson is general: when a CR specifies both a mechanism and the bite that
must break it, check they touch the same thing.** A bite that cannot reach the mechanism it names is a bite that
will pass forever.

**Execution.** Two things the CR did not predict, both cheap because they were found by running rather than
reasoning:

- **The jar was stale** by four commits — the predecessor's whole backend half. The module docstring's own trap
  ("a STALE jar fails as a *wrong answer*, not as an error") caught it before the first run, via
  `find backend/src -newer …/quarkus-run.jar`. That is the trap paying for itself; worth keeping the one-line
  `find` next to the prose.
- **`clearMailbox` needed a call site the CR did not name, for a reason the CR did state.** Decision 4 explained
  *why* newest-first matters (a re-issue sends a second mail to the same address) but only asked for the helper.
  Sorting alone is **not** sufficient in `recovery.e2e` test 2: test 1 already delivered and **spent** a token for
  that address, so a poll that wins the race against the new mail returns a dead one. Recipient-scoped clearing
  before each `requestRecovery`/`inviteMember` is what makes the read unambiguous.

**Scope held.** No contracts, no backend, no `generate:api` — exactly as the CR promised, and the promise was
worth stating: it removed the usual "is this green measured against a stale client?" question up front.

**Process, both directions.**

- **Mine:** I edited `running-your-instance.md`'s bullet list by inserting a new lead-in without removing the old
  one, leaving `So the two things you own are:` immediately above `So the things you own are:`. A read-back caught
  it, but a targeted `Read` of the edited region would have been cheaper than noticing it later.
- **Ours, and the useful one:** the CR's § Open risk called out its own weakest point — that the ten refs stay
  unbacked by CI — and named the reason (`port_is_taken()` makes the *local* run the one needing a clean machine).
  That is the right way to hand over a knowingly-accepted risk, and the local run was clean here only because no
  IntelliJ backend happened to be on 8080. Worth noticing that the accepted risk survived the session unchanged:
  the ten refs are now backed by a *local* green, which under ADR 0040 is still not a coverage claim CI has made.

**Next time:** when a CR specifies a mechanism **and** the bite test that must break it, verify at refine time
that the bite's lever actually reaches the mechanism — write the two next to each other and ask "does turning
this knob change that code path?". Here it did not, and only implementing it surfaced that.
