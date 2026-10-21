---
id: cr-tools-under-src
title: The two operator tools a release ships move from scripts/ to src/tools/
status: planned
satisfies: ["uc.admin.install-and-upgrade-instance", "uc.admin.back-up-and-restore-instance"]
adrs: ["0106", "0096", "0098"]
invariants:
  - no behaviour changes; a release's assets keep their flat names, so an installed instance and `self-update` see nothing
  - both tools keep shipping alone — Python 3.9, the standard library, no import of anything under `scripts/`
  - every reader re-points before anything moves (changes/README.md § A move re-points its readers first)
---

## Goal

[ADR 0106](../docs/decisions/0106-deployment-under-infra-tests-under-tests.md) decision 8 (owner 2026-10-21): what a
release ships lives under `src/`, what the repository runs on itself under `scripts/`. So `veyqctl.py`,
`instance_backup.py` and their two selftests move to `src/tools/`, every reader follows, and `src/tools/CLAUDE.md`
names their check.

## Context & constraints

### Measured substrate (`main` at `9beb496c`)

The locator for every reader, run again at implement time:

    git grep -n -E 'veyqctl(_selftest)?\.py|instance_backup(_selftest)?\.py|scripts/veyqctl' -- . ':!changes/'

Its hits sorted by how each file consumes the path (changes/README.md § a move's locator sorts the files by how each
is consumed):

**Resolves the path at run time — red, or silently wrong, after the move:**

| Reader | Locator | What it does |
|---|---|---|
| the release producer | `scripts/release_assets.py:77` | `TOOLS = {…: ROOT / "scripts/veyqctl.py", …}` |
| the release-identity guard | `scripts/validate_release_identity.py:407`, `:1464` | reads `veyqctl.py`'s constants without importing it |
| the browser orchestrator | `scripts/run_web_e2e.py:772` | runs `instance_backup.py` for the restore group |
| the tools image | `infra/kubernetes/tools/Dockerfile:25` | `COPY scripts/instance_backup.py scripts/instance_backup_selftest.py /opt/veyq/` |
| CI | `.github/workflows/ci.yml:301`, `:306` | the two selftest steps (`:635` runs the copy inside the image — path unchanged) |
| the backup tool's own defaults | `scripts/instance_backup.py:221` | `REPO = Path(__file__).resolve().parent.parent` — one level too shallow from `src/tools/` |
| the backup selftest | `scripts/instance_backup_selftest.py:301`, `:304`, `:1534` | finds the checkout by `parent.parent`, then `import repo_layout` — with only its own directory on `sys.path` (`:73`), which from `src/tools/` does not hold that module |

`scripts/veyqctl.py` names no repository path (`SELF = Path(__file__)`, `:1677`), and `scripts/veyqctl_selftest.py`
imports only `veyqctl` from its own directory (`:86`); both move as they are.

**Checksummed:** four migrations under `src/backend/core/src/main/resources/db/migration/` name
`scripts/instance_backup.py` in a comment. The owner (2026-10-21): edit them — no release exists
(`scripts/applied_migrations.sha256`: `release: none`), so each changes together with its pin line in the same commit
(`src/backend/CLAUDE.md` § migrations).

**Prose — rewritten, except a measurement ledger** (ADR 0106 decision 4: a grep command with its `file:line` results
describes the tree it was taken on): the ADRs (0056, 0057, 0062, 0067, 0093, 0096, 0098, 0106), the coverage
comments under `docs/coverage/features/admin/`, `docs/features/`, the Run area (`site/src/content/docs/run/`),
backend KDoc and test comments, the web e2e test's comment, `.gitignore`, `.github/dependabot.yml`, the root
`CLAUDE.md`'s repository shape, the scripts' docstrings (and the two selftests' "Runnable locally" lines).

**Checked and unaffected:**
- Release assets are flat names; the Run area's commands fetch the asset `veyqctl`, never a repository path.
- No reader takes a tool from a predecessor's tree (the upgrade proof captures with this checkout's
  `instance_backup.py`, `run_web_e2e.py:772`), so ADR 0106 decision 7 has nothing to apply to.
- `trust-chain.yml`'s guard-gaps filter (`scripts/**`) stops seeing the tools, which is right: no forge names them
  (`grep -n "veyqctl\|instance_backup" scripts/validate_guard_gaps.py` prints nothing).
- Neither tool is in a mutation manifest or a coverage surface (ADR 0040).

**Baseline exit codes at `9beb496c`:** `scripts/veyqctl_selftest.py` 0, `scripts/instance_backup_selftest.py` 0,
`scripts/validate_release_identity.py` 0, `scripts/validate_migration_versions.py` 0.

## Decisions this CR takes

- **`scripts/repo_layout.py` names the tools** — `TOOLS_DIR`, `VEYQCTL`, `INSTANCE_BACKUP` — and every reader in
  `scripts/` takes them from there. They do not join `SURFACES`: that tuple exists for decision 7's predecessor
  lookup, which the tools do not need.
- **The backup selftest reaches `repo_layout` only inside a checkout**: its checkout branch puts the repository's
  `scripts/` on `sys.path` before the import; the image branch (no `.git`) still skips, as today.
- **`src/tools/CLAUDE.md`** is short: what lives here, the ships-alone constraint (today only in the tools' own
  docstrings) and the check — the two selftests. The root `CLAUDE.md` gains the directory under § Repository shape and
  § Prove your work, and its `scripts/` line drops `veyqctl` and the backup tool.
- **No launcher task.** The launcher triggers what a surface already has; two `python3` lines need no Gradle wrapper.

## Phases

### Phase 1 — the readers take the path from `repo_layout`, on today's layout

`repo_layout.py` gains the three names, pointing at `scripts/`. `release_assets.py`, `validate_release_identity.py`
and `run_web_e2e.py` read them instead of spelling the path. Nothing moves.

**Done when:** `validate_release_identity.py` and both selftests exit 0, and `python3 scripts/release_assets.py <scratch>
--app veyq:local --admin veyq-admin:local` writes `veyqctl` and `instance_backup.py` byte-identical to the sources
(exit 0 at `9beb496c`, eight assets). **Bite:** with `repo_layout.VEYQCTL` pointed at a missing file, `validate_release_identity.py`
is red — proving it reads the name rather than a leftover literal; restored by snapshot, never `git checkout`.
Committed before phase 2.

### Phase 2 — the move

1. `git status --short --ignored > <scratch>/before.txt`.
2. `git mv` the four files to `src/tools/`; `repo_layout.TOOLS_DIR` becomes `SRC / "tools"`.
3. **Guard → red → fix → green** on the live depth defect: run `python3 src/tools/instance_backup_selftest.py` with
   `REPO` still at `parent.parent` and the `sys.path` fix in place — `layout_cases` must go red naming the two
   defaults; then set `REPO` to `parents[2]` and see it green.
4. The Dockerfile's `COPY`, the two `ci.yml` steps, the selftests' "Runnable locally" lines.
5. `git status --short --ignored` diffed against `before.txt`: the four renames and nothing else new.

**Done when:** both selftests exit 0 from `src/tools/` (run from the repository root and from `/tmp`, so neither
relies on the working directory); `validate_release_identity.py` exits 0; the tools image builds
(`docker build -f infra/kubernetes/tools/Dockerfile .`) and `ci.yml:635`'s command exits 0 against it, which takes the
image branch's skip; the producer run of phase 1 again writes both tools. Committed.

### Phase 3 — prose, migrations, the ADR

Every prose hit of the locator, rewritten or left as a ledger (each kept ledger line listed in the retrospective);
the four migration comments with their pin lines; `src/tools/CLAUDE.md` and the root `CLAUDE.md`; ADR 0106's Status
set to built. Run-area pages touched: `run/backup-and-restore.md` and `run/upgrade.mdx`, only where they name a
selftest's path — no operator step changes.

**Done when:** `for f in scripts/validate_*.py; do python3 "$f"; done` green, `validate_guard_gaps.py --force` green
(phase 1 changed a validator), `./gradlew check` in `src/backend/` green (the migrations changed; a dev database
that refuses to start after this is recreated, `src/backend/CLAUDE.md` § migrations). The locator's
`scripts/(veyqctl|instance_backup)` hits are only the listed ledgers. Committed. The two-build upgrade proof
(`run_web_e2e.py --upgrade`) is not run here: against a predecessor before this commit it is red by construction (an
edited migration), and it is one of the heavy suites that run once at V1's end.

## Definition of done

The three phases committed, the trust-chain glob green, ADR 0106 says stage 3 is built, and this CR is deleted with
anything it taught moved to where it binds.

## Retrospective
