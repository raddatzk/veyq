---
name: opening-phase-signoff
description: 'Load this Skill when executing the opening phase sign-off BL item — the gate between the opening phase and implementation that confirms scenario observability, DD artifact completeness, and full @coder item coverage before implementation begins.'
---

## Purpose

Steer the user through the opening phase sign-off session: confirm that all opening phase
deliverables are complete and sufficient for implementation to begin, and adapt the backlog
if any blocker is found that cannot be resolved in-session.

## Prerequisites

- **OS-PR-01** The opening phase BL items are all done: all L4 acceptance test scenarios are
  written and `@pending`; all detailed design artifact BL items are done and signed off
  individually.
- **OS-PR-02** The backlog for the increment is available and shows all `@coder` items with
  their linked DD artifact references (PL-BD-OP-02).

## Procedure

**OS-P-01 Check L4 scenario observability.**
For each `@pending` acceptance test scenario, confirm with the user:
- Does the scenario assert an externally observable outcome only (API response, not internal state)?
- Is the scenario achievable within the current increment scope (required read endpoint exists)?

If any scenario fails either check, it must be corrected before proceeding.

**OS-P-02 Check DD artifact coverage.**
For each `@coder` implementation item in the backlog, verify that the linked DD artifact exists
(the BL item is done and the artifact file is present in `documentation/detailed-design/src/`).
If any `@coder` item has no linked, completed artifact, this is a blocker — do not proceed.

**OS-P-03 Confirm no outstanding architecture gaps.**
Ask the user: "Are there any architecture gaps raised during the opening phase that are not yet
resolved?" If yes, resolve them before proceeding.

**OS-P-04 User sign-off.**
Present a summary of what was checked. Ask the user explicitly:
> "The opening phase is complete. Do you confirm that implementation may begin?"

A clear "yes" or "proceed" closes the sign-off. Any reservation is a blocker — work with
the user to resolve it or adapt the backlog.

**OS-P-05 Adapt the backlog if blocked.**
If a blocker cannot be resolved in-session (e.g. a DD artifact gap that requires architecture
rework spanning multiple sessions), `@planner` adapts the backlog: add or reopen the relevant
BL items, adjust ordering, and re-present the opening phase before calling sign-off again.

## Done when

- **OS-DW-01** All L4 scenarios confirmed observable and in-scope.
- **OS-DW-02** All `@coder` items confirmed covered by a completed DD artifact.
- **OS-DW-03** No outstanding architecture gaps.
- **OS-DW-04** The user has given explicit "implementation may begin" sign-off.

