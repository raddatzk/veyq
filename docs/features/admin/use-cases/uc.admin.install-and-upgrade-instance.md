# UC: Install an instance and keep it on the release line its operator chose

- **ID:** uc.admin.install-and-upgrade-instance
- **Primary actor:** The **operator** at the host's shell — in the common single-node deployment the same person as
  the Server Owner ([ADR 0006](../../../decisions/0006-cold-tenant-scope-birth.md)), here with `root` or the Docker
  group rather than a session
- **Goal:** Bring up a Compose instance from a published release, and later move it to a newer one, with one
  command each and without a repository checkout
  ([ADR 0096](../../../decisions/0096-veyqctl-manages-an-instance-from-immutable-releases.md)).
- **Preconditions:** A host with Docker (Compose v2), `python3` (3.9 or newer) and `cosign` (3.1.3 or newer); `veyqctl` fetched and
  its signature checked as the Run area shows. For an upgrade: an instance `veyqctl install` set up.
- **Postconditions (success):** The instance runs the release `VEYQ_RELEASE` selects, from a recipe that is that
  release's assets and images verified by the digests that release names; an upgrade left a backup and the previous
  release's recipe on disk.

## What a release is here

A release is an immutable GitHub Release (or the operator's mirror of one): its assets are the recipe
(`docker-compose.prod.yml`, the `.env` template as `env.example`, `garage.toml`), `veyqctl` itself with its
signature bundle, and a manifest naming both images by
`tag@digest` and every other asset by its sha256, with its own signature bundle (`manifest.json.bundle`) under the
identity that signs the images. Nothing in it can change once published (ADR 0096 decision 3), so "the recipe of
release 1.4" is one set of bytes — and the manifest's signature is what makes those bytes veyq's, wherever they were
fetched from. Snapshots have no release and are never installed.

`VEYQ_RELEASE` in `.env` is the operator's upgrade policy: an exact release (`1.1234`, pinned), a major (`1`, the
newest release of it) or `latest` (crossing majors one at a time: a new major is entered only from the last release
of the one before, so an upgrade from an older release first goes to that one — ADR 0096 decision 5).

## Main flow

1. **Install.** `veyqctl install` (with `VEYQ_RELEASE` given, or `latest`) resolves the release, downloads its assets
   into `/opt/veyq/releases/<release>/` — having verified the manifest's signature, then both images' cosign
   signatures by the manifest's digests, before it fetches or pulls anything the manifest names — writes
   `/opt/veyq/.env` from the release's template with every secret generated and every value the operator must give
   asked for, and brings the stack up. It ends when every service is healthy, and names the admin console's address
   for [first-run setup](uc.admin.setup-server.md).
2. **Upgrade.** Later, by hand, `veyqctl upgrade` resolves the release `VEYQ_RELEASE` selects. If it is the running
   one, it says so and stops. Otherwise it downloads and verifies it as in step 1, checks the new template against
   `.env`, takes a backup ([uc.admin.back-up-and-restore-instance](uc.admin.back-up-and-restore-instance.md), the key to
   the medium `--key-into` names), stops the app, switches to the new recipe and image lines, brings the stack up and
   waits until it is healthy.

## Alternate / exception flows

- **A1 — The new template needs a value `.env` lacks.** The upgrade names the variable and stops **before anything is
  stopped**; the running instance is untouched. With `latest` this is how a major whose removals need the operator
  is caught.
- **A2 — A signature does not verify.** The manifest's is checked first: a manifest whose bundle is missing or does
  not verify is refused before anything it names is fetched or pulled, and then an image whose signature does not
  verify is refused naming it. Nothing is pulled, written or stopped.
- **A3 — The stack is not healthy after the upgrade.** The command fails, names the unhealthy services, and points at
  the way back: the previous release's recipe is still in `releases/`, and the backup it just took restores onto it
  (the schema migrated at boot and has no downgrade path — ADR 0056 §6).
- **A4 — A pinned release.** `upgrade` does nothing and says so; `veyqctl check` still says what is newer.
- **A5 — A mirror.** With a source override the same flow runs against the operator's mirror, verified exactly as
  GitHub is; a mirror decides which releases it offers — it can withhold a newer one — but cannot change what one
  contains.
- **A6 — Kubernetes.** Not this use-case: a cluster upgrades with `helm upgrade` by digest (ADR 0096 decision 8).

## User impact

None for members while the instance runs; an upgrade stops the app for its duration, as every upgrade does today.

## Related

- [ADR 0096](../../../decisions/0096-veyqctl-manages-an-instance-from-immutable-releases.md) (the tool and the release),
  [ADR 0057](../../../decisions/0057-release-identity-and-deployment-recipe.md) (the release identity),
  [ADR 0056](../../../decisions/0056-instance-durable-state-and-cold-logical-backup.md) (the backup an upgrade starts with)
- [uc.admin.identify-instance-release](uc.admin.identify-instance-release.md),
  [uc.admin.learn-of-new-releases](uc.admin.learn-of-new-releases.md)
