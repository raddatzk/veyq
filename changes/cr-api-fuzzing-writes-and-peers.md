---
id: cr-api-fuzzing-writes-and-peers
title: API fuzzing, second slice — positive writes, stateful sequences, a second identity
status: in-progress
satisfies: []
adrs: ["0091", "0042", "0072"]
invariants:
  - operator-blind — the run sends only what any client may; the second identity's refusals tell it nothing (a non-member and a bogus id answer alike)
  - self-hostable — the same MIT Schemathesis image, no new tool, no hosted service
  - API-first — the contracts stay the oracle; no hand-kept links in them, no DTO by hand
---

## Goal

`api-fuzz` (ADR 0091 decision 9) fuzzed every operation negatively and every `GET` positively. This slice (inbox
#495) adds the three things the first one left: **positive writes** (`positive_data_acceptance`: a request the contract
allows is never refused for its shape), **stateful sequences** (Schemathesis' stateful phase: `use_after_free`,
`ensure_resource_availability`), and **a second identity** that must not read or write the first one's objects. It also
gives each run its own database and bucket (inbox #501).

## Decisions

1. **Which writes run positively.** Every public-listener write whose effect stays inside what the run's own identities
   own below the tenant — their account's sealed data, their scopes and what is in them, their links. The server checks
   such a write for shape, ids, membership, revisions and epochs, never for what its ciphertext says (operator-blind), so
   schema-valid random ciphertext is valid data as far as the server can know, and a fuzzer can make it. The seeded
   objects (decision 3) are the ids those writes are fixed to.
   **Negative-only:** every admin-listener write (the instance's configuration — derived from `x-veyq-channel`, no
   list); the writes that change how the run's identities sign in (password, key material, wraps, the account, a
   session); the tenant's own configuration and membership (terms, capability switches, mail, storage, member role and
   removal), which every later request is answered by. Those last two are `NEGATIVE_ONLY` in `scripts/run_api_fuzz.py`,
   a reason per entry; the runner refuses to start when an entry names no operation.
   **Sent, but cannot succeed:** a write carrying a credential the run cannot forge (`x-veyq-visibility: token`): its
   declared 401/403 is a correct answer, which `positive_data_acceptance`'s status set already admits.
2. **Order, so fixtures survive the reads.** The positive `GET`s run before the positive writes (which may trash,
   revoke or purge the seeded objects); the stateful sequences run after both on containers only.
3. **The seed** (`web/scripts/fuzz-seed.mts`, the client's own modules under Node): in the owner's personal scope a
   Memory, one medium (sealed bytes, original only), a bucket and a READ link; a **peer** — a tenant member onboarded
   the product's way (invite by mail, cold accept) with a personal scope of its own. Tokens `token` / `token-peer`,
   0600, never printed.
4. **Links come from Schemathesis' inference, not from the contracts.** The contracts declare no `links` (0 of 227
   operations); the stateful phase infers them from the schemas (`dependency-analysis`) and from `Location` headers.
   Hand-kept links would be a second description of what the schemas already say, and a drifting one. The stateful
   config fixes only the containers (`tenantId`, `userId`, `scopeId`), so a sequence creates the owner's objects and
   reaches them through its own links.
5. **The second identity's rule is a status, held by `hooks.py` `peer_is_refused`, not Schemathesis'
   `object_level_authorization`.** Every positive request the owner made in its personal scope and the server accepted
   is sent again as the peer: it must answer exactly what the same request against a scope nobody has answers — `404`,
   the same `code` (membership is the first guard, `cc.security.authorization` § 2, ADR 0042). Where the path names an
   owner's object (node, medium, bucket, blob), the request is sent again as the peer in its own scope, and must answer
   what the same request naming an object nobody has answers. Shares (a capability) and tenant-level paths (the peer
   is a member) are out of its reach on purpose. The built-in check stays off: it needs `[auth.wfc]` (both tokens in a
   file, the bearer config replaced) and calls a read a leak only on an equivalent body.
6. **Budget and seed.** Same seed (`SEED`), same `MAX_EXAMPLES`; the stateful phase at its default scenario length.
   Measured below; the knob, if it does not fit, is `MAX_EXAMPLES`.
7. **A finding** is fixed here with a test in the ordinary suite, or deferred as a baseline entry naming an open inbox
   row (never `expires`).

## Phases

- [ ] 1 runner + seed + hooks — the above; `npm run check` (web), the guards · Done when: a whole run is green with the
      new invocations testing operations, the summary lists what never answered 2xx
- [ ] 2 findings — each fixed with a test, or deferred to its own inbox row · Done when: `./gradlew check` green when
      the backend changed; the baseline guard green
- [ ] 3 docs — ADR 0091 decision 9, the runner's docstring, `site/` security page, inbox #495/#501 · Done when:
      trust-chain green

## Definition of done

A whole `run_api_fuzz.py` green, within ADR 0091's 15 minutes with room for the jar build and `npm ci`; the
decisions above live in ADR 0091 decision 9 and the runner's docstring; this file deleted.

## Measured

(filled in by phase 1)

## Retrospective

(at close)
