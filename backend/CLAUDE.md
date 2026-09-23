# backend (Quarkus / Kotlin)

Operator-blind API service. Loaded in addition to the root `CLAUDE.md` when working here.

## Prove your work
- `./gradlew check` (compile + slice tests) — runs on **in-memory H2** (`%test`, hermetic + fast).
- **Run the app (`quarkusDev`) on a persistent MariaDB** (`%dev`): `docker compose -f infra/docker-compose.yml
  up -d` starts it; data survives restarts (Flyway migrates once, then "up to date"). `docker compose -f
  infra/docker-compose.yml down -v` wipes it for a fresh instance. The H2 fallback is only the test default now.

## Conventions (see [developer styleguide](../docs/crosscutting/developer-styleguide.md))
- **API-first.** Edit the spec in `../contracts/api/*.openapi.yaml`; the Gradle build generates Kotlin
  models + JAX-RS interfaces (`openApiGenerateSpecs`). Never hand-write a DTO that duplicates a generated model.
  **Nothing generated is tracked** (`backend/build/` is gitignored), and the generator emits no descriptions —
  `useSwaggerAnnotations` / `useMicroProfileOpenAPIAnnotations` are both `false` in `gradle/openapi.gradle.kts`.
  So a spec edit that changes only `description:` / `summary:` text produces **no backend delta at all** and
  needs no backend phase: the contracts guards are its whole check. A shape change — a property, a type, a
  `format:`, an enum, a required flag — is the opposite, and always does.
- **Feature-slice + two axes ([ADR 0001](../docs/decisions/0001-backend-http-adapter-vs-application-slicing.md)):**
  `features/<area>/<usecase>/…`. A `…Resource` implements the generated `…Api` (thin adapter), delegates to
  a `…Handler`; persistence in a `…Store` (Panache). Resources are resource-shaped, not per-use-case.
- **Validation + sanitization belong in the spec** via `x-field-extra-annotation` (bean-validation
  `@NotBlank`/`@Size`/`@Pattern` + a Jackson trim deserializer) → enforced by `@Valid` at the boundary,
  **400 before the handler** — but only one level deep: a nested object or list needs its own
  `@field:jakarta.validation.Valid`, and every request bound needs its annotation
  ([styleguide](../docs/crosscutting/developer-styleguide.md)). Don't re-validate field shape in the handler.
  **Match the annotation to the GENERATED type, not to the wire type.** A property with `format: uuid`
  generates as `java.util.UUID`, and a String validator on it is not a stricter check — it is a
  `UnexpectedTypeException` at request time, i.e. a **500 on a well-formed request** instead of the 400 the
  annotation was reaching for. A uuid field takes `@NotNull` and nothing else; `maxLength` on one is dead text,
  since the length is the type's. Same trap for any `format:` that changes the generated type.
- **Migrations:** Flyway, append-only `V<n>__*.sql`; `schema-management.strategy=none` — Flyway owns the
  schema. **Append-only means an applied migration is never edited — not even a comment in it.** Flyway
  validates the checksum of every applied migration at `migrate-at-start`, so a one-word fix to a `--` comment
  fails startup on every instance that already ran it; `%dev` hides this behind `repair-at-start=true`, so it is
  green exactly where you would test it and red only on somebody else's server. A migration's comment is a
  historical artifact: correct the live copy of that rule (the entity KDoc, the ADR) and leave the file alone.
  Column types: `TEXT` for opaque base64 (65 535 bytes on MariaDB, so a base64 cap must be ≤ 65 532 —
  guarded by `scripts/validate_maxlength_storage.py`), `MEDIUMTEXT` where a cap needs more (16 MiB), `VARCHAR`
  for short. The test datasource runs H2 with **`MODE=MariaDB`** (`application-test.properties`) so it parses
  what prod parses — a **parity** measure, not a workaround: a type-change is `MODIFY COLUMN` (MariaDB-only
  syntax; default-mode H2 rejects it, and there is no type-change form in the H2 ∩ MariaDB *default* subset).
  The mode does **not** make H2 enforce MariaDB's byte ceiling — that stays the guard's job; don't conflate them.

## Invariant
The backend performs **no content crypto** and never sees the password (Model B,
[ADR 0003](../docs/decisions/0003-operator-blind-password-auth.md)). It stores only ciphertext, public
keys and low-sensitivity operational metadata. Re-read
[security model](../docs/crosscutting/security-model.md) before touching any key/data/auth path.
