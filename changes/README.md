# Change requests

A change-request (CR) is the **self-contained work order** for an implementation, derived from refined state.
**A CR is deleted when it is done.** It is scaffolding: consumed the moment the work lands.

This reverses a 2026-06-25 convention change that kept them "as a readable log". By 2026-07-14 that log was
**168 files / 18,016 lines** — larger than all of `docs/`, larger than the backend — and it had no reader:
the owner does not read `docs/` at all, nobody judges a project by reading 168 work orders, and an agent reads
the *current* CR plus recent retrospectives. A log nobody reads is not a record, it is a cost (§ What the docs
are for, in [`../docs/ai/README.md`](../docs/ai/README.md)).

Nothing durable dies with it, because nothing durable was ever *only* there:
- **What was built** → the updated `docs/` state; **what was decided** → an ADR. If a CR holds a decision that
  is in neither, that is the bug — fix it there, then delete the CR.
- **What was learned** moves to where it bites, not into an archive. A code trap becomes a comment on the line
  that trips over it (cr-0089's post-rotation zeroed-key trap lives in `web/src/app/activeSpace.ts`, three
  lines from the code that caused it); a working lesson becomes a rule in this file. Both already happened —
  the retrospectives were the ore, not the metal.
- **Do not offload it to git.** The old pre-2026-06-25 rule said "the durable trace lives in git history",
  which is exactly the assumption that breaks at the v1 repo move. Harvest *before* deleting, into the tree.

So: no `changes/cr-*` archive. `changes/` holds this file and any live review/finding backlog.

### Naming — one file per CR, `cr-<slug>.md`

A descriptive slug, **no number**. The number was dropped once it
proved a pure liability rather than a convenience. Its only live job — telling two *parallel* CRs apart — the
slug already does (two open CRs are about different things). Its damage was entirely in its *durability*: a
number is stamped into immutable git history and code comments, so when a done CR is deleted and its number
reused, `git log --grep cr-0174` returns two unrelated changes and every comment citing it is ambiguous (this
bit in practice — the ADR-0009 sweep first collided with a since-deleted mutation-gate cr-0174). A slug is
unique **by meaning** among live CRs and, if one ever recurred years later, collides *visibly* — not as the
silent numeric ambiguity that cost. So there is nothing to allocate, no max-ever to track, and no history to
scan; `scripts/validate_changes.py` checks only that the filename is a lowercase slug and `id` matches it. The
`cr-` prefix stays as a pure namespace marker — it is what separates a CR from this file and any `review-*.md`
backlog. (This retired inbox #107, whose remedy was going to be a monotonic next-number helper; dropping the
number is the simpler dissolution — the counter never has to exist.)

### Self-contained as a work order

It *links* to the durable truth (use-cases, contracts,
crosscutting concerns, ADRs) and *contains* the delta + plan + acceptance. Never copy state docs
into a CR — if you feel the urge, the state doc is what should be updated instead.

### Phases only above real size

A small CR is single-phase. Each phase ends with the repo in a
working, verifiable state (its own "Done when:"). The natural seam is the dependency order:
contracts → backend → web. **Genuinely independent work = separate CRs, not phases.**

### The CR carries the locator, not the number — run it, don't estimate, don't freeze

A CR's inventory states
code-confirmable facts (a symbol, a path, "all of X do Y") as a **command written into the CR that finds the
sites**, never a guess and never a frozen tally. This is a *format* requirement, not a "be careful" plea (which
broke nine times, e.g. cr-0162's phantom `tasks.createJob`): in this repo's history every bare estimate was an
under-count. Three reasons the number itself does not belong in the file:
- **A count rots; a locator re-runs.** The code drifts between refine and implement, and a stale `→ 4` reads
  as current. The command is the only part that stays true, so it is the part that ships.
- **The number's one real job happens before the CR exists.** *"Is this worth doing at all?"* is decided in the
  refine conversation, looking at the output. Once that call is made the tally has no further reader — the
  implementer needs the **sites**, and the CR already says implementation discovers the exact *where*.
- **Where the CR builds a guard, the guard is the completeness proof** — infinitely stronger than a tally in
  prose. cr-0171 could not under-deliver on 235 fields: the validator stays red while one is missing. Prefer
  that over a number every time (see *promote one-shot proofs*, below).

**Find the sites from two angles keyed on different things — the easiest command encodes what you already
believe.** The locator you reach for first is a paraphrase of your own model of the code, so it fails exactly
where that model is wrong: silently, and with a plausible number. Ask a second time on a **different key** — a
different marker, a different tree, the opposite direction — and compare. **The disagreement is the finding**,
and it names the sites rather than merely raising doubt. cr-0171's refine asked the container's *declaration*
(`type == "array"` → 49) where the reliable key is its *target* (`$ref` anywhere in the property → 60); the
11-property gap was the entire miscount, and it listed every offender. This is the only rule here that works
**without foreknowledge** — *match the marker, not a paraphrase* (below) assumes you already know which is
which, and not knowing is the whole miss. Two conditions:
- **Agreement is worth exactly what the independence is worth.** Two angles that share a blind spot agree, and
  the agreement then reads as confirmation — worse than a single angle, because it manufactures confidence.
  `grep -c` against `grep -rc` is one angle twice: cost paid, nothing bought.
- **Spend it where the number decides something** — a go/no-go call, or a predicate that *is* the CR's own rule
  ("what counts as a container" was ADR 0044's decision, measured by a probe that quietly re-implemented it).
  Not on every grep: this repo dies of ceremony faster than of arithmetic.

**Emit the list, not the tally.** A number cannot look wrong; a list can. Printing the items behind cr-0171's
"246 scalars" puts `derivatives: ['array','null'] items={$ref}` in front of you and the fault is unmissable.
Cheaper than a second angle and it catches a wrong *predicate* — but not a wrong *scope*: a list of the sites
you searched can never reveal the tree you didn't (cr-0160's "~8 imports" were 2 **and 22 test files**). The
two defenses cover different failures; neither replaces the other.

A cited command is re-runnable, which is the point; it is not automatically right, and **a locator is only as
true as its predicate** — over a structured format, enumerate the format's spellings, not the one in front of
you, and trace the predicate to the *rule's own words* rather than to a paraphrase of them. Three further
traps: a null result counts only once you've confirmed the search hit the right place; a **truncated** result
is not a null result (cr-0171 piped an inline-YAML probe through `head -10`, read the empty tail as "there are
none", and broke three specs on the assumption); and match the **marker**, not a hand-rolled regex
(`grep -c '@Test'`, not `fun \w+`). And still: **point at a canonical source, don't restate it** (cr-0044's
phantom `/api/token`).

#### Upstream of every trap

Everything above assumes a command ran. Upstream of all of it is the claim that never reached for one. A
statement of *where* or *how* something is built is a locatable claim even when it reads as settled fact, and
the statements that read *most* as fact are the ones this repo's invariants invert. The world's default is
`crypto → a JVM backend module, gated by a JVM mutation tool`; operator-blindness puts content crypto in the
**client**, so #99's refine wrote "PIT against the Gradle/Quarkus setup" with `backend/CLAUDE.md`'s *"no content
crypto"* loaded every turn and the repo map already naming `web/src/shared/crypto`. This is not a locator being
subtly wrong — it is no locator at all, because the claim did not feel like a claim. So: where a world-default
(`crypto → backend`, `auth → server`, `storage → a path you name`) meets one of the hard invariants, the
one-line `grep` goes **before** the sentence, not after the correction. The tell is the feeling of already
knowing — that is exactly where the imported prior is, and exactly where this repo most often inverts it.

**A third tell, and the cheapest to act on: the claim is about a PATH.** A mount point, a `WORKDIR`, a restore
target, a file a plan writes into — a location reads as a coordinate rather than as a claim, so nobody asks what
already lives there. cr-instance-backup-and-restore made that mistake three times in one document: it put the
config-secret volume on `/work` (which holds `quarkus-app/`, so the mount would have frozen the app at its first
image and made every later upgrade silently do nothing), it specified `mariadb-dump --databases` two screens from
a decision that restores into a differently-named database, and it described a bootstrap helper as sealing an SMTP
password when the line it cited passes `username: null` *with the reason attached*. Each was one `ls`, one `grep`
or one paragraph away. **Before a CR fixes a path, list what is there and paste the output in** — and read the
neighbouring decision while you are at it, because nothing checks a document against itself.

**A fourth tell: the CR located where the code goes and never located the GATE that decides who arrives.** An
inventory naturally asks *"where is this added?"* and stops one question short of *"can the person in the
exception flow get here?"* — and a use-case that names a degraded-access flow (locked vault, offline, no
capability, unauthorized) has already asked it. `cr-account-sessions-list-and-revoke`'s phase 3 required a panel
that renders and revokes with the vault **locked**, and the panel does exactly that; the locator list found
where a settings tab is registered and never touched `web/src/router.ts`, whose `/settings/:section?` route is
`requiresUnlock` — so the screen built for a lost device is reachable only with the vault open. Built, tested,
and unreachable in the one state it was built for. **So: whenever a use-case carries a degraded-access
exception flow, one locator line must find that surface's gate** — the route guard, the security annotation,
the capability check — beside the line that finds the insertion point. It is one `grep`, it runs at refine, and
it is the difference between a feature that is implemented and a feature that is reachable.

**A second tell: the claim was inherited.** A fact copied forward from an older doc, a recovered file, or a
previous CR arrives *feeling* measured, because it is written down — and `[inferred]` markings do not catch it,
since nobody marks what they never noticed was a claim. It is worst on **infrastructure boilerplate**: an
endpoint, a port, a config-key name, a file path. cr-e2e-orchestrator inherited `/q/health/ready` from the
recovered orchestrator skeleton via the scoping doc and wrote it into a phase; this backend has no
`smallrye-health` extension, so `grep -rn 'q/health'` over the repo returns **nothing** — one second, versus a
run that failed all four suites with "the backend never became ready" while every boot log said it started in
2.3s. **So an endpoint a plan polls is a locator too**, as is a path it writes, a key it sets, and a lever it
pulls to break something (§ bite-proof a guard, which owns that last case). Before freezing
a CR, run *everything it names*, not only the commands under § Locators. The same CR had already caught this
class one paragraph earlier — a recorded sweep output naming two files that match nothing — and fixed only that
instance instead of re-checking its siblings; one found instance of an unrun claim is the cue to sweep the rest.

### Coverage is part of the work, not a follow-up

Every CR updates `docs/coverage/` for the
use-case(s) it `satisfies:` — each new acceptance criterion gets a test ref (or an explicit `gaps:`
entry with a reason) — and leaves `python3 scripts/validate_coverage.py` **green**. A CR is not done
while the trust chain is red. (A use-case refined ahead of any implementation ships a `status: planned`
coverage file that gaps every target surface.)

### The trust-chain guards stay green

*Every* deterministic check in `scripts/` gates a CR — all of them, not
a remembered subset: `for f in scripts/validate_*.py; do python3 "$f"; done`. The glob is the point: it cannot
go stale. CI runs that same glob, unfiltered, in
[`../.github/workflows/trust-chain.yml`](../.github/workflows/trust-chain.yml). **`scripts/` is the list**, and
no file restates it, because a restated list drifts out of step with the one that runs. This file's own
*"point at a canonical source, don't restate it"*, applied to itself. A red guard means the CR isn't done.
(The glob sees files, not commits: CI runs it over the **committed** tree, so an *untracked* guard — or an
untracked file a guard would have rejected — is green locally and unchecked in CI. Commit before believing a
local green.)

**Where a CR's done-when says "green in CI", know which jobs can even run.** Two independent gates, and both bit
during cr-e2e-orchestrator:
- **Branch filters.** [`ci.yml`](../.github/workflows/ci.yml) triggers only on `main` and on PRs *targeting*
  `main`, so pushing a feature branch runs **nothing**. That CR's Phase 5 said "push the branch so the job runs";
  the push produced zero runs. `ci.yml` carries `workflow_dispatch`, so `gh workflow run ci.yml --ref <branch>`
  is how a branch gets a real run.
- **`workflow_dispatch` resolves against the DEFAULT branch.** A workflow file that exists only on the feature
  branch cannot be dispatched at all — `HTTP 404: not found on the default branch`. `trust-chain.yml`,
  `mutation*.yml` and `wiki-sync.yml` are in exactly that state: they live on `starting-something-new` and not on
  `main`, so **the whole trust-chain job has never executed in CI** and cannot until the branch lands. Its local
  run over the committed tree is the strongest available proof; say that, rather than "green in CI".

Then **read the job's log, not its tick**: a job that ran nothing and a job whose suites all passed render
identically, which is why `scripts/validate_e2e_discovery.py` exists at all.

**What a guard's module docstring must carry — three fields, and this is the only place that says so.** The
docstring is a validator's whole documentation; every other file points here rather than re-listing the fields.
1. **What it asserts** — the invariant, in the terms a reader can check the code against.
2. **The defect that bought it** — the incident, named concretely enough to re-find. Where there was none,
   say so: *"no incident — built with cr-X because …"*. A guard nobody can trace to a cost is a guard nobody
   can later argue away.
3. **Its limit** — what a green run does **not** prove. **"None, because …" is a statable value; silence is
   not.** This field is load-bearing, not courtesy: `scripts/validate_guard_gaps.py` can only pin a gap that
   is *documented*, so an unwritten limit silently shrinks the meta-guard's reach, and no reader can tell
   "this guard has no limit" from "nobody wrote it down". It already slipped once — the limit of
   `validate_changes.py` lives in `validate_guard_gaps.py`'s docstring, not in its own.

The `scripts/mutation_meta_check*.py` follow the same three, and additionally state **why they sit outside the
`validate_*` glob** (they need a built report, so they run from the mutation jobs).

### Before reshaping a symbol, grep `scripts/` for every guard that anchors on it

**Not just the one the task names.** A guard's anchor is part of that symbol's contract, and the task naming one guard is exactly what hides
the sibling the reshape breaks. #103 nearly re-homed the role enum into a shared `ScopeRole` `$ref` because only
the *drift* guard (`validate_contracts_common.py`) was named; `validate_scope_model_vocabulary.py` silently
anchors on `SetMemberRoleRequest.role.enum`, and a `$ref` would have broken it. Enumerate the guards on the
symbol first, then choose the shape — the shape that keeps every anchor green is a constraint, not an afterthought.

### Promote one-shot proofs to permanent guards

When a CR's correctness rests on a structural invariant it
proves with a one-shot check — a `grep` that "X is no longer duplicated", "Y lives only in `shared/`" — that
check is added as a **permanent guard** (a `scripts/` validator step or CI grep) so the invariant can't
silently regress. The tell that this was needed: an audit finding for a rule that already existed *in prose* but
nothing enforced (e.g. the styleguide mandated `@ConfigMapping`, yet 28 sites drifted). If a one-shot check
genuinely suffices, the CR says why.

### Run a new guard's predicate against the UNTOUCHED tree before you write it down

**§ *Find the sites from two angles* applies to a guard's predicate, not only to a locator** — a guard is a
locator that ships, so the first shape you reach for is a paraphrase of what you already believe, and it fails
silently wherever that belief is wrong. `cr-account-sealed-device-register` needed *"a session family carries no
per-family ciphertext"* and reached first for **"no `contentEncoding: base64` anywhere in
`auth.openapi.yaml`"** — the file-scoped shape, because it is the easiest to express. That spec legitimately
carries ten such fields (the login challenge/response, recovery material), so the guard would have been **red on
arrival**. The predicate that holds is schema-scoped: `SessionFamily`'s property set, and no base64 within it.

This is **upstream of the bite**, and it catches the opposite failure. Every rule below assumes the predicate is
right and asks whether the lever reaches it — they test that the guard goes **red on a break**. This one tests
that it is **green on the healthy tree**, which no injection can show: a guard that is red before the first
injection makes every later bite unreadable, since red-because-broken and red-because-wrong look identical. So:
run it once on the untouched tree, *then* write the sentence, *then* bite it. And when the wrong shape was
reached for first, put it in the guard's docstring under its **limit** — the next reader will reach for the same
easy shape, and "we tried that, here is why it cannot work" is the only thing that stops them.

### Bite-proof a guard by snapshot-and-restore, never `git checkout`

To prove a new test bites, revert the
fix and watch it red — the revert must be exact and reversible. Protocol: `cp <file> /tmp/snap` **before** the
injection, break the code, run the one test, then `command cp -f /tmp/snap <file>` (the `command` prefix skips
any interactive `cp -i` alias — and it is needed for **every** destructive builtin an injection uses, not just
this restore step: an aliased `rm` turns a deletion into a prompt, which is a no-op with a green run behind it,
or a command that hangs until its timeout). **Never `git checkout -- <file>` to restore** — it resets to HEAD and silently
discards *uncommitted* work in that file; `git checkout` is safe only when the baseline is committed. Confirm
the restore before moving on — **against the same baseline the snapshot was taken from, which is almost never
HEAD.** A bite runs *before* the CR is committed, so the files it injects into already carry the CR's own
edits, and `git diff --quiet -- <path>` then reports "still dirty" for every case whether the restore worked or
not — a false failure that invites re-breaking the file to chase it (it fired on all ten of
cr-release-identity's injections). Two forms that do work with an uncommitted baseline: compare the file's
**bytes to the snapshot**, and compare **`git status --porcelain` before and after** the whole run.
`git diff --quiet -- <path>` is correct only once the baseline is committed.
**And do not confirm it by counting the fix marker.** cr-sharing-contributor-e2e's
protocol said `grep -c "deletedAt is null" … # expect 2`; the honest answer is **3**, because the comment above
the predicate quotes it in order to explain it. A tally over source text counts prose as code, so it reports a
successful restore as a failed one — and the repair it invites is deleting the comment that documents the rule.
Compare trees, not occurrences. For an **automated, repeated** guard that re-injects on
every run, work on a throwaway *copy* of the tree, not the live one: `scripts/validate_guard_gaps.py`
copies → forges → runs → discards, so even an abort leaves an orphaned tempdir, never a dirty tree
(every guard's `__file__`-relative `ROOT` makes a copied `scripts/<guard>.py` resolve to the copy).
Snapshot-restore is the interactive one-shot; a copy is its automated form.

**First check the lever is wired to the mechanism at all.** Both traps below assume the lever *reaches* the code
path; a CR that specifies a mechanism and, separately, the bite that must break it can name two things that never
touch. cr-email-member-invite-mail-only chose `up -d --wait mailpit` for readiness and a bite that repointed the
orchestrator's Mailpit **URL constant** at a dead port — but `--wait` probes the container's own healthcheck from
inside the container and never reads that constant, so the injection would have produced a **green run**: the
exact failure the bite exists to detect, one level up. Write the mechanism and its bite next to each other and
ask *does turning this knob change that code path?* Where the answer is no, one of the two is wrong — there it
was both, and keeping each for its own job (the healthcheck wait for readiness, a bounded poll for the address
the tool actually exports) is what made the constant load-bearing.

**And check the lever survives the tool.** An injection is only an injection if the thing under test cannot undo
it. cr-e2e-orchestrator planned *"store absent — stop the garage container"*; the orchestrator's own
`docker compose up -d` restarts a stopped container, so that injection silently becomes a green run — correct
behaviour in the tool, useless as a lever. Drive the **configuration** it reads instead (a blank endpoint aborted
the boot, a dead one failed the PUT: two distinct injections, two distinct messages). Before injecting, ask what
the tool does to repair itself, because that is exactly what it will do to your test case.

**And check the lever reaches the oracle, in the form the oracle searches for.** Surviving the tool is not
enough — the injected value has to *arrive*, and arrive *recognisably*. cr-wire-assertion-db-sweep picked a lever
nothing repairs (*"replace the blinded `slugTokenFor` with the plain `slugify(name)`, and the Memory title's
plaintext lands in `veyq_node.slug_token`"*) and it still failed twice, neither time about survival:
- **The transform mutated the needle.** `slugify` lowercases and dash-collapses, so what lands is
  `e2e-place-178…` while the derived canary is `E2E place 178…`. The sweep runs, finds nothing and reports
  green — a bite test that silently proves the *opposite* of what it claims.
- **A gate in between rejected it first.** `slugToken` is contract-pinned `Pattern("[A-Za-z0-9+/]*={0,2}")`, so
  the dashes are a 400 at the boundary and the suite dies *before* the sweep arms — reddening the run on the
  wrong thing, which that phase's own done-when (*"fail **on the sweep**, named"*) had already forbidden without
  noticing that its own injection could not satisfy it.

So **a lever a plan pulls is a locator too**: before freezing an injection, run the transform it passes through
and pull the contract of the field it lands in — `grep <field> contracts/` plus one line on what the replaced
function does to its input, both one second away. This is § upstream of every trap in injection shape, and one
more reason the live defect (§ below) beats a forged one — a real leak already arrives in the form the oracle
sees.

### Where the defect is still live, order it guard → red → fix → green

**The unfixed defect is a stronger bite test than any injection, and it is free.** A forged one proves the guard fires on a shape *you*
chose; the live defect proves it fires on the shape that actually occurred — which is the one your model of the
code got wrong. cr-mutation-gate-completeness' trigger-path guard printed all eight missing workflow entries on
its first run, before a line of the fix existed, and it named them: the same run was the discovery, the bite and
the fix-list. So when a CR both builds a guard and repairs what it guards, **write the guard first and record the
red**; reaching for the fix first destroys the evidence and leaves you injecting a reconstruction of it. This
does not replace snapshot-and-restore — that is what you fall back to when the defect is already fixed, when the
guard must also reject shapes that never occurred here, or when the case belongs in
`validate_guard_gaps.py`'s repeated harness.

### A bite test must tell "the guard held" apart from "nothing ran"

**Assert the expected test goes red by name, never that "something failed".** An injection can break compilation instead of the assertion, and a
harness that greps for a failing test name then reads *no match* as **green** reports the exact false negative
the guard exists to prevent (cr-ratelimit-posture's first sweep-bite did this: the injection had swallowed two
neighbouring methods, nothing compiled, and the run was scored as "the guard does not bite"). Two corollaries:
read the full build output, not a filtered line; and for a **surgical** injection anchor on the target and cut
**backwards** from it — a forward non-greedy multi-line regex latches onto the first sibling member and eats
everything up to the anchor.

**And prove the injection LANDED before believing the run.** The failure above is a red misread as green; this is
its twin, a green that was never earned — the patch did not apply at all. cr-instance-backup-and-restore's first
bite anchored on a string that had drifted by two words, the edit silently no-opped, and the suite passed exactly
as it should have. A second one used `rm` against an interactive `rm -i` alias, so the file was never deleted and
the run came back green again. Both were caught only by reading the full output. So an injection asserts its own
arrival — `grep` the marker out of the file, or `test ! -e` the thing you deleted — **before** the run starts, and
scores an unconfirmed injection as a failed bite, never as a passing one.

### A new universal check meets every existing local one

**Grep the surface it sweeps for tests that already assert something about it.** This is the inverse of the guard-anchor rule below, and it bites the other way:
there, a reshape breaks a guard; here, a new guard breaks a *test*. `DefaultDenyTest` declares a deliberately
annotation-free `@Path` resource in **test** scope, which `@QuarkusTest` deploys like any other — so the
obvious CDI-based enumeration in cr-ratelimit-posture's posture sweep red-flagged it, and the obvious fix
(annotate the probe) would have destroyed the default-deny proof. The exclusion belongs in the sweep, and it
is a **named blind spot**, not a silent filter. A refine that lists the guard being built but not the guards
standing next to it hands that collision to implement time.

### A test that mutates shared singleton/global state writes its restore in the same edit

**As the mutation, not after a flake.** The `%test` H2 mem DB — and any generated file like the config-secret key — is **JVM-shared
across test classes**, so a test that pokes a singleton (cr-config-secret-key-file-and-fingerprint forced a
`veyq_instance` config-secret fingerprint to a bogus value to exercise the MISMATCH gate) must `finally`-restore
it, or it cascades failures into every later test that assumed the clean value. This is the *write*-side sibling
of the *insert*-side seed-uniqueness rule (distinct domains/emails/**secrets** so the mem DB can't bleed —
cr-email-invite-send-hardening's `token_hash` collision): the same JVM-shared-state trap. Run the **full**
`./gradlew check`, never just the touched class, before calling backend done.

### `git status` before the first edit of a contracts-phase CR

**A dirty shared spec is stop-and-confirm, not proceed.** Two CRs touching one `*.openapi.yaml` mix unrelated deltas into a single commit: #103's mechanical
enum narrowing landed on top of uncommitted `cr-space-icon` WIP in `scopes.openapi.yaml`, and only a pre-edit
`git status` caught it. Split the go — land one, then the other as its own commit — and the history stays legible.

### Before adding a read field to expose a server fact

**Prove the fact isn't already *implied* — and that the server can *reliably know* it.** A new field earns its place only when the value is both (a) not derivable from
what the read already carries and (b) something the server can determine truthfully. cr-email-tenant-mail-settings-web
dropped a proposed `sealAvailable` because `passwordSet: true` already implied the KEK was present at seal time;
cr-config-secret-key-file-and-fingerprint then *added* `configSecretState` — not a reversal, but because a new
capability (the DB fingerprint tripwire) let the server *detect* a state it previously could not. The deciding
question is the server's knowledge, not the client's wish — name that dependency when first weighing the field,
not a CR later.

### Every CR closes with a short, honest retrospective

A `## Retrospective` section: what went wrong
in *planning* and in *execution* (not what went right), the root cause, and one concrete change that
would have prevented it. Criticism flows **both ways** — the assistant's misses *and* the human's
direction (a premature instruction, a missing constraint, a process gap) are both fair game and
explicitly wanted. Cover **process & collaboration**, not just the technical work — in particular,
did we honor the conventions and feedback we already agreed (language, style, workflow rules, the
memory notes)? A rule that already exists but was ignored is a *real* miss, not a non-event. Each
retro ends with a forward-looking **"next time"** — one concrete, actionable thing to do differently.
Two or three bullets is enough; the goal is a compounding record of process lessons, not ceremony.
If genuinely nothing went wrong, say so in one line — don't manufacture a fault. **The process/collaboration
part is also delivered *actively* to the user at implement-session close** — spoken in the conversation, not
only written into this section (see the closing process retro in [`docs/ai/README.md`](../docs/ai/README.md)).

## Template

```markdown
---
id: cr-<slug>            # equals the filename stem, e.g. cr-space-icon — a descriptive slug, no number
title: <short title>
status: planned          # planned → in-progress → done (then harvest what binds, and delete the file)
satisfies: []            # use-case ids this fulfills (trust-chain link)
adrs: []                 # related ADR ids
invariants: []           # hard rules this work could threaten
---

## Goal
One paragraph, readable cold — no chat context required.

## Context & constraints
Links (not copies) to the durable truth this must honor: use-case(s), contract file(s),
crosscutting concern(s), ADR(s), and the specific invariants at risk.

## Phases
- [ ] 1 contracts — <scope> · Done when: <verifiable checkpoint>
- [ ] 2 backend  — <scope> · Done when: <verify command green>
- [ ] 3 web      — <scope> · Done when: <verify command green>
- [ ] N coverage — docs/coverage YAML(s) for each `satisfies:` use-case ·
      Done when: `python3 scripts/validate_coverage.py` green

## Definition of done
Tests green across the listed platforms, AND `docs/coverage/` updated for each `satisfies:` use-case
(every new acceptance criterion covered by a test or an explicit gap) with
`python3 scripts/validate_coverage.py` green. If the CR rests on a structural invariant, its proof is
promoted to a permanent guard (or the CR notes why a one-shot check suffices).

## Retrospective
What didn't go well — in planning, execution, AND process/collaboration (did we honor the conventions
+ feedback we already agreed — language, style, workflow, the memory notes?) — root cause, and the
concrete fix. Both directions (assistant misses + human direction). End with a forward-looking "next
time": one concrete, actionable change. 2–3 bullets, or one line if nothing did.
```
