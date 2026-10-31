# ADR 0022 — Settings & administration information architecture (one scope-contextual hub)

- Status: Accepted
- Built: partly (not built: decision 3's read-only page of a group the member does not own (inbox #814))
- Date: 2026-07-04
- Model: [ui-web-styleguide](../crosscutting/ui-web-styleguide.md)
- Replaced in part: decisions 3 and 4 by ADR 0065 (built)

## Context

Settings are accreting in scattered places. Device-local preferences (theme, language) live in the profile
menu; the Server Owner's tenant administration was its own page (`/server/settings`); tenant member
management was another (`/tenant/settings`). As **groups** arrive (ADR 0014) each group will add
its own member + governance settings, and the fragmentation compounds — a per-scope button here, a profile
submenu item there. We need one coherent information architecture that scales, is discoverable, and works on
a narrow viewport as well as a wide one.

The recurring design mistake to avoid is a **category error**: putting "this group's members" under the profile
menu (which is about *me*), or scattering "settings for the space I'm in" across ad-hoc buttons.

## Decision

**One scope-contextual settings hub**, reached from the profile menu's *Settings* entry, structured as a
responsive sidebar↔detail split, with sections filtered by the scopes in the caller's current path and by
role.

1. **A route, not a popup.** The *Settings* entry (sidebar profile menu) navigates to **`/settings`** — a real page,
   deep-linkable per section and per group (`/settings/<section>`, `/settings/group-<id>`), back-button-friendly, and
   bookmarkable. A dropdown holds too much; a modal has no URL and on mobile is a full page anyway. (A desktop
   *overlay* look is an optional later cosmetic over the same routes — no structural difference.) That entry is
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
     group the caller OWNs**, so you configure any of them without first switching into it. A member-only group has nothing to manage → it is
     **not** listed, though its detail page stays reachable read-only. Listing "groups I own" needs the caller's
     **role per scope**, so the scope list surfaces it ([ADR 0007](0007-membership-role-grant-relation.md) — role
     is already stored; exposing *my own* is operational metadata, operator-blind untouched).
   - **The Server Owner's tenant administration is not in the hub.** It is admin-web's, a separate service
     reachable localhost-only ([ADR 0065](0065-admin-ui-cli-separate-service.md)); what a tenant **owner**
     configures for their own tenant (its mail settings, its terms) stays in the hub's Tenant section, because the
     backend gates it as the tenant owner's, not the Server Owner's.
4. **The load-bearing boundary — configuration vs. content-object.** The hub holds settings that **configure**
   identity, access, and containers: personal preferences, scope membership/governance, a tenant's own settings.
   Settings that **manipulate a specific content object** — a **Folder** or a **Memory** (rename, cover, date,
   location, move, delete) — stay **inline on the object** (its context menu / detail header), never in the
   hub. Rule of thumb: *hub = "about me / about the space"; inline = "about this one thing
   I'm looking at".* A folder is spatial, not a configuration entry.
5. **The profile menu stays "me" — and it is the shell's only door.** The avatar menu carries the **theme**
   and **language** quick-pickers, a single **Settings** entry that opens the hub at decision 1's contextual default,
   and sign out — no duplicated screens, no space/server administration rendered in the menu itself. Theme and
   language are **one pair, on one shelf**: the hub presents them as a single *Appearance and language* block,
   so neither axis is an exception and neither sits at the shell's opposite end from the other. Both remain
   fully configurable in that hub section (the canonical home; the menu's pickers are shortcuts). The **top
   bar carries no preferences and no second entry into the hub** — it is left to what is genuinely contextual
   (breadcrumb, invitations badge, upload tray). That matters most below the compact threshold, where the bar
   shares a sticky header with the sidebar's row and narrows again on a phone — so what a control there costs is
   **width taken from the breadcrumb**, which is the one thing on that bar the user cannot do without.

## Consequences

- **One predictable settings surface that scales.** Groups slot in as just another section; no new top-level
  entry point, no new muscle memory.
- **Narrow-viewport-correct by construction.** The split view collapses to a list→detail stack; there is no
  separate mobile design to maintain.
- **Operator-blindness is untouched.** Device-local preferences stay device-local (they never reach the
  server), and the hub adds no data path: it is only *where* a section is shown, and what each section reads and
  who may see it is the decision of the record that section serves.
- **Rejected — the top-bar gear as the hub's contextual door (a close call, recorded because it nearly won).**
  § Context's category-error warning cuts against decision 5 itself: the hub holds group and tenant
  sections, so hanging its door off a menu that is about *me* is that error stated one level up, and keeping
  the gear in the top bar was the reading faithful to it. It lost on the reconciliation: the profile menu
  gains **one door, not the hub's contents** — *"about me"* describes where the handle is, not what is behind
  it, and what keeps the hub honest is decision 4's boundary, never the handle's location. What settled it is
  that two doors into one route is duplication whichever one goes, and the one in the top bar is paid for in
  the shell's scarcest space (decision 5).
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
