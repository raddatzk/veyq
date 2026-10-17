---
title: Backup and restore
description: Capture an instance's three legs cold, check the capture, and restore it onto a fresh instance.
sources:
  - docs/features/admin/use-cases/uc.admin.back-up-and-restore-instance.md
  - docs/decisions/0056-instance-durable-state-and-cold-logical-backup.md
  - docs/decisions/0096-veyqctl-manages-an-instance-from-immutable-releases.md
  - docs/decisions/0098-on-kubernetes-the-start-that-would-migrate-backs-up-first.md
---

The use-case owns the goal and the failure modes
([uc.admin.back-up-and-restore-instance](https://github.com/raddatzk/veyq/blob/main/docs/features/admin/use-cases/uc.admin.back-up-and-restore-instance.md));
[ADR 0056](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0056-instance-durable-state-and-cold-logical-backup.md) owns the decisions. This
page owns the **commands**. On Docker Compose they are `veyqctl backup` and `veyqctl restore`, which run the running
release's own `instance_backup.py` — an asset of the release, checked against its manifest before it runs, whose
module docstring carries the tool's reference. Each block below also shows what `veyqctl` runs, so it can be done by
hand.

**An instance's durable state is exactly three legs:** (a) the database, (b) the object store's *contents*,
(c) the **config-secret key file**. Two legs produce an instance that boots, serves content, and refuses member
invites and password resets with `503 mail_unavailable` — the leg that gets forgotten is the one whose absence
looks like health.

**The capture is cold, and *cold* means the writer is stopped — not the stores.** Stop the application; leave
MariaDB and Garage running, because the capture reads through their own interfaces. That single act is the whole
consistency argument: with the app down the database gains no rows, so a row whose object was never captured is
structurally impossible and there is no ordering to remember. `instance_backup.py` cannot check this and does not
pretend to; `veyqctl` stops the application itself, so a step by hand is the only place it can be forgotten.

### Capture

```sh
cd /opt/veyq
./veyqctl backup --key-into /media/usb-stick/veyq-<stamp>.key
```

It stops the application, captures legs (a) and (b) into `backups/<UTC stamp>/` and leg (c) to the file
`--key-into` names, checks the capture, and starts the application again — also when the capture failed. The
downtime is the capture, and nothing else. `upgrade` takes the same capture first. By hand, what it runs:

```sh
cd /opt/veyq
release=<running release>               # state.json's "release"
recipe="releases/${release}/docker-compose.prod.yml"   # add -f compose.override.yml below if you have one

# 1. STOP the application, and only it. MariaDB and Garage keep running.
docker compose -f "$recipe" --env-file .env stop app

# 2. Credentials, under the names the tool reads: the two values come from .env. The endpoint is the one the
#    rclone container sees on the store's network, not a published port. The bundled store: its admin token; the
#    tool derives the S3 key pair from it, so nothing else is typed. COMPOSE_ENV_FILES hands the tool's own
#    `docker compose exec` this instance's .env.
read -rs VEYQ_BACKUP_DB_PASSWORD && export VEYQ_BACKUP_DB_PASSWORD              # = MARIADB_ROOT_PASSWORD
read -rs VEYQ_BUNDLED_STORE_ADMIN_TOKEN && export VEYQ_BUNDLED_STORE_ADMIN_TOKEN  # = GARAGE_ADMIN_TOKEN
export VEYQ_BUNDLED_STORE_ENDPOINT=http://garage:3900
export COMPOSE_ENV_FILES=/opt/veyq/.env

# 3. Leg (c) out of the stopped app container, into a directory only you can read.
(umask 077; mkdir .key)
docker cp "$(docker compose -f "$recipe" --env-file .env ps -aq app)":/var/lib/veyq/config-secret.key \
  .key/config-secret.key

# 4. Capture legs (a) and (b) into backups/<stamp>/, and leg (c) to a medium of its own; then drop the copy.
python3 "releases/${release}/instance_backup.py" capture --compose-file "$recipe" \
    --docker-network veyq_edge --config-secret-file .key/config-secret.key \
    --key-into /media/usb-stick/veyq-<stamp>.key --into backups/<stamp>
rm -r .key

# 5. CHECK both — the store leg is captured logically precisely so this is possible at all.
python3 "releases/${release}/instance_backup.py" verify backups/<stamp> --key-from /media/usb-stick/veyq-<stamp>.key

# 6. Start the application again.
docker compose -f "$recipe" --env-file .env start app
```

**A store of your own.** The store is a setting in the admin console, not an environment variable, and the capture is
cold — no app is running to sign for the tool — so you give the tool the store's credentials for this run, the ones you
entered in the console (the console never shows them again, and neither can the tool read them back): export
`VEYQ_BACKUP_STORE_ENDPOINT`, `VEYQ_BACKUP_STORE_ACCESS_KEY` and `VEYQ_BACKUP_STORE_SECRET_KEY` instead of the
bundled pair, plus `VEYQ_BACKUP_STORE_BUCKET` and `VEYQ_BACKUP_STORE_REGION` when they are not `blobs` and
`us-east-1`. Keep them in a password manager: after a lost config-secret key the console asks for the same
credentials again.

The network is `veyq_edge`, the one the store is on: the production compose file declares `name: veyq`, so that is
the project. Leg (c) lives on a **volume**, under Docker's data root, which a host cannot always read (Docker Desktop
keeps it in a VM) and which only root can read where it can; `docker cp` takes it out of the stopped container
instead, so **no step needs root** — a user who may use Docker runs all of it. The key is copied to a destination of
its own, never into the capture.

**How far this is proven.** `veyqctl upgrade` ran this capture once, live, on an instance it had installed from a
release produced from the tree — three legs, the key apart — and the capture was restored onto a fresh install.
`veyqctl backup` is the same capture with the application started again after it; it has not run on its own. Every
`scripts/run_web_e2e.py` run captures a live instance, destroys it, restores it and runs a suite against the result,
and holds the production capture to resolving the production project rather than whatever else runs.

**Leg (c) never goes beside the dump.** The dump plus the key opens every tenant's SMTP password, so a capture
directory holding both is exactly the leaked backup the key exists to defend against (ADR 0056 §1). `capture`
therefore refuses a `--key-into` inside the capture directory, refuses to run with a key found and no destination
for it, and never overwrites an existing file there. `--key-beside-dump` puts it in the capture anyway — knowingly,
and the manifest records that it did. Everything the tool writes is owner-only (`0700` directories, `0600` files),
and `verify` checks it.

**Before a capture leaves this host, encrypt it, and keep the key on another medium.** The tool encrypts nothing:
the dump holds the members' emails and the password wraps an attacker can grind offline even without the key.
Pack the capture directory and encrypt the archive for a recipient whose private key is not on this host — for
example `tar -C /opt/veyq/backups -c <stamp> | age -r <recipient> > veyq-<stamp>.tar.age` — and carry the
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

**Watch the log line, not `GET /api/instance/store-provisioning`:** `./veyqctl logs app` shows
`Object store provisioned` once the store is usable. The endpoint answers the Server Owner only.
A restore target has no Server Owner, and it must not get one: setup writes rows, and the emptiness gate then
refuses the target.

*Empty* means **holding no data**, not holding nothing. The target is a **normally installed instance** —
`veyqctl install` of the release the capture came from (or a newer one), with no first-run setup: it brings the
stack up and waits until the app is healthy, and that one start migrates the schema and provisions the store. There is
no shorter path and there is no manual `CREATE DATABASE` step — since ADR 0067 the app is the only thing that can make
the store usable, and the same start migrates the schema, so the two go together by construction.

```sh
cd /opt/veyq
./veyqctl restore /media/restore/<stamp> --domain <the tenant's domain> --key-from /media/usb-stick/veyq-<stamp>.key
```

It stops the application, loads all three legs with the running release's tool, puts leg (c) into the app's volume as
the application's own user, and starts the application again. `--domain` is required and never defaulted: the
capture holds its **tenants'** domains, and `.env`'s `VEYQ_DOMAIN` is the instance's bootstrap domain, not one of
them. A restore the tool refuses at its gates wrote nothing, and the application stays stopped until `veyqctl start`.
By hand, after the capture's steps 2 and the `recipe` line above:

```sh
# 1. STOP the application of the freshly installed instance.
docker compose -f "$recipe" --env-file .env stop app

# 2. Load all three legs; the tool writes leg (c) to a file you own.
(umask 077; mkdir .key)
python3 "releases/${release}/instance_backup.py" restore /media/restore/<stamp> \
    --domain <the tenant's domain> --compose-file "$recipe" --docker-network veyq_edge \
    --config-secret-file .key/config-secret.key --key-from /media/usb-stick/veyq-<stamp>.key

# 3. Leg (c) into the app's volume, written by the image's own user, the key on stdin; then drop the copy.
docker compose -f "$recipe" --env-file .env run --rm --no-deps -T --entrypoint /bin/sh app \
  -c 'umask 077 && cat > "$1.veyqctl" && mv -f "$1.veyqctl" "$1"' place-key /var/lib/veyq/config-secret.key \
  < .key/config-secret.key
rm -r .key

# 4. Start the application, then VERIFY — this is the step that separates a restore from a hope.
docker compose -f "$recipe" --env-file .env up -d --wait
```

The restore takes the key from where the capture's manifest says it went. A capture that kept it apart is
**refused** without `--key-from`, before anything is written: restoring without leg (c) is A2 — an instance that
boots and refuses invites and password resets — and it happens only when you pass `--without-key` on purpose.

**The key must end up readable by the application, which runs as uid 10001** (the image's `USER`). A key born
`root:root 0600` in the app's volume is one the app cannot read, so it reports `ABSENT` and refuses invites and
resets with the key right there. Step 3 writes it through a one-off container of the `app` service, which runs as the
image's own user, so the key is born `10001:10001 0600` without root and without a path into Docker's data root. (The
tool, given a `--config-secret-file` inside the volume itself, gives the key the owner of the directory it lands in
and refuses a run that cannot — the route for an operator who mounts the volume as root.) **How far this is proven:**
`veyqctl restore` ran once, live, putting an upgrade's capture onto a fresh install: 46 tables dropped, the dump and
4 objects loaded, the key in the app's volume as `10001:10001 600`, and a Memory opened there. The tool's owner rule
is held by `scripts/instance_backup_selftest.py`, without Docker or root.

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

### Under Kubernetes

On a cluster the chart's **backup gate** captures before every release change, in the app pod before the container
that migrates ([Upgrade](/veyq/run/upgrade/),
[ADR 0098](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0098-on-kubernetes-the-start-that-would-migrate-backs-up-first.md)).
Your part:

- **The claim.** `backup.existingClaim` names a PersistentVolumeClaim you create in the release's namespace
  (`ReadWriteOnce` is enough). The chart creates none, so `helm uninstall` leaves the captures behind, and its size,
  class and encryption at rest are yours. The gate writes `captures/<UTC>/` there, beside its record `state.json`;
  a capture interrupted midway stays as `captures/<UTC>.partial/` until the next complete one removes it.
  `backup.keep` (3) complete captures are kept, the newest never pruned. A full claim fails the gate and keeps the
  app down; it is never pruned to make room.
- **A capture without a release change** is a commit: change `backup.request` to any new value. That is a new
  revision of the pod, so the old pod stops first and the capture is cold. The gate never captures because one is
  "due".
- **What a capture holds**: the database dump and the store's **index** — every object's key, size and MD5 — never
  the objects and never the key. Two things are therefore yours:
  - **The store keeps its objects.** Turn on its versioning, replication or snapshots (for the bundled Garage, of its
    data claim). A restore checks that every object the database needs is still in the store and refuses otherwise,
    but it cannot bring back one the store lost.
  - **The key, exported once**, after the first start, onto a medium of its own. It does not change between
    upgrades, and the gate never reads it:

    ```sh
    (umask 077; kubectl -n veyq cp -c app "$(kubectl -n veyq get pod \
      -l app.kubernetes.io/name=veyq,app.kubernetes.io/component=app -o jsonpath='{.items[0].metadata.name}')":/var/lib/veyq/config-secret.key \
      ./veyq-config-secret.key)
    # move it to its medium, then delete ./veyq-config-secret.key
    ```
- **A store of your own.** The gate cannot open its secret key (that would need the key above), so name a Secret
  with `endpoint`, `bucket`, `accessKey` and `secretKey` (optionally `region`) in `backup.store.existingSecret`; a key
  pair that can list the bucket is enough. The gate refuses when the instance uses its own store and none is named,
  or when the Secret's endpoint or bucket is not the store in use.
- **The downtime.** Every release change is longer by the capture: one dump and one listing of the bucket,
  whatever the store holds.

With `backup.enabled: false` none of this runs, and the capture before an upgrade is yours, by hand
([Upgrade](/veyq/run/upgrade/), Kubernetes).

### Restore under Helm

Never automatic and never a hook: a one-off **Job**, run with the tools image of the release that **wrote** the
capture — its `manifest.json` names it as `capture.tool`; the gate writes a capture when the new release starts, so
that is the one image certain to read it. A capture of the gate leaves the objects in the store, so the Job writes
nothing there: before it loads anything it checks that every object the database needs is in the store with the
size it had, and refuses naming the ones that are missing. Bring those back from the store's versioning and run it
again — or add `--accept-missing-objects` to restore knowingly without them, each a Memory whose picture no longer
loads.

The Job's pod carries the app pod's labels, because the NetworkPolicy set admits the app alone to the database and
the store; the app is scaled to zero meanwhile. It runs as the app's uid and writes the key as that uid
(`--key-as-runtime-user`: a claim's directory is root-owned, `fsGroup` only gives it a group). The key reaches it
from your medium through a Secret that exists for the Job's lifetime only — it is in the cluster's store while it
exists, so create it just before and delete it just after. To roll back to `<release>` from the capture `<capture>`
the gate took when that release was left:

```sh
# 0. Argo CD: pause the automated sync first (spec.syncPolicy.automated.enabled: false) — with self-heal on it
#    puts the app back while you work. Flux: suspend the HelmRelease.
kubectl -n veyq scale deploy/veyq-app --replicas=0      # 1. stop the application
kubectl -n veyq exec veyq-mariadb-0 -- sh -c \
  'mariadb -uroot -p"$MARIADB_ROOT_PASSWORD" -e "DROP DATABASE veyq; CREATE DATABASE veyq; GRANT ALL PRIVILEGES ON veyq.* TO '"'"'veyq'"'"'@'"'"'%'"'"';"'
#    2. the database emptied (your own database: empty it your way) — a restore is defined onto no data
helm upgrade veyq "oci://ghcr.io/raddatzk/charts/veyq@${chart_digest}" -n veyq -f my-values.yaml \
  --set image.digest="${app_digest}" --set admin.image.digest="${admin_digest}" \
  --set backup.image.digest="${tools_digest}" --wait --timeout 15m
#    3. <release> started once, by its own chart and digests: the gate finds no data and records it, the app
#       migrates the empty schema and provisions the store
kubectl -n veyq scale deploy/veyq-app --replicas=0      # 4. stopped again
kubectl -n veyq create secret generic veyq-restore-key \
  --from-file=config-secret.key=/media/usb-stick/veyq-<stamp>.key   # 5. the key, for the Job's lifetime
kubectl -n veyq apply -f veyq-restore.yaml              # 6. the Job below
kubectl -n veyq wait --for=condition=complete job/veyq-restore --timeout=15m
kubectl -n veyq logs job/veyq-restore
kubectl -n veyq delete job veyq-restore; kubectl -n veyq delete secret veyq-restore-key   # 7. both gone
kubectl -n veyq scale deploy/veyq-app --replicas=1      # 8. the gate finds <release> in its record and passes
# 9. resume the sync; verify as Restore says: veyq-admin config-secret reads OK, the store check passes, and a
#    person opens a Memory with media
```

`veyq-restore.yaml` — the release name `veyq`, the chart's Secret `veyq` (or yours, `secrets.existingSecret`), the
bundled store and database; for a store of your own, the four `VEYQ_BACKUP_STORE_*` variables from your Secret
instead of the token:

```yaml
apiVersion: batch/v1
kind: Job
metadata:
  name: veyq-restore
  namespace: veyq
spec:
  backoffLimit: 0
  template:
    metadata:
      labels:            # the app pod's, for the NetworkPolicy set; the Job owns the pod
        app.kubernetes.io/name: veyq
        app.kubernetes.io/instance: veyq
        app.kubernetes.io/component: app
    spec:
      restartPolicy: Never
      securityContext:
        runAsUser: 10001
        runAsGroup: 10001
        fsGroup: 10001
        runAsNonRoot: true
        seccompProfile: {type: RuntimeDefault}
      containers:
        - name: restore
          image: ghcr.io/raddatzk/veyq-tools:<capture.tool>@<its verified digest>
          args: ["restore", "/backup/captures/<capture>", "--domain", "<your domain>",
                 "--key-from", "/restore-key/config-secret.key",
                 "--config-secret-file", "/var/lib/veyq/config-secret.key", "--key-as-runtime-user",
                 "--record", "/backup"]
          env:
            - {name: VEYQ_BACKUP_DB_URL, value: "jdbc:mariadb://veyq-mariadb:3306/veyq"}
            - {name: VEYQ_BACKUP_DB_USER, value: veyq}
            - name: VEYQ_BACKUP_DB_PASSWORD
              valueFrom: {secretKeyRef: {name: veyq, key: mariadbPassword}}
            - {name: VEYQ_BUNDLED_STORE_ENDPOINT, value: "http://veyq-garage:3900"}
            - name: VEYQ_BUNDLED_STORE_ADMIN_TOKEN
              valueFrom: {secretKeyRef: {name: veyq, key: garageAdminToken}}
          securityContext:
            allowPrivilegeEscalation: false
            capabilities: {drop: ["ALL"]}
            readOnlyRootFilesystem: true
          volumeMounts:
            - {name: backup, mountPath: /backup}
            - {name: config-secret, mountPath: /var/lib/veyq}
            - {name: restore-key, mountPath: /restore-key, readOnly: true}
            - {name: tmp, mountPath: /tmp}
      volumes:
        - name: backup
          persistentVolumeClaim: {claimName: <backup.existingClaim>}
        - name: config-secret
          persistentVolumeClaim: {claimName: veyq-config-secret}
        - name: restore-key
          secret: {secretName: veyq-restore-key, defaultMode: 0440}
        - name: tmp
          emptyDir: {}
```

`--record /backup` is the Job's last step: it writes the backup's release into the gate's record, so the next start
of that release passes instead of capturing the restored database again or refusing it as a downgrade. The config
secret is read last, as on Compose. **Run once on a kind cluster** (2026-10-17): this Job, this order and these
commands, with the images loaded by tag rather than pulled by digest; the database and the store were the chart's
own.

#### A capture taken by hand

A capture taken by hand with `backup.enabled: false` holds the objects themselves, and restores through
port-forwards. The tool reaches MariaDB and Garage through port-forwards and cannot reach the app's claim, so it
writes leg (c) to a local file of your own, and `kubectl cp` puts it in the pod. `kubectl cp` unpacks as the
container's user, uid 10001, so the key is born the app's — the property the Compose restore gets from its one-off
container of the `app` service. Onto a **freshly installed** release, exactly as [Restore](/veyq/run/backup-and-restore/#restore--onto-a-freshly-installed-instance) requires:

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
docker compose -f "releases/<release>/docker-compose.prod.yml" --env-file .env exec garage /garage meta snapshot
```

There is **no** equivalent for its data blocks (`garage block --help` offers `info | list-errors | purge |
retry-now` and no snapshot), which is the second reason this path is a rollback aid and not the procedure.
`garage.toml` sets `metadata_snapshots_dir` to its own named volume (`veyq_veyq-garage-snapshots`) because the
default writes snapshots **inside** `metadata_dir` — i.e. into the very volume the fast path copies, so each
snapshot grows the thing being snapshotted. An instance created before that setting still carries the old
snapshots under `meta/snapshots/`; they are safe to delete once. No rehearsal of this path is recorded.
