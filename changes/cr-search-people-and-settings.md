---
id: cr-search-people-and-settings
title: People and settings sections are search results; Depot and Expenses kinds get their kind word
status: planned
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

(filled by the implementer)
