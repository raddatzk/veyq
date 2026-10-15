# ADR 0088 — The capability register is a tree, switched off by the operator and by a tenant owner

- Status: Accepted 2026-10-15; not built (§ Consequences; inbox #439). It replaces
  [ADR 0079](0079-veyq-is-a-platform-memories-is-its-first-application.md) decision 8 and reshapes
  [ADR 0032](0032-capability-discovery-version-gating.md) §2, §3 and §5, all three edited to match.
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
   and what happens to **existing** data. An entry that cannot name both is presence only (`○`).
5. **What "off" does.** A share switch and a requirement (decision 8) act at once, on existing links too: a link
   that keeps working defeats the reason it was switched off. A feature switch refuses only what is **new**.
   What exists stays readable and shareable, so there is no "activated but empty" state machine, and the gate sits
   at creation instead of on every endpoint (the cost ADR 0079 named).
6. **Server visibility is accepted where a switch needs it.** Journal, GPX and a face index get a node kind or a
   blob role the server sees. The operator then learns *that* a Memory has a track, never where. This is the same
   kind of metadata as `CHAPTER`.
7. **External services are entries.** When one is switched off, the CSP the backend serves
   ([ADR 0069](0069-backend-serves-the-web-app-shell.md)) leaves its origin out, so the browser blocks it, and the
   manifest hides it from native clients. The services' URLs become operator configuration instead of build
   variables (`VITE_VEYQ_MAP_*` today).
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
    - **The Server Owner** switches in `admin-web` and its CLI, through the Admin API. The state lives in the
      database and takes effect without a restart. It is not a configuration property: the
      `veyq.capabilities.disabled` list retires, and with it any question of what an unknown id in it means.
    - **Configuration as code** (switches declared in compose or Helm values) is a later topic (inbox #444).
13. **The register describes itself.** Every switchable entry carries, in English and German, *what it is for* and
    *what switching it off does*, both to new and to existing data (decision 5). The catalog holds that text and
    the API serves it. `admin-web`, the CLI and `web/`'s tenant settings render it from there, and none of them
    keeps its own copy.

### The register

`*` operator, `+` tenant owner too, `○` presence only, `→` what the server sees.

```
core/
  *+  remember-device              → DEVICE wrap (password only; the device key stands in for the Secret Key)
    *+  passwordless               → DEVICE_KEY wrap (no password)
  *+  device-linking               → /api/pairing/*   (built; independent of remember-device, decision 11)
  *+  group-space                  → group scope creation
  *+  personal-space               → scope kind PERSONAL; "off" = no new content, the scope stays (keys, favorites)
  *+  member-invites               → /member-invites
  *   self-registration   [idea]   → a new endpoint; off by default
  *+  public-shares                → share role READ
    *+  contribute                 → share role READ_WRITE
    *+  drop-box                   → share role WRITE_ONLY
  *+  breached-password            → external (HIBP); CSP connect-src
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
| `core.public-shares` | share only with verified identities (planned; not buildable as specified) |
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

- **Built today** (measured: `contracts/platform/capabilities.json`): four flat ids. They map onto the tree as
  - `sharing.public-links` → `core.public-shares` + `.contribute`
  - `sharing.uploader-links` → `.drop-box`
  - `spaces.groups` → `core.group-space`
  - `account.device-trust` → `core.remember-device` + `.passwordless`
- **Enforcement gaps in what is built**, tracked in the inbox:
  - The server gates `spaces.groups` only at group creation (`grep -c isActive ScopesHandler.kt` → 1), although
    the catalog names five use-cases.
  - Depot lets a member create a drop box while uploader links are off (`web/src/features/depot/capabilities.ts`
    `createDropBox: place`), and the drop box's Share button checks `publicLinks`, so the box can never be shared.
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
