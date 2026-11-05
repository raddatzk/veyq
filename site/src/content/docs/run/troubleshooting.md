---
title: Troubleshooting
description: What to check when uploads are refused, replacing keys is refused as too large, a certificate does not come, the config-secret key reads ABSENT or MISMATCH, the console cannot be reached, a tenant's domain does not answer, mail is refused, or a user reports an error id.
sources:
  - docs/features/admin/use-cases/uc.admin.tenant-reachability.md
  - docs/features/admin/use-cases/uc.admin.configure-object-store.md
  - docs/features/admin/use-cases/uc.admin.tenant-mail-settings.md
  - docs/decisions/0092-logs-are-operational-and-name-no-person.md
  - docs/features/account/use-cases/uc.account.rotate-keys.md
  - docs/decisions/0104-the-identity-pin-store-is-one-sealed-entry-per-pin-under-a-sealed-head.md
  - docs/decisions/0118-the-app-image-is-distroless-and-carries-its-own-commands.md
---

Most failures in veyq are loud by design: a refusal names its reason, and a missing setting stops the thing that
needs it, not the whole instance. Start with the admin console's **Server** section and the application's log
([Operations](/run/operations/#logs)).

## Uploads are refused

| What users see, or the reason code | Cause | What to do |
|---|---|---|
| `store_unavailable` | No object store is set, or its credentials cannot be opened (the config-secret key is ABSENT or MISMATCH) | Set and check the store in **Server → Object store**; re-enter its credentials if the console asks for them |
| `quota_exceeded`; members are told there is no space left | The tenant's quota, or the link's own limit, is reached. The trash counts until it is emptied | Raise or remove the quota, or ask the tenant to empty its trash ([Plan your instance](/run/plan/#storage-and-quotas)) |
| `budget_exceeded` | A single file over 20 GiB | — |
| Uploads fail right after a start | A Garage store is still being set up | Wait for the **Object store** panel to show it ready |
| The store's set-up failed | Garage could not be reached or refused a step; the log names it | Make the store reachable, then run **Check store** |
| The bundled store stays a saved setting, its check failing at **Upload a test object**, **Read it back** or **Browser access (CORS)** | The domain, its certificate or the proxy was not ready in the ten minutes the server kept checking it after its start | Fix that, then run **Check store**, or restart the application, which checks it again |
| Uploads fail in the browser, with a CORS error | A store of your own without a CORS rule for the domain the user is on | Allow that origin in the bucket's CORS rule ([Storage and email](/run/storage-and-email/#the-object-store)) |

**A tenant cannot be created** (`store_not_ready`): no store has passed its check yet. Open **Server → Object store**:
where the deployment brings its Garage the server has set the bundled store and checked it itself, and the page names
the step that failed; otherwise save a store of your own. Fix the named step and run **Check store**
([uc.admin.configure-object-store](https://github.com/raddatzk/veyq/blob/main/docs/features/admin/use-cases/uc.admin.configure-object-store.md)).

## Securing an account is refused as too large

A member who secures their account is told the request is larger than this server accepts, and to ask you. Nothing was
changed on their account. The request carries their whole list of checked contacts, so it grows with that list. (The re-grant of
each of their spaces is uploaded ahead of it in parts, so the number of spaces does not count against the limit.)

- **Cause:** the instance's request limit, `QUARKUS_HTTP_LIMITS_MAX_BODY_SIZE`, is set below its default of
  10,240 KiB, or a proxy in front of the instance has a lower limit of its own.
- **What to do:** raise the limit to at least the default, on the instance and on every proxy in front of it. Then
  the member tries again.
- **Why the default:** it is sized so that securing an account still fits when the list holds its cap of 25,000
  contacts ([ADR 0104](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0104-the-identity-pin-store-is-one-sealed-entry-per-pin-under-a-sealed-head.md)).

## A certificate does not come

- **The domain does not resolve to the instance,** or port 80 does not reach it from the internet. HTTP-01 needs
  both; a tenant's DNS must be in place before the tenant is created.
- **Let's Encrypt's limits:** five failed validations lock a hostname out for an hour, and a registered domain gets at
  most 50 certificates a week. Test against the staging directory (`VEYQ_ACME_CASERVER` /
  `gateway.tls.acme.server`).
- **Docker Compose:** `permission denied` in Traefik's log means the certificate volume is not owned by Traefik's
  user; `veyqctl start` (`up -d --wait` on the recipe) runs the `volume-ownership` step that fixes it.
- **Kubernetes:** cert-manager 1.21 or later must be installed, with its CRDs and with its Gateway API and
  `ListenerSet` support on; `kubectl -n veyq get certificate` shows each order. A tenant with no certificate while the
  instance's own host has one usually means the gateway implementation does not serve `ListenerSet`s:
  `kubectl -n veyq get listenersets` shows whether the `Gateway` accepted each one.

## The config-secret key reads ABSENT or MISMATCH

Read it in **Server → Config-secret key**, or with `veyq-admin config-secret`.

- **ABSENT** — the application has no key: the file is missing and could not be created, or it cannot read it. After
  a restore, the key file must belong to the application's user, uid 10001, with mode `0600`; the log says so when
  the file is not readable.
- **MISMATCH** — a key is in place, but not the one some stored secret was sealed with. Usually the key was not
  restored, or the volume was not mounted and a recreated container generated a new one.

Put the original key file back and restart the application. Without it: each affected tenant's owner re-enters the
relay password in the app, and you re-enter the credentials of a store of your own in the console. Content is never
affected ([Storage and email](/run/storage-and-email/#the-config-secret-key)).

## The admin console cannot be reached

- **The tunnel:** on Compose the console listens on `127.0.0.1:8081` of the host only (`ADMIN_PORT`); reach it with
  `ssh -L 8081:127.0.0.1:8081 you@the-host`. On Kubernetes, with
  `kubectl -n veyq port-forward svc/veyq-admin 8081:8080`.
- **"This instance can't be reached right now."** The console loads but the application does not answer it: check
  that the application runs (`veyqctl status`, `kubectl … get pods`).
- **The application does not start** and its log says the admin listener could not bind: something else holds port
  8090 in its container.
- **The admin container exits** at start: `VEYQ_HSTS_ENABLED` is something other than `true` or `false`.
- **A Server-Owner operation answers `404` on the instance's domain:** that is by design. These operations answer only
  through the admin surface ([First-run setup](/run/first-run/#reach-the-console)).

## A tenant's domain does not answer

([uc.admin.tenant-reachability](https://github.com/raddatzk/veyq/blob/main/docs/features/admin/use-cases/uc.admin.tenant-reachability.md))

- **DNS does not point at the instance.** From outside, this looks exactly like a route that was never written; check
  DNS first.
- **The tenant is offline** (taken offline in the console). It does not route, by design.
- **The route was not written.** The application logs when it cannot write the routing file (Compose) or a route
  (Kubernetes); on Kubernetes `shared` TLS without `gateway.tls.secretName` disables tenant routing, and says so.
  Written but not served: `kubectl -n veyq get httproutes` shows whether the `Gateway` accepted it.
- **The certificate is not there yet** — see above.

## Mail is refused

([uc.admin.tenant-mail-settings](https://github.com/raddatzk/veyq/blob/main/docs/features/admin/use-cases/uc.admin.tenant-mail-settings.md))

- **`mail_unavailable`** — the tenant has no mail set up, it is switched off, or its relay needs a password the
  application cannot open (the config-secret key, above). Member invitations and password resets are refused until
  the tenant's owner sets it up.
- **`mail_send_failed`** — the relay is set up but did not accept the mail within 10 seconds. The invitation is
  withdrawn and can be sent again. The log names the mail's kind and the relay's reply code — or the error's class when
  the relay never answered —, never the recipient; check that the application may reach the relay's host and port.

## A user reports an error id

An answer of `500` carries `"code": "internal_error"` and `"message": "error id <id>"`. Find its line:

```sh
./veyqctl logs app | grep '<id>'                       # in /opt/veyq
kubectl -n veyq logs deploy/veyq-app | grep '<id>'
```

The line names the method, the route template, the exception's class chain, the database's SQLState where the
database refused, and the first line of veyq's own code the error passed — never the error's text, which can carry a
person's data ([Operations](/run/operations/#logs)). When that is not enough to act on, log the full text for a
while by raising one category to `DEBUG`:

- **Docker Compose:** add `services: app: environment: QUARKUS_LOG_CATEGORY__ME_RADDATZ_VEYQ_CORE_HTTP_UNEXPECTED_DETAIL__LEVEL: DEBUG`
  to `compose.override.yml` and run `./veyqctl start`.
- **Kubernetes:** `kubectl -n veyq set env deploy/veyq-app QUARKUS_LOG_CATEGORY__ME_RADDATZ_VEYQ_CORE_HTTP_UNEXPECTED_DETAIL__LEVEL=DEBUG`.

Each unexpected error then writes a second line under the same id with the whole exception, messages included. A
database's message names the values it was given — on a colliding sign-up, the email address — so this logs personal
data on purpose. **Set it back** as soon as you have what you need: remove the line from `compose.override.yml` and
run `./veyqctl start`, or `kubectl -n veyq set env deploy/veyq-app QUARKUS_LOG_CATEGORY__ME_RADDATZ_VEYQ_CORE_HTTP_UNEXPECTED_DETAIL__LEVEL-`
(the next `helm upgrade` drops it too).

## Look inside the application container

The application's image has no shell
([ADR 0118](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0118-the-app-image-is-distroless-and-carries-its-own-commands.md)):
`exec … sh` finds nothing to run. Its logs are the first place to look ([Operations](/run/operations/#logs)).
To read one file, use the image's own command:

```sh
docker compose -f "releases/<release>/docker-compose.prod.yml" --env-file .env exec app \
  java -jar /opt/veyq/image-commands.jar read-file /etc/traefik/dynamic/tenants.yml
kubectl -n veyq exec deploy/veyq-app -c app -- java -jar /opt/veyq/image-commands.jar read-file /etc/veyq/application.properties
```

For more, bring a container of your own choosing beside the application, never into its image. It sees the
application's processes and network; run it as uid 10001 to read the application's files under `/proc/1/root/`:

```sh
docker run --rm -it --pid container:veyq-app-1 --network container:veyq-app-1 --user 10001 <an image you trust> sh
kubectl -n veyq debug -it deploy/veyq-app --target=app --profile=restricted --image=<an image you trust> -- sh
```

A `restricted` namespace admits the debug container only as a non-root user, which is what `--profile=restricted`
asks for. Remove it when you are done: an ephemeral container stays in the pod until the pod is replaced.

## How far this is proven

Of *Look inside the application container*, the Compose `read-file` ran against a lane L1 instance (2026-11-05), where
`sh` and `id` were not found; the `kubectl` forms and both debug containers have not been run. Each cause and code on
this page is the backend's or the console's own, read from their code, contracts and tests. The
fixes for Kubernetes have not been tried in a cluster.
