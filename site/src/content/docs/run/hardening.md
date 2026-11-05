---
title: Hardening
description: A checklist of what is hardened by default, what you must keep that way, and the few settings that are yours.
sources:
  - docs/decisions/0071-runtime-hardening-on-both-targets.md
  - docs/decisions/0118-the-app-image-is-distroless-and-carries-its-own-commands.md
  - docs/decisions/0117-the-web-client-enforces-trusted-types.md
  - docs/decisions/0065-admin-ui-cli-separate-service.md
  - docs/decisions/0050-operator-config-secret-encryption.md
  - docs/crosscutting/cc.security.csp.md
  - docs/crosscutting/cc.security.rate-limit.md
  - docs/decisions/0094-a-clients-address-is-the-hop-the-network-vouches-for.md
---

Most of veyq's hardening is in the recipes and needs nothing from you except not undoing it. This page lists it by
what it protects, says what is yours to decide, and names what stays open. What the operator can and cannot see is a
separate question, answered by the [Security](/security/start-here/) area.

## Run only what was signed

Deploy a release by digest, after checking its signatures: [Verify a release](/run/verify-a-release/). A tag can
move between the check and the pull; a digest cannot. Pin both images, and on Kubernetes the tools image and the chart, to the digests
that check resolved.

## Containers run unprivileged

Every container drops all capabilities, cannot gain privileges, runs as a non-root user and has a read-only root
filesystem ([ADR 0071](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0071-runtime-hardening-on-both-targets.md)).
On Kubernetes every pod passes the `restricted` PodSecurity standard, so you can label the namespace with it from the
start. Do not add capabilities or a writable root in an override: a path a process writes is a named volume or a
`tmpfs` that was measured for it.

The application's image goes further: it is **distroless**
([ADR 0118](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0118-the-app-image-is-distroless-and-carries-its-own-commands.md)) —
the Java runtime, CA certificates and time zones, and no shell, no package manager, no `curl` and no `tar`. Its
healthcheck and the few things a restore puts into it run on its own `java` (`/opt/veyq/image-commands.jar`). So
`docker compose exec app sh` and `kubectl exec … -- sh` find nothing to run; [Troubleshooting](/run/troubleshooting/#look-inside-the-application-container)
says what to use instead. The admin and tools images are not distroless.

## Keep the admin listener local

The application answers Server-Owner operations (setup, tenants, instance reads) only on a second, internal listener,
port 8090, and only to a peer that is not on the public internet; the public port answers them with `404`
([ADR 0065](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0065-admin-ui-cli-separate-service.md)).

- **Route only the public port, 8080,** from any proxy of your own. Nothing but the admin container may ever be
  pointed at 8090.
- **Leave the admin domain unset** (`ADMIN_DOMAIN` / `admin.domain`) unless you need it, and never set it before the
  Server Owner exists: it puts the admin surface, and with it the listener, on the internet.

What stays open: on Compose, every container that shares a network with the application can reach 8090; on a cluster
whose CNI does not enforce NetworkPolicy, every pod can; a NAT in front of the application can make a public peer look
local.

## Name who may reach what

- **Compose:** three networks separate the peers. The proxy never joins the database network, and the database
  network has no route out.
- **Kubernetes:** the chart renders a default-deny NetworkPolicy set and admits only the paths the stack uses. It
  cannot guess your gateway's data-plane pods, so the install fails until you set `networkPolicy.ingressFrom`. **A CNI that
  does not enforce NetworkPolicy renders the policies and enforces none of them.**

## List only the hops in front of the gateway

The application takes the client address from the rightmost `X-Forwarded-For` entry that its trusted-proxies list does
not name, and the rate limits key on it
([Networking](/run/networking/#the-client-address-and-the-rate-limits)). Who may hand it that header is decided
by the network: on Compose the network separation, on Kubernetes the NetworkPolicy. So `app.trustedProxies` lists
only the hops **in front of** your gateway, such as an external load balancer, and stays empty when there are none.
Never list the gateway's own pods: a pod-IP range wide enough to survive a restart trusts every pod beside it.

## Keep the config-secret key apart

The application seals each tenant's SMTP password, the instance mail's password, and the secrets of a store of your own, under a key file it
generates on first start, on the
`veyq-config-secret` volume (Compose) or claim (Kubernetes; the chart keeps the claim on uninstall)
([ADR 0050](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0050-operator-config-secret-encryption.md)).
It defends a leaked database backup, not a compromised host. So:

- Back it up, apart from the database and the store, on a medium of its own, and encrypt the rest of the backup
  ([Backup and restore](/run/backup-and-restore/#capture)).
- Never put it beside the database dump: the two together open every secret it seals.
- Without it the instance boots and serves every page; a tenant whose relay needs a password refuses member
  invitations and password resets, and a store of your own is unreachable until you re-enter its credentials
  ([Storage and email](/run/storage-and-email/#the-config-secret-key)).

## TLS, HSTS and CSP

- **TLS is required.** The session cookie is `Secure` and every invitation link is `https`, so an instance served over
  plain HTTP boots and silently breaks sign-in and invitations.
- **HSTS is on by default** (180 days, no `includeSubDomains`, no `preload`), sent by the application and the admin
  surface alike. Turn it off (`VEYQ_HSTS_ENABLED=false` / `hsts.enabled: false`) only if a hostname of the instance
  is also served over plain HTTP, or your own proxy sets the header already. A browser that saw it keeps it for 180
  days.
- **The Content Security Policy is built in**: scripts only from the instance itself, no framing, and network
  connections only to the instance, its store and the outside services you configured (the breached-password check,
  the map tiles, the place-name lookup and the exchange rates, [External services](/run/capabilities/#external-services)); a service
  without an address, or switched off, is not allowed at all
  ([cc.security.csp](https://github.com/raddatzk/veyq/blob/main/docs/crosscutting/cc.security.csp.md)). It also
  enforces Trusted Types: a browser that supports them refuses any markup or script address the web client did not
  make through its own sanitizer or its short list of library literals
  ([ADR 0117](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0117-the-web-client-enforces-trusted-types.md)).
  The admin console sends its own, stricter policy, also as a header.
- **The other security headers are built in too**, on the application and the admin console alike: no
  content-type sniffing, a page keeps no handle on the page that opened it nor that page on it
  (`Cross-Origin-Opener-Policy`), no other site may embed or read a response of the instance
  (`Cross-Origin-Resource-Policy`), and every browser feature the client does not use — the position and the
  microphone among them — is switched off (`Permissions-Policy`). There is nothing to set; a proxy of yours that
  rewrites or strips response headers must leave them as they are.
- **Test certificates against a staging directory** (`VEYQ_ACME_CASERVER` / `gateway.tls.acme.server`), and switch
  back before anyone uses the instance: staging certificates are not trusted by browsers.

## Rate limits are per instance

The public sign-in and sign-up endpoints, account recovery and invitations are rate-limited per client address and
per account. The buckets live in memory: they are per application instance, and a restart clears them. Behind a
NAT every client shares one bucket
([cc.security.rate-limit](https://github.com/raddatzk/veyq/blob/main/docs/crosscutting/cc.security.rate-limit.md)).
Neither recipe passes a setting for them.

## How far this is proven

The hardened Compose recipe was brought up live once (2026-09-28, on Docker Desktop with locally built images): every
service up, the database unreachable from the proxy, the application reachable through the admin port. The rules are
checked in the repository by `scripts/validate_compose_posture.py`, `scripts/chart_meta_check.py` and
`scripts/validate_images_run_unprivileged.py`, which no CI run has executed yet. The distroless application image ran
lane L1 whole once (2026-11-05, arm64, locally built): `veyqctl install` waited on its own health probe, 172 browser
tests passed, and `veyqctl`'s key placement and ledger read ran against that instance; an amd64 build has not run. On Kubernetes, only what the chart renders is checked; no policy has
been enforced in a cluster.
