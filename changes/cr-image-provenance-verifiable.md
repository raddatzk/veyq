---
id: cr-image-provenance-verifiable
title: A self-hoster can verify where both images came from, and the recipe pins every image it runs
status: planned
satisfies: []
adrs: ["0057", "0064", "0065"]
invariants: [self-hostable by default]
---

## Goal

The only `cosign verify` in the repository is a workflow comment, and its regex accepts a signature from any
workflow on any ref. `infra/README.md` never tells an operator to verify, extracts the recipe by a mutable
tag, and its upgrade steps pull and pin only `veyq`, never `veyq-admin`. The signed recipe then runs
`mariadb`, `garage` and `traefik` by tag, and so does the backup tool's `rclone`. After this CR:
- the README verifies **both** images against an identity pinned to `container.yml` on `main` or
  `release/<major>.x` of `raddatzk/veyq`, then extracts and deploys **by the verified digest**, on compose and
  on Helm;
- every third-party image the recipe, the chart, the Dockerfiles and the backup tool run is digest-pinned;
- two guards hold both properties.

Inbox #163, runtime half, and before V1. The build-time half is `cr-build-inputs-pinned`.

## Context & constraints

- **The decisions:** [ADR 0057](../docs/decisions/0057-release-identity-and-deployment-recipe.md) §3 (*a
  verification is only as narrow as the identity it pins*) and §6 (*a release pins what it runs*), plus the pin
  trade in its Consequences.
- **What signs today** (`.github/workflows/container.yml`):
  - keyless cosign over the **index** digest of both images (`:318-334`);
  - publishes from `main`, `master` or `release/${major}.x` (`:78-81`);
  - `id-token: write` on the publish job (`:213`).

  The certificate SAN this produces is `https://github.com/raddatzk/veyq/.github/workflows/container.yml@refs/heads/<branch>`.
  The slug `raddatzk/veyq` is `charts/veyq/Chart.yaml:21-23`. The repository has no remote, so **no signature
  has ever been produced**. Every statement about the publish is about a file (ADR 0057 § Consequences).
- **A guard trap, measured by the refine's scan:** `validate_release_identity.py:150` (`IMAGE_TAG`) reads every
  file under `infra/`, README included. A literal `veyq:1.0@sha256:<hex>` there is captured as the tag
  `1.0@sha256:…` and turns it red. Examples use placeholders (`veyq:<version>@sha256:<digest>`, or
  `veyq@<digest>`), which it skips. The chart's `APP_IMAGE`/`ADMIN_IMAGE` regexes
  (`chart_meta_check.py:128-129`) still match `repo:tag@sha256:…`, but **not** a bare `repo@sha256:…`, and
  `ADMIN_IMAGE` has no fallback. So the chart renders `repo:tag@digest`, never digest-only.
- **`infra/.env.example:28-31` keeps its invalid-reference trick.** An unfilled copy must still fail `up`
  loudly. Naming the upstream literally applies to the verify and pull instructions, not to that sentinel.
- **Out of scope:**
  - build-time inputs (`cr-build-inputs-pinned`);
  - the chart as a signed OCI artifact (ADR 0064, not yet built);
  - `apt-get`/`microdnf`/`apk` package installs inside the Dockerfiles, which a `FROM` digest does not pin.
    Named, not solved.

## Phases

- [ ] 1 identity — ordered guard → red → fix → green, because the defect is live
      (`changes/README.md` § *Where the defect is still live*).
      - **Guard first.** `validate_release_identity.py` gains the check. It reads the README's documented
        `cosign verify`, then asserts two things. The issuer is `https://token.actions.githubusercontent.com`.
        And the identity pattern **accepts** the SANs for every branch the version step publishes from (derive
        them from the scenarios the guard already runs, `:250-276`, not from a list here) and **rejects**:
        - another workflow on `main`;
        - `container.yml` on a non-release branch;
        - a `refs/pull/…` and a `refs/tags/…` ref;
        - another owner;
        - a repository whose name only starts with `veyq`.

        It also asserts that both images are verified. Run it: **red**, because no README command exists.
      - **Then the fix.** `container.yml`'s version step accepts `main` and `release/${major}.x` only, so
        `master` goes. Its signing comment stops carrying its own regex and points at the README section.
        `infra/README.md` gets one verification section, used by first install, upgrade and Helm alike:
        1. resolve the tag to its index digest **once**;
        2. `cosign verify` that digest for `veyq` and `veyq-admin` with the pinned identity;
        3. extract the recipe from `@<digest>`;
        4. set `VEYQ_IMAGE`/`VEYQ_ADMIN_IMAGE` to `…:<version>@sha256:<digest>`.

        Upgrade steps 4–6 and the rollback cover the admin image too (today `:554-589` handle only `veyq`).
        Instructions name `ghcr.io/raddatzk/…` literally; `.env.example`'s sentinel stays. Locators:
        `grep -n -E 'master|cosign' .github/workflows/container.yml scripts/validate_release_identity.py` and
        `grep -rn '<owner>' infra charts admin-web/Dockerfile`.
      - **Bite it** once green: swap in today's repo-wide regex, and restore `master` in the workflow without
        touching the README. Each must go red.

      · Done when: the trust-chain guards are green (`for f in scripts/validate_*.py; do python3 "$f"; done`).
      · Done when: the README's commands, run by hand against any public cosign-signed multi-arch image with
        that image's own identity, verify and extract as written. No release of ours exists to run them
        against, and the retro says so.
- [ ] 2 chart — `image.digest` and `admin.image.digest` values. `_helpers.tpl:37-43` renders `repo:tag@digest`
      when a digest is set; the schema gains the key with a `^sha256:[0-9a-f]{64}$` pattern. The Helm upgrade
      line (`infra/README.md:374`) sets both digests.
      · Done when: `python3 scripts/chart_meta_check.py` green with a digest set in one `ci/` values file.
- [ ] 3 third-party digest pins + guard — pin every external image by its **multi-arch index** digest, as
      `repo:tag@sha256:…`. A platform manifest's digest would pin one architecture, and the publish builds
      amd64 and arm64. The sites come from two locators that must agree:
      - by key: `grep -rn -E '^\s*(- )?image:|^FROM ' infra charts/veyq/values.yaml Dockerfile admin-web/Dockerfile`
        plus `grep -n -E '^[A-Z_]*IMAGE\s*=' scripts/instance_backup.py`;
      - by shape:
        `git ls-files | grep -v -E 'node_modules|package-lock|\.md$|^docs/|^wiki/|^changes/' | xargs grep -n -o -E '\b(mariadb|dxflrs/garage|traefik|rclone/rclone|axllent/mailpit|eclipse-temurin|node|nginxinc/nginx-unprivileged|quay\.io/quarkus/[a-z-]+|registry\.access\.redhat\.com/ubi9/ubi-minimal):[A-Za-z0-9._-]+' | sort -u`.
        At refine, the by-shape extras beyond the by-key set were only comments and `node:` module imports.

      Fix the stale `traefik:v3.3` in `instance_backup.py`'s comment while there.
      **Guard (permanent):** `scripts/validate_image_pins.py`. The sites are derived from the files, never
      listed. It asserts:
      - every external reference carries `@sha256:<64 hex>`;
      - one `repo:tag` carries one digest everywhere;
      - `${VEYQ_IMAGE}`-style references and `FROM <earlier-stage>` are not external references.

      **Bite it:** strip one digest; give the dev and prod `mariadb` different digests; and as the control,
      confirm a `FROM backend-build-base` is not flagged.
      **Machine hint, holds for this CR's shape:** reading an index digest contacts the registry
      (`docker buildx imagetools inspect <ref>`), and registry calls through Docker can stall on this Mac's
      keychain credential helper. A registry-API call without Docker's credential store avoids it.
      · Done when: the guards are green, and `docker compose -f infra/docker-compose.prod.yml config -q` still
        passes.
- [ ] 4 updater config — `.github/dependabot.yml`:
      - add the image ecosystems for the Dockerfiles (`/`, `/admin-web`), the compose files (`/infra`) and the
        chart (`/charts/veyq`), after checking against Dependabot's current docs which of them it actually
        reads, since that was remembered at refine, not measured;
      - point `npm` at `/`, where the workspace lockfile is (today `/web`, which misses `admin-web`);
      - replace the stale "no root Dockerfile yet" comment.

      No remote exists, so this is unproven by construction. Say so in the file's own comment.
      · Done when: the guards are green.

## Definition of done

- Every phase is done.
- The identity check and `validate_image_pins.py` have each been seen red.
- `chart_meta_check.py` and the trust-chain guards are green.

**Not proven by the above, named:** that a real release verifies with the documented command. Nothing has been
published. The first publish's retro runs the README's verification section against it, and only then is
ADR 0057 §3 observed rather than written.

## Retrospective
