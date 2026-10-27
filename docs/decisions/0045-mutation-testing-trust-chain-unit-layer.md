# ADR 0045 — Mutation testing is the trust chain's last link, measured on the unit layer

- Status: Accepted
- Built: yes
- Date: 2026-07-16

## Context

The acceptance-coverage trust chain runs `feature → use-case → criteria → tests`, and
[`validate_coverage.py`](../coverage/README.md) proves every criterion has ≥1 test and every ref **resolves**.
It does not — cannot — prove the test *proves* anything. The chain is complete to its last link and has no
oracle there: `assertThat(sealed).isNotNull()` is green, its ref resolves, [ADR 0040](0040-coverage-gaps-and-http-not-a-surface.md)'s
"a ref only counts when CI runs it" is satisfied, and `fun seal(k, s) = s` would pass all of it. That is the
measured agent pathology — many tests, weak assertions, coverage up and fault-detection flat.

This matters here for a specific reason, not a general one. veyq is built with **minimal manual review**;
the assertion-strength link is load-bearing precisely because no human reads each test for its oracle. This is
not defect-catching in the abstract — no controlled study shows mutation gates reduce escaped defects — it is
closing the one link the trust chain leaves unmeasured, and standing in for the reviewer the project does not
have. That directly answers [`ai/README.md`](../ai/README.md) rule 3 (*"no guard unless a human would notice
the defect"*): here nothing notices, which is the whole point.

**Measured (2026-07-16), and the measurement reshaped the decision:**

| Surface | Finding |
|---|---|
| **web crypto** (`v1.ts`, 361 LOC) | Already pinned by a **known-answer battery** — `v1.conformance.test.ts` asserts ~20 exact output bytes against `contracts/crypto/vectors/v1.json`. An identity mutant (`aeadSeal=pt`, `symWrap(k,x)=x`) dies by construction: the pinned `sym_master_key.blob` begins `02‖nonce‖tag`, never the plaintext. The place #99 feared weakest is the counter-example. A KAT is strictly stronger than mutation for what it covers. |
| **backend** | Performs **no content crypto** (hard invariant, [`src/backend/CLAUDE.md`](../../src/backend/CLAUDE.md)) — only SHA-256 token verifiers, Ed25519 verify, CSPRNG. The security-critical logic is authz/share/scope/session **decisions**, already covered by strong `@QuarkusTest` slices (44 slice classes vs. 4 boot-free unit files). The mutation cost driver there is the **Quarkus boot per covering test**, not the mutant count (the decision logic is ~85 LOC). |

No mutation tooling exists (web = vitest; backend = Gradle/Quarkus/Kotlin). This is greenfield.

## Decision

**Mutation score is the trust chain's measured last link — it verifies assertion strength, which
`validate_coverage.py` cannot. Adopted per surface, on the unit layer, not repo-wide, not on the integration
layer.**

1. **Two tools, both vendor-neutral OSS.** Web: **StrykerJS** (vitest runner). Backend: **PIT**
   (`info.solidsoft.pitest`) **alone — no Kotlin mutator plugin.** The OSS `pitest/pitest-kotlin` (the filter for
   Kotlin's null-check-intrinsic noise) was **archived read-only in 2023**, never left `0.1-SNAPSHOT`, and names the
   commercial **arcmutate** its successor — barred twice: unmaintained *and* successor-closed (CLAUDE.md's
   no-unmaintained-deps + *self-hostable by default*). It is not needed anyway, and that is **measured, not hoped**:
   base PIT over the **non-null** policy types of §2's unit tier emits **zero** intrinsic-null-check mutants (on
   rotate/consent: 3 on DEFAULTS, 5 on STRONGER, all killed, none NON_VIABLE). The noise the plugin filters is a
   property of null-heavy *wiring* — which the pure tier excludes by construction — so the discipline §2 adopts for
   boot-freeness **also** disposes of the dead plugin. **arcmutate**, now the only living Kotlin mutator (git-native
   for Maven and Gradle), stays excluded as commercial and **unneeded**. The boot cost is a property of the *tests*,
   not the engine, so no tool switch changes it.

2. **Mutation measures decisions on the unit layer — never wiring on the integration layer.** An
   integration-level mutant is killed as often for a wiring reason (a boot-path 500) as for a pinned assertion;
   it conflates *"the endpoint is wired"* with *"the assertion is strong"* — the exact signal the trust-chain
   claim rests on — and pays the Quarkus boot per mutant. So the backend gets a **three-layer split**:

   | layer | proves | test | mutated? |
   |---|---|---|---|
   | **Repository** (dumb data access) | the query returns the *right* rows — a security boundary | real-DB test | **no** — no logic to mutate |
   | **Policy** (pure decision over returned data, mocked repository) | the decision is correct given those rows | unit | **yes** |
   | **Slice** (`@QuarkusTest`) | ordering, existence-leak, the gate precedes the probe | integration | no |

   This requires **disentangling the fusion points** where decision logic leaked *down* into store methods that
   also write — `ScopesStore.rotate` (skip `pendingConsent`, re-grant to the remaining members) is the archetype;
   `TenantHandler`'s cascade already orchestrates *above* the stores and is closer to the target. Scoped to the
   **rich** decisions (rotate/consent, last-owner `≤1` cascade, epoch, gate ordering, rate-limit arithmetic,
   **and the share-access gates `canView`/`canUpload` — the who-may-see-what capability matrix**). The
   discriminator is **redundancy and blast-radius, not line count**: `isScopeOwner` is a one-line `role == OWNER`
   whose mutant is caught loudly and redundantly across many tests — lifting it buys nothing. `canView`/`canUpload`
   are *also* one-liners, but each is a three-role capability matrix whose single flipped `||`-arm silently admits
   a write-only WRITE_ONLY share into the sealed gallery — a **confidentiality-invariant** breach pinned today by exactly
   one slice, and slices are deliberately un-mutated (this §). A one-liner whose decay silently breaks a hard
   invariant under a single un-mutated oracle is **in**; one whose decay is loud and redundantly caught is **out**.
   Feature-by-feature, backed by the green slices as the refactor's safety net.

   **Query correctness stays out of mutation's reach on purpose.** A wrong `WHERE` clause returns wrong rows and
   is a security failure, but a mutant in a query string is invisible to a mocked unit test — so it stays covered
   by a real-DB repository test, not mutated.

3. **Crypto/logic-first pilot, deliberately widened — not repo-wide.** A repo-wide gate is a CI tax that gets
   resented and switched off; the value concentrates where **no other oracle exists** (the un-KAT-able decision
   logic), not on the KAT-pinned primitives, where mutation only confirms what the vectors already prove. The
   pilot hardens the tool at the strong web surface first; the backend refactor is where the trust-chain value
   actually lands. Widening past the pilot is a deliberate later step, not a forbidden one.

   **And the widening is a curated logic tier, never a directory sweep — the frontend layers exactly as §2's
   backend does.** Its **file-pure** `.ts` modules — pure at *file* granularity, because Stryker mutates whole
   files — are the mutate glob (the crypto ring, `epoch`/`naming`/`buckets` computation, the cold master-key
   birth and the node-meta seal). It is **not the memory/tasks layer wholesale**: most of that layer is wiring —
   `memoryLocation`/`memoryDate` import `contentApi`, the task workers import the network client — so a module
   joins only when it imports no I/O. A network-importing worker that *happens* to score 100% under full mocks is
   excluded on the same principle: its green is brittle placement, not earned assertion strength — the boundary is
   purity, not score. Its `.vue` components, `views/`, `components/` and side-effect stores are the
   rendering-and-wiring layer — the frontend's equivalent of §2's repository + slice — and are **excluded**. A mutant there dies for a render/wiring reason or survives as an
   equivalent, producing the noisy score this section exists to avoid; a `.vue` score reads high for brittle DOM
   snapshots and low for equivalents, so it measures neither trust nor its absence. `src/** minus *.vue` is **not**
   the boundary — it re-admits the wiring. The same forcing function as the backend holds: logic worth mutating is
   lifted out of a `.vue` into a `.ts` composable, where it joins the tier and the component stays thin.

4. **CI shape: on PRs, path-gated, report-before-gate.** Web: Stryker's **incremental** cache
   (`incremental: true` + the cached report) is the cost control — it re-tests only mutants whose code *or
   covering tests* changed (⇒ ~1–3 min/PR), so a weakened assertion (a test edit, no source change) is caught,
   not missed. There is **no native git-`--since` in StrykerJS OSS** — that is arcmutate (§1, commercial), so do
   not reach for a `--since` flag; file-level diff scoping, if ever wanted, is external `git diff → --mutate` glue
   (as on the backend), never a Stryker flag. A high `break` threshold on the mutate scope is the gate;
   report-only elsewhere until a baseline exists. **The shared crypto package's `break` is 100, with no margin**
   (inbox #241, 2026-10-01): there every equivalent mutant is disabled at its own line with its measured reason,
   so a margin buys no headroom for one — only blindness, and at 95 it was 23 mutants, enough to lose the identity
   codec's whole test file at exit 0. A margin stays right where equivalents are left in the score; the reasoning
   and the bite live in `src/packages/crypto/stryker.config.mjs`. Backend: the incremental history file as the CI cache; git-diff
   scoping is optional glue and largely moot, because the unit-layer target is small and boot-free. A **diff-aware
   meta-check** asserts *mutants > 0 over the intended glob* only when that glob has changed files — a run that
   mutates nothing and reports 100% is itself a claim nothing backs.

   **A change that moves the mutate scope runs the gate; reading the manifest does not settle it.** The threshold
   is an **aggregate over the whole scope**, so a weak new member passes on its siblings' backs: when the
   rate-limit core was re-scoped ([ADR 0052](0052-token-bucket-rate-limit-core.md)) every structural check was
   green — the glob matches, the class exists, both workflow `paths:` blocks point at real files — and the run
   scored 56/58 = 97% against a threshold of 95 while the newly-scoped class **alone** was 23/25 = 92%, with
   *both* survivors in it. The two mutants were invisible for a reason that generalizes: a boundary is pinned only
   by a case where its two sides differ — `capacity - tokens` equals `capacity + tokens` from an *empty* bucket,
   and saturating vs. not-saturating leave identical state when the elapsed time divides *exactly*. The gate costs
   ~20 s incremental, which is less than the claim it replaces. Whether the aggregate should gain a per-glob score
   floor is open (inbox #116); that a scope move must be *run* is not.

This extends [ADR 0040](0040-coverage-gaps-and-http-not-a-surface.md): that decision fixed *a ref counts only
when CI runs it*; this one adds *a test that runs proves only as much as its assertions pin* — the next link.

## Consequences

- **Blind spots — the sharpest limit, stated first.** Mutation sees functional behaviour only:
  - **Non-functional properties are invisible.** `passwordCheck`'s constant-time compare (`MessageDigest.isEqual`
    → `==` is an equivalent mutant — identical output) is **not** protected. The security property is timing, and
    mutation cannot see timing.
  - **A confidently-wrong oracle kills mutants exactly as well as a right one** — mutation raises trust in
    assertion *strength*, not correctness.
  - **Cross-feature gaps are invisible** (the trash × sharing shape, where the doc↔code delta was empty and the
    defect real). Mutation closes one link, not the chain.
  - **A KAT-pinned suite scores ~100%** — mutation there confirms and teaches little. The two-tier separation
    (crypto primitives vs. authz decisions) must keep the primitive score from masquerading as authz coverage;
    reporting them merged would be the same misleading-green it exists to prevent.
- **Cost, split honestly by surface:**
  - **Web is small** — a tool at an already-strong surface. tier1 `src/packages/crypto/src/**` ≈ 350–650 mutants, full run
    minutes (Argon2id KDF mutants dominate), PR diff ~1–3 min.
  - **Backend is the real work** — a **scoped security-core refactor** (disentangle the fusion points, write the
    pure-policy unit tests), *not* "wire up PIT". The mutatable logic is tiny once boot-free, so PIT itself is
    cheap; the effort is the substrate. The extraction *pattern* is **spike-validated on rotate/consent**: the
    `pendingConsent`-skip lifts cleanly into a pure `RotationPolicy` over a dumb store, the slices stay green as its
    safety net, and unit-PIT scores 100% — so `break = 100 − margin` (per the web pilot; the margin is headroom for
    a future equivalent mutant, of which this tier has none), never 100.
- **Rejected alternatives:**
  - **Integration-level mutation** (PIT over `@QuarkusTest`) — covers the surface with no refactor, but pays
    boot-per-mutant, is PIT+Quarkus-fragile, and its signal conflates wiring with assertion strength: the wrong
    measurement for a trust-chain claim. Kept only as a rare fallback for logic that is irreducibly DB-fused.
  - **A pure-primitives-only backend scope** (`Tokens`, `Ed25519Verifier`) — cheap, but leaves "who may see what"
    unmeasured while the score looks green: the misleading-green trap, worse than no gate for a trust claim.
  - **A repo-wide gate** — a resented CI tax; the value does not justify it on KAT-pinned primitives or on the
    passthrough that is most of the backend. #99's original "never repo-wide" absolute is retired in favour of
    deliberate widening, but the default stays scoped.
  - **PIT for crypto** — PIT is JVM-only and the backend runs no content crypto, so it cannot reach the crypto
    the evidence is about; that crypto lives in web TypeScript, where the tool is Stryker. The row's original
    "does PIT run against Gradle/Quarkus" question was aimed at the wrong runtime.
  - **A git-native mutation tool for the backend** — none maintained exists in OSS; arcmutate is commercial. And
    it would not help, since the backend cost is the boot, not the mutant count.
- **Do not add tamper-rejection vectors to the conformance battery — the gate measured why they buy nothing.**
  The vector file has none (a flipped tag / wrong AAD / truncated frame), and that is the decision, not an oversight:
  a rejection has no output to record, so there is nothing to put in `contracts/`. (The typed seal's refusal cases
  in `v1.conformance.test.ts` — one binding field moved, one purpose swapped — are not tamper vectors: they read the
  vectors, add none, and pin that an opener binds every field of its purpose, which is veyq's own encoder,
  [ADR 0072](0072-a-seal-binds-its-object.md).) Two facts close the case, and
  they cover the whole surface between them. **The rejection itself is `@noble`'s**, not veyq's — GCM tag
  verification lives in `node_modules`, outside any mutate scope, so no veyq mutant can weaken it and no veyq
  test can prove it. (This does **not** condemn the RFC 5869 HKDF KAT, which runs `@noble` code too and stays:
  it pins the library against a *published standard vector* — cross-stack conformance, a different job from
  killing a mutant. "It only exercises a dependency" is the reason to skip a tamper vector, not that one.)
  **The AAD *construction* is already pinned byte-exact** by the forward KAT
  (`expect(hex(frame)).toBe(f.frame)`): any change to `frameAad`/`wrapAad` alters the tag and fails there. So a
  wrong-index or wrong-context test only re-asserts what those two already give — which is why `openFrame`'s
  *"a dropped, reordered or appended frame fails to open"* comment **is** backed, indirectly, and needs no test
  of its own. Measured, not argued: the package gate's `--force` run leaves **zero** frame-, wrap- or
  AAD-survivors (the code now spans `v1.ts`, `primitives.ts` and `seal.ts`; the current mutant counts are stated
  once, in `src/packages/crypto/stryker.config.mjs`, and not here, where a figure rotted from 134 to 186 unseen).
  The one rejection **veyq makes itself** — `decodeKdfParams`'s length guard, a plain `if` with no primitive
  behind it — was the gate's only gap, and its test is what took `v1.ts` to 100%. That test pins the error
  *message*, and must keep doing so: unguarded, a short blob still throws (the `DataView` `RangeError` the guard
  exists to pre-empt), so a bare `.toThrow()` passes with the guard deleted and proves nothing. Its second
  rejection, the served-profile check ([ADR 0017](0017-crypto-primitives-and-post-quantum-strategy.md) rule 5),
  is the same kind and pinned the same way: one negative per field, each on the refusal's own message.

Explained for security researchers on the docs site: [Verify it yourself](../../site/src/content/docs/security/verify-it-yourself.md).
