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
  that trips over it (cr-0089's post-rotation zeroed-key trap lives in `src/web/src/app/activeSpace.ts`, three
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

**A phase commits before the next phase's first edit of a file the two share.** cr-report-and-hold's phase 4 (the
hold) wired itself into the report handler, the module-composition test, the stored-time readers and the migration pins
while phase 3 (the report) was still running its full check; committing phase 3 then meant saving phase 4's copies of
those four files, writing phase-3-only variants by hand, committing, and restoring — a split nothing proves matches
what was checked. A long check is no reason to start the next phase in the files it reads; start it in files of its
own, or wait.

**A contracts phase that adds an operation to an EXISTING tag cannot end green everywhere, and that is correct
rather than a violation of the rule above.** The generated `…Api` interface gains a method the moment the spec
does, so the `…Resource` implementing it no longer compiles until the backend phase — there is no ordering that
avoids this and no reason to want one.

**The clients are in the same position, and there the trigger is any request shape change.** A generated
TypeScript client turns a new required field into a missing-property error and a removed one into an
excess-property error at every call site that builds the request, so after `pnpm run generate:api` a contracts
phase that reshapes a request is red in `src/web/` and `src/admin-web/` until the client phase lands.
`cr-cold-credential-proves-the-secret-key` wrote "`npm run check` (web and admin-web) green" into exactly such a
phase; the Done-when for it is `generate:api` plus the contracts guards. **A removed *response* field is the
other way round: it reddens only code that reads it and fixtures typed against the model.** **An added *required*
response field reddens the server that builds it**: `cr-meta-writes-carry-the-version-they-read` predicted "backend
green" for a contracts phase that added `metaVersion` (now `metaRevision`) to `NodeSummary`, and `ContentResource`'s
`NodeSummary(...)` construction stopped compiling.
`cr-guest-names-for-members-only` predicted a red at the fixture that served the removed field and got a green,
because that fixture reached the code cast `as unknown as` (165 such casts, inbox #195). So a phase that predicts
a client red names the typed line it expects to fail — and runs `pnpm run check` once to see it.
**And a new model's property names are the generator's to keep or change**: cr-bucket-reconcile's `keys` became
`propertyKeys` in the Kotlin model (a name the generator reserves), found only when phase 2 wrote the first constructor
call, so the contract was renamed after its phase had committed. A contracts phase that adds a schema writes one use of
it in each generated language before it commits. **So are its type names, where a schema crosses into `_common`**:
cr-instance-privacy-notice's `PrivacyNotice`, served by the operator spec, reached `LocalizedMarkdown` through the
spec's `_common` alias (and, moved into `_common`, through `_common` itself), and both generators named its parts inline
either way (`PrivacyNoticePassageText`, `PrivacyNoticePassagesInner`, `OperatorSectionRecord`) — the other specs' runs
happen to overwrite `OperatorSection` with the right names, and nothing did for the new schema. The rule above was
skipped, so the backend phase found it and gave the notice a text type of its own; one constructor call would have. And cr-identity-pins-per-entry met both generators' reserved property names a phase
later: Kotlin reserves `entries` (the field is `propertyEntries` there) and TypeScript `delete` (`_delete`, in a model
that did not even compile), so the write's `delete` became `remove` in the backend phase.
a client red names the typed line it expects to fail — and runs `pnpm run check` once to see it. **It finds those lines
by the request's newest existing field, grepped across the whole tree, not by the operation's name:**
`cr-instance-contact-and-provider-identification` grepped `createServerOwner` in admin-web and predicted one red; the
dev seeder, web's e2e harness and two backend tests also build that request, and `moderationEpoch` named all of them.
**A reshaped *response* is found the same way, by its fields, not by its path:** cr-statement-delivery-report nested
`blockAccountSignIn`'s `ModerationSteps` under `steps`, and its locator — the operation's path — missed the slice test
that read `signInBlocks[0].id` off the answer; the first full check found it. Grep the answer's fields too.

**The qualifier is load-bearing: an operation on a NEW tag compiles.** A new tag generates a *new* `…Api`
interface that no `…Resource` implements yet, and an unimplemented interface compiles — so that contracts phase
is green on every surface but three tests of `./gradlew check`, which see a new tag the day it lands:
`ModuleCompositionTest` (a generated API no deployed resource implements answers 404) and, for an operation marked
`x-veyq-channel: admin`, `AdminChannelPostureSweepTest` and `AdminChannelGateSliceTest` (a marked operation no resource
implements; the gate test's interface list is by hand, and its tripwire names the new interface).
`cr-moderators-and-the-moderation-key` predicted an all-green contracts phase from this paragraph's earlier wording and
got exactly those three reds; the phase states them, it does not stub them. `cr-favorites-two-objects` predicted a red from the
unconditional wording this paragraph used to carry, ran `./gradlew check` to confirm it, and got `BUILD
SUCCESSFUL`. Cheap to check and worth checking: the prediction decides whether the phase's Done-when lists the
backend at all, and a phase that says "this will not compile" is a phase nobody runs the backend check on —
which is how a real red would get waved through as the expected one. **One test does see a new operation on day
one:** `OperatorBlindProbeTest.everyContractOperationIsProbedOrExcludedWithAReason` derives the operation set from
the generated interfaces and is red on any operation nobody classified — `cr-email-address-confirmation`'s
contracts phase predicted green and got exactly that one red. So the contracts phase classifies its new
operations there (a probe, or an exclusion with its reason), which is a decision, not a stub.
Its "Done when" is therefore the checks that *can* run
(`pnpm run generate:api` + `pnpm run check`, the contracts guards), stated as exactly that; do not reach for a
stub implementation in the contracts commit to make `./gradlew check` green, which buys a green tick by adding
a second thing to remember to delete.

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

**And where the inventory classifies a SHARED read, classify its callers, not the read.** A sweep that adds a
predicate splits its sites into "takes it" and "must not" — and a `grep` returns *reads*, so a read with two
callers gets one verdict and is silently wrong for one of them. cr-node-delete-reversible-trash's inventory named
three reads that must not filter; there were four. The fourth was `NodesStore.findInScope`, correctly classified
as *taking* the predicate for its **update** caller and wrong for its **delete** caller, where filtering turns a
promised idempotent no-op into a 404 while a foreign node must keep answering the identical 404 — so the two
callers need two lookups, not one. No locator over reads can see this; the follow-up is one `grep` for the read's
own callers, run only for the sites the CR puts on the "must not" side, because that is the side making a
completeness claim about an **absence**.

**A rename is graded by its locator re-run AFTER the sweep, never by the sweep's own pattern.** The edit is a
script, and its regex is narrower than the locator by construction: it is written to change things, the locator
to find them. `cr-files-becomes-depot`'s guard locator listed `"files"` in `scripts/`; the sweep keyed on
`features/files`, so `validate_guard_gaps.py`'s `root / "web" / "src" / "features" / "files"` survived and the guard
went red one 15-minute run later. The same sweep, re-run over a later phase, rewrote the two `files.file` values a
new test pins ON PURPOSE (a pre-rename archive must be skipped), and its possessive rule (`Files'` -> `Depot's`)
turned the closing quote of `'E2E Files'` into a broken string. So after each sweep the locator runs again over the
whole tree, untracked moves included (`git grep` does not see a directory moved with plain `mv`; `grep -r` does),
and every remaining hit is spared by name — the CR's look-alike list, or a comment at the line that says why.

**A locator for a class name searches the bare name, not only its selector form.** cr-own-ui-components-on-reka's
inventory found the library's classes as `\.p-[a-z]` — the way a sheet or a query writes them — so two native
inputs with `class="p-inputtext order-received"` in admin-web's order form passed every phase's locator, the call-site
agents' closing grep and every guard, and lost their field look the day the library's global sheet went; only the
browser pass saw it. A class name has three spellings in this repo: the selector (`.x`), the attribute value
(`class="… x …"`, `:class`) and the string a script builds (`'x'`). A locator meant to prove an absence greps the bare
word (`\bp-[a-z][a-z-]*`) and spares its look-alikes by name.

**"Measured against `main`, so no phase re-derives it" means *don't rebuild the inventory* — never *believe every
row*.** A CR that carries its measurements in a table earns the implementer's time, and the clause that hands
them over is doing the opposite of what it reads as: it suppresses re-derivation of the whole set, which is
right, while sounding like a warrant on each row, which is wrong. Nothing in the pipeline re-checks a handed-over
measurement — § *a settled decision* below is the same failure one altitude up — so the row that is wrong is the
one nobody looks at. `cr-abandoned-upload-session-reclaim` shipped *"the `P30D` every sibling uses"* under
exactly that clause; two of five siblings are not `P30D`, and the implement session only caught it by reading
`application.properties` for an unrelated reason. **So: an implementer spot-checks one row of a handed-over
table — the one the CR's own argument leans on hardest — and the author writes the clause to invite that.** One
row, one command; the point is that the table has *a* reader, not that it is re-derived.

**A locator whose Definition of done is "the same before and after" must ship in a form that COMPARES.** Most
locators answer *"where are the sites?"* and are read once. A few are different: the DoD makes the *set itself*
the condition — cr-device-wrap-join-and-remote-cutoff's anti-withholding sweep, whose done-when is *"the list is
the same before and after"*. Written as a bare `grep -rni`, it returns 53 lines across 23 files, and the check
degrades into comparing two screenfuls by eye — so it gets skipped, or worse, eyeballed and declared equal.
Ship that kind as a **count plus the file set**, or as the narrower predicate the condition is actually about
(there: the calls that delete local key material, which a `git diff | grep` answers in one line and which grew
by exactly zero). The tell is in the DoD's own wording: *"unchanged"*, *"the same as"*, *"no new"* — a condition
about a **set** needs a command that produces a comparable set, not a readable one.

**And the DoD's own BASELINE is that same locator, which is why it is the number most likely to be wrong.** The
rule above is about locators inside the phases; this is it turned on the section that grades them. A Definition
of done that says *"baseline: N guards green, so a red after this CR is this CR's"* has written a set
comparison as a tally, and nothing in the pipeline re-runs it — the implementer is explicitly told not to
rebuild the inventory, and the baseline reads like part of that inventory.
`cr-blob-reference-arity-guard` shipped *"26 guards, all `rc=0`"* at a commit where `ls scripts/validate_*.py`
returned **25**, at the moment it was written. What makes this worth a rule rather than a shrug is the failure
mode: that CR's own work **adds one guard**, so the finished tree stands at exactly 26 — the wrong baseline
would have been confirmed by the work it was meant to grade, and the "a red after this is mine" mechanism would
have been disarmed with every check green. So: **a baseline ships as the command that produces it, and the
implementer runs it before the first edit** — one line, before anything can be attributed to it. **And it runs
on a detached worktree of `HEAD`, not on the tree being edited:** the whole guard glob takes about fifteen minutes
here and reads each file only when it reaches it, so an edit made while it runs lands in the later guards' reading
of the "baseline" (`cr-release-pipeline-and-chart-oci`'s first baseline was spoiled exactly so, and had to be
thrown away and re-run on `git worktree add --detach <dir> HEAD`). The same worktree is where a bite harness
injects, so a half-restored injection can never reach the shared tree.

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
  `grep -c` against `grep -rc` is one angle twice: cost paid, nothing bought. **Independence in KIND is not
  independence in SUBJECT, and a CR that retires more than one thing needs a key per thing.**
  `cr-shell-preferences-into-profile-menu` removed two top-bar controls and asked from two properly different
  kinds of key — the prose claim and the symbols — but both were keyed on *theme*, so everything the codebase
  called *the gear* went unfound: an ADR decision the CR had declared untouched, four comments in the hub's own
  view, and a style citation. One `grep -rni gear` at refine would have listed all six. The tell is arithmetic,
  not judgement: count the things the CR retires, count the keys, and if the second number is smaller the
  sweep has a hole shaped like the difference.
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
**client**, so #99's refine wrote "PIT against the Gradle/Quarkus setup" with `src/backend/CLAUDE.md`'s *"no content
crypto"* loaded every turn and the repo map already naming `src/web/src/shared/crypto`. This is not a locator being
subtly wrong — it is no locator at all, because the claim did not feel like a claim. So: where a world-default
(`crypto → backend`, `auth → server`, `storage → a path you name`) meets one of the hard invariants, the
one-line `grep` goes **before** the sentence, not after the correction. The tell is the feeling of already
knowing — that is exactly where the imported prior is, and exactly where this repo most often inverts it.

**And it reaches the question you put to the USER, which is where it costs the most.** Every tell above is
about a claim written into a document; this one is about a claim embedded in the *framing of a decision you are
asking someone to make*. A question carries its premises, the user answers the question rather than auditing
them, and their "yes" then reads as agreement with the whole framing — so a wrong premise is not merely wrong,
it has been **ratified**. `cr-account-key-rotation-after-compromise` did this at implement time: it asked
whether the cold-credential gate should refuse a device-opened session *before* offering the form, and the
use-case's own E1 already said the opposite in five words — *"asks for the password + Secret Key in place"*.
The premise had never been grepped; the option was approved; the wrong behaviour was built, tested and
documented into two durable docs before a later reading of E1 caught it. The rule is § *upstream of every
trap* pointed one step earlier: **before writing an option that contradicts refined state, re-read the exception
flow that governs it and quote it in the question.** A question that quotes the doc it is about lets the user
overrule the doc knowingly, which is a decision; one that paraphrases it from memory takes that choice away
from them without either of you noticing.

The same holds one step earlier still, in the inbox row the brief is cut from. `cr-item-marks-binary-ids`' first
brief prescribed #491's per-entry shape for item marks, because #491's row was the one that lifted a similar cap —
and ADR 0068 § 4 rejects exactly that shape for marks (a stable row per mark that the server can label by timing).
**A row or a brief that borrows a sibling's design names the ADR that governs its own object**, and quotes the line
that allows or forbids it.

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
where a settings tab is registered and never touched `src/web/src/router.ts`, whose `/settings/:section?` route is
`requiresUnlock` — so the screen built for a lost device is reachable only with the vault open. Built, tested,
and unreachable in the one state it was built for. **So: whenever a use-case carries a degraded-access
exception flow, one locator line must find that surface's gate** — the route guard, the security annotation,
the capability check — beside the line that finds the insertion point. It is one `grep`, it runs at refine, and
it is the difference between a feature that is implemented and a feature that is reachable.

**And its mirror, when the CR MOVES code rather than adds it: cross-imports find who reaches IN, never who a
file is scoped FOR.** The fourth tell asks whether the person in the exception flow can reach a new surface;
this one asks who a file already belongs to before you move it somewhere they cannot go.
`cr-admin-web-extraction`'s refine classified `src/web/src/features/admin/**` by directory plus a cross-import
grep, and correctly found the anonymous and ordinary-member callers reaching into it. It put
`TenantTermsPanel` and `TenantMailSettingsPanel` on the "Server-Owner only, moves out" side — because nothing
imports them from outside. Nothing does: they read `activeSpace.tenantId` and are mounted by the settings hub
under its tenant category, so they are **owner-scoped by the state they read**, and an import graph is
structurally blind to that. The backend gates both with `403 not_tenant_owner`, not with the Server-Owner
gate, and a tenant owner is by construction an ordinary user who accepted an invite — so the move would have
taken a tenant's own email and terms configuration away from everyone who is not also the Server Owner. A
product change, discovered at implement time, in a CR whose whole premise was that a literal directory move
breaks things. **So: classify by the gate the BACKEND applies, not by the directory and not by the import
graph** — `grep -rn '403\|not_tenant_owner\|ServerOwner' <the endpoint's handler>` answers it in one line, and
it answers for the file that nobody imports.

**A fifth tell: the cleanup was specified against the state that prompted the thought, not against the
predicate.** A CR that says *"a row reaching `EXPIRED` unspent revokes what it minted"* has written the case it
was thinking about, and every other terminal state reaches the same hazard unasked. The device-pairing CR did
exactly that: it specified the expiry revoke **and**, three paragraphs apart, that delegating supersedes the
account's other live channels — so a channel superseded seconds after it delegated stranded a live 30-day
refresh token that the sweep, as written, would never have looked at. It surfaced at implement time, which is
the expensive place to find a specification gap. **So: where a CR keys any cleanup on a terminal state,
enumerate every terminal state in that same paragraph and say for each whether it applies.** The tell is the
singular — one status named where the vocabulary has three or four; the fix is one line, and it converts a
finding into a specification.

**A sixth tell: the decision *demands* a value and never locates where that value comes from.** Every tell above
is about a **stated** fact nobody measured; this one is about a **demanded** one, and it hides better, because a
requirement does not read as a claim at all. The delegated-session transport CR argued at length that adoption's
response must carry the **family id** — it named it five times and it was the entire reason the response shape
changed from a token pair to the login response — while a constraint three paragraphs later forbade touching the
service that would produce it. That service's rotation returns `(accessToken, refreshToken)` and its family
lookup is `private`, so the two requirements could not both hold; it surfaced at implement time as a blocking
escalation. The refine had measured that very function **twice**, both times for its *rotation semantics*, never
once for its **return type**. So: **for every value a decision says a response must carry, run the one line that
names the function producing it and prints its signature, and paste the output** — a demanded value is a locator
too. Two corollaries, both cheap: the tell is a decision that says *"it returns X"* where no inventory row says
which call hands X over; and where a constraint forbids touching something, **state the intent rather than the
letter** ("rotation and reuse detection stay semantically unchanged", not "no change to `SessionService`") —
an absolute reading collides with the additive detail the decision itself requires, and the implementer then
cannot tell a real invariant from a phrase that was never meant that strictly.

**A fourth corollary, and the one that bit hardest: the demanded value sits behind a GATE.** The sixth tell is
about a value whose *producer* nobody located; this is about one whose *reader* cannot reach it.
`cr-store-provisioning-from-a-declared-type` specified that its e2e orchestrator wait on a new Server-Owner read
*"reporting the succeeded state"* — an endpoint the same CR's contracts phase had, two pages earlier, given
`@RolesAllowed(SERVER_OWNER)` and a `403 not_server_owner`. The instance doing the reading is a **throwaway,
pre-setup** one that by construction has no Server Owner at all, so the wait could never have been written as
specified, and that surfaced at implement time as a redesign. Both halves were in the CR, four phases apart, and
nothing brought them together — the gate reads as infrastructure while the value reads as the decision. **So:
whenever a phase says "wait on X", "poll X", or "assert X", write the line that reads X *including its auth*
into the CR beside it.** `grep -n 'RolesAllowed\|@PermitAll' <the handler>` is one second, and it is the
difference between a phase that is implementable and one redesigned at its most expensive moment. Note the
family resemblance to the *fourth tell* (the CR located the code and not the gate that decides who arrives):
that one is about a human in a degraded-access flow, this one about the harness — same missing locator, two
different arrivals.

**A third corollary, one altitude down: a decision that branches on a STATUS CODE is a locator too.** A CR can
demand a behaviour the client has no vocabulary for. `cr-pairing-add-a-device-web` required adoption's `400`
(this browser already holds a session) to reach a *different* message than its `401` (the grant is gone) —
their remedies differ, so folding them is the defect — over a `HttpStatus` map that carried no `400` at all;
`rg -n badRequest src/web/src` returns empty and takes one second. It cost three additive lines there, which is
precisely why it is worth writing down: the same miss over something that **cannot** be added additively is
the expensive version of it.

**And in a RACE measurement, every distinct loser answer is a locator for a read the fix must order.**
`cr-one-time-secrets-spend-by-compare-and-set` measured two concurrent accepts as 4× `[200, 500]` and 1×
`[200, 409 tenant_not_pending]`, then planned the compare-and-set "before any insert" and a DoD of exactly one
`410`. The one `409` was the finding: a loser that reads the winner's commit in a *check* (the tenant's status,
the taken email, the taken username) answers that check, so a spend placed after the checks cannot reach the DoD.
It had to move ahead of them, and a refusal after it then had to roll the spend back — a design step the CR never
named, found at implement time. Read the minority answer in a race's tally as a list of reads; the majority one
only names the write.

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

**A seventh tell: the CR named TWO exemplars and never diffed them against each other.** A phase that says
*"mirror X's shape"* and, a clause later, *"reuse Y's expression"* reads as unusually well-grounded — it cites
working code twice. But two templates are two claims, and the CR has asserted a third one it never checked:
that they agree. `cr-abandoned-upload-session-reclaim` pointed its sweep at `MediaTrashPurge` for shape and at
the budget purge's chunk-key enumeration (then `MediaHandler`'s) for the expression; those two sites disagree on exactly the line that
decided whether the CR was testable at all — the trash purge calls `blobStore.delete` unconditionally, the
budget purge gates it behind `if (!blobStore.canPresign) return`, and the hermetic store cannot presign. Copying
the second would have produced a job whose store half no test could observe, which is the hole that CR's *other*
decision existed to close. **So: where a phase names two exemplars, read the lines it is actually copying and
say which one wins.** It is one `sed -n` per site, it runs at refine, and the tell is purely syntactic — an
"and" joining two `file:line` citations in one instruction.

**An eighth tell: the CR prescribed a PROPERTY and, separately, a SHAPE copied from an exemplar — and never
asked what makes the exemplar's shape deliver its own behaviour.** The seventh tell is two exemplars that
disagree; this is *one* exemplar, cited correctly, whose shape is load-bearing only under a condition the CR
never looked for. `cr-tenant-suspend-request-gate` required its gate to refuse **before authentication is
evaluated** and, in the same Decisions section, specified it as *"same shape as `RateLimitFilters.kt`,
`Priorities.AUTHENTICATION`"*. Both sentences are reasonable and they cannot both hold: Quarkus's eager
security rejects an unauthenticated request from a handler that runs ahead of every post-match filter, so that
priority answers `401` and the gate never runs. `IpRateLimitFilter` is untouched by this only because
`key = IP` is confined to `@PermitAll` routes — a condition stated in its own KDoc, one `sed -n` away, and the
whole reason the analogy fails. The tell is syntactic and does not need the answer: **a phase that names a
property in one clause and an exemplar's mechanism in another has asserted that the mechanism delivers the
property**, which is a claim, and the one command that checks it is the one that reads *why the exemplar
works* rather than *what it looks like*.

The other half of that CR is why this costs a paragraph instead of a phase: it had **pinned the property
independently of the mechanism**, as a bite test asserting `403 tenant_suspended` by body against its own `401`
control. So the wrong shape failed loudly in the first run, named the ordering and nothing else, and cost
minutes. **Where a CR prescribes both, the test belongs on the property and never on the shape** — a test
written against the mechanism ("the filter is registered at AUTHENTICATION priority") would have been green on
code that refused nothing.

#### A SETTLED decision carries the one line that settles it

**A CR marks what is open. Nothing marks what is closed — so a closed decision that is wrong is the one thing
in the document nobody looks at.** This is the tells chain applied to the CR's own structure rather than to a
sentence in it: an open question invites a reader to check it, and *"decided in ADR X"* invites them to move on.
Both are claims; only one is dressed as one.

`cr-account-key-rotation-after-compromise` is the worked example, and it named its open questions **well** — two
of them, handed over with the evidence to settle them, both closed in one exchange. It took four. The other two
were hiding inside decisions the CR believed settled: ADR 0060 said the cold wraps are rewritten *"under the
same KEK"*, and the `RECOVERY` wrap is withheld from `GET /api/account/key-material` (`KeyMaterialHandler.kt:43`,
one grep) so the client cannot read its salt; and it said the gate *is* "which `MasterKeyWrap` the caller
opened", which cannot be the whole gate because the rewrite needs the password and Secret Key as *material*.
Neither is a locator that was run and got the wrong answer. Neither was a locator at all.

So: **every decision the CR relies on carries either the command that settles it or the `file:line` that does.**
Not a re-derivation — one pointer, the same length as the assertion it replaces. Three things fall out, and the
third is the reason this is worth the keystrokes:

- The reader can **check it in a second**, which is the whole difference between prose and a locator.
- Writing it is where the author notices there is nothing to point at — which is exactly when the question is
  cheap. Both misses above would have surfaced *while writing the sentence*, not at implement time.
- It **survives the ADR being right and the CR being wrong about it**, which is the actual failure here: ADR 0060
  Decision 2 was a reasonable decision that the code could not honour, and only building it made that visible.
  A pointer at the code the decision constrains is what makes an unbuildable decision loud (§ *doc unbuildable*,
  [`../docs/ai/README.md`](../docs/ai/README.md)) instead of a surprise four phases in.

The bar is judgement, not ceremony: a decision that binds this CR's work, on a surface it touches. Not every
ADR it cites for context, and never a second copy of the ADR's reasoning — a pointer, or the sentence stays as
it is and takes its chances.

### A library swap inventories what the library does unasked, one Defaults row per component

Locators find what *reaches into* a library: its imports, its classes, its variables. They cannot find what the
library *does on its own* that the screens rely on, and in a swap that is where the work hides.
cr-own-ui-components-on-reka counted every PrimeVue import, `.p-*` class and `--p-*` read, and still met each of the
following only in a browser pass or a red test run:
- the date picker opened on focus on every date field, not only the Memory's;
- the dialog focused the field marked `autofocus`;
- a typed date outside min/max was refused;
- the select showed its placeholder;
- a `<label>` handed its click to the first labelable element inside it (the new calendar button, which then closed
  the calendar the focus had just opened);
- a tab switched on `click`, where Reka switches on the press — 27 tests went red, and one had been asserting an
  absence on the wrong tab all along.

So a CR that replaces a component library carries, per component, a **Defaults** row measured at refine against the
running app: where focus goes on open, what opens on focus, what a typed or picked value outside the bounds does,
what the empty state shows, what a wrapping label does, and which event (press, click, pointer enter) drives it. The
row is what the new component must keep or what § Decisions lists as changed on purpose — the screens' behaviour is
the contract, and the old library's defaults were part of it without anyone writing them down.

### When a CR sorts the code into kinds, every kind gets a locator — including the ones it spares

A sweep-shaped CR almost always opens by classifying: *"three mechanisms exist and only one is wrong"*, *"four
call sites, four tests"*. The locator discipline above then gets applied to the kind being **changed** — that
one is the subject, and the author reaches for `grep` naturally — while the kinds being **kept** are written as
prose with one example each. Those are the rows that are wrong, because sparing a kind is a completeness claim
too, and it is the one nothing re-checks: no phase touches it, no test covers it, no guard grades it.

`cr-web-own-confirm-dialog` carried three commands for `window.confirm` and prose for its two siblings. The
prose said the *dedicated dialog* mechanism was `DeleteNodeDialog.vue`; there were three of them, all
hand-rolling the same shape — the sentence in the docs phase would have been written wrong from it. The same
slip one size down, in the phase text: *"the four test files that stub `window.confirm`"* — there were five,
and the fifth stubbed it because it **mounts** a component that calls it, which is exactly the class a prose
count cannot see and `grep -rln` answers in one line.

**A CR that NARROWS what a function accepts has a class it never names: the fixtures that feed it the input
now refused.** A grep for callers finds production call sites; it cannot see a test that builds the refused
input and hands it through the real path. `cr-kdf-params-client-chosen` listed every caller of
`decodeKdfParams` and none of the five suites that served it a cheap Argon2id profile — three via the named
`TEST_ARGON`, two via local literals a grep for that name also misses. Key that locator on the input's
**producer** in the test trees (there: `encodeKdfParams(`), never on the constant you expect them to use.

So: **a classification is an inventory, and every class in it ships with the command that produces its
members** — the one being removed, the ones being kept, and the surfaces explicitly excluded. Cheap (the
locators are variations of one another) and self-checking: if two classes' commands overlap, the classification
itself is wrong, which is worth finding at refine rather than in the retrospective.

### A rule the refine states over every text gets a locator over the texts

A refine that writes a rule for a class ("an empty value renders as the gap wherever a text names it") inventories
every member of the class, or the implementer finds the ones the decisions left out. cr-operating-mode-retires's ADR
edit said it of every fixed text, its decisions named only one sentence of the statement of reasons, and the contact's
gap in the statement — a Server Owner's step with no contact reading "write to not stated by this operator" — surfaced
in the implementation as an owner question (inbox #522).

### The published docs move with the change, in the same CR

The docs site (`site/`, [ADR 0087](../docs/decisions/0087-published-docs-are-a-starlight-site-docs-is-for-development.md))
is how users, space owners, Server Owners and researchers learn what veyq does, so a CR that changes what one of
them sees or does — a screen, a label, a step, an operator procedure or command, a configuration key, a security
property or a residual — updates the site pages that speak for it **in the same CR**, as a phase or inside the
phase that makes the change, never as a follow-up. A page left behind tells its reader something the product no
longer does, and nobody notices until a user follows it. The locator is the pages' `sources:`: every doc the CR
edits under `docs/` is declared by the pages that restate it (`git grep -ln "<doc path>" -- site/src/content/docs`),
and a Use page's steps use the UI's own words (`src/web/src/i18n/index.ts`), so a changed label is found by grepping the
site for the old one. A Use page changes in English and German together; a new user-facing capability gets its
page, at the same depth as its neighbours. The CR says which pages it touched, or that it touched none and why.

### Text retired because another home carries it is compared claim by claim, and checked at the code

`cr-published-docs-site`'s last phase cut every use-case's bilingual reader section to one line, because the Use
pages were to carry its material. Compared claim by claim, 28 of the 74 sections said something no page said (that
signing in never reveals whether an account exists, that a deleted group's links pause for its 30 days). And seven
were false or stale against the code: the server does know who added you to a group; a guest accepts the space's
terms, per space, not the server's; re-framing a cover keeps the picture; Personal's address is `~<username>`. A
section cut unread loses the true half, and one carried over verbatim publishes the false half. So a phase that
retires text because another home carries it lists each claim. It finds the claim in the new home, or checks it at
the code and then adds it there, and it reports every claim it found false. A section-level "the page covers this"
is not that check.

### Coverage is part of the work, not a follow-up

Every CR updates `docs/coverage/` for the
use-case(s) it `satisfies:` — each new acceptance criterion gets a test ref (or an explicit `gaps:`
entry with a reason) — and leaves `python3 scripts/validate_coverage.py` **green**. A CR is not done
while the trust chain is red. (A use-case refined ahead of any implementation ships a `status: planned`
coverage file that gaps every target surface.) There is no planned *criterion*: `status: planned` is the file's, and
every criterion of a built use-case needs a ref, so a CR that adds an alternate flow to a built use-case writes its
criteria in the phase that brings their tests — `cr-guest-write-record`'s phase 0 promised "planned coverage" for three
built use-cases and could write none.

**A phase that renames or removes a test runs `validate_coverage.py` itself — `./gradlew check` does not read
`docs/coverage/`.** `cr-store-connection-in-admin-web` renamed four test methods in its phase 2 commit with a Done-when
that named only `./gradlew check`; `validate_coverage.py` and `validate_guard_gaps.py` (which goes red on a copy it
cannot forge) stayed red through phases 3 and 4 and were first measured in phase 5. The locator is the refs, not
the test sources: `git grep -n "<old test name>" -- docs/coverage`. Such a phase runs `validate_guard_gaps.py
--force`, too: locally that guard skips while no file under `scripts/` changed, and a renamed anchor is exactly what
a skip does not see.
**So the criteria move in the phase that removes their tests, not in a closing docs phase.** cr-operating-mode-retires
planned "coverage files" for its last phase; its backend phase deleted the mode tests the refs named, so it had to
rewrite the criteria then, and retag the e2e specs whose criterion tags `validate_stack_criterion_tags.py` reads. A
phase plan puts each criterion beside the test that answers it.

**Where a CR may not name its use-cases yet, a phase owns writing them.** A new application cannot name use-cases
before its concept settles, so `cr-files-application` started with `satisfies: []` and *"written with F1"* — and
F1, F2 and F3 each closed saying they were not written; fourteen phases later the harvest wrote them from the tests,
which inverts what a criterion is for. So the waiver names the phase: the first user-facing phase writes the
use-cases with `status: planned` coverage, and every later phase turns its own gaps into refs.

**A criterion belongs to the use-case that OWNS the property, not to the one that triggers it — and
`satisfies:` will quietly answer the wrong question.** The refine session names one or two use-cases, the
coverage file is then written under them by default, and nobody asks whether that is where a reader would
look. `cr-database-driven-tenant-ingress` `satisfies:`-ed *create-tenant*, so *"the tenant's domain actually
resolves"* landed there — a property caused by five different operations (create, change-domain, revoke,
suspend, reactivate) and owned by none of them, which showed up immediately as a **second, duplicate** copy of
the same refs under *suspend-tenant*. Two use-cases restating one claim is the tell; the fix was a use-case
for the property itself, with the neighbours keeping their operations and pointing at it. So ask it once,
during refinement, where it is a sentence: **does the criterion describe what this operation *does*, or a
property the system *has*?** A property with more than one trigger wants its own use-case — including its own
failure modes, which is the half that has nowhere else to live: *"no reverse proxy at all"*, *"a `Host` no
tenant claims"*, *"DNS that never pointed here"* fit under no single operation and would simply have gone
unwritten.

### A phase that supersedes prose names the SHAPE of the correction, not only the sentence

Edited, narrowed, or deleted — say which. A phase that names one false sentence *reads* as precise while
saying nothing about the paragraph around it that the same work falsifies wholesale:
`cr-pairing-add-a-device-web`'s phase 3 said the one mislabelled sentence is relabelled and the coverage gap
"otherwise narrowed", directly above fifteen lines of blocker narrative that this very phase existed to
remove. Deleting them was right — § *deleting beats reformatting* in
[`../docs/ai/README.md`](../docs/ai/README.md), and the corrected reason already lived in its own inbox row —
but it was a judgement call about **durable state**, taken at implement time, which is the most expensive
place to take one. One word in the phase removes it.

**And name every SITE, which needs its own locator: a claim is prose, so no inventory row keyed on a symbol
will ever find it.** The rule above fixes *how* a sentence is corrected; this one is *how many* copies there
were. `cr-pairing-qr-sign-in-web`'s phase 1 listed the two schema descriptions carrying *"the answer is a key
**or** a commitment"*; the same sentence also sat in the operation description directly above them, in the
entity's column KDoc, and in the guard's own `EXPECTED` comment — **five copies, two named**. The locator had
asked *"which schemas change?"*, which is the right question for a shape change and the wrong one for a
correction. So: **when a phase supersedes a claim, `grep -rn` a distinctive FRAGMENT of the claim across the
WHOLE tree before writing the site list** — not an enumerated set of directories, which is a list that drifts:
the retired *"always name the compose project on the command line"* rule had sites in `infra/`, in `wiki/` and
in a feature `_index.md`, and a locator shaped like the flag (`grep -rn -- '-p veyq'`) found three of six.
Two things fall out of that one command that nothing else produces: the true count, and — because `scripts/` is
swept too — any collision
with a guard the CR forbids editing, surfaced at refine time instead of as a blocking question at implement
time. **Re-run that sweep at the phase, not only at refine: a CR that both writes new prose and
deletes old prose changes its own corpus.** The site list is measured before the earlier phases exist, so a
phase that *authors* something — a guard docstring, an ADR, a comment — can add a site to the very claim a
later phase supersedes. `cr-blob-reference-arity-guard` did exactly that: its phase 2 wrote a guard whose
docstring cited *"the sweep ADR 0013 §3 promised"*, and its phase 4 deleted that promise from ADR 0013 — a
dangling citation authored by the same CR, three commits apart, invisible to a sweep run before either
existed. The handed-over site list is a floor, not the set. (That collision is also § *sixth tell*'s corollary met from the other side: the CR's done-when said the
guard must stay *"unedited"* where its own inventory row gave the intent, *"a phase editing it has changed a
wire shape"*. A comment changes no shape. **State the intent, not the letter** — an absolute reading collides
with the very correction the CR mandates.)

**And the locator finds other FILES; the owning file is read whole.** A fragment grep is built from the words
of the sentence you already know is wrong, so it is blind to the same claim said differently — and the place a
claim is most likely to be restated in different words is the document that owns it.
`cr-store-own-address-signing-mode`'s locator (`swaps`, `same-origin`, `plays no part in signing`) found both
sites it named, in two ADRs and the use-case's A5, and missed two more in that same use-case: a success
postcondition asserting the URL is *"signed **for that domain**"* and a plain-language paragraph promising
media *"come from that same address"* — the identical claim, sharing not one word with the locator. So a phase
that supersedes a claim reads its owning use-case or ADR **end to end**, including the bilingual reader-facing
section, and uses the sweep for everything outside it.

**A listed invariant with no phase behind it reads as covered.** The same CR's front-matter named
*"`src/web/CLAUDE.md`'s jsQR exception holds only while our own codes stay low-version"* and no phase touched that
file. The stated invariant held — the new payload is QR version 4 — but the exception's prose **enumerates the
markers**, so a third marker falsified it silently while the named bound stayed true. An invariant list is a
list of things that can break; each entry owes either a phase or one sentence saying why nothing can reach it.

### The trust-chain guards stay green

*Every* deterministic check in `scripts/` gates a CR — all of them, not
a remembered subset: `for f in scripts/validate_*.py; do python3 "$f"; done`. The glob is the point: it cannot
go stale. CI runs that same glob, unfiltered, in
[`../.github/workflows/trust-chain.yml`](../.github/workflows/trust-chain.yml) — all of it but
`validate_guard_gaps.py`, which runs beside it as its own job (below). **`scripts/` is the list**, and
no file restates it, because a restated list drifts out of step with the one that runs. This file's own
*"point at a canonical source, don't restate it"*, applied to itself. A red guard means the CR isn't done.
(The glob sees files, not commits: CI runs it over the **committed** tree, so an *untracked* guard — or an
untracked file a guard would have rejected — is green locally and unchecked in CI. Commit before believing a
local green.) One guard runs on its own trigger: `validate_guard_gaps.py` tests the guards, not the commit, so
locally it prints `SKIPPED` while nothing under `scripts/` or the trust-chain skill changed since the checkout's last
green full run, and in CI it runs in full as `trust-chain.yml`'s `guard-gaps` job when a push or pull request
changes those same inputs, and nightly on `main` ([ADR 0091](../docs/decisions/0091-one-pipeline-per-commit-a-release-promotes-a-tested-snapshot.md)
decision 4). Neither sees a forgery's anchor moved outside them — its docstring says so; that is why a change that
renames what a guard anchors on runs it with `--force`.

**In a wave of parallel CRs the glob gates the MERGED tree, once — not each branch.** A branch is not the tree
that lands, so its green says nothing about the merge, and the price of running it anyway is real: on 2026-09-24
eight sessions each ran the whole glob at once, and `validate_guard_gaps.py` alone took over 20 minutes in one of
them. So a wave session runs the guards its own Done-when names (a coverage edit runs `validate_coverage.py`), and
the coordinating session runs the whole glob after merging the wave, before it calls the wave done. A red there
goes back to the CR that caused it. So a CR refined for a wave **names those guards in its done-when**:
`cr-wrap-methods-survivor-whitelist` said "every `scripts/validate_*.py`", and its session duly ran a second
`validate_guard_gaps.py` beside a neighbour's while a web test timed out under the load.

**A rename in a wave reaches sibling CRs it must not edit.** `cr-doc-citations-resolve-from-code` renamed a
guard that three CRs in flight in the same wave named in their done-when. Repointing them from its own branch
would have turned each sibling's checkbox ticks on that very line into a text conflict, and the sibling's own
close into a modify/delete. So the renaming CR still greps `changes/` (§ the locator), but it routes what it
finds instead of editing it: it leaves live sibling CRs alone, names them in its hand-off, and the coordinating
session repoints them after the merge.

**And a clean merge of a wave is not a semantic merge where a CR CLASSIFIES what the server answers.** A
predicate over statuses or error codes meets a neighbour's new code only in the merged tree, and no text conflict
points there. `cr-one-sign-out-path` ended a session on any authoritative 4xx; `cr-scope-access-gate`, in the same
wave, made a suspended tenant's domain answer every `/api` call `403 tenant_suspended` without touching sessions —
so the merged client would have signed a member out, and called an unrevoked family "reached", for a refusal about
the tenant. Found only by re-reading the merged predicate. So a CR that classifies responses re-reads that
classification against the codes the merge brought in (`git diff <before>..main -- contracts/ | grep -i code`)
before it lands.

**The same holds for a CR's own phases run as a wave: a phase's open points are re-read against the phases merged
before it.** `cr-files-application` F1b reported core's `upload.noSpace` as still photo-worded, an open point it had
from B3's notes, while P4d, merged first, had already reworded it; a phase brief is a snapshot of the CR at dispatch,
so its "still open" list is checked against the merged tree before it is written down.

**Which makes "can I commit at all?" a precondition of the first phase, not a question for the first commit.**
A phased CR ends each phase with the repo in a verifiable state, and *verifiable* is the committed tree — so a
session that cannot commit is a session holding that rule open across every phase it finishes.
`cr-account-key-rotation-after-compromise` lost the signing agent to an idle-lock after its contracts phase and
carried four more phases uncommitted; nothing was lost, and every green in that window was weaker than it read.
Confirm the commit path works **before** phase 1 — it is one throwaway commit, and the alternative is
discovering it at the point where you have the most to lose.
**Where a CR's done-when says "green in CI", know which jobs can even run.** Two independent gates, and both bit
during cr-e2e-orchestrator:
- **Branch filters.** [`checks.yml`](../.github/workflows/checks.yml) triggers only on `main`, `release/**` and PRs
  *targeting* them, so pushing a feature branch runs **nothing**. That CR's Phase 5 said "push the branch so the job
  runs"; the push produced zero runs. A pull request is how a branch gets a real run: the one dispatch of the
  pipeline is `publish.yml`'s, the **publish** (ADR 0091), and its version step refuses a dispatch on any branch but
  `main` and `release/<major>.x`.
- **`workflow_dispatch` resolves against the DEFAULT branch.** A workflow file that exists only on the feature
  branch cannot be dispatched at all — `HTTP 404: not found on the default branch`. This once described
  `trust-chain.yml`, `mutation*.yml` and `wiki-sync.yml`, which lived on a feature branch and not on `main`; that
  is **no longer true** (measured 2026-08-17: `git branch -a` → `main` alone, and all seven workflow files are on
  it). Keep the gate written down anyway — it is a property of `workflow_dispatch`, not of one branch, and the
  next long-lived branch meets it again.
- **A third gate now outranks both, and it is the one in force: there is no remote.** `git remote -v` is empty
  (measured 2026-08-17), so **no CI run has executed in this repository** — not the trust chain, not `ci.yml`,
  not `web-e2e`. (The runs that admitted backend's, web's and web-e2e's rows predate its 2026-08-06 import, so a
  test in one of those jobs still counts on its declared run; which rows that leaves admitted, and why `crypto`
  and `admin-web` are not among them, is ADR 0040's note on what a row is.) No branch filter and no dispatch
  trick changes that; only publishing the repository does, which is the owner's
  call and tracked as inbox **#26** item (1). Until then, a local run over the **committed** tree is the
  strongest available proof — `python3 scripts/run_pipeline.py` runs the pipeline's jobs on a commit and names every
  job and step it did not run — and a done-when must say exactly that rather than "green in CI". The two gates
  above stay because they are what bites *after* the remote exists.
- **With no remote, a check only one CI job runs is a check nobody runs — so a done-when names that job.** The
  surface checks and the glob do not cover the pipeline's `web-e2e`, and that job carries more than the API suites: the
  tool self-tests of the backup, `veyqctl` and the upgrade proof, which read the backend's migrations and
  `scripts/`. On 2026-10-16 two migrations that seed rows each landed green past `./gradlew check` and the glob, and
  each made every restore onto a fresh install refuse; the first `run_pipeline.py --job web-e2e` was red in its
  backup self-test within 1.5 minutes (cr-one-pipeline-per-commit's close). A CR whose change reaches what such a
  job reads runs that job (`run_pipeline.py --job <job>`, which pins the shared compose stack's infra directory, so a
  worktree may run it) and says so in its record.

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

**A guard can anchor on a symbol's SHAPE, and a rename moves it out of the shape.** Grep `scripts/` for the pattern a
guard matches as well as for the literal. `validate_write_concurrency.py` found a write's counter by the regex `…Version`;
inbox #437 renamed `metaVersion` / `placementVersion` to `…Revision`, and the guard, run on the renamed contract, went red
on `removeNodeCover` (a write that only its counter makes a subject) and would have passed an undeclared `placementRevision`.
A literal grep for the old names finds nothing in that guard, so only running it on the renamed tree shows the break.

**The mutation manifests anchor on FILES, and a green gate does not say you broke one.** `src/web/stryker.mutate.json`,
`src/packages/crypto/stryker.mutate.json` and `src/backend/core/pitest.scope.json` admit a file only while it is pure.
`cr-meta-writes-carry-the-version-they-read` put a PATCH into `nodeMeta.ts`, which is in the web scope; the gate
(break 95) stayed green at 98.7 % while that file fell from 100 % to 96 %. So the inventory greps the three manifests for
every file a CR edits, and a file in one keeps its I/O next door.

### A move re-points its readers first, on the layout it is about to leave

**A guard that globs is layout-independent, so rewire it before anything moves, in a commit of its own that is green
on the untouched tree** — then a green after the move is the move's, not a reader that stopped seeing it. The
platform module split did this three times (the backend tree helper before the package rename, the spec helper
before the contracts split, the test homes before the guards that judge them), and the one reader it skipped — a
hard-coded fallback directory the rename rewrote to the new path — stayed green when re-pointed at the old one, the
exact hole this order exists to close. The same order holds for the last step of a series: the move lands green
first, and only then do the guards that judge its result change.

**A directory move's locator resolves specifiers; a grep for the path finds only the readers that spell it.** Moving
`src/web/src/features/memory` (inbox #324), `git grep features/memory` found 600 readers and missed 29: imports from a
sibling feature (`'../memory/memoryNodes'`, `'../../memory/api'`) never spell the parent directory, and only `vue-tsc`
named them. Resolve every relative specifier against its file and test where it lands — the way
`validate_web_core_imports_no_applications.py` reads an edge — and a docs tree that shares the name
(`docs/features/memory/`) is set apart by its prefix, not by hand. A Python module spells even less: `import
instance_backup` resolves through `sys.path`, which holds the running script's own directory, so cr-tools-under-src's
locator for the file names missed three importers in `scripts/` that only a run after the move would have broken.
Moving a `.py` file greps `^\s*(import|from) <module>` as well.

**Before its hits are classified, a move's locator sorts the files by how each is consumed.** A file that *ships
alone* cannot import a sibling the move adds: `cr-repo-layout-infra-and-tests` switched every script to one
`repo_layout.py`, and `instance_backup.py` runs from the tools image's `/opt/veyq/` and as a release asset, where that
module is not — every in-repo check would have stayed green over a broken `backup-gate`. A *checksummed* file cannot be
edited at all: a comment in the core baseline migration names the dev compose file, and rewriting it reddened
`validate_migration_versions.py` (`db/migration/` is out of every move's locator). And a link a guard resolves breaks
in the phase that moves its target, so that phase re-points it, not a later docs phase.

**After the `git mv`, diff `git status --short --ignored` against the listing taken before it.** A relative path
that misses does not always fail — it can *create*: `cr-repo-layout-src` moved `web/` one level deeper, and
`openapitools.json`'s `storageDir: ../node_modules/.openapi-generator` quietly downloaded the generator into a new,
ignored `src/node_modules/` while every check stayed green. A new ignored directory is that miss's only signature. And
a locator that filters the moved prefix out of its hits must filter the *match*, not the line: one that drops every
line under `src/` cannot see the moved trees' own `CLAUDE.md` and comments.

### Promote one-shot proofs to permanent guards

When a CR's correctness rests on a structural invariant it
proves with a one-shot check — a `grep` that "X is no longer duplicated", "Y lives only in `shared/`" — that
check is added as a **permanent guard** (a `scripts/` validator step or CI grep) so the invariant can't
silently regress. The tell that this was needed: an audit finding for a rule that already existed *in prose* but
nothing enforced (e.g. the styleguide mandated `@ConfigMapping`, yet 28 sites drifted). If a one-shot check
genuinely suffices, the CR says why.

**A lint door names a rule, and in flat config a rule name is a slot, not a stack.** The last block that matches
a file and sets a rule replaces every earlier block's options for it. cr-argon-worker-proven-in-the-build
specified its door as `no-restricted-syntax` + `no-restricted-imports`, and `src/web/eslint.config.js` already spends
both names (and `@typescript-eslint/no-restricted-imports`) on three other doors over the same files: as
written, adding it would have silently switched one of them off. Before a CR names the rule a door runs under,
`grep -n "<rule-name>" */eslint.config.js`; when the name is taken, the door gets a rule of its own
(`src/packages/crypto/eslint-argon-door.js` is the worked example).

### A proof about WHERE a thing lives cannot be a grep for WHAT it says

**The corollary of the section above, one step earlier: before a one-shot proof is promoted, check that it
measures its own subject.** `cr-terms-editor-lists-the-languages-that-have-terms` rested on *"one locale-markdown
editor exists, and it lives in `shared/ui/`"* and handed over
`grep -rln '<the component name>' src/web/src/features/ --include=*.vue` as the proof, with the comment *"imports
only, never a definition"*. Run against the untouched tree — the tree where the component **was** in a feature —
it returned exactly one path: the **importer**. It never named the definition, and could not: a component file
does not contain its own name, because the name is the filename. The proof was green on the defective tree and
would have been green on every tree, which is the failure § *the UNTOUCHED tree* is built to catch, arriving a
step earlier than that section expects it.

The general form: **when a claim is about a file's identity or location, the instrument is the filesystem
(`rglob`, a basename match, a resolved import path), never a content grep** — and the tell is a proof whose
subject is a *place* while its predicate is a *string*. The same trap has an inverse, already recorded one
section down: a guard whose subject *is* text must ask whether its own source is in its corpus.

### A guard derives its anchor set from the code; it does not list it

**§ *the locator, not the number* applied to a guard's own subject.** A guard that names *what* to check —
"these five call sites", "these three columns" — is only ever as complete as the day it was written, and the
case it will miss is the one added after it: a sixth site is not a broken assertion, it is an assertion nobody
made. `validate_tenant_ingress_triggers.py` was specified as *"grep `TenantHandler` for each of the five
mutating methods"* and is instead written as *"a routability-changing store method is one whose body creates,
deletes, or `UPDATE`s the `domain`/`status` column — find them in `TenantStore`, then require each caller to
reproject"*. Both bite identically on a removed call; only the second bites on a **newly added** store method,
which was verified by forging one. Where a set genuinely cannot be derived, listing it is fine — but then the
guard says so under its **limit**, because "this list is hand-maintained" is exactly what a reader needs to know
before adding the sixth thing.

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

**A sensor that widens from a record's END STATE to its HISTORY must port the old sensor's scope, not only its
needles.** `cr-wire-assertion-history-and-mail` reused the log sweep's needle set for MariaDB's statement log and
marked the log's shape *remembered, measure first* — which was right, and still did not foresee the finding: the
dump sweep was quiet only because it searched `INSERT` lines, and a statement log is mostly SQL the code wrote
(`set autocommit=0` 380 times in one suite, migration comments carrying `range`), against canaries `auto` and
`range`. Searched whole, it would have been red on arrival. The fix was the history's analogue of the old anchor
— literals only. Before reusing a matcher on a wider record, ask what the old record's scope silently excluded.

**A guard that parses its corpus refuses what does not parse — it never falls back to reading lines.** The first
YAML-reading guard over every workflow (`validate_action_pins.py`) found on its first run that `mutation.yml`
did not load at all — a step name holding an unquoted `: ` — so that workflow could not have run. Two guards
already read every workflow and both stepped around it in silence: `validate_image_pins.py` fell back to a line
regex (written for Helm templates), and `validate_coverage.py` skipped it (rightly for its own question — a
workflow that does not parse runs nothing — but without saying so); both now name it and go red. A fallback written for one kind of file is a
silent pass for every broken file of another kind; make a parse failure red unless the file is one the fallback
was written for, and where skipping is right, say so in the output.

### A guard that scans for a literal must not spell that literal in its own documentation

**Its own file is part of what it scans.** `validate_release_identity.py` gained an assertion that no file of
durable state instructs a retired compose flag, and its first run on a healthy tree came back red naming *itself*
— twice, in the docstring paragraph explaining the flag and in the comment above the regex. The repair that
suggests itself is the wrong one: excluding the guard's own file puts a hole in exactly the place a future
instruction could hide, and it is invisible from the outside because the guard still reports green. Describe the
literal instead of writing it, and build the pattern from the value the guard already read (`re.escape(name)`)
rather than typing it, so the code cannot become a copy of what it forbids. The general form: **whenever a
predicate's subject is text, ask whether the predicate's own source is in its corpus** — the answer is yes for
every `scripts/` guard, because `git ls-files` and an `rglob` over the repo both return it.

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
Snapshot-restore is the interactive one-shot; a copy is its automated form. **Where a live dev server serves the tree,
the one-shot bites a copy too:** an injection into a file the owner's Vite serves hot-reloads every mutant into their
open page, so the lever goes into a copy beside the file, run by a copy of its test (2026-11-09, five search bites).
**Batching levers into one run is sound only across disjoint code paths AND disjoint tests:** a test stops at its first
failed assertion, so two levers that redden the same test leave the second unproven (`cr-queued-rotation-owners-only-no-leased-join`
paired "derive from the oldest job" with "drop the re-queue's state gate", and the first answered for both).

**First check the lever is wired to the mechanism at all.** Both traps below assume the lever *reaches* the code
path; a CR that specifies a mechanism and, separately, the bite that must break it can name two things that never
touch. cr-email-member-invite-mail-only chose `up -d --wait mailpit` for readiness and a bite that repointed the
orchestrator's Mailpit **URL constant** at a dead port — but `--wait` probes the container's own healthcheck from
inside the container and never reads that constant, so the injection would have produced a **green run**: the
exact failure the bite exists to detect, one level up. Write the mechanism and its bite next to each other and
ask *does turning this knob change that code path?* Where the answer is no, one of the two is wrong — there it
was both, and keeping each for its own job (the healthcheck wait for readiness, a bounded poll for the address
the tool actually exports) is what made the constant load-bearing.

**Asking is not enough where the lever is a command — RUN IT ONCE, at refine, and paste the output in.** The
check above is a thinking check, and thinking is exactly what fails when the lever's behaviour is a property of
a tool rather than of this repository. A CR that measured its defect with three correct greps then specified a
rehearsal that would *bring the dev stack up under a second compose project* and *capture against it*, and all
three of that plan's load-bearing beliefs were wrong: the dev compose pins `container_name:` so it cannot come
up twice, `capture` refuses a stack that holds no tenant so a running stack is not an instance, and the
predicted red does not occur on a developer's host at all — there the production capture **succeeds** against
the dev stack, which is the worse defect and the opposite verdict. Every one of the three was a two-second
command away, and none of them was reachable by reading the compose files. So: where a phase's lever is a
shell command, the refine runs **its first invocation** — not the rehearsal, the one call the plan hangs on —
and the CR carries that output. The cost is seconds; the alternative is redesigning the phase at implement
time, which is the most expensive place there is to take a design decision.

**And check the lever survives the tool.** An injection is only an injection if the thing under test cannot undo
it. cr-e2e-orchestrator planned *"store absent — stop the garage container"*; the orchestrator's own
`docker compose up -d` restarts a stopped container, so that injection silently becomes a green run — correct
behaviour in the tool, useless as a lever. Drive the **configuration** it reads instead (a blank endpoint aborted
the boot, a dead one failed the PUT: two distinct injections, two distinct messages). Before injecting, ask what
the tool does to repair itself, because that is exactly what it will do to your test case.

**And check the build compiled the injection.** Gradle can repair a lever too, by never seeing it:
cr-config-secret-state-on-admin-channel's second bite printed `compileKotlin UP-TO-DATE` and `test UP-TO-DATE` after
the test file had been edited, because Gradle's file-system watching missed an edit in a tree several sessions
write at once. The green it reported was the previous run's. Run a bite with `--no-watch-fs`, and read the task
lines before the verdict: an injected file whose compile task says `UP-TO-DATE` was not run.

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

**And a property held in two layers is bitten in both at once.** cr-expenses-receipts' purge keeps a receipt that is
not yet due twice: the candidates' query leaves it out, and the purge judges again under the locks it then takes (so a
restore that committed meanwhile is seen). Sabotaging either layer alone stayed green — the other one held, which is
the design working and a bite proving nothing. Bite each layer alone and say that it stays green and why, then both
together and see it red by name; a single-layer green read as "the test is weak" sends the fix to the wrong place.

### A CR that calls an existing test too weak proves it with an injection BOTH tests see

**Otherwise "proven by nothing" is prose, and prose about a test cannot be wrong out loud.** A CR whose
justification is *"the check that exists does not cover the half that matters"* has made a claim about code that
is already green, which is the one kind of claim no run contradicts — the suite passes either way, before and
after. The form that settles it is a single injection of the defect, run against the old case and the new one
together: the new test goes red **by name** and the old one stays **green**, which is the claim, demonstrated.
`cr-abandoned-upload-session-reclaim` argued that `MediaTrashPurge`'s object delete was asserted by nothing;
dropping that delete reddened only the retro-fitted case, while `purgeHardDeletesTrashedPastRetention` passed
untouched — because it calls the store method and reads the keys it *returns*, so it proves they were computed
and never that they arrived. Note the shape: this is the *green* half doing the work, and § *a bite test must
tell "the guard held" apart from "nothing ran"* is its mirror — there an unexpected green is the failure, here
the **named** green is the evidence. Compare against the named other case, never against "nothing else broke".

**And the depth is not overhead at this size.** Three injections over a two-class sweep — the positive, the
window control, the retro-fit — cost less than deciding whether they were warranted. Where a CR's own argument
rests on which tests bite, bite them.

### The CR says PER bite-test how it will be bitten — live defect or injection, and with which lever

**"Write these RED first" is not a method, and for some of them it is not even possible.** A CR that lists its
bite-tests has decided *what* must be proven and left *how* to the implementer — who discovers, one test at a
time and at the most expensive moment, that the list is two different kinds of test wearing one heading. Both
kinds are covered by the two sections below; the CR's job is to say which one each test takes:

- **Live defect** — the test asserts behaviour the fix introduces, so it reddens on the untouched tree and the
  ordering below applies. Free, and the strongest kind.
- **Injection** — the test pins a deliberate *non*-change (a read that must keep its old shape, a traversal that
  must not skip). Nothing in the CR's own diff makes it red, so it is green from the first run and § *bite-proof
  by snapshot-and-restore* applies. **Name the lever in the CR**, because a bite whose lever the author never
  chose is a bite the implementer improvises under time pressure — and § *upstream of every trap* is a list of
  the ways an improvised lever misses.

- **Already live** — the CR claims the assertion *exists*, and the phase only has to prove it still bites from a
  new call site or mount point. That is the cheapest of the three and the only one that can be **false**: it is a
  claim about a file the author did not open. So it names the test **case**, or the grep for its lever
  (`rg 'mockRejected' <file>`) — never the file. cr-devices-and-sessions-one-rubric said a catch-path fallback
  was "live today in" the device panel's test file; that file had five cases and not one of them made the read
  fail, so the bite the CR had budgeted zero effort for was the one that had to be written from scratch. A
  file-level citation is a *remembered* claim wearing a measured one's clothes.

cr-node-delete-reversible-trash paid for both halves of this in one session. Its six bite-tests were four live
defects and two injections, unmarked — so one injection-shaped test was run against the untouched tree, came back
green, and had to be re-planned. A third failed differently: it was a live defect whose **setup** depended on a
fix scheduled later in the same phase, so its first red landed on a `createNode` in the arrangement rather than
on the assertion (§ *a live-defect red can land on the wrong STEP*, below). Marking the method also orders the
phase, which is the second thing it buys: the live defects run first, against the least-changed tree there will
ever be.

And the lever exists in the API the same CR defines: cr-webp-encoder-from-libwebp bit its size test "at quality
1.0 lossless" on an encoder whose API has no lossless mode, and asked for "the decoded size" in a directory with no
decoder.

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

**And the red is taken through a seam the UNFIXED tree has.** A CR that names its bite's lever in the fix's own
terms — `cr-request-tenant-and-tenant-bound-recovery` said "the direct delivery call", a method the fix splits out —
names something that does not exist where the red must be recorded. Record the red through the old seam (there, the
request method that still ran the delivery inline), re-point the test once the fix creates the new one, and re-bite
it by injection on the fixed tree; a CR can save that detour by naming the lever for both trees.
**The unfixed tree must also compile.** A contracts phase that adds an operation to an existing tag leaves the
generated `…Api` interface unimplemented, so no backend test runs until the resource implements it:
cr-share-link-storage-limit ordered its new endpoints *after* the live-defect red, and the red could only be
taken once they existed. Order the endpoints that only make the tree compile before the red.

**But a live-defect red can land on the wrong STEP, and then it is not a bite either.** § *a bite test must tell
"the guard held" apart from "nothing ran"* catches an injection that breaks compilation instead of the assertion;
this is the same failure arriving through the **ordering**. Where several fixes land in sequence, a test staged
before an *earlier* one fails in its own setup — cr-node-delete-reversible-trash's "restore into a taken name is a
clean rejection" ran before the slug-freeing write existed, so its second `createNode` answered `409` and the run
was scored red on a step the test does not exist to check. The tell is purely mechanical and costs one line:
**read which assertion reddened, not that the test reddened** — a red on a setup line is an unbitten test wearing
a bitten one's clothes, and it is re-bitten by injection once the earlier fix lands.

**The same mechanism also fires one level in, between two assertions of the same test, and there it is harmless
but misleading.** cr-gallery-one-order-for-every-tile predicted its live defect would redden on the *position*
of a settled upload tile; it reddened on the *order* assertion two lines above, because the tile order was
already wrong before anything settled, so the position was never reached. Nothing was wrong with the test — but
the CR's prediction and the run disagreed, and only reading the failure told which. The cause is a mismatch of
altitude: the phases had been written from the code's **shape** (two lists concatenated) while the bite was
described by its **purpose** (the slot after a reconcile), and those are different assertions. So when a bite
carries assertions in sequence, **the CR names which one is expected to fail first**. It costs a clause, it is
checked for free on the first run, and a prediction that misses is worth more than one that was never made.

### Bite a new RULE against its own dynamics, not only against the state it will read

A decision that changes how the system answers is bitten in two places, and the second is easy to miss. The
first is its **input**: which cases land on which branch, measured against the untouched tree. The second is
what the rule **does to the state it reads** — because after it fires once, the next caller sees a world the
rule itself moved.

ADR 0004's one-generation grace was decided on four measurements of the first kind, all correct: a dropped
response and five concurrent losers land one generation behind, an ordinary replay lands three behind. It was
then written as *"rotate and supersede the orphaned successor"*, which increments — and the third concurrent
caller of the very case it was built for would have been two behind and revoked the family. Nothing in the
four bites could see it: they measured distances the rule had not yet touched.

The tell is a rule that both **reads** a piece of state and **writes** it. Trace it twice — once for the
second caller, once for the party it just orphaned — before it is written down, because after it ships those
two are a security property. Cheap here: it was a paper trace over measurements already in hand, and it cost
one ADR commit instead of an implementation round.

### A bite's lever must survive the CR's own later phases

**A CR that fixes a defect and also pins a property against that defect will, if it is not careful, lever the
second on the first — and then its own completion disarms the bite.** The check is one question at refine, asked
per bite-test: *after which phase does this lever stop being pullable?* An answer naming a phase inside the same
CR is the fault, and it is invisible from the phase being written, because there the bite is genuinely red.

`cr-scope-erasure-fk-closure` staged the tick-isolation bite (*"one scope that cannot be erased must not stop the
others"*) on a scope carrying a job row, which was exactly the FK gap its next two phases closed. Two phases
later nothing could poison a scope any more — by construction, since a guard now enforces that — so the test
would have degenerated into *"two erasable scopes both get erased"*, and its done-when (*"the poisoned one is
**not** erased"*) would have gone red on a correct tree. The property is permanent; the lever had a two-phase
life. The repair is to lever on a failure the CR does **not** remove — there, a store that refuses, which is
also the realistic residual failure once the schema can no longer produce one.

Note what this is *not*. § *a bite test you cannot make red* is about a lever that never worked; § *a live-defect
red can land on the wrong STEP* is about ordering **within** the run. This one passes both: the lever works, the
red lands correctly, and the bite still dies — of the fix, three phases later, silently and green.

### A test the CR CITES as evidence is a test the CR can disarm

**The rule above asks whether a *new* bite survives the CR's own phases. This one asks it of the tests that are
already there — and it bites hardest on the one test the CR names approvingly.** A CR that writes *"the seam we
need already exists, it is used at X"* has cited X as proof the mechanism works, which is exactly the shape
§ *a SETTLED decision* describes: a sentence that invites the reader to move on. Nothing then asks whether the
CR's own decisions leave X able to run.

`cr-store-object-reclaim-list` did this in one document. Its constraints said the refusing-store seam *"is
already used this way at `ScopesSliceTest` (the poisoned-scope isolation case). This CR does not need a new
seam."* Its Decision 3 removed `blobStore.delete` from `ScopePurge.eraseScope` — the only thing that test could
poison, as that test's own docstring says in so many words. The cited proof and the decision that voided it sat
four paragraphs apart, and the CR's inventory never greps the **test** tree (§ the locator, which already
requires it), so nothing brought them together. It surfaced at implement time as a red on a correct tree, and
the repair was the new seam the CR had declared unnecessary.

Note what makes this different from a test that merely needs updating. A test whose *assertion* the CR changes
goes red and says why. A test whose *lever* the CR removes goes red on an unrelated line, reads as a regression,
and invites the wrong fix — deleting the case, or weakening it into the tautology it has become. So: **for every
existing test a CR cites, and every existing test that injects a failure into a path the CR touches, say in the
CR whether its lever survives** — and where it does not, the CR owns the replacement, because the property is
almost always permanent while the lever was only ever the most convenient thing that could fail. That one was on
its *second* lever: the FK gap first (closed by `cr-scope-erasure-fk-closure`), then the store delete. Levers
move to wherever the fallible work went; the property does not move at all.

### A bite test you cannot make red is telling you the design is not the one the doc describes

**Reach for the design before reaching for a stronger injection.** The reflex when a new test stays green under
every lever is that the lever is too weak. Sometimes it is; sometimes the property has **no observable
consequence under the implementation you just wrote**, and the sentence you are pinning quietly assumed a
different one. cr-node-delete-reversible-trash had to pin *"the delete's traversal must not skip already-deleted
descendants"*. Its first implementation left an already-trashed descendant's earlier marker alone — under which
skipping them and reaching them produce **byte-identical** state, so no injection could ever redden it. The
sentence only means something if the delete **re-stamps** the whole subtree, and re-stamping is what makes the
failure real and observable: a subtree ageing out on two timestamps has its inner rows purged first, and the
restore hands back an empty shell. The test did not get stronger; the semantics changed, and only the failed
attempt to bite it surfaced which semantics the use-case had meant all along. So: **an unbiteable test is a
design question, asked in the cheapest possible place**, and it is worth one round of "what *would* this cost, in
what the user sees?" before deciding the lever is at fault.

**An ordering bite is the sharpest case: the lever must make the misplaced check answer differently.**
`cr-scope-access-gate` planned to prove its existence-oracle guard by moving `findNode` ahead of the gate in
`listMedia`. It stayed green, and correctly: a missing node and a non-member both answer that operation's own
`NoNode`, so the reordering discloses nothing and no stranger can tell — the design (a refusal maps onto the
operation's own 404) is what made the lever inert. The bite that reddens is a pre-gate check whose **miss answers
something else**: `restoreGroup`'s kind check, `409 not_group` for an invented id against the gate's `404`. So
before freezing an ordering bite, write the two answers — real id, invented id — side by side; if they are the
same variant, the lever proves nothing.

### A threshold added against false positives is where the false NEGATIVES hide

A sensor that scores something continuous — a length, a ratio, a score — almost always grows a floor, because
without one it cries wolf. The floor is the right move and it creates a blind spot in the one place nobody
looks: **just under it.** And the defect the sensor exists to catch is frequently small, because small is what
"not done properly" looks like.

cr-wire-assertion-store-and-http's opacity check reads a declared-opaque field and asks whether it decodes to
readable text. Short random strings do, now and then — measured, 7 in 5000 at 8 bytes — so it got a floor at
24 bytes, itself measured (0 in 80 000 above it). Then the bite put an **unsealed Memory name** on the wire:
**23 bytes**, one under the floor, and the check never looked at it. The floor was correct and the oracle was
still wrong.

The fix is not a lower floor — that just moves the blind spot and brings the noise back. It is a **second,
orthogonal test whose direction is opposite**: below the floor, *shortness itself* became the evidence, because
nothing legitimate on that wire is short (an AEAD value carries a 12-byte nonce and a 16-byte tag before any
payload, and the measured minimum across every field a real suite sends was 32). Two tests, no gap.

So when a CR introduces a threshold: **write the bite that lands one unit under it**, and if that bite cannot
be made red, the threshold is hiding something. Where the quantity has a structural minimum, that minimum is
usually the second test.

### A named lever that moves nothing is EITHER a design question or an unasserted property — decide which

The section above sends an unbiteable test back to the design. That is one of two answers, and reaching for it
first is a mistake when the other one applies: the lever moved nothing because **nothing in the tree asserts
the property at all**, in which case the bite has just found a coverage hole and closing it is the CR's work.
The two are told apart by one question — *would a correct implementation and a broken one produce different
observable output?* If no, it is the design (above). If yes, and everything is still green, the assertion is
missing.

`cr-admin-web-extraction` named its lever precisely: *"inject a one-character change into the workspace
package's Argon2id parameters and assert both suites go red"*. `t: 3 → t: 4` in `v1.ARGON` left the **entire
tree** green — the crypto package's 44 tests and web's 1018 — because the conformance battery passes the
params the *vectors* carry and `accountBirth`'s KAT mocks the KEKs, so no test anywhere pinned the production
cost. Stryker did not cover it either: it has no numeric-literal mutator, so `t: 3` was never in the mutant set
that scores that file 100 %. A silent Argon2id **downgrade** (t=3 → t=1, m=32 MiB → 64 KiB) was invisible to
every gate the repo owns. The fix was neither a different lever nor a different design: pin `v1.ARGON` against
the contract's own `kdf_params` vector — an agreement check rather than the literal restating itself — after
which the CR's original one-character injection reddens exactly as written. (`dkLen` was the one field that
already bit, and only structurally: a 31-byte AES key throws downstream.)

**The general shape: a lever aimed at a CONSTANT is the one most likely to move nothing**, because a constant
has no behaviour of its own — it is asserted only where some test compares it to an independent source. Before
writing such a bite into a CR, run it; and when it comes back green, ask which of the two answers applies
before touching either the lever or the design.

**A lever against a threshold with a margin is arithmetic, and the last report has already done it.**
cr-crypto-mutation-gate-green's done-when said deleting the identity codec's round trip would take the mutation
gate below its 95 break. It moved nothing: another test covered the same mutants. Deleting the whole file
orphaned 24 mutants and still scored 95.20 %, because 5 points of 458 is 23 mutants and two orphans timed out,
which counts as detected. Count the mutants only that lever covers (`coveredBy` in `mutation.json`) and set
them against the margin before writing the bite down; one that cannot cross is a question about the threshold.

**An ORDER assertion over async work can be satisfied by the event loop instead of the code.**
`cr-upload-windowed-presign` asserted that its upload pump asks for the next presign window *before* the signed
range runs out (`window:10` logged before `put:9`), and removing the look-ahead left it green: every fake answered
at once, so a pump that asked only when it held no URL still reached chunk 10 while chunk 9 sat in its own
`await` of the seal. The order was a property of the microtask queue. What made it bite was a fake that takes
**time** (each PUT held a few ms) and an assertion with room in it (`window:10` before `put:6`, a whole in-flight
of signed chunks still unsent) — then the lever reddened and the fix did not. Where the claim is an order between
concurrent steps, run the lever once before trusting the green; an instant fake orders things for you.
**An OVERLAP assertion gates both sides.** cr-content-digest-off-the-main-thread wanted "the digest runs beside the
derivation" proven by a deriver that resolves only once the digest has started reading. Run the old way (the digest
first, then the derivation), that test is green: the digest has long started when the deriver asks. Only a pair of
gates bites, each side waiting on the other's start (`media.test.ts`, the digest beside the derivation), so a test
that settles proves they ran together. The same CR's abort case asserted "reserves nothing", which the pump's own
abort check already guarantees; what was the CR's to prove was that the upload rejects without waiting for a
derivation that never ends.

**A structured config file has its own way of swallowing a lever: the duplicate key.** Injecting into YAML or
JSON by inserting a block near an anchor can produce a second `properties:` (or any key) beside the real one,
and a loader keeps exactly one of them — `yaml.safe_load` keeps the last. The file looks injected, the diff
looks right, the guard stays green, and the conclusion drawn is *"the guard does not fire"* rather than *"the
injection did not land"*. Check what the **loader** sees, not what the file says: one line
(`python3 -c "import yaml; print(yaml.safe_load(open(f))[...])"`) before believing a green.

**A lever that removes one operand of a union needs a witness only THAT operand selects.** The grant-delivery
backstop picks scopes as *grant-less ∪ rotated*, and the planned fixture put its stale member M4 in a rotated
scope that also held a grant-less member — so the scope was selected through `grantless` anyway, and dropping
`+ rotated` would have left M4 green. Moving the grant-less member out of that scope made the lever bite. When a
CR names such a lever, it names the row that only the removed operand reaches, and says why no other operand does.

### A control's LEVER must itself be bitten — a harness can make one inert and still print green

**A lever you never watched redden is not a control, and it fails in the one direction nobody checks.** The
rules above are about a lever that visibly moves nothing. This is the quieter case: the lever runs, the suite
stays green, and that green is read as *"the control held"* when it in fact means *"the harness cannot express
the defect"*. The two are indistinguishable from the outside, which is why the lever needs its own bite.

`cr-share-picker-inherits-the-safari-rereads` had to pin that a Safari re-read window is not defeated by the
caller clearing its input. Its third bite was a control over the second: *move the `input.value = ''` back into
the synchronous pick, and the first test goes red again.* The first draft of that harness copied the reference
file's `Object.defineProperty(el, 'files', …)`, which installs a **data** property — so a source that clears
`value` synchronously leaves the test's own `files` untouched, the lever changed nothing observable, and the
control passed while proving nothing. The harness had to model what the browser actually does: the picker hands
over a list and goes on filling **that same array**, and `value = ''` swaps a fresh empty one into the element.
Only then did the lever redden both tests together.

**The general form: run each control's lever and watch it work, in the same pass that writes it.** A control
whose lever is inert is indistinguishable from a fix that works — both print green, and the one that is lying
is the one you will trust later. Note what made the gap findable at all: the CR had **named** the lever per bite
test, so there was something concrete to try. A CR that says only "test that it stays fixed" leaves nothing to
falsify, and the inert control is never discovered.

**A control that shares a fixture with a negative is independent only while that negative is refused.** In
`cr-scope-access-gate`'s probe the unaccepted-proposal persona was, on the unfixed tree, *let through* — and it
purged the medium and spent the upload session the same operations' controls then needed, so three controls went
red for a reason that was not theirs, and the same coupling reddened them again under the pending-consent bite
once the fix existed. The fix was structural: the control fires on its **own**, identically seeded space. Where a
live defect or a bite can let a negative write, give the control a fixture the negative cannot reach.

**A lever that moves a secret out of its scope moves it across the code's own wipe — bite both forms.** In
`cr-rotation-states-its-epoch` the lever for *"a retry never reuses a key whose wraps already left the device"* was
*hoist the mint out of the loop*. Hoisted with the per-attempt `finally { newKey.fill(0) }` left in place, the
retry wraps the wiped, all-zero buffer: a key that differs from the first attempt's and chains consistently, so
"the two keys differ" and "the link peels" both stayed green over the worst version of the defect. Only the form
with the wipe moved too reddened them; an assertion that the retry's key is not the zeroed buffer caught both.
Where an injection changes a value's lifetime, the cleanup that bounded the old lifetime is part of the lever.

**An armed-sensor control's predicate is chosen against the lever that must break it, not only against the
surface.** `cr-wire-assertion-server-logs` asked each boot log to "contain its database name" and bit that control
by deleting the `Database:` line — but a restored member's log names its schema on three other Flyway lines, so a
name-anywhere predicate would have stayed met with the lever pulled. The control that bites is the JDBC URL
(`/<name>` followed by a non-identifier character), which only that line carries. Write the control and its lever
side by side and ask whether the lever removes *every* occurrence the predicate accepts.

**On an expensive, machine-wide-serialised run, levers may share one run — each on its own artifact, each naming
its own failure.** The same CR's ten levers would have been ten e2e runs behind a lock other sessions were
queueing on; they ran in four, because each lever touched a different log (or a different call) and the sweep
names file, line and control per finding, so one red carries every verdict separately. Never two levers on one
predicate (the second masks the first), and a lever that must stay green — a measurement of a gap — gets a run
whose only other levers cannot turn it red.

### Each layer of a defence in depth is tested with the other layers held off

**Layers that overlap mask each other's tests, and the mask is the fix working.** `cr-inert-store-responses`
built three layers for one property — a signed PUT type, a signed GET override, a proxy header — and each one,
left on, makes the next one's assertion true by accident. Once every PUT is pinned, every new object is *stored*
as `application/octet-stream`, so an e2e asserting the *served* type passes without the GET override at all:
measured, dropping `responseContentType` from the signer left the freshly uploaded chunk green and reddened only
a chunk the test had first planted as `text/html` through the old host-only signer. The proxy layer was measured
the same way — an unsigned GET of that planted object, so the signer could not answer for the headers, with the
same object read direct from the store as the control — and the playback shim's type gate is asserted in the SW
itself, not only through the page that already filters.

**So for every layer, recreate the state the other layers prevent** — there, an object stored before the rule —
and bite each layer alone. A suite that only tests the stack whole proves the property holds while *any* layer
does, which is exactly what a later cleanup of "the redundant one" will spend without a red.

### An attacker that omits the new field is refused by the parser, not by the check

**When a fix adds an authenticator to a wire value, the bite's attacker must produce the value's NEW shape with
its best forgery in the new field** — otherwise the fixed tree refuses it for being malformed, and the check the
CR exists for never runs. `cr-pairing-authenticated-by-the-qr` specified its envelope bite as *"the relay seals a
grant of its own (a valid seal with no MAC)"*: red on the untouched tree, and after the fix refused as
`envelope_unreadable` — by the length-prefix reader, before any MAC was computed. Deleting the MAC check left
that test green. A second relay, the same seal with a tag under the one key it does hold, is refused as
`envelope_refused` and reddens when the check is disabled. So the CR names, per bite, **which refusal the fixed
tree must answer with**, and the implementer re-bites the fixed tree by disabling the check itself — a relay test
that stays green with the MAC switched off is a parser test.

### A "nothing moved" bite names the test that goes RED when it moves

**The default's tests and the default's *detector* are usually not the same tests**, and a bite that lists the
first reads as thorough while proving nothing. `cr-store-own-address-signing-mode` added a config member
defaulting to today's behaviour and bit it as *"set the new member to its default explicitly and assert the
three existing `S3BlobStorePresigningTest` cases and the four `*PresignDomainSliceTest` cases are all green"*.
Measured, with the default moved to the other constant: **all eleven stayed green**. The unit cases construct
the store directly, so they see a *constructor* default, not the config one; the slice cases run against a fake
store that ignores the mode by construction, so no value of it changes their output. Eleven tests about signing,
zero detectors of the setting.

The tell is the direction the bite is written in: *"assert these stay green"* is satisfied by every test that
cannot see the change, including all of them. Invert it — **name the test whose failure message would print
the moved value, then run it with the value moved.** Here that was a case reading the default through the real
config mapping and driving it into a real store; it reddens, and the eleven still do not. A default is a
*configuration* fact, so its detector lives at the tier that reads configuration, not at the tier that
exercises the behaviour the default selects.

**A "the read wrote nothing" assertion reads the row in a transaction of its own.** Read on a `@QuarkusTest`'s
own thread, outside a transaction, Panache answers from the request-scoped session, which still holds the entity
the test loaded before the call — so a write the call under test made is invisible, and the assertion that exists to
catch it passes on it. cr-config-secret-state-on-admin-channel's "the read adopts no fingerprint" test was green with
the adopting call swapped in until its read moved into `QuarkusTransaction.requiringNew().call { … }`; only then did
the swap redden it, naming the adopted value.

### A before/after snapshot taken inside the test is blind to what STARTUP already did

**The sibling of the rule above, and it survives the inversion.** `cr-tenant-ingress-kubernetes-sink` named
`TenantIngressNoConfigTest` as one of four that had to still observe the code an extraction moved, and wrote the
detector the way the rule says to — an injection that makes an *unconfigured* instance produce a real file sink,
with the test that claims *"the projection is a clean no-op"* as the case that must redden. It did not. The
projection **was** written, on disk, and the test stayed green: its oracle is
`exists(path)` before a `regenerate()` compared to `exists(path)` after, and the `@Observes StartupEvent`
regenerate had already written the file before the test method took its `before` reading. Both readings were
`true`, and the comparison held for the wrong reason.

The tell is an oracle that is a **difference over a window the test opens**, applied to a subject the application
also drives outside that window — a startup observer, a scheduler, another test class in the same JVM. Anything
that happened before the window is invisible by construction, and "before the window" includes the entire boot.
The repair is not a bigger window: it is to assert the **decision** rather than its side effect (here: the
injected sink's own `enabled`), which no ordering can pre-satisfy. Keep the side-effect assertion — it is the
statement of the behaviour — but do not let it be the only thing that can fail.

### A new external object's OPTIONAL knobs are not its MANDATORY fields

**A refine that inventories config for a resource it has not built lists what it was thinking about, which is
the tunable half.** `cr-tenant-ingress-kubernetes-sink` specified the Kubernetes sink's config as *"namespace
override, optional `ingressClassName`"* — both real, both optional. An `Ingress` cannot exist without a backend
`Service` name and port, which are deployment-time names the backend cannot derive, so implement time had to
invent config the CR never mentioned. Nothing was lost here because the gap was small and obvious; it is worth a
rule because the failure is silent at refine time — an inventory reads complete when every line in it is
correct.

The check is one question, asked against the external system's own schema and not against the CR: **could the
object be constructed from exactly the values listed?** Where the answer is no, the missing values are
mandatory, and mandatory values are where the defaults have to be argued (here: the chart's own Service names,
so that an operator who renames it has somewhere to say so).

### A tool's version floor is read against the advisories on the path it guards

**A floor chosen for a format is not yet a floor for the check.** `cr-signed-release-manifest` settled with the owner
that `veyqctl` needs cosign 3, because cosign 3 reads the Sigstore bundle the publish writes. The advisory the Run page
already cited (GHSA-fx35-mq7g-6g98) names cosign 3.0.0–3.1.2 as accepting a forged *legacy*-format bundle without
checking its identity, and cosign 3 still reads that format when it is handed one. A mirror that controls the manifest
controls the bundle beside it, so "cosign 3" would have left the exact hole the CR closed. The floor became 3.1.3, at
implement time. So a refine that names a minimum version of a tool that does the checking reads that tool's
advisories for the code path it guards, and states the floor as the newer of the two answers.

### A library's default is read at the layer that configures it

**The framework between the app and the library may not pass the library's default on.** `cr-kubernetes-backup-gate`
carried, as remembered, that Flyway ignores applied migrations newer than the code — true of Flyway 12.0.0, whose own
default is `*:future`, and false of this app: Quarkus' `FlywayCreator` hands Flyway an empty pattern list unless its own
`ignore-future-migrations` (default `false`) is set, so an older release refuses a newer schema. One `grep` in the
library's sources would have read the wrong layer; the Quarkus extension's sources and one start of an older image
answered it. So a claim about a library's default names the layer the app reaches it through and is measured there —
the extension's config mapping, or a run.

### A handler registered for a supertype answers for every subtype the framework asks about

**"Catch everything" is a registration for every type below it, including the ones the framework looks up to decide
its own routing.** `cr-logging` decided an `ExceptionMapper<Throwable>` beside a Vert.x failure handler. Quarkus REST
asks for a mapper of `NotFoundException` before it passes an unmatched route on, and finds the `Throwable` one by
walking the hierarchy — so the app shell's SPA fallback and the admin channel's unknown-route refusal would have
stopped answering. The trap was already written at `EmptyBadRequestFilter`, two files from the decision's locator,
and the build moved to one failure handler that Quarkus REST reaches through `RoutingContext.fail`. So a refine that
registers a handler for a broad type reads which lookups the framework makes for the types under it (the
framework's sources, at the line that asks), and greps the code for the trap notes beside the layer it adds to.

### Every pod a CR adds is asked again what it reaches and what it writes

**A property argued for one component does not carry to its neighbour.** `cr-kubernetes-backup-gate` settled that the
backup gate needs no NetworkPolicy change because it runs in the app pod, and that the restored key takes its
directory's owner. Its restore Job inherited both conclusions unexamined; the kind run found the Job, a pod of its own,
cut off from the database by the default-deny set, and a claim's directory root-owned (`fsGroup` gives a group, never an
owner), so the owner rule refused the key. Both were stated in ADR 0098's own Context ("a component that dials the
database … needs a policy change"). So a refine lists, for each pod, Job or container it adds, the peers it dials
against the policy set and the owner of every directory it writes, rather than once for the design. **And it greps the
chart's own guards for what the new peer breaks**: `cr-backup-erasure-and-retention` planned a daily CronJob that reads
B from the database, and `scripts/chart_meta_check.py` (xvi) held *"the database's only peer is the app"* — a guard
change the CR never named; the same CronJob mounts the gate's `ReadWriteOnce` claim, so it must run on the app pod's
node (`podAffinity`), which nothing asked either. The same refine had the *gate* read the ledger from the key's claim,
which ADR 0098 decision 4c and (xxiv) forbid; the restore Job, which already mounts that claim, was the pod that needed
it. Name the pod that does each reading, not the subsystem.

### A CR that adds a gate runs it once at refine, against the untouched tree

A new gate's first run is the CR's real inventory, and the decisions it forces are owner questions — so they belong in
the refine, not in the implementation. cr-api-fuzzing refined Schemathesis offline (the specs load, cases generate) and
predicted its findings ("expect undeclared 404s and Quarkus' 405/415"); the first live run, an hour into
implementation, failed on every spec file with ~600 failures by nine mechanisms, four of them decisions about the wire
or the check — `null` vs absent in every response, 405/`OPTIONS` semantics, whether a credential may be refused before
its shape, and an official image that loads a proprietary plugin. The CR stopped at its triage and waited a round trip
for Q4–Q8. A probe run of the gate over three spec files at refine would have put those questions on the table with the
others. So a CR whose deliverable is a gate (a fuzzer, a scanner, a new check over the whole tree) runs that gate once
at refine — whatever form is cheapest that is LIVE, not offline — and asks the decisions its findings raise there.

### A bite test must tell "the guard held" apart from "nothing ran"

**Assert the expected test goes red by name — and for the assertion the bite is about — never that "something failed".**
cr-identity-pins-per-entry bit "the rotation's rollback reaches the pin write" by moving that write into a transaction of
its own (`REQUIRES_NEW`): twenty rotation tests went red, every one on a 500, because the new transaction deadlocked on
the account row the rotation had locked — and the test about the rollback was red on its status line, never reaching
the assertion it exists for. The lever that bit it was returning the conflict without the rollback. Read the failing
assertion's message, not the count. An injection can break compilation instead of the assertion, and a
harness that greps for a failing test name then reads *no match* as **green** reports the exact false negative
**And it reaches the harness a CR uses to PROVE itself, which nothing else in this file guards.** The
trust-chain job runs `for f in scripts/validate_*.py` behind a `count == 0` backstop, for exactly this reason;
a local re-implementation of that loop that omits the backstop prints `failures=0` when its glob matched
nothing, and a CR then cites it as evidence. cr-refresh-one-generation-grace did that twice before noticing,
because the summary line it read — `GUARDS DONE failures=0` — is identical for "34 green" and "none ran". So
any harness standing in for a check **reports what it ran, not only what failed**, and treats an empty run as
a failure. Copy the backstop with the loop.

**The same green-never-earned reaches a test through its own fixture, with no injection involved.** A test that
asserts *"X survived a round-trip"* proves nothing if its setup hands X over a second time on the way through.
cr-device-wrap-join-and-remote-cutoff's first round-trip case wrote the register twice and re-supplied the same
wrap id on the second write, so it passed with the map dropped from `sealRegister` **or** from `unsealRegister` —
the assertion was reading its own fixture back. The rule generalises past round-trips: **write the case so the
step under test supplies neither of the things it asserts**, and bite it once per direction the property can
fail, because a survival claim with two failure modes needs two bites, not one.

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

**But do not spell that check as "the original text is gone."** It is the obvious shape and it is wrong for
every injection that **wraps or appends** rather than replaces — `if (x)` → `if (false && x)`, or a line added
after the anchor. The old text is still there, verbatim, in a file that was patched perfectly, so the harness
reports *"did not land"* and scores a landed injection as a failed bite; `cr-pairing-qr-sign-in-web`'s harness
did this to two of five injections. The check that holds for every injection shape is **the NEW text is present
AND the file's bytes differ from the snapshot** — and the snapshot is already there, because the restore needs
it. Its sibling holds too: when a bite asserts that an injection reddens **one** case and not another, compare
against the *named* other case, never against "nothing else went red" — the injection legitimately reddens
every test that depends on the same branch, and rejecting those turns a correct bite into a false alarm.

**And an order of log lines names the line that proves a step RAN, not a word that step's lines carry.**
cr-client-address-resolver's boot bite asked that a refused trust list be named "before any datasource line"; the
refused boot's first lines were Hibernate's static banner `Database info: … JDBC URL [undefined/unknown]`, printed
before any configuration is read, so the predicate as written was false on the run that proved the point. What it
meant was "before a connection is attempted" — the control's Flyway `openConnection` line. Name that line.

**And a boot bite knows which steps run before the check it bites.** A refusal written as a `StartupEvent` observer
runs after Flyway's migrate-at-start and after route registration (`AppShellRoutes` reads the store setting), and
both reach the database. cr-client-port-sources planned three boots against an absent database, the control failing
"later on the database"; all three failed on the database first and none reached the check. A refusal at configuration
time (a converter, as `trusted-proxies` has) is seen without a database; an observer's needs a reachable one, a
throwaway container of the pinned image.

**And a harness that reads a test's verdict from its result file deletes that file before each injected run.** A run
whose injection broke the build writes no JUnit XML, and the previous run's file — the clean one, green — is still in
`build/test-results`, so a harness reading "the named case's status" scores the last run's verdict as this run's.
cr-statement-legal-gaps' harness deleted the class's `TEST-*.xml` before every run and scored a missing one as
`not-run`, never as a verdict.

### When the CR's gate is a bespoke measurement, bite the MEASUREMENT before trusting its green

**§ *bite-proof a guard* applied one level up.** Every rule around it assumes the gate is `check` or a
`scripts/` guard that somebody already bit. A CR whose real gate is something built for that CR — a probe, a
diff, a rewriter — carries an assertion nobody has ever tested, and a green from it reads exactly like a green
from a gate that works. `cr-delete-the-inherited-stylesheets` had no automatic gate at all (nothing in
`pnpm run check` renders a pixel, and Vite neither purges nor validates CSS), so its gate was a computed-style
fingerprint plus a CSS rewriter. Both were wrong on the first try and both said so only when bitten:

- The **rewriter** was checked by *"every declaration belonging to a surviving selector must still be present
  byte for byte, and the braces must balance"* — run against the untouched tree, then against a copy with the
  deletion applied. It failed on all eight sheets: the deletion span started at the beginning of the line
  containing the rule's start offset, which sits **after the previous rule's closing brace**, so each deletion
  ate a `}`. Nothing else would have caught that before the build.
- The **measurement** was checked by deleting one live rule through the CSSOM and requiring it to go red —
  and the first two victims moved no computed value, so the bite proved nothing until the victim was *chosen*
  by "deleting it provably changes a computed property". § *prove the injection LANDED*, for a probe.

The cheap version of this rule: **a measurement that has only ever been green has not been tested.** Make it
red on purpose once, in the same session, before any green from it is allowed to authorise a deletion.

### A false positive in a measurement is explained, not excluded

The corollary, and the place where the repair is more dangerous than the fault. The same fingerprint reported
*every element on the page* changed, with no visible difference, whenever a surface was probed before a
`Select` or `Tabs` of the component library the clients used then (PrimeVue, retired by ADR 0107) had ever
mounted: those components registered `--p-select-*` / `--p-tabs-*` on `:root` lazily, and custom properties
inherit. The obvious repair — drop the vendor's `--p-` namespace from the fingerprint — **would have blinded the
gate**, because the project's own `base.css` then deliberately defined some `--p-*` names itself, and their deletion
was exactly the regression the gate existed to catch. The repair
that holds removes the *confounder* instead: warm every surface once before measuring, so the registry is in
the same state on both sides of the comparison.

Generalised: when a gate is noisy, find out **what** differs before deciding what to ignore. An exclusion is a
permanent hole in the shape of today's confusion, and it reports green from inside the hole.
The reverse case is as real: the profile driver of cr-profile-where-the-time-goes refused every upload run on a strict
"no failed request" rule, and the 1,163 `net::ERR_ABORTED` store PUTs it refused on looked like Chromium noise. They
were the client never reading an empty answer body (heavy-stage finding F7), the same defect that held the gallery's
volume reading at `carries: false`; an exclusion would have hidden it from both.

### An exit code from a chain is not the command's exit code

**§ *prove the injection LANDED* generalises past injections, and the ordinary commands are where it is
skipped.** That rule guards a deliberate patch; nothing guards the build, pull or long-running step whose
success the next phase simply assumes. `cr-store-provisioning-from-a-declared-type` reported two images "built"
that did not exist: the `docker build`s had died on a broken `DOCKER_CONFIG`, and the shell's exit code came
from a trailing `echo`. The repair for that then produced the mirror failure — a wait-loop whose bail-out
predicate was `grep -qi 'error'` matched Gradle's `checkKotlinGradlePluginConfigurationErrors SKIPPED` and
printed `BUILD DONE` over a build still running. Two shapes, one rule:

- **Assert the ARTIFACT, never the chain.** `docker image inspect <tag>`, `test -f`, the row, the container
  status — the thing the next step needs, asked for directly. An `&&`/`;` chain's status belongs to whatever
  ran last, and that is usually the `echo` reporting success.
- **A substring oracle over build output is a substring oracle.** Tool logs legitimately contain the word
  `error` in task names, deprecation notices and URLs. Where a loop must detect failure, detect it as
  *"the producing process is gone AND the artifact is absent"*, which is decidable and cannot be spelled wrong.

The tell is a step whose success is reported by the same command that was supposed to do it.

**And never pipe a long run into `head`.** `cr-account-erasure` filtered `./gradlew check` through `grep … | head -40`:
forty lines of expected test log noise later `head` exited, the pipe broke, the build died mid-suite, and the chain
reported `0`. Write the run to a file and read the file.

### A new universal check meets every existing local one

**Grep the surface it sweeps for tests that already assert something about it.** This is the inverse of the guard-anchor rule below, and it bites the other way:
there, a reshape breaks a guard; here, a new guard breaks a *test*. `DefaultDenyTest` declares a deliberately
annotation-free `@Path` resource in **test** scope, which `@QuarkusTest` deploys like any other — so the
obvious CDI-based enumeration in cr-ratelimit-posture's posture sweep red-flagged it, and the obvious fix
(annotate the probe) would have destroyed the default-deny proof. The exclusion belongs in the sweep, and it
is a **named blind spot**, not a silent filter. A refine that lists the guard being built but not the guards
standing next to it hands that collision to implement time.

### A test migration is accounted for per assertion, not by the suite's test count

A sweep that rewrites dozens of test files (a helper swap, a fixture move) can keep every `it` and quietly lose an
`expect` — vitest's *"Tests N passed"* is unchanged either way, so the one number the DoD reads cannot see it.
cr-web-test-support rewrote 67 files through five parallel subagents; what proved nothing was dropped was a
per-file count of `expect(` and `it(` at `HEAD` against the tree, with the only deltas being the two assertions
the CR named for deletion. Run that count before trusting the migration, and name every delta. The same CR's
negative assertions (`not.toContain(…)`) were the other risk: left on a stub string the app never renders, they
stay green on every tree, so each one was resolved through the real source and read back.

**A count catches a lost assertion, not a disarmed one.** A migration that moves a *quantity* — backdating
timestamps so a test survives a new wait — keeps every assertion and can still turn one vacuous: it compares
against what was moved. cr-reclaim-waits-out-the-presign-ttl aged reclaim rows past a new not-before, and
`StoreReclaimSweepSliceTest`'s "the retry is deferred" check read `nextAttemptAt.isAfter(createdAt)` — true on an
aged row with no deferral at all. So read every assertion the moved quantity reaches, re-anchor the ones it
disarms (there, on the tick's own start), and bite the re-anchored one with the lever it guards.

### A test that mutates shared singleton/global state writes its restore in the same edit

**As the mutation, not after a flake.** The `%test` H2 mem DB — and any generated file like the config-secret key — is **JVM-shared
across test classes**, so a test that pokes a singleton (cr-config-secret-key-file-and-fingerprint forced a
`veyq_instance` config-secret fingerprint to a bogus value to exercise the MISMATCH gate) must `finally`-restore
it, or it cascades failures into every later test that assumed the clean value. This is the *write*-side sibling
of the *insert*-side seed-uniqueness rule (distinct domains/emails/**secrets** so the mem DB can't bleed —
cr-email-invite-send-hardening's `token_hash` collision): the same JVM-shared-state trap. Run the **full**
`./gradlew check`, never just the touched class, before calling backend done.

### In a shared working tree, a file is read whole before it is opened for writing

**`open(p, 'w').write(edit(open(p).read()))` empties the file before it reads it** — Python evaluates `open(p, 'w')`,
which truncates, before the argument. On a file only you edit, the edit then fails loudly and `git` restores it. On a
file another session is editing, its uncommitted work is gone, and `git` holds no copy of it:
cr-config-secret-state-on-admin-channel emptied `site/src/content/docs/run/backup-and-restore.md` this way, under
another session's uncommitted rewrite. That session rebuilt the file from its own context. So read into a variable
in one statement and write in the next, and snapshot a file you do not own before the first write to it
(§ *Bite-proof … by snapshot-and-restore* is the same habit). When you stage into a file another session is also
editing, apply only your own edits to the `HEAD` blob (`git hash-object -w` + `git update-index --cacheinfo`) rather
than adding the working-tree file.

### A build step that emits an executable artifact is not done until the artifact RUNS

**A green build proves the builder was happy, not that the thing it produced works.** cr-admin-cli's esbuild
invocation lives in a `package.json` script, so the shell ate a layer of quoting and
`--define:process.env.NODE_ENV=\"production\"` reached esbuild as a bare identifier. esbuild accepted it —
defining a symbol to another symbol is legal — bundled cleanly, printed its size, exited 0. The artifact then
died on its first line with `ReferenceError: production is not defined`. Nothing in `pnpm run check` would ever
have caught it; only `node dist-cli/cli.js` did. So the "Done when" for any phase that emits a runnable
artifact (a bundle, a binary, a container) names **running it**, never just building it — and shell quoting
inside a JSON string is a specific place to look first, because it fails silently in exactly this way.

### A generated pin file is written from the substrate the release builds on

**A pin file a tool writes records the run that wrote it, not the build.** cr-build-inputs-pinned said to write
Gradle's verification metadata "over every task", and it did — from a warm `~/.gradle`, which had no reason to
fetch `okhttp-bom-5.3.2.module`. Every clean build (the Dockerfile's, a fresh CI runner's) does fetch it, so each
of them would have been red on the first run. A cold Linux container found the gap, and a cold macOS write
reproduced it. So a done-when that writes one names its **substrate**: an empty cache, and the OS the release
builds on. Otherwise it names only a command.

### A pin file's write set includes what only RUNS, and what only the IDE and the image build fetch

**"Every task" means every task that resolves, and some resolve only while they run.** cr-build-inputs-pinned's
metadata passed its own check, then broke three consumers its write never exercised, each found on 2026-09-28
by simply using the thing: `quarkusDev` (its bootstrap resolver configuration resolves only when the task
executes — the dev backend refused to start), the owner's IntelliJ sync (it fetches the Gradle distribution's
`-src` zip, which no `-sources.jar` rule matches), and both container builds (the JAR verifier ran inside the
image's `npm ci` before `openapitools.json` had been copied in). So a CR that adds a verification step lists
**every consumer that resolves or runs the verified input** — the dev loop, the IDE sync, each image build —
and its done-when **runs each one**, not the test suite that happens to share the build file.

### A refine that changes who runs a process lists what that process reads, and what the proxy must still reach

Two misses of cr-runtime-hardening-both-targets, found only by building it. **A new uid meets old file modes:** a
non-root Traefik could not read `tenants.yml`, which the app wrote 0600 — the file provider failed silently while
the proxy stayed up. **A network split meets every upstream:** ADR 0071 §3's "admin shares a network with the app
alone" left the proxy's admin router with no path. So when a refine changes a process's uid, it lists every file
that process reads which another process writes, with the writer's mode; and when it splits networks, it checks
the split against every upstream the ingress sink renders (`VEYQ_TENANT_INGRESS_UPSTREAM_*`), not only the ones
the ADR names.

### Split an expensive live proof so its no-daemon half runs anywhere

cr-admin-origin-hsts wrote only the Docker proof, and the implementing session could run no Docker — so two of its
"believed" points waited a day although neither needed a daemon: nginx's header-merge semantics were one function
in its source, and the entrypoint hook's switch values were a one-shot run of the hook with its paths redirected.
Where a refine leaves a tool's semantics "believed, not measured", it reads the tool's source then, and it splits
the proof so the part that needs no daemon is done by whoever implements.

**And a step only the owner can take is named as theirs in the done-when.** `cr-root-gradle-launcher` marked
`infra:wipe` as the owner's, so it never stood as an agent's open item. It did not mark the two other steps only the
owner could unblock — recreating the shared dev database its browser upload needed, and a start and stop from
IntelliJ's Gradle tool window — and both surfaced only at close, as "not proven". A done-when says per live step who
runs it, and an owner's step is asked for when its phase starts, not discovered when the CR closes.

**And a live proof across two releases names, per step, which release's tool runs it.** cr-veyqctl's phase 3 said
"install the first … `upgrade` to the second"; its harness ran `self-update` with the first release's `veyqctl`, which
predates the command, and the whole run was repeated.

### A live proof that names a screen names the instance state that screen needs

cr-app-shell-served-by-backend's Definition of done said *"the app boots to sign-in with zero CSP violations"* and
specified a **throwaway** database for the run. A database with no account boots every route to "not set up", so
the sign-in screen was unreachable without a first-run setup the CR never mentioned — and the Service Worker the
same proof was meant to exercise registers on neither screen. The finding held (the app booted under the policy
with zero violations), but on a different screen than the one written down, and the worker went unverified. So a
done-when that names what the browser should show also names what the instance must hold for it (a seeded
account, a tenant on the browsed host), or it names the screen a bare instance actually reaches. A done-when that runs
lane L1 names the host ports the lane binds (80, 443, the admin port 18081) and checks them with `lsof` at refine:
cr-webp-encoder-from-libwebp's L1 never started, an ssh tunnel and a neighbour session's process held 18081.

**And a lever that simulates a failure must spare what the proof itself needs.** `cr-one-sign-out-path` asked for
its E4 check "with the network offline" — but the flow ends in a document load, which devtools' offline mode
refuses too, so the sign-in page the notice is shown on could never have loaded. Stopping the backend (the dev
proxy then answers 502) fails exactly the calls under test and nothing else. Its other precondition, "a video
having played", needed a tenant with media on the browsed host; the dev DB had none that day.

**And a change to a stored format makes the dev database itself the wrong instance.** `cr-hybrid-kem-is-x-wing`
changed the identity bundle in place (pre-V1), so no identity in the owner's dev database opened any more, and its
browser pass needed a fresh instance — which the done-when never said, and a `down -v` would have wiped the owner's
dev data to get. A database of its own on the same compose stack leaves theirs alone: `CREATE DATABASE` beside `veyq`
(root credential as in `scripts/run_web_e2e.py`), boot the fast-jar under `-Dquarkus.profile=e2e` with
`QUARKUS_DATASOURCE_JDBC_URL` pointed at it, its own `VEYQ_SECURITY_CONFIG_SECRET_FILE`, and
`VEYQ_BUNDLED_STORE_PUBLIC_ENDPOINT` set to the browsed origin (the old `VEYQ_BLOB_STORE_…` name is read by nothing
any more, so a boot that sets it signs for the store's own endpoint and the seeder's store check fails), then `pnpm run bootstrap:user` — which writes `.run/.env`
back, so that file is snapshotted first and restored byte-equal after. A CR that changes a stored format names this
instance in its browser pass.
So does a migration baseline reset on `main` that the shared dev database has not followed yet:
`cr-root-gradle-launcher`'s upload met `Unknown column 'meta_revision'` on a database a few hours behind `main`'s
baseline, and the proof waited on the owner's wipe. A browser pass planned on the dev database first compares the head
of its `flyway_schema_history` with `main`'s newest migration.
Where an agent may not copy `.run/.env` at all (the auto-mode classifier refused even a scratchpad snapshot on
2026-10-15), the instance gets a principal of its own instead: the seeder's `ENV_FILE` is a constant, so a temporary
copy of `src/web/scripts/bootstrap-user.mts` that reads an env file of generated test values outside the repo seeds it, the
copy is deleted after, and the login goes through the form (`?devlogin` reads only `.run/.env`). Boot a COPY of the
fast-jar, too: a neighbour's `./gradlew` rebuilds `src/backend/build/` under a running pass.
Two devices are two HOSTS, not two ports: cookies ignore the port, so `capx.localhost:8087` and `capx.localhost:8085`
share one refresh cookie and the second "device" opens on the first one's session (inbox #447's pass). A second Vite with
`VEYQ_SMOKE_HOST=<tenant domain>` serves the same tenant under another `*.localhost` name with cookies and storage of
its own. And the instance takes its own ports (`QUARKUS_HTTP_PORT`, `VEYQ_ADMIN_CHANNEL_PORT`) when a neighbour holds
8080/8090, with admin-web's Vite pointed at that admin port (`VEYQ_API_TARGET`, `VITE_ADMIN_PORT`).
An instance needs no shared container at all (cr-rotation-refused-as-too-large, 2026-10-27): a MariaDB and a Garage of
its own, run from the dev stack's images by id (`docker inspect veyq-mariadb --format "{{.Image}}"`, so nothing is
pulled), the jar under its default `%prod` profile — `%e2e`'s store hint names the owner's Garage on 19000 — with the
`VEYQ_BUNDLED_STORE_*` env pointed at its own, a Vite of its own (`VITE_PORT`, `VEYQ_API_TARGET`, `VEYQ_STORE_TARGET`),
and `bootstrap:user` with `VEYQ_BASE`, `VEYQ_ADMIN_BASE` and a dead `VEYQ_MAILPIT`. From a worktree, the seeder's
`.run/.env` is the worktree's own, so nothing of the owner's is snapshotted; it is deleted after.
A server setting the screen's failure depends on (a request limit) is lowered **after** the sign-in, by restarting the
jar with it: the tab keeps its session and its unlocked vault, so a `413` is reached on a fresh account without seeding
a body of that size.
The capability register's texts are compiled into the backend (buildSrc generates `CapabilityTexts.kt` from
`contracts/platform/capabilities.json`, and both clients read them over the API), so a live proof of a changed register
text boots a jar rebuilt after the edit; a Vite reload shows the old text (cr-copy-follows-the-rule, 2026-10-23).
A live proof that shortens a duration to fit in minutes (a grace, a retention) shows the screen a value no unit test
rendered: cr-bucket-reconcile's three-minute grace read "0 days" in the verdict and the confirm dialog, every unit test
having used the 30-day default. So a UI that renders a configured duration is unit-tested with the shortest value its
live proof will use, too.
**And a lane that shortens a window to wait it out decides which of a criterion's branches a browser can hold.**
cr-switched-off-application-is-erased's lane ran a two-minute window with a five-second purge tick, which let the
browser cancel before the day and watch the erasure after it. A switch-on *after* the day is a race with the tick's
phase, and a reminder seven days ahead has no window at all, so both stayed the backend's, named in the use case's
`stack` gap. Where the claim is that a tick did nothing (a cancelled day passed and nothing was erased), there is no
state to wait for: the wait is the server's own day plus a number of ticks, computed, never a fixed sleep — and since
2026-10-26 that claim is not waited out in the lane at all: the slice test with its injected clock holds it, and the
lane's window is thirty seconds (ADR 0090 decision 9).

### A CR that grows a count checks each stage against the caps the contract already sets

cr-volume-measurement-local planned a 1,000-member stage, and its seeder stopped at 445 in the middle of the night it
had for measuring: every member a key-holder admits adds a pin to that key-holder's sealed identity-pin store, which
`account.openapi.yaml` caps at 1 MiB (about 2.4 KB a pin). The cap was in the contract the refine read. So a CR whose
stages grow a count (members, photos, nodes, shares) names, per stage, every stored value that grows with it and that
value's `maxLength` / `maxItems` — the locator is a grep of the contracts for the stores the growing flow writes — and a
stage past a cap is a finding at refine, not at 22:25.

### A UI phase's done-when names its browser pass

[`docs/ai`](../docs/ai/README.md) step 3 says a UI slice is not done before it was clicked. `cr-files-application`
had eight UI phases, and every one closed *"not verified in a browser"* — its done-whens named unit tests, goldens and
e2e, all API- or DOM-level, so each phase could tick with the rule unmet and nobody had to decide to skip it. A rule
the done-when does not ask for is skipped by default. So a phase that changes a screen names in its done-when what
is clicked and where (and § above: what the instance must hold for it); a phase that cannot run a browser says so
and the CR carries the pass as an open item, not a footnote.

### A layout that switches at a breakpoint is computed on both sides of it at refine

cr-node-header-actions-by-priority fixed a node header's actions to a row of their own up to 540 px and to the right
end of the title row above it, beside a 12rem minimum title — and the numbers to see the result were all in the CR (a
180 px Memory cover, 41 px buttons, the 12rem). Only the browser pass computed them: 9 buttons at 540 px, **1** at
541 px, 6 at 768 px (#531). A refine that puts a layout switch at a width does the arithmetic for a width just below and
just above it, from the sizes it already measured, and shows the owner both — a cliff at a breakpoint is a design
question, and the refine is where it is cheap.

### A constraint on a module's imports is measured on what that module must CALL

"The ending imports no feature" (ADR 0070) was a sound rule and an unbuildable instruction as written: the first
step the ending had to run — drop the convenience tiers — existed only in `features/account/rememberMe/`, whose
`deviceKeyWrap` imports `withRefresh`, the exact cycle the rule was there to prevent. The implement session had to
move the tiers' storage names and local drop into `app/convenienceTiers.ts`. So when a CR restricts what a module
may import, the refine greps the imports of every function that module is told to call, and names the move if one
is needed.

### An ADR's cost premise expires separately from its decision — re-measure it at implement time

**The decision can still be right while the reason it was cheap has gone stale.** ADR 0065 chose esbuild "already
a transitive Vite dependency, not a new tool". True when written; false at implement, because Vite 8 bundles
with rolldown and `npm ls esbuild` returns empty across the whole tree. The decision survived on its own merits
and the ADR carries a measured amendment — but the *cost* it was accepted under had changed, and only running
the locator revealed it. This is the same instinct as "run the locator, don't trust the tally" one section up,
pointed at the justification instead of the inventory: **a parenthetical that makes a decision look free is a
claim, and claims get re-measured.** A CR that already knows to re-run its own grep should also re-run the
"this costs nothing" check.

**The same holds for a planned index: EXPLAIN it against the data shape the statement meets.** The session-purge
CR planned `expires_at` indexes on three tables so that "the three deletes take a range scan". With the index
present, MariaDB range-scanned only the refresh tokens; access tokens and challenges live minutes against an hourly
purge, so most rows are expired when it runs and the optimizer full-scans regardless. Two of the three indexes
would have cost every login a write for nothing. An index is a claim about selectivity, and selectivity is a
property of the data at the moment the statement runs.

**And an external precondition is versioned by the newest feature it combines, each measured on its own.**
cr-helm-gateway-api's refine measured on the web that cert-manager 1.20 reads `ListenerSet`s and that a
`http01-parentreffallback` annotation sends their challenge to the parent `Gateway` — both true — and ADR 0064 wrote
"cert-manager 1.20 or later". The annotation first ships in 1.21.0; only reading each feature's own release (the
source at v1.20.4 and v1.21.2) showed it. The same pass found the docs' example implementation ignoring
`ListenerSet` in its latest release, and the redirect's `scheme` field to be *Extended*, not Core — so a
precondition list is one claim per feature, and "version X supports the design" is the conjunction of them,
re-measured at implement against the newest release of every project it names.

### A phase's entry condition is phrased against what the phase would CHANGE, not against the problem

**"Measure first, and stop if it is no longer the felt cost" sounds rigorous and can still answer neither
branch.** `cr-upload-prepare-visibly-and-decode-once`'s phase 3 — overlap the next file's on-device preparation
with this file's PUTs — was gated exactly that way. The measurement came back: preparation *was* still the felt
cost (93% of a 12-photo batch), and the phase would have reclaimed **none** of it, because overlapping is
bounded by the time spent on the wire and that was 7%. Both of the written branches were wrong: *stop, it no
longer hurts* was false, and *build, it still hurts* was irrelevant. The phase was closed on an owner's call
the CR had not anticipated needing.

The fix is one clause at refine. Ask what the phase **moves**, and gate on that: not *"is preparation still
slow?"* but *"how much of the batch is spent on the wire, since that is the ceiling on hiding anything behind
it?"*. Stated that way the same run decides by itself, with nobody adjudicating. Two riders the same example
earned: name the **substrate** a performance number is measured on — a loopback object store understates every
network-bound gain, so the number bounds the decision *here* and not everywhere — and record the measurement
where the follow-up will look for it, which is the inbox row for the work it gates, not a retrospective that is
deleted with its CR.

**On this host, machine load is part of a timing's substrate — record it, and interleave before with after.**
Five agents share one Mac, so a timing taken "now" is taken under whatever they are building.
`cr-derive-media-tiers-in-a-worker`'s phase-0 gate (*is the backgrounded pixel stage over 6 s?*) passed at
6.5–8.9 s and code was built on it; the same bench, quiet, measured 2.8 s on both paths — the gate would have
stopped the CR — while under load the page path took 22.6–73.6 s and the change under test 5.2–27.3 s. Neither
reading was wrong; each was a different substrate, and only one was written down. So every timing carries
`sysctl -n vm.loadavg` beside it, a before/after pair runs alternately in one window rather than hours apart,
and a gate that authorises building is taken again quiet before it does — a gate measured once is § *bite the
MEASUREMENT* left undone. **A bound — a ceiling a reading must stay under — is judged at the load there is**, since the
owner's work keeps this Mac from ever being quiet (a harness waiting for a load of 3 waited 25 minutes at 6–25): a bound
that holds under load passes, since load only makes the numbers worse; one that fails gets one re-run and fails on a
second; after a fix, both runs must hold. Let the load of your own last heavy step fall before the first run.

### Where a mapping depends on a library's output, one un-mocked test runs BEFORE the mapping

**A suite that mocks the library proves the mapping and never the tag name, so an invented name passes
everything and is `undefined` in production forever.** `cr-media-detail-panel-and-place-name` planned eleven new
EXIF fields against `exifr` and scheduled a single fixture test — a generated JPEG parsed by the **real**
library — to run first. It caught four of them before a line of panel code existed: `ExposureBiasValue` does not
exist (the key is `ExposureCompensation`); `Flash` is the bitfield the plan assumed only under
`translateValues: false`, and is otherwise an English sentence on which `& 0x1` reads 0; the same option is what
makes `Orientation` a number, which exposed a **pre-existing** defect — that field had been sealed as
`undefined` since it was introduced; and two more shapes were objects where strings were expected. Every one of
those would have been green under the mock.

So: **generate the fixture, do not borrow a file.** A real photo carries a licence question and someone's
metadata; a generated one is reproducible, auditable byte for byte, and carries exactly the tags being claimed.
Write its generator with **no third-party import** — one that needs Pillow installed is a fixture nobody can
regenerate on the machine where it next matters.

**A CR that asks for the un-mocked test names the real reader it means, or says there is none.**
`cr-release-notices` asked that its `.ics` "parses (one un-mocked parse)" and no iCalendar library was on any
classpath; the honest form was the real mail library's MIME parse plus a strict RFC 5545 line reader in the test,
decided at implement time. Adding a library for one assertion is a dependency decision (checksums, a licence), not a
test detail, so the refine makes it or rules it out.

**The same instinct, pointed at the CR itself: a phase can specify work a convention forbids.** The same CR's
doc phase listed two applied Flyway migrations as "edited". `src/backend/CLAUDE.md` forbids that outright, and the
reason is not style — `migrate-at-start` validates each applied migration's checksum, so a one-word comment fix
fails startup on every instance that already ran it, and `%dev`'s `repair-at-start` hides exactly that on the
machine where you would test it. The live copy of the rule moved to the entity KDoc instead. **Read the target
surface's own `CLAUDE.md` before executing a phase that edits it** — a CR is a plan written elsewhere, and the
conventions live where the files do.

### Keying a cache is half the fix — something must still ASK it again

**An invalidation-free cache is a lovely design and it is inert until a watcher's dependencies include the same
key.** `cr-sidebar-favorites-show-their-cover` extracted a per-surface cover cache into one store and dissolved
its staleness trap in the cache *key*: the entry records the `wrappedMeta` it was resolved from, so a changed
cover re-resolves with no invalidation call to forget. That half was right. The other half was written as "a
watch on the favorites set + the epoch + the tree", and "the tree" got implemented as the node **count** —
while the thing that changes on a cover edit is `wrappedMeta`, **mutated in place**. Ids unchanged, epoch
unchanged, count unchanged: the watch never fired, the store was never asked, and the rail showed the old cover.
The same slip sat in the surface the CR was refactoring, for the same reason.

So when a CR says "cache keyed on X", read the trigger in the same breath: **the watcher's dependencies must
contain X**, not a proxy for it. An in-place mutation is the case that makes identity-based dependencies lie.

**And this is why that CR's hand-pass line was not ceremony.** Both surfaces' component tests mock the store —
correctly, they are about rendering — so the seam between trigger and cache was untested *by construction*, and
no unit test could have caught it. The bite that finally pinned it asserts the **trigger** (`ensureCovers` is
called again after a meta change) rather than the rendering, which is the only thing a mocked-store test can
say. When a CR extracts a shared store out of its consumers, expect exactly one new untestable seam and plan a
live pass for it.

### `git status` before the first edit of a contracts-phase CR

**A dirty shared spec is stop-and-confirm, not proceed.** Two CRs touching one `*.openapi.yaml` mix unrelated deltas into a single commit: #103's mechanical
enum narrowing landed on top of uncommitted `cr-space-icon` WIP in `scopes.openapi.yaml`, and only a pre-edit
`git status` caught it. Split the go — land one, then the other as its own commit — and the history stays legible.

### A sibling CR still in flight is cited as PLANNED, and re-read when it lands

`cr-share-key-recovery-for-members-only` justified its key choice with a "precedent": #190's members-only name
keypair under `K_SC`, written in the present tense. #190 then landed without it — its owner took the cheaper
variant — and the paragraph described a design nobody built. The reasoning happened to stand on its own; a CR whose
decision *rested* on the sibling would not have. So a claim drawn from another CR's plan says so ("planned in
`cr-…`"), and the CR that lands second re-reads every such claim against the merged tree before its first edit,
alongside its locators. **Make that re-read a grep:** every sentence that holds only *until* the sibling lands
carries the same marker (*"until `cr-…` lands"*). `cr-queued-rotation-owners-only-no-leased-join` spread its
dependence on the granter lock over an inventory, a caveat, an owner answer and a planned exception; the sibling
removed the lock one commit before implementation began, and only the brief's warning found all four.

**An accepted ADR's decision that nothing has built is a plan too.** `cr-stack-e2e` phase 4 told the stack lane to
pull `build-<sha>-<arch>` by digest "on a push" — ADR 0091 decision 5's snapshot-per-push, decided and not built: the
workflow pushed images on a dispatch only, so on a push there was nothing to pull, and the implementer re-cut the job
(pull on the dispatch, build in the job on a push). A phase that wires into the pipeline measures what the workflow
does today, per event (the triggers of `.github/workflows/checks.yml` and `publish.yml`), and says which half of its sentence waits on
which unbuilt decision.

### An owner's answer that reverses a decision is edited into every phase it reverses

`cr-backup-store-leg-is-an-index` recorded the owner's O3 — the object pool is pruned, not grow-only — under *Decided
with the owner*, and left decision 3 (*"no `delete*` may name a pool"*), bite B4 (*"`copy --immutable` or a read"*),
phase 4's step *"the pool never deletes"* and the coverage criterion `an-object-pool-only-grows` saying the opposite.
The brief carried the correction, so the implementer re-derived the prune's bites and B4's new shape from it — a
CR whose body and appendix disagree hands the implementer a choice that was the owner's. And the answer's own terms
had no referent on one target: *"captures rotate by `keep`"* is the cluster gate's rule, while Compose rotated no
capture at all, so what a "retained capture" is there was decided at implement (the pool's own ledger) and handed
back as such. So an answer that reverses a settled decision rewrites, in the edit that records it, every decision,
bite and step it reverses, and names what each new term refers to on every target the CR touches. **The same holds for
an answer that adds a decision**: `cr-backup-erasure-and-retention`'s decision 7 (moderation survives a restore) was
added after its phases were written, and none of them owned what it needed — a contract enum value (`RESTORED`), a
moderation queue that silently dropped a case with neither report nor order, and a web badge and a hidden review
button; the implementer found each by following the decision through the code. An added decision names, in the same
edit, the phase and the surface each of its effects lands in.

**And an answer that swaps a mechanism re-checks every property the old mechanism bought.** `cr-memory-banner` was
amended the day it was answered — no in-browser transcoder, the banner plays the copied original — and the paragraph
that recorded it rewrote the phases but not the invariant above them: *"the server learns … never which medium"*. A
transcoded copy had bought that; the original re-sealed as it is has the original's exact byte size, so the claim was
false the moment the amendment landed, and the implementer found it while writing the upload (inbox #505). The same
hides in a re-derive: an image derived with an existing tier's own spec in the browser that made that tier reproduces
its bytes. So an amendment names, beside the steps it reverses, each property in the CR's `invariants:` and the ADR's
*What the server sees* that the replaced mechanism carried, and says whether it still holds and by what.

**A fact stored for a later comparison names the read that brings it to the comparing client.** `cr-cover-banner-upload-only-and-dedup`
put a file's SHA-256 into the video banner's `blob-meta`, sealed where the bytes are written - and the client that had to
compare it (a gallery upload looking for the banner's file) reads an attachment only through the refs the node's meta
names, which carry no `blob-meta`. The digest had to move into the banner's sealed meta as well. So a decision that stores a
fact for a match says, beside where it is written, which response or ref serves it to the reader that matches - one `grep`
of the type the reader holds. **And a rule of the form "the last one does X" names its lock:** "reclaim only when no other row
names the object" read by a plain count leaks the object when its two rows go at once and loses it when a new row races the
delete; the decision text is where the lock belongs, not the implementer's review.


### Two CRs that write the same page name each other, and split it by section

`cr-run-area-content` and `cr-store-connection-in-admin-web` both rewrote `site/src/content/docs/run/` the same day:
one fixed the pages' backup, upgrade and install procedures, the other moved their store and config-secret steps onto
the new model. Neither CR named the other's sections, so the two sessions edited the same three files uncommitted at
once, one of them was emptied mid-edit (§ *In a shared working tree, a file is read whole…*), and every commit after
that had to be built from `HEAD` plus one session's hunks. When a refine finds that another live CR edits a file this
one edits, both CRs say so in their Context, with who owns which section, and the second to implement starts from the
first's commit rather than beside its working copy.

### "Which one is mine?" is a flag, not an id — and dropping the id is the stronger answer

**When a read hands back a collection of per-party blobs and the client only needs to find its own, the field
to add is a boolean on the caller's entry, not that party's identifier.** The array already separates the
parties, and a count across it needs no names — so an id is data the read did not have to carry, and the only
fact the client genuinely cannot derive is *which entry is mine*, which the server knows as the token's
principal.

`cr-favorites-two-objects` shipped `ItemFavoritesEntry.memberId` in its contracts phase and replaced it with
`self: boolean` two phases later, when the web phase revealed the client has no way to know its own user id at
all — so the field could not even do the job it was added for. The replacement is smaller *and* stronger: the
product rule was "never show who marked what", and with no member id on the wire a client **cannot** attribute
a mark however it is written. A policy the UI is trusted to keep became a property of the data.

The general form: **prefer removing an identity over adding a rule about not displaying it.** A rule holds
until someone writes the next component; a shape holds by construction. Ask it at refine, where the answer is
a line in an ADR — the section below is the same question about whether the field earns its place at all.

### Before adding a read field to expose a server fact

**Prove the fact isn't already *implied* — and that the server can *reliably know* it.** A new field earns its place only when the value is both (a) not derivable from
what the read already carries and (b) something the server can determine truthfully. cr-email-tenant-mail-settings-web
dropped a proposed `sealAvailable` because `passwordSet: true` already implied the KEK was present at seal time;
cr-config-secret-key-file-and-fingerprint then *added* `configSecretState` — not a reversal, but because a new
capability (the DB fingerprint tripwire) let the server *detect* a state it previously could not. The deciding
question is the server's knowledge, not the client's wish — name that dependency when first weighing the field,
not a CR later.

**A CR that makes a writer bind a value also owes the locator for every READER that must supply it.** The writer
side is the easy grep (every seal site); the gap is on the other end, where the opener needs the same value from
its own address and the read may not carry it. cr-seal-binds-its-object named one missing read field (the bucket
row's share) and there were seven — the public share's scope, a shared chapter's epoch, the tenant switcher's
granted epoch, an invitation's name epoch, the caller's own user id, and a member mark's author, which the read
omits *by design* and so became an accepted gap instead of a field. Each surfaced mid-implementation as a contract
change, and one as an owner question. Run the reader angle at refine: for every bound field, `grep` the read
schema the opener consumes for it, and list the misses in the CR. **A bite on a read field names the persona's
row in that read before it names the value:** `cr-queued-rotation-owners-only-no-leased-join` asked a grant-less
owner's row to read `stalled`, and `listMyScopes` serves no row for a grant-less membership by design. **The same holds for a new KEY a reader needs,
and there the reader is every surface that shows the object, not the schema the key was put on.**
cr-uploader-never-holds-the-memory-key put an identity's wrapped key on `ShareIdentity`, which only that link's own
resolve serves; the curator's gallery, the curator's buckets and a viewer link of the same Memory each read the
upload through a different response, so three read fields surfaced mid-implementation (the media page, the bucket
list, and a resolve that serves other links' uploader identities).

### A CR that changes a gate runs the opt-in suites whose fixtures pass through it

**A suite no CI job runs rots between the CRs that run it, and nothing says so.** `cr-scope-access-gate` made a
write require an accepted **tenant** membership; every slice test's seed already had one, and `BlobBatchE2ETest`
(`VEYQ_S3_E2E=1`, run by hand) did not — so from that merge on its uploads were a `404`, found only when
`cr-upload-windowed-presign` ran it two CRs later. The fix was one seed line; the cost was that the one proof of
the real byte path was dead for a wave without anyone knowing. So a CR that tightens a gate greps the opt-in
suites (`grep -rln 'EnabledIfEnvironmentVariable' src/backend/src/test`) for fixtures that seed through it, and runs
the ones it can.

### Giving a body-less operation an optional body changes what every caller must send

The generated resource then `@Consumes` JSON, and RESTEasy answers a request with no `Content-Type` `415` — before any
handler, however optional the body is. cr-statement-legal-gaps gave `suspendTenant` an optional statement: admin-web's
generated client sends the header on every call and kept working, while nine slice tests and two projector tests that
posted the suspension bare went red at once. So a CR that adds an optional body to an existing operation greps its
route across the test trees, `tests/e2e/` and the Run docs for a caller that sends no `Content-Type`, and says in the
contract that the request is JSON either way.

### A required response field's predicted reds come from who RECEIVES the type, not who builds it

cr-switched-off-application-is-erased made `TenantCapabilities.applicationErasures` required and predicted the web's
reds from `grep "capabilities: \["`. The grep found `SetupStatus` fixtures and `setTenantCapabilities` arguments, and no
red came: the client's `getTenantCapabilities` unwrapped `.capabilities`, so no fixture typed the response at all, and
the field reached the client only once that wrapper returned the whole type (then the two suites mocking it needed
it). So the locator for a new required response field greps the generated type's name and every function that returns
or unwraps it, and the CR says whether a wrapper must change to pass the field through.

### A locator for an endpoint's callers greps its route, not only its operation name

**Slice tests call the API by URL, so a grep for the Kotlin/TS symbol misses them.** `cr-removal-rotates-in-one-request`
narrowed `DELETE /api/scopes/{scopeId}/members/{userId}` to pending proposals, and its test locator
(`grep -rlE 'removeMember|rotateScope|/epochs' src/backend/src/test`) found three files. The fourth,
`ShareSharedScopeSliceTest`, removed a member with `.delete("/api/scopes/$group/members/$member")` and surfaced
only as a red in the full `./gradlew check`. When a CR changes what an endpoint accepts, the locator also greps
the route's path shape (`grep -rnE 'delete\("/api/scopes/\$[a-zA-Z]+/members/' src/backend/src/test`) — one angle per
way a caller can name it.

### A done-when that names a gate records that gate's exit code on `main`, measured at refine

A gate is a claim about the whole tree, not about the CR's files, so "green" in a done-when silently assumes a
green baseline. cr-emergency-kit-codec-in-crypto asked for `pnpm run mutation` in `packages/crypto` with no survivor
in the new file; the file reached 100 %, and the run still exited 1 because `v1.ts` scored 78 % on untouched code
(the manifest's `_comment` said 100 %, measured before the package split). Run the gate once during refine; if it
is already red, the done-when names the scope it can honestly promise and the red goes to the inbox.
The same holds for a **coverage surface**: `cr-tenant-storage-quota` asked for refs "on `backend`, `web` and
`admin-web`", and `admin-web` refs are not admissible until a run has executed that job (ADR 0040) — the sibling
file `uc.admin.suspend-tenant.yaml` already said so in its `gaps:`. `python3 scripts/validate_coverage.py` prints
which surfaces are *"awaiting a first run"*; read that line at refine before promising refs on one.

**A done-when that names the upgrade proof names its predecessor.** `run_web_e2e.py` runs the `upgrade` pair from one
tree in every ordinary run, and the two-build proof (`--upgrade`) against `HEAD^1` by default; they prove different
things, and across a pre-V1 change that rewrites a stored value in place (`cr-files-becomes-depot`: `FILES_DROPBOX`
-> `DEPOT_DROPBOX`, the baseline edited with its pin, no migration) a two-build proof from before the change cannot
be green at all. Its CR said only "the upgrade proof"; the honest reading was the ordinary pair per phase plus
`--upgrade` after the last phase, whose `HEAD^1` already carries the change. Say which, and from where, at refine.

### A server that insists on completeness needs a way to leave something out on purpose

**When a CR has the client skip an item and the server refuse a request that misses one, it has made "skipped" and
"forgotten" the same bytes.** `cr-admission-is-a-key-holder-act` said both, three lines apart: a rotation does not
carry an admission whose tag fails, and the server refuses a rotation that leaves any open admission without a new
tag. Read together, one forged admission — which the operator can plant, since it writes the rows — blocks every
future rotation of that scope, removals included. The fix was a third state on the wire (`droppedAdmissions`: left
out *on purpose*, and cleared by the server so it is no longer open); the refine could have named it in a sentence.
So wherever a CR pairs a client-side skip with a server-side completeness check, it names how the request says
"this one, deliberately not" — and what the server does with it.

### A device-local defence against the server is filed under a key the server cannot name

**Local state that guards against the operator is only as good as the index it is looked up by.**
cr-trust-state-never-goes-backwards said the marks are kept "per device and per account" and left *what names the
account* open. The obvious index is the account id the client already has — and every client learns it from
`GET /api/account/key-material`, i.e. from the server. A server that reports another id sends the device to an
empty slot, which is the no-mark case on demand: the whole floor, silently off, with every bite still green,
because the tests serve one honest id. The slot became a one-way derivation of the Master Key, which the server
cannot choose (ADR 0028 Decision 7). So a CR that adds device-local trust state names its key and asks of it the
question it asks of the state: **could the operator choose this?**

### Turning a degradation into a refusal inventories every path that reached the degradation

**A store that "degrades to empty" was also the recovery path of everything that could leave it unreadable.**
The same CR made an unopenable pin store and register a refusal instead of the empty store, as ADR 0028 Decision 7
decided. The attack it closes is real; so is the honest writer it strands: an account-key rotation re-seals both
best-effort *after* its irreversible publish, and a re-seal that never lands used to cost the pins and now costs
every wrap to a foreign key, with nothing to reset it (inbox #192). Neither the ADR nor the CR listed who produces
an unreadable store honestly. So a CR that turns *degrade* into *refuse* greps for the writers of the degraded
state (`grep -rn 'reseal\|best-effort' …`) and gives each one a way back — or an inbox row, in the CR, before the
refusal ships.

### A race the refine only read gets its symptom and its fix from the red, not from the read

**A read predicts the interleaving; the schema and the neighbouring locks decide what it does.**
cr-managed-writes-flush-only-what-they-change read three same-column races and prescribed a compare-and-set for
each. Run, two of them were somewhere else. A move into a sub-bucket being deleted did not "leave the medium pointing
at it": `fk_media_bucket` refused the commit, so the defect was a `500`, and the prescribed `and bucketId = ?old`
guards the source column, which the race never touches. The fix is a lock on the target row that both writers
take first. A re-activation's `enabled` was already row-locked by an earlier CR; what still raced was the membership
check read before that lock. So for a race row marked *read, not run*, grep the foreign keys and the existing
`PESSIMISTIC_WRITE` reads on both rows (`grep -n 'FOREIGN KEY' …/db/migration`, `grep -rn 'PESSIMISTIC_WRITE'`).
Write the fix as a hypothesis, and re-derive it from the first red.
**Race every writer of the invariant, not only the pair the row names.** `grep` the decision's read (for the
last-owner rule, `ownerCount`), and give each caller an overlap test. #272 named two demotes. The read's third
caller, the tenant removal's plan, left a group with no owner in round 0 as well, and that test is what made the
fix lock before the plan instead of only around the demote
(cr-owners-and-personal-scope-hold-under-overlap). **And a race green on H2 is a claim about H2.** Production
ran MariaDB at REPEATABLE READ while the tests ran READ COMMITTED; the first run of the race classes on the pinned
engine reddened three tests the suite had been green on for weeks, one of them (a refresh chain forking) a defect
no refine had named. Both now run READ COMMITTED (ADR 0078), and a CR that changes a lock, a gate or a transaction
boundary runs `scripts/run_backend_races_on_mariadb.py` before it calls the race fixed.
**Sweep a gate-then-write class from the cleanup's side.** A list of gates outside their write's transaction is
long and mostly harmless; the real ones are where a concurrent cleanup (a member's removal, a purge) enumerates
the rows the gated write adds. Keyed on the cleanups (`grep` the bulk deletes and updates by a set, and the
`@Scheduled` purges), cr-a-gate-holds-until-its-write-commits found thirteen real ones in five clusters and could
name every other gate safe in one line each.

### A refine that adds a precondition to a call, or makes it automatic, reads that call's FIXTURES

**The call sites are only half the inventory.** `cr-breach-check-automatic-on-blur` turned an opt-in lookup into an
automatic one with a policy floor. Its locator found every caller and every test that named the lookup, and two kinds
of red still came only at implement time. The composable's own oracle password was 8 characters, under the floor of
10, so it would never be sent again. And every dialog test that submits now passed through the lookup, so a bare
`vi.fn()` resolving `undefined` blocked unrelated cases. So when a CR adds a precondition to a call, or puts a call
on a path that used to skip it, grep the values that feed it in the tests and the mocks that stand in for it. Name
them in the inventory as affected. The quiet case of it: a call site that starts using a returned promise
(`void f()` becoming `f().catch(…)`) reddens every `vi.fn()` mock of `f` that returns `undefined` — four component
tests in `cr-item-marks-binary-ids`.

### A refine that makes writes retry under a NEW key sorts each write path by what it carries across the retry

`cr-any-member-re-keys` decided that every member write meeting `node_rekey_pending` pulls its path forward and
retries, and its inventory paired each `409` site with its web caller. What it did not ask is what each attempt
carries over: a write rebuilt from plaintext (a name, a meta edit from fields, a bucket name) re-seals under the key a
re-read hands it, but a write that carries ciphertext made under the OLD key — a cover or banner whose blob was
uploaded wrapped under it, a medium's meta edit whose base is the sealed meta as read, an import's own steps — cannot
be retried after the key changed without re-uploading or re-reading the item. Five such paths surfaced only at
implement time and went to inbox #563. So when a CR adds a retry after a key change, the inventory sorts every path
into *re-sealable from plaintext* and *carries old ciphertext*, and the second kind gets its own decision.

### A refine that removes a control lists every role and share root that reaches it

`cr-move-browser-and-replace-by-upload` removed the replace from every surface, the opened file's included, after
reading only the folder flows. A `READ_WRITE` link on a single file (`FilesShareRoots`, three screens further down in
the same `docs/apps/files.md`) writes through exactly that control, and nothing else would have been left for it. The
gap surfaced at implement time, stopped the CR midway, and the owner reversed half of the decision. So before a CR
takes a control away, its refine greps the matrix row, the share roots and the role hints that name that control
(`grep -n <capability> docs/apps/<app>.md`, the `roleContributorHint`-style strings). It then writes down, for each
role, what that role can still do.

### A page that tells users what to click is walked in the client before it counts as written

`cr-use-area-content` wrote two of its eight phases from `src/web/src/i18n/index.ts` and the components, and the later
walk in the running web client still corrected each of them several times. Some controls exist only behind a
switch: *Determine location automatically* appears only once maps are enabled. Some labels depend on state: a
contributions group says *You* only until you have added something. A folder row's menu has no *Move to…* at all.
A guest drop page starts over with *Not you?*, not with the string the drop-box namespace defines. None of that
shows in a catalog. So a CR whose output is user-facing steps (a Use page, an onboarding text) walks every step in
the running client with a test account and a fresh instance. It writes from the walk and corrects the page against
it, and it names in its CR what it could not walk (a second device, a camera, a third-party map) instead of
implying it. A page written from code alone is a draft.

### A decision that puts a write "in its transaction" names the layer that owns that transaction

`cr-guest-write-record` placed its recording "at the resource layer, after the write succeeds and in its transaction".
No resource in the backend holds a transaction: every guest-write handler opens its own (`createOrConflict`,
`QuarkusTransaction.requiringNew`), and an upload's lives in each application's attach behind core's completion seam.
The implementer split the decision — the resource resolves the client once and hands it down, the handler records in
its transaction, and the seam grew an `attached` hook the attach calls last — which held the intent but was a design
call the CR had not made. So a refine that ties a new write to an existing one greps where that write's transaction
begins (`@Transactional`, `QuarkusTransaction`, `createOrConflict` along the call path) and names that layer, and for
a write behind a seam, the hook through which the seam's implementations take part.

### A CR that tells "the affected" lists, per kind of recipient, the channel that reaches them — or says "nobody"

`cr-statement-of-reasons-and-objection` sent a statement to "the share's creator, the uploader and the scope owner",
and two of its sentences disagreed about guests: its context let a guest object through the contact "where the
statement offers one", while ADR 0093 § 4 counted "a guest uploader with no contact" as nobody to tell. A guest has no
account, so no mail and no list of statements; whether its placeholder shows one is a design question the CR had not
asked, and the implementer had to pick a reading. The same held for the reporter's decision: the address it needs is
stored only sealed, so only the deciding moderator's browser can supply it — a channel no sentence named. So a refine
that tells someone something writes a row per recipient kind (member, guest, reporter, Server Owner) with the channel
that reaches them (in the app — on which screen —, by mail — from which address the server holds in the clear —, or
none), and the measures or steps the duty does NOT reach (the Server Owner's own steps carried no statement here).

### A refine that promises a viewer sees a switched state names what that viewer's client is handed

`cr-application-switches` promised that "a `READ_WRITE` link guest sees it read-only" with an application off, and no
sentence asked how the guest's page would know: a link's resolve carries no tenant setting and the tenant's capability
set is members-only. The question reached the owner in the middle of the implementation and moved the decision twice
(no new field, the links keep serving, the members get a banner, the guest learns it from the refused write). So
before a refine promises a viewer a state, it greps what that viewer's client is given — its resolve, its manifest,
its access — and either names the field that carries the state or writes "not told" into the decision.

### A switch that holds back a side effect names where it is entered before the effect fires

`cr-authority-orders-and-art18-reports` decided the authority's deferral as "a row on the statement", and a statement
goes out with its measure in one transaction: a deferral entered on the statement afterwards would have deferred
nothing. The implementer had to put it on the two requests that fire the statement (the order, the measure) and leave
the separate operation to the renewal. So a refine that adds a hold-back, an opt-out or a delay names the request it
travels with, ahead of the effect — or says why the effect waits for it.

### A CR that writes a legal text derives its surfaces from the duty, not from the product

`cr-operator-section-of-the-terms` listed where the operator section shows from where a space's terms already showed —
the member gate, the share gate, "the login page once it exists" — and grounded each against the code, as the rule
asks. The law named other surfaces: DSA Art. 14(1) wants the terms publicly available without an account, Art. 11–12
and TCO Art. 15 a public point of contact, § 5 DDG an identification permanently available; a legal audit added all
three mid-implementation. Code and ADRs cannot answer for a duty they never encoded. So a refine that writes a legal
text lists, per duty it serves, the surface and the content the law names, and only then fits the built surfaces to
it; where the build cannot back a sentence the law asks, the CR names it as a legal blocker with an owner rather than
leave the implementer to choose between the code and the law.

### A new required response field is a fixture change wherever that response is built

A contracts phase that adds a required field to a RESPONSE predicts its reds where the server constructs it — and misses
the clients' test fixtures, which construct the same object to stub the server. cr-gallery-list-paging's phase 1 said
"web check green: only optional request parameters were added"; its required `more` reddened 23 web fixtures, and its new
query parameters changed the generated JAX-RS signature the CR had not named either. At refine, grep the tests for a
literal of the response (`identities: [`, `mediaRevision:`) as well as the server's constructor.

### A set the CR says is derived is derived at refine too, and an annotation is proved on the wire

When a decision says "the set is derived from the contracts, never listed", the refine's own hand list of it is the
estimate the derivation exists to replace: cr-gallery-list-paging listed nine operations whose answers carry a token, and
its derivation found sixteen — the refine's grep stopped at top-level schemas and missed the presigned URLs nested in
items. Run the derivation's predicate at refine, not a grep that paraphrases it. And a framework annotation is a claim
about the framework until a request shows it: `@Uncompressed` on an implementing `override` is not read by Quarkus REST
(it reads the method that declares the endpoint — here the generated interface), and only the wire test's refusal that
the method answered itself showed it.

### A contract that makes a client-sent field required lands on `main` together with its client phase

The paragraphs on contracts phases above let a *branch* be red between the contracts phase and the client phase; they
do not let `main` be. cr-scope-rotation-rekeys-nodes merged its contracts and backend phases while its web phase was
still open, and the contract had made `nodeEpoch` required on twenty writes: until the web phase landed, `pnpm run check`
in `src/web/` was red on `main` (24 call sites) and every sealed write from a web client built from `main` was a `400`
against a backend built from `main` — the owner's dev loop and every session working on `main` with it. Contracts →
backend → web is the order the *work* is done in, not the order it may *merge* in: a CR whose contract makes a field the
client sends required merges its server and client phases in one merge, or keeps the field optional until the client
phase merges.

### A multi-job write flow names, per write, the state a failure right after it leaves — and who puts it back

cr-release-promotes-a-tested-snapshot (2026-11-06) planned the promotion's writes in order — move `<major>` and
`latest`, move the draft onto `v<version>`, push that tag — and never asked what the world looks like if the run dies
between two of them: `latest` would have named an image whose release never published, and a run that had pushed the
tag could not be retried, since its own checks refuse an existing tag. The build reordered the channels after the
publication and added the `refused` job that rolls a half-done run back. So a refine of a workflow with more than one
write lists, for each write, what a failure just after it leaves and which job or person restores it.

### A done-when names the proof; a command is one way to get it

The same CR's phase 1 named `run_pipeline.py --job version` as its bar, and the phase's brief forbade pipeline runs
(the owner's rule of that phase) — a bar the implementation could not meet as written, though the guard's executed
scenario proved the same thing. Name what must be shown; the command is an example of how. And before a CR decides to
*record* a value somewhere, grep where it would come from: the same CR planned to record the major entry, which the
tools image already carried (`/opt/veyq/release.json`).

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
promoted to a permanent guard (or the CR notes why a one-shot check suffices). The docs site's pages that speak
for what changed are updated (EN+DE for Use pages) and `pnpm run check` in `site/` is green — or the CR says why no
page was affected.

## Retrospective
What didn't go well — in planning, execution, AND process/collaboration (did we honor the conventions
+ feedback we already agreed — language, style, workflow, the memory notes?) — root cause, and the
concrete fix. Both directions (assistant misses + human direction). End with a forward-looking "next
time": one concrete, actionable change. 2–3 bullets, or one line if nothing did.
```
