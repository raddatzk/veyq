---
title: Backup and restore
description: Capture an instance's three legs cold, check the capture, and restore it onto a fresh instance.
sources:
  - docs/features/admin/use-cases/uc.admin.back-up-and-restore-instance.md
  - docs/decisions/0056-instance-durable-state-and-cold-logical-backup.md
---

The use-case owns the goal and the failure modes
([uc.admin.back-up-and-restore-instance](https://github.com/raddatzk/veyq/blob/main/docs/features/admin/use-cases/uc.admin.back-up-and-restore-instance.md));
[ADR 0056](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0056-instance-durable-state-and-cold-logical-backup.md) owns the decisions. This
page owns the **commands**, and `scripts/instance_backup.py` is what they call — its module docstring carries
the tool's own reference.

**An instance's durable state is exactly three legs:** (a) the database, (b) the object store's *contents*,
(c) the **config-secret key file**. Two legs produce an instance that boots, serves content, and refuses member
invites and password resets with `503 mail_unavailable` — the leg that gets forgotten is the one whose absence
looks like health.

**The capture is cold, and *cold* means the writer is stopped — not the stores.** Stop the application; leave
MariaDB and Garage running, because the capture reads through their own interfaces. That single act is the whole
consistency argument: with the app down the database gains no rows, so a row whose object was never captured is
structurally impossible and there is no ordering to remember. The tool cannot check this for you and does not
pretend to.

### Capture

On the production recipe, from the directory that holds `infra/`:

```sh
# 1. STOP the application, and only it. MariaDB and Garage keep running.
docker compose -f infra/docker-compose.prod.yml stop app

# 2. Credentials, under the names the tool reads: the passwords come from infra/.env.
#    The endpoint is the one the rclone container sees on the store's network, not a published port.
#    The bundled store: its admin token; the tool derives the S3 key pair from it, so nothing else is typed.
read -rs VEYQ_BACKUP_DB_PASSWORD && export VEYQ_BACKUP_DB_PASSWORD              # = MARIADB_ROOT_PASSWORD
read -rs VEYQ_BUNDLED_STORE_ADMIN_TOKEN && export VEYQ_BUNDLED_STORE_ADMIN_TOKEN  # = GARAGE_ADMIN_TOKEN
export VEYQ_BUNDLED_STORE_ENDPOINT=http://garage:3900

# 3. Capture legs (a) and (b) into infra/backups/<UTC-ISO8601>/, and leg (c) to a medium of its own.
sudo -E python3 scripts/instance_backup.py capture \
    --compose-file infra/docker-compose.prod.yml \
    --docker-network veyq_edge \
    --config-secret-file "$(docker volume inspect -f '{{.Mountpoint}}' veyq_veyq-config-secret)/config-secret.key" \
    --key-into /media/usb-stick/veyq-<stamp>.key

# 4. CHECK both — the store leg is captured logically precisely so this is possible at all.
sudo python3 scripts/instance_backup.py verify infra/backups/<stamp> --key-from /media/usb-stick/veyq-<stamp>.key

# 5. Start the application again. The downtime was the capture, and nothing else.
docker compose -f infra/docker-compose.prod.yml start app
```

**A store of your own.** The store is a setting in the admin console, not an environment variable, and the capture is
cold — no app is running to sign for the tool — so you give the tool the store's credentials for this run, the ones you
entered in the console (the console never shows them again, and neither can the tool read them back): export
`VEYQ_BACKUP_STORE_ENDPOINT`, `VEYQ_BACKUP_STORE_ACCESS_KEY` and `VEYQ_BACKUP_STORE_SECRET_KEY` instead of the
bundled pair, plus `VEYQ_BACKUP_STORE_BUCKET` and `VEYQ_BACKUP_STORE_REGION` when they are not `blobs` and
`us-east-1`. Keep them in a password manager: after a lost config-secret key the console asks for the same
credentials again.

The network is `veyq_edge`, the one the store is on: the production compose file declares `name: veyq`, so that is
the project, and nothing has to be passed on the command line to make it so. Leg (c) lives on a **volume**, not on
your filesystem, so it is addressed there, and it is copied to a destination of its own, never into the capture.

**Capture and verify run as root.** The key is the application's (uid `10001`, mode `0600`, on a volume under
Docker's data root), so no other user can read it, and a capture without root cannot take leg (c). `-E` keeps the
credential variables (a `sudoers` that resets the environment needs them passed explicitly). What a root capture
writes is root's, owner-only: the capture directory and the key file. So `verify` runs as root too, and so does
the encryption below, or hand both to yourself first:
`sudo chown -R "$(id -u):$(id -g)" infra/backups/<stamp> /media/usb-stick/veyq-<stamp>.key`.

**How far this is proven.** Every `scripts/run_web_e2e.py` run captures a live instance, destroys it, restores it
and runs a suite against the result, and holds the production capture to resolving the production project rather
than whatever else runs out of `infra/`. That rehearsal captures a key file it owns, not the production volume. A
root capture from that volume ran once, for the restore rehearsal below, as root in a container that mounted the
volume. The `sudo -E` form above is derived, not rehearsed.

**Leg (c) never goes beside the dump.** The dump plus the key opens every tenant's SMTP password, so a capture
directory holding both is exactly the leaked backup the key exists to defend against (ADR 0056 §1). `capture`
therefore refuses a `--key-into` inside the capture directory, refuses to run with a key found and no destination
for it, and never overwrites an existing file there. `--key-beside-dump` puts it in the capture anyway — knowingly,
and the manifest records that it did. Everything the tool writes is owner-only (`0700` directories, `0600` files),
and `verify` checks it.

**Before a capture leaves this host, encrypt it, and keep the key on another medium.** The tool encrypts nothing:
the dump holds the members' emails and the password wraps an attacker can grind offline even without the key.
Pack the capture directory and encrypt the archive for a recipient whose private key is not on this host — for
example `tar -C infra/backups -c <stamp> | age -r <recipient> > veyq-<stamp>.tar.age` — and carry the
`--key-into` file on a different medium from that archive.

`--docker-network` is the network the pinned `rclone` container joins, and it must be one the store is on; list
them with `docker network ls`. An operator with their own `rclone` install can pass `--rclone-binary rclone` instead, and
one whose database is not this compose service passes `--db-host`/`--db-port` (the client then runs from `PATH`
if installed, else in the pinned `mariadb:11.4` image on `--docker-network`).

No credential ever reaches `argv`: the store remote is defined entirely through `RCLONE_CONFIG_*` and the
database password through `MYSQL_PWD`, both handed to the child as bare `-e NAME`. `rclone` logs
`NOTICE: Config file … not found - using defaults` on every call — that is normal, not an error, and no config
file is written.

**What `verify` tells you, and its one deliberate asymmetry.** An **object with no row** is an ordinary steady
state, and since ADR 0062 for a better reason: the row delete and the reclaim entry share one transaction, and a
single sweep drains that list, so a capture taken between the two legitimately holds objects whose rows are gone
— a reclaim *pending*, not a deletion that failed unnoticed. Orphans are therefore still counted and tolerated,
but a stuck one is now visible on the instance itself
(`SELECT count(*) FROM veyq_store_reclaim WHERE abandoned_at IS NOT NULL`). (Abandoned upload sessions
themselves are reclaimed, by `UploadSessionPurge`.) A **row with no object** is fatal (A4): it
restores as a Memory whose picture yields a valid presigned URL and a `404`, with no server-side error anywhere
— so `verify` names every missing object and exits non-zero. It exits `2`, with a different headline, when the
backup is structurally intact but carries no leg (c). A key kept apart is checked only when `--key-from` names it,
and `verify` says so when it did not.

### Restore — onto a freshly installed instance

A restore targets the **same domain** (ADR 0056 §4); `--domain` is required and the tool refuses anything the
backup did not hold. It also refuses a target that already holds **data**, or a bucket that already holds
objects — the restore is defined onto an empty instance and nothing reconciles two populated ones (A5).

**It compares the backup's schema with the target's before it writes anything** ([ADR 0056](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0056-instance-durable-state-and-cold-logical-backup.md) §7),
in Flyway's version order. A backup **newer** than the target is refused, and the message names the release it
came from: install that release (or a newer one) and restore onto it. A backup at the target's schema **or an
older one** is accepted: the restore first drops every table of the target — safe only because the gate above
proved it holds no data, and needed because the dump recreates only the tables it names — then loads the dump,
and the application migrates the data up at its next start, the same forward migration every upgrade runs. So
"install the backup's release, restore, upgrade" is "restore, start". When the backup is older the restore says
so, because that is an upgrade — and if you meant a rollback, it is not one ([Upgrade](/veyq/run/upgrade/)).

**Watch the log line, not `GET /api/instance/store-provisioning`.** That endpoint answers the Server Owner only.
A restore target has no Server Owner, and it must not get one: setup writes rows, and the emptiness gate then
refuses the target.

*Empty* means **holding no data**, not holding nothing. The target is a **normally installed instance**: bring
the stack up and let the app start once, which migrates the schema and provisions the store. There is no
shorter path and there is no manual `CREATE DATABASE` step — since ADR 0067 the app is the only thing that can
make the store usable, and the same start migrates the schema, so the two go together by construction.

```sh
# 1. A freshly installed instance: start the stack and let the app run once, then STOP the app.
#    That one start migrates the schema AND provisions the store (layout, bucket, key, grant, CORS).
docker compose -f infra/docker-compose.prod.yml up -d
docker compose -f infra/docker-compose.prod.yml logs -f app     # until it logs `Object store provisioned`
docker compose -f infra/docker-compose.prod.yml stop app

# 2. Load all three legs, with the credentials exported as for the capture — leg (c) from wherever you kept it.
sudo -E python3 scripts/instance_backup.py restore infra/backups/<stamp> \
    --domain <your domain> \
    --compose-file infra/docker-compose.prod.yml \
    --docker-network veyq_edge \
    --config-secret-file "$(docker volume inspect -f '{{.Mountpoint}}' veyq_veyq-config-secret)/config-secret.key" \
    --key-from /media/usb-stick/veyq-<stamp>.key
sudo stat -c '%u:%g %a' "$(docker volume inspect -f '{{.Mountpoint}}' veyq_veyq-config-secret)/config-secret.key"
#    → 10001:10001 600

# 3. Start the application, then VERIFY — this is the step that separates a restore from a hope.
docker compose -f infra/docker-compose.prod.yml start app
```

The restore takes the key from where the capture's manifest says it went. A capture that kept it apart is
**refused** without `--key-from`, before anything is written: restoring without leg (c) is A2 — an instance that
boots and refuses invites and password resets — and it happens only when you pass `--without-key` on purpose.

**The key must end up readable by the application, which runs as uid 10001** (the image's `USER`). The tool gives
the key the owner of the directory it lands in, and refuses — before anything is written — a run that cannot: a
key born `root:root 0600` in the app's volume is one the app cannot read, so it reports `ABSENT` and refuses
invites and resets with the key right there. On the production recipe leg (c) lives on the `veyq-config-secret`
volume, which Docker created owned by `10001`, so the restore runs as root, addressed the way the
[capture](#capture) addresses it; the `stat` above checks the result. Run without root against that path, the tool
refuses and says why. **This was rehearsed once, in containers rather than with `sudo` on a Linux host**
(cr-restore-places-the-key-as-the-runtime-user, 2026-10-15). The stack was this recipe on Docker Desktop's
Linux VM. The tool ran as root inside a container that mounted the docker socket and the `veyq-config-secret`
volume, and `--config-secret-file` pointed into that mount. A capture taken the same way was restored onto a
fresh install with no key yet. The key came out `10001:10001 600`, and the instance read `configSecretState: OK`.
The tool from before the fix, run the same way, gave `0:0 600` and `ABSENT`. The `sudo -E` form above has the same
owner on the same directory, so the tool takes the same branch, but that exact invocation is still derived. The
tool's owner rule is held by `scripts/instance_backup_selftest.py`, without Docker or root.

The store's **setting** now travels with the database: it is a row (`veyq_store_setting`, a table the
emptiness gate ignores), its secrets sealed under the config-secret key, so with leg (c) restored they open again.
A **bundled** setting is a reference to the hint of the install it runs on, so it works on the new install
whatever that install's Garage secrets are. The bundled store's own bucket, access key and single-node layout are
still not restored, because they were never state: the app recreates them idempotently on every start. This has no
"fresh node only" limit — the layout step is satisfied by a node already carrying a role at any version, and
applies `current + 1` where it has work to do. A store of your own is the same store after the restore (its
endpoint and bucket are in the restored setting, and they cannot be changed once blobs exist), so the objects go back
into that bucket. The restore refuses a bucket that still holds objects, so empty it first if it outlived the
instance. If the store's credentials were rotated since the capture, re-enter them in **Server → Object store**.

The final step is not "it started". Open **Server → Object store** and run **Check store** (or `veyq-admin store check`): it
proves the restored setting reaches its store before anyone relies on it. Then read the instance's **config-secret state** as the Server Owner, on the admin
channel — the admin console's **Server → Config-secret key**, or `veyq-admin config-secret` — and expect `OK`. This
read changes nothing on the instance. A tenant's mail settings carry the same state, but only that tenant's owner may
read them. Then have a person sign in and **open a Memory that contains media**. That is the only assertion that
touches all three legs at once: the row, the object and the key are three different failures with the same symptom
of *"it looked fine"*.

### Restore under Helm

The mirror of the capture above: the tool reaches MariaDB and Garage through port-forwards and cannot reach the
app's claim, so it writes leg (c) to a local file of your own, and `kubectl cp` puts it in the pod. `kubectl cp`
unpacks as the container's user, uid 10001, so the key is born the app's — the property the compose restore gets
from running as root. Onto a **freshly installed** release, exactly as [Restore](/veyq/run/backup-and-restore/#restore--onto-a-freshly-installed-instance) requires:

```sh
kubectl -n veyq scale deploy/veyq-app --replicas=0      # 1. stop the application
# 2. port-forward MariaDB and Garage as for the capture; export VEYQ_BACKUP_DB_PASSWORD
#    (= secrets.mariadbRootPassword) and VEYQ_BUNDLED_STORE_ADMIN_TOKEN (= secrets.garageAdminToken), and point
#    VEYQ_BUNDLED_STORE_ENDPOINT at the forwarded Garage S3 port; then the Restore command, with
#    --config-secret-file ./veyq-config-secret.key --key-from /media/usb-stick/veyq-<stamp>.key
#    (a file you own, so the tool writes it 0600 as you)
kubectl -n veyq scale deploy/veyq-app --replicas=1      # 3. the pod mounts the claim again
kubectl -n veyq rollout status deploy/veyq-app
kubectl -n veyq cp ./veyq-config-secret.key "$(kubectl -n veyq get pod -l app.kubernetes.io/component=app \
  -o jsonpath='{.items[0].metadata.name}')":/var/lib/veyq/config-secret.key   # 4. leg (c) in
rm ./veyq-config-secret.key
kubectl -n veyq rollout restart deploy/veyq-app         # 5. NOT optional — see below
# 6. verify as Restore says: veyq-admin config-secret reads OK, then a person opens a Memory with media
```

The restart is part of the procedure: anything that touched the seal between step 3 and step 4 holds the fresh
install's key in memory, and the instance would report `MISMATCH` until it restarts. `kubectl cp` needs `tar` in
the container — the app image (`runtime-jvm`, the Dockerfile's only runtime target) has it (measured
2026-10-01). **Derived, not rehearsed**: no cluster has run it.

### The volume-copy fast path — and what it is not

For a **same-host, same-version** rollback you can stop everything and copy the named volumes
(`veyq_veyq-mariadb-data`, `veyq_veyq-garage-meta`, `veyq_veyq-garage-data`) plus the config-secret key file.
It is faster and needs no credentials. It is **not** the procedure the durability bar is measured against: a
volume copy is opaque, so a complete one and a truncated one look identical until you restore them, and it binds
the backup to one store's on-disk format and version.

Garage can snapshot its metadata database consistently while running:

```sh
docker compose -f infra/docker-compose.prod.yml exec garage /garage meta snapshot
```

There is **no** equivalent for its data blocks (`garage block --help` offers `info | list-errors | purge |
retry-now` and no snapshot), which is the second reason this path is a rollback aid and not the procedure.
`garage.toml` sets `metadata_snapshots_dir` to its own named volume (`veyq_veyq-garage-snapshots`) because the
default writes snapshots **inside** `metadata_dir` — i.e. into the very volume the fast path copies, so each
snapshot grows the thing being snapshotted. An instance created before that setting still carries the old
snapshots under `meta/snapshots/`; they are safe to delete once. No rehearsal of this path is recorded.
