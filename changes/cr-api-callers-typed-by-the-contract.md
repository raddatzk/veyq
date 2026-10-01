---
id: cr-api-callers-typed-by-the-contract
title: Code that talks to the API is typed by the generated contract — the dev seeders, the unit tests' API mocks, the feature-module mocks and the `as never` casts
status: planned
satisfies: []
adrs: []
invariants: [API-first]
---

## Open for the owner

**Answered by the owner, 2026-10-01: every recommendation below is taken, and implementation has the go.** Read each question's recommendation as its decision. Where a question proposed a separate inbox row, that row exists in `docs/inbox.md`.

**Questions 6–8 are open** (added 2026-09-28 by the refine of inbox #214, #215 and #216, which folds all three
into this CR as phases 0, 4 and 5). The CR is written for the recommendation in each.

6. **#216: what replaces the DB poke.** Both seeders run `docker exec … mariadb … UPDATE veyq_tenant SET
   domain=…` to give a tenant the domain `127.0.0.1`. It works around the contract, not a missing endpoint: the
   `domain` pattern (dotted labels ending in a letters-only TLD) refuses an IP literal on create *and* on
   `changeTenantDomain` (`grep -n "pattern: '\^(\[a-z0-9\]" contracts/api/tenants.openapi.yaml`), and the backend
   repeats that shape where a stored domain becomes proxy configuration, precisely to drop a row that "arrives by
   any path that skips" the boundary (`TenantIngressProjector.kt`, `isRoutableHostname`). The poke is that path.
   The need for an IP-literal tenant is gone: the dev proxy forwards the browser's Host and allows `*.localhost`
   (cr-0051), which is a valid domain that resolves to loopback, and both `bootstrap:user` (`.run/.env.example`)
   and the e2e harness (`e2eBootstrap.ts`) already use `veyq.localhost`. The options:
   (a) **retire `seed:dev`** and its two sub-seeders, and drop `bootstrap-user`'s numeric branch;
   (b) keep `seed:dev`, but create its tenant under a `*.localhost` domain and delete the remap;
   (c) give the API a path to an IP-literal domain, in production or in a dev profile only.
   **Recommendation: (a).** *Measured:* nothing reads what `seed:dev` produces. `loadDevSeed`/`vaultFromSeed`
   have no caller outside `web/scripts/devSeed.mts` (`git grep -nE "loadDevSeed|vaultFromSeed" -- web`), every
   e2e suite births through `e2eBootstrap.ts`, and no IntelliJ run config runs it (`ls .run/` holds
   `Bootstrap User` and no seed-dev entry). `e2eBootstrap.ts:10`'s "it still serves the local IntelliJ dev flow"
   is therefore wrong today. (a) also shrinks phase 1 from four seeders to one. (c) is rejected either way. A
   production path would put a tenant on a domain the ingress refuses to route. A dev-profile exception cannot be
   written in the contract, which owns the pattern (API-first). And it would exist only for seeding.
   **This question gates phase 1** as well as phase 0: under (a), phase 1 would otherwise migrate three seeders
   that phase 0 deletes. Phases 2 and 3 are independent of it.
7. **#214: how far the feature-module rule reaches.** The rule is: a `vi.mock` factory for a `features/` module
   exports bare `vi.fn()`, and behaviour is set per test through `vi.mocked(fn)`, which is typed by the real
   function. It needs no helper. A feature export is one function. Decision 1's helper exists for the `…Api` classes, where a
   partial literal misses 48 of the class's members. The scope choices: `features/` only (46 values in 15 files, see Inventory),
   or every relative module (213 values in 64 files). **Recommendation: `features/` only, with composables
   (`use*` keys) exempt.** `features/` is where the API calls live. A composable returns reactive UI state, not a
   model, and forcing it through `vi.mocked` would need a full `Ref`-shaped stub or the cast phase 5 removes.
   Widening it later is a one-regex change to the predicate.
8. **#215: ban `as never` in tests outright, or only at a payload.** 70 of the 109 casts hand a payload to a
   typed `vi.mocked(fn)` and switch its check off. The other 39 are third-party payloads (`exifr`), component
   props, fake nodes, `wrapper.vm` handles and rejection shapes. **Recommendation: outright** (`TSAsExpression >
   TSNeverKeyword` in every `*.test.ts`). The predicate is exact, 56 of the 109 casts are dead today (they come
   off with no error, measured below), and the few remaining legitimate casts become `as unknown as <T>`. That
   form names its target, and a reader can grep for it.

**Questions 1–5 were answered by the owner, 2026-09-28: every recommendation below is taken, and implementation has the go.** Read each question's recommendation as its decision. Where a question proposed a separate inbox row, that row exists (#197–#221, `docs/inbox.md`). Question 1's "the seam only" held for #195's own scope. The follow-up it proposed is #214/#215, which this refine folds back in as phases 4 and 5. The helper and seam decisions are unchanged by that.

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

- **The feature-module mocks** (#214). A test that mocks `features/…` with
  `vi.mock(path, () => ({ fn: vi.fn().mockResolvedValue(payload) }))` checks the payload against nothing: the
  string form of `vi.mock` types its factory as `Partial<unknown>`. *Measured* (Inventory): handed to
  `vi.mocked(fn)` unchanged, 11 of today's 21 factory literals go red, and three of them name a field that does
  not exist. admin-web's CLI test resolves `resendInvite` and `reassignInvite` with `{ state: 'ACTIVE' }`, a
  field that `TenantInviteState` does not have.
- **The `as never` casts** (#215). `vi.mocked(fn).mockResolvedValue(x as never)` takes the typed seam and switches
  it off. 109 of them sit in test files, and none in production code.
- **The dev seeders' DB poke** (#216). `seed-dev.mts` and `bootstrap-user.mts` reach past the API into the
  database to give a tenant a domain the contract refuses. That breaks API-first at the root, and it is the one
  path the ingress projector was written to distrust.

After this CR, all of these callers are typed by the generated client or by the real function it wraps. The next
rename or removal fails `typecheck` at the seeder or the mock that still uses the old shape. Lint rules keep the
hand-written forms from coming back, and no seeder writes to the database.

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
- **Out of scope:** production code, and module mocks outside `features/` and `api/client` (question 7).
  Production is already clean:
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

**#216, the DB poke.** *Measured* on 2026-09-28 against `64c1f65`.

| What | Locator | Finding |
|---|---|---|
| The poke | `grep -nE "UPDATE veyq_tenant" web/scripts/*.mts` | `seed-dev.mts:58` (always, since its default domain is `127.0.0.1`), `bootstrap-user.mts:164` (only when `VEYQ_TENANT_DOMAIN` is all digits and dots) |
| What it works around | `grep -n "pattern: '\^(\[a-z0-9\]" contracts/api/tenants.openapi.yaml` | the hostname pattern, 3 sites: `CreateTenantRequest.domain`, `ChangeDomainRequest.domain` (and `TenantMailSettingsUpdate.host`). An IP literal is refused, so `changeTenantDomain` on the admin channel cannot do it either |
| The backend's own copy of that rule | `grep -n "HOSTNAME = Regex" backend/src/main/kotlin/com/veyq/app/shared/tenant/TenantIngressProjector.kt` | the same pattern, re-checked because a row that "arrives by any path that skips" the boundary must not reach a proxy |
| Readers of `seed:dev`'s output (`web/scripts/.seed/dev-seed.json`) | `git grep -nE "loadDevSeed\|vaultFromSeed" -- web` | 4 hits: the two definitions in `devSeed.mts`, a comment in `seed-dev.mts`, and `web/CLAUDE.md:59`. **No caller** |
| e2e suites on the seed file | `grep -rnE "devSeed\|dev-seed" web/src --include='*.ts'` | 1 hit, `e2eBootstrap.ts:5`, a comment that records the move away from it |
| IntelliJ run configs that run `seed:dev` | `ls .run/` | none. `Bootstrap User` runs `bootstrap:user` |
| Who else invokes `seed-owner.mts` / `seed-tenant.mts` | `git grep -nE "seed-(owner\|tenant)" -- . ':!changes' ':!docs/inbox.md'` | only `seed-dev.mts` and the two files' own headers |
| Docs that describe the `127.0.0.1` tenant as the dev default | `grep -nE "127\.0\.0\.1" web/CLAUDE.md infra/README.md` | `web/CLAUDE.md` § seed:dev, § Run the Memory shell locally, the cr-0051 footgun ("the canonical dev URL stays `127.0.0.1:5173`"); `infra/README.md:738-756` |
| `devSeed.mts` exports that `bootstrap-user` still needs | `grep -n "from './devSeed.mts'" web/scripts/*.mts` | `awaitInvite` only |

The poke therefore works around a **dev-only state that no longer exists**, not a missing API. `*.localhost` is a
valid domain under the contract's pattern and resolves to loopback (RFC 6761). The dev proxy forwards the
browser's Host (`vite.config.ts`, `changeOrigin: false`, `allowedHosts: ['.localhost']`), and the local instance
is already browsed at `veyq.localhost:<port>`.

**#214, the feature-module mocks.** *Measured* with a probe ESLint config (the `typescript-eslint` parser and
`no-restricted-syntax` only) over a copy of the untouched `web/src` and `admin-web/src`. The S3 predicate
reproduces the Inventory's 8 in 5 files on the same copy, which is the control.

| What | Locator | web | admin-web |
|---|---|---|---|
| Test files that mock a `features/` module | `grep -rlE "vi\.mock\(['\"][^'\"]*features/" <tree>/src --include='*.test.ts' \| wc -l` | 19 | 4 |
| Factory values that are not a bare `vi.fn()` (`.vue` stubs included) | the S6 predicate with no `.vue` or `use*` exclusion | 48 in 11 files | 18 in 4 |
| S6 as it will ship: a function or call value that is not a bare `vi.fn()`, in a non-`.vue` `features/` mock, `use*` keys exempt | predicate S6 | **33 in 11 files** | **13 in 4** |
| The same over every relative module (question 7's wider option) | S6 with `/^\.\.?\//` for the path | 183 in 58 files | 30 in 6 |
| The typed `vi.mock(import('…'), …)` form in use | `grep -rnE "vi\.mock\(import\(" <tree>/src --include='*.test.ts' \| wc -l` | 0 | 0 |

**What typechecks a feature mock.** *Measured* with `tsc` 6.0.3 and vitest 4.1.9, on a probe module whose function
resolves a model. Probe files are in the refine's scratchpad. They are not committed.

| Form | stale extra field | missing required field |
|---|---|---|
| today: `vi.mock('./m', () => ({ f: vi.fn().mockResolvedValue(p) }))` | silent | silent |
| typed import form, same `vi.fn()` chain: `vi.mock(import('./m'), …)` | **silent** | **silent** |
| typed import form, arrow fake `f: async () => p` | silent | TS2769 |
| typed import form, a misspelt export name | TS2769 | — |
| `vi.mocked(f).mockResolvedValue(p)`, literal in place | TS2353 | TS2345 |
| `vi.mocked(f).mockResolvedValue(p as never)` | silent | silent |
| `vi.mocked(f).mockResolvedValue(v)`, where `v` is a variable | silent | TS2345 |

So the typed `import()` form alone buys nothing against the `vi.fn()` chain, which is the dominant form. Only
`vi.mocked(f)` with the literal in place gets both checks. That is Decision 8.

**The red-first demonstration for #214** (*measured*, in a scratch copy with the generated clients from the main
checkout; both trees typecheck green before the probe). Every factory literal that stands in for a feature
function is handed to `vi.mocked(fn)` unchanged:
- web, 15 literals: **6 red.** `getMyProfile`'s `{ username, sealedName }` lacks `MemberProfile.requireGroupAddConsent`.
  `listMyGroupInvitations`'s rows lack `GroupInvitation.nameEpoch`. `acceptInvite`/`registerAndAccept` resolve
  `{}`, not `TenantActivated`/`TenantActivatedWithUser`. `bornTenantScope` returns `{ grant, … }`, but the real
  function returns `{ birth, scopeRootKey }` (TS2353, a stale field). `sealName` resolves `undefined`, not a `string`.
- admin-web, 6 literals: **5 red.** `createTenant` lacks 3 `TenantCreated` fields, and `listTenants`' row lacks 4
  `TenantSummary` fields. `resendInvite` and `reassignInvite` resolve `{ state: 'ACTIVE' }`, and **`state` does not
  exist on `TenantInviteState`** (TS2353). That is the #195 class, live today. `registerServerOwner` lacks `userId`.

The main checkout's admin-web client was stale (generated 2026-09-21: 13 errors in production code). The probe
used web's same-spec `setup`/`auth`/`tenants` output instead, and the copy was green before the probe.

**#215, the `as never` casts.**

| What | Locator | web | admin-web |
|---|---|---|---|
| All `as never` in test files (the row's count) | `grep -rnE "\bas never\b" <tree>/src --include='*.test.ts' \| wc -l` | 84 in 19 files | 25 in 3 |
| The S7 predicate `TSAsExpression > TSNeverKeyword` | probe ESLint | 84 | 25 |
| Outside test files | `grep -rnE "\bas never\b" web/src admin-web/src web/scripts --include='*.ts' --include='*.vue' --include='*.mts' \| grep -v '\.test\.ts' \| grep -v generated` | 0 | 0 |

By context (*measured* by reading each site; the list is from the grep above):

| Context | Count | Example |
|---|---|---|
| a payload handed to a typed `vi.mocked(<feature fn>)` | 70 | `vi.mocked(getSetupStatus).mockResolvedValue({ setupRequired: false } as never)` |
| a third-party payload (`exifr.parse`) | 24 | `media.test.ts`, `MediaLightbox.test.ts`, `MemoryDetail.test.ts` |
| the `…Api` seam itself (already phase 2/3 work) | 2 | `tenantInvite.test.ts:18`, `tenants.test.ts:22` |
| component props or mount options | 4 | `NodeMutationDialog.test.ts:42` |
| a fake node, a function argument, a gallery page | 4 | `tenantInvite.test.ts:37`, `useMediaTrash.test.ts:33` |
| `wrapper.vm as never as …` | 2 | `useMediaGalleryLive.test.ts:68`, `useMediaTrash.test.ts:45` |
| a rejection shape `{ response: { status } }` | 3 | `TenantDetailPanel.test.ts:245/307/315` (the `httpError` helper exists for this) |

**What each cast hides** (*measured*: every ` as never` not followed by ` as` was stripped in a scratch copy,
and the tree typechecked). There is one cast per line, so errors map to casts one-to-one
(`grep -rnE "as never.*as never" … | grep -v "as never as"` → 0).
- web: 82 stripped → **36 errors in 16 files**, so **46 casts are dead**, all 24 `exifr` casts among them. The
  36 are missing required fields, in generated models (`SetupStatus`, `MemberProfile`, `ScopeMember`, `MyTenant`,
  `InvitePreview`, `MemberInviteState`) and in domain types built from them (`media.ts`'s `MediaItem`, `tree.ts`'s
  `TreeNode`); literal types widened in a variable-held fixture
  (`configSecretState`, `kind`, `role`, `suiteId`: 8 of them in `TenantMailSettingsPanel.test.ts` alone); one
  component mounted without its required prop `mode` (`NodeMutationDialog.test.ts:42`, a **finding**); and the
  `…Api` seam.
- admin-web: 25 stripped → **15 errors**, so **10 are dead**, among them all 5 in `AdminConsoleView.test.ts`. 14 of
  the 15 are one cause: `TenantDetailPanel.test.ts`'s `PENDING`/`ACTIVE`/`SUSPENDED` fixtures widen `status` to
  `string`. Annotating them with `TenantSummary` (Decision 2) clears all 14. The 15th is the seam.
- No stripped cast hid a **stale** field today (TS2353: 0). The casts hide missing fields and widening. That is
  the reason to remove them: the next stale field would hide the same way.

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
6. **The guard is eight lint predicates.** Each predicate below is the one run against the untouched tree. S6
   and S7 were run by the #214/#215 refine, with the same probe method:

   | id | files | predicate | red today |
   |---|---|---|---|
   | S1 | `src/**/*.test.ts` | `no-restricted-syntax`: `CallExpression[callee.property.name=/^mock(ReturnValue\|Implementation)(Once)?$/][callee.object.callee.object.name='vi'][callee.object.callee.property.name='mocked'][callee.object.arguments.0.name=/Api$/]` | 166 in 35 files |
   | S2 | `src/**/*.test.ts` | `no-restricted-syntax`: `TSAsExpression[typeAnnotation.typeName.name='ReturnType'][typeAnnotation.typeArguments.params.0.exprName.name=/Api$/]` | 165 in 34 files |
   | S3 | `src/**/*.test.ts` | `no-restricted-syntax`: `CallExpression[callee.object.name='vi'][callee.property.name='mock'][arguments.0.value=/api\/client$/] > ArrowFunctionExpression > ObjectExpression > Property > *.value:not(CallExpression[callee.object.name='vi'][callee.property.name='fn'][arguments.length=0])` | 8 in 5 files |
   | S4 | `scripts/**/*.mts`, except the adapter | `no-restricted-globals: fetch` | 9 in 4 files |
   | S5 | `scripts/**/*.mts`, except the adapter | `no-restricted-imports: node:http` | 1 |
   | S6 | `src/**/*.test.ts` | `no-restricted-syntax`, two selectors, the arrow body and the `return` of a block body: `CallExpression[callee.object.name='vi'][callee.property.name='mock'][arguments.0.value=/features\/(?!.*\.vue$)/] > :function > ObjectExpression.body > Property:not([key.name=/^use[A-Z]/]) > .value:matches(:function, CallExpression):not(<bare vi.fn()>)`, and the same with `> :function > BlockStatement > ReturnStatement > ObjectExpression` | 33 in 11 files (admin-web: 13 in 4) |
   | S7 | `src/**/*.test.ts` | `no-restricted-syntax`: `TSAsExpression > TSNeverKeyword` | 84 in 19 files (admin-web: 25 in 3) |
   | S8 | `scripts/**/*.mts` | `no-restricted-imports: node:child_process` (no seeder shells out, so none can `docker exec`) | 2 files (`grep -ln child_process web/scripts/*`: `bootstrap-user.mts`, `seed-dev.mts`) |

   Under question 6 (a), phase 0 deletes three of the four seeders first, so S4's red at phase 1 is **3 `fetch` in
   `bootstrap-user.mts`** (`grep -nE "fetch\(" web/scripts/bootstrap-user.mts`: lines 66, 93, 213), and S5's is
   unchanged.

   S1–S3, S6 and S7 go into a config block named `veyq/api-mocks-typed-by-the-contract`, and S4, S5 and S8 into
   `veyq/seeders-use-the-generated-client`. Each block carries a *LIMIT:* comment, as the catalog rule does.
   S1–S3, S6 and S7 also go into admin-web's config. S2 is redundant with S1 at every site today. It is kept because it also
   catches the cast where a mock reaches the code some other way than `vi.mocked` (DI, a fake passed as an
   argument). The helper module sits outside `src/**/*.test.ts`, which is how it keeps its one cast. **LIMIT, to
   write into the comment:** a factory whose return is not an in-line object literal (a variable, a `...spread`)
   escapes S3, and a helper-built payload held in a variable loses its excess check (Decision 2).
7. **The `no-explicit-any` exemption for `scripts/**/*.mts` stays.** `extract-admin-i18n.mts` needs it (3 uses,
   none API-facing). Once the seeders are migrated they need none, and the implementer says in the retro whether
   the exemption can narrow to that one file.
8. **A `features/` module is mocked with bare `vi.fn()` exports, and behaviour goes through `vi.mocked(fn)`**
   (question 7). This is Decision 3 applied to the feature seam, without a helper: `vi.mocked(fn)` is already typed
   by the real function, so a payload literal written in place gets both checks (Inventory). Three forms stay
   allowed in a factory: a spread of `importOriginal()`, a member of it (a real function passed through), and a
   non-function value (a constant, a getter object). A composable (`use*`) keeps its hand-written stub. S6 goes
   into `veyq/api-mocks-typed-by-the-contract` next to S3, in both trees. **LIMIT, to write into the comment:**
   a module-level `vi.fn()` that a test drives directly (`resolveShareMock` in the three `ShareView.*` tests) is
   caught only where the factory forwards to it. The fix is `vi.mocked(resolveShare)`, not a typed copy of the
   mock. A composable stub is exempt by name, so a `use*` export that resolves a model escapes.
9. **A payload that needs a whole model comes from a fixture builder** annotated with that model (Decision 2).
   The builder takes overrides, so `{ ...memberProfile(), username: 'owner' }` replaces
   `{ username: 'owner' } as never`. A builder used by one file stays in that file. One used by two or more moves
   to `src/test/`, per styleguide §10's one fixture per surface.
10. **`as never` is banned in test files** (question 8, S7). The dead casts are deleted. The live ones are fixed
    through Decision 9, or by annotating the variable-held fixture (the 14 `TenantDetailPanel` errors, the 8
    `configSecretState` widenings). A rejection shape uses `httpError` (§10, #171). A cast that remains necessary,
    for example a `wrapper.vm` handle or a deliberately malformed input, is written `as unknown as <T>`, which
    names what it claims. **LIMIT, to write into the comment:** S7 does not see `as unknown as <Model>`, and
    nothing forbids that form. Decision 2 is the rule against it. The Inventory's model-cast grep is the way to
    measure it, and it counted 3.
11. **No seeder writes to the database** (question 6 (a)). `seed-dev.mts`, `seed-tenant.mts` and `seed-owner.mts`
    are deleted, together with `devSeed.mts`'s seed-file half (`readSeed`, `writeOwner`, `writeTenant`,
    `removeTenant`, `loadDevSeed`, `vaultFromSeed`, `materializeKeys`, `seedFilePath`), the `seed:dev` script and
    its `.gitignore` line. `awaitInvite`, the one export `bootstrap-user.mts` uses, stays. `bootstrap-user.mts`
    loses its numeric branch, its `DB_CONTAINER` and its `node:child_process` import. Instead it refuses an
    all-digits `VEYQ_TENANT_DOMAIN` **before** the owner birth, with a pointer to `*.localhost`. It must refuse
    before the birth, because a refusal from `POST /api/tenants` would come after the owner exists and before
    step 4a persists its key. That check is the only copy of the pattern the seeder keeps. Every other shape
    error is the contract's 400. The docs that present `127.0.0.1` as the dev tenant move to `veyq.localhost`.

## Phases

Ordered guard → red → fix → green. Every defect is live, so each lint rule lands first and its red *is* the
bite and the work list. Phase 0 and phase 1 wait for question 6. Phases 2 and 3 do not. Phases 4 and 5 wait
for questions 7 and 8.

- [ ] 0 no seeder writes to the database (#216, Decision 11)
      - **Red first.** Add S8 to `web/eslint.config.js`: red at 2 files, `bootstrap-user.mts` and `seed-dev.mts`.
      - **Fix.** Delete `seed-dev.mts`, `seed-tenant.mts`, `seed-owner.mts` and `devSeed.mts`'s seed-file half,
        and the `seed:dev` script with its `web/.gitignore` line. Drop `bootstrap-user.mts`'s numeric branch and
        add the pre-birth refusal of an all-digits domain. Rewrite the docs that name the `127.0.0.1` tenant or
        `seed:dev`: `web/CLAUDE.md` (the seed:dev bullet, the footgun that sets the two seeders against each
        other, § Run the Memory shell locally, the cr-0051 footgun), `infra/README.md:738-756`, and
        `e2eBootstrap.ts`'s header line 10. While in that paragraph, fix `web/CLAUDE.md:45`'s "it … needs no
        dev-invite file". *Measured* false: `bootstrap-user.mts` calls `awaitInvite(INVITE_FILE, …)`, which polls
        that file and throws after 15 s (`devSeed.mts:101`). It works only because `.run/Run Backend.run.xml:43`
        sets `VEYQ_TENANT_INVITE_DEV_FILE`. ADR 0048:71 is history and is left as written. The coverage comment
        at `uc.admin.back-up-and-restore-instance.yaml:72` names `web/scripts/devSeed.mts`. The file survives
        (it keeps `awaitInvite`), so the comment still resolves. Re-read it anyway.
      - **Blast radius, measured before deleting.** `git grep -nE "seed:dev|seed-dev|seed-tenant|seed-owner|dev-seed|loadDevSeed|vaultFromSeed|materializeKeys|127\.0\.0\.1:5173" -- . ':!changes'`.
        Every hit is either rewritten or named in the retro as deliberately kept.
      - **Bite.** Put `import { execFileSync } from 'node:child_process'` back into `bootstrap-user.mts`: S8 red.
        Remove it. Then, in a worktree (whose gitignored `.run/.env` is its own, never the developer's), set
        `VEYQ_TENANT_DOMAIN=127.0.0.1` and run the seeder against no backend. It must exit on the domain refusal **before** it waits for the backend or births anyone,
        which shows the check sits ahead of the birth.
      · Done when: S8 is green, `npm run check` is green in `web/`, the blast-radius grep is accounted for, and
        the bite was seen.
- [ ] 1 seeders (#185)
      - **Red first.** Add `tsconfig.scripts.json` and wire it into `typecheck`: green (a no-op, as measured).
        Then add S4/S5 and record the red. After phase 0 the expected red is 3 `fetch` and 1 `node:http`, all in
        `bootstrap-user.mts`. (If question 6 is answered (b) or (c), it is the original 9 `fetch` in 4 files.)
      - **Live-defect bite, before migrating.** Rename `LoginVerifyRequest.nonce` in the generated model. `typecheck` is
        red only at `src/features/account/login/login.ts`, **not** at any seeder. Restore with
        `npm run generate:api`.
      - **Fix.** Write the host-pinned `fetchApi` adapter, move the Mailpit probe, and migrate the seeders that
        remain after phase 0 (one under question 6 (a), four otherwise) to the generated clients.
      - **Re-bite (injection, the same lever).** Rename the same field again. Now `typecheck` is red at every
        remaining seeder that logs in (`bootstrap-user`; also `seed-tenant` and `seed-dev` if they survive phase 0), and the red is `TS2353` on the verify body,
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
- [ ] 4 the feature-module mocks (#214, Decision 8), web then admin-web
      - **Red first.** Add S6 to both configs and record the red: 33 in 11 files / 13 in 4.
      - **Live-defect bite, before migrating.** In admin-web, rename a required field of the generated
        `TenantInviteState` (`admin-web/src/api/generated/tenants/models/`). `typecheck` is red in production
        code only. `cli.test.ts:15-16` keep resolving `{ state: 'ACTIVE' }`, and nothing there turns red. Restore
        with `npm run generate:api`.
      - **Fix.** Every flagged value becomes a bare `vi.fn()` in the factory, and its behaviour moves to
        `vi.mocked(fn)` in the test or its `beforeEach`. The three `ShareView.*` forwarders become
        `vi.mocked(resolveShare)` / `vi.mocked(fetchShareTerms)`, and their module-level `vi.fn()`s are deleted.
        The typing surfaces **findings**, 11 of them measured in the Inventory's red-first demonstration. Among
        them are `{ state: 'ACTIVE' }` (no such field) and `bornTenantScope`'s `{ grant }` (the real return is
        `{ birth, scopeRootKey }`). Each is fixed where the test's intent is clear and put to the owner where it
        is not, and the retro lists them.
      - **Re-bite (the same lever).** Rename the same `TenantInviteState` field again. `typecheck` is now also red
        at `cli.test.ts`'s `resendInvite`/`reassignInvite` payloads. Restore. Then put
        `resendInvite: vi.fn().mockResolvedValue({})` back into the factory: S6 red. Change the key to
        `useResend`: S6 green. That is the positive control for the composable exemption, and it is also the
        LIMIT, seen.
      - **Account per assertion**, as phase 2 does (the test count before and after, no `expect(` line removed).
      · Done when: S6 is green in both trees, `npm run check` is green in both, and all bites were seen red.
- [ ] 5 the `as never` casts (#215, Decisions 9–10), web then admin-web
      - **Red first.** Add S7 to both configs and record the red: 84 / 25. The two `…Api`-seam casts are gone
        by now (phases 2 and 3), so the expected red is 83 / 24. Say so if it is not.
      - **Live-defect bite, before migrating.** Rename a required field of the generated `SetupStatus`.
        `typecheck` is red in production code only. `tenantSuspension.test.ts:27` and `activeSpace.test.ts:230`
        keep resolving `{ setupRequired: false } as never` without an error. Restore.
      - **Fix.** Delete the dead casts first (46 in web, 10 in admin-web, measured). The Inventory's scratch
        strip is the method: remove every cast, typecheck, and whatever stays green was dead. Then fix the live
        ones: annotate the variable-held fixtures, add model builders (Decision 9), move the 3 rejection shapes
        to `httpError`, and turn the 2 `wrapper.vm` handles into `as unknown as <T>`. `NodeMutationDialog.test.ts:42`
        mounts without its required `mode` prop, a **finding**. Give it the mode the test means, or ask the owner.
      - **Re-bite.** Rename the `SetupStatus` field again. `typecheck` is now red at the builder or the in-place
        literal those two tests use. Restore. Then put `as never` back on one payload: S7 red.
      - **Account per assertion**, as in phase 2.
      · Done when: `grep -rnE "\bas never\b" web/src admin-web/src --include='*.test.ts' | wc -l` is 0, S7 is
        green in both trees, `npm run check` is green in both, and all bites were seen red.
- [ ] 6 durable state
      - Styleguide §3 gains one sentence: a dev seeder or script that calls the API is a client like any other and
        goes through the generated client.
      - Styleguide §10 gains the mock rule, next to `realI18n()`/`httpError`: *a mocked API response is typed by
        the generated model at the seam, through the one helper, and a lint rule keeps the cast out*. It carries
        the measured number that bought it (165 casts, and the `sealedDisplayName` miss), the way that section
        does for #171. The same paragraph names the feature-seam form (bare `vi.fn()` in the factory,
        `vi.mocked(fn)` for behaviour, composables exempt) and the ban on `as never` with its `as unknown as <T>`
        escape. Each carries the number that bought it: the 11 of 21 literals, the `{ state }` miss, and the 56
        dead casts.
      - `web/CLAUDE.md`'s seeder paragraph gains the transport note (the Host-pinned `fetchApi`, and why). It also
        gains the reason the dev tenant is a `*.localhost` domain and never an IP literal: the contract's pattern,
        and no seeder writes to the database.
      - Inbox #185, #195, #214, #215 and #216 are marked done, and this CR is deleted after the harvest.
      · Done when: the trust-chain guards are green (`for f in scripts/validate_*.py; do python3 "$f"; done`).

## Definition of done

- Every phase is done, and every bite above was seen red **on the assertion it names**.
- `npm run generate:api && npm run check` is green in `web/` and `admin-web/`.
- The trust-chain guards are green.
- **The live proof.** The owner runs it, or the implementer does with the dev instance. Against a fresh
  instance (`down -v`, backend restarted), `npm run bootstrap:user` completes, both with and without the
  `VEYQ_USER2_*` inputs. Under question 6 (b) or (c), `npm run seed:dev` also completes. After the run,
  `veyq.localhost:<viteport>/?devlogin` reaches the unlocked app. The e2e suites do not cover the seeders: they
  birth through `e2eBootstrap.ts`.

**Not proven by the above, named:** a behaviour change in the contract that keeps the shape (a field that
changes meaning but not name or type). Only running the seeder against the backend catches that, and this CR
does not add such a run to CI.

## Retrospective
