---
id: cr-sidebar-favorites-show-their-cover
title: A favorite in the sidebar shows its cover — and one cover cache serves every surface
status: planned
satisfies: [uc.memory.cover]
adrs: ["0012", "0024"]
invariants: []
---

## Goal

[uc.memory.cover](../docs/features/memory/use-cases/uc.memory.cover.md) promises a chosen cover is *"shown
identically wherever the Memory appears"* and then enumerates three places: the browse card, the detail header,
the map pin. There is a **fourth** — the sidebar's Favorites section — and it was never wired: `AppSidebar.vue`
renders a hardcoded `pi pi-images` tile for every pinned Memory, whatever cover that Memory carries. The tell
that this is an omission rather than a decision is in the stylesheet: `layout.css` already carries
`.sidebar-favorite-cover .memory-cover-image` / `… img` sizing rules and three `.mobile-nav-favorite` rules,
and **none of those selectors matches anything in the markup today**. The styling for the cover shipped; the
cover did not.

Wiring it means a second caller of `loadNodeCover`, which today has exactly one. Rather than copy the browse
grid's resolve-and-cache loop into the rail, this CR lifts it into one **cover store** that both read from — so
a cover is decrypted once per Memory per scope instead of once per surface, and the always-mounted sidebar
never has to hold key material to show one. Web-only: no contract, no backend, no wire change.

## Context & constraints

- Use-case: [uc.memory.cover](../docs/features/memory/use-cases/uc.memory.cover.md). Its enumeration of surfaces
  is the thing this CR changes — in the doc *and* in the code. The **"no auto-cover"** rule (E1) is untouched:
  a favorite without a cover keeps showing exactly the placeholder it shows today, which is why
  `MemoryCoverImage`'s placeholder slot is the right component and not a new one.
- Enabler: [en.memory.favorites](../docs/features/memory/enablers/en.memory.favorites.md). Its main-flow step 3
  (*"the sidebar resolves the pinned ids against the local decrypted tree"*) stops at the name; it gains the
  cover.
- ADR [0012](../docs/decisions/0012-content-key-hierarchy.md) (K_mem seals node content) and
  [0024](../docs/decisions/0024-scope-key-rotation.md) (a node opens under **its own** epoch's `K_SC`). Both are
  why the store — not the sidebar — owns key handling: `nodeContentKey` peels the epoch chain, and that chain
  must stay out of the rail's component scope.
- **Operator-blindness is untouched.** Nothing is added to or removed from the wire; the covers rendered here
  are the same ciphertext the browse grid already fetches, decrypted on device. `invariants:` is empty
  deliberately — examined, not skipped.
- **Key material stays outside Vue reactivity** (`activeSpace.ts`'s standing trap). `MemoryBrowse` honors this
  by resolving `nodeContentKey` off the reactive proxy; the store must do the same, and it is the *only* place
  that has to, which is half the point of extracting it.

### Locators — run them; the lists below are not a tally

**L1 — the claim**, the three-surface enumeration that becomes four (`uc.memory.place` / `uc.memory.date` /
ADR 0022 hits are unrelated noise — they name a detail header for their own reasons):

```bash
grep -rn "detail header" docs/ web/src --include='*.md' --include='*.yaml' --include='*.ts' --include='*.vue' | grep -v node_modules
```

**L2 — the symbols**, which L1 cannot see (a component that resolves or renders a cover states no claim):

```bash
grep -rn "loadNodeCover\|MemoryCoverImage\|LoadedCover\|coverImageStyle" web/src --include='*.ts' --include='*.vue' | grep -v '\.test\.'
```

L2 is the angle that carries the surprise: `SpaceMap.vue` does **not** use `MemoryCoverImage` — it re-implements
the crop box inline from `coverImageStyle`. So "one shared display formula" is true and "one shared component"
is not, and this CR adds a `MemoryCoverImage` user rather than a fourth inline crop box.

**L3 — the dead CSS hooks**, the set this CR's whole point is to make live. A comparison, not a reading:

```bash
for c in sidebar-favorite-cover mobile-nav-favorite memory-cover-image; do \
  printf '%-24s css=%s markup=%s\n' "$c" \
    "$(grep -rl "$c" web/src/styles | wc -l | tr -d ' ')" \
    "$(grep -rl "$c" web/src --include='*.vue' --include='*.ts' | grep -v styles | wc -l | tr -d ' ')"; done
```

**Done-when for L3 is `markup>0` for all three** — `mobile-nav-favorite` is at `markup=0` today.

## Phases

- [ ] **1 web — one cover cache, shared** · a new `web/src/features/memory/coverStore.ts`, beside `treeStore.ts`
      and owned by the same lifecycle (`activeSpace.enterScope` already *"resets the tree + favorites so no stale
      per-scope state bleeds across a switch"* — the cover map joins that reset).
      - API: `ensureCovers(nodeIds: string[])` resolves what is missing (scope + epochs read from `activeSpace`,
        `K_mem` via `nodeContentKey(epochs, treeStore.nodes, id)`, bytes via the unchanged `loadNodeCover`), and
        a reactive `covers: Ref<Map<string, LoadedCover>>` the surfaces read. A node whose key is not openable
        yet stays **un-tried** so a later pass retries — `MemoryBrowse`'s existing rule, moved not changed.
      - **The trap the extraction introduces, and its dissolution.** Today the cache is per-visit by accident:
        `MemoryHomeView` renders `MemoryDetail` `v-if` / `MemoryBrowse` `v-else`, so opening a Memory unmounts
        the grid and its cache, and returning re-resolves. A module-level store outlives that — so a cover set
        in the detail header would leave a stale one on the card it came from *and* in the rail. Fix it in the
        cache **key**, not with an invalidation call: the entry records the `wrappedMeta` string it was resolved
        from, and a changed meta re-resolves. `patchNodeMeta` already writes the new blob into the tree, so this
        needs no new signal and cannot be forgotten at a future call site. It over-invalidates (a date edit
        re-decrypts the cover) — accepted: rare, and one thumbnail.
      - `useObjectUrls()` is the one owner of object-URL lifecycle (its own header says so), but it registers
        `onBeforeUnmount` unconditionally and the store has no component. Guard that hook with
        `getCurrentInstance()` rather than hand-rolling a second revoke dance in the store — a second
        implementation is exactly what that composable exists to prevent. `resetCovers()` then calls `clear()`
        itself, and every existing component caller is unaffected.
      - `MemoryBrowse.vue` loses `coverUrls` / `coverCrops` / `coverTried` / its local `covers` computed and
        calls the store instead. `MemoryCard` and `SpaceMap` keep their props — the store hands them the same
        `Map<string, LoadedCover>`.
      - **Bites.** Two, both by injection against the finished store, because neither defect is live yet:
        (a) staleness — resolve a cover, change the node's `wrappedMeta`, assert the cover re-resolves; bite by
        keying the entry on `nodeId` alone, which must go **red**. (b) reset — assert a scope switch revokes
        every held URL and empties the map; bite by deleting the `resetCovers()` call in `enterScope`, red.
        Lever (b) is a call site this CR adds and no later phase touches, so it survives phase 2.
      · **Done when:** `npm run check` green in `web/`, and **L2 re-run shows `loadNodeCover` with exactly one
      caller** (the store).

- [ ] **2 web — the rail renders it** ·
      - `AppSidebar.vue`, desktop: `<MemoryCoverImage :src :crop :alt="memory.name" />` inside the existing
        `.sidebar-favorite-cover` span, replacing the hardcoded `pi pi-images`. The component's own placeholder
        slot covers the no-cover case, so E1 needs no branch in the rail.
      - `AppSidebar.vue`, mobile: the bottom-sheet row is poorer than the desktop one — it shows a
        `sidebar-scope-icon` glyph, not a cover tile at all. It gains the same `.sidebar-favorite-cover` span
        **and** the `mobile-nav-favorite` class, which is what makes `layout.css`'s three orphaned rules live.
      - A `watch` on the favorites set + `activeSpace.epoch` + the tree feeds `ensureCovers`. The rail imports
        no key API — that is a property worth keeping, not an accident of this diff.
      - The component header comment (`AppSidebar.vue:6`, *"Favorites + groups are later slices"*) is false on
        both counts today; correct it to describe what the rail actually carries.
      - **Bites.** Both **live defects**, so the order is test → red → fix → green: a favorite with a cover
        renders an `<img>` under `.sidebar-favorite-cover` (red today — the template can only emit the glyph),
        and the mobile row carries `mobile-nav-favorite` (red today — L3 says `markup=0`). There is **no
        existing sidebar-favorites test to strengthen**: `AppSidebar.test.ts` mentions favorites only in its
        i18n stub, so this is new evidence, and evidence this CR cites is evidence this CR proves it can
        disarm.
      · **Done when:** `npm run check` green, and **L3 re-run shows `markup>0` for all three classes**.

- [ ] **3 docs — the fourth surface is named where the promise is made** · the doc follows the code here (the
      code was behind the doc's *spirit* and matched its *letter*; both now say four). Per site, the shape:
      - `uc.memory.cover.md` — the **Goal** and **Postconditions** enumerations, main-flow **step 4**, **E1**
        (*"all three places"*), **E2** (*"never a broken card/pin"*), and the *"One normalized crop"* quality
        note. Each is an enumeration to extend, not prose to rewrite.
      - `docs/features/memory/_index.md:70` — the same enumeration, one line.
      - `en.memory.favorites.md` step 3 — the pinned id resolves to a name **and its cover**.
      - `memoryCover.ts:15` + `:179`, `MemoryCoverImage.vue:5` — the three code comments that enumerate the
        surfaces. A comment that lists three of four is the next reader's wrong map.
      - **Read `uc.memory.cover.md` end to end, including the bilingual reader-facing section** — *"its card,
        its page, and its pin on the map"* / *"auf ihrer Karte, ihrer Seite und ihrem Pin"* is the same claim in
        user language, where a fragment grep is blind. It needs the favorites entry too, in both languages.
      · **Done when:** `python3 scripts/validate_doc_links.py` and `python3 scripts/validate_changes.py` green,
      and **L1 re-run shows no surviving three-surface enumeration** outside the unrelated `uc.memory.place` /
      `uc.memory.date` / ADR 0022 hits.

- [ ] **4 coverage** · `docs/coverage/features/memory/use-cases/uc.memory.cover.yaml`:
      - `one-crop-everywhere` and `deliberate-no-auto-cover` — both AC texts enumerate surfaces (*"the browse
        card, the detail header, and the map pin"*, *"every surface"*); extend them and add the new sidebar test
        to their `web.unit` lists.
      - **New AC `covers-resolved-once-per-scope`** — *one store resolves a Memory's cover for every surface,
        re-resolves it when the node's sealed meta changes, and releases every decrypted URL on a scope switch.*
        This is the property phase 1 exists for and the one that rots first; it is covered by phase 1's two
        bitten tests.
      - The file's header comment ends at cr-0073 and describes a three-surface feature; it is **replaced**, not
        appended to.
      · **Done when:** `python3 scripts/validate_coverage.py` green.

## Definition of done

**Baseline, run before the first edit** — produced, not remembered, so a red afterwards is attributable here
(measured while writing this CR: **32 guards, all green** — but only on the second run. The first reported
`validate_guard_gaps.py` RED, and it went green ~10 min later with nothing in `scripts/` or `docs/` changed by
this CR in between. The likely cause, stated as likely: that guard **copies the working tree** and asserts the
target validator is green on the un-forged copy first, so a parallel session writing into the same tree during
the copy produces a false red. If the baseline below comes back red on exactly that guard, re-run it once
before believing it):

```bash
for f in scripts/validate_*.py; do python3 "$f" >/dev/null 2>&1 || echo "RED (pre-existing): $f"; done
ls scripts/validate_*.py | wc -l
```

- `npm run check` green in `web/`. `./gradlew check` is **not** in scope: no backend file is touched.
- Every `scripts/validate_*.py` green, count unchanged against the baseline — this CR **adds no guard**, so the
  count cannot be confirmed by the CR's own work.
- `docs/coverage/` updated for `uc.memory.cover` — two extended ACs plus the new one, each with a test behind it.
- **Why no permanent guard, honestly.** *"Every surface that shows a Memory shows its cover"* has no mechanical
  predicate — "a surface" is not a grep-able thing. The nearest one that **would** have caught this bug is real
  and tempting: *a class selector defined in `web/src/styles/` with no user in the markup* (L3, which found
  `mobile-nav-favorite` sitting dead). It is deliberately **not** built here, because its honest form needs an
  exception list — PrimeVue `.p-*` overrides, state classes applied by the router, classes composed in a
  `:class` expression — and a guard carrying a hand-maintained exception list over a UI convention costs more
  than it holds. It is filed as its own question instead — **inbox #140**, which carries the measurement and
  names what has to be decided (a predicate that *derives* its exceptions) before anyone builds it.
- One **hand pass in the running app**: a favorite with a cover, a favorite without, and a cover *changed in the
  detail header* — the third is the one the component tests see only through the store's key, not through the
  rendered rail, and it is the regression phase 1's trap is about. Narrow viewport included, for the mobile row.

## Retrospective

_To be written at close — planning, execution, and process/collaboration (were the agreed conventions honored:
language, workflow, the memory notes?), both directions, ending with one concrete "next time"._
