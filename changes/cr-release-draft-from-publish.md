---
id: cr-release-draft-from-publish
title: The weekly Publish writes the release draft with its notes; the owner reads it and runs the release
status: in-progress
satisfies: []
adrs: ["0091", "0057"]
invariants:
  - "a release is still started by the owner alone: nothing dispatches release.yml, and the draft starts no workflow (owner 2026-11-22: \"halbautomatisch\")"
  - "the release signature stays release.yml's, the snapshot signature publish.yml's; the new job signs nothing, builds nothing, pushes no ref"
  - "release.yml's rule holds: at most one draft release exists — Publish rewrites its own unedited draft, never creates a second, never touches a draft a person edited"
  - "a commit that carries a release tag is never published as a snapshot again"
  - "a pull request's body reaches a script only through $GITHUB_EVENT_PATH or the API, never through a ${{ }} expression in a run line"
  - "the release notes are the user's language (Use-area tone), written when the work is fresh: in the pull request, by whoever writes it"
---

# The weekly Publish writes the release draft; the owner reads it and runs the release

## Goal

Today a release is two manual steps after the Monday snapshot: a draft on the `snapshot-<v>` tag with notes, then
*Actions → Release → Run workflow* ([`release.yml`](../.github/workflows/release.yml) header). The notes were always
written by a Claude session on request, from what it could reconstruct after the fact. The owner wants it
**half-automatic** (2026-11-22): every Monday the draft is simply there, notes included; the owner reads it, edits if
needed, and presses *Run workflow*. That click stays the release decision.

Since features land on `main` only as squashed pull requests once releasable ([`docs/ai/README.md`](../docs/ai/README.md)
§ measurement substrate, owner 2026-11-22), every commit on `main` after the last release maps to one pull request — so
the notes can be assembled from what each pull request already says.

A defect found on the way is fixed first, because the draft would inherit it: the scheduled Publish of 2026-11-16
re-published `b7dda2de` as `snapshot-1.247` two days after it had been released as `v1.247`, recreating the tag the
release had deleted.

## Context & constraints

### Measured (2026-11-22, this branch at `db49cc529`)

- `publish.yml` runs `cron: "17 3 * * 1"` (Monday 03:17 UTC) and on dispatch; jobs `checks-run` → `snapshot` →
  `snapshot-tag`, plus `staging-retention` on the schedule (`grep -n -E "^  [a-z-]+:$" .github/workflows/publish.yml`).
- The stale snapshot: `gh run view 43858520222 --log` (schedule, 2026-11-16 11:31 UTC) logs "this build already holds
  its snapshot names" and tags `snapshot-1.247`; `git ls-remote --tags origin` lists both `snapshot-1.247` and `v1.247`
  on `b7dda2defebb98dd68edb63f196190b4ae2dfacd`. Cause: `scripts/snapshot.py` `checks_run()` asks `tags_of()` for
  `snapshot-*` tags only (`git tag --list snapshot-* --points-at`), and `release.yml` deletes the snapshot tag once it
  has released — so the next Publish of an unchanged `main` sees an unpublished commit.
- `scripts/promote.py` check 1 refuses unless **exactly one** draft exists, on a `snapshot-<major>.<minor>` tag.
- `release.yml` writes a refusal into the draft's body (`promote.py note`), and its `publish` job deletes a "replaced"
  draft after publishing.
- Release history: `gh release list` — v1.84 … v1.249 between 2026-11-12 and 2026-11-16, ten releases, nearly all from
  dispatched snapshots; the schedule fired once (2026-11-16, eight hours late).
- Past notes (`gh release view v1.249 --json body`): a one-line lede, **What is new**, **Fixed**, **What an operator
  must act on**, and a fixed footer pointing at *Verify a release*. Pull request bodies (`gh pr view 42`) are
  engineering prose (Why / What changed), not user notes — they cannot be pasted as notes.
- The squash commit body holds the branch's commit list, not the PR description (`git log -1 --format=%B fddbe1d2e`):
  a note must be read from the pull request through the API.
- The repository is public (`gh repo view --json visibility`).
- `scripts/validate_release_identity.py` holds every `write` permission of every Checks and Publish job by job name
  ("a job renamed is red until this guard learns its permissions"); a new Publish job with `contents: write` is red
  there until it is taught.

### Remembered, to measure in the phase that needs it

- Which guard constrains a **new workflow file** (phase 2's `release-note.yml`): `validate_pipeline_shape.py` at least.
- Whether `GET repos/{repo}/commits/{sha}/pulls` needs `pull-requests: read` on a public repository's `GITHUB_TOKEN`
  when the job declares only `contents` (declare it either way; least privilege still holds — it is read-only).

## Design

### 1. A released commit is never snapshotted again

`snapshot.py checks-run`'s walk treats a commit that carries a `v<major>.<minor>` tag exactly like one that carries its
`snapshot-` tag: `published`, `kind=none`, a notice that names the release. Nothing else changes.

### 2. The note is written in the pull request

Every pull request body gets a section `## Release note`, written by whoever writes the pull request (in practice the
session that built it, when the context is fresh):

```markdown
## Release note

**What is new**
- **A start screen instead of a white page.** While veyq starts, …

**Fixed**
- A video … could occasionally fail to start …

**What an operator must act on**
- Set `VEYQ_X` before upgrading …
```

Any of the three headings may be left out; `none` alone means "nothing a user or operator notices" (CI, docs,
refactors). The tone is the Use area's ([`docs/ai/README.md`](../docs/ai/README.md) language rules): what a person
sees, never class names.

- `.github/pull_request_template.md` carries the section.
- A small workflow `release-note.yml` on `pull_request` (`opened`, `edited`, `synchronize`, `reopened`), `permissions:
  {}`, runs `scripts/release_notes.py check` against `$GITHUB_EVENT_PATH`: red when a non-Dependabot pull request has
  no `## Release note` section, or one that is neither `none` nor at least one item under a known heading. Its own
  workflow, not a job in `checks.yml`, so editing a body re-runs only this. Advisory (the ruleset requires no check).
- `docs/ai/README.md` gets the rule beside the pull-request paragraph.

### 3. Publish writes the draft

A new job `release-draft` in `publish.yml`, after `snapshot-tag`, **on `main` only** (a fix release on
`release/<major>.x` stays a hand-made draft — rare, and a second draft would break check 1):

- `if: !cancelled() && needs.snapshot-tag.result == 'success' && github.ref_name == 'main'`.
- `permissions: contents: write` (the release API) and `pull-requests: read`; no `packages`, no `id-token`.
- Runs `scripts/release_notes.py draft --version <v> --commit <commit>` (stdlib only, no third-party code beside the
  token):
  1. **Range:** the newest `v<major>.*` tag that is an ancestor of `<commit>` … `<commit>`, first-parent. No such tag
     → no draft, a notice (the first release of a major gets its notes by hand).
  2. **Notes:** per commit its merged pull request (`commits/{sha}/pulls`), in merge order; each `## Release note`
     section's items merged under the three headings; `none` skipped; Dependabot pull requests become one line
     "Dependencies updated: #22, #25, …" under its own heading **Under the hood**; a pull request without a usable section is listed under **Needs a note** (visible: the owner
     sees it while reading); a commit without a pull request goes to the job summary only. **What an operator must
     act on** defaults to "- Nothing." The fixed *Verify a release* footer closes it. No lede line: one sentence for
     several pull requests is a judgement the owner adds when wanted (decision below).
  3. **Marker:** the body ends with `<!-- veyq-release-draft sha256:<hash of the body above> -->`.
  4. **One draft:** list drafts. None → create `veyq <v>` on `snapshot-<v>`, draft. One whose marker matches its body
     (untouched) → `PATCH` it onto `snapshot-<v>` with the new body — the range starts at the last release, so nothing
     an older draft named is lost. One that was edited (no marker, or a mismatching hash — a person's edit, or
     `release.yml`'s refusal note) → **leave it**, notice + job summary "a draft someone edited is waiting:
     <url>". More than one → leave all, notice.
- `validate_release_identity.py` learns the job: exactly `contents: write` + `pull-requests: read`, no ref write, no
  `gh release` other than create/edit of a draft, no `draft=false` (publishing stays `release.yml`'s alone).

### 4. Docs

ADR 0091 decision 5 amended (owner 2026-11-22: the draft is Publish's, the run is the owner's), `infra/README.md`
§ Cutting a release, `docs/crosscutting/release-and-deployment.md`, `release.yml`'s and `promote.py`'s headers (step 1
"Draft a new release … write the notes" becomes "read the Monday draft"), inbox #460's row.

## Phases

### Phase 1 — a released commit stays released

`snapshot.py`: `tags_of()` also lists `v[0-9]*` tags; the notice names the release. `scripts/snapshot_selftest.py` gets
the case. **Bite:** revert the `tags_of` change → the new selftest case is red.
**Done when:** `/usr/bin/python3 scripts/snapshot_selftest.py` green, the bite red, guards green.

### Phase 2 — the note in the pull request

`scripts/release_notes.py` with the pure functions (`parse_section`, `check`, `assemble`, `marker`) and its selftest
`scripts/release_notes_selftest.py` (run by `checks.yml` beside `snapshot_selftest.py`); `release-note.yml`;
`.github/pull_request_template.md`; the `docs/ai/README.md` rule. **Bites:** a body without the section passes `check`
→ red; Dependabot's author not exempt → red; a `${{ github.event.pull_request.body }}` in any run line →
`validate_release_identity.py` (or the guard that owns workflow lines) red.
**Done when:** selftest green with each bite red; the guards green; this CR's own pull request carries a release note.

### Phase 3 — Publish writes the draft

The `release-draft` job and `release_notes.py draft` (the API calls behind a seam the selftest forges: no draft,
one untouched, one edited, two, no previous tag, a PR without a note, a Dependabot PR, a commit without a PR).
`validate_release_identity.py` taught, with bites: the job given `packages: write` → red; a `draft=false` in it → red.
**Done when:** selftests and `validate_release_identity.py` green, every bite red; guards green.

### Phase 4 — the docs move with it

Section 4 above. **Done when:** the trust chain is green and no doc still tells the owner to write the notes.

### Phase 5 — on GitHub (owner's go for each step)

1. The pull request of phases 1–4 merged (the owner's merge).
2. The stale `snapshot-1.247` tag deleted (`gh api -X DELETE repos/raddatzk/veyq/git/refs/tags/snapshot-1.247`) —
   outward, owner's go.
3. One Publish dispatch on `main`: the draft appears with notes from the pull requests since `v1.249`; the owner
   reads it and runs Release. Then the next Monday's schedule: a draft again, or the notice that nothing changed.
**Done when:** a release was promoted from a draft Publish wrote, and a Monday with nothing new wrote nothing.

## Progress (2026-11-22, branch `claude/sunday-snapshot-release-d7cccd`)

- **Phase 1** built: `snapshot.published_by()`; bite (snapshot-only filter) red on three cases.
- **Phase 2** built: `scripts/release_notes.py` + selftest (nine bites red), `release-note.yml`, the PR template, the
  `docs/ai/README.md` rule. The promised injection bite found **no** guard: a `${{ github.event.pull_request.body }}`
  in a run line was green everywhere — so `scripts/validate_workflow_injection.py` is new (ten probes: eight red, the
  `env:` form and repository-owned contexts green).
- **Phase 3** built: `publish.yml` › `release-draft`; `validate_release_identity.py` taught the one exception (seven
  bites red). On the way it closed a gap: its file-wide "only release.yml writes a GitHub Release" scan matched
  `gh api -X POST …releases` but not `--method POST` — now both.
- **Phase 4** done: ADR 0091 decision 5 amended, `infra/README.md`, `release-and-deployment.md`, the headers of
  `release.yml`, `publish.yml`, `promote.py`, the stage map's budgets, inbox #460.
- **Phase 5** under way (owner's go 2026-11-22): #44 merged (`b4d4f8e6a`); `snapshot-1.247` deleted. Publish run
  45074147683 refused, correctly — #45 is docs-only, so the snapshot is #43's `615d3b97f`, whose Checks run was still
  queued. Run 45076081333 tagged `snapshot-1.262` and created draft 454786982 from the two pull requests since
  `v1.260` (#43, #44): `commits/<sha>/pulls` finds a squash-merged pull request, and the draft read back through the
  API counts as untouched — both unknowns measured. The draft named #43 under **Needs a note** (opened before the
  template); its note (`none`) was written in #43, and that showed two gaps, closed on
  `claude/release-draft-refresh`: no run could refresh the draft (the job ran only after a new tag; a commit with its
  snapshot publishes nothing) — now a dispatch over an unreleased snapshot does (`draft_version`, the pinned `if:`,
  the tag check in `release_notes.py`); and Release would have published a draft with **Needs a note** — now
  `promote.py` check 1 refuses it. Eleven bites red (#53).
- 2026-11-23: dispatch run 45153047948 moved the untouched draft 454786982 onto `snapshot-1.269` (`update`, nine pull
  requests) — #43's corrected note was in it. The **owner released v1.312 from that draft** (2026-11-25): the first
  half of *Done when* holds. v1.318 followed from draft 459484280 (run 45833567685, 2026-11-26).
- 2026-11-23: the **schedule** ran at 12:56, not 03:17, and refused HEAD's `pending` Checks run (45179033821): no
  snapshot and no draft that week. Fixed on `claude/publish-schedule-waits`: the schedule waits up to 120 minutes for
  a Checks run still going (ADR 0091 decision 5, refined 2026-11-26); ten bites red. Owed: merge it, then a Monday
  with nothing new (no draft written) — and a Monday whose schedule meets a running Checks run, if one comes.

## Decisions (mine, at refine — 2026-11-22)

- **Notes from a section in the pull request**, not from commit titles (they are engineering language — past notes
  never read like them) and not from a model called in CI (a closed vendor and a secret in the release path, for text
  the owner reads anyway). The note is written when the work is fresh, by the one who did it.
- **No lede line generated.** One sentence that sums up several pull requests is an editorial judgement; the owner
  adds one in review when wanted. Copy tone is the owner's: this is open to their override.
- **`main` only.** A fix release on `release/<major>.x` is rare and would collide with the one-draft rule; it stays
  manual.
- **An edited draft is never overwritten**, detected by a hash marker rather than by tracking who edited it: cheap, and
  it also keeps a draft that carries `release.yml`'s refusal for the owner to see.
- **A separate `release-draft` job**, not a step in `snapshot-tag`: that job is the one ref writer and its permission
  line is the owner's decision of 2026-11-06; the draft needs `pull-requests: read` besides.
- **The PR check is its own workflow**, so a body edit re-runs one small job and not the pipeline.

## Open for the owner

- The lede (above): generated-none is my call; say if every release should open with one sentence.
- Phase 5's outward steps, each on its own go.
