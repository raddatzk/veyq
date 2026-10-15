#!/usr/bin/env python3
"""Pin the documented gaps of the other guards, so the trust-chain skill's gap-map can't silently rot.

The other `validate_*.py` assert that a reference *resolves*, never that what it points at carries weight —
the `trust-chain` skill maps exactly where each stops. That map used to live as prose derived by reading guard
code (`SKILL.md`), which is a *remembered* claim wearing a command's clothes: tighten a validator to close a
gap and the prose goes silently false. That is the repo's recurring "Trap 5" (a remembered claim drifting from
reality), one level up — and it bought this guard.

So each documented gap becomes an **executable** assertion instead of a sentence. The guard snapshots the
source tree once, confirms each target validator is **green on an un-forged copy** of it (else an unrelated red
would masquerade as "the gap holds"), and then for every case below: copies the snapshot into a throwaway dir,
injects one forgery, runs the real validator as a subprocess, and asserts its exit code is the documented one.
The *source tree* is what git lists — every tracked file plus every untracked one it does not ignore — so
dependencies, build output, run artefacts and nested worktrees stay out by the repository's own `.gitignore`,
not by a list kept here. A name list (`node_modules`, `build`, …) was what this used to copy by, and it let
through everything it did not name: in a main checkout carrying `.claude/worktrees/`, `.e2e-out/` and
`graphify-out/` each copy was ~9.6k files where the sources are ~1.3k, and each of the per-case copies took
15–20 s (inbox #244). It also keeps a validator that walks the filesystem on the tree CI sees, not on a
sibling worktree's sources. The real
tree is never touched — every guard resolves `ROOT` as `__file__.parent.parent`, so running the *copied*
`scripts/<guard>.py` binds it to the copy; restoration is "delete the tempdir" (an abort leaves only an
orphaned tempdir, never a dirty tree — the automated evolution of `changes/README.md` § bite-proof).

The coupling is the point: if someone tightens a validator to close a gap, the documented pass/fail flips and
**this guard goes red** — forcing the closure to be recorded in `SKILL.md`, not landed silently. When that
happens the fix is *not* to relax this guard blindly: confirm the gap really closed, then move the skill's map
and this table together.

Scope — file-based cases only, each either one the guard must **reject** (a closed hole, pinned so it can't
silently re-open) or a still-**open** gap (the limit is documented, not yet closed). `CASES` below is the
list and the only place the tally lives: a count restated here rots, and did — it said "nine" while eleven
ran. Two further documented gaps stay out on purpose (below).

Rejected — regression-pins for closed holes (a forgery the guard must now catch):
  1  a web coverage ref at a *non-test* file — closed: a web ref must exist AND match *.test|*.spec, so a real
     `package.json` no longer resolves (validate_coverage `WEB_TEST_RE`)
  2  a backend ref at an *empty `fun` body* — closed: the resolved method's body is now read; an empty `{}`
     block is rejected, while a `= expr` expression body still counts (validate_coverage `_kotlin_funs`)
  4a a coverage `status: done` — rejected: the enum permits only unset/`planned`, so a loosened enum bites
  7  a base64 field's `maxLength` pushed above 65 532 (more than a `TEXT` column holds) on a non-allowlisted
     field — rejected: `validate_maxlength_storage` ties every base64 cap to its storage, so the #94 class
     (a cap larger than its column) can't silently re-enter
  11 the app's config-secret volume removed from the production compose file — rejected:
     `validate_config_secret_persistence` pins the mount, so the KEK cannot silently stop surviving a
     container recreate (the hole ADR 0056 § Consequences surfaced)
  12 one more column referencing `veyq_blob`, declared in Kotlin and in a migration with its own foreign key —
     rejected: `validate_blob_reference_arity` pins the arms by name (three since inbox #341 moved the cover onto
     the attachment table), so an arm added without teaching the predicates that enumerate them cannot land
     quietly. It is the one case here whose cost is *user data*: a reclaim predicate short by one arm deletes live
     content wherever no foreign key refuses it (the cover column it was built for had none)
  13 a scheduled purge deleting store objects inline, under a receiver named something other than `blobStore`
     and typed with a fully-qualified name — rejected: `validate_store_reclaim_arity` keys on the declared
     type, not on one spelling. The shape is chosen because it is the one that got through: that guard's first
     predicate matched only an unqualified `: BlobStore` and reported **green** over a tree carrying this exact
     bypass. What a bypass costs is the whole of ADR 0062 — a delete with no failure channel, at a point where
     the row naming the key is already gone
  14 one of the routability-changing tenant operations stops calling the ingress projector — rejected:
     `validate_tenant_ingress_triggers` derives the routability-changing `TenantStore` methods from the store
     itself and requires each `TenantHandler` caller to reproject, so ADR 0063's "never an incremental patch"
     cannot decay into "patched by whoever remembered". The defect is silent by construction: the row stays
     right, the routing goes stale, and the first symptom is a domain that does not resolve
  17 a cited backend test class gated by `@EnabledIfEnvironmentVariable` on a variable no workflow sets —
     rejected: `validate_coverage` reads the gate and requires a CI run of the Gradle tests that satisfies it
     (ADR 0040: a test no CI run executes makes no claim). The real instance was `BlobBatchE2ETest`, cited under
     `uc.memory.gallery` until 6f19923 with every structural check green
  18 every job that runs the backend's Gradle tests made conditional (`if: false`) — rejected: with no
     unconditional run left, no backend ref makes a claim, and the guard says so once. `if: false` is the exact
     shape the web-e2e job carried while four coverage refs rode on it
  19 a criterion-level gap naming `http`, the surface ADR 0040 demoted — rejected: criterion `gaps` lists are now
     read (known surface, non-empty reason, and a surface that carries a ref on that criterion). Before, nothing
     read them at all
  20 a backend ref at a method that exists only inside a KDoc example — rejected: `validate_coverage` scans
     comment- and string-masked source. Unmasked, `TestMailbox`'s KDoc made `TestMailbox.x` a resolvable method
     with a body
  22 a use-case silent on an owed surface (`uc.settings.appearance-language` with its backend gap removed, so
     backend has neither a ref nor a gap) — rejected: `validate_coverage` requires every use-case to address
     `OWED` (backend, web). The coverage README and the `SURFACES` comment had said "never left silent" with
     nothing reading it; six files were silent (2026-09-23), and one silence hid a real web surface
  24 an `admin-web` ref on an implemented use-case (a real admin-web test file, under a criterion that is
     otherwise sound) — rejected: `validate_coverage` admits refs only on a surface in `ADMITTED`, one whose CI
     job some run has executed (ADR 0040, the note on what a row is). ADR 0040 said that rule carries itself;
     14 admin-web and 3 crypto refs went in green before anything read it (2026-09-24)
  26 `BlobUploadRequestItem.wrappedBlobKey` loses its `@Size`, its `maxLength` left standing — rejected:
     `validate_request_bounds` requires every request-body bound to carry its annotation. Before it, nothing tied
     the two, and `BlobUploadsBatchRequest.items` carried `maxItems: 256` with no `@Size` at all (inbox #153)
  27 `BlobUploadsBatchRequest.items` loses its `@field:jakarta.validation.Valid` — rejected: validation does not
     descend without it, so every item-level annotation below is dead, which is exactly how the `wrappedBlobKey`
     checks sat until #153
  28 `MoveMediaRequest.mediaIds`' `@Size` says one more than its `maxItems` — rejected: the two are one rule
     written twice, value for value, and an annotation drifting from its schema is the same gap as a missing one
  29 a `web/src` comment citing a use-case id under a real feature that names no doc — rejected:
     `validate_doc_citations` resolves `uc.`/`en.`/`cc.` ids, not only `ADR NNNN`. Before it, 7 such ids dangled
     at 12 sites across the client, the backend and a spec, and two agents followed them into nothing (inbox #122)
  31 a German site page declaring one doc fewer than its English page — rejected: `validate_wiki_sources` holds a
     translation to the same `sources:` set, so a change to one doc cannot reach one language's page and not the other
  32 a declared doc's every link back to its site page removed — rejected: `validate_wiki_sources` requires each
     `docs/` source to link the page, so whoever edits the doc sees the published page that restates it. Before
     it, a page told users the opposite of its doc and was found because a user asked (inbox #120)
  34 `:core`'s build file depending on `:apps:memories` — rejected: `validate_backend_module_graph` rule (b) holds
     the module direction (ADR 0079 Decision 4: core depends on no application). Since the module split a core
     *file* importing Memories does not compile, so the edge it would need is the one place a text check can see
     the back-edge before a build; Gradle refuses that edge too, as a cycle, but only when it builds
  35 a share-subtree contributor whose `authorizedBlobIds` forgets the derivative arm — rejected:
     `validate_blob_reference_arity` follows the arms into every `SharedSubtreeContributor`, not only core's
     `subtreeBlobIds` (the arm is red because of the follow: the file is present and still implements it)
  36 the media `ScopeEraser` stops deleting `veyq_media` — rejected: `validate_scope_erasure_arity` unions the
     entities deleted across every `ScopeEraser` implementation, so a table that drops out of its owning
     package's eraser is red. Without the union (the guard read only `ScopePurge.eraseScope`) the extraction
     would have made every media delete invisible to it, or — kept hard-wired — blocked on all of them
  37 the media `NodeSubtreeEraser` deleted outright — rejected: `validate_node_subtree_erasure` derives the
     node-keyed entities from their `node_id` column and requires an eraser to reach each. None of those tables
     has a foreign key onto `veyq_node`, so nothing at runtime refuses the purge; before that guard the scope, blob and reclaim
     guards were all green on this exact forgery while the purge orphaned every media, bucket and blob row
  39 a core `shared/ui/` file importing `features/memory/memoryNodes` — rejected: `validate_web_core_imports_no_applications`
     resolves a relative specifier against the importing file, so the web core cannot grow a back-edge into the
     Memories application (ADR 0079). The same file importing `features/account/…` stays green by hand-run control —
     a different edge, not this guard's subject
  40 `export * from` the application in a core file — rejected: the specifier is read after `from`, not only after
     `import`, so a re-export cannot launder the edge
  41 a dynamic `import('…')` of the application in a core file — rejected: the same edge in its lazy spelling
  42 a sibling of the composition root (`applications2.ts`) importing the application — rejected: the exemption is
     the exact path `web/src/applications.ts`, not a directory or a prefix
  43 a multi-line import of the application in a core file — rejected: the specifier is read after `from`, and the
     `} from '…'` line of a multi-line import does not start with `import`
  44 a bare side-effect `import '…'` of the application in a core file — rejected: the third spelling of the edge
  45 a core file naming the Memories node kind — rejected: the shell asks the registry, it does not know the kinds
  46 `import.meta.glob` in a core file — rejected: discovery by glob would hide an edge from the import rule
  48 a kernel (tenant) file importing a slice (the admin tenant feature) — rejected: `validate_kernel_imports_no_slices`
     reads kernel membership from its declared `KERNEL` list, not from a directory (the kernel was `shared/`
     until the package rename folded it into `me.raddatz.veyq.core.<feature>`); every package off the list is a slice
  49 a `core/node/` file naming the generated `NodeKind.MEMORY` in code — rejected: `validate_kernel_imports_no_slices`
     holds core to the node kind `FOLDER`; the kinds Memories owns are answered through `NodeKindRules` and the other
     seams (ADR 0079). The import rule cannot see this edge — a kind is a value, not a package, and the generated
     enum it is spelled through was no Memories package. A KDoc naming the kind sits beside it and must not be what
     is read. The contract's `NodeKind` is an open token since cr-platform-contract-split slice 4 and generates no
     enum; the forgery is text, so it still pins that spelling, and a literal `"MEMORY"` is read by the same rule
  50 a forged second application (`backend/apps/other`, included, copied by the Dockerfile, indexed) depending on
     `:apps:memories` — rejected: `validate_backend_module_graph` rule (b) — an application never depends on another
     (ADR 0079 Decision 4). Gradle accepts that edge without complaint, and no real second application exists yet,
     so a forged one is the only lever; everything else about it is made valid so (b) is what rejects it
  51 a `:core` main file declaring a `me.raddatz.veyq.memories` package — rejected: `validate_backend_module_graph` rule
     (d), no package in two modules. Kotlin compiles the split and Quarkus indexes both halves; the package name
     would then claim an owner the file's module is not
  52 `:apps:memories`' build file renaming its jar (`base { archivesName.set(…) }`) — rejected:
     `validate_backend_module_graph` rule (f). Quarkus cannot map a renamed project's test-fixtures jar, so a
     `testFixtures(project(…))` dependency silently leaves the `@QuarkusTest` application model — the build is
     green and the fixtures are not there (measured 2026-10-05, Quarkus 3.36.0, when the convention still set one)
  53 `:apps:memories`' build file no longer applying `id("veyq.module")` — rejected: `validate_backend_module_graph`
     rule (f). Without the convention plugin the module has no group (its jar loses its `me.raddatz.veyq.` name), no
     toolchain, no test JVM settings, and — for a module with the Quarkus plugin — a second `quarkusBuild`. (It
     forged the retired `:test-support` project's build file until the helpers became `:core`'s test fixtures.)
  54 the Dockerfile's `backend-build-base` stage no longer copying `backend/buildSrc` — rejected:
     `validate_backend_module_graph` rule (e). The warm-up `gradlew help` would find no `veyq.module` plugin, so the
     image does not build, while `./gradlew check` in a checkout stays green
  55 the root declaring `implementation(testFixtures(project(":core")))` — rejected: `validate_backend_module_graph`
     rule (b). Gradle accepts it and the build stays green, but the fixtures then ship: measured, `quarkusBuild` put
     `me.raddatz.veyq.core-test-fixtures.jar` (with the `@Priority` fake `RecordingBlobStore`), JUnit and RestAssured into
     `quarkus-app/lib/main`
  56 `:core`'s `src/testFixtures/resources/META-INF/beans.xml` deleted — rejected: `validate_backend_module_graph`
     rule (c). Without it the fixtures' CDI fakes are no beans and the production bean answers in their place: only
     a test that casts an injected bean to its fake notices; every other one silently runs against production
  57 `:core` declaring `testFixturesImplementation(project(":apps:memories"))` — rejected:
     `validate_backend_module_graph` rule (b). Gradle sees no cycle (the fixtures are not core's main code), so it
     builds, and Memories would boot inside every `:core` test — which must boot core alone (`CoreAloneSliceTest`)
  58 `EnqueueRequest.capabilityTag` loses its annotation, its `$ref` to the bounded scalar `CapabilityTag` left
     standing — rejected: `validate_request_bounds` reads a `$ref`'d scalar's bounds as the property's, because the
     generator inlines it as a String and emits none of them. Before that, this property took any tag (measured:
     `9F3A1C04` was enqueued, 201) while its schema said lowercase hex of at most 64
  59 one of `updateNode`'s version declarations dropped, its field still on the request — rejected:
     `validate_write_concurrency` requires every version-shaped request property to be the `field` of a declaration.
     ADR 0080 gave the operation a second, independent version, which is why `x-veyq-concurrency.fields` became a list
  60 a second version-shaped property added to `UpdateNodeRequest` with no declaration — rejected: the reverse angle
     holds per field, not per operation, so a declared neighbour does not cover it
  61 `updateNode`'s `409` no longer naming a declared conflict code — rejected: each declaration's `conflictCode` must
     appear in the operation's `409` text, so a client meeting it finds it documented
  62 a spec directory `contracts/api/demo/` holding a spec with no `:apps:demo` in the settings — rejected:
     `validate_backend_module_graph` rule (d) derives the application directories from `contracts/api/` and holds each
     to an included module, as Gradle's `SpecSets.requireNoStray` does while it configures (inbox #344)
  63 the same directory with an included, otherwise valid `:apps:demo` whose build file never opts in with
     `veyqOpenApi { generateOwnSpecs() }` — rejected: rule (d), the module must generate its directory, else the
     specs generate nothing (Gradle refuses it too, once every project is configured)
  64 `SpecSets.packageRootOf` turned back into a hardcoded owner list (`"me.raddatz.veyq.core.generated"`,
     `"me.raddatz.veyq.memories.generated"`) — rejected: rule (d) derives the packages from one `"…$owner…"` template
     and refuses a package root spelled for one owner, so the list that refused every unnamed application cannot
     quietly come back as what the rule reads
  66 a `core/blob/` file naming Memories' blob role `"THUMBNAIL"` as a literal in code — rejected:
     `validate_kernel_imports_no_slices` reads the roles an application owns by the node-kind rule since the
     contract's `BlobRole` opened to a token (cr-files-application P3-S2); core compares a role and names none of an
     application's (`COVER` is core's own since inbox #411, so the probe moved to `THUMBNAIL`). The KDoc beside it
     names the role too and must not be what is read
  67 core's attachment `SharedSubtreeContributor` deleted — rejected: `validate_blob_reference_arity`'s live twin
     names the attachment arm (`NodeAttachmentEntity` in a contributor body); the three declaration keys are
     unchanged by it, and a share's blob GET would refuse every file under it (cr-files-application P3-S3)
  68 core's attachment `NodeSubtreeEraser` deleted — rejected: `validate_node_subtree_erasure` derives
     `NodeAttachmentEntity` from its `node_id` column. The runtime refuses too here (`fk_attachment_node`, the one
     foreign key onto `veyq_node`), but only as a purge that rolls back on every tick; the guard does not lean on it
  69 core's attachment `ScopeEraser` no longer deleting its rows — rejected: `validate_scope_erasure_arity` finds
     `veyq_node_attachment` in the FK closure below `veyq_scope`, with no cascade
  72 a `features/depot` file importing `features/memory` — rejected: `validate_web_core_imports_no_applications`'s
     application rule reads every application's files for an edge into another one (cr-files-application F0, the
     second application): an application depends on core only, as the backend's rule (b) holds for the modules
  73 a `features/depot` file importing Memories' generated `media` client — rejected: an application's clients are
     derived per owner (`contracts/api/<owner>/`), so the edge to another application's client is red as well
  74 a `core/node/` file re-exporting `features/depot` — rejected: core's import rule reads every application of
     `APPLICATIONS`, not only the first one
  75 a `core/node/` file naming the Depot node kind `'FILE'` — rejected: the kind rule lists every application's kinds
  76 `:apps:depot`' build file depending on `:apps:memories` — rejected: `validate_backend_module_graph` rule (b) on the
     real second application (case 50 forges one); Gradle accepts the edge without complaint
  77 the root build no longer composing `:apps:depot` (its `implementation(project(…))` line gone) — rejected:
     `validate_backend_module_graph` rule (a) requires every included module to ship. Before F0 this passed every
     guard; the module compiled and its tests ran green while the fast-jar held none of it
  78 a `core/node/` dialog reading Memories' message key `'memory.deleteMemoryTitle'` — rejected:
     `validate_web_core_imports_no_applications`' i18n rule. The catalog is one file, so the key resolves and every test
     is green; core's delete dialog did exactly this and titled a Depot drop box "Delete Memory" (inbox #375)
  79 admin-web's light `--app-primary` set back to Aura's emerald-500 (`#10b981`, white label 2.54:1) — rejected:
     `validate_web_contrast` reads `admin-web/src/styles/base.css` as well as web's (inbox #390). Until then that
     guard's limit (e) said admin-web was not covered, and the copy ADR 0065 keeps failed 34 pairs unseen
  80 `ClaimRequest.capabilityTags` loses its `EachElement`, its `items` `$ref` to the bounded `CapabilityTag` left
     standing — rejected: `validate_request_bounds` rule 6 reads a list of scalars' element bounds and requires the
     list to carry them. Until then that guard documented element bounds as beyond it, and the claim took any string
     as a tag (measured: `NOT-HEX` answered 204, inbox #354)
  81 a stylesheet rule whose class only a `*.test.ts` spells — rejected: `validate_stylesheet_anchors` leaves test
     evidence (tests, `src/test/` helpers, snapshots, fixtures, goldens) out of its markup vocabulary. Until then a
     test asserting a button does NOT carry a class kept that class's hiding rule anchored (inbox #362)
Retired ids, never reused: 38 (a `core/node/` file naming `memories.media` by an inline qualified reference) and 47
(a `core/spaces/` file importing `memories.media`) pinned the Memories half of `validate_kernel_imports_no_slices`,
which the Gradle module split retired: `:core` has no edge to an application, so either shape fails
`:core:compileKotlin` (`Unresolved reference 'memories'`) — a check no buildless forgery can run, and a text rule
beside it would be a weaker copy. Case 34 now pins the edge such an import would need.
Open gaps — a forgery the guard still passes (a documented limit, not a defect to fix blindly):
  4b a lying `status: planned` on an implemented use-case passes. **Left open on purpose** — the obvious rule
     "planned ⇒ no coverage refs" is *false in general*: a use-case can be legitimately `planned` while carrying
     a real ref on a criterion that already shipped ahead of the rest (a partially-built use-case). The narrow
     decidable rule ("every criterion covered ⇒ planned is a contradiction") shuts only the *full* evasion, never
     the partial ("cover 5 of 6, mark planned, skip the 6th"), so it is much mechanism for one theoretical lie.
     `uc.spaces.verify-identity` used to be the live instance of exactly this shape (inbox #128) — split
     2026-08-25 into a fully-built file and a fully-`planned` one, so the repo no longer has a concrete example,
     but the abstract gap in the rule is unchanged and a future file can reopen it. Recorded here, beside the
     case, so nobody re-derives the false rule from scratch.
  5  a CR marked `status: done` with no implementation passes (validate_changes: enum + refs, nothing more)
  6  a link to a non-existent `#fragment` anchor in an existing file passes (validate_doc_links strips it)
  8  a *free-text* (non-base64) `maxLength` blown past what its column holds passes — `validate_maxlength_storage`
     reasons only about base64 caps (char == byte), never free-text byte-width (`chars × up-to-4` under utf8mb4)
     nor columns that serialize several capped fields (`terms_markdown_by_locale`). **Left open on purpose** —
     catching it needs the field→column map the guard deliberately does not carry (a manifest that rots); the
     one real free-text-on-`TEXT` column was instead *widened to MEDIUMTEXT* in #94, closing that instance by
     construction. The gap is the class, not that column.
  9  rate-limit state in a limiter named neither `*Limiter*` nor `*Counter*` passes `validate_ratelimit_core`,
     which matches on the **filename** (ADR 0052). **Left open on purpose** — the reach of a buildless grep *is*
     the naming convention, and no grep can enforce a name that has not been written yet; closing it needs the
     reflection sweep the posture guard already pays for, which would leave this glob entirely. Pinned so the
     limit stated in that guard's docstring is executable rather than a sentence.
  10 a mutated policy that gains a dependency on a file no `paths:` filter lists passes
     `validate_mutation_trigger_paths` — it derives one path per manifest entry and cannot follow imports, so
     editing that dependency alone moves a mutation score without triggering the gate. **Left open on purpose**
     — a path filter is a *file* trigger; following imports would mean a Kotlin dependency graph in a buildless
     Python guard, and the honest alternative (widen the filter to the four scope directories) costs ~70 files'
     worth of no-op mutation runs against the 10 that are in scope. Pinned so the limit in that guard's
     docstring is executable rather than a sentence.
  15 a `$PROJECT_DIR$`-rooted path inside a run configuration's `<env>`/`<entry>` value pointing nowhere passes
     `validate_run_configs`, which resolves a configuration's *subject* (the `package.json` plus the script it
     names, the Gradle project, the shell working directory) and never an env value. **Left open on purpose** —
     a build output legitimately does not exist on a clean tree (an env value naming a file the backend it
     configures writes under `backend/build/`), so resolving env paths would make that guard red on arrival,
     and red-because-wrong is indistinguishable from red-because-broken for every later reader. The same limit
     covers the value that actually bites — a
     `VEYQ_BUNDLED_STORE_PUBLIC_ENDPOINT` disagreeing with the origin you browse is green here and a broken
     presigned URL in the browser — which no file-shaped assertion can decide at all. Pinned so the limit in
     that guard's docstring is executable rather than a sentence.
  16 a CSS class that no markup applies passes `validate_stylesheet_anchors` when it is not the selector's
     ANCHOR — `.a-live-class .never-applied` is green, `.never-applied` alone is red. **Left open on purpose**
     — the inside of a selector is exactly where a stylesheet legitimately reaches into a dependency's DOM
     (`.memory-card .p-card-body`), and the trust-chain job is buildless, so there is no `node_modules` and
     therefore no honest way to tell a vendor class from a typo at that position. Anchoring is what lets that
     guard need no vendor list at all. Pinned so the limit in that guard's docstring is executable rather than
     a sentence.
  21 a cited backend test that skips itself at runtime (`Assumptions.assumeTrue(...)` in its body) passes
     `validate_coverage`, which decides annotation gates, never runtime behaviour. **Left open on purpose** —
     an assumption is an arbitrary expression, possibly in a `@BeforeAll` or a helper it calls, so a check
     would catch only the direct spelling: much mechanism for part of the lie, the same trade as 4b. Pinned so
     the limit in that guard's docstring is executable rather than a sentence.
  23 a Server-Owner use-case silent on `admin-web` (`uc.admin.setup-server` with its admin-web gap removed)
     passes `validate_coverage`, which owes only backend and web. **Left open on purpose** — owing `admin-web`
     everywhere would demand a "not applicable" gap on nearly every file, and owing it per feature fits no
     feature (admin mixes web, admin-web and backend-only use-cases) without a declaration the coverage schema
     does not carry, the schema growth ADR 0040 declined. The `web` gap such a file must carry is where it says
     where its client went. Pinned so the limit in that guard's docstring is executable rather than a sentence.
  25 a Host header read through an alias constant (`const val` holding the header name, then
     `getHeaderString(<the constant>)`) in a resource passes `validate_lookup_single_home`, whose spellings are
     literals at the call. **Left open on purpose** — following a constant to its use is data flow, which a
     buildless grep does not have, and the realistic regression (a hand parse copied from an old resource) is a
     literal and is caught. Pinned so the limit in that guard's docstring is executable rather than a sentence.
  30 a `web/src` comment citing a use-case id under a feature directory that does not exist passes
     `validate_doc_citations`, which reads an id only under a real feature directory. **Left open on purpose** —
     that anchor is what keeps attribute chains (`.parent.parent`, `.value.trim()`) from reading as ids; without
     it the guard cries wolf on Python and TypeScript alike. Pinned so tightening the anchor flips it visibly.
  65 an included, otherwise valid application `:apps:demo` that opts in with `generateOwnSpecs()` while
     `contracts/api/demo/` does not exist passes `validate_backend_module_graph`. **Left open on purpose** — it is
     the module whose directory is missing, handled as before the owners were derived: its spec set is empty, and an
     empty set is refused only when it would generate (`OpenApiGenerate`), because the Dockerfile's warm-up
     configures the build before `contracts/` is copied in and a configuration-time refusal broke the image build.
     Pinned so a rule (d) that starts refusing it is a visible decision against that reason.
  70 core's attachment `ScopeEraser` moved to a phase after `ScopeEraser.BLOB_ROWS_PHASE` passes
     `validate_scope_erasure_arity`, which proves a table is named, never the order (its limit (d)). At runtime
     `fk_attachment_blob` refuses core's blob delete and no group holding a file is ever erased; the order is held
     by `NodeAttachmentSliceTest` and `ScopeEraserOrderSliceTest`, which execute it. Pinned so the limit is executable
  71 core's attachment `NodeSubtreeEraser` deleting its rows without freeing their blobs (`deleteBlobRow` dropped)
     passes `validate_node_subtree_erasure`, which proves each node-keyed table is named, never that what its rows
     referenced goes with them. The blob rows and their store objects would outlive every purged file, unreferenced
     and with no sweep; `NodeAttachmentSliceTest` holds it. Pinned so the limit is executable
  33 a site page whose prose contradicts the doc it declares passes `validate_wiki_sources`, which checks the
     address and never the claim. **Left open on purpose** — whether two sentences in two registers say the same
     thing is not decidable by a grep; the forgery is the sentence that bought that guard, put back. Pinned so
     the limit in that guard's docstring is executable rather than a sentence.
Out of reach by construction, not deferred work: the mutation **total-vs-per-glob** asymmetry (needs a report
fixture + git, so it belongs to a mutation-job-local test, not this buildless glob) and the **rate-limit posture
sweep** (ADR 0051). The sweep is a JVM test — it enumerates JAX-RS endpoints by reflection over compiled classes
and a live CDI container — so this guard, which forges *files* and runs a *Python* validator as a subprocess,
has no way to inject into it. Its boundary (green proves every endpoint **declares** a posture, never that a
domain-keyed limiter **fires**) is therefore held where it can be: in the sweep's own docstring, in the
`trust-chain` skill's gap-map, and in `docs/crosscutting/cc.security.rate-limit.md`. (The other historical deferral here —
CR-number reuse against history, old inbox #107 — is *gone*, not deferred: CRs are named by slug, not an
allocated number, so there is no number to reuse and nothing to guard. See `changes/README.md` § naming.)

A `validate_*.py`, so the trust-chain glob runs it on every push with no build. Runnable locally:

    python3 scripts/validate_guard_gaps.py
"""
from __future__ import annotations

import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
import textwrap

import backend_tree  # where the backend's test and fixture roots live — resolved by glob, never spelled here

ROOT = pathlib.Path(__file__).resolve().parent.parent

# Anchors the forgeries edit. If one drifts (a coverage file relocated, a test class renamed), the forgery
# fails to apply and the case reports red with a "could not forge" message — deliberately loud, because a
# forgery that silently no-ops would make the assertion vacuously pass.
COV_APPEARANCE = "docs/coverage/features/settings/use-cases/uc.settings.appearance-language.yaml"
COV_SHARE_NOTE = "docs/coverage/features/sharing/use-cases/uc.sharing.share-note.yaml"
WEB_TEST_REF = "src/shared/state/useThemeState.test.ts"
BACKEND_TEST_CLASS = "ShareNoteSliceTest"
BACKEND_TEST_METHOD = "createWithNoteThenAnonymousResolveReturnsIt"
BACKEND_TEST_REF = f"{BACKEND_TEST_CLASS}.{BACKEND_TEST_METHOD}"
# A criterion-level gap (a covered surface's refs stopping short) — its surface is what case 19 forges.
COV_GALLERY = "docs/coverage/features/memory/use-cases/uc.memory.gallery.yaml"
CRITERION_GAP_SURFACE = "      - surface: backend\n"
# The use-case gap that keeps an owed surface from being silent (case 22 removes it; 4b leans on it), and a
# Server-Owner use-case's admin-web gap (case 23 removes it, leaving the web gap after it in place).
OWED_GAP_ANCHOR = "\ngaps:\n  backend: >-\n"
COV_SETUP = "docs/coverage/features/admin/use-cases/uc.admin.setup-server.yaml"
ADMIN_WEB_GAP_ANCHOR = "\ngaps:\n  admin-web: >-\n"
NEXT_GAP_ANCHOR = "\n  web: >-\n"
# A real admin-web test file — case 24 cites it on a surface no run has admitted, so only admission can reject it.
ADMIN_WEB_TEST_REF = "src/cli/cli.test.ts"
# A distinctive base64 cap (the space icon, 21 884) — forging it oversize is the #94 closed-hole probe; a
# free-text cap (the operator terms, 20 000) — forging it huge is the free-text open-gap probe.
B64_CAP_ANCHOR = ("contracts/api/scopes.openapi.yaml", "maxLength: 21884", "maxLength: 65536")
FREETEXT_CAP_ANCHOR = ("contracts/api/_common.openapi.yaml", "maxLength: 20000", "maxLength: 999996")
# The volume that carries the config-secret KEK across a container recreate — the hole ADR 0056 § Consequences
# surfaced and cr-instance-backup-and-restore closed. Removing it is the whole forgery.
PROD_COMPOSE = "infra/docker-compose.prod.yml"
CONFIG_SECRET_MOUNT = "      - veyq-config-secret:/var/lib/veyq\n"
# Where a fourth veyq_blob reference arm is forged: a scope is the plausible place for one (a space banner),
# and it is one of the entities the delete paths already touch.
SCOPE_ENTITY = "backend/core/src/main/kotlin/me/raddatz/veyq/core/scope/ScopeEntity.kt"
SCOPE_ENTITY_ANCHOR = "class ScopeEntity : PanacheEntityBase {\n"
# Named the way a real later core migration is (V<yyyymmddHHMM>__core_<slug>.sql), above every pinned version.
BANNER_MIGRATION = "backend/core/src/main/resources/db/migration/V209912312359__core_guard_gap_scope_banner.sql"
# Where a bypass of the single-deleter rule is forged. A scheduled purge is the plausible place: it already
# reclaims rows, so deleting the objects inline is the one-line shortcut ADR 0062 Decision 3 forbids.
TRASH_PURGE = "backend/apps/memories/src/main/kotlin/me/raddatz/veyq/memories/media/MediaTrashPurge.kt"
TENANT_HANDLER = "backend/core/src/main/kotlin/me/raddatz/veyq/core/admin/tenant/TenantHandler.kt"
TENANT_REPROJECT_CALL = "        if (outcome is SuspendOutcome.Done) reproject()\n"
TRASH_PURGE_CTOR_ANCHOR = "    private val store: MediaStore,\n"
# The derivative arm of the share blob-GET authorization, which lives in the media implementation of
# `SharedSubtreeContributor` (ADR 0079 phase 1, step 7) — the place the live-twin check must follow it to.
MEDIA_SHARE_CONTRIBUTOR = "backend/apps/memories/src/main/kotlin/me/raddatz/veyq/memories/media/MediaSharedSubtreeContributor.kt"
DERIVATIVE_ARM_LINE = '            MediaDerivativeEntity.find("mediaId = ?1", m.id).list().forEach { ids.add(it.blobId) }\n'

# Where a Host read through an alias constant is forged: the recovery resource is where the hand parse that
# bought validate_lookup_single_home used to live, so it is where a copy of it would plausibly come back.
RECOVERY_RESOURCE = "backend/core/src/main/kotlin/me/raddatz/veyq/core/account/recovery/RecoveryResource.kt"
RECOVERY_RESOURCE_ANCHOR = "class RecoveryResource("
TRASH_PURGE_BODY_ANCHOR = "        store.purgeTrashedBefore(Instant.now().minus(retention))\n"


def _sub(text: str, old: str, new: str) -> str:
    """Replace exactly once, asserting `old` was present — a drifted anchor becomes a loud ForgeError."""
    if old not in text:
        raise ForgeError(f"anchor not found (drifted?): {old!r}")
    return text.replace(old, new, 1)


class ForgeError(Exception):
    """A forgery could not be applied — its anchor drifted. Update this guard's anchors."""


# ---- forgeries: each mutates the copied tree in place so the target guard sees exactly one planted gap ----

def forge_web_ref_nontest(root: pathlib.Path) -> str:
    f = root / COV_APPEARANCE
    f.write_text(_sub(f.read_text(), f"[{WEB_TEST_REF}]", "[package.json]"))
    return "web coverage ref → package.json (a real, non-test file)"


def forge_backend_empty_fun(root: pathlib.Path) -> str:
    tf = _backend_test_file(root)
    src = tf.read_text()
    cut = src.rstrip().rfind("}")  # inject before the class's final brace
    if cut == -1:
        raise ForgeError(f"no closing brace in {tf.name}")
    tf.write_text(src[:cut] + "\n    fun guardGapEmptyProof() {}\n" + src[cut:])
    cov = root / COV_SHARE_NOTE
    cov.write_text(_sub(cov.read_text(), BACKEND_TEST_REF, f"{BACKEND_TEST_CLASS}.guardGapEmptyProof"))
    return "backend ref → empty fun body (symbol resolves; the body is now inspected)"


def _backend_test_file(root: pathlib.Path) -> pathlib.Path:
    # The class is found in whichever module's test root holds it (`backend_tree.test_roots()`, the fixture roots
    # beside them), read on the real tree the snapshot copied, then mapped into the copy `root`.
    tests = backend_tree.kotlin_files(backend_tree.test_roots() + backend_tree.fixture_roots(), f"{BACKEND_TEST_CLASS}.kt")
    if len(tests) != 1:
        raise ForgeError(f"backend test class {BACKEND_TEST_CLASS} found {len(tests)} times, not once (renamed? moved?)")
    return root / tests[0].relative_to(ROOT)


def forge_backend_ref_env_gated(root: pathlib.Path) -> str:
    # The BlobBatchE2ETest shape, planted on the class the coverage file already cites so the forgery needs no
    # second anchor: a class-level gate on a variable no workflow sets, beneath the class's other annotations.
    tf = _backend_test_file(root)
    src = _sub(tf.read_text(), "import org.junit.jupiter.api.Test\n",
               "import org.junit.jupiter.api.Test\nimport org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable\n")
    tf.write_text(_sub(src, f"\nclass {BACKEND_TEST_CLASS} ",
                       f'\n@EnabledIfEnvironmentVariable(named = "VEYQ_GUARD_GAP_PROBE", matches = "1")'
                       f"\nclass {BACKEND_TEST_CLASS} "))
    return "a cited backend class gated on an env variable no workflow sets (must be REJECTED — it never runs in CI)"


def forge_backend_tests_never_run_in_ci(root: pathlib.Path) -> str:
    # Every job whose steps run the backend's Gradle tests gets `if: false` — the web-e2e job's historical shape.
    # Text-level on purpose: a forgery must not borrow the guard's own predicate, or it could only ever agree.
    hits = 0
    for wf in sorted((root / ".github/workflows").glob("*.yml")):
        text = wf.read_text()
        jobs = re.search(r"^jobs:\n", text, re.M)
        if not jobs:
            continue
        parts = re.split(r"(?m)^(?=  [\w-]+:\s*$)", text[jobs.end():])
        for i, part in enumerate(parts):
            if re.search(r"gradlew[^\n]*\b(?:check|test|build)\b", part):
                header, _, rest = part.partition("\n")
                parts[i] = f"{header}\n    if: false\n{rest}"
                hits += 1
        wf.write_text(text[:jobs.end()] + "".join(parts))
    if not hits:
        raise ForgeError("no workflow job runs `gradlew check|test|build` — re-anchor this forgery")
    return f"all {hits} job(s) running the backend's Gradle tests set to `if: false` (must be REJECTED)"


def forge_criterion_gap_demoted_surface(root: pathlib.Path) -> str:
    f = root / COV_GALLERY
    f.write_text(_sub(f.read_text(), CRITERION_GAP_SURFACE, "      - surface: http\n"))
    return "a criterion-level gap naming `http`, the demoted surface (must be REJECTED — the list is now read)"


def forge_backend_ref_kdoc_phantom(root: pathlib.Path) -> str:
    tf = _backend_test_file(root)
    src = tf.read_text()
    cut = src.rstrip().rfind("}")  # inject before the class's final brace
    if cut == -1:
        raise ForgeError(f"no closing brace in {tf.name}")
    tf.write_text(src[:cut] + "\n    /** An example, not a method: `fun guardGapKdocPhantom() = check(true)` */\n"
                  + src[cut:])
    cov = root / COV_SHARE_NOTE
    cov.write_text(_sub(cov.read_text(), BACKEND_TEST_REF, f"{BACKEND_TEST_CLASS}.guardGapKdocPhantom"))
    return "backend ref → a `fun` that exists only inside a KDoc (must be REJECTED — comments are masked)"


def forge_backend_runtime_assumption(root: pathlib.Path) -> str:
    tf = _backend_test_file(root)
    head = f"    fun {BACKEND_TEST_METHOD}() {{\n"
    tf.write_text(_sub(tf.read_text(), head, head + '        org.junit.jupiter.api.Assumptions.assumeTrue('
                                                      'System.getenv("VEYQ_GUARD_GAP_PROBE") == "1")\n'))
    return "a cited backend test that skips itself at runtime via Assumptions (the annotation-only open gap)"


def forge_coverage_status_done(root: pathlib.Path) -> str:
    f = root / COV_APPEARANCE
    f.write_text(_sub(f.read_text(), "useCase: uc.settings.appearance-language",
                      "useCase: uc.settings.appearance-language\nstatus: done"))
    return "coverage status: done (must be REJECTED — a loosened status enum would bite)"


def forge_lying_planned(root: pathlib.Path) -> str:
    # `planned` needs a gap; the file's own backend gap is that gap, so the lie is the status line alone.
    f = root / COV_APPEARANCE
    t = f.read_text()
    if OWED_GAP_ANCHOR not in t:
        raise ForgeError(f"anchor not found (drifted?): {OWED_GAP_ANCHOR!r}")
    f.write_text(_sub(t, "useCase: uc.settings.appearance-language",
                      "useCase: uc.settings.appearance-language\nstatus: planned"))
    return "lying status: planned on an implemented use-case (its real backend gap satisfies planned-needs-a-gap)"


def forge_owed_surface_silent(root: pathlib.Path) -> str:
    f = root / COV_APPEARANCE
    t = f.read_text()
    cut = t.find(OWED_GAP_ANCHOR)
    if cut == -1:
        raise ForgeError(f"anchor not found (drifted?): {OWED_GAP_ANCHOR!r}")
    f.write_text(t[:cut + 1])  # the backend gap ends the file, so it goes with everything after it
    return "a use-case silent on backend, an owed surface — no ref, no gap (must be REJECTED)"


def forge_admin_use_case_silent_on_admin_web(root: pathlib.Path) -> str:
    f = root / COV_SETUP
    t = f.read_text()
    start = t.find(ADMIN_WEB_GAP_ANCHOR)
    end = t.find(NEXT_GAP_ANCHOR, start + 1) if start != -1 else -1
    if end == -1:
        raise ForgeError(f"anchors not found (drifted?): {ADMIN_WEB_GAP_ANCHOR!r} … {NEXT_GAP_ANCHOR!r}")
    f.write_text(t[:start] + "\ngaps:" + t[end:])
    return "a Server-Owner use-case silent on admin-web, an opt-in surface (the owes-only-backend-and-web limit)"


def forge_unadmitted_surface_ref(root: pathlib.Path) -> str:
    # The ref resolves and is a test file, and admin-web is neither a gap nor owed here, so every other check
    # passes: the one thing left to reject it is that no run has executed admin-web's job.
    f = root / COV_APPEARANCE
    f.write_text(_sub(f.read_text(), f"[{WEB_TEST_REF}]",
                      f"[{WEB_TEST_REF}] }}\n      admin-web: {{ unit: [{ADMIN_WEB_TEST_REF}]"))
    if not (root / "admin-web" / ADMIN_WEB_TEST_REF).exists():
        raise ForgeError(f"admin-web test file missing (moved?): {ADMIN_WEB_TEST_REF}")
    return "an admin-web ref on an implemented use-case — a surface no run has admitted (must be REJECTED)"


def forge_cr_done_no_impl(root: pathlib.Path) -> str:
    # A valid slug-named CR the guard will accept (id == filename stem); content is a valid-but-empty 'done' CR.
    (root / "changes/cr-guard-gap-phantom.md").write_text(textwrap.dedent("""\
        ---
        id: cr-guard-gap-phantom
        title: phantom done CR (guard-gap probe)
        status: done
        satisfies: []
        adrs: []
        invariants: []
        ---
        ## Goal
        A CR marked done whose implementation never existed. validate_changes cannot tell.
        """))
    return "CR status: done with no implementation (enum + refs checked, never the code)"


def forge_doc_dead_fragment(root: pathlib.Path) -> str:
    (root / "docs/guard-gap-probe.md").write_text(
        "# Probe\n\nSee [the readme](../changes/README.md#this-anchor-does-not-exist-zzz).\n")
    return "doc link to a non-existent #fragment anchor (fragment stripped, never verified)"


def forge_b64_cap_over_text(root: pathlib.Path) -> str:
    rel, old, new = B64_CAP_ANCHOR
    f = root / rel
    f.write_text(_sub(f.read_text(), old, new))
    return f"base64 cap {old} → {new} on a non-allowlisted field (must be REJECTED — the #94 class)"


def forge_freetext_cap_over_column(root: pathlib.Path) -> str:
    rel, old, new = FREETEXT_CAP_ANCHOR
    f = root / rel
    f.write_text(_sub(f.read_text(), old, new))
    return f"free-text cap {old} → {new} (guard reasons only about base64 — the open gap)"


def _in_request_property(root: pathlib.Path, spec: str, schema: str, prop: str, old: str, new: str) -> None:
    """Replace `old` once inside one property's block of one component schema — anchored on the names, so a
    reworded neighbour cannot move the forgery onto another field."""
    f = root / "contracts" / "api" / spec
    lines = f.read_text().split("\n")
    try:
        start = lines.index(f"    {schema}:")
        begin = next(i for i in range(start + 1, len(lines)) if lines[i] == f"        {prop}:")
    except (ValueError, StopIteration):
        raise ForgeError(f"{spec}: {schema}.{prop} not found (renamed?)")
    end = next((j for j in range(begin + 1, len(lines)) if re.match(r"^ {0,8}\S", lines[j])), len(lines))
    block = "\n".join(lines[begin:end])
    lines[begin:end] = _sub(block, old, new).split("\n")
    f.write_text("\n".join(lines))


def forge_request_bound_unannotated(root: pathlib.Path) -> str:
    _in_request_property(root, "blob.openapi.yaml", "BlobUploadRequestItem", "wrappedBlobKey",
                         " @field:jakarta.validation.constraints.Size(max = 256)", "")
    return "wrappedBlobKey's @Size removed, its maxLength kept (must be REJECTED — the #153 class)"


def forge_request_cascade_dropped(root: pathlib.Path) -> str:
    _in_request_property(root, "blob.openapi.yaml", "BlobUploadsBatchRequest", "items",
                         " @field:jakarta.validation.Valid", "")
    return "the @Valid on BlobUploadsBatchRequest.items removed (must be REJECTED — every item check dead)"


def forge_request_annotation_drifts(root: pathlib.Path) -> str:
    _in_request_property(root, "memories/media.openapi.yaml", "MoveMediaRequest", "mediaIds", "max = 256)", "max = 257)")
    return "MoveMediaRequest.mediaIds annotated max 257 over maxItems 256 (must be REJECTED)"


def forge_request_scalar_ref_unannotated(root: pathlib.Path) -> str:
    _in_request_property(root, "tasks.openapi.yaml", "EnqueueRequest", "capabilityTag",
                         " @field:jakarta.validation.constraints.Pattern(regexp = \"[0-9a-f]+\")", "")
    return "EnqueueRequest.capabilityTag's @Pattern removed, its $ref'd scalar's pattern kept (must be REJECTED)"


def forge_request_list_elements_unbounded(root: pathlib.Path) -> str:
    _in_request_property(root, "tasks.openapi.yaml", "ClaimRequest", "capabilityTags",
                         " @field:me.raddatz.veyq.core.http.EachElement(maxLength = 64, pattern = \"[0-9a-f]+\")", "")
    return "ClaimRequest.capabilityTags' EachElement removed, its items' $ref'd scalar bounds kept (must be REJECTED)"


def forge_ratelimit_state_under_another_name(root: pathlib.Path) -> str:
    # validate_ratelimit_core matches on the FILENAME (*Limiter*/*Counter*), so a limiter that avoids both name
    # parts holds its own map unseen. This is the whole reach of a buildless grep — pinned, not fixed: enforcing a
    # name nobody has written yet is not decidable, and the alternative (a reflection sweep) leaves the glob.
    (root / "backend/core/src/main/kotlin/me/raddatz/veyq/core/sharing/ShareThrottle.kt").write_text(textwrap.dedent("""\
        package me.raddatz.veyq.core.sharing

        import java.util.concurrent.ConcurrentHashMap

        /** A guard-gap probe: rate-limit state under a name the filename grep does not match. */
        class ShareThrottle {
            private val windows = ConcurrentHashMap<String, Long>()
            fun allow(shareId: String): Boolean = windows.putIfAbsent(shareId, 1L) == null
        }
        """))
    return "rate-limit state in a limiter named neither *Limiter* nor *Counter* (the filename-grep open gap)"


def forge_scoped_policy_gains_unlisted_dependency(root: pathlib.Path) -> str:
    # validate_mutation_trigger_paths derives ONE path per manifest entry, so it sees a scoped class's own file
    # and nothing it depends on. Plant exactly that: a scoped policy now imports a helper whose file is in no
    # `paths:` filter, so editing the helper alone moves the mutation score without triggering the gate. Not
    # fixable by this guard's mechanism — a path filter cannot follow imports — so it is pinned, not closed. If
    # someone ever teaches the guard to walk dependencies, this case flips red instead of landing silently.
    helper = root / "backend/core/src/main/kotlin/me/raddatz/veyq/core/membership/PolicyHelper.kt"
    helper.write_text(textwrap.dedent("""\
        package me.raddatz.veyq.core.membership

        /** A guard-gap probe: a dependency of a mutated policy that no `paths:` filter lists. */
        object PolicyHelper {
            fun isLastOwner(owners: Int): Boolean = owners <= 1
        }
        """))
    policy = root / "backend/core/src/main/kotlin/me/raddatz/veyq/core/spaces/scope/MembershipPolicy.kt"
    src = policy.read_text()
    if "import me.raddatz.veyq.core.membership.MembershipEntity" not in src:
        raise ForgeError("MembershipPolicy's MembershipEntity import drifted — re-anchor this forgery")
    policy.write_text(_sub(src, "import me.raddatz.veyq.core.membership.MembershipEntity",
                           "import me.raddatz.veyq.core.membership.MembershipEntity\n"
                           "import me.raddatz.veyq.core.membership.PolicyHelper"))
    return "a mutated policy depending on a file in no `paths:` filter (the transitive-dependency open gap)"


def forge_fourth_blob_reference_arm(root: pathlib.Path) -> str:
    # A fourth place referencing veyq_blob, declared the way a real one would be: a Kotlin @Column plus the
    # migration that adds the column and its foreign key. It is deliberately spelled the way the NAIVE locators
    # miss — the column name is not the annotation's first argument, and `references` is lowercase — because
    # that pair reports an unchanged 3 and 2 on exactly this forgery, which is the reason the arity guard
    # exists at all. Two files, so the entity key and the schema/backstop keys each see it independently.
    entity = root / SCOPE_ENTITY
    src = entity.read_text()
    entity.write_text(_sub(src, SCOPE_ENTITY_ANCHOR, SCOPE_ENTITY_ANCHOR + textwrap.dedent("""\

            /** A guard-gap probe: a fourth arm referencing veyq_blob. */
            @Column(length = 36, name="banner_blob_id")
            var bannerBlobId: String? = null
        """)))
    (root / BANNER_MIGRATION).write_text(textwrap.dedent("""\
        -- A guard-gap probe: the SQL half of a fourth arm referencing veyq_blob.
        ALTER TABLE veyq_scope ADD COLUMN banner_blob_id UUID NULL;
        ALTER TABLE veyq_scope ADD CONSTRAINT fk_scope_banner_blob
            FOREIGN KEY (banner_blob_id) references veyq_blob (id);
        """))
    return "a fourth column referencing veyq_blob, in Kotlin and SQL (must be REJECTED — consumers are now short an arm)"


def forge_contributor_forgets_derivative_arm(root: pathlib.Path) -> str:
    # The media contributor's `authorizedBlobIds` stops enumerating derivative blobs: the share listing still
    # serves them, the blob GET 404s, and every declaration key of the arity guard is UNCHANGED. The file is
    # present and still implements the interface, so this is red only because the guard FOLLOWS the arms into
    # the contributor's body — a guard that merely scanned core's function would stay green on a missing arm
    # it can no longer see.
    f = root / MEDIA_SHARE_CONTRIBUTOR
    if not f.is_file():
        raise ForgeError(f"{MEDIA_SHARE_CONTRIBUTOR} is missing — the media contributor moved or was deleted")
    f.write_text(_sub(f.read_text(), DERIVATIVE_ARM_LINE, ""))
    return "a share-subtree contributor whose authorizedBlobIds forgets the derivative arm (must be REJECTED)"


def forge_direct_store_delete_bypass(root: pathlib.Path) -> str:
    # A purge that deletes store objects inline instead of recording them for the sweep. Spelled the way the
    # NAIVE locator misses, and this one is not hypothetical: the receiver is named something other than
    # `blobStore` AND its type is fully qualified, which is exactly the shape that ran GREEN against this
    # guard's first predicate over a tree carrying the bypass. Pinning that shape is the point of the case —
    # the easy `grep 'blobStore.delete'` reports nothing here.
    f = root / TRASH_PURGE
    if not f.is_file():
        raise ForgeError(f"{TRASH_PURGE} is missing — the media trash purge moved or was deleted")
    src = _sub(
        f.read_text(),
        TRASH_PURGE_CTOR_ANCHOR,
        TRASH_PURGE_CTOR_ANCHOR + "    private val objects: me.raddatz.veyq.core.blobstore.BlobStore,\n",
    )
    f.write_text(_sub(src, TRASH_PURGE_BODY_ANCHOR, TRASH_PURGE_BODY_ANCHOR + '        objects.delete(listOf("k/0"))\n'))
    return "a purge deleting store objects inline under a renamed, fully-qualified receiver (must be REJECTED)"


CORE_NODE_FORGERY = "backend/core/src/main/kotlin/me/raddatz/veyq/core/node/GuardGapProbe.kt"


MEDIA_SCOPE_ERASER = "backend/apps/memories/src/main/kotlin/me/raddatz/veyq/memories/media/MediaScopeEraser.kt"


def forge_media_eraser_drops_a_table(root: pathlib.Path) -> str:
    # The media step stops deleting `veyq_media`. veyq_media is reachable from veyq_scope and has no ON DELETE
    # CASCADE, so with its only delete gone the scope row is held hostage by it at runtime.
    f = root / MEDIA_SCOPE_ERASER
    f.write_text(_sub(f.read_text(), '        MediaEntity.delete("scopeId", scopeId)\n', ""))
    return "the media ScopeEraser no longer deleting MediaEntity (must be REJECTED — veyq_media would block the scope row)"


MEDIA_NODE_ERASER = "backend/apps/memories/src/main/kotlin/me/raddatz/veyq/memories/media/MediaNodeSubtreeEraser.kt"


def forge_media_node_eraser_deleted(root: pathlib.Path) -> str:
    # The whole media step of the node subtree purge removed. Nothing else names veyq_media or veyq_bucket on that
    # path, and neither table has an FK onto veyq_node to refuse the node-row delete, so the orphans are silent.
    f = root / MEDIA_NODE_ERASER
    if not f.is_file():
        raise ForgeError(f"{MEDIA_NODE_ERASER} is missing — the media node eraser moved or was deleted")
    f.unlink()
    return "the media NodeSubtreeEraser deleted (must be REJECTED — media and buckets would outlive their node)"


ATTACHMENT_DIR = "backend/core/src/main/kotlin/me/raddatz/veyq/core/blob/"
ATTACHMENT_CONTRIBUTOR = ATTACHMENT_DIR + "AttachmentSharedSubtreeContributor.kt"
ATTACHMENT_NODE_ERASER = ATTACHMENT_DIR + "AttachmentNodeSubtreeEraser.kt"
ATTACHMENT_SCOPE_ERASER = ATTACHMENT_DIR + "AttachmentScopeEraser.kt"


def _attachment_file(root: pathlib.Path, rel: str) -> pathlib.Path:
    f = root / rel
    if not f.is_file():
        raise ForgeError(f"{rel} is missing — core's attachment piece moved or was deleted")
    return f


def forge_attachment_contributor_deleted(root: pathlib.Path) -> str:
    # Core's attachment contributor removed. Every declaration key of the arity guard is UNCHANGED (the column, the
    # table and its foreign key all stay); only the live twin sees that no share's blob GET authorizes an attachment
    # any more — a guest of a share would 404 on every file in it.
    _attachment_file(root, ATTACHMENT_CONTRIBUTOR).unlink()
    return "core's attachment SharedSubtreeContributor deleted (must be REJECTED — the live twin misses the arm)"


def forge_attachment_node_eraser_deleted(root: pathlib.Path) -> str:
    # Core's attachment step of the node subtree purge removed. The runtime is loud here, unlike for media
    # (`fk_attachment_node` refuses the node delete, the scope's purge rolls back every tick), but the text guard
    # must not lean on that: it is red because the `node_id` column is derived, not because the FK exists.
    _attachment_file(root, ATTACHMENT_NODE_ERASER).unlink()
    return "core's attachment NodeSubtreeEraser deleted (must be REJECTED — attachments would block their node's purge)"


def forge_attachment_scope_eraser_drops_its_delete(root: pathlib.Path) -> str:
    # Core's attachment step of the group erasure stops deleting: veyq_node_attachment is in the FK closure below
    # veyq_scope with no cascade, so the blob, node, identity and scope deletes are all refused.
    f = _attachment_file(root, ATTACHMENT_SCOPE_ERASER)
    f.write_text(_sub(f.read_text(), '        NodeAttachmentEntity.delete("scopeId", scopeId)\n', ""))
    return "core's attachment ScopeEraser no longer deleting NodeAttachmentEntity (must be REJECTED)"


def forge_attachment_scope_eraser_after_blob_rows(root: pathlib.Path) -> str:
    # The attachment step moved above ScopeEraser.BLOB_ROWS_PHASE: it still names its table, so the arity guard is
    # green — and at runtime core's blob delete is refused by fk_attachment_blob, so no group with a file is ever
    # erased. Ordering is the slice tests' (`NodeAttachmentSliceTest`, `ScopeEraserOrderSliceTest`), not a grep's.
    f = _attachment_file(root, ATTACHMENT_SCOPE_ERASER)
    f.write_text(_sub(f.read_text(), "    override val phase = 200\n", "    override val phase = 650\n"))
    return "core's attachment ScopeEraser at a phase after the blob rows (an OPEN gap — order is not read)"


def forge_attachment_node_eraser_keeps_the_blobs(root: pathlib.Path) -> str:
    # The attachment step deletes its rows and never frees their blobs: every node-keyed table is still named, so
    # the guard is green, and every purged file leaves a blob row and its store objects behind with no reference
    # and no sweep. Freeing is the purge slice test's (`NodeAttachmentSliceTest`), not a grep's.
    f = _attachment_file(root, ATTACHMENT_NODE_ERASER)
    f.write_text(_sub(f.read_text(), "        attachments.forEach { blobs.deleteBlobRow(it.blobId) }\n", ""))
    return "core's attachment NodeSubtreeEraser deleting rows but not their blobs (an OPEN gap — freeing is not read)"


CORE_BUILD = "backend/core/build.gradle.kts"
CORE_BUILD_DEPS_ANCHOR = "dependencies {\n"


def forge_core_depends_on_application(root: pathlib.Path) -> str:
    # The edge a core file importing Memories would need to compile: a `compileOnly` one, which ships nothing and
    # still makes every Memories class visible to core.
    f = root / CORE_BUILD
    f.write_text(_sub(f.read_text(), CORE_BUILD_DEPS_ANCHOR,
                      CORE_BUILD_DEPS_ANCHOR + '    compileOnly(project(":apps:memories"))\n'))
    return ":core's build file depending on :apps:memories (must be REJECTED — core depends on no application)"


SETTINGS = "backend/settings.gradle.kts"
DOCKERFILE = "Dockerfile"
# The Dockerfile's build-base `COPY` of Memories' build file; the forged application's is planted beside it.
MEMORIES_BUILD_COPY = "backend/apps/memories/build.gradle.kts"


def forge_application_depends_on_application(root: pathlib.Path) -> str:
    # A forged application, valid in every respect the module guard checks — included, composed by the root, indexed,
    # copied by the Dockerfile, named by the convention — except for one edge: to the first application.
    _compose_in_root(root, ":apps:other")
    app = root / "backend/apps/other"
    (app / "src/main/resources/META-INF").mkdir(parents=True)
    (app / "src/main/resources/META-INF/beans.xml").write_text("")
    (app / "build.gradle.kts").write_text(
        'plugins {\n    id("veyq.module")\n}\n\n'
        'dependencies {\n    implementation(project(":core"))\n    implementation(project(":apps:memories"))\n}\n')
    s = root / SETTINGS
    s.write_text(s.read_text() + '\ninclude(":apps:other")\n')
    d = root / DOCKERFILE
    text = d.read_text()
    if MEMORIES_BUILD_COPY not in text or "backend/apps/memories/src" not in text:
        raise ForgeError(f"the Dockerfile no longer copies {MEMORIES_BUILD_COPY} and its src — re-anchor this forgery")
    text = text.replace(MEMORIES_BUILD_COPY, f"{MEMORIES_BUILD_COPY} backend/apps/other/build.gradle.kts", 1)
    d.write_text(text.replace("backend/apps/memories/src", "backend/apps/memories/src backend/apps/other/src", 1))
    return "a forged second application depending on :apps:memories (must be REJECTED — no application → application)"


MEMORIES_BUILD = "backend/apps/memories/build.gradle.kts"


def forge_module_renames_its_jar(root: pathlib.Path) -> str:
    f = root / MEMORIES_BUILD
    if not f.is_file():
        raise ForgeError(f"{MEMORIES_BUILD} is missing — re-anchor this forgery")
    f.write_text(f.read_text() + '\nbase { archivesName.set("veyq-memories") }\n')
    return ":apps:memories renaming its jar with an archivesName (must be REJECTED — Quarkus loses its test fixtures)"


MODULE_PLUGIN_REQUEST = 'id("veyq.module")'
BUILD_SRC_COPY = "COPY backend/buildSrc ./buildSrc\n"


def forge_module_without_convention_plugin(root: pathlib.Path) -> str:
    f = root / MEMORIES_BUILD
    text = f.read_text() if f.is_file() else ""
    if MODULE_PLUGIN_REQUEST not in text:
        raise ForgeError(f"{MEMORIES_BUILD} does not apply {MODULE_PLUGIN_REQUEST} — re-anchor this forgery")
    f.write_text(text.replace(MODULE_PLUGIN_REQUEST, 'kotlin("jvm")'))
    return ":apps:memories applying the Kotlin plugin instead of veyq.module (must be REJECTED — an unconventional module)"


ROOT_BUILD = "backend/build.gradle.kts"
CORE_FIXTURES_BEANS = "backend/core/src/testFixtures/resources/META-INF/beans.xml"


def forge_fixtures_on_runtime_classpath(root: pathlib.Path) -> str:
    f = root / ROOT_BUILD
    text = f.read_text() if f.is_file() else ""
    if CORE_BUILD_DEPS_ANCHOR not in text:
        raise ForgeError(f"{ROOT_BUILD} has no `dependencies {{` block — re-anchor this forgery")
    f.write_text(text.replace(CORE_BUILD_DEPS_ANCHOR,
                              CORE_BUILD_DEPS_ANCHOR + '    implementation(testFixtures(project(":core")))\n', 1))
    return "the root shipping :core's test fixtures over `implementation` (must be REJECTED — fakes in the fast-jar)"


def forge_fixtures_not_indexed(root: pathlib.Path) -> str:
    f = root / CORE_FIXTURES_BEANS
    if not f.is_file():
        raise ForgeError(f"{CORE_FIXTURES_BEANS} is missing — re-anchor this forgery")
    f.unlink()
    return ":core's test fixtures without a beans.xml (must be REJECTED — the fakes would be no beans)"


def forge_core_fixtures_depend_on_application(root: pathlib.Path) -> str:
    f = root / CORE_BUILD
    text = f.read_text() if f.is_file() else ""
    if CORE_BUILD_DEPS_ANCHOR not in text:
        raise ForgeError(f"{CORE_BUILD} has no `dependencies {{` block — re-anchor this forgery")
    f.write_text(text.replace(CORE_BUILD_DEPS_ANCHOR,
                              CORE_BUILD_DEPS_ANCHOR + '    testFixturesImplementation(project(":apps:memories"))\n', 1))
    return ":core's test fixtures depending on :apps:memories (must be REJECTED — core's tests boot core alone)"


def forge_image_without_build_src(root: pathlib.Path) -> str:
    d = root / DOCKERFILE
    text = d.read_text()
    if BUILD_SRC_COPY not in text:
        raise ForgeError("the Dockerfile has no `COPY backend/buildSrc ./buildSrc` line — re-anchor this forgery")
    d.write_text(text.replace(BUILD_SRC_COPY, ""))
    return "the Dockerfile's build-base stage without backend/buildSrc (must be REJECTED — the warm-up finds no plugin)"


def forge_core_declares_application_package(root: pathlib.Path) -> str:
    f = root / CORE_NODE_FORGERY
    if not f.parent.is_dir():
        raise ForgeError(f"{f.parent.relative_to(root)} is missing — core/node moved")
    f.write_text("package me.raddatz.veyq.memories.media\n\nclass GuardGapProbe\n")
    return "a :core main file declaring the Memories package me.raddatz.veyq.memories.media (must be REJECTED — one module per package)"


def forge_core_names_memory_kind(root: pathlib.Path) -> str:
    # A core rule about a Memories kind, spelled through a generated enum (the import is of the contract's
    # package, which the import rule cannot object to; the contract generates no such enum since `NodeKind` opened). The KDoc beside it names the kind too; only the code line counts.
    f = root / CORE_NODE_FORGERY
    f.parent.mkdir(parents=True, exist_ok=True)
    f.write_text("package me.raddatz.veyq.core.node\n\n"
                 "import me.raddatz.veyq.core.generated.model.NodeKind\n\n"
                 "/** Whether a MEMORY may hold this. */\n"
                 "class GuardGapProbe {\n"
                 "    fun isMemory(kind: String): Boolean = kind == NodeKind.MEMORY.value\n"
                 "}\n")
    return "a core/node file naming NodeKind.MEMORY in code (must be REJECTED — core knows FOLDER only)"


def forge_core_names_memory_blob_role(root: pathlib.Path) -> str:
    # A core rule about a Memories blob role: the contract's role is an open token, so a literal is the only spelling.
    f = root / "backend/core/src/main/kotlin/me/raddatz/veyq/core/blob/GuardGapProbe.kt"
    if not f.parent.is_dir():
        raise ForgeError(f"{f.parent.relative_to(root)} is missing — core/blob moved")
    f.write_text("package me.raddatz.veyq.core.blob\n\n"
                 "/** Whether this session uploads a THUMBNAIL. */\n"
                 "class GuardGapProbe {\n"
                 "    fun isThumbnail(role: String): Boolean = role == \"THUMBNAIL\"\n"
                 "}\n")
    return "a core/blob file naming the blob role \"THUMBNAIL\" in code (must be REJECTED — core names no application role)"


def forge_kernel_imports_slice(root: pathlib.Path) -> str:
    # A kernel package reaching into a slice: the shape the 2026-09-23 review found (tenant kernel classes on the
    # admin feature's tenant entity). Kernel membership is the declared KERNEL list, not a directory.
    f = root / "backend/core/src/main/kotlin/me/raddatz/veyq/core/tenant/GuardGapProbe.kt"
    if not f.parent.is_dir():
        raise ForgeError(f"{f.parent.relative_to(root)} is missing — the tenant kernel package moved")
    f.write_text("package me.raddatz.veyq.core.tenant\n\nimport me.raddatz.veyq.core.admin.tenant.TenantStore\n\n"
                 "class GuardGapProbe\n")
    return "a kernel (tenant) file importing the admin tenant slice (must be REJECTED — the kernel names no slice)"


WEB_GUARD = "validate_web_core_imports_no_applications.py"


def _plant_web(root: pathlib.Path, rel: str, text: str) -> None:
    f = root / "web" / "src" / rel
    if not (root / "web" / "src" / "features" / "memory").is_dir():
        raise ForgeError("web/src/features/memory is gone — re-anchor this forgery on the application directory")
    f.parent.mkdir(parents=True, exist_ok=True)
    f.write_text(text)


def forge_web_core_imports_application(root: pathlib.Path) -> str:
    _plant_web(root, "shared/ui/GuardGapProbe.vue",
               "<script setup lang=\"ts\">\nimport { loadTree } from '../../features/memory/memoryNodes'\n"
               "void loadTree\n</script>\n")
    return "a shared/ui file importing features/memory/memoryNodes (must be REJECTED — core depends on no application)"


def forge_web_core_reexports_application(root: pathlib.Path) -> str:
    _plant_web(root, "shared/guardGapProbe.ts", "export * from '../features/memory/memoryNodes'\n")
    return "a core file re-exporting the application with `export * from` (must be REJECTED)"


def forge_web_core_dynamic_imports_application(root: pathlib.Path) -> str:
    _plant_web(root, "shared/guardGapProbe.ts",
               "export const lazy = () => import('../features/memory/memoryNodes')\n")
    return "a core file importing the application with `import('…')` (must be REJECTED)"


def forge_web_composition_root_sibling(root: pathlib.Path) -> str:
    _plant_web(root, "applications2.ts", "import './features/memory/memoryNodes'\n")
    return "a sibling of the composition root importing the application (the exemption is the exact path; REJECTED)"


def forge_web_core_multiline_import(root: pathlib.Path) -> str:
    # The `} from '…'` line of a multi-line import does not start with `import`: the specifier regex must still see it.
    _plant_web(root, "shared/guardGapProbe.ts",
               "import {\n  loadTree,\n  resetTree,\n} from '../features/memory/memoryNodes'\nvoid [loadTree, resetTree]\n")
    return "a core file importing the application through a multi-line import (must be REJECTED)"


def forge_web_core_side_effect_import(root: pathlib.Path) -> str:
    _plant_web(root, "shared/guardGapProbe.ts", "import '../features/memory/memoryNodes'\n")
    return "a core file importing the application for its side effects, `import '…'` (must be REJECTED)"


def forge_web_core_names_memory_kind(root: pathlib.Path) -> str:
    _plant_web(root, "shared/guardGapProbe.ts",
               "import { NodeKind } from '../api/generated'\nexport const k = NodeKind.Memory\n")
    return "a core file naming NodeKind.Memory (must be REJECTED — the shell asks the registry)"


def forge_web_core_glob(root: pathlib.Path) -> str:
    _plant_web(root, "shared/guardGapProbe.ts", "export const all = import.meta.glob('../features/*/index.ts')\n")
    return "a core file discovering code with import.meta.glob (must be REJECTED)"


def _plant_depot(root: pathlib.Path, rel: str, text: str) -> None:
    if not (root / "web" / "src" / "features" / "depot").is_dir():
        raise ForgeError("web/src/features/depot is gone — re-anchor this forgery on the Depot application directory")
    _plant_web(root, rel, text)


def forge_web_application_imports_application(root: pathlib.Path) -> str:
    _plant_depot(root, "features/depot/guardGapProbe.ts",
                 "import { MEMORY_KIND } from '../memory/kinds'\nexport const k = MEMORY_KIND\n")
    return "a features/depot file importing features/memory/kinds (must be REJECTED — no application → application)"


def forge_web_application_imports_application_client(root: pathlib.Path) -> str:
    _plant_depot(root, "features/depot/guardGapProbe.ts",
                 "import type { MediaItem } from '../../api/generated/media'\nexport type M = MediaItem\n")
    return ("a features/depot file importing Memories' generated media client (must be REJECTED — the client is "
            "Memories', derived from contracts/api/memories/)")


def forge_web_core_imports_second_application(root: pathlib.Path) -> str:
    _plant_depot(root, "core/node/guardGapProbe.ts", "export { FILE_KIND } from '../../features/depot/kinds'\n")
    return "a core/node file re-exporting features/depot/kinds (must be REJECTED — core depends on no application)"


def forge_web_core_names_file_kind(root: pathlib.Path) -> str:
    _plant_depot(root, "core/node/guardGapProbe.ts", "export const isFile = (kind: string) => kind === 'FILE'\n")
    return "a core/node file naming the Depot node kind 'FILE' (must be REJECTED — the shell asks the registry)"


def forge_web_core_reads_application_i18n(root: pathlib.Path) -> str:
    _plant_web(root, "core/node/guardGapProbe.ts",
               "export const title = (t: (k: string) => string) => t('memory.deleteMemoryTitle')\n")
    return "a core/node file reading Memories' i18n key 'memory.deleteMemoryTitle' (must be REJECTED — core names no application)"


DEPOT_BUILD = "backend/apps/depot/build.gradle.kts"


def forge_depot_depends_on_memories(root: pathlib.Path) -> str:
    f = root / DEPOT_BUILD
    if not f.is_file():
        raise ForgeError(f"{DEPOT_BUILD} is missing — the Depot module moved or was deleted")
    text = f.read_text()
    anchor = '    implementation(project(":core"))\n'
    if anchor not in text:
        raise ForgeError(f"{DEPOT_BUILD} no longer declares `implementation(project(\":core\"))` — re-anchor")
    f.write_text(text.replace(anchor, anchor + '    implementation(project(":apps:memories"))\n', 1))
    return ":apps:depot depending on :apps:memories (must be REJECTED — no application → application)"


def _compose_in_root(root: pathlib.Path, project: str) -> None:
    """Compose a forged module into the root build, as every included module must be (rule (a): it ships)."""
    f = root / ROOT_BUILD
    text = f.read_text() if f.is_file() else ""
    if CORE_BUILD_DEPS_ANCHOR not in text:
        raise ForgeError(f"{ROOT_BUILD} has no `dependencies {{` block — re-anchor this forgery")
    f.write_text(text.replace(CORE_BUILD_DEPS_ANCHOR,
                              CORE_BUILD_DEPS_ANCHOR + f'    implementation(project("{project}"))\n', 1))


def forge_root_drops_depot_module(root: pathlib.Path) -> str:
    f = root / ROOT_BUILD
    edge = '    implementation(project(":apps:depot"))\n'
    if not f.is_file() or edge not in f.read_text():
        raise ForgeError(f"{ROOT_BUILD} no longer composes :apps:depot with `implementation(...)` — re-anchor")
    f.write_text(f.read_text().replace(edge, "", 1))
    return "the root build no longer composing :apps:depot (must be REJECTED — an included module that never ships)"


def forge_unmounted_config_secret(root: pathlib.Path) -> str:
    f = root / PROD_COMPOSE
    if not f.is_file():
        raise ForgeError(f"{PROD_COMPOSE} is missing — the production deployment artifact moved or was deleted")
    f.write_text(_sub(f.read_text(), CONFIG_SECRET_MOUNT, ""))
    return "the app's config-secret volume removed (the KEK would not survive a container recreate)"


def forge_tenant_suspend_without_reproject(root: pathlib.Path) -> str:
    # One of the five routability-changing tenant operations stops re-projecting the reverse proxy's routing.
    # This is the shape the defect actually takes: nothing else changes, the tenant row is still correct, and
    # neither the compiler nor any type notices — a suspended tenant simply keeps resolving. The `suspend` arm is
    # chosen over `create` because its damage points the wrong way (a tenant taken offline that is still
    # reachable), and because it is the one an author is most likely to think of as "only a status flip".
    f = root / TENANT_HANDLER
    if not f.is_file():
        raise ForgeError(f"{TENANT_HANDLER} is missing — tenant administration moved or was deleted")
    src = f.read_text()
    if TENANT_REPROJECT_CALL not in src:
        raise ForgeError("the suspend arm's reproject() call drifted — re-anchor this forgery")
    f.write_text(_sub(src, TENANT_REPROJECT_CALL, ""))
    return "a tenant suspend that never re-projects the routing (must be REJECTED — the domain keeps resolving)"


def forge_run_config_env_path_into_the_void(root: pathlib.Path) -> str:
    # validate_run_configs resolves a configuration's SUBJECT and never an env value, so add an env value naming
    # a path that does not exist and it still reports green. The forgery brings its own entry rather than bending
    # an existing one: env values are developer-tunable lines, and since the dev-invite file left (inbox #285)
    # none of them is a `$PROJECT_DIR$` path at all — pinning one's spelling would turn an innocent edit red.
    env_map = re.compile(r"(<envs>|<map>)")
    for f in sorted((root / ".run").glob("*.xml")):
        src = f.read_text()
        if env_map.search(src):
            probe = '<entry key="VEYQ_GUARD_GAP_PROBE" value="$PROJECT_DIR$/does-not-exist/guard-gap-probe.jsonl" />'
            if "<envs>" in src:
                probe = probe.replace("<entry key=", "<env name=")
            f.write_text(env_map.sub(lambda m: m.group(1) + probe, src, count=1))
            return ("a run configuration's env value pointing at a path that does not exist "
                    "(the env-values-unresolved open gap)")
    raise ForgeError("no .run/*.xml carries an env map — re-anchor this forgery")


def forge_host_read_through_an_alias(root: pathlib.Path) -> str:
    # validate_lookup_single_home matches the header name as a literal AT the call (or anywhere, for the forwarded
    # header). Hold the plain header's name in a constant and read through it, and no spelling matches: the call
    # carries no literal and the literal sits in no call. The name is assembled here, not written, so this file
    # does not carry a copy of the shape it forges.
    f = root / RECOVERY_RESOURCE
    if not f.is_file():
        raise ForgeError(f"{RECOVERY_RESOURCE} is missing — the recovery resource moved or was deleted")
    header = "Ho" + "st"
    probe = (
        f'private const val HOST_ALIAS = "{header}"\n\n'
        "/** A guard-gap probe: a Host read through an alias constant. */\n"
        "private fun aliasedHost(req: io.vertx.core.http.HttpServerRequest): String? = req.getHeader(HOST_ALIAS)\n\n"
    )
    f.write_text(_sub(f.read_text(), RECOVERY_RESOURCE_ANCHOR, probe + RECOVERY_RESOURCE_ANCHOR))
    return "a Host header read through an alias constant (the data-flow open gap)"


def forge_dead_class_inside_a_selector(root: pathlib.Path) -> str:
    # validate_stylesheet_anchors judges a selector's ANCHOR compound and nothing deeper, so a class no markup
    # applies is green wherever it sits behind a combinator. The live anchor is read out of the sheet rather
    # than spelled here: pinning one class name would turn a rename into a red guard, which is the opposite of
    # what a gap-pin is for.
    sheet = root / "web" / "src" / "styles" / "ui.css"
    if not sheet.is_file():
        raise ForgeError("web/src/styles/ui.css is missing — the shared primitives sheet moved or was deleted")
    src = sheet.read_text()
    m = re.search(r"^\.([a-z][a-z0-9-]*)\s*\{", src, re.M)
    if not m:
        raise ForgeError("no class-anchored rule in ui.css — re-anchor this forgery")
    sheet.write_text(src + "\n.%s .guard-gap-never-applied {\n  color: red;\n}\n" % m.group(1))
    return ("a class no markup applies, behind a combinator rather than at the anchor "
            "(the anchor-only open gap)")


def forge_class_anchored_only_by_a_test(root: pathlib.Path) -> str:
    # inbox #362: a rule whose class only a TEST spells (an assertion that a button does NOT carry it) stayed
    # "anchored" until validate_stylesheet_anchors stopped reading test evidence as markup. The test file is
    # found, not named, so a renamed test cannot turn this case red.
    sheet = root / "web" / "src" / "styles" / "ui.css"
    if not sheet.is_file():
        raise ForgeError("web/src/styles/ui.css is missing — the shared primitives sheet moved or was deleted")
    tests = sorted((root / "web" / "src").rglob("*.test.ts"))
    if not tests:
        raise ForgeError("no *.test.ts under web/src — re-anchor this forgery")
    sheet.write_text(sheet.read_text() + "\n.guard-gap-only-a-test-names-this {\n  display: none;\n}\n")
    tests[0].write_text(tests[0].read_text()
                        + "\n// expect(button.classes()).not.toContain('guard-gap-only-a-test-names-this')\n")
    return "a CSS rule whose class only a test file spells (must be REJECTED — a test is not markup)"


def forge_admin_web_primary_lightened(root: pathlib.Path) -> str:
    # The defect that bought the guard, put back on the copy it did not read at first: admin-web's light primary
    # set to Aura's emerald-500 under white text (2.54:1). The token's current value is matched, not spelled, so
    # a palette tweak that stays at AA does not turn this case red.
    sheet = root / "admin-web" / "src" / "styles" / "base.css"
    if not sheet.is_file():
        raise ForgeError("admin-web/src/styles/base.css is missing — the admin surface's token sheet moved")
    src, n = re.subn(r"(--app-primary:\s*)#[0-9a-fA-F]{3,6};", r"\g<1>#10b981;", sheet.read_text(), count=1)
    if not n:
        raise ForgeError("no --app-primary hex in admin-web's base.css — re-anchor this forgery")
    sheet.write_text(src)
    return "admin-web's light --app-primary back on Aura's emerald-500 under white text (2.54:1)"


def _web_comment_citing(root: pathlib.Path, feature: str) -> None:
    # A new file rather than an edit: the probe needs no anchor in existing source, so it cannot drift. The id is
    # assembled, not written, so this guard's own source carries no dangling id for anyone to copy.
    probe = root / "web" / "src" / "guardGapProbe.ts"
    probe.write_text("// " + ".".join(["uc", feature, "guard-gap-no-such-doc"]) + " (web) — a guard-gap probe\n"
                     "export const guardGapProbe = 1\n")


def forge_feature_id_dangles(root: pathlib.Path) -> str:
    if not (root / "docs" / "features" / "memory").is_dir():
        raise ForgeError("docs/features/memory is gone — re-anchor this forgery on a live feature directory")
    _web_comment_citing(root, "memory")
    return "a web/src comment citing a use-case id under a real feature that names no doc"


def forge_feature_id_under_unknown_feature(root: pathlib.Path) -> str:
    feature = "guard-gap-no-such-feature"
    if (root / "docs" / "features" / feature).exists():
        raise ForgeError(f"docs/features/{feature} exists — pick another non-feature name")
    _web_comment_citing(root, feature)
    return "a web/src comment citing a use-case id under a feature that does not exist (the anchor open gap)"


SITE_PAGES = pathlib.Path("site/src/content/docs")


def _site_sources(page: pathlib.Path) -> list[str]:
    # Read as text, in the block-list shape the pages use (`sources:` then `  - <path>` lines); a page written
    # another way is a re-anchor, never a silently skipped case.
    m = re.match(r"---\n(?:.*\n)*?sources:\n((?:  - .+\n)+)(?:.*\n)*?---\n", page.read_text())
    if not m:
        raise ForgeError(f"{page.name} declares no `sources:` block list — re-anchor this forgery")
    return [line[len("  - "):] for line in m.group(1).splitlines()]


def forge_wiki_pair_declares_fewer(root: pathlib.Path) -> str:
    # The page is read out of the tree, not named: any German page whose frontmatter declares two docs or more.
    for page in sorted((root / SITE_PAGES / "de").rglob("*.md")):
        if page.name == "index.md" and page.parent == root / SITE_PAGES / "de":
            continue  # the landing page declares nothing
        sources = _site_sources(page)
        if len(sources) >= 2:
            page.write_text(_sub(page.read_text(), f"\n  - {sources[-1]}\n", "\n"))
            return f"{SITE_PAGES}/de/{page.relative_to(root / SITE_PAGES / 'de')} declares one doc fewer than its English page"
    raise ForgeError(f"no {SITE_PAGES}/de page declares two docs or more — re-anchor this forgery")


def forge_wiki_backlink_removed(root: pathlib.Path) -> str:
    # Every markdown link from the first declared docs/ source back to its English page is unlinked (kept as
    # text): one link left standing would keep the guard green, which is a bite that never landed.
    for page in sorted((root / SITE_PAGES).glob("*.md")):
        if page.name == "index.md":
            continue  # the landing page declares nothing
        for s in _site_sources(page):
            doc = root / s
            if not (s.startswith("docs/") and doc.is_file()):
                continue
            link = re.compile(r"\[([^\]]*)\]\(([^)\s]+)\)")

            def unlink(m: re.Match) -> str:
                target = m.group(2).split("#", 1)[0]
                return m.group(1) if target and (doc.parent / target).resolve() == page.resolve() else m.group(0)

            text = doc.read_text()
            stripped = link.sub(unlink, text)
            if stripped == text:
                raise ForgeError(f"{s} carries no link to {SITE_PAGES}/{page.name} to remove")
            doc.write_text(stripped)
            return f"{s} no longer links back to {SITE_PAGES}/{page.name}"
    raise ForgeError(f"no English page under {SITE_PAGES} declares a docs/ source — re-anchor this forgery")


def forge_wiki_prose_contradicts_doc(root: pathlib.Path) -> str:
    page = root / SITE_PAGES / "account-keys-and-recovery.md"
    if not page.is_file():
        raise ForgeError(f"{SITE_PAGES}/account-keys-and-recovery.md is gone — re-anchor this forgery")
    page.write_text(page.read_text() + "\nTo log in, your password is sent to the server.\n")
    return "a site page claiming the password is sent to the server, against the key model it declares"


WRITE_CONCURRENCY = "validate_write_concurrency.py"


def _in_operation(root: pathlib.Path, spec: str, operation_id: str, old: str, new: str, *, every: bool = False) -> None:
    """Replace `old` inside one operation's block (from its `operationId` to the next one) — anchored on the id, so
    the same text in a neighbouring operation is never what the forgery touches."""
    f = root / "contracts" / "api" / spec
    lines = f.read_text().split("\n")
    try:
        begin = lines.index(f"      operationId: {operation_id}")
    except ValueError:
        raise ForgeError(f"{spec}: operation {operation_id} not found (renamed?)")
    # The operation's own block opens two lines up (`  /path:` → `    patch:`); the extension sits right below the id.
    begin = max(0, begin - 2)
    end = next((j for j in range(begin + 3, len(lines)) if re.match(r"^ {0,4}\S", lines[j])), len(lines))
    block = "\n".join(lines[begin:end])
    if old not in block:
        raise ForgeError(f"{spec} {operation_id}: anchor not found (drifted?): {old!r}")
    block = block.replace(old, new) if every else block.replace(old, new, 1)
    lines[begin:end] = block.split("\n")
    f.write_text("\n".join(lines))


def forge_concurrency_declaration_dropped(root: pathlib.Path) -> str:
    # The second declaration, so the first still stands: one declared field must not cover another.
    _in_operation(root, "content.openapi.yaml", "updateNode",
                  "          - field: placementVersion\n"
                  "            requiredWith: [encryptedName, parentId, wrappedContentKey, slugToken]\n"
                  "            conflictCode: stale_placement\n", "")
    return "updateNode's placementVersion declaration dropped, metaVersion's kept (must be REJECTED)"


def forge_second_version_field_undeclared(root: pathlib.Path) -> str:
    _in_request_property(root, "content.openapi.yaml", "UpdateNodeRequest", "metaVersion",
                         "        metaVersion:\n", "        shadowVersion:\n          type: integer\n"
                         "          format: int64\n          example: 0\n        metaVersion:\n")
    return "UpdateNodeRequest gains a version-shaped shadowVersion no declaration names (must be REJECTED)"


def forge_conflict_code_unnamed(root: pathlib.Path) -> str:
    # Only the declaration's own text: the 409 still names stale_version, so one named code must not cover another.
    _in_operation(root, "content.openapi.yaml", "updateNode", "(`stale_placement`", "(`stale_plac3ment`")
    return "updateNode's 409 no longer names its declared conflict code stale_placement (must be REJECTED)"


DEMO_SPEC = "contracts/api/demo/demo.openapi.yaml"
SPEC_SETS_KT = "backend/buildSrc/src/main/kotlin/me/raddatz/veyq/buildlogic/SpecSets.kt"
PACKAGE_ROOT_FUN = 'fun packageRootOf(owner: String) = "me.raddatz.veyq.$owner.generated"'


def _plant_demo_spec(root: pathlib.Path) -> None:
    spec = root / DEMO_SPEC
    spec.parent.mkdir(parents=True)
    spec.write_text("openapi: 3.1.0\ninfo:\n  title: demo\n  version: 0.0.1\npaths:\n  /api/demo:\n    get:\n"
                    "      tags: [demo]\n      operationId: getDemo\n      responses:\n        '204':\n"
                    "          description: nothing\n")


def _plant_demo_module(root: pathlib.Path, opt_in: bool) -> None:
    # An application valid in every respect the module guard checks — included, composed by the root, indexed, copied
    # by the Dockerfile, named by the convention, depending on core only — so rule (d) is what judges its specs.
    _compose_in_root(root, ":apps:demo")
    app = root / "backend/apps/demo"
    (app / "src/main/resources/META-INF").mkdir(parents=True)
    (app / "src/main/resources/META-INF/beans.xml").write_text("")
    (app / "build.gradle.kts").write_text(
        'plugins {\n    id("veyq.module")\n    id("veyq.openapi")\n}\n\n'
        + ("veyqOpenApi { generateOwnSpecs() }\n\n" if opt_in else "")
        + 'dependencies {\n    implementation(project(":core"))\n}\n')
    s = root / SETTINGS
    s.write_text(s.read_text() + '\ninclude(":apps:demo")\n')
    d = root / DOCKERFILE
    text = d.read_text()
    if MEMORIES_BUILD_COPY not in text or "backend/apps/memories/src" not in text:
        raise ForgeError(f"the Dockerfile no longer copies {MEMORIES_BUILD_COPY} and its src — re-anchor this forgery")
    text = text.replace(MEMORIES_BUILD_COPY, f"{MEMORIES_BUILD_COPY} backend/apps/demo/build.gradle.kts", 1)
    d.write_text(text.replace("backend/apps/memories/src", "backend/apps/memories/src backend/apps/demo/src", 1))


def forge_spec_directory_without_module(root: pathlib.Path) -> str:
    _plant_demo_spec(root)
    return "contracts/api/demo/ holding a spec with no :apps:demo (must be REJECTED — its specs would generate nothing)"


def forge_spec_directory_module_not_generating(root: pathlib.Path) -> str:
    _plant_demo_spec(root)
    _plant_demo_module(root, opt_in=False)
    return ":apps:demo included but never calling generateOwnSpecs() (must be REJECTED — its directory generates nothing)"


def forge_spec_sets_hardcoded_owner_list(root: pathlib.Path) -> str:
    f = root / SPEC_SETS_KT
    text = f.read_text() if f.is_file() else ""
    if PACKAGE_ROOT_FUN not in text:
        raise ForgeError(f"{SPEC_SETS_KT} has no `{PACKAGE_ROOT_FUN}` — re-anchor this forgery")
    f.write_text(text.replace(PACKAGE_ROOT_FUN,
                              'fun packageRootOf(owner: String) = when (owner) {\n'
                              '            "core" -> "me.raddatz.veyq.core.generated"\n'
                              '            else -> "me.raddatz.veyq.memories.generated"\n'
                              '        }'))
    return "SpecSets.packageRootOf back to a hardcoded owner list (must be REJECTED — owners are derived)"


def forge_module_without_spec_directory(root: pathlib.Path) -> str:
    _plant_demo_module(root, opt_in=True)
    return ":apps:demo generating its own specs with no contracts/api/demo/ (must still PASS — an empty set is refused at generation)"


# Ids must stay unique: parallel work that adds cases picks the next free id at merge time, not at branch time
# (two parallel steps both took the same number once, so renumber on the merge, never in the branch).
# id, target guard, forgery, expect_pass (True = guard passes the forgery = gap still open; False = must reject)
CASES = [
    ("1",  "validate_coverage.py",  forge_web_ref_nontest,      False),
    ("2",  "validate_coverage.py",  forge_backend_empty_fun,    False),
    ("4a", "validate_coverage.py",  forge_coverage_status_done, False),
    ("4b", "validate_coverage.py",  forge_lying_planned,        True),
    ("5",  "validate_changes.py",   forge_cr_done_no_impl,      True),
    ("6",  "validate_doc_links.py", forge_doc_dead_fragment,    True),
    ("7",  "validate_maxlength_storage.py", forge_b64_cap_over_text,          False),
    ("8",  "validate_maxlength_storage.py", forge_freetext_cap_over_column,   True),
    ("9",  "validate_ratelimit_core.py",    forge_ratelimit_state_under_another_name, True),
    ("10", "validate_mutation_trigger_paths.py", forge_scoped_policy_gains_unlisted_dependency, True),
    ("11", "validate_config_secret_persistence.py", forge_unmounted_config_secret, False),
    ("12", "validate_blob_reference_arity.py", forge_fourth_blob_reference_arm, False),
    ("13", "validate_store_reclaim_arity.py", forge_direct_store_delete_bypass, False),
    ("14", "validate_tenant_ingress_triggers.py", forge_tenant_suspend_without_reproject, False),
    ("15", "validate_run_configs.py", forge_run_config_env_path_into_the_void, True),
    ("16", "validate_stylesheet_anchors.py", forge_dead_class_inside_a_selector, True),
    ("17", "validate_coverage.py", forge_backend_ref_env_gated,          False),
    ("18", "validate_coverage.py", forge_backend_tests_never_run_in_ci,  False),
    ("19", "validate_coverage.py", forge_criterion_gap_demoted_surface,  False),
    ("20", "validate_coverage.py", forge_backend_ref_kdoc_phantom,       False),
    ("21", "validate_coverage.py", forge_backend_runtime_assumption,     True),
    ("22", "validate_coverage.py", forge_owed_surface_silent,            False),
    ("23", "validate_coverage.py", forge_admin_use_case_silent_on_admin_web, True),
    ("24", "validate_coverage.py", forge_unadmitted_surface_ref,         False),
    ("25", "validate_lookup_single_home.py", forge_host_read_through_an_alias, True),
    ("26", "validate_request_bounds.py", forge_request_bound_unannotated, False),
    ("27", "validate_request_bounds.py", forge_request_cascade_dropped,   False),
    ("28", "validate_request_bounds.py", forge_request_annotation_drifts, False),
    ("29", "validate_doc_citations.py", forge_feature_id_dangles,            False),
    ("30", "validate_doc_citations.py", forge_feature_id_under_unknown_feature, True),
    ("31", "validate_wiki_sources.py", forge_wiki_pair_declares_fewer,     False),
    ("32", "validate_wiki_sources.py", forge_wiki_backlink_removed,        False),
    ("33", "validate_wiki_sources.py", forge_wiki_prose_contradicts_doc,   True),
    ("34", "validate_backend_module_graph.py", forge_core_depends_on_application, False),
    ("35", "validate_blob_reference_arity.py", forge_contributor_forgets_derivative_arm, False),
    ("36", "validate_scope_erasure_arity.py", forge_media_eraser_drops_a_table, False),
    ("37", "validate_node_subtree_erasure.py", forge_media_node_eraser_deleted, False),
    ("39", WEB_GUARD, forge_web_core_imports_application, False),
    ("40", WEB_GUARD, forge_web_core_reexports_application, False),
    ("41", WEB_GUARD, forge_web_core_dynamic_imports_application, False),
    ("42", WEB_GUARD, forge_web_composition_root_sibling, False),
    ("43", WEB_GUARD, forge_web_core_multiline_import, False),
    ("44", WEB_GUARD, forge_web_core_side_effect_import, False),
    ("45", WEB_GUARD, forge_web_core_names_memory_kind, False),
    ("46", WEB_GUARD, forge_web_core_glob, False),
    ("48", "validate_kernel_imports_no_slices.py", forge_kernel_imports_slice, False),
    ("49", "validate_kernel_imports_no_slices.py", forge_core_names_memory_kind, False),
    ("50", "validate_backend_module_graph.py", forge_application_depends_on_application, False),
    ("51", "validate_backend_module_graph.py", forge_core_declares_application_package, False),
    ("52", "validate_backend_module_graph.py", forge_module_renames_its_jar, False),
    ("53", "validate_backend_module_graph.py", forge_module_without_convention_plugin, False),
    ("54", "validate_backend_module_graph.py", forge_image_without_build_src, False),
    ("55", "validate_backend_module_graph.py", forge_fixtures_on_runtime_classpath, False),
    ("56", "validate_backend_module_graph.py", forge_fixtures_not_indexed, False),
    ("57", "validate_backend_module_graph.py", forge_core_fixtures_depend_on_application, False),
    ("58", "validate_request_bounds.py", forge_request_scalar_ref_unannotated, False),
    ("59", WRITE_CONCURRENCY, forge_concurrency_declaration_dropped, False),
    ("60", WRITE_CONCURRENCY, forge_second_version_field_undeclared, False),
    ("61", WRITE_CONCURRENCY, forge_conflict_code_unnamed, False),
    ("62", "validate_backend_module_graph.py", forge_spec_directory_without_module, False),
    ("63", "validate_backend_module_graph.py", forge_spec_directory_module_not_generating, False),
    ("64", "validate_backend_module_graph.py", forge_spec_sets_hardcoded_owner_list, False),
    ("65", "validate_backend_module_graph.py", forge_module_without_spec_directory, True),
    ("66", "validate_kernel_imports_no_slices.py", forge_core_names_memory_blob_role, False),
    ("67", "validate_blob_reference_arity.py", forge_attachment_contributor_deleted, False),
    ("68", "validate_node_subtree_erasure.py", forge_attachment_node_eraser_deleted, False),
    ("69", "validate_scope_erasure_arity.py", forge_attachment_scope_eraser_drops_its_delete, False),
    ("70", "validate_scope_erasure_arity.py", forge_attachment_scope_eraser_after_blob_rows, True),
    ("71", "validate_node_subtree_erasure.py", forge_attachment_node_eraser_keeps_the_blobs, True),
    ("72", WEB_GUARD, forge_web_application_imports_application, False),
    ("73", WEB_GUARD, forge_web_application_imports_application_client, False),
    ("74", WEB_GUARD, forge_web_core_imports_second_application, False),
    ("75", WEB_GUARD, forge_web_core_names_file_kind, False),
    ("76", "validate_backend_module_graph.py", forge_depot_depends_on_memories, False),
    ("77", "validate_backend_module_graph.py", forge_root_drops_depot_module, False),
    ("78", WEB_GUARD, forge_web_core_reads_application_i18n, False),
    ("79", "validate_web_contrast.py", forge_admin_web_primary_lightened, False),
    ("80", "validate_request_bounds.py", forge_request_list_elements_unbounded, False),
    ("81", "validate_stylesheet_anchors.py", forge_class_anchored_only_by_a_test, False),
]


def run_guard(root: pathlib.Path, script: str) -> int:
    return subprocess.run(
        [sys.executable, str(root / "scripts" / script)],
        capture_output=True, text=True,
    ).returncode


def source_files() -> list[str]:
    """Every file git tracks, plus every untracked one it does not ignore — the tree the validators are about.

    `.git` is not among them, and need not be: no target guard reads history. A directory entry (git lists an
    untracked nested repository or worktree as `path/`) is not a file and is skipped, as is a tracked file
    deleted in the working tree.
    """
    out = subprocess.run(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
                         cwd=ROOT, capture_output=True, text=True)
    if out.returncode != 0:
        raise SystemExit(f"GUARD-GAP VALIDATION FAILED: `git ls-files` failed, so there is no tree to copy and "
                         f"no case was checked: {out.stderr.strip()}")
    files = sorted({p for p in out.stdout.split("\0") if p and ((ROOT / p).is_file() or (ROOT / p).is_symlink())})
    if not files:
        raise SystemExit("GUARD-GAP VALIDATION FAILED: `git ls-files` listed no file, so no case was checked")
    return files


def snapshot() -> pathlib.Path:
    """Copy the source tree once; every case copies this, so all of them see the same tree."""
    tmp = pathlib.Path(tempfile.mkdtemp(prefix="guardgap_snapshot_"))
    made: set[pathlib.Path] = set()
    for rel in source_files():
        dst = tmp / rel
        if dst.parent not in made:
            dst.parent.mkdir(parents=True, exist_ok=True)
            made.add(dst.parent)
        shutil.copy2(ROOT / rel, dst, follow_symlinks=False)
    return tmp


def copy_tree(snap: pathlib.Path) -> pathlib.Path:
    tmp = pathlib.Path(tempfile.mkdtemp(prefix="guardgap_"))
    shutil.copytree(snap, tmp / "repo", symlinks=True)
    return tmp


def green_unforged(snap: pathlib.Path, script: str, verdicts: dict[str, bool]) -> bool:
    """Is `script` green on an un-forged copy? Asked once per guard: every copy of one snapshot is the same tree."""
    if script not in verdicts:
        tmp = copy_tree(snap)
        try:
            verdicts[script] = run_guard(tmp / "repo", script) == 0
        finally:
            shutil.rmtree(tmp, ignore_errors=True)
    return verdicts[script]


def check_case(snap: pathlib.Path, verdicts: dict[str, bool],
               case_id: str, script: str, forge, expect_pass: bool) -> str | None:
    """Return an error string if the documented pass/fail no longer holds, else None."""
    if not green_unforged(snap, script, verdicts):
        return (f"case {case_id}: {script} is already RED on the un-forged copy — cannot trust the "
                f"forgery result (fix the underlying red first)")
    tmp = copy_tree(snap)
    try:
        root = tmp / "repo"
        try:
            planted = forge(root)
        except ForgeError as e:
            return f"case {case_id}: could not apply forgery — {e}. Update this guard's anchors."
        passed = run_guard(root, script) == 0
        if passed != expect_pass:
            want = "still pass (gap open)" if expect_pass else "reject it"
            now = "passed" if passed else "was rejected"
            return (f"case {case_id}: {script} should {want} for [{planted}], but it {now}. "
                    f"If a validator was deliberately tightened, move the SKILL.md gap-map and this "
                    f"guard's CASES table together — do not just relax this check.")
        return None
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def main() -> int:
    # `--case ID` (repeatable) runs only the named cases: a narrow loop while writing a forgery. The full run, with
    # no argument, is what CI and the trust-chain loop do.
    wanted = {a for a in sys.argv[2:]} if sys.argv[1:2] == ["--case"] else None
    cases = [c for c in CASES if wanted is None or c[0] in wanted]
    if wanted is not None and {c[0] for c in cases} != wanted:
        raise SystemExit(f"GUARD-GAP VALIDATION FAILED: unknown case id(s) {sorted(wanted - {c[0] for c in cases})}")
    snap = snapshot()
    try:
        verdicts: dict[str, bool] = {}
        errors = [e for c in cases if (e := check_case(snap, verdicts, *c))]
    finally:
        shutil.rmtree(snap, ignore_errors=True)
    if errors:
        print("GUARD-GAP VALIDATION FAILED:")
        for e in errors:
            print("  -", e)
        return 1
    print(f"guard gaps OK: {len(cases)} pinned case(s) hold (closed holes stay rejected, open gaps stay open)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
