# ADR 0071 — Both targets run every container unprivileged, reachable only by the peers it serves

- Status: Accepted
- Date: 2026-09-23

## Context

[ADR 0064](0064-kubernetes-helm-deployment-target.md) made every chart pod pass the `restricted` Pod Security
Standard. The compose recipe never followed: no service sets `user:`, `cap_drop`, `no-new-privileges` or
`read_only`, and Garage runs as uid 0 under compose but as 1000 in the chart. **Reachability** is open on both
targets. Every compose service shares one network, so the internet-facing proxy can open a connection to the
database. The chart has no NetworkPolicy, so any pod in the cluster can reach the app directly. The per-IP
rate limiter keys on the forwarded client address, so such a pod can also forge it
([cc.security.rate-limit](../crosscutting/cc.security.rate-limit.md)). The proxy's own Docker socket, the
worst of it, is answered by [ADR 0063](0063-database-driven-tenant-ingress.md): Traefik runs the file provider
only.

Forces:

- **Compose has no `fsGroup`.** A named volume inherits ownership only from the image directory it covers
  (ADR 0064, ADR 0063 § Consequences). Measured on fresh volumes with the pinned images:
  - `mariadb:11.4` as 999, with all capabilities dropped, `no-new-privileges`, and a read-only root with tmpfs
    `/run/mysqld` and `/tmp`, comes up healthy. The image owns `/var/lib/mysql`.
  - `dxflrs/garage:v2.0.0` as 1000 exits: `Unable to create LMDB data directory: Permission denied`. The image
    is scratch-based and seeds nothing.
  - `traefik:v3.6` as a non-root uid binds `:80`/`:443`, because Docker lets containers bind low ports by
    default. But it **skips its ACME resolver silently**
    (`unable to get ACME account: open /acme/acme.json: permission denied`). The container stays up and no
    certificate is ever issued.
- **The ingress controller is the operator's.** Its namespace and labels are unknowable to the chart
  (ADR 0064), and cert-manager's HTTP-01 solver pods run in the release namespace.

## Decision

1. **The same container posture on both targets.** Every container:
   - runs as a non-root user;
   - drops every capability;
   - cannot escalate privileges;
   - has a read-only root filesystem, with explicit writable mounts: its volumes, plus tmpfs/`emptyDir` for
     scratch.

   The chart adds `readOnlyRootFilesystem` to what `restricted` already holds. Compose adds
   `cap_drop: [ALL]`, `no-new-privileges` and `read_only` to every service. It adds `user:` only where the
   image starts as root: MariaDB 999, Garage 1000 as in the chart, and Traefik. The app and admin images
   already declare a non-root `USER`, and a second copy of that uid would drift from the Dockerfile the guards
   read.
2. **Compose sets volume ownership with a one-shot init service.** It runs before Garage and Traefik
   (`depends_on: … service_completed_successfully`) and holds only `CAP_CHOWN`. It changes a volume root only
   when that root is not already owned by the target uid: a no-op after the first run, and it never walks a
   store's data tree. Its image is one the recipe already pins, so it adds no supply-chain input.
3. **Reachability is default-deny on both targets, then exactly the paths the stack uses.**
   - **Compose: separate networks.** The proxy shares one with the app and the store; the database shares one
     with the app alone; the admin container shares one with the app and the proxy, which routes to it when
     `ADMIN_DOMAIN` is set. The internet-facing process cannot open a connection to the database.
   - **Helm: a NetworkPolicy set.** Ingress is denied to every pod the chart deploys, then allowed:
     - the app's public port from the ingress controller alone, and its admin listener from the admin pod
       alone ([ADR 0065](0065-admin-ui-cli-separate-service.md) § admin channel, which introduced that
       second rule);
     - the store's S3 port from the ingress controller and the app;
     - its admin port from the app alone;
     - the database from the app alone;
     - the admin pod from the ingress controller, only when `admin.domain` is set;
     - cert-manager's solver pods from the ingress controller. Without that rule a default-deny fails every
       ACME challenge.

     Egress is narrowed where it is knowable: the database and the store get only what they use, and the
     admin pod reaches only the app's admin listener. The app's egress stays open, because it sends SMTP to hosts the operator
     chooses and talks to an API server whose address is cluster-specific.
   - **The operator names the ingress controller.** `networkPolicy.enabled` defaults to `true`, and while it
     is, the schema requires `networkPolicy.ingressFrom` (a list of NetworkPolicy peers). An install without
     it fails and says why; the chart never guesses a namespace. An operator whose CNI does not enforce
     policy, or who writes their own, sets `enabled: false` explicitly.
4. **`quarkus.http.proxy.trusted-proxies` is an optional chart value with no default, and compose does not
   set it.** A default would have to name the controller's address. On a typical cluster that is an ephemeral
   pod IP inside the same CIDR as every other pod, so any range wide enough to survive a restart trusts every
   pod, which is the finding again. The selector-based policy is what confines who can send a forwarded header.
   The value is for topologies with a stable proxy address: a host-network controller or an external load
   balancer. On compose, decision 3 leaves the app's public port reachable only from its network peers. Of
   those, the proxy is the one that forwards a client's address. The admin container talks to the admin
   listener, not to the public port.

## Consequences

- **Silence is the failure mode, so it drives the proofs.** A read-only root that misses one scratch path
  fails at runtime, never at `docker compose config`, and Traefik's ACME failure keeps the container up. A
  change to either target is proven by a live `up` of the production recipe that reads Traefik's log, not by
  container state. A third silent failure of the same kind: Traefik reads the app's routing file under a uid of
  its own, and a file the app left owner-only stops its file provider while the proxy stays up and routes nothing
  (measured on `traefik:v3.6`). The app therefore writes that file world-readable; it is routing config, never a
  secret.
- **What cannot be proven here, named.** `helm template` proves what the chart renders, not that a cluster
  enforces it. Whether a NetworkPolicy binds depends on the CNI, and this repository has no cluster to run
  one on.
- **Compose networks separate by peer, not by port.** The app's admin listener stays reachable from every
  container that shares a network with the app. [ADR 0065](0065-admin-ui-cli-separate-service.md) residual
  (i) names that and why it costs nothing. Only the Helm policy separates the two ports.
- **Commands that join a stack network name a network.** A backup capture that reaches the store joins the
  network the store is on, so the backup runbook changes with the network names.
- **Rejected — Garage and Traefik at uid 0 with only capabilities dropped.** It avoids the init step, but it
  gives up parity with the chart and leaves the internet-facing process root inside its container.
- **Rejected — a derived image per third-party service,** to bake the ownership in. The recipe builds nothing
  ([ADR 0057](0057-release-identity-and-deployment-recipe.md),
  [ADR 0067](0067-store-provisioning-from-a-declared-type.md)).
- **Rejected — a permissive policy default** that leaves the public ports open to every namespace until the
  operator narrows them. It would ship the finding open on every install that took the defaults.

## Related

- [ADR 0063](0063-database-driven-tenant-ingress.md) (the file-provider-only proxy this builds on).
- [ADR 0064](0064-kubernetes-helm-deployment-target.md) (the `restricted` posture the chart already holds).
- [ADR 0065](0065-admin-ui-cli-separate-service.md) (the admin container, a second proxy in front of the app).
- [cc.security.rate-limit](../crosscutting/cc.security.rate-limit.md) (the forwarded-address trust this narrows).
