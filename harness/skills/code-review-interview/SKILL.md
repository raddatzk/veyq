---
name: code-review-interview
description: 'Load this Skill when facilitating the human code review at the start of the increment closing phase (PL-BD-07 step 0).'
---

## Purpose
Facilitate the human code review interview: classify each code finding the user raises and act on it immediately — either fix in-session or record as a finding.

## Prerequisites

- **CR-PR-01** Before the first finding is raised, identify the files changed in the increment (`git diff --name-only` or equivalent) so `@coder` has the code in context to assess fix scope.
- **CR-PR-02** Know the current increment number N and the paths to:
  - component project `findings_increment-N.md`
  - harness `findings_increment-M.md` (for process/agent-rule findings, CI-RF-03)

## Procedure

**CR-P-01** For each finding the user raises, suggest a classification and ask the user to confirm or override:

- **(a) Fix now** — appropriate when the fix requires no new test and no design decision: dead code removal, renaming, trivial restructuring. `@coder` implements the fix immediately in the same session before the interview continues. No finding written, no backlog item.

- **(b) Postpone** — appropriate when the fix requires a new test, a design decision, or structural refactoring. Write one finding to the **component project's** `findings_increment-N.md`:
  - `**Observed:**` the code complaint — specific, with file/class/method reference
  - `**Why it matters:**` impact on correctness, maintainability, or conventions
  - `**Suggested action:**` the concrete fix
  `@coach` processes this finding at the retrospective (PL-BD-07 step 4). `@planner` converts it to a backlog item when deriving I-N+1 (PL-BD-07 step 5).

- **(c) Harness finding** — appropriate when the issue reveals a process gap or missing agent rule. Write one finding to the **harness** `findings_increment-M.md` per CI-RF-03. May be raised alongside (a) or (b) if the same observation has both a code fix and a process implication.

**CR-P-02** (a) and (b) are mutually exclusive for a given finding: either fix it now or record it. A finding that is partially in boy-scout scope and partially structural must be split — fix the in-scope part now under (a), write a finding for the remainder under (b).

**CR-P-03** Write (b) and (c) findings immediately after the user confirms the classification — do not accumulate and batch at the end. Use `insert_edit_into_file` anchored on the last existing finding entry.

**CR-P-04** Repeat CR-P-01–03 until the user signals the review is complete.

**CR-P-05** Present a session summary:
- Fixes applied in-session: list by file and change
- Findings filed: list F-NNN IDs with one-line titles

## Done when
The user has signalled the review is complete and the session summary has been presented.

