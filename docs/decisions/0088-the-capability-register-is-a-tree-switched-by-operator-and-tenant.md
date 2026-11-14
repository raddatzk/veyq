# ADR 0088 — The capability register is a tree, switched off by the operator and by a tenant owner

- Status: Accepted
- Built: partly (not built: decision 8's verified-identities and offline-cache requirements, decision 12's configuration as code, admin-web's own CSP under decision 7, § The register's entries the catalog does not hold)
- Date: 2026-10-15
- Model: [Applications](../apps/README.md)

## Context

[ADR 0032](0032-capability-discovery-version-gating.md) built a lean core: a flat, append-only catalog of four
ids, advertised pre-auth on `/api/setup` and switched off by the operator for the whole instance. Three things
have changed since then.

- **Minors add features, and native clients drift.** Under [ADR 0085](0085-one-major-moved-on-a-yearly-train.md)
  a store-shipped client talks to servers of an older minor in the same major. Expenses, GPX tracks in a Memory
  or the journal arrive in minors, and the client has to see whether *this* server serves them. The minor cannot
  answer that: it counts commits ([ADR 0057](0057-release-identity-and-deployment-recipe.md) §1) and must not be
  pre-auth anyway.
- **A tenant owner has policy too.** ADR 0079 rejected per-tenant activation of an application, with clutter as the
  problem it would solve, which "created on demand" already solves. The reason now is a different
  one: a tenant owner who does not want Depot, or public links, or password-less devices in their tenant.
- **There are now applications.** A flat list cannot say that switching public links off in core also switches
  them off in every application.

ADR 0086 has already separated the per-view `…Rights` functions from capabilities. This ADR is only about the
instance and tenant layers.

## Decision

1. **One register, a tree.** An id is a path: `core.<x>`, or `<application>.<x>`, where the application's own
   node is the application itself (`depot`). An entry is placed where it is used. It moves up to `core` only when
   a second application uses it. Moving it up only adds a parent, so no id changes.
2. **An entry means the server serves this.** Some entries can also be switched off: `*` by the operator for the
   instance, `+` by a tenant owner as well. The effective state is an **AND** along the path and across both
   levels, so a tenant can only narrow what the operator allows, never widen it.
3. **The server computes; the client reads.**
   - Pre-auth `/api/setup` carries the instance level, for what matters before login (`core.remember-device`).
   - After login the server delivers the effective set for the tenant.
   - A client knows only the ids it has UI for. It ignores an unknown id and hides the feature when an id is
     missing. Why an id is missing (an older server, the operator, the tenant) is never the client's question, so
     no client keeps a table per server version.
4. **What makes an entry switchable.** An entry gets `*` or `+` only if it names two things: what the server
   **refuses**, by something the server sees (a node kind, a blob role, a share role, an endpoint, a CSP origin),
   and what happens to **existing** data. An entry that cannot name both is presence only (`○`). A switch over
   something the server **keeps** rather than serves names what it stops writing instead of what it refuses.
5. **What "off" does.** A share switch, a device switch and a requirement (decision 8) act at once, on what
   exists too: a link that keeps working, or a remembered device that keeps unlocking without the password,
   defeats the reason it was switched off. A device switch withholds its wraps (`DEVICE` for `remember-device`,
   `DEVICE_KEY` for `passwordless`) from the key material the server hands out, the way the `RECOVERY` wrap is
   already withheld. It never deletes them, so switching back on restores every remembered device, and the
   `PASSWORD` wrap is always served, so nobody is locked out. A feature switch refuses only what is **new**.
   An **application switch** (`memories`, `depot`, `expenses`, the application's own node of the tree) is the one
   feature switch that reaches what exists: it makes the application **read-only**, every write of it refused — a node
   of its kinds created, renamed, moved, copied or imported, its records and blobs written, a link rooted on one of its
   nodes created or given a write role, a guest's write through any link — and every read and download kept, through
   the links that exist too, so nobody's data is trapped. Taking something out is never refused: a delete to the trash
   and its restore (a restored node is read-only like the rest), an erasure, a moderation measure, a member's own marks
   on its items ([ADR 0068](0068-favorites-two-confidentiality-levels.md)) and a rotation's re-key, which is the
   security of what exists. Its children (`memories.places`) are off with it, by the AND along the path, and its
   one-segment id is the only one a root may be (`core` is no entry). Every member of the tenant sees a banner saying
   so, so there is time to save what is in it.
   What exists stays readable, so there is no "activated but empty" state machine, and the gate sits on the
   application's writes, never on its reads. **Left off, it is the one switch that erases**: after a window, one
   instance-wide value of the Server Owner (30 days by default), the application's content in the tenant is erased on
   a day every member is told — by the banner and, where the instance has mail, by a notice at switch-off and a reminder before the day. Switched on before that day, nothing is lost;
   switched on after it, the erasure completes and spares only what was made after the switch-on as a node of its own.
   The day a member was told is never moved earlier, and a switch is never refused. The window is no requirement
   (decision 8): a tenant owner sets no value for it, and a tenant only narrows by switching the application off, never
   by shortening what the instance allows (decision 2). An instance switch gives every tenant its own day, and a tenant
   already off keeps its earlier one. Only the application's own entry erases; a child entry (`memories.places`) erases
   nothing.
6. **Server visibility is accepted where a switch needs it.** Journal, GPX and a face index get a node kind or a
   blob role the server sees. The operator then learns *that* a Memory has a track, never where. This is the same
   kind of metadata as `CHAPTER`.
7. **External services are entries.** When one is switched off, the CSP the backend serves
   ([ADR 0069](0069-backend-serves-the-web-app-shell.md)) leaves its origin out, so the browser blocks it, and the
   manifest hides it from native clients. The services' URLs are operator configuration, not build variables.
8. **Requirements are values on a node, and the strictest wins.** A tenant can tighten and never loosen. On the
   wire `[{ id }]` grows to `[{ id, …values }]`, the extension ADR 0032 already left room for.
9. **Presence ids retire.** Once every major in the client window serves an id, the client stops checking it, and
   the server may stop advertising it at the next train. That is a removal under ADR 0085.
10. **The catalog is cut once more before V1, then frozen.** Its append-only rule protects released clients, and
    there are none yet. From V1 on it holds strictly: an id is never renamed or repurposed. It is removed only while no
    released client can still read it. The web client ships with the server that serves the catalog, so until a
    native app is released that holds for every id; the three `memories.music` ids went this way with the streaming
    services ([ADR 0135](0135-background-music-is-a-nodes-own-audio-media-played-by-cores-player.md) decision 1). A
    removed id is **retired**: `scripts/validate_capabilities.py` lists it and refuses it, so it never returns with
    another meaning.
11. **Device linking and remembering a device are two independent switches.** Linking answers
    *how a key reaches a device*: from another unlocked device instead of password and Secret Key. Remembering
    answers *whether the key may stay there*. Every combination is coherent:
    - With `passwordless` off, a linked device gets a session but no stored key, because the only wrap it can
      enrol is a `DEVICE_KEY` (ADR 0059 §10), so the client does not offer "stay signed in" there.
    - With linking off, an instance rules out the one mechanism that turns a single human confirmation into the
      Master Key, where a phished grant is an identity takeover (ADR 0059).
    - Handing the key from one of the user's own native apps to another on the same device is not
      linking: it needs no network and no confirmation. Each app enrols its own wrap, and that wrap is governed by
      `remember-device`.
12. **Where each level switches.**
    - **A tenant owner** switches in the tenant settings in `src/web/`, beside terms and mail. The tenant owner is an
      ordinary member, and ADR 0065 keeps what a member can reach out of `admin-web`.
    - **An entry a client offers before sign-in is the Server Owner's alone** (`*`, never `+`): `remember-device`,
      `passwordless`, `device-linking` and `breached-password`. The sign-in screen offers "stay signed in" and "sign in
      with code", and a password dialog before sign-in checks breaches, from the pre-auth manifest, which carries the
      instance level only (decision 3), so a tenant's switch there would be a button that fails after the click. The key
      material is the account's, not a tenant's, and the pairing channel carries no tenant either. A tenant that wants
      these off asks the Server Owner.
    - **The Server Owner** switches in `admin-web` and its CLI, through the Admin API. The state lives in the
      database and takes effect without a restart. It is not a configuration property: the
      `veyq.capabilities.disabled` list retires, and with it any question of what an unknown id in it means.
    - **Configuration as code** (switches declared in compose or Helm values) is a later topic.
13. **The register describes itself.** Every switchable entry carries, in English and German, *what it is for* and
    *what switching it off does*, both to new and to existing data (decision 5). The catalog holds that text and
    the API serves it. `admin-web`, the CLI and `src/web/`'s tenant settings render it from there, and none of them
    keeps its own copy.
14. **No operating mode reaches the register**
    ([ADR 0093](0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md) § 14). A mode,
    *private* or *club, organisation or company*, was once a preset: choosing *private* set
    `core.public-shares.guest-write-record` off, after which the switch moved on its own. That left two sources of
    truth for one state and let a private instance serve guest writes without their record, the case the record exists
    for and the one an operator-blind Server Owner cannot see. So that entry is gone (the record is kept wherever a
    guest may write, ADR 0093 § 10), the catalog's `modes` and the write that applied them go with it (decision 10's
    last cut), and ADR 0093 § 14 retires the mode itself. What guests and members may do is the register's alone.

### The register

`*` operator, `+` tenant owner too, `○` presence only, `→` what the server sees.

```
core/
  *   remember-device              → DEVICE wrap (password only; the device key stands in for the Secret Key)
    *   passwordless               → DEVICE_KEY wrap (no password)
  *   device-linking               → /api/pairing/*   (independent of remember-device, decision 11)
  *+  group-space                  → group scope creation
  *+  personal-space               → scope kind PERSONAL; "off" = no new content, the scope stays (keys, favorites)
  *+  member-invites               → /member-invites
  *   self-registration   [idea]   → a new endpoint; off by default
  *+  public-shares                → share role READ
    *+  contribute                 → share role READ_WRITE
    *+  drop-box                   → share role WRITE_ONLY
    *+  embeddable                 → CORS on the guest share API (ADR 0121)
  *+  share-embedding              → CSP connect-src https: (a kept link browsed in place, ADR 0121)
  *   breached-password            → external (HIBP); CSP connect-src (instance only: checked before sign-in)
  *+  push                [native] → external (APNs/FCM); a content-free wake-up
  ○   kept-shares, search, live-collab, notifications   [later]
*+ memories                        → off = read-only: every write of its kinds and operations
  *+  chapters                     → node kind CHAPTER
  *+  journal             [planned]→ a server-visible kind or blob role
  *+  gpx                 [planned]→ a server-visible blob role
  *+  faces               [idea]   → a server-visible role for the face index (biometric data)
  *+  places                       → external (reverse geocoder); CSP connect-src
  *+  map                          → external (map tiles); CSP img-src
  *+  public-shares / contribute / drop-box   → inherit core.public-shares
    *+  download-originals         → blob role ORIGINAL (only Memories has derivatives)
*+ depot                           → off = read-only: node kinds FILE / DEPOT_DROPBOX, blob role FILE_CONTENT
  *+  public-shares / contribute / drop-box   → inherit; drop-box also gates creating a DEPOT_DROPBOX
*+ expenses                        → off = read-only: node kind EXPENSES_POT (ADR 0108); *+ public-shares/… inherit
  *+  exchange-rates               → external (exchange-rate provider), no opt-in; CSP connect-src
*+ inventory/  [concept]           → its node kinds; *+ public-shares/… inherit; *+ reminders-push inherits core.push
```

`places` and `map` are independent of each other. A place comes from the media's GPS or from a pin dropped by hand,
and its name is geocoded or typed. The geocoder suggests names, and the tiles carry the pin and the map view. With
both off a place still works, named by hand. The user's own consent to the two
([ADR 0016](0016-geocoding-provider-neutrality.md)) is a third, separate thing and stays.

### Requirements

| Node | Requirement |
|---|---|
| `core.public-shares` | a link needs a password; a link expires within N days |
| `core.group-space` | joining a group needs the member's consent (beside the member's own setting for it) |
| `core.remember-device` | ask for the password again after N days |
| `core.public-shares` | share only with verified identities (planned; not buildable as specified: the server knows no verified identity to check) |
| `core.offline-cache` [native] | no decrypted data kept on the device. Only the client can keep this, the server cannot enforce it |

### Not in the register

- **Never switchable:** derivatives (the gallery's performance rests on them), profile picture, recovery (without
  it a forgotten password and a lost Emergency Kit lose the data, and an operator-blind server has no admin way
  around that), passkey, auto-upload, favorites, trash, move, copy, cover, participants, date, EXIF, buckets,
  live updates, the viewer.
- **Export/import stays unswitchable** because no switch could be enforced: whoever can read holds the plaintext
  on their device ([ADR 0082](0082-a-node-and-everything-under-it-exports-as-a-plaintext-zip-and-imports-under-the-importer.md)).
  A switch would promise protection against data leaving the instance that does not exist.
- **Preconditions, not switches:** mail ([ADR 0055](0055-mail-is-a-precondition-not-a-capability.md)), the S3
  store, ACME.
- **Parameters, not switches:** quotas, the per-link storage limit, a file-size cap, retention periods.
- **Admin channel only:** creating, suspending and moving tenants, custom domains, backup.

## Consequences

- **It replaces [ADR 0079](0079-veyq-is-a-platform-memories-is-its-first-application.md) decision 8 and
  [ADR 0032](0032-capability-discovery-version-gating.md) § 2, § 4's who switches where and § 5**; ADR 0032 § 3 keeps
  capability ≠ permission, narrowed per tenant. How the register is built — the catalog, the AND, both deliveries, each
  kind of switch, the requirements, the external services and an application's erasure window — is
  [en.platform.capability-register](../features/platform/enablers/en.platform.capability-register.md). The catalog
  (`contracts/platform/capabilities.json`) is what is built, never a list here.
- **A switched-off link answers like an unknown one.** Every guest endpoint answers a link of a switched-off role
  exactly as an unknown link, a `404` with the same body, before the password is asked, so a guest learns nothing of
  the instance's policy, and the one predicate every guest path already passes is where it sits, so no application
  handles it. Nothing is written onto the link, so switching back on serves it again. A guest's page is likewise not
  told that an application is off; its write is refused by the server.
- **A requirement acting on what exists took two more decisions.** A link's lifetime counts from when it was made,
  never from now: a moving window would refuse a link today and serve it later, so an edit fixes a younger link and an
  older one is renewed as a new link, with a new address. `addConsent` leaves members already added alone, a direct add
  whose key is still on its way included: the server holds no sealed name key to turn it into a request, and removing
  it would delete a membership. A missed requirement is fixed on the same link, address and key kept, and an edit is
  judged as it leaves the link, so a write toward a requirement is never refused by it.
- **A member sees why a link serves no guest.** The guest gate's one predicate returns its reasons, and the share list
  serves the same list on every row, so the list cannot call a link on that a guest gets as unknown; a guest's `404` is
  unchanged.
- **A feature switch has one gate.** `core.group-space` is gated at group creation only, although its catalog entry
  serves several use-cases: a feature switch refuses only what is new (decision 5). Creating a Depot drop box needs
  `core.public-shares.drop-box`, and so does its share.
- **The served CSP is the instance level's.** The header is written with no database read, from a snapshot of the
  instance's origins, so a tenant's own switch of an external service acts through its set, not through the policy.
  One part follows the tenant: `core.share-embedding`'s `https:` is appended for the tenant whose domain serves the
  page, from a snapshot of the same kind ([ADR 0121](0121-a-received-share-is-browsed-in-place-through-its-own-api.md)).
- **`download-originals` waits for a measurement.** If guests stream video from the `ORIGINAL`
  ([ADR 0047](0047-inline-video-playback-range-streaming.md)), switching it off also stops video playback for guests
  until a video preview tier exists. This must be measured before the entry is built.
- **Cost:** every switch doubles the states a feature must be tested in, and inheritance multiplies that. Decision
  4 is the brake.

## Alternatives considered

- **A feature level (one rising number) instead of presence ids.** Rejected: it cannot say "off", for the operator
  or for a tenant, and a client would have to know which number brought which feature.
- **A capability table per server version in the client.** Rejected: the server knows what it serves (decision 3).
- **A flat list.** Rejected: core cannot switch a function off for every application at once.
- **Tenant policy as part of the per-view rights.** Rejected: rights are the client's projection of a permission
  (ADR 0086), and a tenant-wide policy is server-enforced state.
- **Splitting public shares by risk (read vs. any guest write) instead of by role.** Rejected: the role split
  gives the same "no anonymous uploads" with two switches, and lets an application forbid only its drop box.

## Related

- On the docs site: [Capabilities](../../site/src/content/docs/run/capabilities.mdx) (the register for the Server Owner:
  how to switch, the built entries rendered from the catalog, and the designed ones),
  [Moderation](../../site/src/content/docs/run/moderation.md) (switching as one of the operator's levers) and the
  [Security roadmap](../../site/src/content/docs/security/roadmap.md) (the designed switches).
