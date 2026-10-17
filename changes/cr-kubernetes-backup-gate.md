---
id: cr-kubernetes-backup-gate
title: On Kubernetes the start that would migrate backs up first — the backup-gate init container and the veyq-tools image
status: in-progress
satisfies: ["uc.admin.back-up-and-restore-instance", "uc.admin.install-and-upgrade-instance"]
adrs: ["0098", "0056", "0057", "0064", "0091", "0096", "0067", "0089", "0050"]
invariants:
  - "Operator-blind E2EE: the gate reads what the app already reads (the database password, the store's admin token) and less — it never mounts the config-secret claim, so no capture on the backup volume can hold the KEK (ADR 0056 §1, ADR 0098 decision 4c)"
  - "Self-hostable by default: the gate runs the same under helm, Argo CD, Flux and a plain kubectl apply; it asks no release source (offline major-entry rule) and adds no Role, no NetworkPolicy peer, no CRD, no cluster-scoped object"
  - "Every infra decision has an answer on both targets: Compose keeps veyqctl's backup-first upgrade unchanged (ADR 0098 decision 10); what this CR adds to the shared tool must leave Compose's capture, restore and rollback working"
  - "No deprecated dependencies: the tools image's MariaDB client and rclone are the pinned, maintained ones the tool already runs"
---

## Goal

Build [ADR 0098](../docs/decisions/0098-on-kubernetes-the-start-that-would-migrate-backs-up-first.md). Today a cluster
upgrade takes its backup by hand (port-forwards, `kubectl cp`, a scale-down), and a GitOps deploy never runs that
procedure. After this CR, the app pod of the Helm chart carries an init container, `backup-gate`, that runs before the
container which migrates. When the pod is about to start a different release (or a new `backup.request`), it captures
the database and an **index** of the store onto a volume the operator provides, refuses the upgrades no tested path
covers, and keeps the newest `backup.keep` captures. It runs `scripts/instance_backup.py` in a new in-pod mode, shipped
in a third released and signed image, `veyq-tools`. A restore stays a documented one-off Job. Compose is unchanged:
`veyqctl upgrade` already backs up first.

## Context & constraints

- **The decisions are ADR 0098's 1–10 and its "Decided with the owner" (2026-10-16): the gate (1a), an explicit choice
  the schema enforces (2b), `veyq-tools` (3a), the index only (4), the offline entry rule (5 a+b).** This CR does not
  restate them; it fixes what they leave to the build (§ Decisions this CR takes) and asks what they leave to the owner
  (§ Open for the owner).
- **Owning docs:** [uc.admin.back-up-and-restore-instance](../docs/features/admin/use-cases/uc.admin.back-up-and-restore-instance.md)
  (A4, A5, A7, A9, A10 are the restore gates this CR extends to an index capture) and
  [uc.admin.install-and-upgrade-instance](../docs/features/admin/use-cases/uc.admin.install-and-upgrade-instance.md)
  (its A6 says *"Kubernetes. Not this use-case"* today — `grep -n "A6" docs/features/admin/use-cases/uc.admin.install-and-upgrade-instance.md`).
  Where the gate's criteria live is O1.
- **No coverage surface reaches the gate.** A Python operator tool is no surface (ADR 0040; the backup use case's
  coverage file says so on four criteria — `grep -n "operator tool" docs/coverage/features/admin/use-cases/uc.admin.back-up-and-restore-instance.yaml`),
  and `stack` is not admitted (`grep -n '^ADMITTED' scripts/validate_coverage.py` → `{"backend", "web"}`). New criteria
  are written with `gaps:` that name what proves them: `scripts/instance_backup_selftest.py` (run by `ci.yml`'s
  `web-e2e` job: `grep -n instance_backup_selftest .github/workflows/ci.yml`) and `scripts/chart_meta_check.py` (run by
  the `chart` job).
- **Sibling CRs on the same files** (changes/README.md § *Two CRs that write the same page*):
  - **cr-veyqctl is closed** (its phase 6 rewrote the Run area around `veyqctl` and left the Kubernetes halves as they
    were — ADR 0098's Consequences now say so: `grep -n "nothing waits" docs/decisions/0098-*.md`). The Kubernetes
    tab of `run/upgrade.mdx` still carries the hand procedure and the "not built" bullet
    (`grep -nE "TabItem label=\"Kubernetes\"|Kubernetes-native backup" site/src/content/docs/run/upgrade.mdx`), and
    `run/backup-and-restore.md` its § Restore under Helm (`grep -n "^### " site/src/content/docs/run/backup-and-restore.md`).
    This CR owns those two sections, `run/kubernetes.md`, and in `run/verify-a-release.md` only the `cosign verify`
    block for `veyq-tools` (beside the app and admin ones: `grep -n "cosign verify \"ghcr" site/src/content/docs/run/verify-a-release.md`).
  - **cr-signed-release-manifest has landed** (inbox #469 B; its CR is deleted). The publish step's `github-release`
    now signs `manifest.json` (`cosign sign-blob --yes --new-bundle-format`, bundle `manifest.json.bundle`;
    `release_assets.MANIFEST_BUNDLE` is in the producer's fixed set: `grep -n "MANIFEST_BUNDLE" scripts/release_assets.py`),
    and `validate_release_identity.py` assertion 11 collects every `sign-blob` line of that step and requires the
    producer first, then both `sign-blob`s, and nothing between the manifest's `sign-blob` and `gh release create`
    that calls `release_assets.py` or names `manifest.json` (`grep -n "sign-blob" scripts/validate_release_identity.py`).
    `veyqctl` verifies the manifest (cosign ≥ `COSIGN_MIN_VERSION` 3.1.3) before parsing it
    (`grep -n "COSIGN_MIN_VERSION\|def verify_manifest" scripts/veyqctl.py`). Phase 2 touches the same four files
    (`release_assets.py`, `veyqctl`, the publish job, `run/verify-a-release.md`) for `images.tools`: the manifest
    gains a key, it stays signed as one file, and assertion 11 must stay green with the third image.
  - **cr-one-pipeline-per-commit** is `in-progress` with phases 1–6 checked (`grep -n '^- \[.\]' changes/cr-one-pipeline-per-commit.md`);
    phase 2 here edits `ci.yml`, so re-read it on the merged tree before the first edit.
  - **cr-text-fields-seal-under-their-own-key** touches `packages/crypto`, `web`, backend limits — no file in common.
- **ADR 0091 decision 5 is not built** (measured: `grep -rln "attest" scripts .github` → no file, exit 1; `grep -n "upgrade-proof:" -A5 .github/workflows/ci.yml`
  shows `if: false`). There is no L2 lane and no attestation naming a predecessor. ADR 0098's 5 (b) — *"each release
  carries the release its L2 lane upgraded from"* — therefore has no producer today. The one derivation of "the
  release this build was proven from" that exists is `run_web_e2e.default_predecessor_ref()` (the upgrade proof's and
  `chart_upgrade_check.py`'s predecessor: `grep -n "def default_predecessor_ref" -A8 scripts/run_web_e2e.py`). O3 asks
  which one the image bakes. Cite it as **planned in ADR 0091 decision 5** wherever the CR leans on L2.
- **A third image contradicts a sentence of ADR 0067**: *"keeps its two images. The alternative was a third, published,
  signed and versioned forever to serve a one-shot"* (`sed -n 148,149p docs/decisions/0067-store-provisioning-from-a-declared-type.md`).
  The owner chose the third image for a different job (ADR 0098 § Decided, 3); phase 4 narrows that sentence rather than
  deleting its reason.
- **One stale line in ADR 0098 itself:** its Consequences list *"the store-leg choice"* among the new values
  (`grep -n "the store-leg choice" docs/decisions/0098-*.md`), written before the owner decided "the index only". There is
  no such value here; phase 4 deletes the phrase. Stated, not silent: it follows from the owner's answer 4.
- **Machine hints (hold for the CR's planned shape; re-derive against what is built).** No cluster runs on this Mac
  (measured 2026-10-16: `which kind k3d` → not found; `kubectl get nodes` → `127.0.0.1:6443 connection refused`);
  `helm` v4.0.1 is installed and matches `ci.yml`'s `HELM_VERSION`. Docker runs (8 GB). A live run against a Compose
  stack must never recreate the owner's dev containers: use a throwaway compose project or `scripts/run_stack_e2e.py`'s
  instance, never `infra/docker-compose.yml` under its default project name.

### Inventory — locators (run at refine on `e829e603`; re-run before the first edit)

- The tool's two backends today (compose / `--db-host`, container / `--rclone-binary`): `grep -nE "def _wrap|shutil.which|self.binary" scripts/instance_backup.py`
- Where the release comes from today (Docker only): `grep -n "def app_release" -A3 scripts/instance_backup.py`
- The store leg today (a mirror) and the restore's store-emptiness gate: `grep -nE "def mirror_out|def mirror_in|store.size()|already holds .* object" scripts/instance_backup.py`
- The format and its reader: `grep -nE "BACKUP_FORMAT|def read_manifest" scripts/instance_backup.py`
- The emptiness predicate the gate reuses (A5): `grep -nE "def populated_tables|^NOT_DATA" scripts/instance_backup.py`
- Who imports the tool (its API is theirs too): `grep -rln "instance_backup" scripts .github` and `grep -rn "instance_backup\.[A-Za-z_]*" scripts --include='*.py' -o | sort -u`
- The store setting the gate reads (bundled or own, endpoint, bucket — plaintext; the secret sealed): `grep -n -A14 "CREATE TABLE veyq_store_setting" backend/core/src/main/resources/db/migration/V202610041200__core_baseline.sql`
- The app pod the gate joins: `grep -nE "strategy|fsGroup|serviceAccountName|volumes:|initContainers" charts/veyq/templates/app-deployment.yaml`
- The env the app reads for DB and store (the gate's sources): `grep -nE "QUARKUS_DATASOURCE_|VEYQ_BUNDLED_STORE_" charts/veyq/templates/app-deployment.yaml`
- The explicit-choice shape to mirror: `grep -n '"networkPolicy"' -A10 charts/veyq/values.schema.json`; the top-level `required` at `sed -n 6p charts/veyq/values.schema.json`
- Image helpers and the digest pattern: `grep -nE "define \"veyq.appImage\"|define \"veyq.adminImage\"" -A2 charts/veyq/templates/_helpers.tpl`; `grep -n '"imageRef"' -A8 charts/veyq/values.schema.json`
- What the chart checker treats as an app container (the gotcha): `grep -n "def is_app_container" -A4 scripts/chart_meta_check.py`
- Which checks read `initContainers` and which only `containers`: `grep -nE "initContainers|\"containers\"" scripts/chart_meta_check.py`
- The values files every chart check renders: `ls charts/veyq/ci/`
- The image set every release guard reads: `grep -n "image: \[" .github/workflows/ci.yml`; `grep -nE "def own_images|def published_images" scripts/validate_image_pins.py scripts/validate_compose_posture.py scripts/validate_release_identity.py`
- Guards that enrol a new Dockerfile by itself: `grep -ln "dockerfile" scripts/validate_images_run_unprivileged.py scripts/validate_npm_installs_gated.py scripts/validate_image_pins.py`
- The hard-coded app/admin pairs a third image must join (or deliberately not): `grep -nE "veyq-admin|--admin|images\[\"admin\"\]|\"admin\": admin" .github/workflows/ci.yml scripts/release_assets.py scripts/run_stack_e2e.py scripts/veyqctl.py scripts/veyqctl_selftest.py`
- Dependabot's docker directories: `grep -n "package-ecosystem: \"docker\"" -A6 .github/dependabot.yml`
- Prose saying "two/both images" (phase 4's sweep): `grep -rnE "two images|both images|image pair" docs/decisions site/src/content/docs .github/workflows scripts`
- Prose saying Kubernetes backup is not built / deferred: `grep -rnE -i "kubernetes-native backup|backup/restore stays|0098.*proposed|compose-only" docs site/src/content/docs charts`
- The Run pages' Kubernetes sections: `grep -nE "TabItem label=\"Kubernetes\"|Kubernetes-native backup" site/src/content/docs/run/upgrade.mdx`; `grep -n "^### " site/src/content/docs/run/backup-and-restore.md`

### Decisions this CR takes that the ADR leaves to the build (stated, not silent)

1. **Two capture formats, by leg.** A mirror capture (Compose, `veyqctl`) stays `backupFormat: 2`, byte-for-byte the
   shape it has (`objects/`). An index capture is `backupFormat: 3`: no `objects/`, and `store.leg: "index"` with
   `store.index` = `[[key, size, hash], …]`. The tool reads both. **Why not bump every capture to 3:** `veyqctl`
   restores with *the running release's* tool, which after a Compose rollback is the older release's — it would refuse
   a format it does not know. An index capture is the one shape an older tool must refuse, and the format number is
   what makes it refuse instead of failing at `objects/`.
2. **The index's hash is what rclone reports, measured first.** ADR 0098 says "ETags". rclone exposes the S3 MD5,
   which it takes from a single-part ETag (R8, R9). Chunks are uploaded by one presigned PUT each, so for them the two
   coincide. Phase 1 measures it; if a listing gives no hash for an object, the index records `null` and the A4 check
   compares that object by key and size only, saying so.
3. **A restore of an index capture never writes to the store.** It replaces A5's store-emptiness gate with A4 against
   the live store: every key the dump's blob rows require is listed, with the indexed size (and hash where both have
   one), before anything is written. An object the live store holds and the index does not is tolerated (an upload
   after the capture — the same asymmetry `verify` already has). A missing or changed object refuses, naming it.
   O4 asks whether there is a knowing override.
4. **The gate reaches the database by the app's own URL and user, under names of its own.** The chart renders the
   gate's `VEYQ_BACKUP_DB_URL` from the same helper as the app's `QUARKUS_DATASOURCE_JDBC_URL` (one helper, so the two
   cannot drift) and `VEYQ_BACKUP_DB_USER` / `VEYQ_BACKUP_DB_PASSWORD` from the app's user and its Secret key
   `mariadbPassword` (ADR 0098 Consequences: *"the gate reads what the app already reads"*). The gate must **not**
   carry `QUARKUS_DATASOURCE_JDBC_URL`: `chart_meta_check.is_app_container` would classify it as the app and demand the
   config-secret mount. The tool parses `jdbc:mariadb://host[:port]/db[?…]` and refuses any other form, naming it.
   That the app's user can dump, drop and load its own database is R10, measured in phase 1.
5. **The store by address.** The bundled store: `VEYQ_BUNDLED_STORE_ENDPOINT` and `VEYQ_BUNDLED_STORE_ADMIN_TOKEN`, the
   app's own values, the key pair derived as today. The bucket is the one `veyq_store_setting` names (R11). A store of
   the operator's own is O2.
6. **The release grammar is ADR 0057's**, `<major>.<minor>` with an optional `-SNAPSHOT`
   (`sed -n 11,14p charts/veyq/Chart.yaml`), ordered by `(major, minor, release-after-its-snapshot)`. The gate refuses a
   target that does not parse, naming `backup.enabled: false` as the way a checkout's dev tag (`0.0-dev`, a
   `candidate-*` tag) runs without it. A record whose release does not parse is treated like no record (decision 2's
   last case: capture, release unknown, no ordering refusal).
7. **The record and its write.** `state.json` on the backup volume: `{"release", "request", "capture", "writtenAt"}`,
   written to a temporary name and renamed, so an interrupted write leaves the previous record. A record that is not
   readable JSON fails the gate, naming the file — never treated as absent, because "absent" means *capture and move
   on* and would silently skip the downgrade refusal.
8. **The order of the gate's checks is part of the decision and is pinned by a test:** (1) a readable record or none;
   (2) the database holds no data → write the record with the target, pass (this must come before the downgrade
   refusal: the documented rollback empties the database and starts the old release once, decision 9); (3) target and
   request equal the record → pass; (4) the refusals of ADR 0098 decision 3, writing nothing; (5) capture into
   `captures/<UTC>.partial/`, verify it offline, rename; (6) move the record; (7) prune. A failure in 5 leaves the
   `.partial`, the record unmoved, and a non-zero exit.

### Remembered — measured before it drives code

ADR 0098's R1–R7, and four this refine adds. Each says which step measures it, or why nothing depends on it.

| | Claim | What depends on it | Measured where |
|---|---|---|---|
| R1 | Helm's three-way merge restores an out-of-band `replicas` | nothing — only the rejected hook needed a scale-down | not measured, on purpose |
| R2 | `lookup` is blind under `helm template` | the reason detection is the gate's, not the template's — no code | not measured; the chart uses no `lookup` for it |
| R3 | Flux waits for readiness by default; drift correction runs no hooks | the Run page's `spec.timeout` advice | phase 4, against fluxcd.io's HelmRelease page, before the page states it |
| R4 | Flyway (12.0.0 here) ignores applied migrations newer than the code on validate, so an older release *starts* on a newer schema | the downgrade refusal's wording, and the restore's existing A10 message (*"would refuse to start"*, ADR 0056 §7) | phase 1, from Flyway 12.0.0's `ignoreMigrationPatterns` default and `quarkus.flyway.*` in `backend/core/src/main/resources/application.properties` (only `migrate-at-start` is set today: `grep -n flyway backend/core/src/main/resources/application.properties`). If true, the refusal stands for a stronger reason, and phase 4 corrects ADR 0056 §7's and A10's "would refuse to start" |
| R5 | Argo CD's self-heal reverts an out-of-band `kubectl scale` | the restore Job's "pause the automated sync first" | phase 4, against Argo CD's auto-sync page |
| R6 | Argo shows a Deployment past its deadline as `Degraded` | the Run page's "who sees a failed gate" | phase 4, Argo's health docs; else the page says "progressing" only (M7) |
| R7 | A failing init container is retried with backoff under `restartPolicy: Always` | idempotence matters (decision 6) | phase 3's live proof if O5 (a); else kubernetes.io's init-container page, cited |
| R8 | `rclone lsjson -R --hash` reports an S3 object's MD5 from its single-part ETag and no raw ETag | decision 2's index fields | phase 1, the pinned rclone against Garage |
| R9 | Garage's ETag for a single-part PUT is the body's MD5 | the same | phase 1, same run |
| R10 | The app's database user (`MARIADB_USER`'s grant on its database) can `mariadb-dump --single-transaction`, drop every table and load | decision 4; the Helm restore page uses the root password today (`grep -n mariadbRootPassword site/src/content/docs/run/backup-and-restore.md`) | phase 1, against `mariadb:11.4` with the chart's user/grant shape |
| R11 | A bundled `veyq_store_setting` row names its bucket (or the tool's default `blobs` holds) | decision 5 | phase 1, a freshly provisioned bundled instance's row |

## Decided with the owner

Owner 2026-10-17 ("Passt", to "alles wie empfohlen"): every recommendation below is taken — O1 (a), O2 (a) `backup.store.existingSecret`, O3 (a), O4 (b) refuse with
`--accept-missing-objects`, O5 (a) `kind` installed once via Homebrew for the cluster proof, O6 (a). The options stay
below as the record of what was weighed.

- **O1 — Which use case owns the gate's criteria?** (a) `uc.admin.install-and-upgrade-instance` owns *"a release change
  backs up before it migrates, on Kubernetes too"* (its A6 widens from "not this use-case" to the gate), and
  `uc.admin.back-up-and-restore-instance` owns the index capture, A4 against the live store and the restore Job.
  (b) Everything under the backup use case. (c) A new use case for the cluster gate. **Recommendation: (a)** — the
  upgrade use case already owns `an-upgrade-backs-up-before-it-migrates` for Compose, the same property with a second
  trigger; putting the cluster's copy elsewhere is the two-homes tell changes/README.md § coverage warns of.
- **O2 — A store of the operator's own on Kubernetes.** The gate has no KEK and cannot open the sealed secret key
  (`secret_key_ciphertext`, R11's table). (a) `backup.store.existingSecret` holding endpoint, bucket and key pair; the
  gate checks them against the plaintext `veyq_store_setting` row and fails, naming the value, when the row says
  *own* and no Secret is named. (b) For an own store the gate captures the database only and the restore skips A4.
  **Recommendation: (a)** — (b) silently drops the one check the logical leg exists for; a read-only key is enough and
  the Run page says so.
- **O3 — What the image bakes for the major-entry rule, while ADR 0091's L2 is not built.** (a) The predecessor the
  release's own upgrade proof and chart check use, `default_predecessor_ref()` (the newest `v*` tag merged into the
  release commit's parent), recorded at build as `majorEntry` when it lies in the previous major, `null` otherwise;
  replaced by the L2 attestation's input when ADR 0091 decision 5 lands. (b) Nothing until L2 exists: only the two
  offline rules (no downgrade, at most one major). **Recommendation: (a)** — one derivation the pipeline already
  trusts; (b) leaves exactly the jump ADR 0096 forbids open on the target that has no `veyqctl`. With (a) the entry
  is **equality**: entering major X from any release but the baked one is refused, an older one and a newer
  maintenance release of X−1 alike (the newer one is an untested path; the next X release bakes it). **And where it
  lives:** the owner's 5 (b) said "the tools image or the chart". Recommendation: the image (`/opt/veyq/release.json`,
  `{release, majorEntry}`), with the gate refusing a target release that differs from the image's own — the entry
  rule and the format are that release's, and a values override cannot detach them.
- **O4 — A knowing override for missing objects at restore.** A rollback days after an upgrade can find objects the
  new release's reclaim sweep deleted. (a) Refuse, listing them; the operator restores them from the store's
  versioning (the duty the Run page names) and retries. (b) As (a), plus `--accept-missing-objects`, the shape of
  `--without-key`: the instance comes back with those pictures broken (A4's symptom), knowingly. **Recommendation:
  (b)** — refusal stays the default, and an operator without versioning is not left with no way back at all.
- **O5 — The live cluster proof.** No cluster runs here (Context, machine hints). (a) Phase 3 installs `kind`
  (kubernetes-sigs, Apache-2.0, `brew install kind`) and runs one recorded proof: install with the gate, an upgrade
  that captures, a restart that does not, a refused downgrade, the restore Job, then deletes the cluster. The owner
  OKs the install; the run needs the Gateway API CRDs (the chart renders routes) and side-loaded images
  (`kind load docker-image`), and is not run in a parallel wave (memory). (b) No cluster: phase 2's container proof
  (the image as the gate against a Compose instance), phase 3's render checks, and the semantics left to M7/R7 —
  the cluster leg stays inbox #430's. **Recommendation: (a)**, once — the gate is the first chart object whose
  behaviour, not its shape, is the decision, and #430's recurring job can follow later.
- **O6 — Which tools image a restore Job runs.** ADR 0098 decision 9 says *"the tools image of the release the backup
  came from"*. The capture is written by the **new** release's gate (the pod that starts the new release), and the
  first release that ships `veyq-tools` has a predecessor with no tools image at all. (a) The tools image of the
  release that **wrote** the capture (the manifest records it as `capture.tool`). (b) Any tools image at or above the
  first that reads format 3. **Recommendation: (a)** — it is the one image certain to read the capture; phase 4
  edits decision 9's sentence to say so.

## Phases

- [x] **1 the tool — `scripts/instance_backup.py` in the pod, and its self-test.**
  - **Measure first** (into the retrospective): R4, R8–R11. R8/R9/R10/R11 need a store and a database: a throwaway
    compose project (`mariadb:11.4` and Garage at the pins `validate_image_pins.py` holds), never the owner's dev
    stack. A claim that fails changes the decision it feeds before code is written, and is reported.
  - **Build:**
    - In-pod mode: database by URL (decision 4, `--db-url`, env `VEYQ_BACKUP_DB_URL`/`_USER`/`_PASSWORD`), client
      binaries required on `PATH` (no Docker fallback in this mode — a missing binary is a refusal naming it), store
      by `--rclone-binary`. The release named by the caller (`--app-version`), never by `docker inspect`.
    - The index leg: `capture --store-leg index` lists the bucket (R8's command), writes `store.index`, format 3.
      Leg (c): `--key-kept-apart` records `configSecret.placement: separate` without reading any key (decision 4c of
      the ADR) — the gate passes it; Compose's flags are unchanged.
    - `verify` on format 3: dump structure as today; every key the blob rows require is in the index (A4 at capture,
      offline); with `--against-store`, the live check of decision 3. Format 2 verification unchanged.
    - `restore` on format 3: decision 3 replaces the store-emptiness gate; no `mirror_in`; `--record DIR` writes
      `state.json` with the manifest's `app.version` as its last step (ADR decision 9); O4's flag if (b).
    - `gate` subcommand: decisions 6–8 and ADR decisions 2, 3, 5, 7; `--dir`, `--target-release`, `--request`,
      `--keep` (≥ 1), the baked `/opt/veyq/release.json` path overridable for the self-test; the capture's manifest
      records `capture.tool` (O6). Retention: after a complete capture, the complete ones beyond `--keep` (the newest
      never) and every `.partial`; a full volume fails, never prunes to make room.
    - The module docstring: the in-pod mode, the index leg, the record, the refusals — and that a Compose capture is
      untouched. `e2e_sweeps.py` and `run_web_e2e.py` keep their calls (the locator above), unchanged.
  - **Tests** (`scripts/instance_backup_selftest.py`, no Docker; each with how it bites):
    - **G1 (live defect) the decision table.** Fake `Database`/`Store` seams: empty DB → record written, no capture;
      same release and request → nothing; new release → capture then record; new request only → capture; no record
      with data → capture, `app.version` null, release named unknown. Red on the untouched tree (no `gate`).
    - **G2 (live defect) refusals write nothing.** Downgrade, two majors, a major entered from a release other than
      `majorEntry` (O3), a target ≠ the image's release, an unparsable target, an unreadable `state.json` — each
      refused by its own message, the volume byte-identical before and after (a recursive listing with sizes and
      the record's bytes), and each with a positive control one field away that does capture. Each crafted input
      fails exactly one check (changes/README.md: four refusal tests once stayed green with their check removed).
    - **G3 (live defect) order.** An emptied database under a record naming a newer release passes and rewrites the
      record (the rollback path) — red if the downgrade refusal runs first.
    - **G4 (live defect) retention.** `keep` 3 with five complete and two `.partial` → the three newest remain; a
      failed capture prunes nothing and leaves the record; `keep` 0 refused.
    - **G5 (live defect) the index restore.** A format-3 manifest against a fake live listing: all present → passes
      and never calls `mirror_in` (asserted on the seam); one missing key → refused naming it before `load`; a size
      mismatch → refused; an extra object → tolerated; with O4 (b) the flag passes the missing case and the closing
      log names the broken objects.
    - **G6 (injection) Compose is untouched.** A format-2 capture's manifest and `verify` output are unchanged: lever —
      make `capture` default to the index leg; the test that pins the default mirror shape must go red.
    - **G7 (live defect) the URL parser.** `jdbc:mariadb://h:3306/veyq?x=y`, no port, a multi-host and a
      `jdbc:mysql:` URL — the last two refused naming the form.
    - **G8 (live defect) no key, by construction.** The gate given a readable key file at the default path still
      records `separate` and copies nothing (lever: none needed — red until the flag exists).
  - **Done when:**
    - Baseline before the first edit, on a detached worktree of `HEAD`: `python3 scripts/instance_backup_selftest.py`
      exit code recorded.
    - `python3 scripts/instance_backup_selftest.py` green with G1–G8; each live defect's first red recorded.
    - One live run (agent's, throwaway compose project): `gate` run twice against a seeded instance from a container
      on its network with the client binaries on `PATH` — one capture, one no-op — then `restore` of that capture onto
      an emptied database, A4 green, and the instance opens a Memory with media
      (`scripts/run_web_e2e.py`'s capture/restore hooks may carry it; the memory notes on pinning it from a worktree
      apply). The Compose rehearsal (`web-e2e`'s `restore.*`/`legthree.*` groups) stays green.
    - Every `scripts/validate_*.py` green (`validate_guard_gaps.py` skipping unless a guard changed).

- [ ] **2 the `veyq-tools` image — built, signed, published, named.**
  - **Build:** a Dockerfile (path decided at build; recommended `tools/Dockerfile`, context the repo root) whose last
    stage is `runtime-tools`: the MariaDB client from the pinned `mariadb:11.4` (the server's own version, the pin
    `validate_image_pins.py` already holds — `grep -n "mariadb:11.4" scripts/instance_backup.py infra/docker-compose.prod.yml charts/veyq/values.yaml`),
    `rclone` copied from the pinned `RCLONE_IMAGE`, Python 3 (≥ 3.9), `scripts/instance_backup.py` at
    `/opt/veyq/`, and `/opt/veyq/release.json` from build arguments (O3). Non-root `USER 10001`, nothing exposed,
    `ARG VEYQ_VERSION`/`VEYQ_REVISION` in that stage only. The base choice is measured, not assumed: starting from
    the `mariadb` image vs. a Python base plus copied client libraries — the smaller image that runs the self-test
    wins, and the retrospective says which.
  - **Pipeline** (`ci.yml`): `veyq-tools` joins the `build` matrix (`image:` and an `include` with its `dockerfile`
    and `target`). The publish's hard-coded pairs gain it: the refuse step's `ARTIFACTS`, an "Extract image metadata"
    and a "Create multi-arch manifest" step, `IMAGES` of the signing step, and `release_assets.py --tools` writing
    `images.tools` into `manifest.json`. The `stack` job does **not** build it (Compose never runs it; say so in the
    job's comment). `veyqctl` ignores `images.tools` (`parse_manifest` reads only `app`/`admin`) — its self-test
    gains one case that a manifest with `tools` still parses. Dependabot's `docker` directories gain the new one.
  - **The artifact runs** (changes/README.md § *A build step that emits an executable artifact*): the `build` job's
    tools leg loads the image and runs `python3 /opt/veyq/instance_backup_selftest.py` inside it (copy the self-test
    in, or mount it), plus `mariadb-dump --version` and `rclone version`. Locally through `run_pipeline.py`'s
    host-Docker stand-in.
  - **Guards it enrols by itself** (Context: `validate_image_pins.py`, `validate_compose_posture.py`,
    `validate_release_identity.py`, `validate_images_run_unprivileged.py`, `validate_npm_installs_gated.py`) each run
    and are read: assertion 7 of `validate_release_identity.py` demands `run/verify-a-release.md` verify the new image
    by digest — this phase adds that line (the sibling split above), with its `cosign verify` identity as narrow as
    the others.
  - **Bites:** **T1 (injection)** drop `veyq-tools` from the refuse step's `ARTIFACTS` → `validate_release_identity.py`
    red naming it; **T2 (injection)** a `FROM` without a digest in the new Dockerfile → `validate_image_pins.py` red;
    **T3 (injection)** `USER root` last → `validate_images_run_unprivileged.py` red; **T4** the verify page without
    the tools line → assertion 7 red. Each in a detached worktree, snapshot-and-restore.
  - **Done when:** the image builds for the host's architecture and runs its self-test green inside itself;
    `release_assets.py` writes `images.tools`; T1–T4 recorded; `python3 scripts/run_pipeline.py` green on the
    committed tree with every not-run step named (registry write, keyless signing — no remote exists); every
    `scripts/validate_*.py` green, `validate_guard_gaps.py --force` too if a guard's anchor changed.

- [ ] **3 the chart — the gate in the app pod, the explicit choice.**
  - **Values** (`values.yaml`, header's list of required choices updated): `backup.enabled` (default `true`),
    `backup.existingClaim` (`""`), `backup.keep` (3), `backup.request` (`""`), `backup.image` (`imageRef`:
    `ghcr.io/raddatzk/veyq-tools`, tag defaulting to `appVersion`, `digest`), `backup.resources`, and O2's
    `backup.store.existingSecret` if (a). No store-leg value (ADR answer 4).
  - **Schema:** `backup` joins the top-level `required`; `anyOf` [`enabled` const `false`] / [`existingClaim`
    `minLength` 1], so an install naming neither fails naming `backup`; `keep` ≥ 1; `backup.image` is an `imageRef`.
  - **Template** (`app-deployment.yaml`, a `veyq.toolsImage` helper beside the other two): with `enabled`, an
    `initContainers` entry `backup-gate` — the restricted security context include, `readOnlyRootFilesystem`, a
    `tmp` `emptyDir`, the claim at `/backup`, the env of decisions 4–5, `VEYQ_BACKUP_TARGET_RELEASE` =
    `image.tag | default .Chart.AppVersion`, `VEYQ_BACKUP_REQUEST`, `VEYQ_BACKUP_KEEP`; **no** `config-secret` mount;
    the `backup` volume from `existingClaim`. `backup.request` also rendered as a pod-template annotation so a change
    is a new revision (ADR decision 7). No Role, no NetworkPolicy change (the gate is the app pod —
    `grep -n "component: app" charts/veyq/templates/networkpolicy.yaml`).
  - **NOTES.txt:** with `enabled: false`, that no backup is taken before a migration and the Run page's hand
    procedure is the operator's; with `enabled`, `helm upgrade --wait --timeout <above the capture>` and Flux's
    `spec.timeout`.
  - **`chart_meta_check.py`** (outside the glob, the `chart` job runs it): new invariants, its docstring's three
    fields extended — (xxiii) with `enabled`, the Deployment has exactly one init container `backup-gate` on the tools
    image, mounting the named claim and **not** the config-secret claim, its target release equal to the app image's
    tag; (xxiv) with `enabled: false`, no init container and no `backup` volume, with the positive control on the
    same file; (xxv) `helm lint` refuses neither choice naming `backup`, and passes each; (xiv) widened to
    `backup.image.digest` (render and the bad-digest refusal). Every `ci/*.yaml` makes a choice (one `false`).
    (v) already loops `initContainers` (`grep -n initContainers scripts/chart_meta_check.py`).
  - **`chart_upgrade_check.py`:** the predecessor's `ci/` values carry no `backup`, so the candidate's schema refuses
    them — a finding, information-only until the first release (`grep -n "release:" scripts/applied_migrations.sha256`
    → `none`). That finding is the intended explicit choice; the Run page says an existing values file must add it.
  - **Bites:** **C1 (injection)** mount `config-secret` in the gate → (xxiii) red naming it; **C2 (injection)** give
    the gate `QUARKUS_DATASOURCE_JDBC_URL` → red (via `is_app_container`'s demands) — recorded so the trap is known;
    **C3 (injection)** drop the `anyOf` → (xxv) red; **C4 (injection)** render the target release from a hard-coded
    string → (xxiii) red on the file whose `image.tag` differs.
  - **Live (O5):** (a) the recorded kind run, owner's step to OK the install, asked when this phase starts; (b) the
    render checks only, and the retrospective names what that leaves unproven (admission under `restricted`, the
    claim's permissions under `fsGroup`, R7, Recreate's termination order in practice).
  - **Done when:** `python3 scripts/chart_meta_check.py` green (at refine: `chart meta check OK`, re-run as baseline);
    `chart_upgrade_check.py` run and its finding read; C1–C4 recorded; O5's run recorded or its absence named.

- [ ] **4 docs — state, the Run pages, coverage.**
  - **State:** ADR 0098's status line (built: decisions and where), its "store-leg choice" phrase deleted, decision 9's
    image sentence per O6; ADR 0064 (`grep -n -i "backup" docs/decisions/0064-*.md`: the "(proposed)" and "deferred"
    lines point at ADR 0098 as built); ADR 0056 §3 gains the index form for the gate in place (its reasons stand for
    the mirror), and §7 per R4; ADR 0067's *"keeps its two images"* narrowed (Context); ADR 0057 §3's and ADR 0091's
    "both images"/"image pair" narrowed to what each still means (the recipe's two, the release's three) — each site
    from the prose sweep in the inventory, re-run here (this CR wrote prose in phases 1–3).
  - **Use cases (per O1):** the owner use case's text gains the gate (A6 rewritten under (a)), the backup use case
    gains the index capture and A4 against the live store; coverage criteria added with `gaps:` naming the self-test
    and `chart_meta_check.py`, `status: planned` kept.
  - **Site** (the sibling split above): `run/upgrade.mdx` Kubernetes tab — the gate replaces the hand procedure,
    `--wait --timeout`, Flux/Argo (R3/R5/R6 as measured), the hand procedure kept for `backup.enabled: false`;
    `run/backup-and-restore.md` — § Under Kubernetes (the gate, the claim, `backup.keep`, `backup.request` as a
    commit, the one-time key export with `kubectl cp`, the **store's versioning as the operator's duty**, the
    downtime) and § Restore under Helm rewritten to the one-off Job (scale to zero, pause Argo's sync, empty the
    database, `--key-from`, the record, the config-secret read last); `run/kubernetes.md` the values. Every command on
    them is one O5 (a)'s run executed, or marked unproven. No Use page: no member or guest sees anything different.
  - **Done when:** every `scripts/validate_*.py` green; `npm run check` in `site/` green; the prose sweep re-run with
    every remaining hit spared by name.

## Definition of done

- G1–G8, T1–T4 and C1–C4 recorded with lever and result; the Compose rehearsal unchanged and green.
- The chart renders the gate with a claim and nothing with `enabled: false`, and refuses neither choice; the gate
  never mounts the config-secret claim (C1 is the permanent guard: invariant (xxiii)).
- `veyq-tools` is built, runs its self-test inside itself, joins the publish's signing and `manifest.json`, and every
  release guard holds it. Registry writes and keyless signing are named as not run (no remote).
- Coverage per O1, `python3 scripts/validate_coverage.py` green; the Run pages above updated, `site/` check green.
- **Baseline** — run on a detached worktree of `HEAD` before the first edit:
  `for f in scripts/validate_*.py; do python3 "$f" >/dev/null 2>&1 || echo "RED $f"; done`, plus
  `python3 scripts/chart_meta_check.py` and `python3 scripts/instance_backup_selftest.py`.
- **No CI run exists in this repository** (no remote): "green" is the local run over the committed tree,
  `python3 scripts/run_pipeline.py`, or the per-phase commands above.

Implementation has the owner's go (2026-10-16) and every answer (2026-10-17). Its entry condition — `cr-signed-release-manifest` landed first, since both edit the publish step, `release_assets.py` and `validate_release_identity.py` — holds (merged at `a0a4aeb1`).

## Retrospective

(filled at implementation)
