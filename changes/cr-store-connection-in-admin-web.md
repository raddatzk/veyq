---
id: cr-store-connection-in-admin-web
title: The object store is connected and checked in admin-web, not in environment variables
status: planned
satisfies: ["uc.admin.configure-object-store"]
adrs: ["0089", "0067", "0050", "0013", "0056", "0063", "0065"]
invariants:
  - "Operator-blind E2EE: the store holds ciphertext only; its credentials are server-held and sealed, never returned"
  - "Self-hostable by default: the bundled store keeps working with no setting entered beyond choosing it"
  - "API-first: the store setting and its check are declared in contracts/api/instance.openapi.yaml first"
---

## Goal

Move the object-store connection out of environment variables into a Server-Owner setting in the admin console
and its CLI, as [ADR 0089](../docs/decisions/0089-instance-settings-live-in-admin-web-the-environment-only-bootstraps.md)
decides: a type (*S3-compatible*, the default, or *Garage*), the connection fields, a **check**, credentials sealed
under the config-secret key with a fingerprint per value, a **bundled-store hint** as the one environment
exception, the store applied **at runtime**, and a checked store as the precondition for creating a tenant. The
compose file, `.env.example` and the Helm values lose every store variable but the hint; the backup tool takes the
store credentials from the operator.

## Context & constraints

- [ADR 0089](../docs/decisions/0089-instance-settings-live-in-admin-web-the-environment-only-bootstraps.md):
  decisions 1–7 and the rejected alternatives.
- [ADR 0067](../docs/decisions/0067-store-provisioning-from-a-declared-type.md): an unknown store is never
  provisioned (decision 1) — the check writes only a probe object in the configured bucket; provisioning is its own
  seam (decision 2); network work never gates the boot and its outcome is readable on the Server-Owner surface
  (decision 3). Decision 3's "configuration is checked at boot" changes and is edited in phase 5.
- [ADR 0050](../docs/decisions/0050-operator-config-secret-encryption.md): the seal (`ConfigSecretSeal`, AES-GCM,
  no AAD today) and the single global fingerprint that becomes per value; the tenant SMTP passwords move to the
  per-value fingerprint too, so re-entering one secret cannot hide another still sealed under a lost key.
- [ADR 0013](../docs/decisions/0013-blob-storage-presigned-direct-to-store.md) §1 (a byte path is required) and
  §4 (public endpoint, CORS); [ADR 0063](../docs/decisions/0063-database-driven-tenant-ingress.md) (the tenant
  ingress, whose store route takes the bucket at boot today).
- [ADR 0056](../docs/decisions/0056-instance-durable-state-and-cold-logical-backup.md) decision 2: the capture is
  cold, so the backup tool cannot ask the app; `scripts/instance_backup.py` reads `VEYQ_BLOB_STORE_*` today and its
  restore emptiness gate (`NOT_DATA`) must learn the new settings table.
- The store is decided at boot today, in four places: `BlobStoreProducer` (one `S3Client`, an in-memory fallback on
  a blank endpoint), `BlobStoreStartupCheck` (boot fails on a blank endpoint), `AppShellRoutes` (the CSP
  `connect-src` store origin) and `TenantIngressSinkProducer` (the bucket in the store route). `S3BlobStore`'s
  presigner cache is never invalidated. All four follow the current setting after this CR.
- `ConfigSecretKeyGuard.state()` adopts a fingerprint on read; `cr-config-secret-state-on-admin-channel` adds the
  read-only state — whichever lands second builds on the other.
- Pre-V1: the baseline changes in place together with its pin line, and every database is recreated.

### Locators

| What | Locator |
|---|---|
| Every store config key and reader | `git grep -nE "blob-store|blobStore\.|BlobStoreProducer|StoreType|SigningMode|storeOrigin" -- backend` |
| Store environment in recipes, run configs, profiles, harness | `git grep -nE "VEYQ_BLOB_STORE_|VEYQ_S3_|GARAGE_ADMIN_TOKEN|s3AccessKey|s3SecretKey|garageAdminToken|storeDomain"` |
| Provisioning and its Server-Owner read | `git grep -n "StoreProvision\|getInstanceStoreProvisioning"` |
| The seal and its fingerprint | `git grep -n "ConfigSecretSeal\|config_secret_fingerprint\|recordReseal"` |
| Tenant creation | `git grep -n "createTenant\|TenantHandler"` |
| Guards that read store config | `git grep -nE "VEYQ_BLOB_STORE|blob-store|garage" -- scripts` |
| Docs and site that describe store env | `git grep -nE "VEYQ_BLOB_STORE_|VEYQ_S3_|blob-store" -- docs site infra/README.md` |

## Phases

- [x] 1 contracts — on the admin channel: read and write the store setting (type; endpoint, public endpoint,
      bucket, region, access key; secret key and Garage admin token write-only, `x-veyq-visibility: token`; the
      bundled choice), run its check and read the check's result per step, and read whether a bundled hint exists;
      tenant creation's refusal while no store has passed its check. Each new operation in `OperatorBlindProbeTest`.
      · Done when: `generate:api` in `admin-web/` and the contracts guards green, and the predicted reds named.
- [ ] 2 backend — the settings table in the baseline (and in `instance_backup.py`'s `NOT_DATA`); sealing with a
      fingerprint per value for the store secrets and the tenant SMTP passwords; a store that follows the current
      setting (the signing client, the presigner cache, the CSP store origin, the ingress store route); no boot
      failure and no in-memory store without a setting outside `%test` — uploads refused with a reason; Garage
      provisioning run from the setting or the hint; the check (bucket, probe object by presigned PUT and GET
      against the public endpoint, CORS preflight with the public origin); tenant creation gated on a passed check;
      changing type, endpoint or bucket refused once blobs exist. · Done when: `./gradlew check` green; bites: a
      check against a store with no bucket fails at that step and creates none; a tenant create before a passed
      check is refused; a store secret re-entered under a new key leaves the SMTP secret reported unreadable;
      object deletion still has its one caller (`validate_store_reclaim_arity.py`).
- [ ] 3 admin-web + CLI — Server → Store: the type list, the fields, *Use the bundled store* where a hint exists,
      save, check with each step's result, the re-entry prompt when credentials cannot be opened; create-tenant
      closed with the reason until a check passed; `veyq-admin store show | set | check`. · Done when: `npm run check`
      in `admin-web/` green; a browser pass sets the bundled store, checks it, creates a tenant, and shows a failing
      check against a wrong bucket.
- [ ] 4 recipes + tooling — compose, `.env.example`, Helm values, schema and templates keep only the bundled hint
      (Garage still gets its own secrets); `.run/Run Backend.run.xml`, the dev and e2e profiles and
      `scripts/run_web_e2e.py` set the store through the hint or the API; `instance_backup.py` takes the store
      credentials from the operator for the run (the bundled case from the recipe's environment); the guards follow.
      · Done when: `python3 scripts/chart_meta_check.py` and the compose guards green; the web e2e suites green
      including the backup rehearsal and the upgrade proof (run named); a fresh compose instance uploads after
      choosing the bundled store and nothing else.
- [ ] 5 docs — ADR 0067 decision 3 and ADR 0050 edited to what is built; `uc.admin.configure-object-store`'s
      coverage refs; the restore use-case's store and credential steps; the Run pages' store and backup sections
      (unless `cr-run-area-content` is past them, then that CR's pages). · Done when: trust-chain green and
      `npm run check` in `site/` green.

## Definition of done

A Server Owner connects the bundled store or an S3-compatible one in the admin console, checks it, and creates a
tenant only after the check passed; no recipe passes store credentials through the environment; a lost
config-secret key asks for the store credentials again instead of bricking the instance; the backup tool works
with operator-supplied credentials; backend, admin-web, chart and e2e checks are green and the new use-case's
criteria carry their refs.

## Retrospective

(filled at close)
