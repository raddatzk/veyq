---
id: cr-bundled-store-opt-in
title: The bundled store is opt-in on Kubernetes and can be left out on Compose
status: planned
satisfies: ["uc.admin.configure-object-store"]
adrs: ["0064", "0089", "0067", "0013", "0056", "0096"]
invariants:
  - operator-blind E2EE is untouched — this changes which containers run and which route exists, never what the server sees
  - the app and admin-web already run without a bundled store (ADR 0089 Decision 3) — no contract, no admin-web change
  - Compose keeps Garage in the recipe and on by default; nothing an existing Compose instance runs changes unless its operator writes the override
  - the chart has no release yet, so flipping its default migrates no install
---

## Goal

Inbox #520, decided in [ADR 0064](../docs/decisions/0064-kubernetes-helm-deployment-target.md) § *The bundled store
is opt-in on this target, and stays the default on Compose* — read that bullet first, it is the whole decision. In
short: the chart's new `garage.enabled` defaults to `false` and gates everything Garage; Compose keeps Garage on and an
operator leaves it out with `compose.override.yml`; the Traefik sink renders the same-origin blobs route only while the
store in use is the bundled one; `veyqctl` backs up an instance whose recipe has no Garage.

## Decisions the ADR leaves to this CR

1. **The blobs route's condition is the store *in use*, read at every render.** The sink's bucket lambda
   (`TenantIngressSinkProducer`, `bucket = { store.routeBucket() }`) becomes "the bundled store's bucket, or none":
   non-null only while `StoreSettings.current()` is `Ready` with a bundled connection. With none, no `<name>-blobs`
   router is rendered for any domain (bootstrap pair included); the `veyq-blobs` service and the inert middleware go
   with it. No new trigger: startup and `StoreHandler`'s promote → `projector.regenerate()` already cover every way the
   setting in use changes (a hint that disappears needs a restart, which regenerates). `routeBucket()`'s hint/default
   fallback loses its only caller — delete it rather than keep a dead branch.
2. **Testing the bundled branch without a new Quarkus profile.** `%test` passes no hint, so
   `StoreSettingFixtures.chooseBundled()` resolves to `Unavailable(hintMissing)`. `:core`'s test JVM is near its heap
   ceiling (`src/backend/CLAUDE.md`), so prove the bundled branch through a seam on the producer's lambda or by
   constructing the sink directly (as `TenantIngressEmptyProjectionTest` does), not through a new `@TestProfile`. Every
   negative (own store → no blobs router; no store → none; bundled chosen but hint missing → none) carries a positive
   control on the same render (bundled ready → the router, its bucket prefix, its inert middleware).
3. **The stack e2e's store sentinel moves after the bundled store is in use.** `scripts/run_stack_e2e.py`'s
   `plant_store_sentinel` (stage `window`) sends `GET /<bucket>/<key>` through Traefik before the browser stage chooses
   the bundled store (`tests/e2e/tests/setup/first-run.setup.ts`); under decision 1 it would hit the app's catch-all and
   the Garage-log control in `e2e_sweeps.py` would fail. Plant it once the bundled store is in use, still through Traefik
   on the bootstrap domain, still before the sweeps read Garage's log. Do not pre-choose the store by CLI: the setup
   test's click on *Use the bundled store* is itself under test.
4. **Chart: `garage.enabled`, default `false`.** Off, none of these render: `templates/garage.yaml` (Service,
   StatefulSet, HTTPRoute), `garage-configmap.yaml`, the `https-store` entry of `veyq.chartHosts` (and with it the
   Gateway listener), the `veyq-garage` NetworkPolicy, the Garage keys of `secret.yaml`, and every `VEYQ_BUNDLED_STORE_*`
   on the app and on the `backup-gate` init container. The schema requires `storeDomain` and the two Garage secrets
   (when no `existingSecret`) only under `garage.enabled: true` — the if/then shape `mariadb.enabled` already uses.
   `NOTES.txt` names `storeDomain` only when on, and when off says the store is set in the console as a store of your
   own, and that the backup gate then needs `backup.store.existingSecret`. A `garage.enabled: false` that follows a
   `true` leaves the claims behind (`volumeClaimTemplates`, no retention policy); NOTES says so in one line.
5. **CI values cover both sides.** At least one `ci/*.yaml` renders with `garage.enabled: true` and at least one with
   it off (the default). `scripts/chart_meta_check.py` (xix) — the app carries the bundled hint — and (xxvii) — no
   container runs the store's image — become conditional on `garage.enabled`, each with a control in the other
   direction (hint present / store image present when on, absent when off). `chart_upgrade_check.py` is information
   until the first release; a Garage-off ci file losing the predecessor's `veyq-garage` StatefulSet is an expected
   finding there, and the CR's retrospective says whether rule 7 should learn that a values-driven removal is not an
   upgrade defect before the first release turns it red.
6. **Compose recipe: `app` → `garage` is `required: false`**, nothing else in `docker-compose.prod.yml` changes.
   Measured during refinement on Compose v5.6.0 (single-file equivalent, not an override merge): with
   `required: false` a profile-disabled `garage` drops out of `config`; with `required: true` or the short form,
   `config` fails *depends on undefined service*. `${GARAGE_RPC_SECRET:?}` / `${GARAGE_ADMIN_TOKEN:?}` are still
   interpolated when the service is disabled — they stay required in `.env` (veyqctl generates both at install), and
   the Run page says so rather than pretending they become optional. Measure the real override merge
   (`profiles:` laid onto the recipe's `garage` by a second `-f`) before writing the Run page's example.
7. **`veyqctl` without Garage** (ADR 0064, *Backup and restore take the own store's pair*): `stack_facts` stops
   requiring a `garage` service and the app's bundled hint when the composed config (recipe + override) has no `garage`
   service. Then: the rclone container runs on Docker's default network, no `VEYQ_BUNDLED_STORE_*` is passed, and
   `backup_tool` refuses before anything stops — naming them — when `VEYQ_BACKUP_STORE_ENDPOINT` /
   `VEYQ_BACKUP_STORE_ACCESS_KEY` / `VEYQ_BACKUP_STORE_SECRET_KEY` are not in its shell. With a `garage` service,
   behaviour is unchanged. `veyqctl_selftest.py` gets the Garage-less case, its refusal and its positive control.

## Inventory — the commands that find the sites

| Surface | Command |
|---|---|
| Sink + its tests | `grep -rln "TraefikFileIngressSink\|veyq-blobs\|routeBucket\|TenantIngressProjector" src/backend --include='*.kt'` |
| Stack e2e sentinel | `grep -n "plant_store_sentinel\|store_sentinel" scripts/run_stack_e2e.py scripts/e2e_sweeps.py` |
| Chart | `grep -rn -i "garage\|storeDomain\|BUNDLED_STORE\|bundled\|https-store" infra/kubernetes/charts/veyq` |
| Chart checks | `grep -n "BUNDLED_STORE\|STORE_IMAGE\|(xix)\|(xxvii)" scripts/chart_meta_check.py`; `grep -n "rule 7\|StatefulSet" scripts/chart_upgrade_check.py` |
| Compose + veyqctl | `grep -n -i "garage\|BUNDLED_STORE" infra/docker/docker-compose.prod.yml infra/docker/.env.example src/tools/veyqctl.py src/tools/veyqctl_selftest.py` |
| Values reference guard | `scripts/validate_run_config_reference.py` — every `values.yaml` leaf needs a row in `site/src/content/docs/run/configuration.md` |
| Published docs | `grep -rn -i -E "garage\|storeDomain\|bundled store\|compose\.override" site/src/content/docs/run/ site/src/components/website/` |
| Internal docs that say Garage always runs on Kubernetes | `grep -n -i "garage" docs/decisions/0013-*.md docs/decisions/0067-*.md docs/decisions/0089-*.md docs/crosscutting/cc.security.wire-assertion.md` |

Nothing installs the chart (the kind leg is inbox #430), so the default flip breaks no install leg.

## Phases

### Phase 1 — backend: the blobs route follows the store in use (decisions 1–3)

Done when: `./gradlew check` green from `src/backend/`; the sink tests assert decision 2's negatives with their
controls; `run_stack_e2e.py` plants the sentinel after the store is chosen (the stack e2e itself runs at the V1 heavy
stage, not here — say so in the retrospective); `python3 scripts/validate_tenant_ingress_triggers.py` green.

### Phase 2 — chart (decisions 4–5)

Done when: `python3 scripts/chart_meta_check.py` green over every `ci/*.yaml`; `python3 scripts/chart_upgrade_check_selftest.py`
green and `chart_upgrade_check.py`'s output read and quoted in the retrospective; `values.yaml` documents
`garage.enabled` and why it is off; `python3 scripts/validate_run_config_reference.py` green with its row.

### Phase 3 — Compose and veyqctl (decisions 6–7)

Done when: the override merge is measured (`docker compose -f infra/docker/docker-compose.prod.yml -f <override> config`
with throwaway env) and the result quoted; `python3 src/tools/veyqctl_selftest.py` and
`python3 src/tools/instance_backup_selftest.py` green; `python3 scripts/validate_compose_posture.py` and
`python3 scripts/validate_release_identity.py` green.

### Phase 4 — docs

The Run pages the inventory's grep finds: `kubernetes.md` (Garage no longer among the mandatory hosts and secrets;
when to turn it on and what it then is — one node, one claim, its snapshots are yours), `configuration.md` (the
`garage.enabled` row; `storeDomain` and the Garage secrets required only when on; on Compose still required),
`storage-and-email.md` (the bundled store is Compose's default and Kubernetes' opt-in), `new-instance.mdx` (the
helm values to fill; the `compose.override.yml` row gains *leave the bundled store out*, with the measured example),
`networking.md`, `plan.md`, `index.md`, `operations.md`, `backup-and-restore.md` (own store on both targets; veyqctl's
shell pair; *deleting the claims Garage left behind deletes the objects*), `upgrade.mdx`; `Home.astro`'s stack list if it
states Garage as always there. Internal: ADR 0067's and 0089's sentences that read as *the chart deploys Garage*,
ADR 0013 §4's Kubernetes branch (*wherever the chart brings Garage*). Then inbox #520 is deleted.

Done when: the trust chain is green (`scripts/validate_*.py`; `validate_guard_gaps.py` only if a validator changed —
`chart_meta_check.py` is not a `validate_*`, but check whether guard_gaps covers it); `site/` builds (`npm run build`
in `site/`).

## Retrospective

_(filled by the implementer)_
