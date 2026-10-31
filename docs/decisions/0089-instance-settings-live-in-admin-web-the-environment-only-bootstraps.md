# ADR 0089 — Instance settings live in admin-web; the environment only bootstraps

- Status: Accepted
- Built: yes
- Date: 2026-10-15
- Model: [release-and-deployment](../crosscutting/release-and-deployment.md)

## Context

An instance was configured almost entirely through environment variables: the compose recipe passed about two
dozen, the Helm chart as many values, and the backend read them once at boot. The object store was the largest
block — type, endpoint, public endpoint, signing mode, bucket, region, access key, secret key, and Garage's admin
endpoint and token — and it is also the one an operator is most likely to get wrong, because a wrong value shows only
when a photo will not upload.

Three things push the other way. [ADR 0088](0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md)
already moved the capability switches out of a boot-time config list and into the database, switched by the Server
Owner in admin-web. An environment variable can be changed only by someone with shell access to the host, set only
before the start, and checked only by reading logs. And the backend supports any S3-compatible store
([ADR 0067](0067-store-provisioning-from-a-declared-type.md) decision 1), but the recipes hardcode the bundled
Garage, so using one meant editing the compose file, and under Helm it was not possible at all.

## Decision

1. **Instance settings are set by the Server Owner in admin-web (and its CLI) and stored in the database.** The
   environment carries only what the process needs **before it can reach the database or serve its first
   request**: the database connection, the public domain and proxy posture, the path of the config-secret key file,
   the images, the admin listener. A new setting goes to admin-web unless it is one of those.
2. **The object store is the first setting moved.** The Server Owner enters it in the admin console: a type from a
   list — **S3-compatible** (the default: endpoint, public endpoint, bucket, region, access key, secret key) or
   **Garage** (the same, plus its admin endpoint and token, because veyq provisions it) — and then **checks** it.
   The check for S3-compatible **changes no store configuration**: the bucket exists; one fixed probe object
   under a `veyq-check/` key is written through a presigned PUT against the public endpoint and read back through
   a presigned GET (overwritten by every check and never deleted, so object deletion keeps its one arm, the reclaim
   work list of [ADR 0062](0062-store-object-reclaim-is-a-durable-work-list.md)); and a CORS preflight sent with the
   instance's public origin is answered with that origin.
   Writing an object into the bucket veyq was pointed at is its ordinary data plane; creating a bucket or writing a
   CORS policy is provisioning, which ADR 0067 decision 1 keeps away from an unknown store, and stays away. S3 has no
   portable way to ask what a key *may* do, so the probe is how the check learns it. For Garage, veyq provisions
   first and then runs the same check.
3. **The bundled Garage is the one exception, as a hint, not a setting.** A recipe that brings Garage — Compose by
   default, the chart with `garage.enabled` ([ADR 0064](0064-kubernetes-helm-deployment-target.md) § the bundled
   store is opt-in) — still generates or requires Garage's RPC secret and admin token, because the Garage container
   needs them at its own start, and passes the app a **bundled-store hint** (endpoint and admin token). Where the hint exists the console offers *Use the
   bundled store*; choosing it stores a reference to the hint, not a copy of its secrets. A restore onto a fresh
   install and a lost config-secret key therefore leave the bundled case untouched: it reads the hint of the install
   it runs on.

   **Where the hint exists and the instance holds no store setting — none in use and none pending — the server
   chooses the bundled store itself**: the bundled store is preconfigured, so it needs no step of the Server
   Owner's. The hint exists only where the operator opted into the bundled
   Garage (the Compose default, the chart's `garage.enabled`), so there is nothing left for the Server Owner to
   decide, and a step they have to click is a step that only delays the first tenant. After its boot provisioning of
   the bundled Garage has finished, the server saves the bundled store as the pending setting and runs **the same
   check** the Server Owner's *Check store* runs; decision 5 is unchanged, so only a passing check puts it in use, and
   a failing one stays pending, naming its step, for the Server Owner to check again. The check is kept, not skipped:
   *preconfigured* says nobody has to type anything, not that the public endpoint, the certificate or the CORS rule
   in front of the store work, and decision 6 still gates a tenant on it. The condition is read at every boot and
   nothing marks it done, so a Server Owner who discarded a pending store of their own and left nothing saved gets
   the bundled store back at the next start; a store of their own replaces it as any checked setting does
   ([uc.admin.configure-object-store](../features/admin/use-cases/uc.admin.configure-object-store.md) A2) until the
   first blob fixes it (decision 6). A restore replaces the setting wholesale while the app is stopped, so the
   restored setting wins.
4. **Store credentials are sealed under the config-secret key** ([ADR 0050](0050-operator-config-secret-encryption.md)),
   as the tenants' SMTP passwords are, and each sealed value records the fingerprint of the key that sealed it.
   When the key is absent or is not the one that sealed a value, the console says which values cannot be opened and
   asks for them again. Media are unreachable until the Server Owner re-enters the store credentials — which they
   hold anyway, in the store's own console — but nothing is lost: *degrade, don't brick*, with the store as the
   loudest degradation.
5. **A store setting takes effect once it has passed its check, and then at once, without a restart.** A saved
   setting is *pending*: the store the backend signs against, the store origin in the app shell's CSP and the store
   route in the tenant ingress keep following the setting in use until the pending one passes its own check, which
   replaces it. A typo in a credential or an endpoint therefore reaches the check, never a running instance's media;
   a failed pending setting is corrected or discarded. Until a store has passed its check, the instance runs,
   answers `/api/setup`, and refuses uploads with a reason — never the silent in-memory store, which stays a
   `%test` tool.
6. **A working store is the precondition for a tenant.** Creating a tenant is refused until the current store
   setting has passed its check; nothing else in the console is gated by it. Once the first blob is stored the store
   is fixed: moving an instance's blobs to another store is out of scope here.
7. **A backup gets the store credentials from the operator.** The capture is cold — the app is stopped
   ([ADR 0056](0056-instance-durable-state-and-cold-logical-backup.md) decision 2) — so nothing could sign for it;
   the operator passes the credentials for that run (the bundled case reads them from the recipe's environment as
   today). An operator holds them in a password manager already, because decision 4 asks for them after a lost key.

## Consequences

- The compose file, `.env.example` and the Helm values lose every store variable except the bundled hint; the
  run configuration, the dev and e2e profiles and the e2e orchestrator set the store through the same API the
  console uses, or through the hint.
- **The external services are the second setting moved**, under the same rule: Server-Owner settings applied without
  a restart and followed by the served CSP
  ([ADR 0088](0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md) decision 7).
- Configuration-as-code ([inbox #444](../inbox.md)) is a later, optional layer over these settings — a declared
  value that wins and shows as fixed in admin-web — not a second place to keep them.
- ADR 0050's single global fingerprint becomes a per-value one, so re-entering one secret cannot hide that another
  is still sealed under a lost key.
- The backup tool's store credentials and the restore procedure change: a restored instance carries its store
  setting in the database, sealed under the restored key; a bundled one reads the new install's hint.
- **Rejected:** keeping the store in environment variables (shell access to change, a restart to apply, a log to
  check, and no external store under Helm); keeping only the credentials in the environment and the rest in
  admin-web (two places for one setting); a mandatory store step right after the first login (the console has
  more to offer than tenants, and only tenants need a store); a bundled store that is in use without a check
  (decision 3's second paragraph: its public path is exactly what an operator's domain, proxy and certificate can break); a backup tool that asks the running app for presigned
  URLs (the capture is cold, so no app is running to sign); an admin endpoint that hands out store credentials
  (a surface whose only job is to leak a secret).

For Server Owners, on the docs site: [First-run setup and the admin console](../../site/src/content/docs/run/first-run.mdx),
[Configuration reference](../../site/src/content/docs/run/configuration.md) and
[Storage and email](../../site/src/content/docs/run/storage-and-email.md).
