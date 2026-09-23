---
id: cr-traefik-file-provider-only
title: Traefik runs the file provider only — the ingress sink renders all of compose's routing
status: planned
satisfies: [uc.admin.tenant-reachability]
adrs: ["0063", "0065", "0070"]
invariants: [self-hostable by default]
---

## Goal

The production Traefik mounts `/var/run/docker.sock`. A `:ro` mount does not restrict the Docker API, so
compromising the internet-facing proxy means root on the host. After this CR, Traefik on both compose stacks
runs the **file provider only**, and no container holds the socket. On production, `TraefikFileIngressSink`
renders the whole routing file: the services, the bootstrap domain's router pair, every tenant's pair, and the
admin router when an admin domain is configured. The compose file carries no routing labels at all. Inbox #162,
first half. The container hardening that this unblocks is `cr-runtime-hardening-both-targets`.

## Context & constraints

- **The decision:** [ADR 0063](../docs/decisions/0063-database-driven-tenant-ingress.md) § docker-compose sink +
  `VEYQ_DOMAIN` is routed from configuration. It also records the rejected alternative, a static routes file,
  with the three measurements that ruled it out. The admin router's source is
  [ADR 0065](../docs/decisions/0065-admin-ui-cli-separate-service.md) § Own domain. The behaviour lives in
  [uc.admin.tenant-reachability](../docs/features/admin/use-cases/uc.admin.tenant-reachability.md) (step 2 and
  the bootstrap bullet).
- **Why the services must move too, not only the routers.** The tenant routers reference `veyq-app@docker` and
  `veyq-blobs@docker`: `TraefikFileIngressSink.kt:76,83`, asserted in `TenantIngressProjectorTest.kt:112-113`.
  Those services exist only as Docker labels, so dropping the provider without this CR breaks every tenant
  route, not just the bootstrap one.
- **The bootstrap domain is deduplicated, not excluded.** Today `TenantIngressProjector.kt:68` filters a tenant
  row whose domain equals the bootstrap domain, for **both** sinks. After this CR:
  - the Traefik sink renders the bootstrap pair from configuration, and still skips a tenant row that carries
    the same domain. Two routers on one `Host` make Traefik drop both.
  - the Kubernetes sink keeps leaving the bootstrap domain out, because the chart's own `Ingress` routes it
    (`charts/veyq/templates/app-ingress.yaml`).

  Where the filter lives after this is the implementer's call. The rule is the one above.
- **The failure budget does not change shape.** The sink still never throws, and a missing bootstrap domain is
  a loud log, not a failed boot (ADR 0063 § Consequences, *the projection is not a startup requirement*). With
  one author, a failed write now unroutes the bootstrap domain as well. ADR 0063 states that cost; the
  localhost admin port bypasses Traefik and is the operator's way in.
- **Ordering with `cr-inert-store-responses`.** Its phase 4 *defines* the store-header middleware as labels on
  Garage and references it from tenant routers `@docker`. That cannot survive this CR. Whichever of the two
  lands **second** makes the adjustment:
  - **This CR second:** move the middleware definition into the sink's render, attach it to every blobs router
    (the bootstrap one included), and delete the Garage labels. Also fix the dev mirror (phase 3 here).
  - **That CR second:** its phase 4 becomes a sink change only. Define the middleware once in the rendered
    file, attach it to every blobs router, add no labels anywhere, and assert it in the projector tests. The
    reason its phase text gives for landing middleware and reference together also dissolves, because both
    sit in one atomic file.

  Whichever lands first adds a one-line note to the other's phase 4 text, so the second implementer finds it.
- **Neighbours on the same lines, not in scope:** the `/veyq` path prefix (#151) and the public Admin API
  (#152). Both will edit the sink's render after this. That is where compose's routing now lives, and the
  inventory command below finds it.
- **Out of scope:** container options, networks and anything on Helm. That is
  `cr-runtime-hardening-both-targets`, which depends on this CR because a non-root Traefik could not read the
  socket anyway.

## Phases

- [ ] 1 backend — `TraefikFileIngressSink` renders the complete file:
      - **Services** `veyq-app`, `veyq-blobs` (with `passHostHeader: true`, which ADR 0013 §4 needs) and
        `veyq-admin`, at upstream URLs from configuration. New `veyq.tenant.ingress.*` properties with the
        compose topology as defaults (`http://app:8080`, `http://garage:3900`, `http://admin:8080`), declared
        in `VeyqConfig` beside the existing ingress keys.
      - The **bootstrap pair** from `bootstrap-domain`.
      - The **tenant pairs**, as today.
      - The **admin router** when a new optional admin-domain property is set.

      Every router references its service without a provider suffix, so the name resolves within the file
      provider. The router shapes stay what the labels had:
      - app priority 1; blobs priority 100 with `PathPrefix(/veyq)`;
      - `websecure`, `letsencrypt`;
      - no prefix strip, `Host` preserved.

      Locators:
      - `grep -rn '@docker' backend/src` lists every reference.
      - `grep -rn -i -E 'static label|statically|already routes it' backend/src` lists the prose and test
        comments that assume labels.
      - `grep -rn 'bootstrapDomain' backend/src/main/kotlin` finds the filter.

      The empty-projection branch (`TenantIngressEmptyProjectionTest`) now fires only when no bootstrap domain
      is configured either. Keep it if that state is reachable under the Traefik sink, delete it if not, and
      say which in the retro. Tests (`TenantIngressProjectorTest`):
      - the bootstrap pair is rendered with no tenant row at all;
      - a tenant row carrying the bootstrap domain yields exactly one router per name;
      - the services are defined in the file and no router names `@docker`;
      - the admin router appears iff the admin domain is set.

      **Bite it:** re-add the old exclusion-only behaviour, so the bootstrap pair is absent, and see the
      no-row test go red.
      · Done when: `./gradlew check` green.
- [ ] 2 infra, production — `infra/docker-compose.prod.yml`:
      - delete every `traefik.*` and `veyq.stack` label, `--providers.docker*`, and the socket mount;
      - pass the admin domain and upstreams to `app` as environment;
      - Traefik keeps `--providers.file.directory` + `watch` and the volume stays `:ro`.

      The long comments that explain the label mechanics (the `${ADMIN_DOMAIN:+…}` trick, the `veyq.stack`
      constraint, "two routers on one Host") are **deleted**, not reworded. What still binds moves to ADR 0063,
      which already states it. Keep the app's volumes in short form:
      `scripts/validate_guard_gaps.py:149-150` anchors on the literal `      - veyq-config-secret:/var/lib/veyq`.
      Locator: `grep -n -E 'traefik\.(enable|http)|providers\.docker|docker\.sock|veyq\.stack' infra/docker-compose.prod.yml`
      must return nothing afterwards.
      · Done when: that grep is empty.
      · Done when: `docker compose -f infra/docker-compose.prod.yml config -q` passes with the variables the e2e
        harness fills (`scripts/run_web_e2e.py` `rehearse_production_project` runs exactly this).
      · Done when: a **live** `up` of the production file shows, through Traefik's API, the bootstrap pair's
        routers and the three services as `@file` and `enabled`. Query
        `wget -qO- http://127.0.0.1:8080/api/http/routers` and `…/services` inside the container, with
        `--api.insecure` added for the check only. Read the names, not a count. The tenant pairs are the
        projector tests' job, because a second tenant needs a set-up instance.

      **Machine hint, holds for this CR's shape:** a live `up` needs the app image. `docker build` can stall on
      this Mac's keychain credential helper (see local memory). Only `traefik:v3.6`, `mariadb:11.4` and
      `dxflrs/garage:v2.0.0` are cached here.
- [ ] 3 infra, dev — `infra/docker-compose.yml`'s `s3`-profile Traefik also drops the Docker provider and the
      socket. The dev app runs in the IDE and projects nothing (the sink is disabled without a path), so the dev
      blob route becomes a small static file in a bind-mounted directory next to the compose file. No watch is
      needed there. Keep it representative: same router shape as the sink's blobs router, entrypoint `web`.
      Locator: `grep -n -E 'traefik\.|providers\.docker|docker\.sock|veyq\.stack' infra/docker-compose.yml`
      must be empty.
      · Done when: `curl -sI http://localhost:18080/veyq/` reaches Garage: an S3 error body from Garage, not
        Traefik's 404.
- [ ] 4 docs — text that still says the bootstrap route is labels:
      - `infra/README.md` §§ on the proxy (`grep -n -i -E 'provider|veyq\.stack|label' infra/README.md`);
      - the chart comment at `charts/veyq/templates/app-ingress.yaml:1`;
      - the `docs/coverage/features/admin/use-cases/uc.admin.tenant-reachability.yaml` criterion
        `domain-actually-routes` ("the statically-routed bootstrap domain is never projected twice" becomes "the
        bootstrap domain is routed once, from configuration"; its refs stay the projector tests).

      Final sweep: `grep -rn -i -E '@docker|docker provider|static label|statically[- ]routed' docs infra wiki backend/src charts changes`.
      The only hits left may be ADR 0063's rejected-alternative bullet and the inbox row.
      · Done when: the trust-chain guards are green (`for f in scripts/validate_*.py; do python3 "$f"; done`).

## Definition of done

- Every phase is done.
- No compose file mounts the Docker socket or enables the Docker provider.
- The projector tests assert the rendered services and the bootstrap deduplication, and the no-row test has
  been seen red.
- A live production `up` shows the bootstrap routers and all three services loaded from the file provider
  alone.
- `python3 scripts/validate_coverage.py` and the trust-chain guards are green.

**Not proven by the above:** ACME issuance for the rendered routers needs public DNS. The router carries
`certResolver: letsencrypt` exactly as before, which the projector test asserts. Issuance itself is not
exercised here.

## Retrospective
