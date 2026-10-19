---
id: cr-statement-facts-sealed-once
title: A statement's facts are sealed once, and per affected member only the facts key is wrapped
status: in-progress
satisfies: ["uc.moderation.object-to-a-measure"]
adrs: ["0093", "0072", "0099"]
invariants:
  - operator-blind - the server stores the facts only sealed, once under K_facts and once to the moderation key, and K_facts only wrapped to each member's identity
  - whole or refused - a statement reaches every affected member or the step is refused by name, never cut
  - every sealed moderation text fits its own schema at its largest plaintext
---

## Goal

Owner decision 2026-10-19 ("in V1"): the moderator's browser seals a statement's facts **once**, under a fresh
`K_facts`, and wraps only `K_facts` to each affected member's identity — so the facts' length no longer decides how many
members one step can tell ([ADR 0093](../docs/decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
§ 7, *The facts are sealed once*). And the latent size bug inbox #498's agent found: an asymmetric moderation seal of
7,677–8,000 UTF-8 bytes is 12,456 base64 characters, above every such field's `maxLength: 12000`.

## Context & constraints

- State: ADR 0093 § 7 (the decision and its bound), [uc.moderation.object-to-a-measure](../docs/features/moderation/use-cases/uc.moderation.object-to-a-measure.md)
  step 1, [security-model](../docs/crosscutting/security-model.md) § moderation. ADR 0072 Decisions 1 and 3 (every id
  the opener addresses by is bound; the client mints the id of what it seals — `id_taken`).
- Not live: the old per-member format (`moderation-facts` [caseId, accountId]) is dropped, no legacy path; a dev
  instance's existing statements lose their facts for the members (the log's copy still opens).
- Sizes (measured from the envelope layout, `packages/crypto/src/primitives.ts`): an X-Wing wrap of a 32-byte key is
  1 + 1,120 + 12 + 32 + 16 = 1,181 bytes = 1,576 base64 characters; the facts once, at their largest bucket (8,192), are
  1 + 12 + 8,192 + 16 = 8,221 bytes = 10,964; a note-encoded asymmetric seal at its largest is 9,341 bytes = 12,456.

## Phases

1. **Docs** — ADR 0093 § 7, the use case, the coverage text, the security model, the site's Run and Security pages.
2. **Crypto + contracts** — purposes `moderation-statement-facts` [caseId, statementId] (symmetric, under `K_facts`) and
   `moderation-facts-key` [caseId, statementId, accountId] (to an identity); `moderation-facts` removed. Vectors from an
   independent Python implementation, which first re-derives an existing vector byte for byte. Contract:
   `ModerationStatementInput.statementId` (client-minted), `sealedFacts`, `factsKeyFor`; `ModerationStatement.sealedFacts`
   + `wrappedFactsKey`; every note-encoded moderation seal's `maxLength` 12,456. Done when: `npm run check` and
   `npm run mutation -- --force` (100 %) in packages/crypto.
3. **Backend** — migration, entities, `takeIn` (the statement id the client's, `409 id_taken`; facts, sealed facts and
   wraps together or not at all), the member's read, the bound. Done when: `./gradlew check`.
4. **Web** — `takeMeasure` seals once and wraps per member; `openFacts` unwraps and opens; the bound's message. Done when:
   `npm run check` in web/ and admin-web/.

## Bites

- The bound: `MAX_SEALED` raised past what 10,240 KiB holds → the body-size test answers 413 (red).
- The size bound: the contract's 12,456 back to 12,000 → the crypto boundary test and the backend validator test red.
- The binding: each new purpose's vector and the conformance test's moved-field and other-purpose refusals; the
  mutation gate at 100 %.
