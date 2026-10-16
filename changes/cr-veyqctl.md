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

- [x] **2 `veyqctl` installs, runs and verifies.** `scripts/veyqctl.py` (the asset is `veyqctl`): Python 3.9, stdlib,
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

- [x] **3 `upgrade`, `backup`, `restore`, `self-update`.** `upgrade` exactly as ADR 0096 decision 5 orders it, nothing
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

- [x] **3b A major is entered from its predecessor's last release** (owner 2026-10-16, ADR 0096 decision 5 step 1,
  decided after phase 3 landed). `upgrade`'s resolution: if the selected release lies in a later major than the
  running one and the running release is not the newest of its major, the target becomes that newest release, and
  the run ends by saying the next `upgrade` enters the next major; a run never crosses more than one major (from the
  last of major N, `latest` or a pin in N+2 goes to the newest of N+1). A pin inside the next major is reached
  directly once the running release is its predecessor's last. `--help` and the `VEYQ_RELEASE` messages stop saying
  "crossing majors" plainly. Pure resolution, no Docker needed.
  - **Bites (selftest):** the rule removed → a run from 1.3 with `latest` at 2.5 (1.9 newest of major 1) resolves
    2.5, red; the one-major cap removed → from 1.9 with 3.1 listed resolves 3.1, red; control: within a major, 1.3 →
    1.9 directly, green.
  - **Done when:** the bites read; `veyqctl_selftest.py` and every guard green; committed.

- [x] **4 The recipe leaves the image; the stack lane boots through `veyqctl`.** Re-read `scripts/run_stack_e2e.py` on
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

**Phase 2.** Decided in the build, each in `veyqctl.py`'s docstring:
- *The manifest lists every asset with its sha256* (`release_assets.py` writes it): an HTTP mirror cannot be listed,
  so the list is what `veyqctl` fetches, and each file is checked before it lands in `releases/<release>/`, the
  manifest kept there verbatim. It binds the files to the manifest, not the manifest to veyq — the images' signatures
  do that, and for the recipe only the source does (an immutable GitHub Release, or a mirror the operator trusts).
- *The project is named once.* `install` reads it from `docker compose config` (the recipe's `name:`, or a
  `COMPOSE_PROJECT_NAME` in `.env`, which beats it), records it in `state.json`, and every later command passes `-p`;
  a `COMPOSE_*` in the operator's shell is dropped and every command runs in the home. The live run set the project
  through `--set COMPOSE_PROJECT_NAME=…`, so the overlay stayed a verbatim copy.
- *Assertion 7 reads `veyqctl`'s `COSIGN_OIDC_ISSUER` and `COSIGN_IDENTITY_REGEXP`* (by AST, without importing it),
  holds them to the page's one identity and to the same accepts and rejects; the self-test holds the `cosign verify`
  call to exactly those two. Bitten by snapshot-and-restore, each red on its own message, the untouched tree green:
  the `^` dropped, `master` added, another issuer, the constant renamed away.
- *Not done here: the self-test is not in `ci.yml`.* The implementing run's brief kept the workflows off limits
  (cr-one-pipeline-per-commit edits `ci.yml` in parallel); the step belongs beside `instance_backup_selftest.py`'s.
- *Live*, measured (log in the implementing session, not committed): `veyq:ctl-p2` and `veyq-admin:ctl-p2` built from
  the tree; `release_assets.py` → `v0.528`, six assets, the three recipe files byte-identical to `infra/`; the stack
  lane's own `pebble()` under the run's project first (Pebble's root must be in the app's truststore before the app
  starts — `install` brings everything up at once, so phase 4 meets the same ordering); `veyqctl install
  --unsigned-local` with every line prefixed `NOTHING VERIFIED`, `.env` and `state.json` `0600`, every service
  healthy, the stack lane's certificate check green through Traefik (`GET https://veyq.test/api/setup` → 200);
  `stop` → `status` exit 1, `start` → healthy again; `verify` without the flag refused the local build; teardown by
  project name, the host's 5 containers and 4 volumes unchanged.
- **Next time:** a Done-when the brief overrides (here `ci.yml`) is named in the brief as overridden, so the
  implementer does not have to find the contradiction.

**Phase 3.** Decided in the build, each in `veyqctl.py`'s docstring:
- *Step 3 refuses every key the new template assigns and `.env` lacks*, not only a `${…:?}` the recipe names: the
  template's assigned keys are its required ones (its optional ones are comments), so taking a template's example
  value for one would boot on a placeholder. The filled result is staged as `.env.next` and `docker compose config`
  checks it before anything stops. A selector BELOW the running release is refused: there is no downgrade.
- *Step 4's "back up; stop the app" is one act*: the capture is cold (ADR 0056 §2), so the app stops for it and stays
  stopped through the switch; a failed capture switches nothing and starts the running release again.
- *"Import" is read as "run the release's own copy"*: `instance_backup.py` runs as a child process from
  `releases/<running>/`, checked against its manifest's sha256 first, so its `umask 077` and environment stay its own.
- *Leg (c) needs no root and no readable Docker data root*: it leaves the stopped app container by `docker cp` and a
  restored key goes back through a one-off `app` container as the image's user (born `10001:10001 0600`) — the route
  `instance_backup.py`'s own refusal already names. The Run page's mountpoint-and-`sudo -E` form still works by hand;
  phase 6 rewrites the page around this.
- *`restore --domain` is required*, found live: defaulting it to `.env`'s `VEYQ_DOMAIN` was refused by the tool's gate
  (no write) — a capture holds its TENANTS' domains, and `VEYQ_DOMAIN` is the bootstrap one.
- *Open for phase 5*: `self-update` checks the installed `veyqctl` against its release's manifest only; ADR 0096
  decision 3 signs it with `cosign sign-blob`, and phase 5's text does not say `self-update` then verifies that bundle.
- *Live*, measured (log in the implementing session, not committed). Release A `v0.529` from `bc23009b` (phase 2), B
  `v0.530` from this phase's head; B's images came out of the build cache identical to A's — nothing in the image
  changed between the two commits, so the run proves `veyqctl`'s switch, not a schema migration. A installed by A's
  own `veyqctl` (pinned), seeded by the stack lane's whole browser suite (10 tests: first run, a Memory with a photo),
  `VEYQ_RELEASE` moved from the pin to `0`, then `upgrade` with B's `veyqctl`: every service healthy,
  `backups/<stamp>-before-0.530/` with `database.sql`, `objects/` (4) and a manifest saying leg (c) `separate`,
  `verify` OK, `releases/0.529/` kept, `.env` on B's image lines; the Memory then opened on a fresh device (a scratch
  spec, not committed). `self-update` of a locally edited copy left it `cmp`-identical to B's with its mode kept, and
  found nothing to do a second time. The upgrade's backup restored onto a fresh install of B: 46 tables dropped,
  dump and 4 objects loaded, the key in the app's volume `10001:10001 600` with the backup key's sha256, and the
  Memory opened there too. Teardown by project name, nothing left, the host's containers and volumes unchanged.
- *Bites*, each against the seeded instance with every container's `StartedAt` compared: (c) `--key-into` omitted →
  refused, unchanged; (b) B produced with digest references, the app digest one hex digit off → refused at `cosign
  verify` (`no signatures found`), no pull among the commands it printed, unchanged, no `releases/0.530/`; (a) B'
  produced from the tree with `VEYQ_BITE_REQUIRED=` in the template and `${VEYQ_BITE_REQUIRED:?…}` in the recipe
  (snapshot-and-restore of `infra/`, byte-identical after) → refused naming it, unchanged, `backups/` empty, `.env`
  and `state.json` byte-identical. Bite (b)'s limit: nothing is published, so the unaltered digest is refused too —
  its positive control is the self-test's stand-in host; and its "local images unchanged" sensor read CHANGED, because
  another session builds images on this host, so the printed command list is the evidence there. The self-test's new
  checks were bitten by six mutants of a copy of `veyqctl.py` (stop before the template check, a template example taken,
  `--key-into` checked late, no restart after a failed backup, `.env`'s selector ignored, the key in an argv): each
  red, the unmutated copy green.
- *What went wrong*: the first live run called `self-update` on release A's `veyqctl`, which predates the command (a
  harness error), and defaulted the restore's domain wrongly (a product bug, fixed above); the whole run was repeated
  at the fixed head. **Next time:** a live proof that mixes two releases' tools names, per step, which release's tool
  runs it — the CR's "install the first … `upgrade` to the second" leaves that to the harness.

**Phase 3b.** Decided in the build, each in `veyqctl.py`'s docstring:
- *The rule sits on top of `select`, not in it* (`upgrade_target`): `select` stays what `VEYQ_RELEASE` selects, and
  `install` (no running release) and `verify` keep using it alone; `upgrade` and `check` apply the rule, so `check`
  names the release the next `upgrade` really takes and says, as `upgrade` ends, which major the one after enters.
- *A next major with no release is refused*, naming it: the ADR's "no run crosses more than one major" read
  literally — a mirror lacking major N+1, or one whose N+1 is only a prerelease, would otherwise be crossed in one run.
  The use-case names no such flow; with releases cut from one `VERSION` it should not arise.
- *Bites*, as phase 3's: mutants of a copy of `veyqctl.py`, the copy's self-test run against each (script in the
  implementing session, not committed). The rule removed → red, 8 checks (`latest` from 1.3 with 2.5 listed and 1.9
  the newest of major 1 resolves 2.5, plus the pin, `2`, the end-to-end `upgrade` and `check`); the one-major cap
  removed → red, 3 (from 1.9 with 3.1 listed resolves 3.1; the empty-major refusal); further `upgrade` or `check`
  ignoring the rule, and a prerelease counted as a major's newest → each red on its own checks. The control (1.3 → 1.9
  within major 1) green in every mutant, and the unmutated copy green.
- *What went wrong*: nothing in the build. Phase 3's self-test pinned the superseded reading (`latest` and `2` from 1.3
  straight to 2.1); both went red at the first run after the rule, as they should, and now pin 1.12.

**Phase 4.** Decided in the build, each in `run_stack_e2e.py`'s docstring:
- *Pebble before `install`, without teaching `veyqctl` about it.* The lane's stages are now `assets` →
  `pebble` → `install` → (cr-stack-e2e's) `certificate` …: `pebble` brings Pebble up under the run's project from
  the release's own recipe (`src/<tag>/`) and the repository's overlay, with a `pebble.env` holding the run's values
  and a placeholder for each secret and image line `install` writes later (Compose interpolates the whole model to
  start one service); `install`'s `up` then finds Pebble `Running`, and the stage is red unless Pebble's container
  id is the one `pebble` left — a recreated Pebble issues under a root the app does not trust.
- *The pebble stage reads the overlay from the tree, `install` reads the home's copy* — which is what makes the
  CR's bite land on `certificate` and not on `pebble`.
- *The lane takes local `name:tag` builds only*: `--unsigned-local` refuses a registry reference, so a CI lane
  that pulls `build-<sha>` by digest re-tags it first (hand-off below). `install` runs with stdin closed, so a key
  the template gains is refused by name, not asked for; the run's template values (`VEYQ_DOMAIN`,
  `VEYQ_ACME_EMAIL`) are held to the release's `.env.example` at `assets`, as `write_env` held them.
- *Made false by this phase and fixed here, beyond the CR's list:* ADR 0064 (three sentences), the Security area's
  *Verify it yourself* (its release paragraph), inbox #466, the overlay's and `.gitignore`'s headers, ADR 0096's
  status line, `veyqctl`'s "what proves it". Carried over from phases 2–3: `veyqctl_selftest.py` runs in `ci.yml`
  beside the backup self-test (0.5 s, the job's 15 minutes unchanged, `validate_pipeline_shape.py` green);
  `ReleaseFeed`'s manifest comment names `assets`.
- *Live*, measured (logs in the implementing session, run directories under `.e2e-stack-out/`, not committed):
  images built from the tree (`veyq:ctl-p4`, `veyq-admin:ctl-p4`, no `/opt/veyq` in the app image), then
  `run_stack_e2e.py` GREEN, twice — at the edit and at the committed head (`v0.542`, 44.6 s of stages: `install`
  16.7 s, `browser` 23.0 s with 10 tests executed, the sweeps green on database, store, container logs and statement
  log, the host's 5 containers and 4 volumes unchanged); the summary names release, home and the five assets.
- *Bite*: the overlay dropped from the override file (the copy line replaced, snapshot-and-restore, `cmp`-identical
  after) → `install` green, then RED at `certificate` after 121 s, `:443 serves CN=TRAEFIK DEFAULT CERT`; window,
  browser and sweep skipped; teardown and host clean. Its positive control is the green run on the same code.
- *What went wrong*: the CR contradicts itself — phase 4's done-when wants `git grep "opt/veyq/deploy"` down to ADR
  0096, while phase 6 owns the two Run pages that still carry it (`run/verify-a-release.md`'s `docker cp`,
  `run/new-instance.mdx`'s in-image bullet) and inbox #26; left to phase 6, so the grep also lists those three and
  `changes/` (this CR, and cr-one-pipeline-per-commit's phase text, a sibling's). Until phase 6 lands,
  `verify-a-release.md` tells an operator to copy a directory no image built from `main` has — nothing is published,
  so nobody can follow it. The final run started at a load of 16.8, above the brief's limit of 14 (not checked
  before starting); it ran green. **Next time:** a phase that removes a thing greps the *published* pages for it at
  refine and assigns each hit to a phase, so no done-when and phase list disagree.

**Hand-off to cr-stack-e2e** (its owner session; this CR does not edit its file): `run_stack_e2e.py` no longer
extracts anything. `extract()`, `write_env()`, `up()`, `compose_cmd()` and the `extract`/`env`/`up` stages are gone;
the stages before `certificate` are `assets` (`release_assets.py` into `<run>/src`), `pebble` (now takes the
release and the run's values, copies the minica root itself) and `install` (`veyqctl install --unsigned-local`,
`<run>/home`). Everything after reads `Installed.compose` — `docker compose -p <project> -f
home/releases/<release>/docker-compose.prod.yml -f home/compose.override.yml --env-file home/.env` — and the sweeps
read `home/.env`. `--app`/`--admin` must be local `name:tag` builds: a CI lane re-tags the pulled digest first.
`VEYQ_STACK_WAIT_SECS` now reaches `veyqctl` as `VEYQ_WAIT_SECS`. Now false in `changes/cr-stack-e2e.md`: the Goal's
parenthesis "until `cr-veyqctl` phase 4 lands, the recipe is extracted from the app image as phase 1 built it"; the
Context bullet's "Everything after `up` — Pebble, …" (there is no `up` stage; Pebble runs before `install` and is
handed the release); and phase 1's ticked text where it describes extraction, `garage.toml` from the checkout and
the `.env` the script writes (history of a done phase, its owner's to keep or cut).
