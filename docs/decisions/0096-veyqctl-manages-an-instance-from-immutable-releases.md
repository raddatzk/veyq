# ADR 0096 — `veyqctl` manages an instance from immutable releases

- Status: Accepted
- Built: partly (not built: decision 3's immutable-releases setting and the first published release)
- Date: 2026-10-16
- Model: [release-and-deployment](../crosscutting/release-and-deployment.md)

## Context

A Compose operator ran veyq by following the Run pages by hand: resolve digests, check two signatures, extract the
recipe from the app image, copy it into place, a six-step upgrade, and a backup tool called from a repository
checkout. Three forces met:

- **The recipe in the image was not self-sufficient.** [ADR 0057](0057-release-identity-and-deployment-recipe.md) §3
  then copied the compose file and `.env.example` into the app image, but the compose file bind-mounts `./garage.toml`,
  which the image did not carry; lane L1 ([ADR 0090](0090-end-to-end-tests-boot-the-release-candidate.md)) found it.
- **The operator needed the repository regardless**, for the backup tool, so the in-image recipe saved nothing.
- **A release had no immutable home.** A git tag can be moved by anyone with write access. GitHub's **immutable
  releases** close exactly that: once published, a release's assets cannot be added, changed or deleted, and its tag
  can be neither moved nor deleted.

The owner asked for the operator's side to be as easy as an all-in-one installer, and chose a host-side tool over a
managing container: Nextcloud AIO's mastercontainer drives the stack through the Docker socket, which is root on the
host, and its own Helm chart drops it for that reason, losing updates and backups with it.

## Decision

**1. Management is one host-side tool, `veyqctl`, separate from administration.** Administration is what the Server
Owner does *inside* a running instance through the Admin API — [admin-web](0065-admin-ui-cli-separate-service.md) and
its CLI. Management is the instance's lifecycle *on the host*: install, start and stop, upgrade, backup and restore,
rollback, verify, self-update. The two never merge: `veyqctl` holds no Admin API credential, and admin-web gains no
host access. The commands are the Run area's (and `veyqctl`'s own docstring); `rollback` returns in place to the
release a capture was taken under, the Compose form of [ADR 0098](0098-on-kubernetes-the-start-that-would-migrate-backs-up-first.md)
decision 9's rollback.

**2. Python, standard library only, one file.** The repository's rule for scripts, and the host already needs
`python3` for backups. One file because the operator fetches it with `curl`: no `pip`, no virtualenv.

**3. A release is an immutable GitHub Release, and the recipe is its assets.** Per release, a GitHub Release on the tag
`v<major>.<minor>` carries the compose file, its `.env` template, every file the compose file reads beside itself,
`veyqctl`, the backup tool and a manifest naming the images by `tag@digest` and every other asset by its sha256 — all
uploaded in the one call that creates the release. The manifest and `veyqctl` are signed under the images' identity,
and `veyqctl` verifies the manifest's signature before it trusts anything it names. The repository has immutable
releases switched on before the first release, so nothing a release carries can change after it is published.
Snapshots get no GitHub Release; `veyqctl` never installs one.

**4. The operator picks a release line in `.env`: `VEYQ_RELEASE`** — an exact release (pinned: `upgrade` does
nothing), a major (its newest release), or `latest` (the newest, crossing majors one at a time, decision 5).
"Release", not "version": [ADR 0086](0086-one-word-per-meaning.md) keeps "version" for compatibility.

**5. `upgrade` runs only by hand.** No timer, no unattended mode: an upgrade replaces the web client that holds the
keys, so it stays a deliberate act. In order, and nothing is stopped before step 4:
1. resolve the release `VEYQ_RELEASE` selects; stop if it is the running one. **A major is entered only from the
   release its upgrade was proved from:** the release of the next major this run goes to (the selected one, or that
   major's newest when the selection lies beyond it) names its entry as `majorEntry` in its signed manifest — the
   newest release of the previous major when it was cut, a `release/<major>.x` fix included, the one its upgrade proof
   ran from and its tools image bakes ([ADR 0085](0085-one-major-moved-on-a-yearly-train.md) decision 10). Running
   that entry, this run enters the next major; running an older release, it upgrades to the entry first and says that
   the next `upgrade` enters the next major; running a later one (a fix release cut after that release), it refuses,
   since no path from it was proved and a later release of the next major is entered from it — and no run crosses
   more than one major. That entry is the path the lanes test
   ([ADR 0091](0091-one-pipeline-per-commit-a-release-promotes-a-tested-snapshot.md) decision 6). *Amended
   2026-10-31 (inbox #436): it was "the running major's newest release", which a fix release published after the
   next major's release turned into a path nobody proved;*
2. verify the manifest's signature, then both images' signatures by the manifest's digests, then download its assets
   into `releases/<release>/`, each against the manifest's sha256;
3. refuse if the new `.env` template requires a value `.env` lacks, naming it — with `latest` this is what stops a
   major whose removals need the operator;
4. back up; stop the app; switch to the new release's recipe and image lines; start; wait until healthy. The backup is
   the database and the store's index ([ADR 0056](0056-instance-durable-state-and-cold-logical-backup.md) §3), taken
   with the app stopped. With an object pool configured, the objects are copied into it **before** the app stops and
   topped up after: objects are immutable, so the early copy cannot go stale, and the downtime grows by the new
   uploads only.

The previous release's directory stays, so the diff and the way back are both on disk.

**6. An instance lives in `/opt/veyq`** (`--home` overrides): `releases/<release>/`, `.env`, `backups/`. The object
pool gets no directory here: it lives where the operator names, by intent another disk or another store, since a pool
on the store's own disk dies with it.

**7. GitHub is the default source, never a requirement.** `veyqctl` takes a source override, a mirror the operator
runs, verified exactly as GitHub is: the manifest's signature holds its contents to veyq's publish, so trusting a
mirror is a matter of availability and freshness — it decides which releases are offered, and could withhold a newer
one — never of integrity.

**8. Kubernetes is out of scope.** `veyqctl` drives Compose only. A cluster upgrades with `helm upgrade` by digest
and keeps current with its own GitOps tooling; veyq ships no in-cluster operator.

## Consequences

- **The operator's path is the tested path.** Lane L1 installs the candidate with `veyqctl install` from its own
  release assets, served as a local source; a local, unsigned candidate passes the signature check only with
  `--unsigned-local`, refused unless both images are local references. Lanes L2 and L3 are `veyqctl upgrade` and
  `veyqctl restore`.
- **Each Run page keeps what the tool runs**, so an operator can do any step by hand.
- **The L3 archive holds the previous major's last release too** — the one release a new major is entered from;
  within a major, every archived backup still opens.
- **Immutable releases are a repository setting** the owner switches on once the repository has its remote; until
  then nothing is published and nothing here can run against GitHub.
- **Rejected — keep the recipe in the image and add `garage.toml`.** Closes the gap, keeps an unusual shape, and still
  leaves the operator needing the repository for backups.
- **Rejected — fetch the recipe at the commit the image names (`VEYQ_REVISION`).** Binds recipe to image without a
  release object, but needs a fetch-by-commit and a content check per mirror; an immutable release gives the same
  binding with nothing to check.
- **Rejected — a managing container (Nextcloud AIO's shape).** Easiest for the operator, but it needs the Docker
  socket (root on the host) beside a recipe that is otherwise read-only, non-root and capability-free, and it has no
  Kubernetes form.
- **Rejected — step through every intermediate release.** Within a major Flyway applies every missing migration in
  order whatever the starting release, and L3 restores every archived backup of the major into the candidate, so a
  skip is a tested path; stepping would only multiply downloads, backups and restarts.
- **Rejected — any release straight into a newer major.** One run fewer, but no lane covers the data path from an
  arbitrary older release across a major's removals; entering through the previous major's last release costs one
  upgrade and keeps every path tested.
- **Rejected — unattended upgrades.** Convenient, and they roll a compromised publish out with nobody looking.
- **Rejected — Bash.** Asked for first; the upgrade logic (signatures, template check, backup) is not thin glue, and
  the backup tool is already Python.

## Related

- Run area of the docs site, each page carrying what `veyqctl` runs: [Set up a new instance](../../site/src/content/docs/run/new-instance.mdx),
  [Upgrade](../../site/src/content/docs/run/upgrade.mdx), [Backup and restore](../../site/src/content/docs/run/backup-and-restore.md),
  [Verify a release](../../site/src/content/docs/run/verify-a-release.md)
