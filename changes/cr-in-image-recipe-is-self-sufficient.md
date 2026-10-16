---
id: cr-in-image-recipe-is-self-sufficient
title: The recipe in the image carries every file it reads — garage.toml joins /opt/veyq/deploy/
status: planned
satisfies: []
adrs: ["0057", "0090", "0064", "0067"]
invariants:
  - "Self-hostable by default: an operator deploys a verified release from the image alone, with no repository checkout behind it"
  - "ADR 0057 §3: what is deployed is read out of the verified digest, so it inherits that digest's cosign signature — a file taken from anywhere else has no trust path"
---

## Goal

The app image carries its deployment recipe at `/opt/veyq/deploy/` ([ADR 0057](../docs/decisions/0057-release-identity-and-deployment-recipe.md)
§3), but the recipe is only the compose file and the env template — and the compose file bind-mounts
`./garage.toml`, which the image does not carry. So a recipe extracted from a verified image cannot start without a
file from the repository, and that file is outside the signature the whole design exists to give it. This CR puts
`infra/garage.toml` into `/opt/veyq/deploy/` beside the compose file, turns the guard's fixed two-file list into
"every file the production compose file reads beside itself", and removes the stated limit from the Run pages, ADR
0057, inbox #26, and the copy-from-checkout in `scripts/run_stack_e2e.py`. Found by
[`cr-stack-e2e`](cr-stack-e2e.md) phase 1 (2026-10-16), whose run has to copy the file in and says so on every run.

Implementation waits for the owner's go on this CR.

## Context & constraints

- **The defect, measured on `main` (`b84aa02e`):**
  - `grep -n "^COPY infra/" Dockerfile` → only `docker-compose.prod.yml` and `.env.example` go to `/opt/veyq/deploy/`.
  - `grep -n '\./' infra/docker-compose.prod.yml` → one relative read, `./garage.toml:/etc/garage.toml:ro` on `garage`.
  - Second angle, keyed on what Compose itself resolves rather than on a grep:
    `docker compose -f infra/docker-compose.prod.yml config --no-interpolate --format json` → its only `type: bind`
    volume is `garage`'s `…/infra/garage.toml`; no `env_file`, `configs`, `secrets`, `include` or `extends`. The two
    agree, so `garage.toml` is the **whole** residual — as inbox #26 already says (narrowed 2026-09-10 by ADR 0067).
  - `grep -n "garage.toml\|two files" site/src/content/docs/run/new-instance.mdx` → the stated limit (§ Install's
    "current limit" note).
- **The guard that anchors on the recipe** (`grep -ln "garage\|deploy/\|docker-compose.prod\|env.example" scripts/*.py`,
  each read): only `validate_release_identity.py` assertion 4 anchors on the recipe's **contents** — the literal
  tuple `RECIPE = ("infra/docker-compose.prod.yml", "infra/.env.example")` (`grep -n "^RECIPE" scripts/validate_release_identity.py`).
  The others read the compose file for other properties (`validate_compose_posture.py`, `validate_image_pins.py`,
  `validate_app_volume_ownership.py`, `validate_run_config_reference.py` reads `.env.example`'s variables) and are
  unaffected by a third COPY; `validate_guard_gaps.py` pins no gap of assertion 4
  (`grep -n "release_identity" scripts/validate_guard_gaps.py` → nothing). A literal list is exactly what let this
  slip: the compose file gained a relative read and nothing compared the two. So the set is **derived from the
  compose file** (§ *a guard derives its anchor set from the code*), and the next relative read added without a COPY
  is red.
- **`garage.toml` is safe to ship:** it holds no secret by design — `rpc_secret` and `admin_token` come from the
  environment (the file's own header, `sed -n 1,7p infra/garage.toml`). It is the same file the dev stack mounts
  (`grep -n garage.toml infra/docker-compose.yml`), and it stays in `infra/` for both; the image takes a copy at build
  time. `.dockerignore` does not exclude it (`grep -n "infra\|toml" .dockerignore` → only `infra/backups`).
- **The chart is not touched beyond a comment.** It carries its own `ConfigMap` copy
  (`charts/veyq/templates/garage-configmap.yaml`), deliberately different in `rpc_public_addr`; a Helm chart cannot
  read a file outside its own directory, so the image's copy does not serve it. Its header comment says the compose
  recipe bind-mounts "the repository's copy" — after this CR that is the recipe's copy.
- **`scripts/run_stack_e2e.py` exists only on `claude/cr-stack-e2e-phase-1-08272e`**, not on `main`
  (`git merge-base --is-ancestor claude/cr-stack-e2e-phase-1-08272e main || echo not-merged`). Phase 2 here edits it,
  so phase 2's **entry condition** is that branch merged. Phase 1 does not need it. The finding is recorded on that
  branch at `changes/cr-stack-e2e.md` ("The extracted recipe is not self-sufficient … closing it is not this CR's"):
  it is a sibling CR in flight and is cited as planned, never edited from here.
- **Rejected alternatives**, so the go is a choice and not a default:
  - *Inline the config into the compose file* (top-level `configs: garage: content: |`). The compose file alone would
    be self-sufficient, but it needs Compose ≥ 2.23.1 — remembered, not measured — and it makes a **third** copy of
    the config beside `infra/garage.toml` (still needed by the dev stack) and the chart's, with nothing holding them
    together. Shipping the file keeps one source for both compose stacks.
  - *A veyq-built Garage image with the config baked in.* A fourth published artifact to sign, pin and patch, for one
    20-line file — and the recipe would stop running the upstream image the Run pages verify by digest.
  - *Leave it and document it.* That is today's state: the extracted recipe needs a checkout, which is a trust path the
    signature does not cover, and an operator without the repository cannot start the store at all.
- **The Run pages copy the recipe by directory, not by file list.** `verify-a-release.md` already extracts the whole
  directory (`docker cp veyq-recipe:/opt/veyq/deploy/. …`, `grep -n "docker cp" site/src/content/docs/run/verify-a-release.md`),
  so it needs no change. `new-instance.mdx` and `upgrade.mdx` copy and diff **two named files**
  (`grep -n "recipe-<" site/src/content/docs/run/new-instance.mdx site/src/content/docs/run/upgrade.mdx`) — a hand
  list of exactly the kind that drifted here. They change to the directory form, so a fourth file later needs no page
  edit. **Owner decision point:** the alternative is naming the three files on each page — clearer to read, and it
  goes stale the same way again; this CR recommends the directory form.
- **Machine hints, for the CR's planned shape:** the app image builds in about 5½ minutes from a warm cache
  (measured by cr-stack-e2e phase 1, its hand-over note); build it in the background. The stack run needs host ports
  80, 443 and 18081 free and PyYAML. Re-derive these against what is built.

### Inventory — the locators

| What | Locator |
|---|---|
| Files the production recipe reads beside itself | `grep -n '\./' infra/docker-compose.prod.yml` and the `docker compose … config --no-interpolate --format json` angle above |
| What the image copies into the recipe dir | `grep -n "/opt/veyq/deploy" Dockerfile` |
| Guards anchoring on the recipe | `grep -ln "garage\|deploy/\|docker-compose.prod\|env.example" scripts/*.py` |
| Prose that states the recipe's contents or the limit | `git grep -n -i "garage.toml\|env template\|two files\|opt/veyq/deploy" -- Dockerfile docs/decisions site/src/content/docs charts infra scripts` |
| Run pages that copy or diff recipe files | `git grep -n "recipe-<\|recipe-\${" -- site/src/content/docs` |
| Pages that restate ADR 0057 (they move with §3) | `git grep -ln "0057-release-identity" -- site/src/content/docs` |

## Phases

- [ ] **1 guard → red → Dockerfile → green, then the docs.** The defect is live, so the order is guard first
  (§ *where the defect is still live*):
  - **Guard.** `validate_release_identity.py` assertion 4: the recipe set is `infra/docker-compose.prod.yml`,
    `infra/.env.example`, and every file the production compose file reads relative to its own directory. Enumerate
    the format's spellings, not the one in front of you: `services.*.volumes` short form (`./…` / `../…` sources)
    and long form (`type: bind`, `source:`), `services.*.env_file` (string, list, `{path:}`),
    `services.*.label_file` (remembered as a newer Compose key — check the spec at implement time), top-level `configs.*.file` and `secrets.*.file`. A relative path containing `${…}`
    (unresolvable statically), one that escapes the directory (`../`), and any `include:` or `services.*.extends`
    are **refused** with a reason rather than followed — the recipe has none today (measured above), and supporting
    one is a decision for when it is needed. Absolute host paths (`/…`, `~`) are the host's, not the recipe's: out of
    scope, and the docstring's limit says so. Every derived file must be COPYed in every runtime target **to the
    compose file's destination directory at its relative path**, so `docker cp …/deploy/.` yields a directory that
    runs as it is. The existing "same destination in every target" and forbidden-destination checks stay. The OK
    line **prints the derived file list**, not a count, so a run that derived nothing reads differently from one
    that held. Docstring: assertion 4 rewritten to the derived rule; the limit gains "absolute host paths are not
    checked"; the buildless limit stays as it is (a declared COPY is not a file in an image — phase 2's run is that
    proof). Run it on the untouched Dockerfile: **red, naming `infra/garage.toml`.**
  - **Dockerfile.** `COPY infra/garage.toml /opt/veyq/deploy/garage.toml` beside the other two; the block's comment
    names the recipe as "the compose file, the env template and every file the compose file mounts beside itself",
    and its `Extract with:` line becomes the directory form `verify-a-release.md` uses. Guard **green**.
  - **Bites**, each on a detached worktree of `HEAD` (`git worktree add --detach <dir> HEAD`), by snapshot-and-restore,
    each asserted red with its message read:
    1. *live defect* — the untouched Dockerfile (above);
    2. *injection* — the new COPY moved to `/opt/veyq/conf/garage.toml` (not beside the compose file);
    3. *injection* — a long-form `type: bind`, `source: ./extra.yml` added to `traefik` in the production compose file;
    4. *injection* — `env_file: ./extra.env` added to `app`;
    5. *injection* — `garage`'s mount rewritten as `"${GARAGE_CONF:-./garage.toml}:/etc/garage.toml:ro"`;
    6. *injection* — a top-level `include: [./extra.yml]`.
    Bites 3–6 must fail on the **derivation**, not on a missing file on disk — the message names the spelling.
  - **Docs, same phase** (§ *the published docs move with the change*):
    `docs/decisions/0057-release-identity-and-deployment-recipe.md` §3 — the recipe is the compose file, the env
    template and every file the compose file reads beside itself (edit the decision text; no amendment line,
    `docs/ai/README.md` § docs state what is). `site/src/content/docs/run/new-instance.mdx` — the "ride inside the
    image" bullet names the store config, the "current limit" note goes, and the install block copies the directory
    (`cp -R ./recipe-<version>/. infra/`). `site/src/content/docs/run/upgrade.mdx` — step 5 diffs every file of the
    new recipe (`for f in $(ls -A ./recipe-<new-version>); do diff -u "infra/$f" "./recipe-<new-version>/$f"; done`),
    step 6 copies the directory. `charts/veyq/templates/garage-configmap.yaml` header comment. `docs/inbox.md` #26 —
    the "Still open: … `garage.toml` still comes from the repo" clause becomes closed with this CR's date.
    `verify-a-release.md` and `security/verify-it-yourself.md` need no change (they already speak of "the recipe";
    re-read both against the locator, and say so).
  - **Live, without the stack:** build the app image (`docker build --target runtime-jvm -t veyq:recipe-check .`, in
    the background), extract exactly as `verify-a-release.md` does into an empty directory, and list it: three files,
    `garage.toml` byte-identical to `infra/garage.toml` (`cmp`).
  - **Done when:** the six bites red with their messages read, then green; `python3 scripts/validate_release_identity.py`
    and `python3 scripts/validate_changes.py` green; the extracted directory lists the three files and `cmp` is
    silent; `npm run check` in `site/` green; committed.

- [ ] **2 the stack run reads the recipe whole.** **Entry condition:** `run_stack_e2e.py` is on `main`
  (`git ls-files scripts/run_stack_e2e.py` prints it). In `extract()`: copy `/opt/veyq/deploy/.` as a directory
  (one `copy_out` entry onto the empty `recipe/`), drop `GARAGE_TOML` and `RECIPE_FILES`, and print the extracted
  file list instead of the checkout caveat; the module docstring's step 1 loses its "one file still comes from the
  checkout" sentence. Then `ADR 0090`'s "the only file laid over the recipe is the overlay" holds without an
  exception. Re-read `changes/cr-stack-e2e.md` (if still live) for the finding it recorded and leave it to that CR's
  owner session to strike — name it in the hand-off.
  - **Bite:** run once against an image built **without** phase 1's COPY (`git worktree add --detach <dir> <phase-1 parent>`,
    build from there) — the run must go red at `up` (remembered, not measured: Compose creates a missing bind source as an
    empty directory, and Garage cannot read a directory as its config — record what it actually says), proving the run no longer papers
    over the defect.
  - **Done when:** `python3 scripts/run_stack_e2e.py --app <image built with phase 1> --admin <admin image>` exits 0
    with the summary listing `garage.toml` from the image; the bite run is red; guards from phase 1 still green.

## Definition of done

`python3 scripts/validate_release_identity.py` green with its new derivation bitten six ways; the extracted recipe of
a built image holds `garage.toml` and runs the stack without a checkout (phase 2's green `run_stack_e2e.py` run and
its red bite); the Run pages, ADR 0057 §3, the chart comment and inbox #26 no longer state the limit. Baseline,
measured at refine on `b84aa02e` (re-run on a detached worktree of `HEAD` before the first edit):
`python3 scripts/validate_release_identity.py; echo rc=$?` → `rc=0`; `python3 scripts/validate_changes.py` → OK.
The whole glob (`for f in scripts/validate_*.py; do python3 "$f"; done`) runs once over the merged tree before the
CR is called done. **Coverage:** none — `satisfies: []`, because no use-case acceptance criterion names the recipe's
contents (`grep -n -i "recipe" docs/features/admin/use-cases/*.md` → only ADR links); the invariant is held by the
guard instead. **Published docs:** `run/new-instance.mdx`, `run/upgrade.mdx`; `run/verify-a-release.md` and
`security/verify-it-yourself.md` checked and unchanged.

## Retrospective
