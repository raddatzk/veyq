# Working with veyq

How we build veyq. Concise on purpose; this is the entry for every working session. The hard
invariants in [CLAUDE.md](../../CLAUDE.md) override everything here.

## What the docs are for — and who reads them

**Two audiences, and the project's owner is neither:**

- **The agent implementing** — needs the current state, stated **once**, precisely. Duplication is the tax it
  pays on every task, and it does not stay honest: `uc.platform.capability-discovery` kept its own copy of the
  seven acceptance criteria that already lived in its coverage file, and by the time anyone compared them the
  two had **already disagreed** about what sharing gates on. Two copies are not twice the truth; they are one
  truth and one lie, and nothing says which.
- **Someone outside judging the project** — reads it on GitHub, for twenty minutes, and never installs an IDE
  plugin. A format only IntelliJ renders is a diagram nobody sees.

**The owner reviews in conversation, before the work — not by reading `docs/`.** So: describe what a refinement
or a CR is *actually about*, in two sentences, and let them stop it. That exchange is the review loop, and it is
the only check that can ask **"do we need this at all?"** — no validator can. The prose-to-product ratio is
currently healthy (~12k lines of docs against ~50k of source), and it is worth knowing what that cost: it was
last corrected by **deleting** ~2.6k lines in one pass, not by writing carefully. Prose grows by default; only
that question shrinks it.

Five rules follow:

1. **Deleting beats reformatting.** Success is lines *removed*. Bullets vs. prose is roughly token-neutral;
   duplication is not. Never restructure what you could delete.
2. **Point at one canonical source; never restate it.** A restated list drifts from the one that runs — the CI
   job is the guard registry, the ADR owns its rule, the use-case owns its behaviour.
3. **No guard unless a human would notice the defect.** Ask what it *cost* before building one: three diagrams
   failed to render for 13 days at an observed cost of **zero**. Thirteen unnoticed days are evidence nobody
   looks — not evidence of urgency. The alternative to a guard is usually a format or structure that cannot
   break quietly.
4. **A claim nothing backs is a lie with a reason attached.** Coverage no CI runs, a diagram no reader can
   render, a work-order nobody reads. Cut it, don't annotate it.
5. **Don't offload meaning to git.** This rule was written against a planned move to a fresh repo, and that
   move has happened — so it is no longer a precaution held open against a maybe. This repo's history begins
   at its import; the implementation it replaced is archived and still readable at
   [`raddatzk/veyq-no-e2ee`](https://github.com/raddatzk/veyq-no-e2ee). Nothing crossed but the working tree,
   which is the rule stated as an event: **what must survive lives in the working tree, or is knowingly let
   go.** The obligation that leaves is on the docs, not on the repo. Work-order ids (`cr-…`) are **provenance
   labels, not resolvable pointers** — a CR is deleted when done, and no `git log --grep` recovers what it
   said. So a doc that cites one carries the whole reason in its own sentence; the id records where a decision
   came from, never where to go read it. A reason that only a `git log` holds is a reason the next reader will
   not find.

## State vs. flow

- **State** = what veyq *is* (durable truth):
  - `docs/architecture/`, `docs/crosscutting/`, `docs/features/<feature>/use-cases/`, `docs/decisions/`
    (internal engineering docs, **English**),
  - `contracts/` (OpenAPI + crypto vectors),
  - `site/` (the **published** documentation site — Use in plain language **EN+DE**, Run and Security in English;
    [ADR 0087](../decisions/0087-published-docs-are-a-starlight-site-docs-is-for-development.md)).
  - State is only updated — never a dumping ground for raw ideas.
- **Flow** = what we're *changing*: `docs/inbox.md` (raw topics) → `changes/` (change-requests).

## The pipeline

1. **Inbox** (`docs/inbox.md`) — a single status table of topics. Low friction.
2. **Refine** — pull a topic and refine it *with the user* into durable state. Always check the new
   fits the old: reconcile into existing crosscutting concerns and features/use-cases; never create
   orphan concepts. Durable decisions → an ADR in `docs/decisions/`. This is a sparring
   conversation, not transcription. **Scope each refinement** — don't refine all of crypto at once.
   **And re-derive the row's question before answering it — a row records the symptom it was written from, not
   the problem.** On 2026-09-21 five rows were refined in one pass and **all five** named the wrong open
   question: #140 asked whether a dead-CSS guard's exceptions could be *derived* (they could — the blocker was
   that 71 % of the corpus is dead, so the guard would be red on the day it was born), and #144 asked whether
   three near-identical dialogs should merge (there are five, and they share no contract, so what is duplicated
   is chrome rather than confirmation). A row is written at the moment of discovery, when only the symptom is
   visible; asking its question again against the tree was the whole value of each of those refinements. This is
   step 3's *"re-derive it, don't trust the status word"* applied one step earlier — to an **open** row, where it
   is cheapest.
3. **Diff the refined state against the code** — scoped to what you just refined, **measured, not recalled**
   (every count is a cited command's output). This is the step that closes the loop: without it the pipeline
   only runs forward, and nothing ever asks *"is this still true?"*. Read it **both ways** — a disagreement is
   a **question back to the refinement** ("which side is right?"), never automatically a work item:
   - **Code ahead** — a CR changed behaviour and the doc never learned. The doc is the stale side; do **not**
     plan the code back to it. cr-0123 relaxed the bucket gate to member — *"the rule the user chose"*, in its
     own words — while ADR 0034 §6 and its enabler kept asserting the owner-only `owner_key == caller`; the drift
     even survived a **false close** (a review finding waved through as "already-resolved" without re-measuring)
     before #87 aligned the docs to the code. A close is a claim like any other: **re-derive it, don't trust the
     status word** — that false close had misread `owner_key == caller` as *member*-gated, the very thing it
     wasn't.
   - **Doc ahead** — a decision nobody built. *That* is the CR.
   - **Doc unbuildable** — it asks for something the stack has no form of, so the code never could follow.
     A migration was once told to add a partial unique index the H2 ∩ MariaDB subset does not have.

   **A measurement is only as true as the tree it ran against.** *Measured, not recalled* guards the **claim**;
   nothing guards the **substrate**. The command ran, its output is real, and it still proves nothing if it read
   the wrong tree — a stale checkout, another branch, a sibling repo mid-edit. So name the substrate before the
   sweep, not after the correction: which branch, how current, what is uncommitted. Observed, not imagined: on
   2026-07-27 a delegated inbox sweep ran while a **parallel session** was editing `docs/inbox.md` underneath it
   (`wc -l` → 126, ten minutes later 127) — it caught the shift itself and qualified its numbers, which is the
   save. Two consequences: a measurement whose substrate is *named* can be re-run, one whose substrate is
   *assumed* cannot be checked at all; and where the question is a **status** ("is this built?"), the substrate
   is the **whole branch** — `git log --all --grep` plus the files' existence — never the recent log window,
   which is how the 2026-07-22 sweep found seven rows that had been stale for weeks.

   **The same rule reaches a LIVE verification, where a wrong substrate reads as a product bug.** On 2026-09-20
   a hand pass against the running app reported "the sidebar shows no cover" twice. Neither reading was about
   the code: the first page had a stale hot-reloaded module (`ReferenceError` in the console), the second was
   measured on a page that had not finished booting, and the dev server was meanwhile serving a tree three
   parallel sessions were merging into — one of which briefly 500'd the app entry. A browser observation is a
   measurement like any other: **wait for a state, not for a duration; read the console and the failed-request
   list before believing the DOM; and name which tree the dev server is serving.** The fix that pass did find
   was real, but it was found only after the substrate was established — everything before that was noise
   presented as evidence.

   **And a UI slice is not done before it was clicked in a real browser.** The public folder page of
   cr-node-key-hierarchy landed with 33 new unit tests green and two defects only a browser showed: a breadcrumb
   whose `href="#"` cleared the share key from the URL fragment, and a card's actions hidden by a width rule at
   every width up to 1120 px. Each test had asserted the programmatic path (the `push` it was handed, the element
   in a stubbed DOM); none clicked what the browser rendered. Unit tests prove the logic; the click proves the
   screen.

   **A live parallel session is a hazard for WRITES too, and this one bites harder than a stale count.** Commits
   here go straight to `main`, so a second session's tip and its uncommitted tree share yours. Two moves are
   therefore forbidden regardless of how clean the tree *looked* when the phase started: `git add -A` (which on
   2026-09-20 swept a neighbour's in-progress CR and inbox rows into an unrelated docs commit) and any
   `git reset` by relative ref such as `HEAD~1` (the same session then reset what it thought was its own tip,
   hit the neighbour's four newer commits, and a `--hard` discarded a whole finished phase's uncommitted work —
   recoverable only because every edit was still in the conversation). **Stage by explicit path, and address a
   commit by its hash, never by its distance from the tip.** An explicit path is not enough on its own: `git rm`
   stages at once, so a later phase's deletion rides along with an earlier phase's commit (it did on
   2026-09-27, when a failed `git add` left only that deletion staged). Read `git status --short` between the
   add and the commit, column by column: `git mv` stages the rename with the OLD content, and an `RM` line is that rename
   with its edits unstaged - a staging filter that skipped `RM` lines committed a renamed module's old body under its new
   name (cr-download-is-the-export, 2026-10-14; the next phase's commit carried the fix).
   The index itself is shared too: `git mv` and a neighbour's `git rm` both stage into the one index every session
   commits from, so a plain `git commit` takes whatever is in it (on 2026-10-15 a neighbour's staged CR deletion sat
   there while cr-files-becomes-depot committed). The form that cannot sweep anything up builds the commit in an index
   of its own: `GIT_INDEX_FILE=<scratch> git read-tree <tip>`, `git add` by explicit path (or `git update-index
   --cacheinfo` for a file whose working copy already carries the next phase), `git write-tree`, `git commit-tree -p
   <tip>`, then `git update-ref refs/heads/main <new> <tip>`, which refuses when a neighbour moved the tip in between;
   afterwards `git reset -q -- <your paths>` re-syncs the shared index for your paths only.

   **And the rule reaches a *delegated* read, where nobody can apply it from inside.** A subagent measures
   whatever tree it is pointed at and cannot know which parts of it this session already moved. A `web/`
   mapping spawned just after an implementer's first `npm run generate:api` reported the work order's
   own — correct, when written — *"the generated client does not carry this verb"* as **stale**: which reads
   as a fault in the work order and is in fact the effect of the step above it. So **name in the subagent's
   prompt what you have already changed**, or its report hands back a contradiction only you can resolve.

   **What this step cannot see: a rule the docs never wrote.** A public share served trashed media for the
   whole retention window because the trash CR and the sharing CRs both read `veyq_media` and never met —
   the doc↔code delta there was **empty**, and the defect was real. Doc drift and cross-feature gaps are
   different problems; this step finds only the first.
4. **Change-request** (`changes/cr-<slug>.md`) — a self-contained, phased work order derived from
   the refined state. See [`changes/README.md`](../../changes/README.md). A CR is **scaffolding, deleted when
   done** — the durable truth lives in the updated state + ADRs, and what the work *taught* is moved to where
   it binds before the file goes (rule 5: not into git).

> **Refined state comes before the CR — always.** A change-request is only written once the durable state it
> builds on already exists as written docs: its **use-case(s) (`uc.*`)**, **enabler(s) (`en.*`)**, **crosscutting
> concern(s) (`docs/crosscutting/`)**, and any governing **ADR(s) (`docs/decisions/`)**. A CR's `satisfies:` /
> `adrs:` must resolve to docs that are already there — **never invent the concept inside the CR**. The concept is
> decided in refinement (step 2, with the user); the CR only turns it into a work-order. And a CR grants no
> licence to build: **implementation waits for the user's explicit go-ahead on that CR.**

## Context topology: refine with the user, implement in a subagent

Refinement runs **in the conversation with the user**. Implementation runs in a **subagent** started from that
conversation. The subagent is the fresh context that the earlier rule ("one phase per session") got by opening
a new session. It gives the same clean room without the user having to open one, and the main conversation stays
free of implementation context.

- **Refine (the conversation).** One CR. Ground it in recent **retrospectives** and in targeted subagent spikes
  over the surface. Produce a tight CR: decisions, inventory, and a Definition of done that names the guard which
  enforces it. Delegate broad reading to subagents so the main context stays lean. If the conversation is already
  long, or carries unrelated work, start a fresh one for the refinement: it is the highest-leverage phase, and it
  deserves its best conditions.
- **Implement (a subagent), after the user's explicit go.**
  - **Brief.** The subagent starts with fresh context. Its brief is **the CR's path, the working rules, and
    machine hints marked as such**. A machine hint can hold only for the CR's planned
    form, so the implementer re-derives it against what it actually builds. Nothing else. Anything the implementer needs that the brief would have to explain belongs
    in the CR, so write it there and not into the prompt. A brief that carries knowledge the CR lacks voids the
    clean-room proof, exactly as a warm session did.
  - **Questions.** The subagent cannot ask the user. A decision the CR does not settle is **handed back**, never
    decided in place: commit what is finished, then return the question with a recommendation.
  - **Output.** Code, proof, a filled retrospective, and a report back to the conversation.
- **The conversation does not implement a CR itself.** So it never carries implementation context into the next
  refinement. The old rule that refinement never runs on an implementation's tail is now true by construction.

**Implementing in the conversation itself is a named, risk-based exception, not the norm.** It is allowed for a
CR that is **tightly scoped, mechanical and behaviour-preserving**, and that is backed by a **strong automatic
gate**: for example `./gradlew check` over a broad slice-test suite that pins the behaviour, as in cr-0161. The
gate then stands in for the clean-room completeness proof, because a fresh implementer would only re-derive what
the suite already verifies. **Design-heavy, multi-surface, or novel-abstraction CRs, where the CR is the main
safety net, go to a subagent.** When in doubt, delegate.

**Parallel implementers share one working tree.** Two subagents that edit the same files (a CR, a README both
touch) run one after the other, not side by side. Every implementer stages by explicit path, as § The pipeline
step 3 says.

**The closing process retro is mandatory and spoken, not just filed.** When the implementer's report arrives, I
proactively tell the user, in the conversation and not only in the CR's `## Retrospective`, **what could improve
about *how we work together*** (the Arbeitsweise): the process, the collaboration, the tooling, and the
conventions we did or didn't honour. It draws on the subagent's process observations and on my own from the
refinement. It runs **both directions**: my misses *and* the user's (a premature instruction, a missing
constraint, an internally inconsistent CR). It happens **every time, even when the technical work went
cleanly**, because a clean run still teaches process. Keep it to 2–4 concrete, actionable points, and invite the
user's side. This is the compounding channel that makes the *next* refinement better. The filed retro is its
durable record; the spoken hand-off is what actually reaches the user.

Why the split holds:
- **The retrospective is the channel from implementation to the next refinement**, not a warm context window.
  Lessons an implementation surfaces (e.g. "an entity relocation touches every referencing unit *and* its tests")
  land in the CR's retro. The next refinement reads them there, in durable state.
- **Clean-room implementation is a quality gate.** A fresh implementer *proves* the CR is complete, and a
  question it has to hand back is the cheap signal that it was not. That proof is void if the implementer was
  told what the CR leaves out, whether through a warm session or a fat brief.
- **Keep a shallow buffer (1–2 refined CRs ahead), not a deep queue.** A CR refined far ahead goes stale as
  neighbouring code drifts. Refine just ahead of need.

## Refinement checklist

A refined CR nails **decisions, not keystrokes**:
- **Decisions over keystrokes.** Fix the *what / why / shape* — does this belong in `shared`? which invariant is
  at risk? what is the Definition of done? Do **not** script line-level edits ("change line 46 of X"): prose can't
  compile or be tested, line numbers drift, and a `grep`/build finds the exact sites for free at implement time.
  Name the *inventory* (which files/surfaces, as a table) + the *decisions*; implementation discovers the exact
  *where* (which line, which site). **The inventory hands over the command that finds the sites, not a tally of
  them** — the number answers *"is this worth doing?"* here, in this conversation, and then has no reader; the
  command is what stays true while the code drifts. Format + the traps:
  [`changes/README.md`](../../changes/README.md) owns that rule. **The inventory greps the *test* trees too, not
  just source** — a symbol move/rename's blast radius includes every test that references it: cr-0160's guessed
  "~8 cross-slice imports" was really +2 same-package users **and 22 test files**, a gap a `grep -rc` at refine
  would have closed instead of the build. **And it greps the *flow* surfaces — `docs/inbox.md` + `changes/` —
  whenever the CR deletes or renames a symbol**: `cr-token-bucket-rate-limit-core`'s locator covered code,
  contracts and `docs/`, and the row left naming the deleted class was inbox #114's inventory line — a *live*
  finding made unreadable by a rename nobody pointed at it. **And a CR that narrows what is accepted greps the tests
  — web e2e included — for the input it newly refuses**: cr-guests-create-no-application-nodes refused a guest's
  Memory, and its locator (the rule's own names) missed a slice test and an e2e that created one through a helper.
  **And it names the site pages the change touches** — a change to what a reader sees or does has published pages
  to update in the same CR ([`changes/README.md`](../../changes/README.md) § The published docs move with the change).
- **Name the control an input arrives through, per surface, and cite the line behind "as X does".** A refine that
  adds an input names where the user gives it: cr-download-is-the-export decided a picked directory and a drop box's
  ZIP without saying where the picker sits or reconciling the use case's E5, and both came back as owner questions.
  A sentence that leans on a neighbour ("a startup failure, as the other seams handle it") cites the line it means:
  in cr-guests-create-no-application-nodes no other seam did, the refine had described them from memory.
  The same holds for a core feature over application nodes: name the registry hook that carries each kind's answer
  (cr-select-nodes said "capabilities decide per node", and core may not call an application's capability function -
  the implementer had to add `LevelRow.selection`).
- **Run the locator here — from two angles, and read the output as a list.** Handing one over unrun is the
  estimate wearing a command's clothes. And the first command you reach for is a paraphrase of what you already
  believe, so it fails silently wherever that belief is wrong: cr-0171's refine measured "246 scalars" with a
  probe that knew one of OpenAPI's three nullable spellings, and asking the same question on a different key
  (`$ref` in the property, not `type: array` on it) answers **60** where the probe says 49 — the gap is the
  finding, and it names the 11. The bullet above is the standing example of a second angle, remembered because
  cr-0160 paid for it; the general rule and when it is worth the cost live in
  [`changes/README.md`](../../changes/README.md). This is also where an abstraction gets sanity-checked against
  reality before the CR freezes it — the list is the spike's cheapest form.
- **When the CR mirrors an existing proof, port its *defences*, not just its subject.** A CR whose job is "prove
  this at a new altitude" has a reference test, and refinement reads it for *what it asserts* — which is the
  cheap half. The expensive half is *how it keeps a green from being accidental*: its controls, its status-code
  anchors, what it splits apart so a failure names itself. cr-sharing-contributor-e2e set out to mirror
  `ShareTrashedMediaSliceTest` and specified **two bare negatives**; the file it was mirroring had already solved
  that, with a live control per negative and the reason in its own source (*"the filter must not over-reach"*) —
  because a share that 404s wholesale satisfies a lone negative perfectly. Two rules fall out, both general:
  **every negative assertion owes a positive control on the same predicate**, and **a negative anchors on the
  specific observable** (`isHttpStatus(e, notFound)`), never on "it threw" — a bare rejection is equally produced
  by a network error, a boot failure, or a crypto failure. All three gaps were sitting in the file the CR already
  pointed at. And **a crafted negative input fails exactly one check**: in cr-download-is-the-export four refusal
  tests stayed green with their named check removed, because an earlier check refused the same input first. And **a
  proof of a transition says whether its recorded run must cross one**: cr-upgrade-proof's first green claimed a
  migration that no build in the run carried. And **a guard's list of what it refuses derives from the authority's rule, not from
  today's instances**: cr-helm-upgrade-check's refine listed the StatefulSet fields the chart sets, the API server
  refuses every field outside a small mutable set, and the build had to widen it.
- **A CR that changes what a hook is handed traces every reader down to the native API**, not only the first one: cr-copy-node
  fed a chunked source to the upload pump (fine) and to Memories' import, which decodes whole (`createImageBitmap`) - a
  question back to the owner the refine could have asked. And a list of fields in a decision says whether it is
  **exhaustive or illustrative**. A **mode flag** that unlocks trust (a copy's extras riding content an archive also
  carries) comes from the caller, never from the data. A CR that edits a pre-release baseline says there are **no existing rows** (every dev
  database is recreated) rather than designing for legacy rows that cannot exist (cr-file-uploader). A CR that changes an
  identifier greps the client's local records keyed by it too (cr-change-email found the remember-me record and the
  session vault only by following the address end to end). A proof that seems to need a root Linux host (a file's owner in a
  Docker volume) usually needs only a root container that mounts the volume (cr-restore-places-the-key-as-the-runtime-user
  waited two weeks on the owner for a run an agent then did in an hour); and an expected log line in a CR is measured, not
  remembered.
- **Price a confidentiality layer against what the same attacker already reads.** `cr-guest-names-for-members-only`
  was refined to a per-Memory name keypair under `K_SC` so that *a link plus a database dump* could not read guest
  names — an attacker who, with that same link and dump, decrypts every photo of the Memory. The owner took the
  response-only fix and a stated residual instead, and the refine's own inventory already held the fact that
  settled it (another link's guests were reachable that way before the CR). Before designing keys for a field,
  name the weakest attacker the new layer stops and what that attacker reads anyway, and put the cheap variant
  with its residual beside the expensive one.
- **Spike when the shape is unclear.** The right abstraction often only emerges from N real usages (that is why
  `core/membership/MembershipStore` came out clean — 5 usages, not a guess). If refinement can't settle the
  shape from reasoning, run a time-boxed subagent spike over the surface and fold the inventory back in — don't
  guess an abstraction into the CR.
- **`satisfies:` decides where the acceptance criteria land — so decide it, don't inherit it.** A criterion
  that describes a *property with several triggers* belongs to a use-case of its own, not under whichever
  operation's use-case the CR happened to name; the tell that it went wrong is the same refs appearing under
  two use-cases. Rule + the worked example: [`changes/README.md`](../../changes/README.md) § coverage.
- **Carry the enforcement guard into the Definition of done.** If the CR rests on a structural invariant ("no
  duplication of X", "Y lives only in `shared/`"), its one-shot proof grep becomes a **permanent** guard — see
  [`changes/README.md`](../../changes/README.md).

## Two documentation audiences

- **Internal** (`docs/`): precise, English, for devs + agents. The technical security model lives here.
- **Published** (`site/`): the Use area in plain language, **EN+DE**, for the **non-technical end user** and the
  space owner who want to know *what they can do* and *why their data is safe*; the Run area (English) for Server
  Owners; the Security area (English) for researchers.
- **Hand-written pages that name their source.** User-facing prose lives only on the site. A use-case keeps one
  English `## User impact` line on why it exists for the user; the Use pages are written by hand, one per topic,
  and each declares the docs it speaks for, which link it back
  ([`../features/README.md`](../features/README.md) § use-case format).
- **Kept current by every CR.** A change to what a reader sees or does updates its site pages in the same CR
  ([`changes/README.md`](../../changes/README.md) § The published docs move with the change).

## What counts as verified

**The coverage surfaces are `backend` and `web` — that is the whole set.** CR phases follow the dependency
order **contracts → backend → web**.

The rule underneath, from [ADR 0040](../decisions/0040-coverage-gaps-and-http-not-a-surface.md): **a test that
no CI run executes makes no coverage claim.** It is not about which directories exist, or even which surfaces —
it is about what a green build actually proves. Note the granularity, which cost a second pass to get right: the
unit is the **test**, not the surface. `web` is built and tested on every run, and four of its refs still
pointed at `*.e2e.test.ts` files gated behind `describe.skipIf(!process.env.VEYQ_E2E)` — real tests, genuinely
valuable, that every CI run **skips**. A surface being built is no alibi for a test that isn't. Three cases have
failed this rule:

- **`http/`** was IntelliJ requests fired by hand. ADR 0040 stopped counting it; it has since been **deleted**.
- **`web`'s `*.e2e.test.ts`** need a live backend, so they are gated on `VEYQ_E2E`, which only the `web-e2e` job
  sets — and that job carried `if: false` for as long as its orchestrator was missing, which made them a hand-run
  smoke in the same shape as `http/`. Their four refs were removed for it. **This one was paid off, not written
  off:** `scripts/run_web_e2e.py` brings up the bundled stack itself, and the refs came back only after a green
  CI run whose log was read to confirm it had executed four suites. It is the worked example of the rule rather
  than a violation of it — and of the rule's sharp edge, since *"the job passed"* and *"the job ran nothing"*
  look identical from outside, which is what `scripts/validate_e2e_discovery.py` exists to tell apart.
- **`backend`'s `BlobBatchE2ETest`** is gated on `VEYQ_S3_E2E=1`, which no workflow sets, and was cited as
  coverage until 6f19923 — a real file, a real method, a real body, so every check that existed passed. That
  one is now caught mechanically: `scripts/validate_coverage.py` holds each backend ref's JUnit gates against
  the Gradle runs the workflows declare, and rejects what none of them would execute.

**Nothing enters the coverage model before a CI run executes it.** The claim follows the build, never the
intention — and "the test exists" is an intention.

Published for readers outside the project, stage by stage and with what has run: [How veyq is tested](../../site/src/content/docs/security/how-veyq-is-tested.md).

## The verification layer — a map, not a copy

The pipeline above is what we *do*; this is what **checks** it. Every item has exactly one canonical home and
this section only points at it (rule 2) — assembling this picture by hand each session was the cost
[ADR 0046](../decisions/0046-operative-lore-in-skills-and-agents.md) was written against.

- **The guards** — `scripts/`. **The glob is the registry:** CI loops over `scripts/validate_*.py`, so a new
  guard runs the moment it lands, with nothing to register — which is why no list of them exists here or
  anywhere else. **`scripts/` is the list**; each module docstring is that guard's whole documentation, and
  what it must carry is defined once in [`../../changes/README.md`](../../changes/README.md) § trust-chain
  guards. Run the chain locally as CI does:
  `for f in scripts/validate_*.py; do python3 "$f"; done` — with one difference, `validate_guard_gaps.py`, which
  skips locally while no guard changed (`--force` runs it; CI never skips).
- **Three CI runs, three triggers.** [`trust-chain.yml`](../../.github/workflows/trust-chain.yml) —
  deliberately unfiltered and buildless, the whole guard glob; a docs-only change is validated **there and
  nowhere else**, because [`ci.yml`](../../.github/workflows/ci.yml) `paths-ignore`s `docs/**`.
  [`mutation.yml`](../../.github/workflows/mutation.yml) /
  [`mutation-backend.yml`](../../.github/workflows/mutation-backend.yml) are path-gated onto their scope files.
- **What green proves — and where it stops.** A reference **resolves**; not that what it points at carries
  weight. Those limits are **executable, not prose**:
  [`validate_guard_gaps.py`](../../scripts/validate_guard_gaps.py) injects each documented gap into a
  throwaway tree and asserts the guard's documented verdict, so *closing* a gap turns that guard red instead
  of landing silently. The synthesis and the **four principled edges** (oracle / timing / cross-feature /
  scope-completeness — limits, not a backlog) live in the
  [`trust-chain` skill](../../.claude/skills/trust-chain/SKILL.md).
- **Assertion strength** is the one edge no resolvability guard can reach — a test that asserts nothing still
  resolves. [ADR 0045](../decisions/0045-mutation-testing-trust-chain-unit-layer.md) closes it on the **unit
  layer only**, via mutation score (Stryker on `web`, PIT on `backend`), scoped by the two manifests
  [`web/stryker.mutate.json`](../../web/stryker.mutate.json) +
  [`backend/core/pitest.scope.json`](../../backend/core/pitest.scope.json) and self-checked by
  `scripts/mutation_meta_check*.py` — which sit **outside** the `validate_*` glob on purpose (they need a
  built report).
- **The skills layer** — [ADR 0046](../decisions/0046-operative-lore-in-skills-and-agents.md): conditional
  lore loads **on a trigger** from `.claude/skills/` rather than taxing every turn from CLAUDE.md, and it
  **points instead of restating** — with `validate_skill_links.py` keeping those pointers honest. It also
  records why there are **no** `.claude/agents/` role files: *a subagent is a child of the conversation that
  starts it*, so a write-boundary on a `refine` subagent would be a false guarantee. The implementer is an
  ordinary subagent whose brief is the CR. § Context topology above is what actually enforces the split.
- **Claim-provenance** — every claim in a report, CR or handoff is marked **measured** (naming the command
  that proves it) or **remembered**. Carried by the
  [`trust-chain` skill](../../.claude/skills/trust-chain/SKILL.md); rationale in
  [ADR 0046](../decisions/0046-operative-lore-in-skills-and-agents.md) § 4. It is § the pipeline's
  *"measured, not recalled"* applied to prose.

## Working rules

- **Sparring partner, not yes-man.** Critically question proposals — the user's and my own — surface
  disadvantages, risks and simpler alternatives before accepting. Only the hard invariants are off the table.
- **Structured over fast.** There is **no time pressure to ship something demoable.** Approach each topic
  structured from the start: build the enabler/foundation properly and slice for *soundness*, not for the
  earliest visible result. A throwaway stopgap to "see something" is the wrong trade here — prefer doing it
  right (e.g. the media derivatives before the gallery). Correctness + clean structure first; speed-to-demo
  is not a goal.
- **Language.** Converse with the user in the language they use; **document and implement in English**
  (docs, code, identifiers, commits). The published site's Use area is the intentional bilingual EN+DE exception. German
  user-facing text follows [neutral-first inclusive language](../crosscutting/cc.inclusive-language.md).
- **Diagrams as inline Mermaid.** Where a diagram genuinely clarifies, embed it as a ` ```mermaid `
  fenced block directly in the markdown. Diagrams-as-code only — no binary image files. Skip diagrams that
  are mere decoration. **Mermaid, not PlantUML, because GitHub renders it** — and `docs/` is read by people
  judging the project there, who cannot install an IDE plugin. A format only IntelliJ renders is a diagram
  nobody sees: all three diagrams in `architecture/README.md` were syntactically broken from their first
  commit and nobody noticed for 13 days. Mermaid covers every kind veyq draws (sequence, state, component,
  flow); its `%%{init}%%` styling is not worth using — the defaults theme themselves, and the one PlantUML
  construct used everywhere (`skinparam`) is what broke. Two characters must be escaped in label text:
  **`;` → `#59;`** (it separates statements → parse error) and **`#` → `#35;`** (it opens an entity → the
  rest of the label is swallowed **silently**, with no error at all).
- **Scripts and utilities in Python.** Bash only for thin CI/hook glue.
- **Durable state is edited in place — no addendums.** State every doc (ADRs, use-cases, enablers, crosscutting)
  as it is **now**; when a decision changes, **edit the doc**. Never accrete `Amended: <date>` lines or
  `## … addendum <date>` sections — a dated change-log is not state. Canonical rule:
  [`../README.md`](../README.md) (§ current-state) + [`../decisions/README.md`](../decisions/README.md).
  **But do not offload the *reason* to git** (§ What the docs are for, rule 5).
  The test is not "old vs. new", it is **"does this still bind a future decision?"**: if a change leaves a live
  obligation, that obligation *is* current state and stays in the doc — as the obligation, not as a diary entry.
  [ADR 0026](../decisions/0026-client-trust-tiers.md) is the worked example: it does not narrate which tiers it
  once listed; it says a stronger unlock method is **not** a client-integrity answer, and why — the wrong turn
  someone will otherwise take again. The date is noise; the trap is state. Where nothing binds, let it go.
- **Reuse the existing UI — never invent new UI.** The built product is the design source: `web/` is whole, so
  the nearest built screen answers almost every question. The rule and its details live once, in
  [`../crosscutting/ui-web-styleguide.md`](../crosscutting/ui-web-styleguide.md) § Reuse first — not here.
- **Start light.** Heavier machinery — validators, hooks, the full `contracts/`
  tree, `.claude/` commands + subagents — is added when there is code/feature surface that justifies
  it, not up front.

## When to add a tool (so we don't over-build)

Two rules under everything: **pain before tool** (no tool until there's a felt cost) and
**rule of three** (a manual action repeated three times is a candidate to automate). I monitor against
these within a session and *propose* — I don't add tooling silently. Concrete triggers:

| Tool | Trigger |
|---|---|
| graphify on veyq | tracing one use-case from contract through backend to web gets lossy across 2–3 Explore sweeps in one session |
| trust-chain validators | the first use-case + first test exist (something to link) |
| an invariant guard | a CR removes a duplication or establishes "X only in Y" — promote its one-shot proof grep to a permanent check |
| a hook | a validator/formatter exists and we've run it manually a few times |
| a slash command | the same prompt framing has been typed ~3 times |
| split the inbox | it needs scrolling to navigate |
