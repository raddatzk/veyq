# ADR 0096 — A host-side tool manages the instance; the recipe comes from the release's commit

- Status: Proposed 2026-10-16 (owner's direction; the open questions below need answers before a CR)
- Date: 2026-10-16

## Context

A Compose operator runs veyq today by following the Run pages by hand: [Verify a release](../../site/src/content/docs/run/verify-a-release.md)
resolves digests, checks two cosign signatures and extracts the recipe from the app image;
[Set up a new instance](../../site/src/content/docs/run/new-instance.mdx) copies it into `infra/`;
[Upgrade](../../site/src/content/docs/run/upgrade.mdx) is a six-step procedure (back up, verify, diff, apply, `up`);
[Backup and restore](../../site/src/content/docs/run/backup-and-restore.md) calls `scripts/instance_backup.py` from a
repository checkout. Three forces meet here:

- **The recipe in the image is not self-sufficient.** [ADR 0057](0057-release-identity-and-deployment-recipe.md) §3
  copies `docker-compose.prod.yml` and `.env.example` into the app image at `/opt/veyq/deploy/`, but the compose file
  bind-mounts `./garage.toml`, which the image does not carry (`grep -n "/opt/veyq/deploy" Dockerfile`;
  `grep -n '\./' infra/docker-compose.prod.yml`). An extracted recipe needs the repository anyway. Found 2026-10-16 by
  `scripts/run_stack_e2e.py` ([ADR 0090](0090-end-to-end-tests-boot-the-release-candidate.md) phase 1), which copies the
  file in from the checkout on every run.
- **The operator needs the repository regardless.** The backup tool is `scripts/instance_backup.py`, run from a
  checkout (`grep -n "instance_backup.py" site/src/content/docs/run/backup-and-restore.md`). So the in-image recipe
  saves the operator nothing, and it is an unusual shape: self-hosted projects ship their compose file from the
  repository or a release asset (remembered, not surveyed).
- **The signature argument for the in-image recipe is weaker than ADR 0057 §3 stated.** The cosign identity the
  operator pins is `container.yml` in `raddatzk/veyq` on GitHub's OIDC issuer — the trust root is GitHub either
  way. What the in-image copy really bought is the *binding* of recipe to image, and that binding exists without
  the copy: every image carries the commit it was built from, `VEYQ_REVISION=sha-<commit>`
  (`Dockerfile:100-102`, `.github/workflows/container.yml:283`), inside the signed image config.

The owner asked (2026-10-16) for the operator's side to be as easy as an all-in-one installer, and chose a host-side
tool over a managing container: Nextcloud AIO's mastercontainer drives the stack through the Docker socket, which is
root on the host, and its own Helm chart drops it for exactly that reason (it runs "without a container having access
to the docker socket", at the cost of the UI, update notifications and backups).

## Decision

**1. Instance management is one host-side command-line tool, separate from administration.** Administration is what
the Server Owner does *inside* a running instance through the Admin API — [admin-web](0065-admin-ui-cli-separate-service.md)
and its Node CLI (`admin-web/src/cli/`). Management is the instance's lifecycle *on the host*: containers, recipe,
backups, upgrades. The two never merge: the management tool holds no Admin API credential, and admin-web gains no
host access. Its commands:

| Command | Does |
|---|---|
| `install <version>` | verify, fetch the recipe, write `.env` from the template with fresh secrets, `up` |
| `start` / `stop` / `status` / `logs` | the stack, addressed by the recipe's project name |
| `upgrade <version>` | verify the target, refuse before stopping anything if the new template needs a value `.env` lacks, back up, pull, switch the recipe, `up`, wait healthy |
| `backup` / `restore` | today's `instance_backup.py` capture, verify and restore — leg (c), the config-secret key, still goes to a separate medium (`--key-into` stays required, [ADR 0056](0056-instance-durable-state-and-cold-logical-backup.md)) |
| `verify <version>` | resolve both images to digests and check both signatures, nothing else |
| `check` | whether a newer release exists |
| `self-update` | replace the tool with the one of a verified release |

**2. Python, standard library only, one file.** The repository's rule for scripts, and the host already needs
`python3` for backups. One file because the operator fetches it with `curl`; no `pip`, no virtualenv.

**3. The recipe is `infra/` at the release's commit, and the tool binds it to the verified image.** The tool
verifies the app image by digest (the identity [Verify a release](../../site/src/content/docs/run/verify-a-release.md)
pins today), reads `VEYQ_REVISION` out of that image's config, and fetches the recipe — `docker-compose.prod.yml`,
`.env.example`, `garage.toml`, and the backup code — **at that commit**, checking what it fetched against it. So
the recipe is exactly as trustworthy as the signed image names it, and nothing is copied into the image. The tool
keeps one directory per installed version, so an upgrade's diff and a rollback have both sides on disk.

**4. The tool itself is a release artifact.** The publish signs it keylessly with the same identity as the images
(`cosign sign-blob`), and the Run pages give the `curl` plus the `cosign verify-blob` that checks it. `self-update`
does the same check.

**5. Kubernetes is out of scope.** The tool drives Compose only. A cluster is upgraded with `helm upgrade` by digest,
and kept current by the cluster's own GitOps tooling; veyq ships no in-cluster operator.

## Consequences

- **ADR 0057 §3 changes:** the recipe no longer travels in the image; the two `COPY … /opt/veyq/deploy/` lines and
  `validate_release_identity.py` assertion 4 go, and §3 is rewritten to the binding of decision 3 (edited in place).
  The defect that started this — `garage.toml` missing from the extracted recipe — disappears with the extraction.
- **ADR 0090 changes:** lane L1 boots the candidate *through the tool* (`install` with the candidate's images), so
  the operator's path is the tested path; `run_stack_e2e.py`'s `extract()` goes. A local, unsigned candidate needs
  a flag that skips the signature check, refused unless both images are local references.
- **The Run area is rewritten around the tool** (new-instance's Compose tab, upgrade, backup-and-restore,
  verify-a-release); each page still shows what the tool runs, so an operator can do any step by hand.
- **`scripts/instance_backup.py` becomes part of the tool's release set**; whether it is folded into the one file or
  fetched beside the recipe is a CR decision, not this ADR's.
- **New dependency on GitHub for fetching**, which *self-hostable by default* forbids making mandatory: the tool
  takes a source override (a mirror, or a local checkout).
- **ADR 0091 gains an artifact:** the tool is published and signed per release, next to the two images and the chart.
- **Rejected — keep the recipe in the image and add `garage.toml`.** Closes the gap, keeps an unusual shape, and
  leaves the operator needing the repository for backups. Refined as a CR first, then dropped by the owner.
- **Rejected — a managing container (Nextcloud AIO's shape).** Easiest for the operator, but it needs the Docker
  socket (root on the host) beside a recipe that is otherwise hardened to read-only, non-root and no capabilities,
  and it has no Kubernetes form.
- **Rejected — Bash.** Asked for first; the upgrade logic (signature check, template diff, backup) is not thin glue,
  and the backup tool is already Python.

### Open questions — answer before the CR

1. **The tool's name.** It must not say *admin* (that is admin-web) or *app* (ADR 0086). Proposal: `veyqctl`.
2. **How decision 3 checks the fetched recipe against the commit.** A `git` fetch of that commit verifies object
   hashes but needs `git` on the host; an HTTP fetch of files at the commit needs no `git` but trusts the transport.
   A signed per-release tarball would be a fourth artifact.
3. **Where the operator's instance lives** (the tool's home directory, `.env` and backups), and how an existing
   `infra/`-based install moves into it.
