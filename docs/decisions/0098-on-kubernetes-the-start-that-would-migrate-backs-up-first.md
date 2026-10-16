# ADR 0098 — On Kubernetes, the start that would migrate backs up first

- Status: Accepted 2026-10-16 (owner: every recommendation of the former § Open for the owner taken, and the store
  leg is its index only); nothing of it is built. It is the Kubernetes-native backup decision
  [ADR 0064](0064-kubernetes-helm-deployment-target.md) defers; the owner's starting idea was a Helm `pre-upgrade` hook,
  and this ADR keeps it as the rejected shape with its costs (§ Decided with the owner, 1).
- Date: 2026-10-16

Provenance: a claim marked **(Mn)** was measured — a command or a primary document fetched on 2026-10-16, listed in
§ Measurement ledger; **(Rn)** is remembered and listed in § Remembered, to be measured before it drives code.

## Context

The owner (2026-10-16): a GitOps deploy of a new release — Argo CD syncing a bumped chart — should always take a
backup first, into a volume the operator provides. Under Compose that order is already `veyqctl upgrade`'s
([ADR 0096](0096-veyqctl-manages-an-instance-from-immutable-releases.md) decision 5, step 4); decision 8 there
leaves Kubernetes to `helm upgrade` and the operator's GitOps tooling. On a cluster today the backup is a hand
procedure — `kubectl cp` of the key, `kubectl scale --replicas=0`, captures through port-forwards — that a GitOps
deploy never runs (the Run area's Upgrade page, Kubernetes tab (M8)). Five facts constrain the answer.

**1. Helm hooks are lifecycle events, and every GitOps tool reads them differently.** Helm waits for a `pre-upgrade`
Job to complete, and a failed hook fails the release, before any resource is updated (M1); but `--no-hooks` skips
them, and `--timeout` (default 5m) bounds a hook Job like any other operation (M2). Argo CD renders with
`helm template` and maps `pre-install` **and** `pre-upgrade` to the one phase `PreSync`, because it *"cannot know if
it is running a first-time install or an upgrade"* (M3); a failed `PreSync` hook stops the whole sync, but hooks do
not run during a selective sync at all (M4), and an automated sync happens whenever the application is OutOfSync,
not only when the release changed (M5). Flux runs real Helm actions (M6, R3), so a `pre-upgrade` hook runs on an upgrade only;
its per-operation timeout covers hook Jobs and defaults to 5m, and a failed upgrade is remediated by rollback by
default (M6). A hook is therefore not "before every new release": it is "before every sync" under Argo, "unless
skipped" under Helm and Argo, and "within five minutes" under Helm and Flux.

**2. The app pod is already replaced with nothing in between.** It is one pod with `strategy: Recreate` (ADR 0064),
and Kubernetes guarantees for an *upgrade* that every pod of the old revision has terminated before any pod of the
new one is created — not when a pod is merely deleted, whose replacement may start beside it (M7). Between the old
pod's end and the new pod's first container, the writer is stopped: exactly ADR 0056 §2's *cold*.

**3. The config-secret key is not a Kubernetes `Secret`.** The app generates it on first start onto the claim
`veyq-config-secret` (ReadWriteOnce, `helm.sh/resource-policy: keep`) and only the app pod mounts it (M8). It is
generated once and never rotated in v1 (ADR 0050, M8). So it is the one leg that does not change between upgrades.

**4. The chart's security shape is narrow on purpose.** The backend's Role is namespace-local and reaches only
`httproutes` and `listenersets`; the NetworkPolicy set is default-deny, the database admits the app pod alone and
the store's S3 port the gateway and the app pod alone (M8). A component that dials the database or the store needs a
policy change; one that scales the app needs a Role of its own.

**5. The capture tool exists and is Compose-shaped.** `scripts/instance_backup.py` captures the three legs, refuses
a key destination inside the capture, and refuses a populated restore target (A5); it reaches the database through
`docker compose exec` or, with `--db-host`, a `mariadb-dump` on `PATH`, and the store through a pinned `rclone`
container or `--rclone-binary`; it reads the running release from the app container through Docker (M8). It ships as
a release asset beside `veyqctl` (M8), but in no image.

## Decision

**1. The backup runs as a gate in the app pod, before the container that migrates — not as a Helm hook.** An init
container, `backup-gate`, runs before `app` in the same pod. Because the pod belongs to the new revision of a
`Recreate` Deployment, the old app has terminated before the gate starts (Context 2): the capture is cold by
construction, with no scale-down, no Role and no ordering to get right. Because the gate is part of the Deployment
rather than of a lifecycle event, it runs the same under `helm upgrade`, Argo CD, Flux and a plain
`helm template | kubectl apply`, and nothing skips it: not `--no-hooks`, not a selective sync, not an operator who
applies the manifests by hand (Context 1). It shares the app pod's network identity, so the NetworkPolicy set gains
no peer (Context 4). The owner's hook is weighed against this in § Open for the owner, 1.

**2. The gate captures when, and only when, the pod is about to start a different release.** It keeps one record on
the backup volume, `state.json`: the release whose data the database holds, and the last capture request it served
(decision 7). On each start:
- the database holds no data (A5's own predicate, `populated_tables`) → nothing to capture; the record is written
  with the target release, and the app starts — the first install;
- the target release equals the record and no new request is pending → nothing happens: a restart, a rescheduled
  pod, an Argo sync that changed nothing all pass in a second;
- the target differs from the record → decision 3's checks, then a capture, then the record moves to the target.
  It moves when the capture succeeds, not when the migration does: from there on the new release migrates, and the
  way back is that capture (ADR 0056 §6);
- no record but data (a new volume, a restored database) → a capture, saying the running release is unknown.

The target release is the app image's tag, rendered into the gate's environment by the chart; the release a
capture names as `app.version` is the record's, since there is no Docker to ask (Context 5).

**3. Before capturing, the gate refuses what no tested path covers — and writes nothing.**
- **A target older than the record** — a downgrade. The previous release would start against a schema it does not
  know (R4). The refusal names the capture to restore instead (ADR 0056 §7: a rollback is the backup's release
  plus a restore).
- **A target more than one major ahead** — ADR 0096 decision 5's *no run crosses more than one major*.
- **A major entered from anything but the release it was tested from** — how the gate knows that release is
  § Open for the owner, 5.

**4. The legs.** (a) The database is dumped into the capture, as today. (b) **The store leg is its index only** (owner 2026-10-16:
"nur Datenbank + Index"): the object keys with their sizes and ETags, read through the S3 API, never the objects.
An upgrade migrates the schema; it does not rewrite an object — every object is an immutable ciphertext blob — so
the database plus the index is the whole rollback point. Before a restore writes, the index is checked against the
live store (ADR 0056's A4): every object the dump references must still be there. Keeping the objects themselves is
the store's own promise — its versioning, replication or volume snapshots — which the Run page names as the
operator's part. This gate is an upgrade's way back, not a disaster backup of the store. (c) **The gate never touches the key.** It does not mount
`veyq-config-secret`, so a capture on the backup volume cannot hold the KEK, and ADR 0056 §1's *never beside the
dump* holds by construction rather than by a refused flag. The key does not change between upgrades (Context 3);
it is kept on its claim (kept on uninstall) and on the operator's own medium, exported once after the first start —
the step the Run page already documents (`kubectl cp`). The manifest records leg (c) as `separate`, so a restore
asks for it.

**5. The volume is the operator's.** `backup.existingClaim` names a PersistentVolumeClaim the operator created; the
chart creates none, so `helm uninstall` cannot take the backups with it, and its size, class and encryption at rest
are the operator's. ReadWriteOnce suffices: one pod mounts it. A capture is written to `captures/<UTC>.partial/` and
renamed when complete; after a complete capture the gate prunes complete ones beyond `backup.keep` (default 3, the
newest never) and every `.partial` left by an interrupted run. A full volume fails the gate (decision 6) — it never
prunes to make room.

**6. A failed or refused gate keeps the app from starting, and that is the failure mode.** Nothing has migrated, the
record has not moved, and the old pod is already gone: the instance is down until the operator acts, with its data
untouched. Pinning the previous release back starts it again — the record still names it. Kubernetes retries a
failed init container with backoff (R7); the gate is idempotent, every attempt a fresh capture. Who sees it:
- `helm upgrade` without `--wait` reports success, since its default waits for hooks only (M2) — the Run page says
  to upgrade with `--wait --timeout` above the capture time;
- Argo CD shows the application progressing, and the Deployment reports `ProgressDeadlineExceeded` after
  `progressDeadlineSeconds` (600 by default) while the controller keeps retrying (M7, R6);
- Flux waits for readiness within its timeout (R3, M6); past it, remediation rolls back, which ends the gate
  mid-capture — the `.partial` is swept on the next start. The operator sets `spec.timeout` above the capture time.

**7. A capture can also be asked for, the GitOps way.** `backup.request` is an opaque value rendered into the pod
template; changing it in git is a new revision, so Kubernetes stops the old pod first (Context 2), and the gate
captures because the request differs from the record's, without a release change. A scheduled backup is then a
commit, by a person or a bot. The gate never captures because one is "due": a pod replaced outside a revision
change has no termination guarantee (M7), so a due capture could be a hot one.

**8. The gate runs `instance_backup.py`, the one capture implementation.** The tool gains a mode for running inside
the pod (database and store by address, client binaries on `PATH`, no Docker), the record, the refusals and the
retention; its format stays the one Compose writes, so a capture taken in a cluster restores onto Compose and back
(same domain, ADR 0056 §4). The image the gate runs is pinned by digest and released and signed with the other two
([ADR 0057](0057-release-identity-and-deployment-recipe.md), [ADR 0091](0091-one-pipeline-per-commit-a-release-promotes-a-tested-snapshot.md));
which image it is, is § Open for the owner, 3.

**9. Restore is never automatic and never a hook.** It is a documented one-off Job on the Run page, run with the
tools image of the release the backup came from: the app scaled to zero (`kubectl scale`, by hand — an operator
under Argo pauses its automated sync first), the database emptied by the operator because A5 refuses a populated
target, the key from the operator's medium (`--key-from`), the domain named. The Job ends by writing the record
with the backup's `app.version`, so the next start of that release passes the gate; ADR 0056 §5's config-secret
read stays the last step.

**10. Compose keeps `veyqctl`.** Its upgrade already backs up first (ADR 0096 decision 5); a second gate there
would take two backups per upgrade. ADR 0096 decision 8 stands: no in-cluster operator, and nothing here installs a
CRD or a cluster-scoped object.

## Consequences

- **Every upgrade's downtime grows by the capture**, on top of `Recreate`'s restart: a database dump plus one
  listing of the bucket — independent of how much the store holds.
- **A disaster backup of the store is the operator's**, not the gate's: an instance whose store has no versioning,
  replication or snapshot loses its objects with the store, whatever the gate captured. The Run page says so.
- **The backup volume holds ciphertext and opaque metadata**, never the KEK — operator-blindness is unchanged: the
  gate reads what the app already reads (the database password, the store's admin token) and less (no KEK).
- **The values gain `backup.*`** (`enabled`, `existingClaim`, `keep`, `request`, the store-leg choice, the tools
  image) and the schema refuses `enabled` without a claim. `chart_meta_check.py` and `chart_upgrade_check.py` see a
  new init container and a new volume on the Deployment — neither an immutable field.
- **The Run area changes**: the Kubernetes tab of Upgrade replaces its hand procedure with the gate and the
  `--wait`/`timeout` advice; Backup and restore gains the restore Job and the one-time key export. cr-veyqctl
  rewrote both around `veyqctl` (done 2026-10-16) and left their Kubernetes halves as they were, so nothing waits.
- **ADR 0056's guard gap narrows, not closes**: the cluster backup is the gate's, but that a key export happened is
  still the operator's to know; the fingerprint tripwire remains the only detection of a lost key.
- **A stack lane for it** is a later step: ADR 0091's lanes boot Compose through `veyqctl`; a cluster lane is inbox
  #430's.

## Rejected alternatives

- **A Helm `pre-upgrade` hook Job** — the owner's starting shape, kept open as § Open 1 because it is a real
  option; its costs are listed there.
- **A hot capture, leaving the old app running.** Gives up ADR 0056 §2's whole consistency argument for minutes a
  household instance does not need.
- **Copying the key into the capture, or a `Secret` for it.** The first is the leaked backup ADR 0056 §1 exists to
  prevent; the second would need the chart or the backend to write `Secret`s, the RBAC ADR 0064 avoids.
- **A volume snapshot (`VolumeSnapshot`, Velero) as the procedure.** Storage-class-bound and unverifiable, the
  reasons ADR 0056 §3 rejects a volume copy; an operator may still take one beside the gate.
- **A scheduled `CronJob` that scales the app down and captures.** It needs a Role to scale, and under Argo CD's
  self-heal an out-of-band scale-down is reverted within seconds (M5, R5) — a capture that starts cold and ends
  hot. Decision 7's request is the scheduled form.

## Decided with the owner

Owner 2026-10-16 ("Bei den drei offenen Punkten gehe ich überall mit"): 1 (a) the gate; 2 (b) an explicit
choice the schema enforces; 3 (a) a third released image, `veyq-tools`; 4 the index only; 5 (a) + (b), offline. The
options stay below as the record of what was weighed.

1. **Gate or hook?** (a) The init-container gate (decisions 1–9). (b) A `pre-upgrade` hook Job, as proposed: it
   needs its own ServiceAccount and Role (`get` on the Deployment to read the running image, `patch` on
   `deployments/scale` for `veyq-app`, namespace-local), a new NetworkPolicy peer on the database and the store, the
   same release detection (Argo runs it on every sync and on install, M3), a scale-back on its own failure, and a
   `--timeout` raised above the capture in Helm and Flux (M2, M6); Helm does not track the Job as part of the release
   (M1); it can be skipped (`--no-hooks`, selective sync, M2, M4). Its one real advantage: a refusal or a failed
   capture leaves the old app serving, and `helm upgrade` fails visibly. (c) Both. **Recommendation: (a)** — it is
   the only shape every tool runs the same way, and it needs no new permission or network path; the outage on a
   failed gate is the price, paid only on a release change the operator is watching anyway.
2. **On by default?** (a) Off, the NOTES warning loudly. (b) An explicit choice: the schema refuses an install that
   neither names `backup.existingClaim` nor says `backup.enabled: false` — the shape `networkPolicy.ingressFrom`
   already has. (c) On, the chart creating a claim with `resource-policy: keep`. **Recommendation: (b)** — the
   backup is the only way back (ADR 0056 §6), and the chart does not guess a size or a class.
3. **Which image runs the gate?** (a) A third released image, `veyq-tools`: Python, the MariaDB client, `rclone`,
   pinned, signed like the other two. (b) Separate init containers from the pinned `mariadb` and `rclone` images
   plus a Python one, the manifest's logic split across them. (c) The tools added to the admin image. **Recommendation:
   (a)** — one tool, one image, one signature; (b) splits the code ADR 0056 wants in one place, and (c) puts a
   database client into the localhost-only admin surface.
4. ~~What the store leg costs per upgrade~~ — **decided** (owner 2026-10-16): the index only, decision 4.
5. **How the gate knows a major's entry release.** (a) Only the offline rules (no downgrade, at most one major).
   (b) Each release carries the release its L2 lane upgraded from (ADR 0091 decision 5 names it in the attestation),
   baked into the tools image or the chart; crossing a major from an older one is refused. (c) The gate asks the
   release source, as `veyqctl` does (egress the app pod already has; ADR 0097 reads the same source). **Recommendation:
   (a) + (b)** — offline and exactly the tested path; (c) makes a deploy depend on GitHub being reachable.

## Measurement ledger

- **M1** helm.sh/docs/topics/charts_hooks (fetched 2026-10-16): `pre-upgrade` *"executes on an upgrade request
  after templates are rendered, but before any resources are updated"*; for a Job *"Helm will wait until it
  successfully runs to completion"*; *"if the hook fails, the release will fail"*; default delete policy
  `before-hook-creation`; hook resources *"are currently not tracked or managed as part of the release"*.
- **M2** helm.sh/docs/helm/helm_upgrade (page for Helm 4.3.1): `--no-hooks` *"disable pre/post upgrade hooks"*;
  `--timeout` *"time to wait for any individual Kubernetes operation (like Jobs for hooks) (default 5m0s)"*; `--wait`
  defaults to `hookOnly`.
- **M3** argo-cd.readthedocs.io/en/stable/user-guide/helm: Helm is *"only used to inflate charts with
  `helm template`"*; `pre-install` and `pre-upgrade` are each *"equivalent to `argocd.argoproj.io/hook: PreSync`"*;
  Argo *"cannot know if it is running a first-time install or an upgrade"*; `pre-rollback` is unsupported; if any
  Argo hook is defined, all Helm hooks are ignored.
- **M4** argo-cd.readthedocs.io/en/stable/user-guide/sync-waves (the resource-hooks page moved there): a failed hook
  stops *"the whole sync process"*; *"Hooks do not run during a selective sync operation"*; no policy means
  `BeforeHookCreation`.
- **M5** argo-cd.readthedocs.io/en/stable/user-guide/auto_sync: automated sync *"only … if the application is
  OutOfSync"*; self-heal re-syncs after a 5-second timeout; a failed sync is not reattempted for the same commit.
- **M6** fluxcd.io/flux/components/helm/helmreleases: `disableHooks` per action, default `false`; the timeout is
  for *"any individual Kubernetes operation (like Jobs for hooks)"*, default `5m0s`; upgrade remediation strategy
  defaults to `rollback`, retries to 0; a changed chart or values triggers a new Helm release.
- **M7** kubernetes.io/docs/concepts/workloads/controllers/deployment: under `Recreate`, *"This will only guarantee
  Pod termination previous to creation for upgrades"*, and a manually deleted pod's replacement *"will be created
  immediately"*; `progressDeadlineSeconds` *"defaults to 600"* and the controller *"will keep retrying"*.
- **M8** this tree at `a9afc0d4`: `charts/veyq/templates/app-pvc.yaml` (the claim, RWO, `keep`),
  `app-deployment.yaml` (`replicas: 1`, `Recreate`, the key at `/var/lib/veyq/config-secret.key`), `app-rbac.yaml`
  (the Role's one rule), `networkpolicy.yaml` (the database's and the store's ingress); `grep -n "rotation"
  docs/decisions/0050-operator-config-secret-encryption.md` (*"No KEK rotation in v1"*); `scripts/instance_backup.py`'s
  docstring and `Database._wrap` / `app_release` (the Docker paths); `TOOLS` in `scripts/release_assets.py`;
  `site/src/content/docs/run/upgrade.mdx`, Kubernetes tab.

## Remembered (measure before it drives code)

- **R1** Helm's three-way merge puts back a `replicas` changed out of band on the next upgrade — relevant only to
  § Open 1 (b)'s scale-down; unmeasured under Helm 4's server-side apply.
- **R2** Helm's `lookup` returns nothing under `helm template`, so an Argo-rendered chart cannot read the live
  Deployment at render time (community issues, not an Argo document; `values.yaml` already says `lookup` is blind
  there) — why detection lives in the gate, not the template.
- **R3** Flux's helm-controller is built on the Helm SDK, waits for readiness by default, and its drift correction
  patches resources without running hooks.
- **R4** Flyway's default ignores applied migrations newer than the code on validate, so an older release may start
  on a newer schema rather than refuse it.
- **R5** Argo CD runs one operation per application at a time, so self-heal does not interrupt a running sync, and
  reverts an out-of-band `kubectl scale` otherwise.
- **R6** Argo CD's health for a Deployment past its progress deadline is `Degraded`.
- **R7** A failing init container is restarted with backoff under the pod's `restartPolicy: Always`.
