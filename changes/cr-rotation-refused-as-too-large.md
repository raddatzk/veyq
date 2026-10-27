---
id: cr-rotation-refused-as-too-large
title: A key rotation refused as too large says so, names the Server Owner, and does not ask for a retry
status: planned
satisfies: ["uc.account.rotate-keys"]
adrs: ["0060", "0104"]
invariants:
  - a rotation the server refused has changed nothing, and the copy may say so only where the server answered
  - a failure with no answer (a fetch that rejected) is never labelled "too large" - the client cannot know it was
  - the panel reads one error type from rotateKeys (RotationRefused), as the cold-credential refusals already do
  - a server response body is read for its machine-readable code only, never shown (shared/httpError.ts:10-11)
---

## Goal

Inbox #553. A rotation whose request body is past the request limit is answered `413`, and nothing is written. Today
the panel shows the generic `rotation.errorFailed`: "Your keys could not be replaced. Nothing was changed — please try
again." Trying again sends the same body and fails the same way. The user cannot fix it, but the Server Owner can.

This CR maps a `413` on the publish to a refusal of its own, with copy that says trying again will not help and names
the Server Owner. It does **not** map a network failure to "too large", because the browser cannot tell the two apart
(§ What the browser can see).

**This is small.** One branch in `rotateKeys.ts`, one in the panel, one i18n key in EN and DE, two unit tests, and the
doc lines that carry the new flow. It is a CR, not a direct fix, for two reasons. Q1 asks whether the reset case is
measured in a browser first. Q4 is a finding about the same `catch` that needs its own row.

## Context & constraints

Measured at refine on `worktree-agent-a5e897a765499fe51` at `3f06c90de`, with nothing changed. Every pointer was read in
this refine. Claims held from memory are marked *(remembered)*. This worktree has no `node_modules`, so `npm run check`
was not run. The generated client was read in the main checkout's
`src/web/src/api/generated/account/runtime.ts` (it is generated, not in git).

### What the server sends, and where

- **The contract declares it on this operation.** `rotateAccountKeys` lists
  `"413": { $ref: "_common.openapi.yaml#/components/responses/RequestTooLarge" }` (`contracts/api/account.openapi.yaml:674`).
  The shared response is `ErrorResponse` with `code: request_too_large`, "answered on the `Content-Length` header
  alone, before the body is read or the caller is authenticated, and the connection is closed with it"
  (`contracts/api/_common.openapi.yaml:81-89`). The 413 on this operation means nothing else.
- **The backend sends exactly that shape on every path, this one included.** `RequestTooLargeRoutes` is a global
  route ordered one ahead of Quarkus' own upload limit (`src/backend/core/src/main/kotlin/me/raddatz/veyq/core/http/RequestTooLargeRoutes.kt:47-48`).
  A declared length past the limit is answered from the header (`:64-69`). A body of unknown length is counted while it
  is read and answered by the failure handler (`:51-62`, `:73-79`). Both write `{code: request_too_large}` with
  `Connection: close` and then close the connection (`:81-93`). `RequestTooLargeSliceTest` holds both paths
  (`src/backend/core/src/test/kotlin/me/raddatz/veyq/core/http/RequestTooLargeSliceTest.kt:82-85`).
- **The limit is `quarkus.http.limits.max-body-size`, 10,240 KiB by default** (`RequestTooLargeRoutes.kt:39`). At that
  default, a rotation at the pin store's cap of 25,000 entries sends 9,602,700 bytes and is answered `200`
  (ADR 0104 § Consequences). So this case is reached only when an instance lowers the limit, a proxy in front has a
  lower one, or a bound moves.
- **Nothing was written.** The answer comes before authentication and before the body is bound. On the client the
  `413` leaves the publish loop at `rotateKeys.ts:204-206` (only a `409 stale_revision` retries). The `finally` zeroes
  the new keys (`:248-252`), and the vault is untouched, since `setUnlocked` runs only after the publish (`:237`).
  `withRefresh` does not retry it, because it is not a 401 (`src/web/src/app/sessionRefresh.ts:89-98`).

### Only the rotation sends a body this large

ADR 0104 names two writes that carry the whole pin store: a rotation and a reset. The reset writes the **empty** set
(`sealWholeSet`'s doc, `src/web/src/features/account/identityPins.ts:388-390`), so it stays small. No other web flow is
in scope here.

### What the browser can see

- **A `413` that arrives is a `ResponseError`.** The generated runtime throws one for every non-2xx answer
  (`runtime.ts:135-139`). Its `.response` is what `shared/httpError.ts` decodes, and `httpStatus` reads it without
  `instanceof` (`src/web/src/shared/httpError.ts:36-45`).
- **A fetch that rejects is a `FetchError`** wrapping the browser's error (`runtime.ts:205-220`). The Fetch standard
  gives a network error as a bare `TypeError` with no reason *(remembered)*. Its message is the browser's own wording
  ("Failed to fetch", "NetworkError when attempting to fetch resource.", "Load failed") *(remembered)*. So **a reset
  mid-body cannot be told apart from being offline, a dropped Wi-Fi, or a proxy timeout.** The `ECONNRESET` the row
  cites is undici's `cause.code` in Node. A browser exposes nothing like it.
- **Whether a browser sees the 413 or a reset is not measured.** The row's reset was measured with undici, on the
  measurement's **seed** write (the pin store's own `PUT`), not on a rotation and not in a browser. With the limit set
  just below the rotation, the rotation itself was answered `413`
  (`src/web/src/features/account/rotation/rotationAtPinCap.measure.e2e.test.ts:26-30`). The server closes the
  connection while the client is still sending. Whether the client then reads the `413` or loses it to a TCP reset
  depends on timing, the browser's HTTP stack, and any proxy hop. Over HTTP/2 a server may answer early and reset the
  stream, and the client keeps the answer *(remembered, RFC 9113 § 8.1)*. A Go-based proxy that loses the backend's
  answer to a reset may answer `502` instead *(remembered)*. None of this is measured. Q1 asks whether to measure it.

### Can the client check the size before sending?

It knows the body: it builds the request (`rotateKeys.ts:190-201`) and could serialise it once. It does **not** know the
limit:
- no operation serves it (`git grep -n -i "max-body-size\|bodyLimit\|requestLimit" -- contracts src/web/src` finds the
  limit only in descriptions and a test comment);
- `/api/setup`, the pre-authentication surface for instance facts, has two operations and neither carries it
  (`contracts/api/setup.openapi.yaml:29`, `:75`).

A check against a hard-coded 10,485,760 bytes would pass in exactly the case this row is about (a lowered limit). Serving
the limit means a contract field, the backend serving it, and the web reading it, for a case the default never reaches.
It would still not see a proxy's own limit. So this CR maps the answer and does not add a pre-flight check (Q2).

### Is a 413 mapped anywhere else?

No. `git grep -n "413\|request_too_large\|contentTooLarge" -- src/web/src src/admin-web/src ':!**/generated/**'` finds
only the measurement's comment (`rotationAtPinCap.measure.e2e.test.ts:29-30`). The other "too large" messages are
client-side refusals that never reach the server: `SaveTooLarge` (`core/export/ExportSubtreeDialog.vue:75`,
`features/depot/levelRows.ts:396`) and the cover's size check (`core/node/cover/pickImage.ts:8`). The pattern to
follow is the shared decoder:
- `HttpStatus` names a status only when a call site branches on it, and says to add one when a new check needs it
  (`shared/httpError.ts:14-34`). `413` is not in it yet.
- `isHttpStatus` / `isCodedError` (`shared/httpError.ts:48-50`, `:89-92`) are what the publish loop already uses for its
  `409` (`rotateKeys.ts:205`).
- The backend names the status `CONTENT_TOO_LARGE(413)` (`src/backend/core/src/main/kotlin/me/raddatz/veyq/core/http/HttpFailure.kt:29`),
  after RFC 9110's "Content Too Large". The web constant follows it: `contentTooLarge: 413`.

### Where the mapping goes

The panel maps errors synchronously in `onError` (`KeyRotationPanel.vue:138-150`). Every refusal there is a
`RotationRefused` with a `reason`, and everything else falls to `rotation.errorFailed`. `rotateKeys.ts` already turns
the cold-credential refusals into that vocabulary "so the panel keeps reading one error type" (`:113-119`).
`RotationRefused` is documented as "Thrown before any write. A rotation that refuses has changed nothing, anywhere."
(`:51-57`). A 413 matches that exactly. So the mapping goes in `rotateKeys.ts`, as a fifth `RotationRefusal`, and the
panel gains one more arm.

### The role's name

ADR 0086 holds no row for the Server Owner (`docs/decisions/0086-one-word-per-meaning.md:29-41`). The role is named in
`docs/architecture/README.md:347`. The web client's existing copy says "the Server Owner" in EN
(`src/web/src/i18n/index.ts:612`, `:741`, `:753`) and "die Person, die den Server betreibt" in DE (`:2564`, `:2694`,
`:2706`). The new copy follows both.

### Today's tests

- `rotateKeys.test.ts`'s `fakeServer` implements `rotateAccountKeys` inline (`:189-205`). It throws `httpError(409,
  'stale_revision')` for the retry tests. The bound test asserts the call count and that `setUnlocked` never ran
  (`:662-669`). `httpError` (`src/web/src/test/httpError.ts:11-17`) builds a real `Response`, so a coded `413` and a
  bare one can both be faked faithfully.
- `KeyRotationPanel.test.ts` covers **no** error arm today
  (`grep -n "error" src/web/src/features/account/rotation/KeyRotationPanel.test.ts` finds only two `mockRejectedValue`s
  on label reads, `:194-195`). Its `vi.mock('./rotateKeys')` replaces `RotationRefused` with `class extends Error {}`
  (`:9-14`), with no `reason`.
- Coverage for the use case is `docs/coverage/features/account/use-cases/uc.account.rotate-keys.yaml`. Its criteria
  list `rotateKeys.test.ts` and, at `:142`, `KeyRotationPanel.test.ts`.

## Decisions this CR takes

1. **Map a 413 on the publish, by status alone, to `RotationRefused('requestTooLarge')`.** In the publish loop's
   `catch` (`rotateKeys.ts:204-206`): `isHttpStatus(e, HttpStatus.contentTooLarge)` throws the refusal, and everything
   else is rethrown as today. The match is on the status, not on `code: request_too_large`, because a proxy in front of
   the instance answers its own body limit with a bare `413` and no JSON body *(remembered: nginx's default is 1 MiB)*,
   and the remedy is the same: the Server Owner raises a limit. The contract gives this operation's 413 no other meaning
   (`account.openapi.yaml:674`). Q3 is the owner's check on that choice.
2. **A fetch that rejects stays where it is today.** No `FetchError`, `TypeError`, `502` or `504` is mapped to "too
   large". The client cannot know it, and telling an offline user to call the Server Owner is worse than the generic
   copy. If Q1's measurement shows that browsers mostly see a reset, the fix that does not mislabel is the server's:
   it must make the `413` arrive. That is a backend change to `RequestTooLargeRoutes`, its own CR (Q1 says when).
3. **The panel maps the new reason to `rotation.errorTooLarge`** (EN and DE), in the same `RotationRefused` chain
   (`KeyRotationPanel.vue:139-149`). `trustRefused` stays false for it. The copy says three things: the request is
   larger than this server accepts; nothing was changed; trying again will not help, so ask the Server Owner. It gives
   no number, because the client does not know the limit. The proposed text is in Q5.
4. **`HttpStatus` gains `contentTooLarge: 413`**, with a one-line note on what it decides. This follows the rule at
   `shared/httpError.ts:14-19`.
5. **The flow becomes durable state.**
   - `uc.account.rotate-keys` gains an exception flow **E10 — the rotation is larger than the server accepts**: the
     `413` before any write, the copy, why retrying cannot help, and that the Server Owner's limit is the remedy.
   - Its coverage YAML gains a criterion with both unit tests.
   - The use case's E7 is **not** touched here. See Q4.
6. **The published docs move with it** (`changes/README.md` § The published docs move with the change).
   - **Use (EN and DE):** `site/src/content/docs/account/replace-your-keys.mdx` and its `de/` twin gain one line: if
     the page says the request is larger than the server accepts, trying again won't help, so ask the Server Owner.
   - **Run (EN):** `site/src/content/docs/run/troubleshooting.md` gains a short section, *Replacing keys is refused as
     too large*. It names the cause (the instance's `QUARKUS_HTTP_LIMITS_MAX_BODY_SIZE` set below the default, or a
     proxy in front with a lower limit), what to do (raise it to at least the default 10,240 KiB), and why the default
     carries the pin store's cap (ADR 0104). Today no Run page mentions the request limit
     (`git grep -n -i "max-body-size\|MAX_BODY_SIZE\|request limit" -- site infra` is empty). Copy that sends a member
     to the Server Owner needs a page that tells the Server Owner what to change.

### Not in this CR

- **A pre-flight size check, or serving the limit** (Q2).
- **A server-side change that makes the 413 reach a client still sending** (Q1, decision 2).
- **What a lost answer after a committed publish does** (Q4). This is a separate defect in the same `catch`.
- **Suggesting "Start a list over" to shrink the request.** The reset empties the pin store, so the rotation would fit.
  But it throws away every verified contact key to get past an operator's setting. A message must not offer that.

## Inventory

| What | Where | Locator |
|---|---|---|
| The refusal reason and the 413 branch | `src/web/src/features/account/rotation/rotateKeys.ts` | `grep -n "RotationRefusal\|isCodedError(e, HttpStatus.conflict" src/web/src/features/account/rotation/rotateKeys.ts` |
| The status constant | `src/web/src/shared/httpError.ts` | `grep -n "tooManyRequests" src/web/src/shared/httpError.ts` |
| The panel arm | `src/web/src/features/account/rotation/KeyRotationPanel.vue` | `grep -n "RotationRefused" src/web/src/features/account/rotation/KeyRotationPanel.vue` |
| The copy, EN and DE | `src/web/src/i18n/index.ts` | `grep -n "errorFailed: 'Your keys\|errorFailed: 'Deine Schlüssel" src/web/src/i18n/index.ts` |
| Every reader of the reason type (a new union member may need an arm) | web | `git grep -n "RotationRefused\|RotationRefusal\|\.reason ===" -- src/web/src ':!**/generated/**'` |
| The use case, its coverage | `docs/features/account/use-cases/uc.account.rotate-keys.md`, `docs/coverage/features/account/use-cases/uc.account.rotate-keys.yaml` | `grep -n "^- \*\*E9" docs/features/account/use-cases/uc.account.rotate-keys.md` |
| The site pages | `site/src/content/docs/account/replace-your-keys.mdx`, `site/src/content/docs/de/account/replace-your-keys.mdx`, `site/src/content/docs/run/troubleshooting.md` | `git grep -n -i "replace your keys\|Schlüssel ersetzen" -- site/src/content/docs` |

Ran at refine, second angle on the reason type: `git grep -n "RotationRefused" -- src/web/src` finds `rotateKeys.ts`,
the panel, the panel test's mock, and `rotateKeys.test.ts`. Nothing else switches on `reason`.

## Phases

### Phase 1 — what a browser sees (only if Q1 is answered "measure")

No product change. Run headless system Chrome (memory: browser pass in headless system Chrome). Use a page **on the
backend's own origin**, because a cross-origin page without CORS reads every failure as a `TypeError` and would fake
the result. From there, `POST /api/account/rotation` with a body past a lowered limit. The `413` is answered before
authentication, so no account is needed. Use a built fast-jar from the worktree, started with
`QUARKUS_HTTP_LIMITS_MAX_BODY_SIZE` lowered, per the measurement's recipe
(`rotationAtPinCap.measure.e2e.test.ts:8-13`). Never use a second `quarkusDev`.

Sweep the body past the limit by about 1.1×, 2× and 10×, ten requests each. Record for each request whether `fetch`
resolved with `413` and a readable `code`, or rejected. Do it once directly against the jar, and once through the dev
stack's proxy if it can be reached without recreating a shared container.

**Done when:** the table is in the CR's Retrospective, and the owner has decided from it whether the backend CR in
decision 2 is filed.

### Phase 2 — the refusal, its copy, its proof, its docs

Decisions 1 to 6.

- **`rotateKeys.test.ts`**, in the `describe` that already holds the publish's retries ("the pin store and the
  register ride in the publish"):
  - give `fakeServer` an option that makes `rotateAccountKeys` throw a given error, as `staleRotations` does today;
  - `it.each` over `httpError(413, 'request_too_large')` and a bare `httpError(413)`: the call rejects with
    `{ reason: 'requestTooLarge' }`, `rotateAccountKeys` was called **once** (no retry), and `setUnlocked` never ran;
  - controls on the same option: `httpError(500)` and a `FetchError`-shaped rejection (an `Error` with a `TypeError`
    `cause` and no `response`) are rethrown as they are, **not** as `requestTooLarge`. These are the tests that go red
    if the branch is widened to "any failure of a large body".
- **`KeyRotationPanel.test.ts`**, a new `describe` for the error arms:
  - the mock's `RotationRefused` gets a constructor that keeps `reason`, so the panel's `reason` branches can be
    reached;
  - a refusal with `requestTooLarge` shows `t('rotation.errorTooLarge')`;
  - a plain `Error` still shows `t('rotation.errorFailed')`.
  These are the panel's first error-arm tests.
- **Bite each test once.** Remove the 413 branch: the first `it.each` goes red. Widen it to every non-`ResponseError`
  rejection: the `FetchError` control goes red. Drop the panel arm: the panel test goes red. Restore from the snapshot,
  never with `git checkout` (`changes/README.md` § Bite-proof a guard by snapshot-and-restore).

**Done when:**
- `npm run check` in `src/web/` is green;
- the trust-chain guards are green (`scripts/validate_*.py`, without `validate_guard_gaps.py`, since no guard changes);
- the site builds;
- **browser pass:** on an instance whose limit lies between the pin store's seed write and the rotation (the
  measurement's recipe, `rotationAtPinCap.measure.e2e.test.ts:26-30`), *Replace my keys* in the owner's German UI
  shows the DE text of `rotation.errorTooLarge`, and a second press shows it again.
  If this phase cannot run a browser, it says so, and the pass stays an open item here.

## Definition of done

- A `413` on the rotation's publish shows copy that says trying again will not help and names the Server Owner, in EN
  and DE.
- Nothing else that used to show `rotation.errorFailed` shows the new copy. The `FetchError` and `500` controls prove
  it.
- E10 is in the use case, its criterion is in the coverage YAML, and the Use (EN and DE) and Run pages carry it.
- This CR is deleted, after its Retrospective's findings have moved to where they apply.

## Open questions (owner)

**Q1 — Measure what a browser sees before mapping, or map the 413 and stop?**
- **Recommendation: measure (phase 1). It is cheap: pre-authentication, no account, a built jar.**
- **Reason:** if browsers mostly lose the `413` to a reset, decision 1 helps almost nobody. Then the honest fix is on the
  server, where `RequestTooLargeRoutes` closes the connection right after it answers (`:89`). The alternative is to
  ship phase 2 alone and file the reset as a row.

**Q2 — No pre-flight size check, and the limit stays unserved?**
- **Recommendation: yes.**
- **Reason:** the limit is not discoverable (§ Can the client check the size). Serving it is a contract, backend and
  web change on the pre-authentication surface, for a case the default never reaches. It would still miss a proxy's
  limit, and a hard-coded default is wrong in exactly this case.

**Q3 — Match the 413 by status alone, or only with `code: request_too_large`?**
- **Recommendation: by status.**
- **Reason:** a proxy's own 413 has no veyq body, and its remedy is the same: a limit the Server Owner sets. The
  contract gives this operation's 413 no other meaning. Matching the code would show "please try again" for exactly the
  proxy case.

**Q4 — A lost answer after a committed publish. File it as its own row?**
- **Recommendation: yes, a V1 row, not part of this CR.**
- **What the code shows (read, not run):**
  - The generic arm's "Nothing was changed" is also shown for a `FetchError`, the case where the server may have
    committed and its answer was lost.
  - A retry then mints new keys again (`rotateKeys.ts:130-131`). It does not "finish rather than re-mint", which E7 says
    the client does.
  - The retry is refused at the cold-credential proof as `credentialRejected`, because the floor now opens `MK'` while
    the vault holds `MK` (`features/account/coldCredential.ts:62-70`). The user is told their password "didn't match".
  - Worse: the sole-member re-key, which opens those scopes' grants with the **old** identity, runs only after the
    publish (`rotateKeys.ts:224-230`). The old identity's private key is then held only in this tab's vault. Closing the
    tab may lose access to the personal space.
- **Why it is a separate row:** it needs a recovery design, not a message. The copy for "no answer" depends on that
  design. This CR's `FetchError` control keeps today's behaviour so that the row starts from a known state.

**Q5 — The copy. The owner decides the wording; proposed:**
- EN `rotation.errorTooLarge`: "Your keys could not be replaced, because the request is larger than this server
  accepts. Nothing was changed, and trying again won't help. Ask the Server Owner to raise the server's request limit."
- DE `rotation.errorTooLarge`: "Deine Schlüssel konnten nicht ersetzt werden, weil die Anfrage größer ist, als dieser
  Server annimmt. Es wurde nichts geändert, und ein neuer Versuch hilft nicht. Bitte die Person, die den Server
  betreibt, die Grenze für Anfragen anzuheben."
- **Recommendation: take these.**
- **Reason:** they follow the panel's existing sentence pattern ("… could not be replaced. Nothing was changed …",
  `i18n/index.ts:300`, `:2278`) and the role's two spellings (§ The role's name). They give the Server Owner, who is
  often the same person in a self-hosted instance, a word to search the Run page for ("request limit").

## Retrospective

*(filled at implement time)*
