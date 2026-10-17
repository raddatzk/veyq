# ADR 0088 — The capability register is a tree, switched off by the operator and by a tenant owner

- Status: Accepted 2026-10-15; the instance level and the tenant level are built, share switches reaching existing
  links included (inbox #442, #439 step 2), and so are four requirements (#439 step 3) and the three external
  services (#439 step 4); a member's share list says why a link serves no guest (#457) (§ Consequences). It replaces
  [ADR 0079](0079-veyq-is-a-platform-memories-is-its-first-application.md) decision 8 and reshapes
  [ADR 0032](0032-capability-discovery-version-gating.md) §2, §3 and §5, all three edited to match. Decision 14
  (the operating mode as a preset, 2026-10-17) is built: the catalog's `modes`, the generated `CapabilityPresets`
  and the write that sets them with the mode ([uc.admin.operator-details](../features/admin/use-cases/uc.admin.operator-details.md));
  one entry carries a preset, `core.public-shares.guest-write-record` (off in *private*; cr-guest-write-record).
- Date: 2026-10-15

## Context

[ADR 0032](0032-capability-discovery-version-gating.md) built a lean core: a flat, append-only catalog of four
ids, advertised pre-auth on `/api/setup` and switched off by the operator for the whole instance. Three things
have changed since then.

- **Minors add features, and native clients drift.** Under [ADR 0085](0085-one-major-moved-on-a-yearly-train.md)
  a store-shipped client talks to servers of an older minor in the same major. Expenses, GPX tracks in a Memory
  or the journal arrive in minors, and the client has to see whether *this* server serves them. The minor cannot
  answer that: it counts commits ([ADR 0057](0057-release-identity-and-deployment-recipe.md) §1) and must not be
  pre-auth anyway.
- **A tenant owner has policy too.** ADR 0079 decision 8 rejected per-tenant activation of an application, with
  clutter as the problem it would solve, which "created on demand" already solves. The reason now is a different
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
   something the server **keeps** rather than serves names what it stops writing instead of what it refuses
   (`guest-write-record`: no new row, and the next purge deletes the live ones).
5. **What "off" does.** A share switch, a device switch and a requirement (decision 8) act at once, on what
   exists too: a link that keeps working, or a remembered device that keeps unlocking without the password,
   defeats the reason it was switched off. A device switch withholds its wraps (`DEVICE` for `remember-device`,
   `DEVICE_KEY` for `passwordless`) from the key material the server hands out, the way the `RECOVERY` wrap is
   already withheld. It never deletes them, so switching back on restores every remembered device, and the
   `PASSWORD` wrap is always served, so nobody is locked out (owner, 2026-10-15). A feature switch refuses only
   what is **new**.
   What exists stays readable and shareable, so there is no "activated but empty" state machine, and the gate sits
   at creation instead of on every endpoint (the cost ADR 0079 named).
6. **Server visibility is accepted where a switch needs it.** Journal, GPX and a face index get a node kind or a
   blob role the server sees. The operator then learns *that* a Memory has a track, never where. This is the same
   kind of metadata as `CHAPTER`.
7. **External services are entries.** When one is switched off, the CSP the backend serves
   ([ADR 0069](0069-backend-serves-the-web-app-shell.md)) leaves its origin out, so the browser blocks it, and the
   manifest hides it from native clients. The services' URLs become operator configuration instead of build
   variables (they were `VITE_VEYQ_MAP_*` until #439 step 4).
8. **Requirements are values on a node, and the strictest wins.** A tenant can tighten and never loosen. On the
   wire `[{ id }]` grows to `[{ id, …values }]`, the extension ADR 0032 already left room for.
9. **Presence ids retire.** Once every major in the client window serves an id, the client stops checking it, and
   the server may stop advertising it at the next train. That is a removal under ADR 0085.
10. **The catalog is cut once more before V1, then frozen.** Its append-only rule protects released clients, and
    there are none yet. From V1 on it holds strictly.
11. **Device linking and remembering a device are two independent switches** (owner, 2026-10-15). Linking answers
    *how a key reaches a device*: from another unlocked device instead of password and Secret Key. Remembering
    answers *whether the key may stay there*. Every combination is coherent:
    - With `passwordless` off, a linked device gets a session but no stored key, because the only wrap it can
      enrol is a `DEVICE_KEY` (ADR 0059 §10). `useGrantReceiver.ts` already hides "stay signed in" then.
    - With linking off, a tenant rules out the one mechanism that turns a single human confirmation into the
      Master Key, where a phished grant is an identity takeover (ADR 0059).
    - Handing the key from one of the user's own native apps to another on the same device (inbox #438) is not
      linking: it needs no network and no confirmation. Each app enrols its own wrap, and that wrap is governed by
      `remember-device`.
12. **Where each level switches** (owner, 2026-10-15).
    - **A tenant owner** switches in the tenant settings in `web/`, beside terms and mail. The tenant owner is an
      ordinary member, and ADR 0065 keeps what a member can reach out of `admin-web`.
    - **An entry a client offers before sign-in is the Server Owner's alone** (`*`, never `+`): `remember-device`,
      `passwordless` and `device-linking`. The sign-in screen offers "stay signed in" and "sign in with code" from the
      pre-auth manifest, which carries the instance level only (decision 3), so a tenant's switch there would be a
      button that fails after the click. The key material is the account's, not a tenant's, and the pairing channel
      carries no tenant either. A tenant that wants these off asks the Server Owner (cr-tenant-capability-switches,
      2026-10-16).
    - **The Server Owner** switches in `admin-web` and its CLI, through the Admin API. The state lives in the
      database and takes effect without a restart. It is not a configuration property: the
      `veyq.capabilities.disabled` list retires, and with it any question of what an unknown id in it means.
    - **Configuration as code** (switches declared in compose or Helm values) is a later topic (inbox #444).
13. **The register describes itself.** Every switchable entry carries, in English and German, *what it is for* and
    *what switching it off does*, both to new and to existing data (decision 5). The catalog holds that text and
    the API serves it. `admin-web`, the CLI and `web/`'s tenant settings render it from there, and none of them
    keeps its own copy.
14. **An operating mode is a preset, not a level** (owner, 2026-10-17;
    [ADR 0093](0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md) § 14). The Server
    Owner chooses *private* or *club, organisation or company* at first-run setup and may change it later. Choosing
    one **sets** the instance-level switches the preset names — the catalog carries each such entry's value per mode,
    so the register still describes itself (decision 13) — and nothing more: afterwards each is an ordinary switch
    the Server Owner may set on its own, and the AND of decision 2 is unchanged, with no third level. Choosing a mode
    again sets the preset's switches again, after admin-web and the CLI have shown which ones will change. A tenant's
    own switches are never touched. What the mode decides beyond switches — whether admin-web warns about an empty
    instance contact or provider identification, the variant of the terms' operator section
    ([ADR 0035](0035-server-terms-of-service.md) decisions 6–7), the statement of reasons' form — refuses nothing, so
    by decision 4 it is no entry: those read the mode itself. Today's preset differs in one entry,
    `core.public-shares.guest-write-record` (off in *private*); no entry the report path needs is a switch.

### The register

`*` operator, `+` tenant owner too, `○` presence only, `→` what the server sees.

```
core/
  *   remember-device              → DEVICE wrap (password only; the device key stands in for the Secret Key)
    *   passwordless               → DEVICE_KEY wrap (no password)
  *   device-linking               → /api/pairing/*   (built; independent of remember-device, decision 11)
  *+  group-space                  → group scope creation
  *+  personal-space               → scope kind PERSONAL; "off" = no new content, the scope stays (keys, favorites)
  *+  member-invites               → /member-invites
  *   self-registration   [idea]   → a new endpoint; off by default
  *+  public-shares                → share role READ
    *+  contribute                 → share role READ_WRITE
    *+  drop-box                   → share role WRITE_ONLY
    *   guest-write-record [planned] → stops writing veyq_guest_write_record (ADR 0093 § 10); off in the private preset
  *   breached-password            → external (HIBP); CSP connect-src (instance only: checked before sign-in)
  *+  push                [native] → external (APNs/FCM); a content-free wake-up
  ○   kept-shares (#435), search, live-collab, notifications   [later]
*+ memories/                       → node kinds MEMORY / CHAPTER
  *+  chapters                     → node kind CHAPTER
  *+  journal             [planned]→ a server-visible kind or blob role
  *+  gpx                 [planned]→ a server-visible blob role
  *+  faces               [idea]   → a server-visible role for the face index (biometric data)
  *+  places                       → external (reverse geocoder); CSP connect-src
  *+  map                          → external (map tiles); CSP img-src
  *+  public-shares / contribute / drop-box   → inherit core.public-shares
    *+  download-originals         → blob role ORIGINAL (only Memories has derivatives)
*+ depot/                          → node kind FILE, blob role FILE_CONTENT
  *+  public-shares / contribute / drop-box   → inherit; drop-box also gates creating a DEPOT_DROPBOX
*+ expenses/   [concept]           → its node kind;  *+ public-shares/… inherit
*+ inventory/  [concept]           → its node kinds; *+ public-shares/… inherit; *+ reminders-push inherits core.push
```

`places` and `map` are independent of each other. A place comes from the media's GPS or from a pin dropped by hand,
and its name is geocoded or typed. The geocoder suggests names, and the tiles carry the pin and the map view. With
both off a place still works, named by hand. The user's own consent to the two
(`web/src/features/memory/useMemoryLocation.ts`) is a third, separate thing and stays.

### Requirements

| Node | Requirement |
|---|---|
| `core.public-shares` | a link needs a password; a link expires within N days |
| `core.group-space` | joining a group needs the member's consent (today a per-user setting, `require_group_add_consent`) |
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

- **Built today** (measured: `contracts/platform/capabilities.json`): seven `core.*` entries - `core.public-shares`
  with `.contribute` and `.drop-box`, `core.group-space`, `core.remember-device` with `.passwordless`, and
  `core.device-linking`. The server computes the AND along the path (`CapabilityRegistry`); each client reads one leaf.
- **The instance-wide switch is built** (decision 12, measured: `InstanceResource.listInstanceCapabilities` /
  `setInstanceCapability`, `StoredCapabilitySwitches`, `admin-web/src/cli/commands.ts`). The Server Owner switches an
  entry in admin-web's Server → Capabilities panel or with `veyq-admin capabilities list | off <id> | on <id>`,
  through the Admin API (`GET /api/instance/capabilities`, `PUT /api/instance/capabilities/{capabilityId}`). The state
  is one row per switched-off entry in `veyq_capability_switch`, read through an in-process cache that the write
  invalidates after commit, so it acts without a restart on the one replica the chart runs. The words both surfaces
  show are the catalog's (decision 13), generated into the backend (`CapabilityIds`, `CapabilityTexts`) and served,
  never copied by a client.
- **A device switch withholds its wraps** (decision 5, measured: `KeyMaterialHandler.forUser`):
  `GET /api/account/key-material` leaves out every `DEVICE` wrap while `core.remember-device` is effectively off and
  every `DEVICE_KEY` wrap while `core.remember-device.passwordless` is, and deletes neither. The web client keeps the
  device's local key, asks for the password and the Secret Key with a line saying why, and does not re-enrol the
  device to the other tier, so switching back on restores it. Without `DEVICE_KEY` that is the Secret Key too, not
  the password alone: a device holds one convenience wrap ([ADR 0005](0005-unlock-methods-and-device-trust.md) §9).
- **A share switch reaches existing links** (decision 5, measured: `ShareGuestGate.activeShare`): while
  `core.public-shares`, `.contribute` or `.drop-box` is effectively off, every guest endpoint in core and in both
  applications answers a link of that role exactly as an unknown link, a `404` with the same body, before the
  password is asked, so a guest learns nothing of the instance's policy and web's unavailable page needs no new state.
  The one predicate every guest path already passed is where it sits, so no application handles it. Nothing is
  written onto the link, so switching back on serves it again. Presigned URLs already handed out run to their TTL, as
  after a revoke. Changing a READ link into a READ_WRITE one (`updateShare`) is refused like creating one.
- **The tenant level is built** (decisions 2, 3 and 12, measured: `TenantCapabilitySwitchHandler`,
  `StoredTenantCapabilitySwitches`, `web/src/features/tenant/TenantCapabilitiesPanel.vue`). The catalog's `levels` names
  who may switch an entry (`instance`, and `tenant` for `core.public-shares` with its two children and
  `core.group-space`), generated into `CapabilityIds.TENANT_SWITCHABLE`. A tenant owner switches in the web client's
  tenant settings (`GET /api/tenants/{tenantId}/capability-switches`, `PUT …/{capabilityId}`), one row per entry in
  `veyq_tenant_capability_switch`, read through a per-tenant cache that a write invalidates after commit. Every member
  reads the effective set after sign-in (`GET /api/tenants/{tenantId}/capabilities`), and the web client soft-gates on
  it from its first scope entry on. `CapabilityRegistry.isActive(id, tenantId)` takes the tenant at every gate: the
  share gates and the guest gate ask the share's scope's tenant, group creation the request's, node creation the
  scope's; the key material and pairing ask none (the instance-only entries above). The Server Owner's console marks
  the entries a tenant may narrow further (`tenantSwitchable`).
- **Four requirements are built** (decision 8, measured: `CapabilityRequirements`, generated from the catalog's
  `requirements`; `LinkRequirements`; `SessionService.pastPasswordMaxDays`): `core.public-shares` `linkPassword` and
  `linkMaxDays`, `core.group-space` `addConsent`, `core.remember-device` `passwordMaxDays` (instance only, as its entry).
  Each is set at its entry's levels (`PUT|DELETE …/requirements/{requirement}`, the console, `veyq-admin capabilities
  require`, the tenant settings), stored per level in `veyq_capability_requirement` and
  `veyq_tenant_capability_requirement`, and in effect at the strictest of both. Both deliveries carry the values on their
  entry, `[{ id, …values }]`. They act at once (decision 5), and that took two decisions the table above did not hold:
  a link's lifetime counts from when it was made, never from now, because a moving window would refuse a link today and
  serve it later, so an edit fixes a younger link and an older one is renewed; and `addConsent` leaves members already
  added alone, a direct add whose key is still on its way included, because the server holds no sealed name key to turn
  it into a request and removing it would delete a membership. What the gates read is what the server already held: that
  a link has a password verifier, its dates, a session family's start. Not built: the verified-identities requirement
  and `offline-cache` (above).
- **A member's share list says why a link serves no guest** (decisions 5 and 8, inbox #457, measured:
  `ShareGuestGate.guestStops`, `ShareSummary.guestStops`, `web/src/core/sharing/ShareGuestStops.vue`). The guest gate's
  one predicate now returns its reasons, and `activeShare` is that list being empty: revoked, expired, a space paused or
  being deleted, each switch along the role's path at each level (`CapabilityRegistry.offSwitches`, whose emptiness is
  `isActive`), each requirement missed (`LinkRequirements.missed`, whose first is the refusal a write meets). The share
  list serves the same list on every row, as codes the client words in its own languages, so the list cannot call a link
  on that a guest gets as unknown. Only a member who may list the link reads it; a guest's `404` is unchanged, byte for
  byte. Memories', the folder's and the drop box's panel draw it with one component. A missed requirement is fixed on
  the same link, address and key kept (owner, 2026-10-16): its owner gives it the password or the end date from the row,
  and the edit is judged as it leaves the link, so a write toward a requirement is never refused by it; a link older
  than the lifetime is renewed as a new link, and the row says that it gets a new address. A switch names who can switch
  it back on. The client names the three share entries itself, since a member's set carries no catalog words; an entry
  it does not know is named plainly.
- **The external services are entries** (decision 7, measured: `ExternalServicesHandler`, `ExternalServiceOrigins`,
  `AppShellRoutes.cspNow`): `memories.map` and `memories.places` (`*+`) and `core.breached-password` (`*`, because the
  join and recover screens check before sign-in, decision 12). Source `external-service`: an entry is active only while
  the Server Owner configured its address (`veyq_external_service`, seeded with OSM tiles, Nominatim and the HIBP range
  API; admin-web's **External services**, `veyq-admin services`). Both deliveries carry the address as `serviceUrl`
  (and the tiles' `serviceAttribution`), the web reads only that, and the served CSP names an origin only while its
  entry is active at the instance level: the origins are a snapshot recomputed after each write, because the header is
  written on the event loop, so a tenant's own switch acts through its set and not through the policy. `memories` is
  the first application root of the tree. **A place works with both off** (inbox #458, measured:
  `MemoryLocationEditor`, `useMemoryLocation.openLocationEditor`): without `memories.map` the location editor opens
  with no map and no consent dialog, takes a typed name and keeps the position the photos' GPS gives (`auto`, the
  medoid), a pin set earlier, or none — a place that is a name only, which the sealed `location` always allowed
  (`derived` is optional in the v1 shape, so no new version) and which every map skips. Without the map the editor
  asks no geocoder even where `memories.places` is on, because the one consent the geocoder rides on is asked only
  with the map (ADR 0018); `memories.places` alone still names a photo in the lightbox for a member who consented
  earlier. Not built: admin-web's own policy, a static `<meta>`, which keeps the default HIBP origin.
- **Not built:** configuration as code (#444). The off states are driven in a real browser by lane L1's
  `capability-off-states.spec.ts` (#447, cr-stack-e2e phase 5); a share list's row of a stopped link is not.
- **The two enforcement gaps of the flat ids are closed.** The server gated `core.group-space` only at group creation
  although the catalog names five use-cases - decided a doc fault, since a feature switch refuses only what is new
  (decision 5), so group creation is the one gate. Depot let a member create a drop box while uploader links were off,
  and the drop box's Share button read the READ leaf - now creating one needs `core.public-shares.drop-box`, and its
  share needs the same leaf, not the READ one.
- **`download-originals` waits for a measurement** (owner, 2026-10-15). If guests stream video from the
  `ORIGINAL` ([ADR 0047](0047-inline-video-playback-range-streaming.md)), switching it off also stops video
  playback for guests until a video preview tier exists. This must be measured before the entry is built.
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
