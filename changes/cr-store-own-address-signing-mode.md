---
id: cr-store-own-address-signing-mode
title: A store on its own address is a named signing mode, not a deployment's special case
status: planned
satisfies: ["uc.admin.tenant-reachability"]
adrs: ["0067", "0013", "0063", "0064"]
invariants:
  - "Operator-blind E2EE — signing holds no key material and derives none; this CR changes which host a URL is signed for and nothing else. No phase touches ciphertext, wraps or grants."
  - "A presigned URL must remain the whole capability: one method, one object key, a short TTL. Phase 1 changes the signed host, never the TTL, the method set or the key scope."
  - "Signing must never be steerable by a request header. The domain reaching the signer is a registered value from the `tenant` table (`TenantHostResolver.domainFor`), and bite test 3 pins that a spoofed Host still cannot choose an origin in either mode."
---

## Goal

`S3BlobStore.endpointFor` swaps `veyq.blob-store.public-endpoint`'s **host** for the resolved tenant's domain.
That is right for the docker-compose path route, where the store is same-origin behind our own Traefik
([ADR 0063](../docs/decisions/0063-database-driven-tenant-ingress.md)), and wrong for any store reached at an
origin of its own — where every tenant must sign against the **same** store host. Two things need that second
mode and neither is a special case of the other:
[ADR 0064](../docs/decisions/0064-kubernetes-helm-deployment-target.md)'s Kubernetes branch, where Garage gets
its own Ingress host, and a SaaS tenant on their own bucket. This CR makes it **one named mechanism with two
callers**, decided in [ADR 0067](../docs/decisions/0067-store-provisioning-from-a-declared-type.md) Decision 5
rather than inside either of them. The default is unchanged, so no existing deployment moves.

## Context & constraints

**This is genuinely separate work from
[`cr-store-provisioning-from-a-declared-type`](cr-store-provisioning-from-a-declared-type.md), not a phase of
it.** Neither depends on the other: the provisioner does not read the signing mode, and the signing mode does
not need a provisioned store. They touch different files, and this one has a second caller (ADR 0064's chart)
that the provisioning CR does not. `changes/README.md`: *genuinely independent work = separate CRs, not
phases.* They may be built in either order.

- [ADR 0067 Decision 5](../docs/decisions/0067-store-provisioning-from-a-declared-type.md) — the decision.
- [ADR 0013 §4](../docs/decisions/0013-blob-storage-presigned-direct-to-store.md) — the two same-origin
  strategies, chosen per deployment target, and the **measured** table of what a bucket CORS policy can express
  on the pinned Garage v2.0.0. The store-origin mode is the branch that *needs* a CORS policy; §4's verdict is
  that on the bundled store that policy can only be `*`, and why that is tolerable (the presigned URL is the
  capability, its TTL is short, the fetch carries no credential). **Writing the policy is the other CR's work.**
- [ADR 0063](../docs/decisions/0063-database-driven-tenant-ingress.md) — per-request signing, which this
  generalises rather than replaces.
- [uc.admin.tenant-reachability](../docs/features/admin/use-cases/uc.admin.tenant-reachability.md) — the
  use-case that **already owns this property**. Its criterion `presign-for-the-arriving-domain` is where the
  signing behaviour lives, and its **A5** already states the branch in words: *"the object store is not behind
  this deployment's proxy (external S3, or any store already reachable at its own hostname): the tenant's
  domain plays no part in signing."* This CR is `satisfies:`-ing a use-case that describes the behaviour, not
  inventing one — but see the delta below, which is the whole reason there is work to do.

### The delta, stated precisely — A5 is refined state the code can only half serve

A5 is true and today it has exactly one implementation: leave `public-endpoint` **blank**, and `endpointFor`
returns the internal endpoint and ignores the domain (`BlobStore.kt:180-187`, pinned by
`S3BlobStorePresigningTest.withNoPublicEndpointTheDomainIsIgnored`). That serves A5's *first* example — a store
already publicly reachable at its own hostname **which is also the endpoint the backend uses**. It does not
serve the case ADR 0064 needs: on Kubernetes the backend reaches Garage in-cluster (a Service name) while the
browser reaches it at an Ingress host. Blanking `public-endpoint` there signs for the in-cluster address, which
is unreachable from a browser — a green boot and a failing fetch.

So the mode this CR adds is narrow and can be said in one sentence: **use `public-endpoint` verbatim and do not
swap its host for the tenant's.** Three branches where there are two today.

### Decisions this CR is built on, each with the line that settles it

| Decision | Settled by |
|---|---|
| The host swap is what needs a mode, not the endpoint choice | `BlobStore.kt:180-187` — `endpointFor` already branches on `publicEndpoint.isBlank()`; the new mode is a third branch on the same value, not a new value |
| One presigner per resolved endpoint, cached, never invalidated | `BlobStore.kt:113-117, :137` — in store-origin mode every tenant resolves to the same endpoint, so the cache collapses to one entry. No invalidation problem is introduced; nothing here makes store config runtime-editable. |
| The signing domain is never a header value | `RequestTenantDomain.kt:32` → `TenantHostResolver.domainFor` (`:36`), through the `tenant` table |
| Existing behaviour must not move | The three `S3BlobStorePresigningTest` cases + four slice cases already referenced by `docs/coverage/features/admin/use-cases/uc.admin.tenant-reachability.yaml` |

## Inventory

Run the locators; do not rebuild the set from them.

**The signing sites:**
```
grep -rn 'endpointFor\|presignerFor\|publicEndpoint\|public-endpoint' backend/src contracts infra .run \
  --exclude-dir=build
```
**The tests that pin today's behaviour — the test tree, which an inventory keyed on source misses:**
```
grep -rln 'presignGet\|presignPut\|PresignDomain\|Presigning' backend/src/test
```
**The prose that states the one-mode claim** (a correction is prose, so no symbol-keyed locator finds it —
run this before writing the site list, and again at the phase that writes new prose):
```
grep -rn --exclude-dir=node_modules --exclude-dir=.git --exclude-dir=build \
  "swaps\|host swapped\|same-origin\|plays no part in signing" docs contracts backend/src infra
```

| Surface | What changes |
|---|---|
| `backend/.../shared/blobstore/BlobStore.kt` | `endpointFor` gains the third branch; the class KDoc's two-branch paragraph (`:119-123`) becomes three. |
| `backend/.../config/VeyqConfig.kt` | One member on `BlobStore`, defaulting to today's behaviour. |
| `backend/src/test/.../S3BlobStorePresigningTest.kt` | The new mode's cases beside the existing three. |
| `backend/src/test/.../MediaPresignDomainSliceTest.kt`, `SharePresignDomainSliceTest.kt` | The mode at slice altitude, if the unit layer cannot reach a property they can. |
| `docs/features/admin/use-cases/uc.admin.tenant-reachability.md` | A5 gains the case it does not currently serve; step 5 currently states one mode as if it were the only one. |
| `docs/coverage/features/admin/use-cases/uc.admin.tenant-reachability.yaml` | The criterion's refs. |
| `infra/README.md`, `infra/.env.example` | Only if the mode is operator-settable on compose. It should **not** be needed there — compose is the same-origin branch — and saying so in the CR is cheaper than an operator discovering a knob that must stay untouched. |

**One line to fix while in the file, and no more:** `BlobStore.kt:121`'s KDoc says *"so `[domainFor]` ignores
the domain entirely"*. The method that ignores the domain is `endpointFor`, in that same class;
`TenantHostResolver.domainFor` is a different method in a different package that ignores nothing. The link
resolves, which is why no guard catches it, and it points at the wrong thing in exactly the paragraph this CR
rewrites.

## Phases

- [ ] **1 backend** — the third branch, its config member, and its tests.
      - **Name the mode, do not infer it.** Inferring it (e.g. "the public-endpoint host matches no tenant
        domain") is available and wrong: it is a runtime property of the `tenant` table, so the signing
        behaviour of a running instance would change when a tenant is created. An explicit config member,
        **defaulting to today's host-swapping behaviour**, so no existing deployment moves and the compose
        recipe needs no new line.
      - **Decide the spelling once, and let it read as a mode rather than a flag.** There are three signing
        behaviours to distinguish, not two — swap the host for the tenant's domain (today's default), use
        `public-endpoint` verbatim (this CR), and sign against the internal endpoint because no public one is
        configured (today, when blank). A boolean forces the third to stay implicit in a blank string; that is
        the current shape and it is exactly what made A5 half-serveable. Whatever the spelling, the branch
        conditions must be **mutually exclusive and total** — write them out in one place and check each of the
        three is reachable.
      - **This CR does not write a CORS policy.** The store-origin mode is the branch that needs one, and
        `cr-store-provisioning-from-a-declared-type` writes it. Signing for an origin whose bucket has no CORS
        policy is a browser-side failure with a green server, so the two CRs are not independent *in
        deployment* even though they are in code — a done-when below names that dependency instead of hiding
        it.
      · **Done when:** `./gradlew check` green — the **full** run, not the touched class.

- [ ] **2 coverage + use-case** — the acceptance criterion and the flow it belongs to.
      - `uc.admin.tenant-reachability` **step 5** currently states the host swap as the behaviour, full stop;
        **A5** states the other branch and, as written, describes a case the code does not fully serve. Both
        need the third branch named. This is a correction to durable state: say which shape it takes — edited,
        narrowed, or extended — rather than only naming the sentence, and re-run the prose locator above at
        this phase, because phase 1's KDoc edits changed the corpus.
      - The coverage criterion is `presign-for-the-arriving-domain`. Decide, in one sentence and with the rule
        rather than by reflex, whether the new mode is a **new criterion** or refs added to that one. The
        deciding question from `changes/README.md` § coverage is whether it describes what an operation *does*
        or a property the system *has* — and both modes are the same property (*a presigned URL is signed for
        an origin the client can actually reach*) under different deployment shapes, which argues for one
        criterion. Two criteria carrying the same refs is the tell that it went wrong.
      · **Done when:** `python3 scripts/validate_coverage.py` green, and the whole
      `for f in scripts/validate_*.py; do python3 "$f"; done` glob green over the **committed** tree.

## Bite tests — and how each is bitten

1. **Live defect — two tenants on a store-origin instance sign against the same host.** The mirror of the
   existing `twoTenantsSigningTheSameKeyGetTwoDifferentHosts`. Red on the untouched tree, because the mode does
   not exist. Free, and the strongest kind.
2. **Its positive control, on the same predicate.** The existing
   `twoTenantsSigningTheSameKeyGetTwoDifferentHosts` must stay **green by name** in the same run. A negative
   assertion ("the hosts do not differ") is satisfied perfectly by a signer that has stopped resolving tenants
   at all, so the claim is only demonstrated by the pair. Compare against the **named** other case, never
   against "nothing else broke".
3. **Injection — a spoofed `Host` still cannot choose an origin, in the new mode.** The security property A3
   states, which must hold in both modes and is the one thing the new branch could quietly weaken. Lever: drive
   a request with a `Host` no tenant claims and assert the signed origin is the configured store origin, not
   the header's. Anchor on the **specific observable** (the signed host), never on "it did not blow up".
4. **Injection — the default did not move.** Lever: set the new member to its default explicitly and assert the
   three existing `S3BlobStorePresigningTest` cases and the four `*PresignDomainSliceTest` cases are all green.
   This is the "no existing deployment moves" claim, which is otherwise prose about code that is already green.

**Do the levers survive this CR's own phases?** Yes — all four lever on configuration and on the `tenant`
table, and no phase here removes either. **And do the tests this CR cites survive?** The three
`S3BlobStorePresigningTest` cases are cited above as evidence that today's behaviour is pinned, and this CR
must not disarm them: `withNoPublicEndpointTheDomainIsIgnored` is the one at risk, because it pins the blank
branch that phase 1 re-expresses. If the mode's spelling makes that branch unreachable, the CR owns its
replacement — the property is permanent, the lever was only ever the most convenient thing that could fail.

## Definition of done

- **Baseline, run before the first edit** — the command, not a number in this file:
  `for f in scripts/validate_*.py; do python3 "$f" >/dev/null 2>&1 || echo "RED: $f"; done`. A red after this
  CR that was not red then is this CR's.
- `./gradlew check` green (full run); `python3 scripts/validate_coverage.py` green; the full guard glob green
  over the **committed** tree. This repo has no remote and no CI run has ever executed, so that local run over
  the committed tree is the strongest available proof — a done-when here may not say "green in CI".
- `docs/coverage/` updated for `uc.admin.tenant-reachability`: every criterion this CR touches carries a test
  ref or an explicit `gaps:` entry with a reason.
- All four bite tests demonstrated, each in the form its entry names.
- **Named, not proven here:** an end-to-end store-origin deployment needs a bucket CORS policy, which
  `cr-store-provisioning-from-a-declared-type` writes and ADR 0013 §4 measures. This CR's proof stops at the
  signed URL. Say so in the commit rather than letting a green suite read as a working deployment.
- **Commit path confirmed before phase 1**, with a throwaway commit — the 1Password SSH agent idle-locks
  mid-session and a locked agent fails every commit with `1Password: failed to fill whole buffer`. Do not
  reach for `--no-gpg-sign`; ask. Commits go straight to `main`, small and conventional, split by phase.

## Traps

- **The presigner cache is never invalidated** (`BlobStore.kt:113-117, :137`). Correct while the connection is
  fixed at boot. The new mode must be boot-time like everything else on `veyq.blob-store.*`; a runtime-settable
  signing mode would leave stale presigners behind with no code path that notices.
- **`application-e2e.properties:38-45` leaves `public-endpoint` deliberately unset**, with five lines
  explaining that setting it makes `media.e2e.test.ts` fail in the **PUT**, long after a green boot. Read that
  comment before touching e2e config. This CR should not need to.
- **Out of scope:** the per-tenant store connection and its trust decision (ADR 0067 defers it to
  `docs/inbox.md` #41 — a central operator presigning against a customer's own bucket holds that customer's
  infrastructure credentials, and picking between a bucket-scoped credential and an assumed role belongs
  there); the Helm chart itself (`docs/inbox.md` #26 slice 4); the CORS policy (the other CR).

## Retrospective

<!-- Filled at implement time. What didn't go well — planning, execution, AND process/collaboration (did we
honor the conventions we already agreed?). Root cause, concrete fix. Both directions. End with one
forward-looking "next time". -->
