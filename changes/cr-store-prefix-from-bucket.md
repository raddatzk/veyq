---
id: cr-store-prefix-from-bucket
title: Every blobs route takes its prefix from the bucket setting, and the bundled bucket is `blobs`
status: planned
satisfies: [uc.admin.tenant-reachability]
adrs: ["0013", "0067", "0063"]
invariants: [self-hostable by default]
---

## Goal

Presigned URLs are path-style, so an object's path is `/<bucket>/<key>`, and on compose the proxy hands that
path to the store by its first segment. The bucket is a setting (`veyq.blob-store.bucket`), yet every route
spells `/veyq` itself. Changing the setting alone therefore 404s every media request, with nothing reporting
it. On a Garage store it also strands every stored object, because the provisioner creates an empty bucket
under the new name. After this CR:
- every blobs router the sink renders takes its prefix from the setting, as `/<bucket>/`;
- startup refuses a bucket name that breaks S3's naming rules;
- the default is `blobs`, marked as fixed once an instance is installed;
- the dev loop, the scripts and the docs follow the new default, and the owner's dev instance is reset onto it.

Inbox #151.

## Context & constraints

- **The decisions, written in the refine — do not re-decide them:**
  [ADR 0013 §4](../docs/decisions/0013-blob-storage-presigned-direct-to-store.md) (the prefix is the bucket and
  is derived; the trailing slash; the name `blobs`, and why no page-like word and no reserved-name list) and
  [ADR 0067](../docs/decisions/0067-store-provisioning-from-a-declared-type.md) § Consequences (the default is
  fixed once installed; an install that must move pins the old name).
- **Entry condition: `cr-traefik-file-provider-only` is done.** That CR moves the bootstrap domain's blobs router
  from Garage labels into `TraefikFileIngressSink`'s render and turns the dev route into a static file. After it,
  the sink is the only author of every production blobs router, so the derivation is one parameter at one
  render site. Before it, the bootstrap router would need parametrising through compose interpolation, which that
  CR then deletes. Check it against what this CR would change: both
  `grep -n -E 'traefik\.http' infra/docker-compose.prod.yml` and `grep -n -E 'traefik\.' infra/docker-compose.yml`
  return nothing. If either still does, stop.
- **Only two places read the setting today** (spot-check this row first, since the whole derivation argument
  leans on it): `grep -rn '\.bucket()' backend/src/main` → the presigner and the provisioner factory. The sink is
  built from a path alone (`grep -rn 'TraefikFileIngressSink(' backend/src/main/kotlin`), so it has to be given
  the bucket.
- **Spared, measured:**
  - **Helm.** The store has its own host, and the chart routes `path: /` to it
    (`grep -n 'path:' charts/veyq/templates/garage.yaml`). The Kubernetes sink renders `/`, and its test pins it
    (`grep -n 'assertEquals("/"' backend/src/test/kotlin/com/veyq/app/shared/tenant/KubernetesIngressSinkTest.kt`).
    Only the sink's comment names `/veyq`: `grep -n '/veyq' backend/src/main/kotlin/com/veyq/app/shared/tenant/KubernetesIngressSink.kt`.
  - **`web/src`.** It fetches whatever URL it is handed, and its Service Worker handles only `/__media/`
    (`grep -rn '/veyq' web/src` is empty). Only the dev proxy in `web/vite.config.ts` changes.
  - **Names that contain `veyq` but are not the bucket:** the middleware `veyq-blobs-inert`, the store key alias
    `veyq-app`, the e2e harness's extra buckets `veyq-e2e-*` and `veyq-prod-project-decoy`, and
    `/var/lib/veyq`, and the database and its user, both `veyq`. They all stay.
  - **Test-local bucket constants.** They set the property themselves, so they are self-consistent and stay:
    `grep -rn 'BUCKET = "veyq"' backend/src/test`.
- **No installed store exists** (ADR 0063 § Consequences: the production stack "had never actually been
  deployed"), so there is no migration. On the owner's call in the refine, the owner's dev instance is **reset,
  not migrated**, and store and database are reset **together**. A store emptied under a kept database leaves
  every media row pointing at a missing object, which is the one skew the media read path does not handle
  (`uc.admin.back-up-and-restore-instance.yaml`, criterion `a-stopped-app-makes-the-dangerous-skew-impossible`).
- **Neighbours with no ordering constraint:**
  - `cr-runtime-hardening-both-targets` changes ports, networks and container posture, and no paths. It overlaps
    only textually, in `infra/README.md`'s backup section.
  - `cr-upload-windowed-presign` shares `BlobBatchE2ETest` and runs that test against the dev store. After this
    CR, the store holds `blobs`.
  - `cr-app-shell-served-by-backend` names `/veyq` in its Ordering prose. Phase 3's sweep finds that text if the
    CR is still open. A bare `/blobs` without the slash now reaches that CR's shell fallback, which is harmless.

## Phases

- [ ] 1 backend
      - The default becomes `blobs` in both places that declare it:
        `grep -rn -E 'blob-store\.bucket=|@WithDefault\("veyq"\)' backend/src/main`. The properties line gets a
        one-sentence comment: the name is fixed once installed, with a pointer to ADR 0067.
      - `BlobStoreStartupCheck` also refuses a bucket name that breaks S3's general-purpose naming rules, naming
        the key in its message. The rules: 3–63 characters of `[a-z0-9.-]`, starting and ending with a letter or
        digit, no `..`, and not shaped like an IPv4 address.
        - The check runs in every launch mode, `%test` included. Unlike the endpoint, the name is never absent.
        - The rule is a pure companion function, tested directly in the shape of the existing `hasStore`.
          Accepted: `blobs`, `veyq`, `a.b-c`. Refused: a backtick, an uppercase letter, `_`, two characters, a
          leading `-`, `192.168.0.1`.
      - `TraefikFileIngressSink` is given the bucket. It goes through `TenantIngressSinkProducer`, which already
        holds the config. The sink renders `` PathPrefix(`/<bucket>/`) `` on every blobs router
        (`grep -n 'PathPrefix' backend/src/main/kotlin/com/veyq/app/shared/tenant/TraefikFileIngressSink.kt`).
      - The literal assertions (`` grep -rn 'PathPrefix(`/veyq' backend/src/test ``) assert the configured value
        instead.
      - **A new projector test** configures a bucket that is not the default. It asserts that every rendered blobs
        router, the bootstrap one and a tenant's, carries `/<that>/` and nothing else.
        **Bite (injection, snapshot-and-restore):** hard-code `/blobs/` back into the sink. This test goes red on
        its assertion while every default-bucket test stays green, which is exactly why this test must exist.
      - The Kubernetes sink's `/veyq` comment is reworded to name the route by its role.
      - `BlobBatchE2ETest`'s fallback follows the new default (`grep -n '?: "veyq"' backend/src/test/kotlin/com/veyq/app/features/media/BlobBatchE2ETest.kt`).
        Its comment that credits the deleted Garage init script with creating the bucket is corrected to name
        the provisioner.

      · Done when: `./gradlew check` green.
- [ ] 2 dev loop, scripts, the dev instance
      - **The literals that cannot read the setting.** Each becomes `blobs`, and its comment says the value is the
        backend's default:
        - the proxy key in `web/vite.config.ts`;
        - the dev route file from `cr-traefik-file-provider-only` phase 3, `infra/traefik-dev/blobs.yml` (landed), with
          the trailing slash;
        - the `/veyq` comments in `backend/src/main/resources/application-dev.properties`,
          `application-e2e.properties`, `.run/Run Backend.run.xml` and `infra/docker-compose.yml`
          (`grep -rn '/veyq' backend/src/main/resources .run infra/docker-compose.yml infra/traefik-dev web/vite.config.ts`).

        No guard: a mismatch breaks dev media on the first page load.
      - **The scripts:** `scripts/run_web_e2e.py`'s `STORE_BUCKET` and its fallbacks, and
        `scripts/instance_backup.py`'s default and docstring
        (`` grep -n -E 'STORE_BUCKET|store[-_.]bucket|BLOB_STORE_BUCKET|`veyq` bucket' scripts/run_web_e2e.py scripts/instance_backup.py ``).
      - **The dev instance reset.** This is a machine step, and it holds for the owner's machine only.
        - Ask the owner at that moment. It deletes the dev data.
        - Then stop the IntelliJ backend and take the dev stack down with its volumes, from the **main checkout's**
          `infra/`, never from a worktree (local memory: a worktree recreates the user's containers with other
          mounts).
        - Bring the stack back up. The backend's first start provisions `blobs`.
        - Tell the owner to restart the backend through IntelliJ.

      · Done when, in the dev loop:
        - a fresh image upload and view go through `/blobs/…`, and the network list shows the `PUT` and the `GET`
          with `200`;
        - `curl -sI http://localhost:18080/blobs/` answers from Garage (an S3 error body, not Traefik's 404);
        - `curl -sI http://localhost:18080/blobsx` answers Traefik's 404. That is the trailing slash bitten live,
          and the line above is its positive control.

      · Done when: `python3 scripts/run_web_e2e.py` is green. Its store gate reads the new name first.
- [ ] 3 docs + coverage
      - **`uc.admin.tenant-reachability.yaml`** gets a new criterion `the-blob-route-follows-the-bucket`, whose ref
        is phase 1's non-default-bucket test: *"Every blobs route the proxy is given — the bootstrap domain's and
        each tenant's — routes the configured bucket's path and no path that merely begins with its name, so a
        bucket other than the default routes with configuration alone."* Web gets no ref, for the reason
        `domain-actually-routes` already states.
      - **`infra/README.md`** names the bucket at the lines
        `` grep -n -e '`veyq` bucket' -e 'bucket `veyq`' infra/README.md `` returns. Edit them to `blobs`. Where
        the README says the backend creates the bucket, it names the setting.
      - **Final sweep, flow surfaces included:**
        `git grep -n -P '(?<![A-Za-z0-9_./-])/veyq(?![A-Za-z0-9_:-])(?!/deploy)' -- docs infra wiki changes scripts backend web .run`
        and `git grep -n -E "bucket .?veyq|veyq.? bucket" -- docs infra scripts backend .run`.

        Hits that may stay:
        - this CR and the `docs/inbox.md` row;
        - the live-smoke evidence comment in `docs/coverage/features/memory/use-cases/uc.memory.cover.yaml`, which
          records what was observed then and is not a claim about now.

        A hit in another open CR is edited to `blobs`, since it is flow prose that a later reader acts on.

      · Done when: `python3 scripts/validate_coverage.py` and every `scripts/validate_*.py` are green.

## Definition of done

- Every phase is done. The baseline before the first edit is
  `for f in scripts/validate_*.py; do python3 "$f" >/dev/null 2>&1 || echo "RED $f"; done`.
- The non-default-bucket projector test was seen red with the literal re-hard-coded.
- In the live dev loop, `/blobsx` got Traefik's 404 beside `/blobs/` reaching Garage.
- The name check is unit-tested both ways, and its refusal names `veyq.blob-store.bucket`.
- The phase 3 sweep leaves only the hits it lists.

**Not proven by the above:** that a production `up` routes a non-default bucket live. The projector test proves
the rendered file, and `cr-traefik-file-provider-only`'s live `up` proves that Traefik loads that file.

## Retrospective
