# ADR 0096 — `veyqctl` manages an instance from immutable releases

- Status: Accepted 2026-10-16; built: `veyqctl` (decisions 1, 2, 4–7), the producer of a release's assets, and no
  image carries a recipe (`scripts/veyqctl.py`, `scripts/release_assets.py`); the stack lane installs every candidate
  through it. Not built: the publish creating the GitHub Release (decision 3), the Run pages around `veyqctl`
- Date: 2026-10-16

## Context

A Compose operator runs veyq today by following the Run pages by hand: [Verify a release](../../site/src/content/docs/run/verify-a-release.md)
resolves digests, checks two cosign signatures and extracts the recipe from the app image;
[Set up a new instance](../../site/src/content/docs/run/new-instance.mdx) copies it into `infra/`;
[Upgrade](../../site/src/content/docs/run/upgrade.mdx) is a six-step procedure; [Backup and restore](../../site/src/content/docs/run/backup-and-restore.md)
calls `scripts/instance_backup.py` from a repository checkout. Three forces meet here:

- **The recipe in the image is not self-sufficient.** [ADR 0057](0057-release-identity-and-deployment-recipe.md) §3
  copies `docker-compose.prod.yml` and `.env.example` into the app image at `/opt/veyq/deploy/`, but the compose file
  bind-mounts `./garage.toml`, which the image does not carry (`grep -n "/opt/veyq/deploy" Dockerfile`;
  `grep -n '\./' infra/docker-compose.prod.yml`). Found 2026-10-16 by `scripts/run_stack_e2e.py`
  ([ADR 0090](0090-end-to-end-tests-boot-the-release-candidate.md)), which copies the file in from the checkout.
- **The operator needs the repository regardless**, for `scripts/instance_backup.py`
  (`grep -n "instance_backup.py" site/src/content/docs/run/backup-and-restore.md`). So the in-image recipe saves the
  operator nothing, and self-hosted projects usually ship their compose file from the repository or a release
  (remembered, not surveyed).
- **A release has no immutable home of its own.** ADR 0057 makes a release one git ref `v<major>.<minor>` and signed
  images; there is no GitHub Release object, and a tag can be moved by anyone with write access. GitHub's
  **immutable releases** (generally available 2025-10-28) close exactly that: once published, a release's assets
  cannot be added, changed or deleted, and its tag can be neither moved nor deleted.

The owner asked (2026-10-16) for the operator's side to be as easy as an all-in-one installer, and chose a host-side
tool over a managing container: Nextcloud AIO's mastercontainer drives the stack through the Docker socket, which is
root on the host, and its own Helm chart drops it for that reason, losing updates and backups with it.

## Decision

**1. Management is one host-side tool, `veyqctl`, separate from administration.** Administration is what the Server
Owner does *inside* a running instance through the Admin API — [admin-web](0065-admin-ui-cli-separate-service.md) and
its Node CLI (`admin-web/src/cli/`). Management is the instance's lifecycle *on the host*. The two never merge:
`veyqctl` holds no Admin API credential, and admin-web gains no host access.

| Command | Does |
|---|---|
| `install` | fetch the release `VEYQ_RELEASE` selects, verify it, write `.env` from its template with fresh secrets, `up` |
| `start` / `stop` / `status` / `logs` | the stack, addressed by the recipe's project name |
| `upgrade` | decision 5 |
| `backup` / `restore` | today's `instance_backup.py` capture, verify and restore; leg (c), the config-secret key, still goes to a separate medium, so `--key-into` stays required ([ADR 0056](0056-instance-durable-state-and-cold-logical-backup.md)) |
| `verify` | check the signatures of the release `VEYQ_RELEASE` selects, nothing else |
| `check` | which release `upgrade` would take, and whether a newer major exists beyond the selector |
| `self-update` | replace `veyqctl` with the one of the installed release |

**2. Python, standard library only, one file.** The repository's rule for scripts, and the host already needs
`python3` for backups. One file because the operator fetches it with `curl`: no `pip`, no virtualenv.

**3. A release is an immutable GitHub Release, and the recipe is its assets.** The publish creates, per release, a
GitHub Release on the tag `v<major>.<minor>` carrying `docker-compose.prod.yml`, `.env.example`, `garage.toml`,
`veyqctl` (signed keylessly with `cosign sign-blob`, the same identity as the images), and a manifest naming both
images by `tag@digest`. The repository has immutable releases switched on before the first release, so nothing a
release carries can change after it is published. Snapshots get no GitHub Release; `veyqctl` never installs one.

**4. The operator picks a release line in `.env`: `VEYQ_RELEASE`.**

| Value | `install` / `upgrade` take |
|---|---|
| `1.1234` | exactly that release — pinned; `upgrade` does nothing |
| `1` | the newest release of major 1 |
| `latest` | the newest release, crossing majors |

"Release", not "version": [ADR 0086](0086-one-word-per-meaning.md) keeps "version" for compatibility.

**5. `upgrade` runs only by hand.** No timer, no unattended mode: an upgrade replaces the web client that holds the
keys, so it stays a deliberate act. In order, and nothing is stopped before step 4:
1. resolve the release `VEYQ_RELEASE` selects; stop if it is the running one;
2. download its assets into `releases/<version>/` and verify both images' cosign signatures by the manifest's digests;
3. refuse if the new `.env.example` requires a value `.env` lacks, naming it — with `latest` this is what stops a
   major whose removals need the operator;
4. back up; stop the app; switch to the new release's recipe and image lines; `up`; wait until healthy.

The previous release's directory stays, so the diff and the way back are both on disk.

**6. An instance lives in `/opt/veyq`** (`--home` overrides): `releases/<version>/`, `.env`, `backups/`. There is
nothing to migrate from today's `infra/` layout: no release has ever been published.

**7. GitHub is the default source, never a requirement.** `veyqctl` takes a source override, a mirror the operator
runs; trusting that mirror is the operator's own decision, and the Run pages say so.

**8. Kubernetes is out of scope.** `veyqctl` drives Compose only. A cluster upgrades with `helm upgrade` by digest
and keeps current with its own GitOps tooling; veyq ships no in-cluster operator.

## Consequences

- **ADR 0057 §3 is rewritten in place:** the recipe leaves the image and becomes the release's assets. The two
  `COPY … /opt/veyq/deploy/` lines and `validate_release_identity.py` assertion 4 go; the `garage.toml` gap closes
  with them.
- **[ADR 0091](0091-one-pipeline-per-commit-a-release-promotes-a-tested-snapshot.md)'s promotion gains a step:**
  promoting a snapshot to a release creates the GitHub Release with its assets, after the images are signed.
- **ADR 0090's lane L1 boots the candidate through `veyqctl install`**, so the operator's path is the tested path;
  `run_stack_e2e.py`'s `extract()` goes. A local, unsigned candidate needs a way past the signature check that is
  refused unless both images are local references.
- **The Run area is rewritten around `veyqctl`** (new-instance's Compose tab, upgrade, backup-and-restore,
  verify-a-release). Each page keeps what the tool runs, so an operator can do any step by hand.
- **Immutable releases are a repository setting** the owner switches on once the repository has its remote (inbox #26
  item 1); until then nothing is published and nothing here can run against GitHub.
- **Where `instance_backup.py` goes** — folded into `veyqctl` or shipped beside it — is the CR's call.
- **Rejected — keep the recipe in the image and add `garage.toml`.** Closes the gap, keeps an unusual shape, and
  still leaves the operator needing the repository for backups. Refined as a CR first, then dropped by the owner.
- **Rejected — fetch the recipe at the commit the image names (`VEYQ_REVISION`).** Binds recipe to image without a
  release object, but needs a fetch-by-commit and a content check per mirror; an immutable release gives the same
  binding with nothing to check.
- **Rejected — a managing container (Nextcloud AIO's shape).** Easiest for the operator, but it needs the Docker
  socket (root on the host) beside a recipe that is otherwise read-only, non-root and capability-free, and it has no
  Kubernetes form.
- **Rejected — unattended upgrades.** Convenient, and they roll a compromised publish out with nobody looking.
- **Rejected — Bash.** Asked for first; the upgrade logic (signatures, template check, backup) is not thin glue, and
  the backup tool is already Python.
