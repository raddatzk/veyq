---
title: Troubleshooting
description: What to check when uploads are refused, a certificate does not come, the config-secret key reads ABSENT or MISMATCH, the console cannot be reached, a tenant's domain does not answer, or mail is refused.
sources:
  - docs/features/admin/use-cases/uc.admin.tenant-reachability.md
  - docs/features/admin/use-cases/uc.admin.configure-object-store.md
  - docs/features/admin/use-cases/uc.admin.tenant-mail-settings.md
---

Most failures in veyq are loud by design: a refusal names its reason, and a missing setting stops the thing that
needs it, not the whole instance. Start with the admin console's **Server** section and the application's log
([Operations](/veyq/run/operations/#logs)).

## Uploads are refused

| What users see, or the reason code | Cause | What to do |
|---|---|---|
| `store_unavailable` | No object store is set, or its credentials cannot be opened (the config-secret key is ABSENT or MISMATCH) | Set and check the store in **Server → Object store**; re-enter its credentials if the console asks for them |
| `quota_exceeded`; members are told there is no space left | The tenant's quota, or the link's own limit, is reached. The trash counts until it is emptied | Raise or remove the quota, or ask the tenant to empty its trash ([Plan your instance](/veyq/run/plan/#storage-and-quotas)) |
| `budget_exceeded` | A single file over 20 GiB | — |
| Uploads fail right after a start | A Garage store is still being set up | Wait for the **Object store** panel to show it ready |
| The store's set-up failed | Garage could not be reached or refused a step; the log names it | Make the store reachable, then run **Check store** |
| Uploads fail in the browser, with a CORS error | A store of your own without a CORS rule for the domain the user is on | Allow that origin in the bucket's CORS rule ([Storage and email](/veyq/run/storage-and-email/#the-object-store)) |

**A tenant cannot be created** (`store_not_ready`): no store has passed its check yet. Open **Server → Object store**,
save it, and run **Check store**; the step that failed is named
([uc.admin.configure-object-store](https://github.com/raddatzk/veyq/blob/main/docs/features/admin/use-cases/uc.admin.configure-object-store.md)).

## A certificate does not come

- **The domain does not resolve to the instance,** or port 80 does not reach it from the internet. HTTP-01 needs
  both; a tenant's DNS must be in place before the tenant is created.
- **Let's Encrypt's limits:** five failed validations lock a hostname out for an hour, and a registered domain gets at
  most 50 certificates a week. Test against the staging directory (`VEYQ_ACME_CASERVER` /
  `ingress.tls.acme.server`).
- **Docker Compose:** `permission denied` in Traefik's log means the certificate volume is not owned by Traefik's
  user; `docker compose -f infra/docker-compose.prod.yml up -d` runs the `volume-ownership` step that fixes it.
- **Kubernetes:** cert-manager must be installed, with its CRDs; `kubectl -n veyq get certificate` shows each order.

## The config-secret key reads ABSENT or MISMATCH

Read it in **Server → Config-secret key**, or with `veyq-admin config-secret`.

- **ABSENT** — the application has no key: the file is missing and could not be created, or it cannot read it. After
  a restore, the key file must belong to the application's user, uid 10001, with mode `0600`; the log says so when
  the file is not readable.
- **MISMATCH** — a key is in place, but not the one some stored secret was sealed with. Usually the key was not
  restored, or the volume was not mounted and a recreated container generated a new one.

Put the original key file back and restart the application. Without it: each affected tenant's owner re-enters the
relay password in the app, and you re-enter the credentials of a store of your own in the console. Content is never
affected ([Storage and email](/veyq/run/storage-and-email/#the-config-secret-key)).

## The admin console cannot be reached

- **The tunnel:** on Compose the console listens on `127.0.0.1:8081` of the host only (`ADMIN_PORT`); reach it with
  `ssh -L 8081:127.0.0.1:8081 you@the-host`. On Kubernetes, with
  `kubectl -n veyq port-forward svc/veyq-admin 8081:8080`.
- **"This instance can't be reached right now."** The console loads but the application does not answer it: check
  that the application runs (`docker compose … ps`, `kubectl … get pods`).
- **The application does not start** and its log says the admin listener could not bind: something else holds port
  8090 in its container.
- **The admin container exits** at start: `VEYQ_HSTS_ENABLED` is something other than `true` or `false`.
- **A Server-Owner operation answers `404` on the instance's domain:** that is by design. These operations answer only
  through the admin surface ([First-run setup](/veyq/run/first-run/#reach-the-console)).

## A tenant's domain does not answer

([uc.admin.tenant-reachability](https://github.com/raddatzk/veyq/blob/main/docs/features/admin/use-cases/uc.admin.tenant-reachability.md))

- **DNS does not point at the instance.** From outside, this looks exactly like a route that was never written; check
  DNS first.
- **The tenant is offline** (taken offline in the console). It does not route, by design.
- **The route was not written.** The application logs when it cannot write the routing file (Compose) or an Ingress
  (Kubernetes); on Kubernetes `shared` TLS without `ingress.tls.secretName` disables tenant routing, and says so.
- **The certificate is not there yet** — see above.

## Mail is refused

([uc.admin.tenant-mail-settings](https://github.com/raddatzk/veyq/blob/main/docs/features/admin/use-cases/uc.admin.tenant-mail-settings.md))

- **`mail_unavailable`** — the tenant has no mail set up, it is switched off, or its relay needs a password the
  application cannot open (the config-secret key, above). Member invitations and password resets are refused until
  the tenant's owner sets it up.
- **`mail_send_failed`** — the relay is set up but did not accept the mail within 10 seconds. The invitation is
  withdrawn and can be sent again. The log names the recipient and the relay's error; check that the application may
  reach the relay's host and port.

## How far this is proven

Each cause and code on this page is the backend's or the console's own, read from their code, contracts and tests. The
fixes for Kubernetes have not been tried in a cluster.
