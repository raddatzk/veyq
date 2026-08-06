## JetBrains Rules

- **JB-01 Never trust terminal output**  
  Always redirect important output and read via `read_file`:  
  `<cmd> > documentation/agent/tmp/<name>.txt 2>&1`

- **JB-02 Read workspace files via `read_file` only**  
  Do not rely on `cat`, `grep`, `head`, `tail` for file content.

- **JB-03 Use terminal for computation only**  
  Allowed: search, discovery, aggregation (`find`, `wc`, etc.).  
  Do not read final results directly from terminal.

- **JB-04 Use tmp as single IO buffer**  
  Only `documentation/agent/tmp/`; only for command output.  
  Never copy workspace files there.

- **JB-05 Verify after every write**  
  Re-read modified files to detect truncation or duplication.

- **JB-06 Preserve git state explicitly**  
  Rename → `git mv`, delete → `git rm`.  
  If output is unclear → fallback to JB-01.

---

## Recording

These rules apply to every session, regardless of agent. The `agent/record/` folder lives in the
**project repository** (not in aeb-ai-harness). 

### Session Log

One log file per increment: `agent/record/log_increment-N.md`.

**CI-RL-01** **One entry per work block.** A work block is one BL item or one coherent discussion
with a single goal. Start a new entry when the goal changes.

**CI-RL-02** **Update the current entry after every prompt** 
A teammate or a new context window must be able to orient from the last entry at any point.
Update Status, Completed, and Open thread before responding to anything else. If you are in a debugging loop, the entry must reflect the current failure state and what was tried.
**CI-RL-03** **Append new entries at the bottom** of the file. When appending, anchor the edit on the last
existing entry to ensure the new entry lands at the bottom.

```markdown
## YYYY-MM-DD — <one-line goal of this work block>

**Goal:** <one sentence: what are we trying to achieve?>

**BL item:** BL-NNN (or "—" if not backlog-driven)

**Status:** IN-PROGRESS | DONE | BLOCKED

**Completed:** <what was done so far — grow this field with each prompt - be extremely concise>

**Rationale:** <why were the key implementation decisions made? — one or two sentences, no more>

**Self-critique:** <how could the solution be improved? what is uncertain or rough? what would you do differently? — be honest on both sides, be brief>

**Open thread:** <anything mid-flight or needing follow-up — "—" if clean>

**Findings:** F-NNN <one-liner>, … (or "—" if none)
```

**CI-RL-04** **Keep entries concise.** Every field above should fit in one or two sentences.
No field should be a paragraph. 

**CI-RL-06** **No backward changes.** Log entries, backlog rows, and roadmap scope are historical records.
Once written, they must not be edited to reflect a different approach or outcome. Corrections
belong in the *current* entry (Completed / Rationale / Self-critique), or in a new entry.
Rewriting history to make past decisions look better is prohibited.

**CI-RL-07** **Log-write self-check.** Before any write to a log file, ask: *"Is this the current
work block entry — written in this prompt or this session?"* If the entry predates this prompt,
treat it as immutable and write a **new** entry at the bottom instead. Editing a past entry is
a CI-RL-06 violation even when the intent is to correct rather than rewrite. The check must be
applied every time, not only when uncertain.

### Findings

One findings file per increment: `agent/record/findings_increment-N.md`.
Findings are processed at the retrospective; a clean file starts with the next increment.
**CI-RF-01** **Append new findings at the bottom.** Anchor on the last existing entry when appending.

**CI-RF-02** **Finding IDs are F-NNN — sequential across all increments in a project, never restart per increment.**
F-001, F-002, … continue upward from the last used ID regardless of which increment file you are
writing to. The file name provides the increment scope; the ID provides a stable, unique
**project-wide** reference. IDs are scoped to one project — the harness has its own F-NNN series,
and each component project has its own. They are independent and may overlap in number without
conflict. Never use a prefix like `F-H1-NNN` or `F-PROJ-NNN`.

**CI-RF-03** **When working in a component project:** feed process-level findings (workflow gaps,
wrong assumptions, surprising costs) back to the *harness* findings file
(`aeb-ai-harness/documentation/agent/record/findings_increment-N.md`), not to the project's
own findings file. The harness ID series is used in the harness file; the component's own
F-NNN series is independent.

Two annotations are required when forwarding a finding to the harness:

1. **In the harness findings file:** add a `**Source:**` line immediately after the `**Date:**`
   line, naming the originating project and the component project's local finding ID (if one was
   assigned):
   ```markdown
   **Date:** YYYY-MM-DD  **Status:** OPEN
   **Source:** vwac-aeb-AI-PoC / F-019
   ```
2. **In the component project's log entry:** note that the finding was forwarded, with the
   harness finding ID so the link is navigable in both directions:
   ```markdown
   **Findings:** F-019 (forwarded to harness as F-025)
   ```

The harness retro processes forwarded findings alongside harness-native ones.

**CI-RF-04** **Write a finding whenever** something is worth carrying into the retrospective:
a process gap, a wrong assumption, a surprising cost, a risk, a better way noticed in passing,
an open question that needs a decision, or an improvement idea for the system or the process.
If in doubt, write it — brief is fine. The retro decides what matters.
Findings are the primary retrospective feed. The session log references them by ID only.

```markdown
## F-NNN — <short title that names the observation>

**Date:** YYYY-MM-DD  **Status:** OPEN
<!-- **Source:** <project> / F-NNN   ← add this line only when forwarded from a component project -->

**Tracking:** — | <navigation pointer — where is this finding being handled?>
<!-- Three states:
     OPEN + Tracking: —          → not yet assigned
     OPEN + Tracking: <link>     → actively tracked (BL item, retro handover table entry, etc.)
     NOTED                       → closed; Tracking field points to where the fix landed -->

**Observed:** <what exactly was seen or hit>

**Why it matters:** <what breaks, degrades, or gets missed if not addressed>

**Suggested action:** <concrete next step, or "open question: …" if undecided>
```

**CI-RF-05** **`Tracking:` is a navigation pointer, not a description.** It holds a short cross-reference
to where this finding is actively being handled — for example `BL-028`, `H-5 in retro_increment-1.md`,
or `commit abc1234`. It is never a description of the fix (that belongs in `Suggested action:`)
or a summary of what was done (that belongs in the log entry's `Completed:` field).

**CI-RF-06** Close a finding by updating **`Status:`** to `NOTED` **in the header line** — the single
`**Date:** YYYY-MM-DD  **Status:** OPEN` line becomes `**Status:** NOTED`. Optionally update
`**Tracking:**` to point to where the fix landed (e.g. a commit hash or BL-NNN). Do not append
a second status line or write "Status: NOTED" at the bottom of the entry. One status, one place.
The `**Observed:**`, `**Why it matters:**`, and `**Suggested action:**` fields are immutable once
written — do not remove, replace, or truncate them when closing. A finding stripped of its body
is not a closed finding; it is a lost one.

**CI-RF-07** **Stop-and-fix:** if a finding signals a blocker or significant wrong assumption, stop, log it,
fix it, then continue. The context must be captured *before* the fix, not after.

**CI-RF-08** **Self-diagnosed process failures are findings.** When you identify a mistake in your own
behaviour — a wrong tool-use pattern, a recurring ordering error, a rule you failed to apply —
that diagnosis is a finding, not just a self-critique note. Write it to the findings file
immediately. The end-of-response question ("Anything worth adding to findings?") is for the
user's observations; it does not substitute for the agent's own stop-and-fix obligation.

**CI-RF-09** **At the end of every response:** ask once — *"Anything worth adding to findings?"*
No answer means no. Do not repeat the question.

---

## Planning

These rules apply to every agent that reads or writes plan artefacts.

### Backlog

**CI-PB-01** One backlog file per increment: `agent/plan/backlog_increment-N.md`.

Each file is a table of all tasks for that increment — open and completed.
**Completed items:** strike through the Task column only (`~~text~~`). Write plain `Done YYYY-MM-DD`
in the Status column without strikethrough — keeping Status readable at a glance.
There is no separate completed file.

```markdown
| ID | Task | Detail | Agent | Status |
|----|------|--------|-------|--------|
| BL-NNN | **<task name>** | <detail> | `@agent` | Open |
| BL-NNN | ~~**<task name>**~~ | <detail> | `@agent` | Done YYYY-MM-DD |
```

**CI-PB-02** IDs are sequential across all increments (BL-001, BL-002, … — never restart per increment).

**CI-PB-03** **Reading spec and architecture on demand.** When a BL item references a spec requirement
ID or architecture section, read that specific section — do not read the full document speculatively.
Use `documentation/PROJECT_CONTEXT.adoc` for ID lookup and table of contents navigation first,
before opening any spec or architecture file.

**CI-PB-05** **No rewrites once work has started.** Once work on a backlog item has started, do not
rewrite its Task or Detail fields. Append a correction note to the Detail instead. The original
text is the historical record of what was planned; the note records what changed and why.

---
