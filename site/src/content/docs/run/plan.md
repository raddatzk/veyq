---
title: Plan your instance
description: Choose Compose or Kubernetes, size the host, plan for storage growth and quotas, and set up the domains an instance needs.
sources:
  - docs/decisions/0064-kubernetes-helm-deployment-target.md
  - docs/decisions/0098-on-kubernetes-the-start-that-would-migrate-backs-up-first.md
  - docs/decisions/0063-database-driven-tenant-ingress.md
  - docs/decisions/0073-storage-quota-belongs-to-whoever-pays-for-the-store.md
  - docs/features/admin/use-cases/uc.admin.tenant-storage-quota.md
  - docs/crosscutting/cc.storage.object-lifecycle.md
  - docs/features/memory/enablers/en.memory.derivatives.md
---

Decide four things before you install: the deployment target, the size of the host, how storage will grow, and the
domains. Nothing here has been measured on a real workload: veyq states no latency, throughput or availability target,
its load model is a household, and the expensive work (encryption, thumbnails) runs in the users' browsers, not on
the server.

## Docker Compose or Kubernetes

Both targets run the same images and the same procedures; every page in this area shows both where they differ.

| | Docker Compose | Kubernetes (Helm) |
|---|---|---|
| What you need | One host with Docker (Compose v2), `python3` 3.9 or later and `cosign` (v3.1.3 or later) | A cluster you already run (Kubernetes 1.31 or later), the Gateway API CRDs (v1.5 or later) and an implementation of them, and cert-manager 1.21 or later if the chart should obtain certificates |
| Install and upgrade | `veyqctl`, from a release's files; upgrades by hand, a new major only from the previous one's last release | `helm install` / `helm upgrade` by digest |
| Proxy and TLS | The bundled Traefik, with Let's Encrypt over HTTP-01 | Your Gateway API implementation, serving the chart's `Gateway`; TLS by `gateway.tls.mode`, optionally through the chart's cert-manager issuer |
| Object store | The bundled Garage by default, on the instance's own origin under a path; or a store of your own, the bundled one left out | A store of your own by default; the bundled Garage opt-in (`garage.enabled`), one node on one claim, on a host of its own (`storeDomain`) |
| Network isolation | Separate container networks | A NetworkPolicy set, enforced only if your CNI enforces NetworkPolicy |
| Database | The bundled MariaDB | The bundled MariaDB, or your own (`mariadb.enabled: false`, `externalDatabase.jdbcUrl`) |
| Backup and restore | Rehearsed with the backup tool | The chart's backup gate captures the database and an index of the store before every release change; a restore is a one-off Job; both run once on a kind cluster |
| Proven | Every stack-lane run installs the hardened recipe through `veyqctl` from a release built on the host; no published release installed through `veyqctl` yet | The rendered chart is checked on every run; one kind cluster ran it once, for the backup gate, without a Gateway implementation |

If you have no cluster, Compose is the smaller and better-proven choice
([ADR 0064](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0064-kubernetes-helm-deployment-target.md)).

## Size the host

No CPU, memory or disk minimum is documented. What the recipes set:

| Component | Docker Compose | Kubernetes requests | Kubernetes limits |
|---|---|---|---|
| Application | `mem_limit` 1 GiB (`VEYQ_MEM_LIMIT`) | 250m CPU, 512 Mi | 1 Gi |
| Admin surface | — | 50m CPU, 32 Mi | 128 Mi |
| MariaDB | — | 250m CPU, 256 Mi | 1 Gi |
| Garage (with `garage.enabled` on Kubernetes) | — | 100m CPU, 128 Mi | 512 Mi |

The application's JVM sizes its heap at 60 % of the container's memory limit; without a limit it sizes off the
host's memory. Change the Compose and Helm values together, or neither. On Kubernetes the default claims are 10 Gi
for MariaDB, 100 Mi for the config-secret key and, with `garage.enabled`, 50 Gi for Garage's data and 1 Gi each for
its metadata and snapshots.

## Storage and quotas

**What grows.** Every photo and video is stored as its original plus up to two smaller copies the uploading browser
makes (a 512 px thumbnail and a 2048 px preview; a video gets a still image for both)
([en.memory.derivatives](https://github.com/raddatzk/veyq/blob/main/docs/features/memory/enablers/en.memory.derivatives.md)).
A file in Depot is one stored object. Almost nothing is deduplicated: the same file uploaded twice is stored twice,
because showing the operator which files are equal is the leak veyq declines. The one exception: a Memory's cover or
banner equal to a photo or video of its own gallery shares that item's stored object
([ADR 0013](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0013-blob-storage-presigned-direct-to-store.md#5-one-stored-object-for-an-equal-file-inside-one-node-nothing-wider)).
One file may be up to 20 GiB. Trashed items and spaces in their deletion grace keep their bytes for 30 days, until the
scheduled purge.

**Quotas.** You can give each tenant a storage quota: the most it may keep on your store
([ADR 0073](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0073-storage-quota-belongs-to-whoever-pays-for-the-store.md),
[uc.admin.tenant-storage-quota](https://github.com/raddatzk/veyq/blob/main/docs/features/admin/use-cases/uc.admin.tenant-storage-quota.md)).

- By default a tenant has no quota; your disk is the limit.
- It counts every stored object in the tenant's spaces (originals, the smaller copies, covers), uploads in progress at
  their declared size, and the trash until it is purged. It does not count what lives in the database.
- An upload that would go over it is refused whole, before anything is stored; nothing already stored is removed.
- You set it in the admin console, or with `veyq-admin tenant quota --tenant-id <id> --gib <n|none>`. A tenant's owner
  can never raise it, and you see each tenant's usage, never a member's.
- Within it, a tenant's owner sets how much a share link may receive (10 GiB by default, `0` switches link uploads
  off).

**Backups.** A capture is the database and an index of the stored objects, never the objects, so a capture is about the
size of the database ([Backup and restore](/run/backup-and-restore/)). The objects are kept by the store — or, on
the bundled store, which keeps no versions, by an **object pool** on another disk or store: plan space there for every
stored object once, growing by the new uploads at each backup and pruned to what the newest captures need.

## Domains

| Domain | Docker Compose | Kubernetes | Notes |
|---|---|---|---|
| The instance (`VEYQ_DOMAIN` / `domain`) | Required | Required | The bootstrap domain: routed from configuration, so the instance is reachable before any tenant exists. Changing it later is untested |
| The bundled object store | — (served under the instance's own origin) | `storeDomain`, required with `garage.enabled`, a host of its own | Presigned uploads and downloads go straight from the browser to this origin; a store of your own has its own address |
| The admin surface (`ADMIN_DOMAIN` / `admin.domain`) | Optional, unset by default | Optional, unset by default | Leave it unset and reach the console over an SSH tunnel or a port-forward; if you set it, only after the Server Owner exists, and choose a name you publish nowhere |
| Each tenant's own domain | Required per tenant | Required per tenant | One domain per tenant, routed when the tenant is created, with no restart. Written into every invitation and recovery link the tenant's members get; the Server Owner can move a tenant to another domain ([Tenants](/run/first-run/#tenants)), which stops the old one answering at once, so a link mailed before the move no longer opens. A restore targets the domain the backup holds ([Backup and restore](/run/backup-and-restore/)) |

Every domain must resolve to the instance **before** it is used, and port 80 must reach the instance from the
internet for Let's Encrypt's HTTP-01 challenge. Each new tenant domain is a certificate order: test against Let's
Encrypt's staging directory first, because five failed validations lock a hostname out for an hour, and a registered
domain gets at most 50 certificates a week
([ADR 0063](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0063-database-driven-tenant-ingress.md)).
