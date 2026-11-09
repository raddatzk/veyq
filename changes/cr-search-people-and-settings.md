---
id: cr-search-people-and-settings
title: People and settings sections are search results; Depot and Expenses kinds get their kind word
status: done
satisfies: [uc.platform.search]
adrs: ["0125", "0022", "0083", "0079"]
invariants:
  - the search offers exactly the settings sections the rail offers this member now; one list, one set of gates (ADR 0125 decision 9)
  - a section is never offered on a guess; while a gate's answer is out, its section is not offered
  - nothing about a query leaves the device or is written to storage (ADR 0125 decisions 1, 2)
  - core imports no application (`validate_web_core_imports_no_applications.py`)
  - no backend and no contract change
---

## Goal

Build [ADR 0125](../docs/decisions/0125-search-runs-in-the-client-over-what-it-can-open-across-every-space.md)
decision 9 on top of `cr-search-across-spaces` (built, this branch). Three things:

- **People:** a member of this tenant is a result in a **People** group. It opens their profile page.
- **Settings:** a section of the settings page is a result in a **Settings** group, offered exactly where the rail
  offers it. It opens that section.
- **Kind words:** Depot's and Expenses' kinds get a kind word, so a kind chip reaches them: `FILE`, the drop box,
  `EXPENSES_POT`. Run the locator below for their exact kind ids.

Both groups come after the spaces' groups, People first. An empty group is not shown.

## Context & constraints (measured at refine, substrate: branch `worktree-search-design` at `200448f42`)

- **The search:** in `src/web/src/core/search/` (`documents.ts`, `searchIndex.ts`, `query.ts`, `groups.ts`,
  `SearchField.vue`). The registry door is `KindDescriptor.search = { label, facets?, documents?, note? }`.
  `src/web/CLAUDE.md` has a search paragraph; read it first.
- **The settings rail:** built inside `src/web/src/views/SettingsHubView.vue`, as `NavItem`s with gates:
  - the section list starts near the `{ id: 'appearance', … }` entry;
  - the device-linking capability gates pairing;
  - `trustRefused` gates trust reset;
  - `activeSpace.tenantId` gates the profile;
  - the moderator appointment gates moderation;
  - tenant ownership gates mail, storage and capabilities.

  The view's comments state two rules that bind here:
  - a section is never judged hidden on a guess (`ready` once every answer is in);
  - the rail mixes the scope-in-path axis with the account axis (ADR 0022 §3).

  The list has to leave the view for one module that both the rail and the search read. A copy in the search would be
  a second truth for the gates.
- **People:**
  - `core/people/directory.ts` (`loadTenantPeople`, `personNow`, `tenantNames`) is the tenant roster, read in the
    background by `SpaceView`.
  - A profile page is `place.person` in `SpaceView`, reached by `personHash(userId)` from `core/node/location`, and
    rendered by `core/people/PersonProfile.vue`.
- **Kind words:** a kind gets its chip word from `search.label` in its descriptor. Depot and Expenses register their
  kinds in `features/depot/` and `features/expenses/`.

## Design

- **People documents:** one per tenant member, from the roster.
  - **Fields:** kind `person`, the directory's display name as name, the username as a second matched field (so
    `~kevin` and "kevin" find the account).
  - **Order:** the member themself is a result like any other; nothing is pinned.
  - **Lifetime:** the documents follow the roster. They are rebuilt when it reloads and dropped at sign-out, like
    every search document.
  - **Display:** the row shows the picture or initial the roster already has, and *"~username"* as its second line.
  - **Opening:** opens the profile page in the tenant space, as the existing profile links do.
- **Settings documents:** one per `NavItem` the rail would show now, from the extracted module.
  - **Fields:** kind `settings`, the section's label (UI language) as name, the axis' heading (account, this space,
    the tenant) as the second line.
  - **Gates:** while the gates are not all answered, no settings document exists, and the group says nothing (no
    spinner of its own).
  - **Opening:** opens `/settings/<id>`, keeping the active space as the gear does. A hard navigation would reset it,
    and that is a known trap (`SettingsHubView`'s comments).
- **Groups:** `groups.ts` gains two fixed trailing groups, `people` and `settings`, ranked by the same rules as a space
  group.
- **Chips:**
  - A person chip still filters content. A person result is answered alongside it.
  - Choosing the *person chip* suggestion filters; choosing the *person result* opens the profile.
  - With any chip set, the People and Settings groups are hidden, because a chip is a filter over content.
- **Kind words:** Depot and Expenses add `search.label` for each of their kinds, in their own i18n namespaces.
  - The kind chip then filters to them.
  - Their `note` stays empty.
- **Empty state:** the "what is searched" sentence gains people and settings.

## Inventory (locators — run them)

| Where | Locator |
|---|---|
| The rail's list and gates | `rg -n "id: '" src/web/src/views/SettingsHubView.vue` |
| Other readers of the rail's ids | `rg -n "settings/'\|name: 'settings'" src/web/src` |
| The roster and profile links | `rg -n "personHash\|loadTenantPeople\|personNow" src/web/src` |
| The kinds to word | `rg -n "kind\(" src/web/src/features/depot src/web/src/features/expenses` |
| The search groups and empty state | `rg -n "otherSpaces\|thisSpace\|note" src/web/src/core/search` |
| Site | `site/src/content/docs/spaces/search.mdx` and `de/spaces/search.mdx` |

## Phases

### Phase 1 — one list of settings sections

- **Build:** move the section list and its gates out of `SettingsHubView.vue` into one module (location is the
  implementer's: where settings code lives today). It returns the sections and a `ready` flag. The rail reads it, with
  unchanged behaviour.
- **Tests:**
  - Each gate, at the module level: a non-owner does not get mail, storage or capabilities; no moderator, no
    moderation; capability off, no pairing; not `ready`, no answer.
  - The existing `SettingsHubView` tests stay green unchanged. If one has to change, say why.
- **Done when:** `pnpm run check` is green in `src/web/`, and the rail looks the same in the browser (owner and the
  sections it shows).

### Phase 2 — people and settings as results; kind words

- **Build:** the documents, groups, opening and empty-state change from § Design; Depot's and Expenses' `search.label`;
  EN+DE strings.
- **Tests:**
  - **People:** a member found by name and by username; choosing it opens their profile hash.
  - **Settings:** a section found by its label, opening `/settings/<id>` with the active space kept.
  - **The bite pair:** a section the rail hides (an owner-only one for a non-owner) is never a result, with a positive
    control — the same section IS a result for the owner.
  - **Chips:** a chip hides both groups.
  - **Kind words:** a kind chip for a Depot file and for a Pot.
- **Coverage:** `uc.platform.search.yaml` gains the criteria with refs in this phase:
  - `people-are-results`;
  - `settings-are-the-rails-sections`;
  - `every-kind-has-a-word` (or an extension of an existing criterion where it fits better; say which).
- **Done when:** `pnpm run check` is green and the core-import guard is green.

### Phase 3 — the browser pass and the docs

- **Browser pass on the `veyq-search` stack:** see `cr-search-across-spaces` § Retrospective for how to start it.
  - "kevin" lists the person, and choosing it opens the profile page.
  - The settings group shows "Passwort", and choosing it opens that section with the space kept.
  - "Speicher" (an owner-only section) appears for the owner.
  - A kind chip for a Depot file works. Upload one file if the stack has none.

  Desktop and phone width; screenshots of the final state only.
- **Docs:**
  - The use case's Status (decision 9 built) and the ADR's `Built:` (decision 8 still open).
  - Inbox #21.
  - The Use page `spaces/search` in EN+DE.
- **Done when:** the trust-chain guards are green (`/usr/bin/python3`, guard_gaps skipped unless a validator changed).

## Retrospective

Implemented 2026-11-09 in the worktree `search-design`, three phases. Each code phase `pnpm run check` green in `src/web/`
(measured: 539 test files both times), the core-import guard green; the browser pass ran on the `veyq-search` stack.

**Technical choices made (owner away; recorded here, as asked).**
- **The one list is a composable, not a store:** `features/settings/sections.ts` `useSettingsSections()` returns the
  categories, `offered` (the sections with their heading, empty until `ready`), `ready`, `isHidden` and `load()`. Each
  caller holds its own answers: the rail loads on mount (as before), the search on every focus (the previous answers
  stand until the new ones are in, so the group does not flicker). A tenant change re-reads the role only once `load()`
  ran, so the search reads nothing before its first focus. Device linking became a `computed` (it was read once at
  setup); the hub's behaviour is otherwise unchanged and its test file is untouched.
- **The group collection is in the list too:** the CR says one document per rail `NavItem`, so an owned group's page is a
  Settings result headed *Gruppen*.
- **People follow the directory's snapshot** (`tenantPeopleNow`, new): rebuilt when the roster is read, empty after
  `resetPeopleDirectory` (sign-out). The person chips read the same source now, instead of the field's own copy.
- **Model:** `SearchDocument` gained `aliases` (matched as a name, at its best rank: `~kevin` finds the account),
  `subtitle` (a row's own second line) and the kinds `person` / `settings`. Both kinds carry their own `open`, and
  `groups.ts` classifies by kind first, so they never fall into the contributed-document branch.
- **The chip rule is explicit in `groups.ts`**, although no chip offered today lets a person or a section through
  (`satisfies` already drops them): it is the rule the ADR states, and its test builds a chip that would let them
  through, so the rule is bitten, not only the predicate.
- **A sixth door in `veyq/one-settings-door`:** `SearchField.vue` is added to the rule's ignores with its reason (the
  lint caught the `/settings/<id>` push).
- **`every-kind-has-a-word`** is its own criterion, held by a test in `applications.test.ts` over `registry.kinds()`
  (every kind but core's bookmark) plus the chip in `SearchField.test.ts`.
- **Bites (snapshot-and-restore):** phase 1 - the owner gate and the `ready` gate dropped, 4 module tests and 2 hub tests
  red; phase 2 - the same two gates, both kind words and the chip rule dropped, 6 tests red (the bite pair, the "answer
  out" case, both kind chips, the applications test, the chip rule).

**Pages touched:** `site/src/content/docs/spaces/search.mdx` and `de/spaces/search.mdx` (People, Settings, the new kind
words, the chip hiding both groups).

**Browser pass** (`veyq-search` stack, owner `kevin@veyq.test`, German UI; one file `Reiseplan.txt` uploaded into
*Reisen* for the kind chip): desktop 18 checks, phone (390 px) 6 checks, all PASS (scripts `p9-pass.mjs`, `p9-phone.mjs`
in the session scratchpad's `search-pass/`). Found there, not by a test: **after a chip is chosen, no row is highlighted,
so Enter does nothing** until ArrowDown - the layer's behaviour since the first CR, not this one's; left as is and named
below.

**Owner questions (each with the conservative default built).**
1. **The bookmark kind has no kind word** (the CR named Depot's and Expenses'). Recommendation: give `SHARE_BOOKMARK` one
   (*Lesezeichen*) in a one-line follow-up; the applications test then drops its exception.
2. **The person chip and the person result read alike** ("Kevin Raddatz · Person" over "Kevin Raddatz · ~kevin"). Built
   as the ADR says; recommendation: keep, the chip's row says *Person*, the result's shows the picture and handle.
3. **Enter after a chip does nothing until a row is highlighted.** Recommendation: highlight the first result after a
   chip (a small change in `VeyqTagsInput`), so the keyboard flow continues.
4. Keep this CR for your review or delete it: its binding lessons are in `src/web/CLAUDE.md` (the search paragraph) and
   in code comments.

**What went wrong.**
- **Phase 2's first full check was red three times on things the targeted runs did not cover:** the one-settings-door
  lint, `AppTopbar.test.ts` reaching the network (the focus now loads the settings answers) and Depot's descriptor
  snapshot in `register.test.ts`. Root cause: I ran only the suites I had touched before the full check. Next time: before
  the first full check of a phase that adds a read to a shared component, grep every test that mounts it
  (`rg -l "<Component>" -g '*.test.ts'`) and every test that snapshots a descriptor it extends.
- **Signing:** phase 1's commit failed with the 1Password error and was made with `--no-gpg-sign` (as the owner allowed);
  phase 2's signed normally an hour later. The signer is intermittent, not down.

**Next time:** grep the mounters of a shared component before adding a read to it.
