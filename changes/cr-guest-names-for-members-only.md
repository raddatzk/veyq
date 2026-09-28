---
id: cr-guest-names-for-members-only
title: A guest's name reaches the Memory's members only — no share link is served it
status: in-progress
satisfies: [uc.memory.buckets, uc.sharing.share-contributor, uc.sharing.share-uploader]
adrs: ["0030", "0031", "0034"]
invariants: [operator-blind E2EE, API-first]
---

## Settled by the owner (2026-09-28)

The refine proposed a cryptographic separation (a per-Memory name keypair whose private half sits under `K_SC`).
The owner **rejected it** for the simpler variant below and gave the go. Do not reopen it; the reason it is
enough is in Decisions.

1. **No anonymous "added by a guest" mark** on a guest's page — the page stays as it is.
2. **A CONTRIBUTOR does not see its own name listed** — nothing changes in the UI.
3. **`ShareMedia.identityId` is null on a contributor's media**; only an uploader's media keeps it.
4. **The name pre-screen gets one sentence, EN and DE,** saying who sees the name (the Memory's members).
5. **Camera metadata in the original** is inbox #193, not this CR.

## Goal

A VIEWER or CONTRIBUTOR link holder can read the name every guest gave: the link's own contributors and every
uploader whose media the link shows. The public resolve serves those names, and the link yields the keys they are
sealed under. The owner's call is that link holders must not be served guest names, while the Memory's members
keep reading them exactly as today. This CR takes the names out of the one guest response that carries them. The
names stay sealed as they are; no key and no seal changes.

## Context & constraints

- **The rule:** [ADR 0030](../docs/decisions/0030-contributor-public-share.md) point 8, which this CR rewrites from
  the rejected keypair design to the chosen one. Point 5 (*"returns … each share identity's sealed name"*) is what
  it supersedes.
- **What must keep working:** a viewer opens an uploader's upload through the identity's wrapped key
  ([ADR 0031](../docs/decisions/0031-uploader-public-share.md) point 7). So the resolve keeps serving an UPLOADER
  identity's `{identityId, wrappedIdentityKey}` and that media's `identityId`.
- **Members** read names through the authenticated bucket list (`MediaStore.primarySealedNameFor`), which this CR
  does not touch ([ADR 0034](../docs/decisions/0034-media-uploader-buckets.md)).
- **No stored-format change:** no migration, no dev-instance reset. `sealed_display_name` stays where it is.
- **Order:** independent of every live CR (`ls changes/`).

### Inventory — measured on `main` @ `9e90e0e`, 2026-09-28

What a guest can open today, per link role. Each row is *read, not run*; the commands re-run it.

| Link holder | Names it can open today | How |
|---|---|---|
| VIEWER | every UPLOADER guest whose media the link shows | The resolve serves the identity with its name sealed under `K_up`. The node's drop private key is under `K_mem`, which opens the identity's wrapped `K_up`. |
| VIEWER, once a CONTRIBUTOR link | also every guest who named itself on it | The role flip keeps the identities, and the resolve serves the link's own ones, sealed under `K_mem`. |
| CONTRIBUTOR | every other guest on the same link, its own name, and every UPLOADER as above | Same two paths. |
| any link | another CONTRIBUTOR link's guests: **not served**, but sealed under `K_mem` | So a link plus a database dump reads them. After this CR, that is true of every guest name — the accepted residual. |
| any link | the Memory's **members**: nothing | No guest response names a member. |

The commands behind it:

- **The resolve serves the identities, names included.** `grep -n "fun readerIdentities" -A 5 backend/src/main/kotlin/com/veyq/app/features/sharing/SharingStore.kt`
  shows the link's own identities plus every identity with a wrapped key. `grep -n "sealedDisplayName = it" backend/src/main/kotlin/com/veyq/app/features/sharing/SharingResource.kt`
  maps the name into the response. `resolve` hands both to every `canView` role
  (`grep -n "readerIdentities(shareId" backend/src/main/kotlin/com/veyq/app/features/sharing/SharingHandler.kt`).
- **Tests that pin the served name.** `ShareContributorSliceTest` asserts the resolve serves
  `identities.find { … }.sealedDisplayName`; `web/src/features/memory/shareUploader.test.ts` builds a resolve
  fixture carrying it. (`share.test.ts` and `shareUploader.test.ts` also open the name under `K_mem` / `K_up` —
  that is the seal a member opens, and it stays.)
- **The role flip keeps the identities.** `grep -rn "ShareIdentityEntity.delete" backend/src/main` finds only
  the scope purge, and `updateShare` only sets `share.role`
  (`grep -n "share.role = role" backend/src/main/kotlin/com/veyq/app/features/sharing/SharingStore.kt`). An UPLOADER
  identity only ever belongs to an UPLOADER share, because the role never flips to or from UPLOADER
  (`grep -n "INVALID_ROLE" backend/src/main/kotlin/com/veyq/app/features/sharing/SharingStore.kt`) — so *has a
  wrapped key* and *is an uploader identity* are the same predicate.
- **Only the resolve carries anything about an identity.** The guest-reachable operations are the ones with no
  `bearerAuth` (`grep -n "operationId\|bearerAuth" contracts/api/sharing.openapi.yaml`). Of their response
  schemas, only `PublicShare.identities` carries an identity, and `ShareIdentity` is referenced nowhere else
  (`grep -rn "schemas/ShareIdentity\"" contracts/api`).
- **No guest-side code reads a name.** The resolve's identities feed only the uploader key map
  (`grep -n "share.identities" web/src/features/memory/share.ts`), and `ShareView.vue` never reads `identityId`
  (`grep -n "identityId" web/src/views/ShareView.vue` finds nothing).
- **Members read names in one place.** `grep -rn "openBucketName(" web/src --exclude-dir=generated | grep -v test`
  finds `MemoryBuckets.vue`, fed by the bucket list's copy of the identity's sealed name
  (`grep -n "primarySealedNameFor" backend/src/main/kotlin/com/veyq/app/features/media/MediaStore.kt`).
- **The pre-screen promises the opposite today.** `grep -n "contributeIntro" web/src/i18n/index.ts` — *"so
  everyone can see who added what"* (EN) / *"damit alle sehen"* (DE). It is corrected, not only supplemented.

## Decisions

- **Why the response is enough.** Every name stays sealed under a key a VIEWER or CONTRIBUTOR link yields —
  `K_mem`, or an uploader's `K_up` through the drop private key. So a link holder **plus a database dump** still
  reads guest names. In practice that is the operator holding a link. The owner accepts it: that holder already
  decrypts every medium of the Memory, and a name is less than the photos it is attached to. It is stated
  plainly in ADR 0030 point 8 and the security model, not implied closed.
- **`ShareIdentity` loses the name.** The served shape is `{identityId, wrappedIdentityKey}`, both required: only
  UPLOADER identities are served, and each has a key. Removing the field, rather than serving it null, makes the
  guest-side reader impossible to write against the generated client — the shape holds by construction
  (changes/README.md § *prefer removing an identity over adding a rule*).
- **The resolve serves only UPLOADER identities with media in the served subtree** — never the link's own
  contributor identities, whatever the link's role is now. An UPLOADER link is still served none.
- **`ShareMedia.identityId` is set only where it names a served identity**, i.e. on an uploader's media. It is
  derived from the same set, so the two can never disagree.
- **The request side does not change.** `CreateShareIdentityRequest.sealedDisplayName` stays; members read it
  through the bucket list.
- **The pre-screen:** `contributeIntro` loses *"so everyone can see who added what"*; one new sentence, shown for
  both roles under the name field: *"Only the people this memory belongs to see your name."* /
  *"Deinen Namen sehen nur die Personen, denen diese Erinnerung gehört."*

## Phases

- [ ] 0 red — on the untouched tree, write **B1** and record its reds (the failing assertion of each case goes
      into the Retrospective). Not committed red.
- [ ] 1 contracts — `ShareIdentity` without `sealedDisplayName`, `wrappedIdentityKey` required; the descriptions of
      `PublicShare.identities` and `ShareMedia.identityId`.
      Locator: `grep -n "ShareIdentity:\|identities:\|identityId:" contracts/api/sharing.openapi.yaml`.
      · Done when: `npm run generate:api` succeeds and the contracts guards are green. `./gradlew check` and
      web `npm run check` are red at the `ShareIdentity` construction and fixture until phases 2–3.
- [ ] 2 backend — `readerIdentities` → uploader identities only; the resolve maps `identityId` only for them.
      Turn `ShareContributorSliceTest`'s served-name assertion around; B1 green.
      Locator: `grep -rn "readerIdentities\|sealedDisplayName = it\|identities\.\(find\|size\)\|identities\[" backend/src`.
      · Done when: `./gradlew check` green.
- [ ] 3 web — the generated type change (`share.ts` key map, the `shareUploader.test.ts` fixture); the pre-screen
      sentence (EN+DE); the live e2e assertions below.
      Locator: `grep -rn "identities\|sealedDisplayName\|contributeIntro" web/src --exclude-dir=generated`.
      · Done when: web and admin-web `npm run check` green; `python3 scripts/run_web_e2e.py` green with both
      sharing suites executed.
- [ ] 4 docs + coverage — ADR 0030 points 2, 5 and 8 (8 rewritten to this decision, *"Not built yet"* dropped);
      the security-model row (§ *What holds today*); `uc.sharing.share-contributor` (step 3, the diagram's
      server note, both *What this means for you*, criteria `direct-append-attribution` and
      `sealed-name-anonymous-identity`'s comment); `uc.sharing.share-uploader`'s *"The price: …"* sentence
      (**deleted**, replaced by what is served); the new criterion in `uc.memory.buckets`; inbox #190 done.
      Sites: `grep -rnE "sealedDisplayName|sealed name|display name|names stay sealed|guests gave|#190" docs contracts wiki web/src/features/memory/*.e2e.test.ts`,
      re-run at this phase. Each owning use case and ADR 0030 is read end to end.
      · Done when: all `scripts/validate_*.py` green.

## Definition of done

- Baseline, measured green on `061dbcd` before the first edit:
  `for f in scripts/validate_*.py; do python3 "$f" >/dev/null 2>&1 || echo "RED $f"; done` prints nothing.
- **B1 — live defect, backend slice `ShareGuestNamesSliceTest`.** One Memory with a CONTRIBUTOR link (two named
  guests, one with a media), a second CONTRIBUTOR link (a guest with a media), an UPLOADER guest with a media, a
  VIEWER link, and a VIEWER link that was a CONTRIBUTOR link with a guest. For every one of the four readable
  resolves:
  - **identities are exactly the uploader's** `{identityId, wrappedIdentityKey}` — the positive control on the
    same predicate, so an empty list cannot pass;
  - **no served identity carries a `sealedDisplayName` key** — anchored on the key's absence on that present
    identity, never on an empty response;
  - **the contributor's media is served with `identityId` null** and the uploader's with its id — anchored on a
    media found by its `mediaId`.
  - **Control (green on both trees):** the member bucket list still serves both guests' sealed names.
  - **Expected first red per case (untouched tree):** VIEWER → the name key is present; CONTRIBUTOR and flipped
    VIEWER → the identity set is larger than the uploader's; contributor media → `identityId` is stamped.
- **Re-bite on the fixed tree by injection:** restore the own-identity union in `readerIdentities` (snapshot
  copy) → the CONTRIBUTOR and flipped-VIEWER cases red on the identity set; drop the `identityId` filter → the
  media case red. The name-key case needs no injection: the generated DTO no longer has the field.
- **Live e2e:** `shareUploader.e2e` still shows a viewer opening the uploader's upload, and its raw viewer resolve
  carries exactly the uploader's identity, no name key; `shareContributor.e2e`'s raw resolve carries no identity
  and a null `identityId` on the contributed media, while the curator still recovers the name (the positive).
- `grep -rn "sealedDisplayName" backend/src/main web/src --exclude-dir=generated` finds only the request side
  (the identity create, the entity, the bucket copy).

**Not proven, by decision:** a link holder with a database dump reads guest names (ADR 0030 point 8). **Not
proven, by tier:** an operator that serves a guest a tampered page sees the typed name before it is sealed
([ADR 0026](../docs/decisions/0026-client-trust-tiers.md)).

## Retrospective
