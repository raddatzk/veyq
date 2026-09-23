---
id: cr-inert-store-responses
title: Nothing the store or the playback shim serves is renderable on the app origin
status: planned
satisfies: [uc.memory.upload, uc.memory.gallery]
adrs: ["0013", "0047", "0067"]
invariants: [operator-blind E2EE, self-hostable by default, API-first]
---

## Goal

Any member or share contributor can plant a `text/html` object in the store today. They `PUT` plaintext through
their own presigned URL with a `Content-Type` of their choosing, and `complete` checks only the size. The store
then serves that object back with the uploader's type. On compose the store shares the app's origin, so a link to
the object opens as a script-bearing page on the genuine domain, without a CSP. The playback Service Worker has
the same flaw with decrypted bytes: it serves the uploader-sealed `mediaType` as `Content-Type` and answers page
loads. After this CR, nothing either of them serves is renderable:
- the **signer** pins the stored and the served type, so the store refuses an HTML upload and never answers with
  a renderable type;
- the compose **proxy** adds `nosniff` + a `sandbox` CSP as a second layer;
- the **shim** only plays a single well-formed video type, refuses page loads and marks its responses inert.

## Context & constraints

- **The rule:** [ADR 0013 §6](../docs/decisions/0013-blob-storage-presigned-direct-to-store.md) (with the Garage
  v2.0.0 measurements this CR rests on) and [ADR 0047 § The shim serves inert responses](../docs/decisions/0047-inline-video-playback-range-streaming.md).
  Both are already written. This CR only builds them.
- **Why the signer is the enforcement point:** [ADR 0067](../docs/decisions/0067-store-provisioning-from-a-declared-type.md)
  provisions only the bundled store; a `type: s3` store is never touched by veyq. A fix that lived in
  provisioning or in the proxy alone would skip exactly those stores.
- **Operator-blind stays intact:** nothing here inspects content. The server still can't tell ciphertext from
  anything else, so this CR makes what the store serves safe instead of trying to validate what it holds.
- **API-first:** the pinned PUT header becomes part of every client's contract (a later native client must send
  it), so it is written into `contracts/` first.
- **Out of scope:**
  - the blob path prefix / bucket rename (inbox #151);
  - the missing security headers on the app origin itself and the stale "no production web-serving component"
    premise in `cc.security.csp.md` (a separate review finding);
  - Helm proxy headers, because the Helm store has its own origin (ADR 0013 §4).

## Phases

**Order: web goes before backend, on purpose.** A store ignores an unsigned header (ADR 0013 §6, row 1), so the
client can start sending `Content-Type: application/octet-stream` before the signer requires it. The reverse
order would break every upload between the two phases. The Traefik middleware and the tenant-sink change that
references it land **together** (phase 4): a file-provider router that points at a middleware Traefik doesn't
know yet fails, and takes that tenant's blob route down with it.

- [ ] 1 contracts — the `PresignedUrl` descriptions in `media` and `sharing` state that a presigned `PUT` must
      carry exactly `Content-Type: application/octet-stream` (anything else is refused by the store), and that a
      presigned `GET` is served as `application/octet-stream` with `Content-Disposition: attachment`.
      Locator: `grep -n -i 'presigned' contracts/api/media.openapi.yaml contracts/api/sharing.openapi.yaml`.
      This is a description-only change, so the generated code is unchanged and the phase ends green on every
      surface.
      · Done when: `npm run generate:api` + `npm run check` (web) green, and the trust-chain guards green.
- [ ] 2 web — the one client `PUT` to a presigned URL sends the pinned header. Locator:
      `grep -rn "method: 'PUT'" web/src | grep -v -e generated -e '\.test\.'`. Member and contributor uploads both
      go through it; the implementer confirms that with `grep -rn putUrls web/src/features/memory`.
      The playback shim follows ADR 0047:
      - a single well-formed `video/<subtype>` essence is required before a handle is registered. Locator for
        the current gate: `grep -rn "startsWith('video/')" web/src`. Only the sites that decide *playback* change
        — the uploader's own deriver and optimistic tiles read the local `File`, not a sealed value.
      - `request.mode === 'navigate'` gets a 404.
      - every shim response carries `nosniff` + `sandbox`.

      Tests go into `media.test.ts` and `sw/mediaSw.test.ts`. The case that must bite: a navigate request to a
      live handle returns 200 before the change.
      · Done when: `npm run check` green.
- [ ] 3 backend — `BlobStore.presignPut` signs `contentType("application/octet-stream")`. `presignGet` signs
      `responseContentType("application/octet-stream")` + `responseContentDisposition("attachment")`. Locator:
      `grep -rn 'presignPut\|presignGet' backend/src/main/kotlin`; there are exactly two sites, both in
      `BlobStore`.
      - **Guard (permanent, in `./gradlew check`):** `S3BlobStorePresigningTest` parses both URLs. The GET must
        carry both `response-content-*` parameters; the PUT's `X-Amz-SignedHeaders` must include `content-type`.
        **Bite it:** drop one override and show the test red before you trust it.
      - **Proof against the real store:** `BlobBatchE2ETest` (opt-in, real Garage) sends its chunks with the
        pinned header and reproduces the ADR 0013 §6 matrix: a planted `text/html` PUT → 403, and a GET →
        `application/octet-stream` + `attachment`.

      · Done when: `./gradlew check` green, and
      `VEYQ_S3_E2E=1 ./gradlew test --tests '*BlobBatchE2ETest*'` green against the dev store
      (`docker compose -f infra/docker-compose.yml --profile s3 up -d`).
- [ ] 4 infra — a Traefik headers middleware sets `X-Content-Type-Options: nosniff` +
      `Content-Security-Policy: sandbox; default-src 'none'`. It is defined once as labels on the store service
      and attached to:
      - the `veyq-blobs` router in `infra/docker-compose.prod.yml`;
      - the dev mirror in `infra/docker-compose.yml`, so the dev route stays representative;
      - every tenant blob router `TraefikFileIngressSink` renders (a `middlewares:` entry referencing it
        `@docker`).

      **Ordering:** if `cr-traefik-file-provider-only` lands first, there are no labels to define it on. This
      phase then becomes a sink change only, and that CR's § Context says what it becomes.
      The sink's projector tests assert the reference. Locator:
      `grep -n 'veyq-blobs\|blobs:' infra/docker-compose*.yml backend/src/main/kotlin/com/veyq/app/shared/tenant/TraefikFileIngressSink.kt`.
      Helm: nothing, because its store has its own origin (ADR 0013 §4). If `infra/README.md` says what each
      target does for store responses, it gets one line there, and nowhere else.
      · Done when: `./gradlew check` green, and a GET through the dev Traefik (`:18080`) on a store object shows
        both headers.
- [ ] 5 coverage — a new criterion in `docs/coverage/features/memory/use-cases/uc.memory.upload.yaml`
      ("the store never serves an object as a renderable type, and refuses a PUT of any type but the pinned
      one"), covered by the presigning guard and the opt-in e2e. A new criterion in `uc.memory.gallery.yaml`
      next to the `sw-*` rows ("the playback shim serves only a single well-formed video type, refuses page
      loads, and marks every response inert"), covered by the shim tests.
      · Done when: `python3 scripts/validate_coverage.py` green.

## Definition of done

- Every phase is done.
- The ADR 0013 §6 matrix is reproduced by `BlobBatchE2ETest` against the dev Garage.
- The presigning guard has been seen red with an override removed.
- `docs/coverage/` carries both new criteria with `python3 scripts/validate_coverage.py` green.
- The trust-chain CI guards are green.

**Not proven by any of the above, and named here so nobody reads it as covered:**
- The last browser step, a page load rendering the planted object, is standard browser behaviour and is not
  exercised.
- The shim's comma-list MIME resolution comes from reading the spec and was not measured in a browser.

Both become moot once the response types are pinned and the page loads refused. If a browser e2e exists for the
lightbox by implement time, the navigate-refusal case belongs in it.

## Retrospective
