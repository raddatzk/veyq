---
id: cr-workflow-actions-pinned-by-commit
title: Every workflow action is pinned by a commit SHA with its full version as a comment, runs on a live runtime, and a guard holds the shape
status: planned
satisfies: []
adrs: ["0057"]
invariants: []
---

## Open for the owner

**Answered by the owner, 2026-10-01: every recommendation below is taken, and implementation has the go.** Read each question's recommendation as its decision. Where a question proposed a separate inbox row, that row exists in `docs/inbox.md`.

1. **What replaces `gradle/actions/setup-gradle`?** Its pinned v4.4.3 runs on `node20`, which GitHub removed from
   its runners on 2026-09-23. Its live successor, v6, ships a proprietary caching component and uses it **by
   default**. The MIT path is an opt-in (`cache-provider: basic`, from v6.3.0). v5 is MIT and runs on `node24`,
   but it is a superseded major.
   **Recommendation: drop it.** Use `actions/setup-java` `cache: gradle`, the vendor-neutral standard (MIT). Add
   one `gradle/actions/wrapper-validation` step (MIT, `node24`) before `./gradlew` in each job that runs it. That
   step is what keeps #189's CI-time JAR check. CLAUDE.md prefers the vendor-neutral standard over a single
   vendor's client, and the MinIO case is the same shape. The fallback is v6.3.0 with `cache-provider: basic`,
   plus a guard rule that requires `basic` on every such step. Its cost: every Dependabot bump keeps pulling a
   bundle that contains closed code, and a new step that forgets the input runs that code silently.
2. **Bump every action to its current `node24` major here, or only fix the pin shape?** 10 of the 12 pinned
   actions run on `node20` (see Inventory). **Recommendation: bump here.** CLAUDE.md says a deprecated dependency
   is replaced now, and the bump edits the same 39 lines that the comment fix touches anyway. Keep
   `sigstore/cosign-installer` at v3 (it is `composite`, so node20 does not touch it). A major bump there
   changes the cosign that signs the release, and that belongs to a release decision.
3. **Its own guard, or check 6 of `validate_build_input_pins.py`?** **Recommendation: its own
   `scripts/validate_action_pins.py`.** It has the shape of `validate_image_pins.py`: its own anchor set, its own
   defect and its own limits. `validate_build_input_pins.py` already carries five checks once #189 lands.
4. **Is an online check worth it?** Whether a 40-hex SHA is a *commit* or an *annotated tag object* can only be
   told through the API. That tag-object case is the defect that opened this row. **Recommendation: no guard.**
   Record the resolution command in this CR's DoD, the same trust-on-first-write stance `validate_image_pins.py`
   takes for index digests. The chain stays buildless and offline. A later bump owes the same check.

## Goal

Every `uses:` that reaches outside the repository names an immutable **commit** SHA, followed by the **full**
version it resolves to (`# v6.0.1`, not `# v4`). The same action carries one SHA everywhere. No action runs on a
runtime GitHub has removed. A guard in the trust-chain glob keeps the first three true.

## Context & constraints (measured at refine, worktree on `64c1f65`)

- **Sites.** 39 `uses:` lines across 7 workflows, 13 (action, SHA) pairs, 12 distinct SHAs, 12 repositories. No
  composite `action.yml` is tracked, and no workflow has job-level (reusable-workflow) `uses:`. Measured with
  `grep -hE '^\s*(-\s+)?uses:' .github/workflows/*.yml | wc -l` → 39 and
  `git ls-files | grep -E '(^|/)action\.ya?ml$' | wc -l` → 0. The one other `uses:` hit, `codeql.yml:122`, is a
  YAML comment.
- **The pin shape.** All 39 are `owner/repo[/path]@<40 hex>`. **All 39 carry a major-only comment** (`# v4`,
  `# v3`…), and 0 carry a full version. Measured with the same grep plus
  `@[0-9a-f]{40}\s+#\s*v[0-9]+\s*$` → 39, and with `…v[0-9]+\.[0-9]+\.[0-9]+\s*$` → 0.
- **One annotated tag object, 12 commits.** The API resolution below gives this per pair. `setup-gradle@48b5f213…`
  is a tag object (→ `v4.4.3`). Each of the other 12 SHAs is a commit, and its most specific tag is:
  checkout v4.3.1, cache v4.3.0, setup-java v4.8.0, setup-node v4.4.0, build-push-action v6.19.2, login-action
  v3.7.0, metadata-action v5.10.0, setup-buildx-action v3.12.0, paths-filter v3.0.3, codeql-action (init and
  analyze share one SHA) v4.36.2, cosign-installer v3.9.1.
  The resolution: for each pair, `GET /repos/<o>/<r>/git/commits/<sha>` (200 = commit) and
  `GET /repos/<o>/<r>/git/tags/<sha>` (200 = tag object), and `GET /repos/<o>/<r>/tags` filtered on
  `commit.sha == <sha>`. The refine's script was scratch-only: `r14/deps/pins.py`.
- **The runtime.** At the pinned SHA, `runs.using` is `node20` for 10 of the 12 SHAs. The exceptions are
  codeql-action (`node24`) and cosign-installer (`composite`). Measured by reading `action.yml` at
  `raw.githubusercontent.com/<repo>/<sha>/[<path>/]action.yml`. GitHub's changelog *Deprecation of Node 20 on
  GitHub Actions runners* (2025-09-19, editor's note 2026-08-25) says: Node24 became the default on 2026-06-16,
  and Node20 was **removed on 2026-09-23**. Measured by fetching that page.
  What a removed-runtime action does now (forced onto node24, or refused) is remembered, not measured.
- **Current releases, all `node24`, measured on 2026-09-28** (`github.com/<repo>/releases/latest` redirect, then
  `action.yml` at that tag): checkout v7.0.1, cache v6.1.0, setup-java v6.0.1, setup-node v7.0.0,
  build-push-action v7.4.0, login-action v4.6.0, metadata-action v6.2.0, setup-buildx-action v4.4.1,
  paths-filter v4.0.3, cosign-installer v4.1.2 (composite). These numbers are spent the day they are read, so
  re-resolve them when implementing.
- **gradle/actions licensing, read from the files at each tag.** At v4.4.3, v5.0.0 and v5.0.2 no file mentions a
  proprietary component, and v5.x is `node24`. The v6.0.0 release notes list "Extract caching logic into a
  separate gradle-actions-caching component". The v6.3.0 `DISTRIBUTION.md` rates *Enhanced Caching*
  "Proprietary / Closed" and makes it the default. *Basic Caching* is MIT, opted into with
  `cache-provider: basic`. `wrapper-validation` is `node24` from v5.0.0 on and has no cache input.
- **Why the comment must carry the full version, from Dependabot's source** (dependabot-core `main`, fetched
  2026-09-28; the repo has no remote, so this is a source read, not a run).
  `github_actions/lib/dependabot/github_actions/file_updater/workflow_updater/version_commenter.rb`
  `previous_version_from_comment` collects the version tags that point at the **old** SHA
  (`common/lib/dependabot/git_commit_checker.rb` `local_tags_matching_sha`). It keeps only the ones the comment
  **ends with**. If none matches, `updated_comment` returns nil and the old comment is kept verbatim.
  A floating major tag (`v4`) moves off the pinned SHA at the next release. Measured by the tag listing above:
  the floating major no longer points at checkout's, setup-java's, paths-filter's or codeql-action's pinned SHA.
  It still does for 7 pairs (cache, setup-node, the four docker actions, cosign-installer). So `# v4` stops matching exactly when a bump arrives, and Dependabot would
  move the SHA to v7 and leave `# v4` standing: a comment that lies.
  A full-version tag stays on its commit, so `# v4.3.1` always matches and is rewritten to the new version.
  An annotated-tag-object SHA matches no tag's `commit_sha` at all.
- **How the existing guards read workflows.** `validate_image_pins.py` and `validate_release_identity.py`
  `yaml.safe_load` `container.yml` for its build matrix. `validate_image_pins.py` finds its sites by **key**
  across `git ls-files` (any YAML key `image`), and falls back to a line regex where YAML cannot parse. Take the
  same shape: derive the anchors from the key `uses`, never from a list of files or actions.
- **Interaction with #189 (a go-given direct fix, not yet landed; checked with `git grep -c 48b5f213 main`).**
  Its step (1) re-pins `setup-gradle` to `ed408507… # v4.4.3`, a **commit, but a `node20` one** (measured above).
  Its check-5 clause "no workflow sets `validate-wrappers: false`" is about `setup-gradle`. This CR lands after
  #189 and rewrites that clause for whatever Q1 chooses.
- **Nothing here is provable by a run.** No workflow has ever executed (no remote). Correctness stays file-level,
  the same stated gap as ADR 0057's publish.

## Scope

**In:** every tracked `.github/workflows/*.y*ml`, and every tracked `action.y*ml` (none today; the guard's anchor
set covers them when one appears). The new guard. ADR 0057 §6 gains the build runner. A `.github/dependabot.yml`
comment on the version-comment convention.
**Out:** the cosign major (Q2). A GitHub repository setting that enforces SHA pinning at run time. It is a
real complement, but there is no remote to set it on (remembered: the setting exists). Runner images.

## Phases

### Phase 1: guard red, then pins, then guard green

1. **Write `scripts/validate_action_pins.py` first.** Its docstring carries the required fields
   (`changes/README.md` § trust-chain guards). It asserts:
   - **R1 (shape):** every `uses:` value under any tracked workflow or `action.y*ml` is local (`./…`),
     `docker://…@sha256:<64 hex>`, or `owner/repo[/path]@<40 lowercase hex>`. Found by YAML key, at step level
     **and** job level.
   - **R2 (comment):** the source line of every remote pin ends in `# v<MAJOR>.<MINOR>.<PATCH>`, with an optional
     pre-release suffix. The YAML-derived set and the line-derived set must be equal. A flow-style step
     (`- {uses: …}`), or any other `uses:` whose comment the guard cannot locate, is red, never skipped.
   - **R3 (one pin per action):** one `owner/repo` carries one `sha + comment` everywhere, keyed on the repo,
     not the path, so codeql `init` and `analyze` cannot split.
   - **R4 (only if Q1 = fallback):** every `gradle/actions/setup-gradle` step sets `cache-provider: basic`.
2. **Red-first:** run it on the untouched tree. Expected: R2 names all 39 sites. Record the output in the commit
   body.
3. **Re-pin** per Q1 and Q2. Resolve each target tag to its **commit**: `git/commits/<sha>` 200 and
   `git/tags/<sha>` 404, or peel the tag object. Write `@<commit> # v<full>`. Read each action's release notes
   between the pinned and the target major for breaking changes to the inputs we pass
   (`fetch-depth`, `cache: npm`, `distribution`, `java-version`, buildx, metadata `tags`, …). Write down each
   one that applies, plus how it was handled, in the commit body.
4. For Q1 = drop: replace each of the 4 `setup-gradle` steps with `cache: gradle` on the preceding `setup-java`,
   plus a `gradle/actions/wrapper-validation@<commit> # v<full>` step. Rewrite #189's check-5 workflow clause
   to fit.

**Done when:** `python3 scripts/validate_action_pins.py` is red on the pre-edit tree (R2, 39 sites) and green
after the edits. `validate_release_identity.py` and `validate_image_pins.py` stay green, since both parse
`container.yml`. The whole glob passes: `for f in scripts/validate_*.py; do python3 "$f"; done`. Every pin's
commit-ness is shown by the API resolution, rerun and pasted in the commit body. The `runs.using` of every
re-pinned SHA reads `node24` or `composite`.

**Bite (snapshot-and-restore, `changes/README.md` § bite-proof a guard). Each lever must turn the guard red:**
- R1: one SHA → `@v4`; one SHA truncated to 39 hex; one SHA upper-cased; `docker://alpine:3` without a digest.
- R1 anchor: a job-level `uses: o/r/.github/workflows/x.yml@main`; a scratch tracked `action.yml` with a
  composite step `uses: actions/checkout@v4`. This proves the anchor set is derived, not listed.
- R2: one comment cut to `# v6`; one comment deleted; one step rewritten flow-style.
- R3: one `actions/checkout` site given a different valid 40-hex SHA.
- R4 (fallback only): `cache-provider: basic` removed from one step.

### Phase 2: docs

- ADR 0057 §6, *At build time*: the actions that build and sign the release are pinned by commit SHA, with the
  full version as a comment, held by `validate_action_pins.py`. The bump discipline is unchanged.
- `.github/dependabot.yml`, the `github-actions` entry: one comment stating the full-version-comment convention,
  and why a major-only comment goes stale, citing the source lines above.
  This must not contradict the file's header ("UNPROVEN BY CONSTRUCTION").
- The trust-chain doc gate (`scripts/validate_*.py` glob) is green.

**Done when:** the glob is green and ADR 0057 names the guard.
