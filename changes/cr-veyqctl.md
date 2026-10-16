---
id: cr-veyqctl
title: veyqctl — install and upgrade a Compose instance from immutable releases; the recipe leaves the image
status: in-progress
satisfies: ["uc.admin.install-and-upgrade-instance"]
adrs: ["0096", "0057", "0090", "0091", "0056", "0086"]
invariants:
  - "Self-hostable by default: GitHub is the default source, never a requirement — a mirror or a local directory serves the same layout"
  - "ADR 0057: a release is deployed by the digests it names, after their signatures verified; nothing unverified is pulled or started"
  - "ADR 0056: an upgrade backs up all three legs before the new app migrates, and the key never lands beside the dump unless asked"
  - "Hardened recipe: veyqctl adds no container, no Docker socket mount and no privilege to the stack"
---

## Goal

Build [ADR 0096](../docs/decisions/0096-veyqctl-manages-an-instance-from-immutable-releases.md): `veyqctl`, one
host-side Python file that installs a Compose instance from a published release and upgrades it by hand along the
line `.env`'s `VEYQ_RELEASE` selects; a producer that turns the tree into a release's assets (the recipe, `veyqctl`,
the backup tool and a manifest naming both images by digest); the publish creating an immutable GitHub Release from
them; and the end of the recipe inside the image (ADR 0057 §3), which closes the `garage.toml` gap that started it
(inbox #26). The stack lane of [ADR 0090](../docs/decisions/0090-end-to-end-tests-boot-the-release-candidate.md) then
boots the candidate through `veyqctl install`, so the operator's path is the tested one.

The owner's go covers every phase, landing on `main` included (2026-10-16).

## Context & constraints

- **Use-case:** [uc.admin.install-and-upgrade-instance](../docs/features/admin/use-cases/uc.admin.install-and-upgrade-instance.md),
  coverage `planned` for good — a Python operator tool is not a coverage surface (ADR 0040; the file's comment says
  what proves it instead).
- **There is no remote** (`git remote -v` → empty). The publish wiring (phase 5) is proven only by the guard that
  reads it; the immutable-releases repository setting is the owner's, once the remote exists (inbox #26 item 1).
  Every other phase is proven by local runs: images built with `docker build`, assets produced into a directory,
  `veyqctl --source <dir>`.
- **The publish is the dispatch-only `publish` job of `.github/workflows/ci.yml`** (`grep -n "^  publish:" .github/workflows/ci.yml`):
  cr-one-pipeline-per-commit (in progress) folded `container.yml` into `ci.yml` (its phase 2, landed); that CR's
  open phases touch budgets, guard gaps in CI and a local runner, and it leaves the release as a promotion (inbox
  #460) to a later CR. Phase 5 wires the release into the `publish` job; that promotion inherits it when it is
  written.
- **`cr-stack-e2e` is live and writes `scripts/run_stack_e2e.py` too** (its phases 2–5; a session is on phase 2 at
  refine time). Two CRs, one file: this CR owns the recipe-and-`.env` half (`extract()`, `write_env()`), cr-stack-e2e
  everything after `up`. Phase 4 here re-reads that file on `main` before editing, and its hand-off names the change
  for cr-stack-e2e's owner session; this CR never edits `changes/cr-stack-e2e.md` (§ *a sibling CR still in flight*).
  cr-stack-e2e's Goal says the recipe is "taken out of the app image" — phase 4 makes that sentence false, and the
  hand-off says so.
- **Python conventions, measured:** the floor is 3.9 (`grep -n "from __future__" scripts/instance_backup.py` and 15+
  scripts saying "the repo's scripts run on Python 3.9"), stdlib only for an operator tool, no pytest/unittest
  (`git grep -n -e "import unittest" -e "import pytest" -- '*.py'` → none): a tool is proven by a
  `<tool>_selftest.py` with a `check()` helper, run in `ci.yml` (`grep -n "selftest" .github/workflows/ci.yml`).
  `run_stack_e2e.py` imports PyYAML; `veyqctl` must not — it reads the project name and services through
  `docker compose config --format json`.
- **`validate_image_pins.py` scans every module-level `<NAME>IMAGE = "<ref>"` in Python** (its docstring); `veyqctl`
  names no image of its own, and a constant it grows is pinned by digest or it is red.
- **Decided here (ADR 0096 left it to the CR):**
  - *`instance_backup.py` ships beside `veyqctl` as a release asset*, and `veyqctl backup|restore|upgrade` import it
    from the running release's directory. Folding 1,320 lines into one file would fork the code `run_web_e2e.py`
    imports and `instance_backup_selftest.py` proves; beside it, both keep testing what ships.
  - *An operator override file.* `veyqctl` always runs `docker compose -f releases/<r>/docker-compose.prod.yml`, plus
    `-f /opt/veyq/compose.override.yml` when that exists, and keeps every `.env` key the template does not name across
    upgrades. That is Compose's own extension point, the stack lane's Pebble overlay uses it, and an operator's
    additions survive an upgrade instead of being overwritten.
  - *The mirror layout is the producer's output:* `<source>/releases.json` (tag, date, prerelease flag, manifest) plus
    `<source>/<tag>/<asset>`. A local directory, an HTTP mirror and the GitHub adapter all answer the same three
    questions (list, manifest, asset), so the stack lane and the selftest use the real resolver.
  - *A local candidate* passes `--unsigned-local`, refused unless both image references carry no registry host and no
    digest (a `docker build -t veyq:<x>` tag); the run says on every line that nothing was verified.
  - *The manifest marks an unscheduled major* (`"unscheduled": true`), from a publish input that defaults to false
    and is refused on a release that does not change the major. The backend's release check reads it
    (`core/admin/instance/ReleaseFeed`, ADR 0097 — built: through the GitHub source it reads each new major's
    `manifest.json` release asset, so phase 5's publish must upload it among the assets); this CR only writes it.

### Inventory — the locators

| What | Locator |
|---|---|
| The in-image recipe and everything that reads it | `git grep -n -e "opt/veyq/deploy" -e "docker cp veyq-recipe" -e "RECIPE" -- ':!docs/decisions/0096*' ':!changes/'` |
| Files the production compose file reads beside itself (the recipe set the producer must carry) | `grep -n '\./' infra/docker-compose.prod.yml` and `docker compose -f infra/docker-compose.prod.yml config --no-interpolate --format json` (bind sources) — two angles, compare |
| Guards reading the recipe files | `grep -l -e "env.example" -e "docker-compose.prod.yml" scripts/validate_*.py` |
| The publish job's steps (refuse, sign, chart, tag) | `sed -n '/^  publish:/,$p' .github/workflows/ci.yml \| grep -n -e "- name:"` (the job is the file's last) |
| Run pages with Compose procedures | `git grep -c -e "docker compose" -e "instance_backup" -e "infra/" -- site/src/content/docs/run` |
| `.env` keys and the reference page | `python3 scripts/validate_run_config_reference.py` (names the page lines it reads) |
| `run_stack_e2e.py`'s recipe half | `grep -n -e "^def extract" -e "^def write_env" -e "^def copy_out" -e "RECIPE" scripts/run_stack_e2e.py` |

## Phases

- [x] **1 The release, as files: the producer and its guard.** `scripts/release_assets.py <out> --app <ref> --admin <ref> [--unscheduled]`
  writes one release in the mirror layout: the recipe set, `veyqctl`, `instance_backup.py`, and `manifest.json`
  (release, date, both images, `unscheduled`). The **recipe set is derived**, never listed: `.env.example` plus the
  production compose file plus every file it reads relative to itself — enumerate the spellings
  (`services.*.volumes` short and long `type: bind`, `env_file` string/list/`{path:}`, `label_file`, top-level
  `configs.*.file` / `secrets.*.file`); refuse `${…}` in such a path, `../`, `include:` and `extends`. The guard is the
  same derivation: `validate_release_identity.py` assertion 4 becomes "the producer carries every file the recipe
  reads", replacing the Dockerfile half (which phase 4 deletes); its image-tag half stays. Its OK line prints the
  derived list. Docstring: the three fields, the limit "absolute host paths are the host's, unchecked".
  - **Bites** (detached worktree of `HEAD`, snapshot-and-restore): the producer told to skip `garage.toml` → red
    naming it; a long-form `type: bind`, `source: ./extra.yml` on `traefik` → red; `env_file: ./extra.env` on `app`
    → red; `"${GARAGE_CONF:-./garage.toml}:/etc/garage.toml:ro"` → red; a top-level `include:` → red. Each red on the
    derivation's message, not on a missing file.
  - **Done when:** the bites read red then green; `python3 scripts/release_assets.py` into an empty directory lists
    the files and `cmp` matches each recipe file against the tree; `python3 scripts/validate_release_identity.py`
    green; committed.

- [ ] **2 `veyqctl` installs, runs and verifies.** `scripts/veyqctl.py` (the asset is `veyqctl`): Python 3.9, stdlib,
  one file. `install`, `start`, `stop`, `status`, `logs`, `verify`, `check`; `--home` (default `/opt/veyq`), `--source`
  (default the GitHub Releases of `raddatzk/veyq`; a URL or a directory in the mirror layout). Resolution of
  `VEYQ_RELEASE` (`<major>.<minor>` | `<major>` | `latest`; prereleases never), signature verification through the
  host's `cosign` with the identity `verify-a-release.md` pins (one source for it: the guard's assertion 7 reads the
  page, and phase 2 adds `veyqctl` to what it reads), `.env` from the template (secrets generated, operator values
  asked or given with `--set KEY=VALUE`, mode 0600), the override file, `--unsigned-local`.
  `scripts/veyqctl_selftest.py` — no Docker: the resolver against a mirror directory it writes, the template merge,
  the refusals (`--unsigned-local` with a registry ref; a prerelease; `../` in an asset name), and the manifest parse.
  Added to `ci.yml` beside the backup self-test. `release_assets.py`'s `TOOLS` gains `veyqctl` (phase 1 carries
  `instance_backup.py` alone: `veyqctl` did not exist yet).
  - **Live:** build both images locally; `release_assets.py` into `<tmp>/src`; with the stack lane's Pebble overlay
    copied to `<tmp>/home/compose.override.yml`, `veyqctl --home <tmp>/home --source <tmp>/src install --unsigned-local`;
    `veyqctl status` lists every service healthy; `stop`, `start`, healthy again; tear down by project name.
  - **Done when:** selftest green; the live run's log read and kept; guards green; committed.

- [ ] **3 `upgrade`, `backup`, `restore`, `self-update`.** `upgrade` exactly as ADR 0096 decision 5 orders it, nothing
  stopped before the template check; `backup`/`restore` delegate to the running release's `instance_backup.py`
  (`--key-into` required for `backup` and for `upgrade`'s backup); `self-update` replaces the running `veyqctl` with
  the installed release's, verified.
  - **Live:** two releases from two commits — the parent of this phase's first commit and its head — each built and
    produced into the same `<tmp>/src`; install the first, seed one Memory through the stack lane's seed (or the web
    e2e seed against its port), `upgrade` to the second: healthy, a backup with three legs under `backups/`, the first
    release's directory still there, the Memory still opens.
  - **Bites:** (a) *injection* — the second release's template and compose gain `VEYQ_BITE_REQUIRED` with `${…:?}`:
    `upgrade` names it and every container's `StartedAt` is unchanged (`docker inspect`), proving "before anything
    stops"; (b) *injection* — the second manifest's app digest altered by one hex digit: refused at verify, nothing
    pulled; (c) *injection* — `--key-into` omitted: refused before the stop.
  - **Done when:** the live upgrade and the three bites read; selftest extended (decision order, `VEYQ_RELEASE` per
    line) and green; committed.

- [ ] **4 The recipe leaves the image; the stack lane boots through `veyqctl`.** Re-read `scripts/run_stack_e2e.py` on
  `main` first (cr-stack-e2e moves it). Delete the two `COPY … /opt/veyq/deploy/` lines and their comment block from
  the `Dockerfile`; `extract()` and `write_env()` give way to `release_assets.py` into the run directory plus
  `veyqctl install --unsigned-local` with the overlay as the override file and the run's values through `--set`; the
  project name and teardown stay the run's. ADR 0057 §3 rewritten in place (the recipe is the release's assets; the
  binding is the release, not the image); ADR 0090 decision text and status line say L1 boots through `veyqctl`;
  `charts/veyq/templates/garage-configmap.yaml`'s header comment no longer says "the repository's copy".
  - **Bite:** the overlay dropped from the override file → the run is red at the certificate stage, proving it reads
    the override and not a leftover.
  - **Done when:** `python3 scripts/run_stack_e2e.py --app <built> --admin <built>` exits 0 with the summary naming
    the assets `veyqctl` installed; `git grep -n "opt/veyq/deploy"` returns only ADR 0096's history sentence; guards
    green; the hand-off to cr-stack-e2e written; committed.

- [ ] **5 The publish creates the release.** In the `publish` job, release kind only, after the images and the chart
  are signed and the `v<major>.<minor>` ref exists: `release_assets.py` with the digests the job already resolved (it imports PyYAML, which the job installs as `guards`
  does),
  `cosign sign-blob` for `veyqctl` (the bundle is an asset), and `gh release create "v${VERSION}" --verify-tag` with
  every asset in the one call (an immutable release takes no asset after it is published), not a draft, not a
  prerelease. A workflow input `unscheduled_major` (boolean, default false) reaches the producer, refused unless the
  release changes the major. `validate_release_identity.py` gains the assertion: that step exists, is release-only,
  follows the tag and signing steps, names the producer's output directory, carries `--verify-tag`, and no step uploads
  a release asset later; assertion 3's "only a dispatch writes" covers `gh release` (it is a write).
  - **Bites:** the step's `if:` dropped → red; `--verify-tag` dropped → red; a later `gh release upload` → red; the
    step moved before the tag → red.
  - **Done when:** the four bites read; guards green, `validate_guard_gaps.py --force` included (this phase changes a
    guard); committed. Not provable further without a remote — say so in the Run pages' "how far this is proven".

- [ ] **6 The Run area, the reference and the state docs.** `infra/.env.example` gains `VEYQ_RELEASE` (and
  `run/configuration.md` its row — `validate_run_config_reference.py` is red until it does); the Compose half of
  `run/new-instance.mdx` (§ Install, § Worth knowing's in-image bullet), `run/upgrade.mdx` (the Compose tab's seven
  steps, § Rollback), `run/verify-a-release.md` (`veyqctl verify`, plus the manual commands it runs and the
  `cosign verify-blob` for `veyqctl` itself), `run/backup-and-restore.md` (§ Capture, § Restore, the fast path),
  `run/index.md` (§ The procedures), `run/plan.md` (§ Docker Compose or Kubernetes), `run/troubleshooting.md` (its two
  compose commands) — each keeps what the tool runs, so a step can be done by hand. Every command on those pages is
  one a phase 2/3 live run executed; a page command no run executed is either run now or marked unproven.
  `docs/features/admin/_index.md` (the two Upgrade entries become one), inbox #26 and #464.
  - **Done when:** `npm run check` in `site/` green; `python3 scripts/validate_run_config_reference.py` green; the
    pages' commands each traced to a live run's log; committed.

## Definition of done

`veyqctl` installs, upgrades, backs up and restores a Compose instance from a release in the mirror layout, proven by
the live runs of phases 2–4 and `scripts/veyqctl_selftest.py`; the stack lane boots through it; no image carries a
recipe; the publish declares the immutable release and the guard holds it to that. Coverage: the use-case stays
`planned` with its gaps (a host tool is not a surface; the file says what proves it). Published docs: the Run pages
of phase 6. **Baseline** — run on a detached worktree of `HEAD` before the first edit:
`for f in scripts/validate_*.py; do python3 "$f" >/dev/null 2>&1 || echo "RED $f"; done` (guard-gaps included, with
`--force`, because phases 1 and 5 change a guard). **Owner actions, outside this CR:** publish the repository, then
switch on immutable releases before the first release (ADR 0096 Consequences).

## Retrospective

**Phase 1.** Decided in the build, where the CR was silent — each is in `release_assets.py`'s docstring:
- *A recipe path with a directory in it is refused*, beside `../`: a GitHub Release asset is a flat name, so the
  mirror layout's `<tag>/<asset>` cannot carry `./conf/x.yml` under the name the compose file reads.
- *The producer counts the release number itself*, as `ci.yml`'s version step does (the CR's signature has no
  `--release`), and `validate_release_identity.py` assertion 2 holds the two counts to each other on its throwaway
  history (bitten: one commit more → red, the shallow refusal dropped → red).
- *An image reference is a digest or a local build's bare `name:tag`*; a release already in `<out>` is refused;
  `--unscheduled` is refused when an earlier `v<major>.*` is a git tag or listed in `<out>/releases.json`.
- *`releases.json` is `{"releases": [{tag, date, prerelease, manifest: "<tag>/manifest.json"}]}`*, `date` the UTC day
  of production, `prerelease` always false (nothing produces a prerelease yet). Phase 2's resolver reads exactly this.

The spelling bites (long-form bind, `env_file`) can only be red while the file they name is absent: the guard and
the producer share one derivation, so with the file present it is carried and green. Each was run both ways — red
on the derivation's message naming the spelling and its location, green with the file present and the OK line
listing it (the positive control). The guard's docstring states the shared derivation as its limit.
