# ADR 0098 — On Kubernetes, the start that would migrate backs up first

- Status: Accepted
- Built: yes
- Date: 2026-10-16
- Model: [release-and-deployment](../crosscutting/release-and-deployment.md)

## Context

A GitOps deploy of a new release — Argo CD syncing a bumped chart — should always take a backup first, into a volume
the operator provides. Under Compose that order is `veyqctl upgrade`'s
([ADR 0096](0096-veyqctl-manages-an-instance-from-immutable-releases.md) decision 5); decision 8 there leaves
Kubernetes to `helm upgrade` and the operator's tooling, where a hand procedure (copy the key, scale to zero, capture
through port-forwards) is one a GitOps deploy never runs. This is the Kubernetes backup decision
[ADR 0064](0064-kubernetes-helm-deployment-target.md) defers. Five facts constrain it:

**1. Helm hooks are lifecycle events, and every GitOps tool reads them differently.** Helm runs a `pre-upgrade` Job
before any resource is updated and fails the release on its failure, but `--no-hooks` skips it and `--timeout`
(default 5m) bounds it. Argo CD renders with `helm template` and maps `pre-install` **and** `pre-upgrade` to one
`PreSync` phase, because it cannot tell an install from an upgrade; hooks do not run in a selective sync, and an
automated sync runs whenever the application is OutOfSync, not only when the release changed. Flux runs real Helm
actions, bounds hooks by a 5-minute default, and remediates a failed upgrade by rollback. So a hook is not "before
every new release": it is "before every sync" under Argo, "unless skipped" under Helm and Argo, and "within five
minutes" under Helm and Flux.

**2. The app pod is already replaced with nothing in between.** It is one pod under `strategy: Recreate` (ADR 0064),
and for an *upgrade* Kubernetes terminates every pod of the old revision before it creates one of the new — not when a
pod is merely deleted, whose replacement may start beside it. Between the two, the writer is stopped: exactly ADR 0056
§2's *cold*.

**3. The config-secret key is not a Kubernetes `Secret`.** The app generates it once onto its own claim, which only the
app pod mounts, and it is never rotated ([ADR 0050](0050-operator-config-secret-encryption.md)). It is the one leg that
does not change between upgrades.

**4. The chart's security shape is narrow on purpose.** The backend's Role reaches only `httproutes` and
`listenersets`; the NetworkPolicy set is default-deny, the database admits the app pod alone and the store's S3 port the
gateway and the app pod alone. A component that dials the database or the store needs a policy change; one that scales
the app needs a Role of its own.

**5. The capture tool exists and is Compose-shaped.** `instance_backup.py` captures the three legs and refuses a
populated restore target, reaching the database and the store through Docker or binaries on `PATH`, and reading the
running release through Docker. It ships as a release asset beside `veyqctl`, in no image.

## Decision

**1. The backup runs as a gate in the app pod, before the container that migrates — not as a Helm hook.** An init
container, `backup-gate`, runs before `app` in the same pod. Because the pod belongs to the new revision of a
`Recreate` Deployment, the old app has terminated before the gate starts (Context 2): the capture is cold by
construction, with no scale-down, no Role and no ordering to get right. Because the gate is part of the Deployment
rather than of a lifecycle event, it runs the same under `helm upgrade`, Argo CD, Flux and a plain
`helm template | kubectl apply`, and nothing skips it: not `--no-hooks`, not a selective sync, not an operator who
applies the manifests by hand (Context 1). It shares the app pod's network identity, so the NetworkPolicy set gains no
peer (Context 4). The price: a failed gate is an outage (decision 6), paid only on a release change the operator is
watching anyway.

**2. The gate captures when, and only when, the pod is about to start a different release.** It keeps one record on
the backup volume, `state.json`: the release whose data the database holds, and the last capture request it served
(decision 7). On each start:
- the database holds no data → nothing to capture; the record is written with the target release — the first install;
- the target release equals the record and no new request is pending → nothing happens: a restart, a rescheduled pod,
  an Argo sync that changed nothing all pass in a second;
- the target differs from the record → decision 3's checks, then a capture, then the record moves to the target. It
  moves when the capture succeeds, not when the migration does: from there on the new release migrates, and the way
  back is that capture (ADR 0056 §6);
- no record but data (a new volume, a restored database) → a capture, saying the running release is unknown.

The target release is the app image's tag, rendered into the gate's environment by the chart; the release a capture
names as `app.version` is the record's, since there is no Docker to ask (Context 5).

**3. Before capturing, the gate refuses what no tested path covers — and writes nothing.**
- **A target older than the record** — a downgrade. The previous release refuses to start on a schema history holding
  a migration it does not know (M9), and the gate cannot tell whether this one holds such a migration. The refusal
  names the capture to restore instead (ADR 0056 §7: a rollback is the backup's release plus a restore).
- **A target more than one major ahead** — ADR 0096 decision 5's *no run crosses more than one major*.
- **A major entered from anything but the release it was tested from.** The gate knows that release offline: each
  release carries the release it enters its major from, baked into its tools image. It never asks the release source,
  which would make a deploy depend on GitHub being reachable.

**4. The legs.** (a) The database is dumped into the capture. (b) **The store leg is its index only**: the object keys
with their sizes and MD5s, read through the S3 API in one listing, never the objects. An upgrade migrates the schema;
it does not rewrite an object — every object is an immutable ciphertext blob — so the database plus the index is the
whole rollback point. Before a restore writes, the index is checked against the live store: every object the dump
references must still be there. Keeping the objects is the store's own promise (its versioning, replication or
snapshots), the operator's part; this gate is an upgrade's way back, not a disaster backup of the store. This is every
target's store leg ([ADR 0056](0056-instance-durable-state-and-cold-logical-backup.md) §3). **The optional object pool
(`--objects-to`) does not apply to the gate**: a copy in an init container lengthens every release change by the new
objects, and a pool outside the cluster is a new egress peer for the default-deny set (Context 4). A restore Job may
still name `--objects-from`, only into an empty store (ADR 0056 §7). (c) **The gate never touches the key.** It does
not mount the key's claim, so a capture cannot hold the KEK, and ADR 0056 §1's *never beside the dump* holds by
construction. The key, which does not change between upgrades (Context 3), is kept on its claim and on the operator's
own medium, exported once after the first start; the manifest records leg (c) as `separate`, so a restore asks for it.

**5. The volume is the operator's.** `backup.existingClaim` names a claim the operator created; the chart creates none,
so `helm uninstall` cannot take the backups with it, and size, class and encryption at rest are the operator's. **The
backup is an explicit choice**: the schema refuses an install that neither names a claim nor says
`backup.enabled: false` — the backup is the only way back (ADR 0056 §6), and the chart does not guess a size or a
class. A capture is written to a `.partial` directory and renamed when complete; after a complete capture the gate
prunes beyond `backup.keep` (default 3) and every leftover `.partial`, and at every start every capture older than
ADR 0056 decision 8's bound (a daily CronJob on the same image and claim prunes between starts). A full volume fails
the gate — it never prunes to make room.

**6. A failed or refused gate keeps the app from starting, and that is the failure mode.** Nothing has migrated, the
record has not moved, and the old pod is already gone: the instance is down until the operator acts, with its data
untouched. Pinning the previous release back starts it again — the record still names it. Kubernetes retries a failed
init container with backoff; the gate is idempotent. Who sees it: `helm upgrade` only with `--wait` (its default waits
for hooks alone); Argo CD as a progressing application past the Deployment's progress deadline; Flux within its
timeout, past which remediation rolls back and ends the gate mid-capture — the `.partial` is swept on the next start.
The Run area tells the operator to set those timeouts above the capture time.

**7. A capture can also be asked for, the GitOps way.** `backup.request` is an opaque value rendered into the pod
template; changing it in git is a new revision, so Kubernetes stops the old pod first (Context 2), and the gate
captures because the request differs from the record's. A scheduled backup is then a commit, by a person or a bot. The
gate never captures because one is "due": a pod replaced outside a revision change has no termination guarantee, so a
due capture could be a hot one.

**8. The gate runs `instance_backup.py`, the one capture implementation**, in a third released image, `veyq-tools`:
Python, the MariaDB client and `rclone`, pinned, released and signed with the other two
([ADR 0057](0057-release-identity-and-deployment-recipe.md)). The tool gains a mode for running inside the pod
(database and store by address, no Docker); its format is the one every capture writes (ADR 0056 §3), so a capture
taken in a cluster restores onto Compose and back (same domain, ADR 0056 §4) — onto the other target's store only with
its objects, from a pool (ADR 0056 §7).

**9. Restore is never automatic and never a hook.** It is a documented one-off Job, run with the tools image of the
release that **wrote** the capture (its manifest names it) — the one image certain to read it. The Job's pod carries the
app pod's labels, so the NetworkPolicy set admits it as it admits the app, which is scaled to zero meanwhile (by hand;
an operator under Argo pauses its automated sync first); it places the key as the app's uid. The operator empties the
database (a populated target is refused) and supplies the key from their medium. The Job ends by writing the record
with the backup's `app.version`, so the next start of that release passes the gate; ADR 0056 §5's config-secret read
stays the last step.

**10. Compose keeps `veyqctl`.** Its upgrade already backs up first (ADR 0096 decision 5); a second gate there would
take two backups per upgrade. ADR 0096 decision 8 stands: no in-cluster operator, and nothing here installs a CRD or a
cluster-scoped object.

## Consequences

- **Every upgrade's downtime grows by the capture**, on top of `Recreate`'s restart: a database dump plus one listing
  of the bucket — independent of how much the store holds.
- **A disaster backup of the store is the operator's**, not the gate's: an instance whose store has no versioning,
  replication or snapshot loses its objects with the store, whatever the gate captured.
- **The backup volume holds ciphertext and opaque metadata**, never the KEK — operator-blindness is unchanged: the gate
  reads what the app already reads, and less.
- **ADR 0056's guard gap narrows, not closes**: the cluster backup is the gate's, but that a key export happened is
  still the operator's to know; the fingerprint tripwire remains the only detection of a lost key.
- **No lane boots the chart**: ADR 0091's lanes drive Compose through `veyqctl`; a cluster lane is inbox #430's.

## Rejected alternatives

- **A Helm `pre-upgrade` hook Job** — the owner's starting shape. It needs its own ServiceAccount and Role (read the
  Deployment, scale it), a new NetworkPolicy peer on the database and the store, the same release detection (Argo runs
  it on every sync and on install), a scale-back on its own failure, and a raised `--timeout`; Helm does not track the
  Job as part of the release, and it can be skipped. Its one real advantage — a refusal leaves the old app serving and
  `helm upgrade` fails visibly — does not outweigh being the only shape that every tool runs differently. Hook and gate
  together has the costs of both.
- **The backup off by default**, or **on with a claim the chart creates**: off leaves the only way back to a warning;
  a created claim guesses a size and a class.
- **Separate init containers from the pinned `mariadb` and `rclone` images**, or **the tools in the admin image**: the
  first splits the code ADR 0056 wants in one place; the second puts a database client into the localhost-only admin
  surface.
- **A hot capture, leaving the old app running.** Gives up ADR 0056 §2's whole consistency argument for minutes a
  household instance does not need.
- **Copying the key into the capture, or a `Secret` for it.** The first is the leaked backup ADR 0056 §1 exists to
  prevent; the second would need the chart or the backend to write `Secret`s, the RBAC ADR 0064 avoids.
- **A volume snapshot (`VolumeSnapshot`, Velero) as the procedure.** Storage-class-bound and unverifiable, the reasons
  ADR 0056 §3 rejects a volume copy; an operator may still take one beside the gate.
- **A scheduled `CronJob` that scales the app down and captures.** It needs a Role to scale, and under Argo CD's
  self-heal an out-of-band scale-down is reverted within seconds — a capture that starts cold and ends hot. Decision
  7's request is the scheduled form.

## Measurement ledger

Two measurements other documents cite; the rest of this decision's sources were the Helm, Argo CD, Flux and Kubernetes
documentation of its date.

- **M9** Flyway refuses an older release on a newer schema: Quarkus hands Flyway an empty `ignoreMigrationPatterns`
  unless `quarkus.flyway.ignore-future-migrations` or the patterns are set, and nothing in the backend sets either. An
  image four migrations older, started on the newer schema, stopped with `FlywayValidateException: Detected applied
  migration not resolved locally`.
- **M13** One run on a kind cluster (a `restricted` namespace): the gate's first attempt, before the database was up,
  failed and was retried with backoff; an install on an empty database recorded the release; an upgrade captured, the
  old pod terminating before the new one was scheduled; a restart captured nothing; a downgrade was refused and
  `helm upgrade --wait` failed; pinning the release back started it; the documented rollback ran the restore Job with
  the capture's tool image; a changed `backup.request` captured, and the fourth capture pruned the oldest. A restore
  Job without the app pod's labels could not reach the database through the NetworkPolicy set.

## Related

- Run area of the docs site: [Upgrade](../../site/src/content/docs/run/upgrade.mdx) (the Kubernetes tab: the gate and
  the `--wait`/timeout advice), [Backup and restore](../../site/src/content/docs/run/backup-and-restore.md) (§ Under
  Kubernetes, the restore Job), [Kubernetes](../../site/src/content/docs/run/kubernetes.md),
  [Plan your instance](../../site/src/content/docs/run/plan.md)
