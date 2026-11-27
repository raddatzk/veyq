---
id: cr-apps-reopen-and-deep-links
title: The apps reopen where the person left, and a place reached from outside the app opens there
status: in-progress
satisfies: ["uc.memory.browse"]
adrs: ["0150", "0122", "0140"]
invariants:
  - "the kept place is sealed in the account's offline store (no node or scope id in clear on disk) and wiped with it"
  - "one account's place never opens for another: the record lives in the account's own store, keyed by its tenant"
  - "a link from outside the app only navigates, within the open tenant, through the shell's own checked open; it starts no sign-in and no fetch"
  - "a `veyq:` link carries an origin and opaque ids only, never a name or a slug; it is never stored or logged"
---

## Goal

Inbox #1415 (owner 2026-11-27). A restart unlocks onto Home today; the web's reload returns where the person was. The core
keeps the last open place per account and tenant, sealed in the account's offline store, and opens it after the unlock
through the shell's open. The same place shape is reached from outside the app: the web's *Open in the app*
(`veyq://open` carrying the instance and the place), a member URL shared into the app (ADR 0150 § S) or pasted into
*Open a link*.

## Measured at refine (2026-11-27, this branch)

- The apps already reopen on "the object used last" (`ShellModel.load(openLastUsed)` → `RecentUses.lastUsed`), and
  `RecentUses` is **one per install** in plain prefs (`AppModel.kt` `veyq-recent`, Apple `Shared.defaults`
  `veyq.recent`, the bridge's `veyq.recent`), its `UseKey(scopeId, nodeId, …)` naming no account: this is the observed
  defect — after a join, account B opened the Memory account A had used last in the same tenant.
- The web's route form is `/` with the place in the fragment as **name slugs** (`location.ts`: `#~me/reisen-2026/memories/rom`,
  a group as its name's slug or its scope id), and `/settings/:section`. A slug is a name's folding: content.
- `DeepLink.parse` reads only the host (§ O1); the tenant's own origin is `TenantSession.base`.

## Decision log

1. **The `veyq:` link carries ids, not the web's slugs.** The owner accepted the residual as "the instance's domain and the
   route's opaque ids — no content". The web's fragment holds name slugs (measured above), so the button builds
   `veyq://open?i=<origin>&s=<scopeId>[&n=<nodeId>]` or `veyq://open?i=<origin>&settings=<section>` from the shown page's
   ids. The accepted residual then holds as stated. A member URL pasted or shared in is read in the web's slug form,
   resolved on the device against the decrypted tree: it comes from the person, not over the scheme.
2. **The kept place is the id form too** (scope id + the node and its ancestors' ids, nearest first), sealed as an offline
   store entry of a new kind `place`, one per tenant. The ancestors make "gone → nearest parent" work without the tree that
   held the node. The slug form would break on every rename; the id form is the one the `veyq:` link carries, so the two
   share one shape.
3. **No store, no reopen.** An account without an offline store on the device (not trusted, a TV) opens on Home. The
   device-wide `RecentUses` no longer decides where the app opens, on any account; it still orders the drawer's Recent
   (its per-account split is inbox #1423).
4. **What is kept:** a space's root, a folder, an object's page, a kept link's page. A person's profile, Search and an
   application's list keep the place below them unchanged. A lightbox, a dialog, Settings are not the shell's place and are
   never kept.
5. **A link navigates only within the open tenant** (its origin equal to `TenantSession.base`): rule (1) read strictly. A
   link for another signed-in tenant is refused with its own word rather than switching accounts behind the person's
   back. A `veyq:` link that arrives before the shell is open (the sign-in, the unlock) waits in memory and is applied
   when the shell opens, in place of the kept place; one whose origin does not match is dropped silently.
6. **The allow-list** (core, `PlaceLink`): `/` with a fragment of the grammar's scope token (`''`, `~me`, `@<slug or id>`)
   and slug segments only (`[a-z0-9-]`), or `/settings` with one of the apps' `SettingsSection` ids other than `erase` and
   `sign-out` (whole sections that are one action). Refused: any query, `#people/…`, any other path (`/s/…`, `/tenant/accept`,
   `/recover`, `/confirm-email*`, `/moderation`, `/invitations`, `/terms`, `/login`), credentials, a non-http(s) scheme. A
   `veyq:` link with any other parameter, or ids not shaped as UUIDs, is plain `Open`.
7. **Resolution reads only what is listed**: the shell's lists (hidden spaces out). A scope not listed → nothing happens
   for a link, Home for a kept place; a node not listed → its nearest listed ancestor (kept place) or the space's root.
8. **Settings** is answered to the platform (`Arrived.Settings(section)`): each opens its settings page at that section,
   as a search's settings result does.

## Phases

1. **web** — `openInApp.ts` builds the place href from the shown ids (`SpaceView` publishes them); `pnpm run check`.
2. **core** — `PlaceLink` (parse + allow-list), the slug resolver (port of `location.ts`' `segmentsOf`/`resolveSlugPath`),
   `DeepLink.At`, the `place` kind in `AccountOffline` (protected, kept by the sweep, carried by a rotation), `ShellModel`
   keep/reopen/`arrive`, `OpenLink`'s member URL; tests bitten.
3. **Android, Apple, Linux + Windows (the bridge)** — `veyq://open?…`, a shared and a pasted member URL.
4. **harvest** — ADR 0150 § O, ADR 0122 decision 5, native-apps, inbox.
