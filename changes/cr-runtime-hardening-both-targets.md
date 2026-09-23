---
id: cr-runtime-hardening-both-targets
title: Every container runs unprivileged on both targets, and reaches only the peers it serves
status: planned
satisfies: []
adrs: ["0070", "0064", "0063"]
invariants: [self-hostable by default]
---

## Goal

The chart's pods already pass `restricted`, while no compose service sets a single hardening option, and
reachability is open on both targets. After this CR:
- on **compose**, every service drops all capabilities, cannot escalate, has a read-only root, and runs as a
  non-root user. Garage and Traefik get writable volumes from a one-shot ownership step, and separate networks
  keep the internet-facing proxy away from the database;
- on **Helm**, every container also has a read-only root, and a default-deny NetworkPolicy set allows exactly
  the paths the stack uses, with the ingress controller named by the operator. `trusted-proxies` becomes an
  optional value.

Inbox #162, second half. **Depends on `cr-traefik-file-provider-only`**, because a non-root Traefik cannot read
the Docker socket.

## Context & constraints

- **The decisions, with their measurements and rejected alternatives:**
  [ADR 0070](../docs/decisions/0070-runtime-hardening-on-both-targets.md). Do not re-derive them here.
- **What the chart already holds:** `charts/veyq/templates/_helpers.tpl:24-31` (the shared container context),
  asserted by `scripts/chart_meta_check.py` check (v) at `:335-348`. This CR extends that helper and that
  check. It adds no parallel mechanism.
- **The two silent failures ADR 0070 measured decide how each phase is proven.** Traefik without a writable
  `/acme` stays up and never issues a certificate. A read-only root that misses one scratch path fails at
  runtime, not at `compose config`. So every compose proof is a **live** `up` that reads logs and health, never
  `config -q` alone.
- **No deployed instance exists** (ADR 0063 § Consequences: the production stack "had never actually been
  deployed"). So there is no migration of root-owned volumes. The init step also covers a first upgrade,
  because it acts on any volume root whose owner does not match.
- **`cr-admin-api-only-through-the-admin-container` (#152) renders the chart's first NetworkPolicy.** On the
  app pods it admits the public port from anywhere and the admin listener only from the admin pod, with two
  `chart_meta_check.py` assertions of its own. This CR **generalises that one; it does not add a second**. The
  public port narrows to `ingressFrom`, and the admin-listener rule and its assertions stay as that CR wrote
  them. Whichever lands second merges them into one template with one policy per pod. Two overlapping
  policies on the app would be additive and hard to read. `networkPolicy.enabled: false` removes every policy
  the chart renders, the admin-listener rule included. An operator who turns it off owns reachability, and
  the README says so next to the key. On compose that CR points the admin container at the listener, so the
  admin container reaches only the app, which is what phase 1's admin network carries.
- **The forwarded-address trust this narrows** is stated in
  [cc.security.rate-limit](../docs/crosscutting/cc.security.rate-limit.md) § *Trusts the client IP*, already
  updated by this refinement.
- **Out of scope:** the dev stack (`infra/docker-compose.yml`, not internet-facing), and egress policy for the
  app (ADR 0070 §3 says why).

## Phases

- [ ] 1 compose — `infra/docker-compose.prod.yml`. Locator for the services:
      `grep -n -E '^  [a-z-]+:$' infra/docker-compose.prod.yml` (five today, plus the init service after this).
      - **Posture on every service:** `cap_drop: [ALL]`, `security_opt: [no-new-privileges:true]`,
        `read_only: true`, plus a `tmpfs:` for each scratch path the process writes. Measured for MariaDB:
        `/run/mysqld`, `/tmp`. Measure the others by running them: the app (JVM `/tmp`), admin (its entrypoint
        writes `/etc/nginx/conf.d/default.conf`, and nginx's pid lives under `/tmp`, per
        `admin-web/docker-entrypoint.d/30-veyq-backend-url.sh:25` and `admin-web/Dockerfile:52-53`), Garage and
        Traefik.
      - **`user:`** on MariaDB (999), Garage (1000, as `charts/veyq/templates/garage.yaml` runs it) and Traefik
        (a non-root uid of your choice, the same one the init step chowns to). Not on `app`/`admin`: their
        images declare the user, and the guards read it from the Dockerfile.
      - **The ownership init service** (ADR 0070 §2). It holds `cap_drop: [ALL]` + `cap_add: [CHOWN]` and
        chowns the root of the Garage `meta`/`data`/`snapshots` volumes and of `veyq-acme`, each only if not
        already owned by the target uid. It uses an image the recipe already pins, so
        `cr-image-provenance-verifiable`'s guard, if it landed first, covers it. It has `restart: "no"`, and
        Garage and Traefik depend on it with `condition: service_completed_successfully`.
      - **Networks** (ADR 0070 §3): Traefik+app+Garage on one, app+MariaDB on one, app+admin on one. Nothing
        stays on the implicit `default`, so no service can join a network nobody named.

      Keep the app's volume entries in short form (`scripts/validate_guard_gaps.py:149-150` anchors on the
      literal line).
      **Guard (permanent):** `scripts/validate_compose_posture.py` reads the production file and asserts:
      - every service carries the three posture options;
      - every service whose image is not built from this repository sets a non-root `user:`;
      - the proxy's networks and MariaDB's networks are disjoint.

      Derive the service set from the file, never from a list. Scope it to the production file and say why in
      its docstring: the dev stack is not internet-facing. **Bite it** three ways, by snapshot-and-restore
      (`changes/README.md` § Bite-proof a guard): drop `cap_drop` from one service; put MariaDB on the proxy's
      network; remove a third-party service's `user:`.
      · Done when: `docker compose -f infra/docker-compose.prod.yml config -q` passes (the e2e harness's
        `rehearse_production_project` runs the same). **Also** a live `up` in which:
        - every service is healthy or completed;
        - `acme.json` exists owned by Traefik's uid (`docker run --rm -v veyq_veyq-acme:/a <pinned image> ls -ln /a`);
        - `docker compose logs traefik` has no `permission denied`;
        - `curl -fsS` against the app's `/api/setup` through the admin port answers;
        - a connection from the Traefik container to `mariadb:3306` **fails**, while the app's healthy DB
          connection is the positive control on the same predicate.

      **Machine hint, holds for this CR's shape:** the live `up` needs the app and admin images, and
      `docker build` can stall on this Mac's keychain credential helper (local memory).
- [ ] 2 helm — `charts/veyq/`.
      - **`readOnlyRootFilesystem: true`** in the shared container context, and an `emptyDir` for each scratch
        path phase 1 measured, per pod. Extend `chart_meta_check.py` (v) to require it.
      - **`templates/networkpolicy.yaml`**, per ADR 0070 §3:
        - one default-deny policy selecting every pod of the release;
        - ingress allows for:
          - the app's public port from `ingressFrom`, and its admin listener from the admin pod (#152's rule,
            kept);
          - the store's S3 and admin ports, MariaDB, and admin;
          - cert-manager's solver pods (`acme.cert-manager.io/http01-solver: "true"`), always rendered because
            the rule is harmless without such pods;
        - egress narrowed for MariaDB, Garage and admin, each with DNS (port 53 to any namespace) plus exactly
          its peers. Garage's peer is its own RPC port, and admin's is the app's admin listener. The app's
          egress is left open.
      - **Values/schema:** `networkPolicy.enabled: true` and `networkPolicy.ingressFrom` (a list of peers). The
        schema requires a non-empty `ingressFrom` while `enabled` is true (`if`/`then`), and an install without
        it fails naming the key. `app.trustedProxies` is optional, rendered as
        `QUARKUS_HTTP_PROXY_TRUSTED_PROXIES` only when set.
      - Both `charts/veyq/ci/*.yaml` files gain an `ingressFrom`.

      **New `chart_meta_check.py` assertions:**
      - a default-deny policy exists and selects every rendered pod;
      - the app's public-port peers are exactly `ingressFrom`, and its admin listener still admits only the
        admin pod (#152's assertion, kept);
      - MariaDB's only peer is the app;
      - a render with `networkPolicy.enabled=false` has no NetworkPolicy;
      - `helm lint` with `ingressFrom` removed **fails**. That is a negative, so pair it with the positive
        control that the same lint passes with it set.

      **Bite each** by editing the template, snapshot-and-restore.
      · Done when: `python3 scripts/chart_meta_check.py` green (it needs `helm`, and runs outside the
        `validate_*` glob by design).
- [ ] 3 docs —
      - `infra/README.md`: compose's posture and networks in a few lines. Point at ADR 0070; do not restate it.
      - The backup commands' network names. The rclone container must join the network the store is on, which
        is no longer `veyq_default`. Locator from two angles:
        `grep -rn -- '--docker-network' infra/README.md scripts/*.py` and
        `grep -rn -E '\b(veyq|infra)_default\b' infra scripts docs wiki`. Only the production ones change;
        `infra_default` is the dev stack's.
      - The Helm install example gains `networkPolicy.ingressFrom`, with one sentence on CNIs that do not
        enforce policy and one on when `trustedProxies` is worth setting.
      · Done when: the trust-chain guards are green (`for f in scripts/validate_*.py; do python3 "$f"; done`).

## Definition of done

- Every phase is done.
- `validate_compose_posture.py` and the new `chart_meta_check.py` assertions have each been seen red.
- A live production `up` meets phase 1's checks, including the refused Traefik→MariaDB connection with its
  control.
- The trust-chain guards and `chart_meta_check.py` are green.

**Not proven by the above, named:** that a cluster enforces the rendered policies. It depends on the CNI, and
this repository has no cluster (`kubectl` has no reachable context on this machine). `helm template` proves
what is rendered, and nothing more.

## Retrospective
