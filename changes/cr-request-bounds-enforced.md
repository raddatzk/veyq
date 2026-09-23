---
id: cr-request-bounds-enforced
title: Every request-body bound reaches the server, and a guard keeps it so
status: planned
satisfies: [uc.memory.upload, uc.memory.buckets]
adrs: []
invariants: [API-first, operator-blind E2EE]
---

## Goal

The backend's Kotlin models are generated without schema-derived bean validation. So a bound in a request
schema reaches the server only if someone also hand-writes its `x-field-extra-annotation`, and nothing ties the
two. Two gaps follow, and both are live:
- **Bounds with no annotation.** `BlobUploadsBatchRequest.items` declares `maxItems: 256` and its generated
  model carries no `@Size`, so one request can start any number of blobs.
- **Nested annotations that never run.** The same `items` carries no `@Valid` either. Validation does not
  descend without it, so even the annotations that do exist below it (every item's `wrappedBlobKey`
  `@NotBlank @Size @Pattern`) are dead.

After this CR, a guard holds the rule the
[styleguide](../docs/crosscutting/developer-styleguide.md) now states: in a request body, the schema bound and
its annotation are one rule written twice, value for value, both ways, and every nested constrained object
carries `@Valid`. Every current violation is fixed. The two places where the web client exceeds a bound nobody
enforced are fixed as well, so enforcing it breaks nothing.

## Context & constraints

- **The rule:** [`developer-styleguide.md`](../docs/crosscutting/developer-styleguide.md) § the request-body
  bullet (written in the refine). [`backend/CLAUDE.md`](../backend/CLAUDE.md) points at it.
- **Why hand annotations at all** (each line measured at refine, re-check one):
  - The model generator is `kotlin` with no `useBeanValidation` (`backend/gradle/openapi.gradle.kts:58-63`).
  - The stock template emits `x-field-extra-annotation` verbatim (openapi-generator 7.21.0,
    `kotlin-client/data_class_req_var.mustache`). There is no templates dir.
  - A generator that derives validation from the schema is an optional later spike, not this CR.
- **Parameters are out.** Path, query and header parameters come from the `jaxrs-spec` API run with
  `useBeanValidation=true` (`openapi.gradle.kts:67-81`), which derives their annotations itself (e.g. the
  generated `SharingApi.java` puts `@Pattern @Size(min=43,max=43)` on `shareId`).
- **What a violation looks like:**
  - Request bodies arrive `@Valid @NotNull` in the generated interfaces.
  - A violation is answered by Quarkus's built-in `ResteasyReactiveViolationExceptionMapper`: `400`, body
    `"Constraint Violation"`, no veyq `code`.
  - `LoginSliceTest` already asserts that shape (`grep -n 'Constraint' backend/src/test/kotlin/com/veyq/app/features/auth/LoginSliceTest.kt`).
- **The uuid trap** ([`backend/CLAUDE.md`](../backend/CLAUDE.md)): a `format: uuid` property generates as
  `java.util.UUID`, so a String validator on it is a 500 on a well-formed request. The guard must never demand
  one there.
- **Order:**
  - This CR implements **after** `cr-inert-store-responses`. That CR edits the same two specs' `PresignedUrl`
    descriptions; the overlap is textual only.
  - It implements **before** `cr-upload-frame-size-contract` and `cr-upload-windowed-presign`, so their
    contract changes land under this guard.
- **Out of scope:**
  - Response schemas.
  - Presence constraints: `@NotBlank`/`@NotNull`/`@NotEmpty` are not bounds, and the schema says presence with
    `required`.
  - Request arrays that declare **no** bound at all (`RotateScopeRequest.grants`, the two `masterKeyWraps`).
    There is nothing to mirror there, and whether they need a bound is its own question.

## Decisions

**The guard** is `scripts/validate_request_bounds.py`, schema-scoped rather than file-scoped. Its subject is every
property of every schema reachable from any operation's `requestBody`: transitively through `$ref`, `items`,
`allOf` and the OpenAPI 3.1 nullable `oneOf`/`anyOf` spellings, across spec files. For each property it asserts:
1. **Bound → annotation, same value.**

   | schema | annotation |
   |---|---|
   | `maxLength`/`maxItems` | `@Size(max = N)` |
   | `minLength`/`minItems` | `@Size(min = N)`; `@NotBlank`/`@NotEmpty` also satisfy N = 1 |
   | `minimum` | `@Min(N)` |
   | `maximum` | `@Max(N)` |
   | `pattern` | `@Pattern` with the same regexp, anchors normalised (`^…$` vs unanchored) |
   | `contentEncoding: base64` | the base64 `@Pattern` the specs already use |
   | `format: email` | `@Email` |

2. **Annotation → bound.** No `@Size`/`@Min`/`@Max`/`@Pattern`/`@Email` enforces something the schema does
   not declare. A base64 `@Pattern` counts as declared by `contentEncoding: base64`.
3. **Cascade.** A property whose target is an object schema, or a list of one, carries
   `@field:jakarta.validation.Valid` whenever that target carries any constraint, at any depth. The target set
   is derived from the specs, never listed by hand.
4. **No string bound on `format: uuid`.** It is dead text, and annotated it is the 500 above.

The guard reports **the list** (spec, schema, property, which rule), not a count. Its docstring carries the
three fields `changes/README.md` § trust-chain guards requires. Its limit: it proves the annotation is
**present**, never that it has an **effect** — that is phase 2's slice tests, and it names parameters and
responses as unchecked.

Run on the untouched tree at refine (a prototype of rules 1–3), the list names:
- the three upload/download/complete batches of `media` and `sharing` (`items` bound + cascade);
- `chunkCount`/`byteSize` bounds on both upload items;
- `wrappedMeta`/`wrappedExif`/`derivatives` on both media request items and on `MediaMetaUpdate`;
- `MoveMediaRequest.mediaIds`;
- the `epoch` minimums in `content`/`scopes`;
- `ScopeGrantEntry.wrappedGrant` + the `RotateScopeRequest.grants` cascade;
- the `RotatedWrap` cascades in `account`;
- `MasterKeyWrap.id` (`setup`, `tenants`).

Rule 2 adds 3 `domain`/`host` patterns and 4 `username` patterns the schemas lack, plus the mail `port`
min/max. Rule 1's base64 half adds 4 `RecoveryCompleteRequest` fields. That list is the refine's orientation
only. **The guard's first run is the list**: write it, record the red, then fix.

**Where a bound starts biting, the client stays inside it; the bound does not move.** Measured at refine:
the web client sends 1–3 items per upload/complete/download batch
(`grep -n 'startBlobUploads\|completeMedia\|presignBlobDownloads\|startShareBlobUploads\|completeShareMedia' web/src/features/memory/media.ts web/src/features/memory/share.ts`),
and `wrappedMeta` is already clamped (`PLACE_NAME_MAX_BYTES`, `media.ts`). Two senders exceed a bound today:
- **Moves.** `moveBucketMedia` sends the whole selection (`MemoryBuckets.vue`,
  `grep -n 'moveBucketMedia' web/src/features/memory/components/MemoryBuckets.vue`). It becomes one request per
  ≤ 256 ids. The slices are not atomic together: a failed slice surfaces through the existing error path,
  after the earlier slices have moved.
- **EXIF.** The sealed tier has free text (`title`, `description`, `artist`, `copyright`, `software`, `lens`,
  `name`, …) that `cleanStr` only trims (`grep -n 'function cleanStr' -A4 web/src/features/memory/media.ts`).
  The client fits the tier under its `maxLength: 16384` before sealing, following the place-name precedent:
  - each free-text field is clamped to a per-field byte cap;
  - a tier that still does not fit drops free text before numbers;
  - the upload never fails for it.

## Phases

- [ ] 1 web — the client stays inside the bounds before anything enforces them.
      - `moveBucketMedia` slices at the contract's `maxItems`. The generated TS client carries no bounds, so the
        value is one named constant whose comment cites `MoveMediaRequest.mediaIds`.
      - The EXIF tier is fitted under its bound. Tests go in `media.test.ts` / the buckets test. Two cases must
        bite as **live defects** (red on the untouched tree): a 300-id move issues two requests, and an EXIF
        tier with a 20 000-char description seals to ≤ 16 384 base64 chars and keeps its numeric fields.
      - Locator for every EXIF string field: `grep -n 'cleanStr(' web/src/features/memory/media.ts`.

      · Done when: `npm run check` (web) green.
- [ ] 2 contracts + backend — **both bites first, against the tree phase 1 left; then the fix.** In this order:
      1. **Effect tests, live defects.** These are slice tests at the HTTP boundary, one per mechanism, because
         the guard can only see presence:
         - **top-level bound:** `startBlobUploads` with 257 items → `400` "Constraint Violation", against a
           256-item control → `200`;
         - **cascade:** one item with a blank `wrappedBlobKey` → `400`, against a well-formed control → `200`;
         - **anonymous path:** the top-level bound on `startShareBlobUploads`, because the contributor path is
           anonymous and must not be the one left open.

         Anchor every negative on the status **and** the mapper's body, never on "it threw". On the untouched
         tree the assertion expected to fail first is the status (`200` where `400` is wanted). Read which
         assertion reddened, not that the test did.
      2. **Guard, live defect.** Write `validate_request_bounds.py`, run it, and record the red list.
         - Before writing the predicate, grep `scripts/` for guards anchoring on the touched schemas:
           `grep -ln 'BlobUploadsBatchRequest\|MasterKeyWrap\|x-field-extra-annotation' scripts/`.
         - `validate_maxlength_storage.py` reads the same base64 `maxLength`s. It must stay green and untouched.
      3. **Fix.** Add every missing annotation, `@field:jakarta.validation.Valid` and schema declaration until
         both are green. Follow the uuid trap and the base64 spelling the specs already use. An existing backend
         test that now goes red because it sends an out-of-bound body gets its body fixed, not the bound.
      4. **Pin the guard.** Add three cases to `scripts/validate_guard_gaps.py`, each **rejected**, each a
         forgery on a copy:
         - delete `wrappedBlobKey`'s `@Size` → red;
         - delete one `@field:jakarta.validation.Valid` → red;
         - change one annotation's value so it differs from its schema bound → red.

      · Done when: every `scripts/validate_*.py` green, `./gradlew check` green, `npm run generate:api` +
        `npm run check` (web) green.
- [ ] 3 coverage — two new criteria:
      - `uc.memory.buckets`: "moving more media than one request carries moves all of them".
      - `uc.memory.upload`: "the sealed EXIF tier always fits its contract bound — long free text is shortened,
        the upload never fails for it".

      Each is covered by phase 1's tests. Phase 2's slice tests are the guard's effect proof, and the guard's
      docstring names them. They go under an existing criterion only if they assert what it says.
      · Done when: `python3 scripts/validate_coverage.py` green.

## Definition of done

- Every phase is done. Baseline before the first edit, one line, so a red after it is attributable:
  `for f in scripts/validate_*.py; do python3 "$f" >/dev/null 2>&1 || echo "RED $f"; done`.
- `validate_request_bounds.py` was seen red on the tree phase 1 left, and its red list is pasted into this
  CR's retrospective.
- The three `validate_guard_gaps.py` cases are rejected, and the whole guard chain is green.
- Phase 2's slice tests were seen red on their status assertion before the fix.
- `docs/coverage/` carries phase 3's criteria, and `python3 scripts/validate_coverage.py` is green.
- The styleguide bullet gets its one pointer to the guard (`enforced by scripts/validate_request_bounds.py`)
  in the same commit that lands the guard.

**Not proven by any of the above:** that a bound is the *right* value. The guard ties the annotation to the
schema, never either to the column or the client (`validate_maxlength_storage.py` covers base64 caps against
`TEXT`). And parameters: the generator derives those, and no test sends an out-of-range one.

## Retrospective
