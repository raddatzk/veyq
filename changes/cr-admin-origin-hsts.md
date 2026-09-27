---
id: cr-admin-origin-hsts
title: The admin origin sends HSTS under the app's switch, from an nginx that is still maintained
status: in-progress
satisfies: [cc.security.csp]
adrs: ["0069", "0065", "0064"]
invariants: [self-hostable by default]
---

## Goal

The Server-Owner console is the highest-privilege page an instance serves, and its nginx sends no
`Strict-Transport-Security`. On the two public admin exposures, compose's `ADMIN_DOMAIN` router and the chart's
`admin.domain` Ingress, every visit that starts on `http://` can be downgraded by a network attacker. That
includes a typed hostname, an old bookmark, and Traefik's own `:80 → :443` redirect
(`infra/docker-compose.prod.yml:222-224`). After this CR the admin nginx sends the app's HSTS value, and the
deployment switch that `cr-app-shell-served-by-backend` introduces turns it off for both origins at once. The
duplicated header block that the `add_header` inheritance trap forced goes, replaced by nginx's own merge
directive. The admin image also moves off `nginx-unprivileged:1.29-alpine`, a branch that has stopped
receiving builds, onto the maintained 1.30 stable branch. Inbox #176.

## Context & constraints

- **The decision:** [cc.security.csp](../docs/crosscutting/cc.security.csp.md) § *admin-web*. It covers where
  the header comes from (nginx, not the proxy), why there is one switch and not two, and why the admin policy
  stays a `<meta>`. It was settled with the owner on 2026-09-24. Do not re-decide it here. The header value is in
  the same doc, § *The other response headers*.
- **Depends on `cr-app-shell-served-by-backend`.** Its phase 3 creates the switch on both targets: an
  `infra/.env.example` key, the compose pass-through with default `true`, a Helm value plus its
  `values.schema.json` entry, and the `infra/README.md` sentence. This CR adds the admin container as a second
  reader of that switch and names the key nowhere else. Locator, which finds nothing until that CR has landed
  (measured 2026-09-24: 0 hits):
  `grep -n -i hsts infra/.env.example infra/docker-compose.prod.yml charts/veyq/values.yaml charts/veyq/values.schema.json charts/veyq/templates/*.yaml`.
- **Equal headers on `/api/*` are expected, so do not filter them.** The admin nginx passes the backend's
  response headers through unchanged (`admin-web/nginx.conf.template`, `location /api/`). Once the app shell
  sends HSTS on every response, `/api` included (that CR's phase 1), an admin-origin `/api/*` answer carries two
  equal STS headers. With one switch they cannot disagree, so no `proxy_hide_header` is added. A second
  mechanism would guard a case the single switch has already removed.
- **The trap this replaces.** `add_header` in a location replaces the server-level set instead of adding to
  it, which is why `location = /index.html` repeats the four headers today (`nginx.conf.template:34-38,
  77-83`). nginx 1.29.3 added `add_header_inherit merge` (measured 2026-09-24 against
  [the nginx docs](https://nginx.org/en/docs/http/ngx_http_headers_module.html#add_header_inherit)). With it
  declared at the server level, the index location keeps only its `Cache-Control`, and HSTS becomes one line
  instead of a fifth duplicated one. **Believed, not measured:** that the directive set at server level applies
  inside a location that declares its own `add_header`. If it does not, declare it in that location. Either
  way the trap comment is rewritten to describe the setup that is actually in place.
- **The switch in nginx must not rest on undocumented behaviour.** The entrypoint hook
  (`admin-web/docker-entrypoint.d/30-veyq-backend-url.sh:25`) renders the template with an explicit `envsubst`
  variable list. The nginx docs do not say what an empty `add_header` value does, so the hook renders the
  whole HSTS directive or nothing. It accepts exactly `true` (the default when unset) and `false`. Any other
  value stops the container and names the variable. A mistyped security switch has to fail loudly, never
  silently on or silently off.
- **The value is a literal in the template**, `max-age=15552000`, with no `includeSubDomains` and no `preload`.
  A comment points at cc.security.csp as its owner. No guard pins it to the backend's default: a drifted
  `max-age` does no harm, and nobody would ever notice it (`docs/ai/README.md` rule 3).
- **The default exposure is untouched.** Localhost-only on compose and a port-forward on Helm
  ([ADR 0065](../docs/decisions/0065-admin-ui-cli-separate-service.md)) are plain HTTP, where a browser ignores
  the header. nginx sends the header whatever the scheme, because it listens on plain `8080` behind whatever
  terminates TLS and cannot tell which case it is in.
- **The nginx base.** Measured 2026-09-24 via the Docker Hub API: `nginxinc/nginx-unprivileged:1.29-alpine` was
  last pushed 2026-05-11, and the `1.30-alpine*` tags on 2026-09-23. nginx.org lists 1.30.5 stable and 1.31.6
  mainline, both released 2026-09-15. The Dockerfile's reliance on this image (`admin-web/Dockerfile:48-60`:
  uid 101, port 8080, pid under `/tmp`, `conf.d` writable by 101, `apk add` as root) is re-checked on 1.30 by
  the live build, not assumed. Locator: `git grep -n "nginx-unprivileged:"` (measured: `admin-web/Dockerfile:56`
  only). The pin's comment gains the rule that governs it: the stable branch, moved when nginx cuts the next
  one. The `FROM` is written `repo:tag@sha256:<multi-arch index digest>` (ADR 0057 §6), and
  `scripts/validate_image_pins.py` is red on a bare tag, so the move resolves the new tag's index digest too.
- **Neighbours, all textual overlap only; whichever lands second rebases.**
  - `cr-admin-api-only-through-the-admin-container`: the same entrypoint hook (its `BACKEND_URL` default) and
    the same admin env blocks.
  - `cr-runtime-hardening-both-targets`: the admin service's posture. This CR adds no new writable path; the
    hook already writes `conf.d`.
  - `cr-image-provenance-verifiable`: pins this `FROM` by digest. If it lands first, the bump here carries the
    new tag's digest, and its guard enforces that.
  - `cr-traefik-file-provider-only`: no overlap. The header lives in nginx rather than in the router, and that
    is the reason nginx carries it.
- **Out of scope:**
  - the admin CSP's delivery (it stays a `<meta>`, see the decision above);
  - `Permissions-Policy` and COOP/COEP, which are undecided for the app too;
  - the SPA fallback sending `index.html` for a missing hashed asset (`try_files … /index.html`). This is not a
    security gap: `nosniff` makes the browser refuse HTML as a script.

## Phases

- [ ] 1 admin-web + infra — **the header, the switch on both targets, the base image.**
      - `admin-web/Dockerfile`: `FROM` moves to the 1.30 stable tag, and the pin comment says why that branch.
      - `nginx.conf.template`: `add_header_inherit merge`. The HSTS directive is rendered by the hook, and
        `location = /index.html` keeps only `Cache-Control`.
      - Entrypoint hook: reads the switch, accepts `true`/`false` only, and renders the directive or nothing.
      - compose: the `admin` service gets the switch from the same `.env` key, with the same default as `app`.
      - Helm: `templates/admin.yaml` renders the same value the app Deployment renders.

      **Proof: live, because no CI run starts this container** (measured at refine: no script or workflow
      reads `nginx.conf.template`). Build the admin image and `docker run` it with no backend, so `/api/*`
      answers 502, which is what `always` exists for. `curl -sI` four paths, and record each result:
      - `/`, which is the `index` internal redirect;
      - `/setup`, which is the `try_files` fallback into `= /index.html`;
      - one real hashed asset from the image's `/usr/share/nginx/html/assets`;
      - `/api/setup`.

      Check them in three runs:
      - **switch unset:** every path carries STS plus the four headers, and both index answers carry
        `no-store`;
      - **switch `false`:** no path carries STS, and the four headers are still present. That is the positive
        control on the same responses;
      - **switch `bogus`:** the container exits non-zero, and its log names the variable.

      **Bite** (injection by snapshot-and-restore, `changes/README.md` § Bite-proof a guard): remove
      `add_header_inherit merge`, rebuild, and `/setup` loses STS and the four headers while the asset keeps
      them. This is the old defect, and it proves the merge is what carries them.
      **Machine hint, holds for this CR's shape:** `docker build` and `pull` can stall on this Mac's keychain
      credential helper (local memory).
      · Done when:
      - the three runs and the bite are recorded red → green in this file;
      - `python3 scripts/validate_images_run_unprivileged.py` is green on the bumped `FROM`;
      - `docker compose -f infra/docker-compose.prod.yml config`, run on a scratch `.env` because the file's `:?`
        variables are required, shows the switch on `app` and `admin`, and both follow a flipped `.env`;
      - `helm lint charts/veyq -f charts/veyq/ci/lint-values.yaml` passes, and a `helm template` with the value
        flipped renders it into both Deployments;
      - the guards that read those files are green:
        `grep -ln "charts/veyq\|docker-compose.prod\|\.env\.example\|admin-web/Dockerfile" scripts/validate_*.py`.

      **Record, 2026-09-27 — built; the live half NOT run** (that session had no Docker runs allowed, and this
      Mac has no nginx). Measured:
      - [x] The base. Registry API (anonymous token, `HEAD /v2/nginxinc/nginx-unprivileged/manifests/<tag>`,
        OCI index accept): `1.30-alpine` → `sha256:4714e0b1…0bc5`; `1.29-alpine` → `sha256:0c79d56a…fbe6`, the
        old pin, which is the control on the method. The amd64 config blob of `1.30-alpine`: `User 101`,
        `ExposedPorts 8080/tcp`, entrypoint `/docker-entrypoint.sh`, `NGINX_VERSION=1.30.5` — the same shape as
        `1.29-alpine` (`1.29.8`). The pid path and `conf.d` ownership are inside layers and stay a live-build
        fact.
      - [x] The merge reaches the location. nginx `release-1.30.0` `ngx_http_headers_filter_module.c`,
        `ngx_http_headers_merge_conf`: `ngx_conf_merge_uint_value(conf->headers_inherit, prev->headers_inherit,
        ON)`, then the parent's headers are appended when the result is `MERGE`. A location that does not set
        the directive takes the server's `merge`, so it is declared once. The "believed, not measured" above is
        now read in the source; the bite below is what proves it served.
      - [x] The hook. A one-shot run of the real hook against the real template, its two `/etc/nginx` paths
        redirected to a temp dir (macOS `sh`, not busybox `ash`): unset, empty and `true` render
        `add_header Strict-Transport-Security "max-age=15552000" always;`; `false` renders it with a leading
        `#`; `bogus` and `TRUE` exit 1 with `veyq-admin: VEYQ_HSTS_ENABLED must be 'true' or 'false', got '…'`
        on stderr and render no file. The image's `docker-entrypoint.sh` runs hooks under `set -e` (read in
        `nginxinc/docker-nginx-unprivileged` `stable/alpine-slim`), so that exit stops the container.
      - [x] `python3 scripts/validate_images_run_unprivileged.py` → OK (3 runtime stages);
        `python3 scripts/validate_image_pins.py` → OK (19 references).
      - [x] `docker compose --env-file <scratch> -f infra/docker-compose.prod.yml config -q` → exit 0; `config
        --format json` with the key unset / `false` / `true`: app `VEYQ_HTTP_HSTS_ENABLED` and admin
        `VEYQ_HSTS_ENABLED` are `true`/`true`, `false`/`false`, `true`/`true`.
      - [x] `helm lint charts/veyq -f charts/veyq/ci/lint-values.yaml` → 0 failed; `helm template` default and
        `--set hsts.enabled=false` render `"true"`/`"false"` into both Deployments; `--set-string
        hsts.enabled=bogus` is refused by the schema (`got string, want boolean`).
      - [x] The guards that read those files: `validate_images_run_unprivileged`, `validate_image_pins`,
        `validate_compose_posture`, `validate_app_volume_ownership`, `validate_release_identity`,
        `validate_tenant_ingress_triggers`, `chart_meta_check` → all exit 0.
      - [ ] **OPEN — the three runs and the bite.** From the repo root, on port 18081 (never 8080):
        ```sh
        docker build -f admin-web/Dockerfile -t veyq-admin:hsts .
        # run 1: switch unset. Run 2: the same, with `-e VEYQ_HSTS_ENABLED=false` on the `docker run`.
        docker run -d --name vah -p 127.0.0.1:18081:8080 veyq-admin:hsts
        A=$(docker exec vah sh -c 'ls /usr/share/nginx/html/assets | grep "\.js$" | head -1')
        for p in / /setup "/assets/$A" /api/setup; do echo "== $p"; curl -sI "http://127.0.0.1:18081$p"; done
        docker rm -f vah
        # run 3: must exit non-zero and name VEYQ_HSTS_ENABLED
        docker run --rm -e VEYQ_HSTS_ENABLED=bogus veyq-admin:hsts; echo "exit=$?"
        # bite: injected into a copy mounted over the image's template, so the tree is never touched
        grep -v 'add_header_inherit merge;' admin-web/nginx.conf.template > /tmp/vah-bite.template
        docker run -d --name vah -p 127.0.0.1:18081:8080 \
          -v /tmp/vah-bite.template:/etc/nginx/veyq.conf.template:ro veyq-admin:hsts
        for p in /setup "/assets/$A"; do echo "== $p"; curl -sI "http://127.0.0.1:18081$p"; done
        docker rm -f vah
        ```
        Expected: run 1, all four paths carry STS and the four headers, `/` and `/setup` also `no-store`, and
        `/api/setup` is a 502; run 2, no STS anywhere and the four headers still there; run 3, exit ≠ 0 with
        the message above; bite, `/setup` keeps only `Cache-Control` while the asset keeps all five. Run 1 is
        also the first proof that the 1.30 image builds and starts at all (`apk add nodejs`, uid 101, `conf.d`).
- [ ] 2 docs — **built stops reading as decided.**
      - cc.security.csp § *admin-web*: its "not yet built" line goes. § *Verification* gains the live proof and
        names what it does not cover (below).
      - `infra/README.md`: the switch's sentence, which `cr-app-shell-served-by-backend` phase 3 writes, says it
        governs both origins.
      - Inbox #176 becomes DONE.

      Locator:
      `git grep -n -i "hsts\|Strict-Transport\|#176\|add_header" -- docs infra charts admin-web wiki ':!docs/inbox.md'`.
      · Done when: the trust-chain guards are green (`for f in scripts/validate_*.py; do python3 "$f"; done`).

      **Record, 2026-09-27 — done except the DONE flip.** cc.security.csp: the status line and § *admin-web*
      describe what is built, and § *Verification* records the checks above and names the served-header proof
      as not yet run. `infra/README.md`: the switch sentence covers both origins and the refusal. Inbox #176 is
      **in progress** with the live runs named as open. It becomes DONE, and § *Verification*'s "not verified
      yet" bullet becomes the record of those runs, once phase 1's open item is run.

## Definition of done

- Both phases are done. Phase 1's three runs and its bite are recorded, and the trust-chain guards that were
  green at the start are still green.
- The one-shot proof does not become a guard. The merge directive removes the trap structurally: a future
  location's `add_header` adds to the server-level set instead of replacing it, so nothing is left to guard.
  If phase 1 finds the directive has to be repeated per location, that reasoning no longer holds. Say so in the
  retrospective and put the question to the owner.
- **Not proven by any of the above, and named so nobody reads it as covered:**
  - that a browser records the admin host as a known HSTS host. That needs a trusted certificate on a real
    admin domain, and a browser ignores STS on a certificate error. The header's presence is proven, the
    browser's state is not;
  - the Kubernetes path on a cluster. It is shown by `helm template` only.
- Coverage: this CR satisfies a cross-cutting concern, which has no coverage YAML. Its record is
  cc.security.csp § *Verification* (phase 2).

## Retrospective

- **The CR's whole proof was one Docker session, and this session could run no Docker.** Every served-header
  check hung on `docker build`/`run`, so the CR cannot close here, though the code is finished. Two of its
  "believed" points did not need Docker at all. The merge semantics were one function in nginx's headers module
  (`ngx_http_headers_merge_conf`), and the hook's three switch values were a one-shot run of the real hook with its
  paths redirected. Root cause: the refine wrote only the most expensive proof, not the cheap one ahead of it.
  **Next time:** where a refine leaves a tool's semantics "believed, not measured" and the docs are silent,
  read the tool's source at refine. Then split the proof so that its no-daemon half can run anywhere.
- **envsubst rewrites comments, and the template's header comment spelled its own placeholders.** The
  rendered config printed the backend URL and resolvers into that comment. That was harmless for two values.
  With a third placeholder that renders `#`, it would have left a stray fragment. The comment now names them
  without the dollar-brace form and says why. The trap lives at that comment, which is where it bites.
- **Process.** Signing was locked (1Password), so there are no commits, and the per-phase path lists went to the
  coordinating session. The worktree sandbox also refused shell loops that pass a variable to `docker`, `helm`
  or `sed`, so each one-shot check became a short Python script in the scratchpad. That also meets the "scripts
  in Python" rule.
