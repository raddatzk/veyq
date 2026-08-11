---
id: cr-release-identity
title: A release names itself from the tree, and carries its own deployment recipe
status: planned
satisfies: [uc.admin.identify-instance-release]
adrs: ["0057", "0056", "0032", "0040"]
invariants: [operator-blind-e2ee, self-hostable-by-default]
---

## Goal

veyq cannot currently name a release. `.github/workflows/container.yml` computes its version by reading a
`VERSION` file that does not exist, so the job dies at its first command and **no image has ever been
published** — while `infra/.env.example` instructs the operator to pin `VEYQ_IMAGE` to one. Downstream of that
absence, three artifacts already state things nothing performs: the workflow derives its version from the
*registry* (so a transient API error republishes an existing tag under its own name), no git tag corresponds to
an image tag (so the deployment files belonging to a version cannot be found), and every backup manifest records
`app.version: null` because it reads the variable from the capture process's host environment.

This CR gives a release an identity that comes from the tree, ties exactly one git ref to it, ships the
production deployment recipe **inside** the signed image so it cannot drift from the bits it describes, and makes
the Server Owner able to read which release is running. It is the precondition of the upgrade procedure, and
deliberately not the upgrade procedure: an upgrade whose rollback target cannot be named is advice, not a
procedure.

## Context & constraints

- **The decision:** [ADR 0057](../docs/decisions/0057-release-identity-and-deployment-recipe.md) — version from
  the tree, one `v<major>.<minor>` ref per release, the recipe travels in the image, and **two version notions
  that are never merged**.
- **The use-case:** [uc.admin.identify-instance-release](../docs/features/admin/use-cases/uc.admin.identify-instance-release.md)
  — its A1 (an unstamped build says so) and A2 (nobody but the Server Owner reads it) are the two that are easy
  to get wrong.
- **What must not move:** [ADR 0032](../docs/decisions/0032-capability-discovery-version-gating.md)'s pre-auth
  `version: { major, build }`. `major` stays the hard client gate; `build` stays a hand-maintained diagnostic
  that gates nothing. The only change to that surface is **one description sentence** that currently claims a
  bump on every additive change nothing performs.
- **Where this came from:** this is `docs/inbox.md` **#26 slice 2** (*"versioning: decided in principle, not
  written"*), now written. That row also records the cause — `VERSION` was deleted by a cleanup commit that also
  took `Dockerfile` and `scripts/run_web_e2e.py`; those two were recovered and this one never was. It is **not**
  recoverable here: `git cat-file -t 5432372d` → *not a valid object name*, because this repository's history
  begins at its import. The file is decided fresh, not restored.
- **Invariants at risk:** *self-hostable by default*, in the specific form ADR 0026 gives it — an unsigned
  artifact in the install path is a client-integrity regression, which is why the recipe rides the signed image
  rather than a raw URL. Operator-blindness is untouched: a release version is a property of the software, and
  it ends up disclosed to **fewer** parties than the capability manifest already discloses the feature set to.
- **Out of scope, named so it is not drifted into:** the `curl`-able bootstrap, the upgrade tool, and any
  `start`/`stop`/`backup` wrapper. Those are the next CR (`docs/inbox.md` #121), and they are blocked on this
  one — not the other way round.

## Locators — run these, read them as lists

The command is what ships; the numbers below are for deciding scope **here**, in this conversation, and have no
later reader.

**1. The absence itself** — the claim the whole CR rests on:

```sh
git ls-files | grep -ix VERSION            # → empty
git tag                                    # → empty
sed -n '63p' .github/workflows/container.yml
```

**2. Every site that names a version** — the blast radius, from the *value* side:

```sh
grep -rn "VEYQ_VERSION\|VEYQ_IMAGE\|veyq\.version\.\|version\.build\|version\.major" \
  --exclude-dir=.git --exclude-dir=node_modules --exclude-dir=build --exclude-dir=dist --exclude-dir=generated .
```

Run at refine time it returns, among the docs: `Dockerfile:64,66,112,114` (both runtime targets),
`infra/docker-compose.prod.yml:87`, `infra/.env.example:25` (whose example tag `…:1.0.0` is a **three-part shape
this pipeline never emits**), `infra/README.md:132`, `backend/src/main/resources/application.properties:21-22`,
`web/src/shared/state/useCapabilities.ts:35-36`,
`backend/src/test/.../CapabilityManifestSliceTest.kt:33-34`, `scripts/instance_backup.py:442`,
`.github/workflows/container.yml:209`, and `scripts/validate_config_secret_persistence.py:180`.

**3. The second angle, keyed on the guard rather than the value.** The list above answers *"who mentions a
version"*; it cannot answer *"who breaks if the Dockerfile or the prod compose is reshaped"*, which is the
actual risk of phase 2:

```sh
grep -rn "Dockerfile\|docker-compose.prod\|runtime-\|WORKDIR" scripts/validate_*.py
```

`scripts/validate_config_secret_persistence.py` identifies the app service partly by
`"VEYQ_IMAGE" in service.image` and holds the `Dockerfile`'s `WORKDIR`, `VeyqConfig`'s default and the compose
mount to **one path**. Phase 2 adds files to the image and must leave all three anchors intact — this is
`changes/README.md` § *before reshaping a symbol, grep `scripts/` for every guard that anchors on it*, and the
sibling here is the one the task does not name.

**4. The env contract, from both sides** — phase 2's done-when depends on it:

```sh
grep -o '\${[A-Z_][A-Z0-9_]*:?' infra/docker-compose.prod.yml | sed 's/\${//;s/:?//' | sort -u   # 9 required
docker compose --env-file <filled> -f infra/docker-compose.prod.yml config -q                    # exit 0
```

The two angles are independent — one asks what the file *mentions*, the other asks what compose *needs* — and
they agree today. Note the predicate trap the first one walks into: it also matches `VAR` out of the file's
header comment, so a guard built on it must exclude comment lines. **`docker compose config -q` is the env
contract in executable form**; nothing in this CR maintains a list of variable names, and nothing should.

## Phases

- [ ] **1 release identity in the tree** — add `VERSION` (`major.minor`); make `container.yml` derive the version
      from it instead of from the registry, **fail** a publish whose tag already exists rather than replacing it,
      and create the `v<major>.<minor>` git ref on publish. Delete the `|| printf '[]'` fallback path with the
      registry query it belonged to. · **Done when:** `scripts/validate_release_identity.py` is green and its
      bite is recorded (below), and the version the workflow would stamp is derivable and asserted **without a
      network call**.
- [ ] **2 the recipe travels in the image** — copy `infra/docker-compose.prod.yml` and `infra/.env.example` into
      **both** runtime targets of the `Dockerfile`, at a path nothing mounts and nothing writes: **not** `/work`
      (it holds `quarkus-app/`, and the file already carries the trap comment explaining why a mount there
      freezes an upgrade) and **not** `/var/lib/veyq` (the config-secret volume). Extend
      `validate_release_identity.py` to assert the `Dockerfile` **declares** that copy, from the real in-tree
      source path, in **both** runtime targets — a guard anchored on one target passes a Dockerfile whose other
      one drifted, which the config-secret comment in that same file already learned. Note the guard's ceiling
      and write it into its docstring's *limit* field: the trust chain is **buildless**, so nothing in
      `scripts/` can inspect a built image's contents. That the file is really *in* the image is proven by this
      phase's done-when, not by a guard, and the two must not be confused. · **Done when:**
      `docker build --target runtime-jvm .` succeeds; the recipe is extractable from the built
      image (`docker run --rm --entrypoint cat <img> <path>`); that extracted file passes
      `docker compose --env-file <filled> -f - config -q`; and
      `python3 scripts/validate_config_secret_persistence.py` is **still** green.
- [ ] **3 contracts** — the Server-Owner read of the release version, and the one-sentence correction to
      `build`'s description in `contracts/api/setup.openapi.yaml` (it is hand-maintained and gates nothing; it
      does not claim an automatic bump). `GET /api/setup`'s **response shape does not change**. ·
      **Done when:** the specs regenerate and `python3 scripts/validate_contracts_common.py` +
      `validate_field_visibility.py` are green.
- [ ] **4 backend** — serve the release version from the image's `VEYQ_VERSION`, owner-gated; report its
      **absence** explicitly when unstamped (A1), rather than a placeholder. · **Done when:**
      `./gradlew check` green, including a slice test per criterion — the owner read, the unstamped answer, and
      the pre-auth negative. The negative anchors on the **specific observable** (the field absent from
      `/api/setup`, the non-owner call refused with its own status), never on "it threw"; its positive control is
      the owner read on the same predicate.
- [ ] **5 web** — surface the release version to the Server Owner in the **existing** settings/admin surface —
      `ls web/src/features/admin/createTenant/*Panel.vue` shows the built pattern to follow. **Reuse the
      existing UI, never invent new UI** (`docs/crosscutting/ui-web-styleguide.md` § Reuse first). ·
      **Done when:** `npm run check` green.
- [ ] **6 the backup manifest stops lying** — `scripts/instance_backup.py:442` sources `app.version` from the
      instance being captured, or the field goes. Fix `infra/.env.example:25`'s example tag to a shape the
      pipeline actually emits, and `infra/README.md` § Upgrading to name the rollback target. · **Done when:** a
      capture against the dev stack writes a manifest whose `app.version` is either a real value or an explicit
      absence, and never a structural `null`.
- [ ] **7 coverage** — replace the gaps in
      `docs/coverage/features/admin/use-cases/uc.admin.identify-instance-release.yaml` with refs for what phases
      4–5 actually built; keep the two that stay gapped (the workflow is not a coverage surface; the manifest is
      a Python tool's output) with their reasons. · **Done when:** `python3 scripts/validate_coverage.py` green.

## The guard, and its bite — order it guard → red → fix → green

Phase 1 rests on a structural invariant (*a release version exists, comes from the tree, and every artifact that
names one names a shape the pipeline emits*), so its one-shot proof is promoted to a permanent guard,
`scripts/validate_release_identity.py`. Its module docstring carries the three required fields
(`changes/README.md` § what a guard's docstring must carry) — what it asserts, **the defect that bought it**
(this one is concrete: a `VERSION` deleted by a cleanup commit, unnoticed long enough that no container run has
happened since 2026-06-17), and **its limit** (it reads files; it cannot prove a publish ran, and this repository
has no remote — the publish path stays an ADR 0040 gap).

**The defect is still live, so the bite is free and stronger than any injection.** Write the guard **first** and
record its red output before adding `VERSION` — the same run is the discovery, the bite and the fix-list
(`changes/README.md` § where the defect is still live). Reaching for the fix first destroys that evidence and
leaves a reconstruction to inject later. For the phase-2 half of the guard, where nothing is broken yet, fall
back to snapshot-and-restore (`cp` to a scratch path, break, run the **one** guard, `command cp -f` back, confirm
with `git diff --quiet -- <path>` — never `git checkout`), and **assert the injection landed** by grepping the
marker out of the file before believing any run.

## Definition of done

Every phase's own done-when met; `for f in scripts/validate_*.py; do python3 "$f"; done` green over the
**committed** tree (the glob sees files, not commits — an untracked guard is green locally and unchecked in CI);
`./gradlew check` and `npm run check` green; `docs/coverage/` updated for `uc.admin.identify-instance-release`
with `python3 scripts/validate_coverage.py` green.

**And one thing this CR may not claim.** No CI run has ever executed in this repository — there is no remote — so
nothing here proves that a publish stamps a version, creates a ref, or refuses a republish. Those are statements
about a file. The done-when above is deliberately written so that every checkpoint is **locally executable**,
which is the whole reason ADR 0057 §1 puts the version in the tree rather than in the registry. Say *"green
locally over the committed tree"*, never *"green in CI"*.

## Retrospective

*(Filled at close: what went wrong in planning, in execution, and in process/collaboration — both directions —
root cause, and one concrete "next time".)*
