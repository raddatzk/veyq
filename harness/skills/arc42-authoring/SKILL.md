---
name: arc42-authoring
description: 'Create or update arc42 architecture documents. Use when an architecture document should be written or reviewed for update.'
---

## Context read-list (cold start — read these before any action)

- **CRL-01** `documentation/architecture/architecture-*.adoc` — current state (same suffix as
  the spec, e.g. `architecture-claims-calculation.adoc`; or the template if starting fresh)
- **CRL-02** `documentation/spec/spec-*.adoc` — the project spec; architecture must trace back to it
- **CRL-03** `documentation/agent/plan/roadmap.md` — current increment and its scope
- **CRL-04** `documentation/agent/record/findings_increment-N.md` — open findings; also check
  the retro handover section in `documentation/agent/retros/retro_increment-N.md` for
  architecture-relevant items agreed for this increment
- **CRL-05** Relevant sections of `Hybrid-Architecture-E2E-Integration/` — domain SoT (see AR-SoT-01)

---

## Sources of truth

- **AR-SoT-01** Priority order for external sources of truth:
    1. `Hybrid-Architecture-E2E-Integration` — authoritative for domain knowledge and cross-component
       data models. Read-only — the architecture must align with it; deviations require an ADR.
    2. `documentation/spec/` — the project's own requirements; the architecture implements these.
    3. `aeb-documentation` — overall system architecture beyond a single component.
    4. `aeb-application-template-repository` — technical constraints and patterns; every component
       must follow the template unless an ADR documents the deviation.
    5. `aeb-infra` — DevOps and deployment constraints.
    6. `aeb-framework` — framework-level constraints.

- **AR-SoT-02** When the architecture diverges from any source of truth: first, try to resolve
  it immediately in dialogue with the user. If that is not possible — which will often be the
  case when the source of truth itself would need to change — log it as a finding immediately and
  mark the affected section with `// INCONSISTENCY: F-NNN`. Do not silently pick one variant.

## Capture-first rule

- **AR-CF-01** This rule is always active — across all phases and all dialogue turns.
  When the user provides any fact or decision that belongs to a section not currently being
  written, **MUST immediately** write `// PENDING §N: <note>` at the top of that section's
  `.adoc` file — before continuing the conversation. Elaborate when that section is opened.
  Never lose information by deferring without capturing.
  **Pre-response self-check:** before writing every response, ask: *"Did the user provide any
  fact or decision in their last message that belongs to a section I'm not currently writing?
  If yes, capture it NOW before responding."*

---

## Phase 0 — Intake (before touching the document)

- **AR-P0-01** Read the full context read-list (CRL-01 through CRL-05).
- **AR-P0-02** Verify the architecture document exists at `documentation/architecture/architecture-*.adoc`.
  If it does not exist, copy the template from `aeb-application-template-repository/documentation/architecture/`
  and rename it (AR-FM-01) before proceeding. Do not start writing content into a missing or unconfigured file.
- **AR-P0-03** Read the relevant section of `Hybrid-Architecture-E2E-Integration` for domain
  context diagrams, data models, and cross-component protocols.
- **AR-P0-03a** Check the four external technical sources of truth for changes relevant to this
  session before touching the architecture document:
  - `aeb-application-template-repository` — build patterns, Spring config, template conventions
  - `aeb-infra` — deployment constraints, server URLs for each stage, security headers
  - `aeb-framework` — framework-level APIs and constraints
  - `aeb-documentation` — overall system architecture
    Run: `git -C <path> log --oneline -5 -- . 2>&1` for each. Identify any commits since the last
    architecture review. If a change affects an architecture section or an OpenAPI file, address it
    before declaring the current session complete. This check runs at session start — not only at
    increment close.
- **AR-P0-04** Present a one-paragraph summary: what the component does, which domain it lives in,
  who its consumers are, and what its primary responsibilities are. Cite sources explicitly.
  State what is inferred vs. what comes from an external source.
- **AR-P0-05** Ask: *"Is this understanding complete? Are there interface specs, domain diagrams,
  or deployment constraints I should read before starting?"*
- **AR-P0-06** Do not touch the architecture document until the user confirms intake is sufficient.

---

## Phase 1 — System boundary first (§3, §2)

**Rationale:** Understanding *what enters and leaves* the component is the prerequisite for all
internal decisions. Agreeing on interfaces and contracts first prevents internal design work from
being invalidated by a boundary correction later.

- **AR-P1-01** Start with **§3 Context and Scope**: draw the system boundary. Identify all external
  systems, actors, and the data that flows in and out. For each interface, state:
  - Direction (inbound / outbound)
  - Protocol and data format
  - Pre- and postconditions (contract)
  - Spec IDs that mandate this interface (any of: `REQ-COMP-NNN`, `NFR-COMP-NNN`, `CON-COMP-NNN`)
- **AR-P1-02** Assign `COMP-CTX-NN` IDs to each identified interface and external system.
- **AR-P1-03** After §3 is settled, fill **§2 Architecture Constraints** from the template and
  from the sources of truth (AR-SoT-01). Many constraints come from the template repository and
  from `aeb-documentation` — cite each source explicitly.
- **AR-P1-04** Fill **§1 Requirements Overview** in dialogue with the user:
  - Propose a short selection (3–7) of the most architecturally significant requirements from the
    spec — the ones that shape the whole design. Cite or paraphrase each from the spec (do not
    invent); link to the full spec for the complete list.
  - Confirm with the user: *"Are these the right top-level requirements to highlight? Anything
    missing or to remove?"*
  - Do the same for quality goals: propose the top 3–5 NFR-COMP-NNN items that most influence
    the architecture; confirm with the user.
  - Do not copy full requirement text from the spec verbatim — a short paraphrase plus the
    spec ID and a link is the right level of detail.
- **AR-P1-04a** Fill **§1 Stakeholders** in the same Phase 1 batch. Derive stakeholder table
  entries from the spec (roles, concerns) and the context diagram (external systems and actors
  from §3). Present together with §3, §2, and §1 Requirements Overview in AR-P1-05. Do not defer
  stakeholders to Phase 3 — they are known at intake time and forgetting them leaves the document
  incomplete without any visible signal.
- **AR-P1-05** Present §3, §2, §1 Requirements Overview, and §1 Stakeholders together as the
  first batch. State self-critique. Ask focused questions. Wait for the user's signal before
  proceeding.

---

## Phase 2 — Internal architecture (§4 through §8)

The default is to work chapter by chapter in the arc42 order: §4 → §5 → §6 → §7 → §8.
Combine chapters into one batch only when there is a clear reason (e.g. §7 is trivial and follows
directly from §4). For §5 (Building Block View) and §6 (Runtime View), work level by level:
Level 1 first, confirm with the user, then Level 2 if needed.

**For each batch:**

- **AR-P2-01** Show your suggestion — filled in, IDs assigned where required (AR-ID-02), WHY explained.
- **AR-P2-02** State self-critique: what is uncertain, what could not be derived from inputs,
  where alternatives were not fully evaluated.
- **AR-P2-03** Ask 1–3 focused questions specific to the current batch.
- **AR-P2-04** Wait for the user's response. Refine. Repeat until the user signals *"next"*, *"done"*, or *"continue"*.

**Spec references throughout:**

- **AR-P2-SR** Whenever an architectural element (building block, constraint, concept, runtime
  scenario, decision) exists because of a spec requirement, cite the relevant spec ID inline —
  as a label, in the description, or in a `[NOTE]` block. Use the ID types from AR-ID-03.
  An architectural claim without a traceable motivation is incomplete.

---

### §4 Solution Strategy

- **AR-P2-§4-01** Summarise the key architectural choices in one short paragraph before the details.
  A reader must understand *why* this component is designed as it is from this section alone.
- **AR-P2-§4-02** Make the architectural principles (see §Architectural principles above) visible
  in §4: state explicitly which principles shaped the solution strategy and why they fit this
  specific context. §4 is where the cross-cutting principles become concrete — they do not stop
  applying in §5, §6, or §8, but §4 is the place to name them and anchor the rationale.
- **AR-P2-§4-03** `aeb-application-template-repository` is the technical baseline. Document every
  deviation from it with a reason. An unexplained deviation is not acceptable.

---

### §5 Building Block View

- **AR-P2-§5-01** Start with **Level 1** (the whole component as a single box with its external
  interfaces from §3). Present to the user and confirm before decomposing further.
- **AR-P2-§5-02** Move to **Level 2** only when the internal structure genuinely aids understanding —
  do not over-decompose. If Level 1 suffices, stop there.
- **AR-P2-§5-03** Assign `COMP-BB-NN` IDs to each named building block. These are the anchor
  points for Javadoc and code-level traceability (`@see COMP-BB-01`).
- **AR-P2-§5-04** Every building block description must include: responsibility (one sentence),
  interfaces offered, interfaces required, and the spec IDs it implements (AR-P2-SR).

---

### §6 Runtime View

- **AR-P2-§6-01** Work scenario by scenario. Start with the most architecturally significant one.
  Confirm before adding more.
- **AR-P2-§6-02** Prioritise scenarios that reveal non-obvious behaviour — error paths, concurrency,
  partial failures — not the trivially obvious happy path of every endpoint.
- **AR-P2-§6-03** Assign `COMP-RV-NN` IDs. Link each scenario to the spec IDs that make it
  necessary (AR-P2-SR).

---

### §7 Deployment View

- **AR-P2-§7-01** The deployment model follows `aeb-infra` constraints and `aeb-application-template-repository`.
  Deviations require an ADR.

---

### §8 Cross-cutting Concepts

- **AR-P2-§8-01** Do not fill §8 with a generic list. Instead: review the topic taxonomy in
  `aeb-application-template-repository/documentation/architecture/src/08_concepts.adoc` (the
  commented topic tree covers: Architecture & Design Patterns, Safety & Security, Development
  Processes, Operation & Administration, Domain Concepts, and Under-the-hood topics such as
  Persistence, Exception Handling, Integration, Parallel Processing, etc.). Propose to the user
  the topics that are relevant for this specific component and context. Discuss and agree before
  writing. Pick only what genuinely matters — the template explicitly says "DO NOT ATTEMPT to
  cover all topics."
- **AR-P2-§8-02** Link each agreed concept to the building blocks (§5) and ADRs (§9) where it
  applies. Cite the spec IDs that motivated the concept (AR-P2-SR).

---

## Phase 3 — Architecture Decisions and supplementary (§9 through §12)

- **AR-P3-01 §9 Architecture Decisions (ADRs):** each ADR uses this format:
  ```asciidoc
  === COMP-ADR-NN — <short decision title>
  *Status:* Accepted | Superseded by COMP-ADR-MM | Deprecated
  *Context:* <What forced a decision? Which spec IDs or external constraint?>
  *Decision:* <What was decided? One or two sentences — the what, not the why.>
  *Rationale:* <Why this option over the alternatives? Name the alternatives considered and why
  they were rejected. This is the most important part.>
  *Consequences:* <What becomes easier, harder, or constrained as a result?>
  *Traces:* <Spec IDs (REQ/NFR/CON/RISK/ASM) and architecture IDs (COMP-BB/RV/CTX) this
  decision affects>
  ```
  Every non-trivial decision gets an ADR — including decisions to follow the template defaults.
  "We use the template default" is still an explicit decision worth recording.
- **AR-P3-02 §10 Quality Requirements:** this section references the spec's `NFR-COMP-NNN` items
  rather than duplicating them. Add only *additional technical* quality aspects not visible at
  requirements level (e.g. internal latency budgets, memory constraints). No new IDs unless the
  item is purely architectural and has no spec equivalent.
- **AR-P3-03 §11 Technical Risks:** reference open findings (`F-NNN`) and spec risks
  (`RISK-COMP-NN`). Add only architectural risks not already captured in the spec. State
  mitigation strategy or `// OPEN: F-NNN` if unresolved.
- **AR-P3-04 §12 Glossary:** only architectural and technical terms that are *not* already
  covered in spec §8. If a term from spec §8 needs a better description after implementation
  experience, update the spec §8 entry — do not duplicate it here. No IDs needed — referenced
  by term.

---

## `@architect review` — Regular revisit

- **AR-REV-01** Check all sources of truth (AR-SoT-01 items 1–6) and the project spec for commits
  since the last review. Use: `git -C <path> log --oneline -- <file> 2>&1`.
- **AR-REV-02** Re-read open findings (CRL-04) and retro handover. Any OPEN finding with
  architecture impact must be addressed before the review is complete.
- **AR-REV-03** For each change found: assess impact across all architecture sections. Sections
  with IDs (§3, §5, §6, §9) are the most likely to require updates because other artefacts
  (code, OpenAPI, tests) reference them by ID — a change there has a wider blast radius.
  Sections with narrative content (§2, §4, §7, §8) may also be affected; do not skip them.
  Present findings to the user. Apply agreed updates.
- **AR-REV-04** Any postponed architectural decision that has now matured must be resolved this
  review — not deferred again without explicit agreement.
- **AR-REV-05** Record the review date in the document control CSV.

- **AR-REV-06** **Template drift check.** Compare the component project's `build.gradle.kts`
  (plugin versions, dependency versions) and `docker/compose.yaml` (service images, versions)
  against the current `aeb-application-template-repository` equivalents.
  Run: `git -C <template-path> log --oneline -5 -- build.gradle.kts docker/compose.yaml 2>&1`
  to identify recent template changes. Log any version delta as a finding immediately. In the
  finding's `Suggested action`, note if the changed component is security-related (e.g. auth
  library, base image) so the user can prioritise accordingly.

- **AR-REV-07** **Sibling-repo state confirmation.** Before running any `git log` check on a
  sibling repository — whether in `@architect review` (AR-REV-01, AR-REV-06) or at session
  start (AR-P0-03a) — confirm with the user that each relevant repo is on the intended branch
  and at the expected latest commit. Do not assume `main` is correct. Ask:
  *"Before I check `<repo>`, can you confirm it is on the correct branch and up to date?"*
  Proceed with the `git log` check only after the user confirms. A review run against a stale
  or wrong-branch repo silently misses real changes or reports false deltas.

---

## PlantUML diagrams

- **AR-PU-01** Use PlantUML for all diagrams. Every diagram is a separate `.puml` file — never
  embedded inline in AsciiDoc.
- **AR-PU-02** Place `.puml` files in `documentation/architecture/uml/`. Render PNGs to
  `documentation/architecture/images/` via `./gradlew renderDiagrams`.
- **AR-PU-03** Reference in `.adoc` as `image::<filename>.png[<title>]`. Do not add a folder
  prefix — `imagesdir` in the root adoc resolves the path.
- **AR-PU-04** Commit `.puml` and `.png` together in the same commit.
- **AR-PU-05** Every diagram must have a one-sentence explanation above or below it: what it
  shows and why it matters.
- **AR-PU-06** State the diagram's origin before the image reference (same as `@spec` P2-§5-01):
  *Taken over from source* | *Tailored from source* | *Created from discussion*.
- **AR-PU-07** Use **C4 model** notation for context and container diagrams. The template provides
  two canonical examples to follow in style:
  - `aeb-application-template-repository/documentation/architecture/uml/context.puml` — C4 Context
    diagram using `!include <C4/C4_Context>`
  - `aeb-application-template-repository/documentation/architecture/uml/container.puml` — C4
    Container diagram using `!include <C4/C4_Container>`

  Study these before producing the §3 context diagram and the §5 container/component diagrams.
  Apply the same structural choices: `LAYOUT_WITH_LEGEND()`, `System_Boundary`, `System_Ext`,
  `Container`, `ContainerDb`, `Rel_*` directives with protocol labels on relations.

- **AR-PU-08** Use the **plantuml-icon-font-sprites** library for technology and actor icons:
  ```
  !$ICONURL = "https://raw.githubusercontent.com/tupadr3/plantuml-icon-font-sprites/v3.0.0/icons"
  !includeurl $ICONURL/<category>/<icon-name>.puml
  ```
  Icon categories available: `font-awesome/`, `font-awesome-5/`, `devicons/`, `devicons2/`,
  `material/`. Browse at: https://github.com/tupadr3/plantuml-icon-font-sprites/tree/main/icons/
  Use `$sprite="<icon-name>"` on `System`, `Container`, `Person` etc. to attach the icon.
  Choose icons that match the actual technology (e.g. `devicons2/apachekafka_original` for Kafka,
  `devicons2/spring_original` for Spring Boot, `devicons/redis` for Redis).

---

## File management

**AR-FM-01 — New project setup:**

- Copy the architecture template from `aeb-application-template-repository/documentation/architecture/`.
- Rename to `architecture-<suffix>.adoc` — same suffix used for the spec (e.g.
  `architecture-claims-calculation.adoc`). The suffix is the short human-readable component name,
  not the COMP code and not the full git repository name.
- Update the `[.below-doc-title-header]` to the component name.
- Update `control/documentControl/architecture_document_control.adoc` and
  `document_control_entries_architecture.csv`: add first entry with version **`V0.1`** (not V1),
  today's date, "Initial draft", relevant AEB-NNN, author name. The document starts as a draft
  and remains at 0.n until `@planner` assigns V1 when writing the roadmap (see AR-FM-03).
- Verify `documentation/architecture/uml/` and `documentation/architecture/images/` exist.
- Verify `openapi/` directory exists for API YAML files.

**AR-FM-02 — Living document:**

- **AR-FM-02a** **Preserve arc42 help blocks.** Section source files contain
  `ifdef::arc42help[]` / `endif::arc42help[]` blocks. These are conditional help text blocks
  controlled by the `:arc42help:` attribute — they appear in the template
  but are suppressed in the compiled output. **Never remove or overwrite them.** Write
  architecture content *outside* these blocks, not in place of them.

- Update the architecture whenever a decision changes, a new constraint is discovered, or an
  increment reveals a gap. Do not freeze between increments.
- Add a CSV row for every significant update. Use `V0.2`, `V0.3`, … while the document is in
  draft (before `@planner` assigns V1).
- When an open finding reveals an architecture gap: mark the affected section with
  `// OPEN: F-NNN — <one-liner>` until the finding is resolved.

**AR-FM-03 — Version maturity and ID freeze:**

- **AR-FM-03-1** While the architecture is at version 0.n (inception phase, before V1), IDs
  (`COMP-CTX-NN`, `COMP-BB-NN`, `COMP-RV-NN`, `COMP-ADR-NN`) may be freely renumbered to keep
  them orderly and consistent with the document's intent.
- **AR-FM-03-2** V1 is assigned by `@planner` when writing the roadmap for increment 1. From V1
  onward: IDs are **frozen**. Never reuse or renumber any architecture section ID.
- **AR-FM-03-3** Both spec and architecture receive V1 in the same `@planner` action — they share
  the same planning baseline event.

---

## Dialogue rules

- **AR-DR-01** Default to chapter by chapter in Phase 2 (AR-P2-01 through AR-P2-SR). Combine into
  one batch only when there is a clear reason. Never present the full document at once.
- **AR-DR-02** Always in this order: suggestion → self-critique (including WHY of the approach
  and alternatives considered) → questions.
- **AR-DR-03** Wait for the user's signal before advancing. Accepted signals: *"next"*, *"done"*, *"continue"*.
- **AR-DR-04** Never invent silently. Every inferred claim carries a visible provenance marker.
- **AR-DR-05** When a decision is needed, present options with rationale and wait for the user to
  choose before writing an ADR. Do not write ADRs for decisions that have not been made.

---

## Inline completion checklist

- [ ] Context read-list loaded (CRL-01 through CRL-05)
- [ ] External technical sources of truth checked for recent changes (AR-P0-03a)
- [ ] Template drift check done: build.gradle.kts + docker/compose.yaml versions compared against
  aeb-application-template-repository; deltas logged as findings (AR-REV-06)
- [ ] Sibling-repo state confirmed with user (branch + latest commit) before any git log on external sources (AR-REV-07)
- [ ] Sources of truth checked for changes relevant to this session (AR-REV-01)
- [ ] System boundary (§3) agreed before any internal section is started
- [ ] arc42 help blocks preserved in all edited section files (AR-FM-02a)
- [ ] Capture-first pre-response self-check applied every response (AR-CF-01)
- [ ] Every new building block has a `COMP-BB-NN` ID and spec IDs cited (AR-P2-SR)
- [ ] Every ADR follows the AR-P3-01 format and explains WHY and alternatives considered
- [ ] All new/changed diagrams have separate `.puml` files in `uml/` and rendered PNGs in `images/`
- [ ] `./gradlew renderDiagrams` run
- [ ] No section contains unexplained deviations from `aeb-application-template-repository` baseline
