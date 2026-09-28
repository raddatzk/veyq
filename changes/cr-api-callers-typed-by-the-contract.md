---
id: cr-api-callers-typed-by-the-contract
title: Code that talks to the API is typed by the generated contract — the dev seeders and the unit tests' API mocks
status: planned
satisfies: []
adrs: []
invariants: [API-first]
---

## Open for the owner

**Answered by the owner, 2026-09-28: every recommendation below is taken, and implementation has the go.** Read each question's recommendation as its decision. Where a question proposed a separate inbox row, that row exists (#197–#221, `docs/inbox.md`).

These are your calls. The CR is written for the recommendation in each. No phase starts until they are
answered.

1. **How far #195 reaches.** The CR types the **API-client seam**: every mock of `src/api/client` and every fake
   standing in for a generated `…Api`. Two other seams in the same tests are just as unchecked, and they are left
   out: 19 web test files (4 in admin-web) that mock a *feature* module, some of whose functions return generated models
   (`grep -rlE "vi\.mock\(['\"][^'\"]*features/" web/src admin-web/src --include='*.test.ts'`), and the
   `as never` casts on arguments, 84 in web and 25 in admin-web (`grep -rnE "\bas never\b" <tree>/src
   --include='*.test.ts'`). **Recommendation: the seam only.** It is where the one felt miss happened (#195's
   `ShareIdentity.sealedDisplayName`), its predicate is exact (see Inventory), and the other two need a different
   rule. They are listed as a proposed follow-up instead of growing this CR.
2. **How the seeders reach the API.** (a) Call the generated `…Api` clients directly, keeping the crypto they
   already import from `@veyq/crypto` and `src/features/spaces/grant`. (b) Fold them onto the app's own feature
   functions, the way [`e2eBootstrap.ts`](../web/src/test/e2eBootstrap.ts) births an instance for the e2e suites.
   (b) would also share behaviour, not just shape. But that module's header records the split as deliberate:
   `seed-dev.mts` "still serves the local IntelliJ dev flow". The seeders also do things the e2e path does not:
   `.run/.env` I/O, the numeric-domain DB remap, reusing the Secret Key. **Recommendation: (a).** A typed body is
   exactly what would have turned the `nonce` change red.
3. **The tenant-scoped transport in `bootstrap-user.mts`.** It talks `node:http` because fetch drops a
   hand-set `Host`. The options are to keep that transport as the generated client's `fetchApi`, or to require
   a tenant domain that resolves on its own (`*.localhost` or numeric) and drop `node:http`. **Recommendation:
   keep it as `fetchApi`, in one module.** Nothing changes for an existing `.run/.env`, and the guard can exempt
   one file by name.
4. **The guard's form.** **Recommendation: ESLint rules** in `web/eslint.config.js` and
   `admin-web/eslint.config.js`, following `veyq/tests-use-the-real-catalog`, rather than a Python
   `validate_*.py`. The predicates below were run against the untouched tree, and each matches the grep count
   exactly. Lint already covers `scripts/**/*.mts` and `src/**/*.test.ts` (*read* from the `veyq/files` block,
   not run: the probe used its own config).
5. **admin-web.** It has the same seam, in 2 sites and 2 files, and no seeders. **Recommendation: include it,
   with its own copy of the ~30-line helper.** The npm workspace exists for `@veyq/crypto` only (root
   `CLAUDE.md`), so a test helper is not shared.

## Goal

A contract change is supposed to be loud. Change a field in `contracts/api/`, run `generate:api` and `check`,
and every caller that still uses the old shape goes red. Two kinds of caller in `web/` are outside that net:

- **The dev seeders** (#185). `bootstrap-user.mts`, `seed-dev.mts`, `seed-tenant.mts` and `seed-owner.mts` hand
  JSON literals to `fetch` and read `any` back. They are also outside `npm run typecheck`, because
  `web/tsconfig.json` includes only `src` and `vite.config.ts`. When the contract made login verify name its
  nonce, the app's login changed and the seeders did not, so `npm run bootstrap:user` created the owner and then
  failed its own login with 400. That broke the documented cold start.
- **The unit tests' API mocks** (#195). Tests hand the code a mocked `…Api` with
  `as unknown as ReturnType<typeof xApi>`, and inside it an untyped `vi.fn().mockResolvedValue(payload)`. Neither
  part checks the payload against the model. `cr-guest-names-for-members-only` removed
  `ShareIdentity.sealedDisplayName` and predicted a red at the fixture that served it. The fixture stayed green.

After this CR, both callers are typed by the generated client. The next rename or removal fails `typecheck` at
the seeder or the mock that still uses the old shape. Lint rules keep the hand-written forms from coming back.

## Context & constraints

- **The rule this extends:** [developer styleguide](../docs/crosscutting/developer-styleguide.md) §3 *API-first
  contract* ("never hand-write a DTO that duplicates a generated model") and §10 *Testing* ("test data comes from
  one fixture per surface"). That section already holds the precedent this CR follows: `realI18n()` plus a lint
  rule, and the one shared [`httpError`](../web/src/test/httpError.ts), both from inbox #171.
- **`npm run check` does not regenerate the client** ([`web/CLAUDE.md`](../web/CLAUDE.md)). Every bite below that
  edits a contract or a generated model runs `npm run generate:api` (or edits `src/api/generated/`, which is
  untracked and restored by regenerating), then `check`.
- **The generated clients are `typescript-fetch` with `withInterfaces=true`**
  (`grep -n "withInterfaces" web/package.json admin-web/package.json`). Each `…Api` class also exposes `…Raw`
  and `…RequestOpts` members. That is why a partial object literal is not assignable to `ReturnType<typeof xApi>`
  without a cast. *Measured:* `vi.mocked(publicSharesApi).mockReturnValue({ resolveShare: vi.fn() })` is
  `TS2345`: the literal is missing 48 of the class's members.
- **Out of scope:** the `docker exec … UPDATE veyq_tenant` DB poke in `seed-dev.mts`/`bootstrap-user.mts`, the
  feature-module mocks and argument casts named in question 1, and production code. Production is already clean:
  `grep -rnE "\bfetch\(" web/src admin-web/src --include='*.ts' --include='*.vue' | grep -vE "generated|\.test\.ts|test/mailpit"`
  finds only S3 presigned PUT/GET, HIBP and the dev-credentials middleware, none of them the veyq API.

## Inventory

All counts were *measured* on 2026-09-28 against `faf9ef4` (clean worktree), each with the command beside it.
Where a scratch copy was used, its generated client was copied from the main checkout, and the same copy
typechecked green before any edit (`vue-tsc --noEmit` exit 0).

**#185, the seeders.**

| What | Locator | Count |
|---|---|---|
| Hand-written API calls (`post`/`get`/`tenant`/`fetch` lines) | `grep -cE "(post\|get\|tenant)\(.(GET\|POST\|PUT\|/api)\|fetch\(.\\$\{(BASE\|ADMIN_BASE)\}/api" web/scripts/*.mts` | bootstrap-user 13, seed-dev 7, seed-tenant 4, seed-owner 2 |
| The root primitive: `fetch` in a seeder (second angle; one of the 9 is Mailpit's `/api/v1/info`) | the S4 predicate below | 9 in 4 files |
| `node:http` transport | the S5 predicate below | 1 (`bootstrap-user.mts:23`) |
| Seeders under `typecheck` | `grep -n include web/tsconfig.json` | none: `["src", "vite.config.ts"]` |
| Seeders' typecheck once included (`extends` + `allowImportingTsExtensions`, since they import `./devSeed.mts`) | `tsc --noEmit -p <scratch>/tsconfig.scripts.json` | green, exit 0 — including them costs nothing by itself |

The row names three seeders. `seed-owner.mts` is a fourth with the same pattern: it POSTs `/api/setup/owner`
by hand. `devSeed.mts` and `extract-admin-i18n.mts` make no API calls.

**#195, the mock seam.** Every form below was matched by an independent grep or scan *and* by the ESLint
predicate the guard will use (run with a probe config over the untouched tree). The two agree exactly.

| Form | Locator (grep) | web | admin-web |
|---|---|---|---|
| S1: `vi.mocked(<x>Api).mockReturnValue(…)`, with its `Once` and `mockImplementation` variants — the seam itself | predicate S1 | **166 in 35 files** | 2 in 2 |
| S2: `… as unknown as ReturnType<typeof <x>Api>`, the row's count | `grep -rn "as unknown as ReturnType<typeof" <tree>/src \| wc -l` | **165 in 34 files** (all at S1 sites) | 1 |
| S1 sites cast `as never` instead | the S1 scan | 1 (`tenantInvite.test.ts`) | 1 (`tenants.test.ts`) |
| S3: a `vi.mock('…/api/client', factory)` export that is not a bare `vi.fn()` | predicate S3 | **8 exports in 5 files** (`changePassword`, `signOut`, `recovery`, two `TenantJoinView.*`) | 0 |
| All `api/client` mocks | `grep -rlE "vi\.mock\(['\"][^'\"]*api/client['\"]" <tree>/src \| wc -l` | 44 files | 2 |
| Stateful fakes for an `…Api`, found by name | `grep -rnoE "(function\|const) \w*[fF]ake\w*" web/src --include='*.ts'` | `fakeStore` (media.test.ts), `galleryFakeStore.ts`, 3× `fakeServer` | 0 |
| Fixtures double-cast to a generated model (the model list derived from the generated tree) | `M=$(ls web/src/api/generated/models web/src/api/generated/*/models \| grep -E '\.ts$' \| grep -v index \| sed 's/\.ts$//' \| sort -u \| paste -sd'\|' -); grep -rnE "as unknown as ($M)\b" web/src admin-web/src` | 3, all `AccountKeyMaterial` in `deviceKeyWrap.test.ts` (`MediaItem`'s casts are to a domain type in `media.ts`, not a model) | 0 |
| Already typed at the seam (`satisfies <Model>`) | `grep -rn "satisfies PublicShare" web/src` | 1: the #195 fixture | 0 |

`mediaApi` alone accounts for 77 of the 165 casts, and 71 of those hand over a fake's `.api`
(`grep -rnE "(store|fake)\w*\.api as unknown as ReturnType<typeof mediaApi>" web/src | wc -l`). So typing the
media fakes once removes most of that weight.

**What typechecks what.** *Measured* with `tsc` 6.0.3 and the repo's vitest types, on `SharingApi.resolveShare →
Promise<PublicShare>`. Probe files are in the refine's scratchpad. They are not committed.

| Mock form | stale extra field | missing required field |
|---|---|---|
| today: `{ m: vi.fn().mockResolvedValue(p) } as unknown as SharingApi` | silent | silent |
| cast removed, `Partial<SharingApiInterface>`, untyped `vi.fn()` | **silent** | **silent** |
| a helper demanding `Mock<I[K]>` per method, fed an untyped `vi.fn()` | **silent** | **silent** |
| typed `vi.fn<I['m']>().mockResolvedValue(p)` | TS2353 | TS2345 |
| `satisfies PublicShare` on the payload | TS2353 | TS1360 |
| a payload map `{ m: p }` typed `Payload<I[K]>`, with no function allowed | TS2353 | TS2739 (an untyped `vi.fn()` in it: TS2322) |
| a function `async () => p` with no return annotation | **silent** | TS2322 |
| a function with a return annotation `async (): Promise<PublicShare> => p` | TS2353 | TS2739 |
| a function map with a type that rejects an `any`-returning function, fed an untyped `vi.fn()` | TS2322 (`never`) | — |
| a typed mock after creation: `api.m.mockResolvedValueOnce(stale)` | TS2353 | — |

Two consequences shape the Decisions:
- **Removing the cast alone buys nothing.** The untyped `vi.fn()` is the real hole.
- **A literal loses its excess-property check** when it comes from an un-annotated function, or from a variable
  rather than written in place. So fakes and fixture builders annotate their return type with the model.

**The red-first demonstration — today nothing goes red** (*measured*, in a scratch copy). Two edits were made to
generated models: `TokenPair.refreshToken → rotatingToken`, and `LoginVerifyRequest.nonce → challengeNonce`.
The second one is the exact #185 class. Results:
- `vue-tsc` over `src`: **2 errors, both in production** (`app/sessionRefresh.ts:55`,
  `features/account/login/login.ts:31`), and **0 in test files**. `sessionRefresh.test.ts:148` still serves
  `{ accessToken: 'a2', refreshToken: 'r2' }` through the cast.
- `tsc` over all four seeders, even when included: **0 errors**. All three that log in still post `nonce`.

## Decisions

1. **One mock helper per surface**, in `src/test/` next to `httpError.ts`. It has two entry points, and it holds
   the only cast between a partial object and a generated `…Api` class:
   - **stub**: `(factory, { method: payload })`. Each value is the method's resolved model, typed so that an
     in-place literal gets both checks. No function is accepted here.
   - **fake**: `(factory, { method: impl })`, for behaviour: rejections, sequences, stateful fakes. Its type
     rejects a function whose return type is `any`, so an untyped `vi.fn()` cannot be passed in.

   Both return the mocks typed `Mock<…Api['method']>`, so a later `.mockResolvedValueOnce(x)` is checked too.
   The exact signatures are the implementer's. The rows of the table above are the properties they must keep,
   and phase 2's bites prove them.
2. **Fakes and fixture builders annotate their return type** with the generated model: `(): AccountKeyMaterial => …`,
   never `as unknown as AccountKeyMaterial`. That is what brings back the excess check the table says an un-annotated
   function drops.
3. **`api/client` is mocked with bare `vi.fn()` exports only.** Behaviour is set per test through the helper.
   The 5 factory files move their inline objects into the helper.
4. **The seeders call the generated clients** (question 2 (a)), built with a `Configuration` per base:
   `BASE`, `ADMIN_BASE`, and the tenant Host through one host-pinned `fetchApi` (question 3). That adapter is the
   only file under `web/scripts/` that may use `fetch` or `node:http`. The Mailpit reachability probe moves into
   `src/test/mailpit.ts`, which already owns every Mailpit call. Response reads come typed from the client, which
   removes the `json(r) as Promise<any>` helpers.
5. **The seeders join `npm run typecheck`** through a `tsconfig.scripts.json` that extends the app config and
   adds `allowImportingTsExtensions` (measured green above). The app's own compiler options stay untouched.
   `typecheck` becomes `vue-tsc --noEmit && vue-tsc --noEmit -p tsconfig.scripts.json`, or the equivalent.
6. **The guard is five lint predicates.** Each predicate below is the one run against the untouched tree:

   | id | files | predicate | red today |
   |---|---|---|---|
   | S1 | `src/**/*.test.ts` | `no-restricted-syntax`: `CallExpression[callee.property.name=/^mock(ReturnValue\|Implementation)(Once)?$/][callee.object.callee.object.name='vi'][callee.object.callee.property.name='mocked'][callee.object.arguments.0.name=/Api$/]` | 166 in 35 files |
   | S2 | `src/**/*.test.ts` | `no-restricted-syntax`: `TSAsExpression[typeAnnotation.typeName.name='ReturnType'][typeAnnotation.typeArguments.params.0.exprName.name=/Api$/]` | 165 in 34 files |
   | S3 | `src/**/*.test.ts` | `no-restricted-syntax`: `CallExpression[callee.object.name='vi'][callee.property.name='mock'][arguments.0.value=/api\/client$/] > ArrowFunctionExpression > ObjectExpression > Property > *.value:not(CallExpression[callee.object.name='vi'][callee.property.name='fn'][arguments.length=0])` | 8 in 5 files |
   | S4 | `scripts/**/*.mts`, except the adapter | `no-restricted-globals: fetch` | 9 in 4 files |
   | S5 | `scripts/**/*.mts`, except the adapter | `no-restricted-imports: node:http` | 1 |

   S1–S3 go into a config block named `veyq/api-mocks-typed-by-the-contract`, and S4–S5 into
   `veyq/seeders-use-the-generated-client`. Each block carries a *LIMIT:* comment, as the catalog rule does.
   S1–S3 also go into admin-web's config. S2 is redundant with S1 at every site today. It is kept because it also
   catches the cast where a mock reaches the code some other way than `vi.mocked` (DI, a fake passed as an
   argument). The helper module sits outside `src/**/*.test.ts`, which is how it keeps its one cast. **LIMIT, to
   write into the comment:** a factory whose return is not an in-line object literal (a variable, a `...spread`)
   escapes S3, and a helper-built payload held in a variable loses its excess check (Decision 2).
7. **The `no-explicit-any` exemption for `scripts/**/*.mts` stays.** `extract-admin-i18n.mts` needs it (3 uses,
   none API-facing). Once the seeders are migrated they need none, and the implementer says in the retro whether
   the exemption can narrow to that one file.

## Phases

Ordered guard → red → fix → green. Both defects are live, so each lint rule lands first and its red *is* the
bite and the work list.

- [ ] 1 seeders (#185)
      - **Red first.** Add `tsconfig.scripts.json` and wire it into `typecheck`: green (a no-op, as measured).
        Then add S4/S5 and record the red. The expected red is 9 `fetch` and 1 `node:http`, exactly the S4/S5
        counts.
      - **Live-defect bite, before migrating.** Rename `LoginVerifyRequest.nonce` in the generated model. `typecheck` is
        red only at `src/features/account/login/login.ts`, **not** at any seeder. Restore with
        `npm run generate:api`.
      - **Fix.** Write the host-pinned `fetchApi` adapter, move the Mailpit probe, and migrate the four seeders
        to the generated clients.
      - **Re-bite (injection, the same lever).** Rename the same field again. Now `typecheck` is red at the three
        seeders that log in (bootstrap-user, seed-tenant, seed-dev), and the red is `TS2353` on the verify body,
        not on some setup line. Restore. Then put one `fetch('/api/setup')` into a seeder: S4 red. Put the same
        call into the adapter: green, the positive control that the exemption is by file.
      · Done when: `npm run check` is green in `web/`, all bites were seen red, and the live proof below is done.
- [ ] 2 the web mock seam (#195)
      - **Red first.** Add S1–S3 and record the red: 166 / 165 / 8. Add the helper, and write
        `src/test/apiMock.test.ts` with `// @ts-expect-error` cases, one for each property in the table: a stale
        field in a stub payload; a missing required field; an untyped `vi.fn()` given to the fake entry; an unknown
        method name; a stale field through the returned `Mock`'s `mockResolvedValueOnce`. Also one un-expected
        positive per entry point. **Bite the helper by injection, one lever per entry point.** First widen the
        stub's payload type to `unknown`: the payload cases' `@ts-expect-error` lines turn into `TS2578` (unused
        directive), so `typecheck` goes red. Restore. Then drop the fake's `any`-rejection: the untyped-`vi.fn()`
        case turns into `TS2578`. Restore.
      - **Live-defect bite.** Rename `TokenPair.refreshToken` in the generated model. Before migration:
        `typecheck` red in `app/sessionRefresh.ts` only. Restore.
      - **Fix.** Migrate file by file. Type the two media fakes and the three `fakeServer`s once (Decision 2).
        Fix the 3 fixture casts. Every stale field or wrong shape the typing surfaces is a **finding**: fix it
        where the test's intent is clear, and ask the owner where it is not. The retro lists each one. A handful is
        *predicted*, not measured. #195's own miss says they exist.
      - **Re-bite.** Rename `TokenPair.refreshToken` again. `typecheck` is now also red at
        `sessionRefresh.test.ts`, at its `refreshSession` fakes. It is red through the **missing** required
        `rotatingToken`, not the stale `refreshToken`: those fakes are un-annotated functions, which the table
        says drop the excess check. Name which line reddens first. Restore.
      - **Account for the migration per assertion, not by count** (§ *a test migration is accounted for per
        assertion*). Compare `vitest run`'s test count before and after. `git diff --stat` touches only test files
        and `src/test/`. No `expect(` line is removed: `git diff -U0 -- '*.test.ts' | grep -c '^-.*expect('` is
        0, or each one is explained.
      · Done when: S1–S3 are green over the whole tree, `npm run check` is green, and all bites were seen red.
- [ ] 3 admin-web. The same helper as its own copy, S1–S3 in `admin-web/eslint.config.js`, and the 2 sites
      migrated. The bite: put back `vi.mocked(tenantsApi).mockReturnValue(api as never)` at `tenants.test.ts`,
      and S1 goes red.
      · Done when: `npm run check` is green in `admin-web/`.
- [ ] 4 durable state
      - Styleguide §3 gains one sentence: a dev seeder or script that calls the API is a client like any other and
        goes through the generated client.
      - Styleguide §10 gains the mock rule, next to `realI18n()`/`httpError`: *a mocked API response is typed by
        the generated model at the seam, through the one helper, and a lint rule keeps the cast out*. It carries
        the measured number that bought it (165 casts, and the `sealedDisplayName` miss), the way that section
        does for #171.
      - `web/CLAUDE.md`'s seeder paragraph gains the transport note (the Host-pinned `fetchApi`, and why).
      - Inbox #185 and #195 are marked done, and this CR is deleted after the harvest.
      · Done when: the trust-chain guards are green (`for f in scripts/validate_*.py; do python3 "$f"; done`).

## Definition of done

- Every phase is done, and every bite above was seen red **on the assertion it names**.
- `npm run generate:api && npm run check` is green in `web/` and `admin-web/`.
- The trust-chain guards are green.
- **The live proof.** The owner runs it, or the implementer does with the dev instance. Against a fresh
  instance (`down -v`, backend restarted), `npm run bootstrap:user` completes, both with and without the
  `VEYQ_USER2_*` inputs, and `npm run seed:dev` completes. The e2e suites do not cover the seeders: they birth
  through `e2eBootstrap.ts`.

**Not proven by the above, named:** a behaviour change in the contract that keeps the shape (a field that
changes meaning but not name or type). Only running the seeder against the backend catches that, and this CR
does not add such a run to CI.

## Retrospective
