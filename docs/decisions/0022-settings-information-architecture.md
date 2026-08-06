# ADR 0022 — Settings & administration information architecture (one scope-contextual hub)

- Status: Accepted
- Date: 2026-07-04

## Context

Settings are accreting in scattered places. Device-local preferences (theme, language) live in the profile
menu; the Server Owner's tenant administration is its own page (`/server/settings`, cr-0050); tenant member
management is another page (`/tenant/settings`, cr-0077). As **groups** arrive (ADR 0014) each group will add
its own member + governance settings, and the fragmentation compounds — a per-scope button here, a profile
submenu item there. We need one coherent information architecture that scales, is discoverable, and works on
a narrow viewport as well as a wide one.

The recurring design mistake to avoid is a **category error**: putting "this group's members" under the profile
menu (which is about *me*), or scattering "settings for the space I'm in" across ad-hoc buttons.

## Decision

**One scope-contextual settings hub**, reached from a gear in the top bar, structured as a responsive
sidebar↔detail split, with sections filtered by the scopes in the caller's current path and by role.

1. **A route, not a popup.** The gear (top bar, beside Uploads) navigates to **`/settings`** — a real page,
   deep-linkable per entity (`/settings/group-<id>`, `/settings/tenant-<id>`), back-button-friendly, and
   bookmarkable. A dropdown holds too much; a modal has no URL and on mobile is a full page anyway. (A desktop
   *overlay* look is an optional later cosmetic over the same routes — no structural difference.) The gear is
   **context-aware**: with no explicit section it resolves to the caller's **active scope as a concrete entity** —
   in a group → **that group's** detail page; in the Tenant scope → the Tenant's members; else *Persönlich*.
2. **A sidebar ↔ detail split, responsive.** Wide viewports render a persistent section **sidebar +
   detail pane**; narrow viewports collapse to a **list → drill-in** navigation stack (`/settings/<section>`).
   Same routes, one breakpoint. A section that is itself a **collection** to fan out (e.g. *multiple groups*: Groups → a group →
   its detail) is a **collapsible category in the rail** — expanding it lists its entities, selecting one opens
   that entity's detail in the same detail pane (`/settings/group-<id>`) — **not** a third column: the split
   stays two panes (rail ↔ detail), scaling to many entities by scroll/collapse. Narrow keeps the list →
   drill-in stack (category → entity → detail).
3. **Sections = scope-in-path × role, with collections as categories.** A section appears **only if the caller
   can see or do something there**; inside a section, controls are **role-gated** (manage vs. view-only — e.g.
   any member reads the member directory, only the owner sees "add member"). Two axes:
   - **Scope-in-path** (contextual): **Persönlich** (always) and the **Tenant**'s members while you are in the
     Tenant scope — encoding the natural hierarchy (from within a group you see Group **and** Tenant).
   - **Collections** (a management hub, decoupled from the active scope): a **Gruppen** category lists **every
     group the caller OWNs**, and **Server → Tenants** lists **every tenant a Server Owner administers** — so you
     configure any of them without first switching into it. A member-only group has nothing to manage → it is
     **not** listed, though its detail page stays reachable read-only. Listing "groups I own" needs the caller's
     **role per scope**, so the scope list surfaces it ([ADR 0007](0007-membership-role-grant-relation.md) — role
     is already stored; exposing *my own* is operational metadata, operator-blind untouched).
4. **The load-bearing boundary — configuration vs. content-object.** The hub holds settings that **configure**
   identity, access, and containers: personal preferences, scope membership/governance, server tenants.
   Settings that **manipulate a specific content object** — a **Folder** or a **Memory** (rename, cover, date,
   location, move, delete) — stay **inline on the object** (its context menu / detail header), never in the
   hub. Rule of thumb: *hub = "about me / about the space / about the server"; inline = "about this one thing
   I'm looking at".* A folder is spatial, not a configuration entry.
5. **The profile menu stays "me".** The avatar menu keeps a single **Settings** entry that opens the hub (its
   *Persönlich* section), the **language** quick-picker, and sign out — no duplicated screens, no space/server
   administration. **Theme** is the exception: it is a very frequent one-tap toggle, so its quick control lives
   in the **top bar** (beside search), cycling system → light → dark; language stays out of the top bar because
   it grows more options. Both remain fully configurable in the hub's *Persönlich* section (the canonical home;
   the top-bar/menu controls are shortcuts).

## Consequences

- **One predictable settings surface that scales.** Groups slot in as just another section; no new top-level
  entry point, no new muscle memory. The existing `/server/settings` (tenant admin) and `/tenant/settings`
  (member management, cr-0077) collapse into hub sections. Nothing is deployed yet, so they are **replaced, not
  redirected**.
- **Narrow-viewport-correct by construction.** The split view collapses to a list→detail stack; there is no
  separate mobile design to maintain.
- **Operator-blindness is untouched.** Device-local preferences stay device-local (they never reach the
  server); the member/tenant sections show only already-stored operational metadata (pubkeys, roles,
  grant-present, tenant name/domain) — never a key. The hub is only *where* these are shown.
- **Rejected — a profile submenu for everything.** Buries space settings under "my account" (category error)
  and becomes deep static nesting as groups arrive.
- **Rejected — a per-scope gear that shows only the current scope.** Cannot present "group **and** tenant
  together", which is exactly the in-a-group case.
- **Rejected — a single flat scroll page.** Does not scale, and mixes a potentially long member directory with
  two appearance toggles.
- **Rejected — a popup/modal as the primary surface.** No URL, no deep-link/back semantics; on mobile it is a
  full page regardless.
- **Rejected — folding folder/memory settings into the hub.** They are content-object manipulations; they
  belong on the object (boundary in decision 4).
