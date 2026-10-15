# ADR 0044 — Field visibility is declared at the field, not inferred from its name

- Status: Accepted
- Date: 2026-07-15

## Context

Operator-blindness is this project's first hard invariant: *"no path may break this"* (`CLAUDE.md`). It is also
the only major claim in the repo that **nothing checks**. Nine validators guard doc links, feature format,
coverage refs, shared components, ADR quoting and scope vocabulary. None of them guards the invariant the
product exists for.

[`security-model.md`](../crosscutting/security-model.md) § *What is encrypted vs. server-visible* already draws
the line, and draws it well: media bytes, journal text, titles, captions, places, tags, GPS and capture time are
sealed; ids, scope and membership, timestamps, sizes, blinded tokens and the tenant's terms are visible.
But it draws it in **kinds** — "sizes", "timestamps" — and the contracts are written in **field names**.
No mechanism connects the two, so the enumeration is advice to whoever remembers to read it.

The contract is where the hole opens. This is an API-first repo: a field exists in a spec before any client can
fill it. A plaintext `title: {type: string}` in `content.openapi.yaml` is the design error; a client putting a
real title into it is only the symptom, and it arrives one phase later.

Measured over `contracts/api/*.openapi.yaml` (2026-07-16): 9 specs, 140 schemas, **401 property occurrences**,
136 distinct names. **106 occurrences already carry `contentEncoding: base64`**; 60 are containers whose targets
carry their own properties; **235 scalar occurrences say nothing about whether the server may read them.** No
request or response body defines inline properties — every named field lives under `components.schemas.*
.properties.*` — so a validator over that path sees the whole body surface.

## Decision

**A field declares its own visibility. The contract states it; the trust chain enforces it.**

1. **`contentEncoding: base64` ⇒ opaque — no marker needed.** The server derives no meaning from these bytes,
   whether they are ciphertext, a wrapped key, a public key, a signature, a salt or a blinded token. This is
   standard OpenAPI 3.1, it is already true for all 106 occurrences, and — unlike `x-field-extra-annotation`,
   which binds request DTOs only — it reaches the 59 response-only field names too.

2. **Every other scalar property carries `x-veyq-visibility: <class>`.** An unmarked scalar fails the build.
   Containers (`$ref`, or an array of `$ref`) carry none: their target's properties carry theirs. **A container
   stays one through every nullable spelling** — OpenAPI 3.1 has three (`type: ['array','null']`,
   `oneOf: [{$ref}, {type: null}]`, and the plain form), all three are in these specs, and 11 containers hide in
   the first two. A guard that only knows `type: array` demands a marker on a `$ref` and contradicts this rule.
   The marker goes **first** in the property, before `type`: a `description` block runs to 374 characters here,
   and a claim about what the operator may read is not something to bury under one.

3. **The classes are `security-model.md` § *What is encrypted vs. server-visible*'s own kinds**, not a new
   taxonomy:

   | class | what it licenses | doc's words |
   |---|---|---|
   | `id` | an opaque identifier the server routes and authorizes on | *"existence and IDs"* |
   | `structure` | scope, membership, parentage, bucket shape, ordering index, epoch | *"scope and membership … the bucket rows' shape"* |
   | `measure` | sizes, counts, offsets | *"sizes; job-queue and op-log offsets"* |
   | `time` | server timestamps, as written (nothing rounds them) | *"server timestamps (created, changed, trashed, last rotated) for ordering"* |
   | `enum` | a closed set the contract itself declares | — (the contract is the disclosure) |
   | `flag` | a boolean the server acts on | *"group-add-consent flag"*, *"trash state"* |
   | `token` | a bearer or capability secret in transit | — (never at rest in plaintext) |
   | `public` | the server's own words — operator-authored text, codes and self-description; never user data | *"the tenant's terms text"* |
   | `pii` | deliberate sensitive plaintext | *"per-tenant username, identity public key"* |

   `public` is the doc's terms-text kind, read for what actually distinguishes it: **whose data it is**, not who
   may see it. 41 of the 235 scalars are the instance describing itself or reporting on itself — error `code`s,
   developer-facing `message`/`reason` text, `Version.major`/`build`, the capability catalog, the advertised
   crypto suites, a tenant's own display name and domain. None is the user's data, and forcing them into `pii`
   or `enum` would say something false about every one of them.

   **Two classes will often both fit. Classify by what the field means, not how it is spelled.** `role` is a
   closed set *and* it is membership — it is `structure`, because that is what it tells the server. `enum` is
   for the fields whose closed set **is** the whole meaning (`method`, `sessionScope`, `suiteId`). Read
   the other way, `enum` would swallow half the table and say nothing. A node's `kind` is no closed set: it is an
   open token that says what an object in the tree is ([ADR 0079](0079-veyq-is-a-platform-memories-is-its-first-application.md)),
   so it is `structure`.

4. **`pii` requires `x-veyq-rationale`**, naming the mechanism that forces the plaintext.
   [ADR 0023](0023-per-tenant-member-identity.md)'s rule — *"only the plaintext the mechanism requires stays
   plaintext"* — is a necessity test that has never been testable. The rationale makes each instance state its
   necessity **at the field, in the diff that adds it**, and turns the sensitive-plaintext set from a silent
   fact into a standing, greppable budget.

The point is the **default**. Today silence means "nobody classified this". After this, silence fails.

## Consequences

- **A new `title: {type: string}` cannot land quietly.** Its author must seal it or write
  `x-veyq-visibility: pii` with a reason — and a reviewer reads that choice in the same diff as the field. That
  is the whole mechanism: it does not detect bad fields, it makes the decision unavoidable and visible.
- **Backfill: 235 scalar occurrences.** Mechanical, and it is the cost. It is paid once; a marker on a field is
  cheaper than a list in the middle, which is what the rejected alternatives all are. It settled at
  `id`=74 · `public`=41 · `structure`=27 · `measure`=22 · `pii`=20 · `time`=14 · `flag`=14 · `token`=13 ·
  `enum`=10.
- **This checks the contract, not the traffic — and that is the sharpest limit, not the list below.** A field
  carrying `contentEncoding: base64` is *permitted* to be opaque; nothing here observes whether the client put
  ciphertext in it. A client that writes plaintext into a field marked opaque satisfies this ADR completely. The
  marker closes the **design** hole — a field that should never have existed in plaintext — and only that. What
  the server actually receives is a different sensor, with the wire as its oracle:
  [ADR 0053](0053-wire-assertion-canary-sweep.md) decides it. The boundary is deliberate and stays — this is the
  **design** sensor, that is the **traffic** sensor, and they do not overlap.
- **The claim is narrow, and the validator's docstring must say so.** It covers the JSON body properties and,
  by the same rules, three places a value crosses the wire outside a body property: a **scalar component schema**
  (a `$ref`'d target with no properties to carry a class — `InviteBootstrapSecret` was classified by nobody until
  this was ruled), every **`in: header` parameter** (`X-Contributor-Token`, `X-Share-Password`, `If-None-Match`)
  and every **declared response header** (the refresh-token `Set-Cookie`, the media list's `ETag`). Each was
  marked when cr-wire-assertion-capability-tokens made the `token` class load-bearing: the e2e run derives its
  capability-token needles by reading `token`-marked values out of its HTTP recording, so an unmarked token is
  one no sweep looks for. It does not see: path params, query params, presigned-URL query strings, 67 DB column
  names with no contract, error `message` free text, a header the server sends without declaring it, or **the
  S3 object key** — the one surface the operator literally lists. A green run means *"no unclassified field in a
  JSON body, and no unclassified declared header"*, never *"operator-blind, verified"*. The object key gets its
  own guard, because it needs one and this cannot be it.
- **`token` is a class, not an encoding — a base64 capability is `token`, not opaque.** The pairing `handle` is
  base64 text, but it is a routing capability the server stores only as a hash, not ciphertext: filed as
  `contentEncoding: base64` it was invisible to the token sweep, and the opacity check it got instead proves
  nothing about random bytes. The guard forbids a marker on a base64 field (a second way to say "opaque" is a
  way to say the opposite), so such a field declares its alphabet with a `pattern` and carries `token`.
- **`x-veyq-*` is claimed here.** It was provably free: `x-field-extra-annotation` (87 uses) was the only
  extension anywhere in `contracts/`.
- `security-model.md` § *What is encrypted vs. server-visible* stays the rationale and gains a pointer here. The
  specs do not restate it; they reference its kinds by name.
- **Rejected — a name-keyed rule** (`^sealed|^wrapped|^encrypted|Key$|Blob$`). Measured unsound **in both
  directions**. `ownerKey` ends in `Key`, is not a key, and is deliberate plaintext (`user:{id}` / `share:{id}`,
  [ADR 0034](0034-media-uploader-buckets.md)) — a name rule reads it as opaque and stops guarding the one field
  that most needs it. It classifies as **`structure`**, which is `security-model.md`'s own phrase for it
  (*"uploader attribution + bucket structure"*). Not `pii`: **deliberate plaintext is not sensitive plaintext**,
  and the content is a prefixed UUID. Marking it `pii` was considered — as a tripwire on the field most likely
  to *become* sensitive — and rejected, because it buys no protection (the marker checks the contract, never the
  traffic, so a username later written into it goes unseen either way) while putting two non-PII fields into the
  budget. A budget with false entries stops being read, and being read is its only function. And `kdfParams`, `pwSalt`, `pwVerifier`, `secretKeyProofSignature`, `signature`, `slugToken`,
  `nonce`, `instanceSalt` are opaque with no matching name. A convention that is 80% real is worse than none: it
  invites the rule that the other 20% silently defeats.
- **Rejected — a central allowlist of permitted field names.** 401 occurrences collapse to 136 names, so names
  are reused across schemas with different meanings: `id` appears in 7 specs; `name` is a tenant's display name
  in `tenants` and something else in `scopes`; `blob` is ciphertext in `account` while `blobId` is an opaque id
  in `media`. Allowlisting the string `name` blesses **every** future `name` in every schema, including the one
  that ends up holding a person's real name. To be sound the list must key on `(schema, property)` — 401
  entries, a second spec, drifting the moment a schema is added.
- **Rejected — guarding only the forbidden names** (assert no plaintext `title`/`caption`/`place`/`tag` appears).
  Cheap, and the same shape as `validate_scope_model_vocabulary.py`, which is why it was tempting. But it only
  catches what someone already thought to forbid, and the field that breaks this invariant will be one nobody
  anticipated — that is what makes it a breach rather than a typo. The marker inverts the default instead of
  extending a blacklist.

Explained for security researchers on the docs site: [What the server sees](../../site/src/content/docs/security/what-the-server-sees.md), [Verify it yourself](../../site/src/content/docs/security/verify-it-yourself.md).
