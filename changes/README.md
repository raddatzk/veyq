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

**A contracts phase that ADDS an operation is the one phase that cannot end green everywhere, and that is
correct rather than a violation of the rule above.** The generated `…Api` interface gains a method the moment
the spec does, so the `…Resource` implementing it no longer compiles until the backend phase — there is no
ordering that avoids this and no reason to want one. Its "Done when" is therefore the checks that *can* run
(`npm run generate:api` + `npm run check`, the contracts guards), stated as exactly that; do not reach for a
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
implementer runs it before the first edit** — one line, before anything can be attributed to it.

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
**client**, so #99's refine wrote "PIT against the Gradle/Quarkus setup" with `backend/CLAUDE.md`'s *"no content
crypto"* loaded every turn and the repo map already naming `web/src/shared/crypto`. This is not a locator being
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

**And its mirror, when the CR MOVES code rather than adds it: cross-imports find who reaches IN, never who a
file is scoped FOR.** The fourth tell asks whether the person in the exception flow can reach a new surface;
this one asks who a file already belongs to before you move it somewhere they cannot go.
`cr-admin-web-extraction`'s refine classified `web/src/features/admin/**` by directory plus a cross-import
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
`rg -n badRequest web/src` returns empty and takes one second. It cost three additive lines there, which is
precisely why it is worth writing down: the same miss over something that **cannot** be added additively is
the expensive version of it.

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
`MediaHandler`'s chunk-key enumeration for the expression; those two sites disagree on exactly the line that
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

### Coverage is part of the work, not a follow-up

Every CR updates `docs/coverage/` for the
use-case(s) it `satisfies:` — each new acceptance criterion gets a test ref (or an explicit `gaps:`
entry with a reason) — and leaves `python3 scripts/validate_coverage.py` **green**. A CR is not done
while the trust chain is red. (A use-case refined ahead of any implementation ships a `status: planned`
coverage file that gaps every target surface.)

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
*"`web/CLAUDE.md`'s jsQR exception holds only while our own codes stay low-version"* and no phase touched that
file. The stated invariant held — the new payload is QR version 4 — but the exception's prose **enumerates the
markers**, so a third marker falsified it silently while the named bound stayed true. An invariant list is a
list of things that can break; each entry owes either a phase or one sentence saying why nothing can reach it.

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

**Which makes "can I commit at all?" a precondition of the first phase, not a question for the first commit.**
A phased CR ends each phase with the repo in a verifiable state, and *verifiable* is the committed tree — so a
session that cannot commit is a session holding that rule open across every phase it finishes.
`cr-account-key-rotation-after-compromise` lost the signing agent to an idle-lock after its contracts phase and
carried four more phases uncommitted; nothing was lost, and every green in that window was weaker than it read.
Confirm the commit path works **before** phase 1 — it is one throwaway commit, and the alternative is
discovering it at the point where you have the most to lose.
**Where a CR's done-when says "green in CI", know which jobs can even run.** Two independent gates, and both bit
during cr-e2e-orchestrator:
- **Branch filters.** [`ci.yml`](../.github/workflows/ci.yml) triggers only on `main` and on PRs *targeting*
  `main`, so pushing a feature branch runs **nothing**. That CR's Phase 5 said "push the branch so the job runs";
  the push produced zero runs. `ci.yml` carries `workflow_dispatch`, so `gh workflow run ci.yml --ref <branch>`
  is how a branch gets a real run.
- **`workflow_dispatch` resolves against the DEFAULT branch.** A workflow file that exists only on the feature
  branch cannot be dispatched at all — `HTTP 404: not found on the default branch`. This once described
  `trust-chain.yml`, `mutation*.yml` and `wiki-sync.yml`, which lived on a feature branch and not on `main`; that
  is **no longer true** (measured 2026-08-17: `git branch -a` → `main` alone, and all seven workflow files are on
  it). Keep the gate written down anyway — it is a property of `workflow_dispatch`, not of one branch, and the
  next long-lived branch meets it again.
- **A third gate now outranks both, and it is the one in force: there is no remote.** `git remote -v` is empty
  (measured 2026-08-17), so **no CI run has ever executed** — not the trust chain, not `ci.yml`, not `web-e2e`.
  No branch filter and no dispatch trick changes that; only publishing the repository does, which is the owner's
  call and tracked as inbox **#26** item (1). Until then, a local run over the **committed** tree is the
  strongest available proof, and a done-when must say exactly that rather than "green in CI". The two gates
  above stay because they are what bites *after* the remote exists.

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

cr-node-delete-reversible-trash paid for both halves of this in one session. Its six bite-tests were four live
defects and two injections, unmarked — so one injection-shaped test was run against the untouched tree, came back
green, and had to be re-planned. A third failed differently: it was a live defect whose **setup** depended on a
fix scheduled later in the same phase, so its first red landed on a `createNode` in the arrangement rather than
on the assertion (§ *a live-defect red can land on the wrong STEP*, below). Marking the method also orders the
phase, which is the second thing it buys: the live defects run first, against the least-changed tree there will
ever be.

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

### A bite test must tell "the guard held" apart from "nothing ran"

**Assert the expected test goes red by name, never that "something failed".** An injection can break compilation instead of the assertion, and a
harness that greps for a failing test name then reads *no match* as **green** reports the exact false negative
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

### A build step that emits an executable artifact is not done until the artifact RUNS

**A green build proves the builder was happy, not that the thing it produced works.** cr-admin-cli's esbuild
invocation lives in a `package.json` script, so the shell ate a layer of quoting and
`--define:process.env.NODE_ENV=\"production\"` reached esbuild as a bare identifier. esbuild accepted it —
defining a symbol to another symbol is legal — bundled cleanly, printed its size, exited 0. The artifact then
died on its first line with `ReferenceError: production is not defined`. Nothing in `npm run check` would ever
have caught it; only `node dist-cli/cli.js` did. So the "Done when" for any phase that emits a runnable
artifact (a bundle, a binary, a container) names **running it**, never just building it — and shell quoting
inside a JSON string is a specific place to look first, because it fails silently in exactly this way.

### An ADR's cost premise expires separately from its decision — re-measure it at implement time

**The decision can still be right while the reason it was cheap has gone stale.** ADR 0065 chose esbuild "already
a transitive Vite dependency, not a new tool". True when written; false at implement, because Vite 8 bundles
with rolldown and `npm ls esbuild` returns empty across the whole tree. The decision survived on its own merits
and the ADR carries a measured amendment — but the *cost* it was accepted under had changed, and only running
the locator revealed it. This is the same instinct as "run the locator, don't trust the tally" one section up,
pointed at the justification instead of the inventory: **a parenthetical that makes a decision look free is a
claim, and claims get re-measured.** A CR that already knows to re-run its own grep should also re-run the
"this costs nothing" check.

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

**The same instinct, pointed at the CR itself: a phase can specify work a convention forbids.** The same CR's
doc phase listed two applied Flyway migrations as "edited". `backend/CLAUDE.md` forbids that outright, and the
reason is not style — `migrate-at-start` validates each applied migration's checksum, so a one-word comment fix
fails startup on every instance that already ran it, and `%dev`'s `repair-at-start` hides exactly that on the
machine where you would test it. The live copy of the rule moved to the entity KDoc instead. **Read the target
surface's own `CLAUDE.md` before executing a phase that edits it** — a CR is a plan written elsewhere, and the
conventions live where the files do.

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
