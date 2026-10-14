---
id: cr-helm-upgrade-check
title: A chart upgrade is checked by rendering the predecessor's chart and the candidate's and comparing the immutable fields
status: planned
satisfies: []
adrs: ["0064", "0057"]
invariants: [self-hostable by default]
---

## Goal

A script renders `charts/veyq` of the upgrade proof's predecessor and of the candidate with the same values, and fails
on anything a `helm upgrade` cannot apply or would lose data over; it also renders the candidate with the predecessor's
`ci/` values. It runs in the image `test` job: information until the first release, red from it. No cluster. Decided by
the coordinator on the owner's behalf, 2026-10-14 (inbox #428). `satisfies: []`: a release-pipeline guard, no use case.

## Context & constraints

- [ADR 0064](../docs/decisions/0064-kubernetes-helm-deployment-target.md) Addendum (2026-10-14) owns the decision and
  lists what is and is not covered; [ADR 0057](../docs/decisions/0057-release-identity-and-deployment-recipe.md)
  Addendum the predecessor and the release switch.
- **Reuse the predecessor:** `default_predecessor` / `resolve_predecessor` in `scripts/run_web_e2e.py` (HEAD^1 while
  `release: none`, then the newest `v<major>.<minor>` tag the first parent contains). The chart needs no build: read the
  predecessor's `charts/veyq` from git (`git archive` / `git show` into a temp dir), not a full worktree.
- **What exists:** `scripts/chart_meta_check.py` renders ONE tree (lint, schema, determinism, `Recreate`); it compares
  nothing across versions. The `test` job (`.github/workflows/container.yml`) already installs `helm` and pyyaml.
- Today's chart (measured): 2 Deployments, 2 StatefulSets (mariadb; garage with three claim templates), 1 PVC
  (`veyq-config-secret`, `resource-policy: keep`), a RoleBinding; no Job, hook or `lookup`; object names are fixed, not
  release-prefixed; the selector (`veyq.selector`) carries no version label.
- Scripts in Python, bare (no venv), python3 3.9 locally.

## Inventory (commands)

- `grep -n -E "selector|volumeClaimTemplates|serviceName|strategy|roleRef|resource-policy|storageClassName|accessModes" charts/veyq/templates/*.yaml`
- `sed -n 1,30p charts/veyq/templates/_helpers.tpl`; `ls charts/veyq/ci/`
- `grep -n -E "def (default|resolve|prepare)_predecessor" scripts/run_web_e2e.py`
- `grep -n "Chart checks\|chart_meta_check\|helm" .github/workflows/container.yml`; `grep -rln "chart_meta_check" scripts docs infra`
- Docs: `infra/README.md` § Running on Kubernetes (its upgrade paragraph), ADR 0064 Addendum, `docs/inbox.md` #428.

## Decisions

1. **One script** (`scripts/chart_upgrade_check.py` or a mode of `chart_meta_check.py` - whichever keeps one
   rendering helper; do not duplicate the render), with a pure comparison function over two lists of rendered manifests
   keyed by `(kind, metadata.name)`, unit-tested on hand-written manifest pairs (a selftest beside the other script
   selftests). It refuses, each with a reason naming the object and field:
   - a Deployment's or StatefulSet's `spec.selector` changed; a StatefulSet's `serviceName`, `podManagementPolicy` or
     `volumeClaimTemplates` (name, `accessModes`, `storageClassName`, size) changed;
   - a PVC's `accessModes` or `storageClassName` changed, or its size shrank;
   - a RoleBinding's `roleRef` changed;
   - a kind changed under the same name;
   - a StatefulSet or a PVC of the predecessor missing from the candidate.
   Anything else may change.
2. **Values:** both renders with the candidate's `ci/*.yaml` (like for like), plus the candidate rendered with each of
   the predecessor's `ci/*.yaml` (an operator's existing values must still pass the schema and render).
3. **Verdict:** while `release:` is `none` a failure is printed with its reasons and exits 0 with "information only";
   from the release on it exits non-zero. One function decides that, reading the same pin line the upgrade proof reads.
4. **CI:** a step in the `test` job after "Chart checks" (the job's checkout needs the history to resolve the
   predecessor: `fetch-depth: 0`). A predecessor without `charts/veyq` (none today) is said and skipped.

## Phases

- [ ] 1 scripts + CI: Decisions 1-4.
  - Tests: the selftest covers every refusal of Decision 1 on a crafted pair and one "allowed change" control (an
    image tag or a label changes: no refusal); the verdict function for `release: none` and for a set release.
  - Bites: each refusal's check removed turns its selftest red; a real local run against `HEAD^1` is green; a chart
    edit that changes `veyq.selector` (or a claim template's size) makes the real run report it - restored after.
  - Done when the selftest and a local run are green and every `scripts/validate_*.py` is green.
- [ ] 2 docs: `infra/README.md` (what the check covers and does not), ADR 0064 Addendum (built), `docs/inbox.md` #428
  done.
  - Done when every `scripts/validate_*.py` is green, run on a detached worktree.

## Definition of done

- The selftest and a local run against `HEAD^1` are green; the bites turned red.
- Every `scripts/validate_*.py` is green, run on a detached worktree.

## Retrospective

_(filled at implementation)_
