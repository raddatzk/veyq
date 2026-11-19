# ADR 0150 — A received link opens in the app as its guest — the share page's rules in the core, a kept link a bookmark, the app opened by a `veyq:` link that carries nothing
- Status: Accepted
- Built: partly — the core and the bridge (cr-app-received-links phases 1–6, 2026-11-19); the platforms' screens, their
  credential stores and the passes follow in that CR; § O is cr-open-in-the-app's, not built
- Date: 2026-11-19
- Model: [native-apps](../crosscutting/native-apps.md) (the share page: [uc.sharing.share-public-viewer](../features/sharing/use-cases/uc.sharing.share-public-viewer.md))

## Context

Inbox #435 and #438, V2 block H. A link someone sent opens only in a browser: the apps build links for others
(`ShareLinks`, `/s/<shareId>#k=<K_share>`) but parse none, hold no guest session, and list a kept link — a
`SHARE_BOOKMARK` node ([ADR 0120](0120-a-received-link-is-kept-as-a-bookmark-node-that-opens-and-reports-where-the-share-lives.md))
— with an icon that opens nothing. Measured 2026-11-19 at the refine (each claim's command is in
`changes/cr-app-received-links.md` § Measured at refine, kept here as the claim itself):

- **The web's share page** is `/s/:shareId` with no session gate; its key is read from the fragment and never sent —
  the resolve takes the id and `X-Share-Password` only. Its parts are `resolvePublicShare.ts` (resolve, key check, the
  password token `Argon2id(password, pwSalt)`), `shareGates.ts` (password → terms → note → content), `guestIdentity.ts`
  (a write link's guest session in `localStorage`), `shareNote.ts`, `shareInstance.ts` (`/api/setup`, `EMBED_MAJORS`)
  and `shareAt.ts`. Behind the gates READ / READ_WRITE draw `ShareTreePage` (core's level at a folder, the kind's detail
  elsewhere, handed the guest's access); WRITE_ONLY draws the root kind's `shareBody` (Depot's drop box, Memories'
  uploader).
- **The core already holds the guest's crypto and client** (the generated `SharingApi`, `V1.sharePasswordToken`, the
  conformance's `share_write_token`), and the kinds' models take a guest's access (`GalleryModel`'s
  `MemoryRights.Access.Guest(role)`, `FileModel`'s `FileRights.Viewer`) — but the Pot's session calls a tenant's
  `ExpensesApi` directly.
- **The device store is no place for a secret** (`DeviceStore` holds the trust marks only), and a guest's session is one.

## Decision

### 1. No account-private list: a link kept privately is a bookmark in the personal space

#435 first asked for a list sealed under the Master Key, not a node. A `SHARE_BOOKMARK` in the member's personal scope
(one per member per tenant) already is private to the account: the scope has no other member, its key is under the
Master Key, and ADR 0120 decision 7 keeps a bookmark out of every link and every review — and every client of the
account reaches it once the apps open bookmarks. A second store would mean a new endpoint, a second sealed format, a
second list on every client and a second answer to rotation, for the same privacy; the operator learns the same either
way (that something of this kind exists, and when). **Owner (2026-11-19): no separate list — but a button to keep the
link for good**: decision 6's *Keep*.

### 2. A received link opens as its guest through the core's port of the share page

Core's (a link is core's, ADR 0079 decision 3), in `core/…/sharing/`:
- **`ReceivedLink.parse(text)`** ports `parseShareLink` and `keptLink` exactly. Both clients answer to one set of cases,
  `contracts/platform/share-links/cases.v1.json` (accepted inputs with their normalized link; refused ones with their
  problem), read by the web's `bookmarkLink.test.ts` and the core's test — the search vectors' form (ADR 0146). The app
  refuses a `#moderation` link with a problem of its own (decision 8; the bookmark's parse calls it `noKey`, as the web
  does); `&report` is kept as a request to open the report dialog (ADR 0120 decision 6), never stored.
- **`ShareInstance`** reads `/api/setup` of the link's origin **for every link**, this instance's too — the app is a
  foreign client to every instance — and opens only a major `ClientWindow` speaks (the web's `EMBED_MAJORS` rule) with
  `core.public-shares` on. `.embeddable` is not asked: it gates another origin's page inside a web page (ADR 0121
  decision 3), and the app is no web page.
- **`ShareGates`** ports `shareGates.ts`: the locked resolve, the password token, the tenant's terms, the note, then the
  content, each decided before the state is ready; the guest notice and a write link's naming as the web's.
- **`GuestTree`** holds the resolved subtree and `K_share`, opens the root's key from the share's `wrap` and hands each
  node's key down as `ShareTreePage` does. A node opens as its kind's model through `AppKinds.guestDoor` (decision 5).
- **Rejected: a web view of the share page inside the app.** It would hand the key to an embedded browser's history and
  storage, and the owner's rule is the app's own screens with the platform's look.

### 3. The key in memory; the guest's session in the platform's credential store

`K_share` lives only in the open link's model and goes with it: no `DeviceStore`, no offline store (ADR 0140 is an
account's, and a guest has none), no recent use (ADR 0122 decision 6 records a member's objects only), no log, no
bridge update. No request carries it — path, query, header or body; a write link's `X-Share-Write` carries the token
derived from it (`share_write_token`), as on the web. A write link's **guest session** (its identity id and contributor
token, an uploader's `K_up`; the web's `localStorage` entry) is a secret, so the apps keep it where they keep the
device's wraps — the Keychain, the Android Keystore-wrapped store, the Secret Service, the Windows credential locker —
behind core's `GuestSessions` port, under a name that is `sha256(origin ‖ "\n" ‖ shareId)` truncated, never the id itself
(the server cannot name the entry). The terms' and the note's acceptances, no secret, are in `DeviceStore` under the
same kind of name. *Not you?* forgets the session as the web does.

### 4. Where it opens: *Open a link*, before and after the sign-in; *Back* at the share's root

The app gains **Open a link** — a text field with the platform's paste control (iOS `PasteButton`, so no paste prompt;
elsewhere a Paste button that reads the clipboard on the tap only, never on its own) — **on the sign-in screen under the
sign-in** (the share page needs no session) — **and nowhere else** (owner 2026-11-19, after seeing it in the account
menu, the drawer's foot and the Mac's File menu, where the owner's question 2 had first placed it: a signed-out person
has no other way in; a signed-in one does not need it there). The unlock screen of a trusted device keeps it, as the
sign-in's own. The guest view is **a screen above the shell**: its
own header (the share's root name), its own back stack, no drawer and no bar (ADR 0122's are a tenant's). **At the
share's root a *Back* asks to confirm** — the key lives only in memory, so leaving drops the link unless it was kept —
**and then returns to where the person was** before opening it: the sign-in screen, or the shell's place (owner
2026-11-19). A password of a link opened from a bookmark is handed in memory (ADR 0120 decision 5's same-origin path —
the app has no tabs, so every origin takes it).

### 5. Each kind's guest door is its application's

| Kind | READ / READ_WRITE | WRITE_ONLY |
|---|---|---|
| FOLDER (core) | core's level over the guest's tree | — |
| FILE (Depot) | `FileModel`, `Viewer.READ` / `READ_WRITE` | — |
| DEPOT_DROPBOX (Depot) | — | the drop page: the guest names itself, uploads |
| MEMORY, CHAPTER (Memories) | `GalleryModel`, `Access.Guest(role)` | the guest uploader under its own `K_up` |
| EXPENSES_POT (Expenses) | `PotSession` over a guest's door | — |

`AppKinds.guestDoor(kind, role)` answers each in its application's package; core asks, never knows an application's
rules (ADR 0079, ADR 0138 decision 2). `PotSession` is refactored onto a door first (the member's the tenant's, its
behaviour unchanged, the Pot's tests the proof), then the guest's door over Expenses' share paths. A kind without an
answer opens the web's "not shown here" state of `ShareTreePage`, never a half view.

### 6. Bookmarks in the apps, as the web's — and *Keep*

- **New → Bookmark** at a space's top or in a folder: the web's dialog — the link checked as it is typed by decision 2's
  parse, its password, the name defaulting to the host, the group space's re-sharing warning (ADR 0120 § Consequences) —
  the meta sealed per `share-bookmark-meta.v1.schema.json`, padded to a multiple of 512.
- **Keep** (owner 2026-11-19, the apps only as a first step; the web's share page keeps none — #1013 holds the web's
  half): the guest view of a signed-in person offers *Keep*, which creates the same `SHARE_BOOKMARK` with the link as
  opened (normalized, no `&report`) and a password the person entered, in a place they pick — **their personal space
  first**, then a group space's top or a folder.
- **Opening:** while `core.share-embedding` is on for the tenant, a bookmark's page shows the share in place under its
  header (ADR 0121 decision 1: decision 2's guest view embedded); **Open on its own page** (the header's
  `open-own-page`) opens decision 4's screen. Off: the row opens decision 4's screen. A meta that does not read: the
  web's unreadable state, offering its removal.
- **Report shared content** opens the guest view with its report dialog open (`&report`'s meaning, in process), to the
  share's own operator; **Report name** reports the name (`NAME`, ADR 0093 § 9) to this instance. A selection holding a
  bookmark reports its name only; no path hands this instance a bookmark's key (the backend refuses a `NODE` report of
  one).
- Download and copy leave the kind out and name it, as the web's (ADR 0120 decision 8).

### 7. What the server sees, and the bridge

The server sees what the web's share page makes it see — the same requests in the same order, the app's own
`User-Agent` — plus the link instance's `/api/setup`, which the web's bookmark page reads too. Nothing of the app's
*Open a link*. On the bridge (Linux, Windows): `Call.LinkOpen(text)` → `Answer.LinkOpened(id)` and the guest view's
updates by that id; the host hands the pasted text in once, and **no `Update` carries the key or the link** (the guest
view's state names the share's root and nodes only).

### 8. A link the app does not open

A `#moderation` link (a moderator's review), a major the app does not speak, an instance whose `core.public-shares` is
off, a non-veyq answer: the screen says which, with the web's words where the web has them, and offers **Open in the
browser** — the link as received, handed to the system browser, the same thing a click on it in the chat would have done.
A revoked, expired or re-keyed link shows the share page's *unavailable* state.

### 9. Words

New keys in `src/mobile/words/words.json` (EN+DE), the web's own words wherever the web has the screen (the gates, the
naming, the bookmark dialog); new only *Open a link* / *Link öffnen*, *Paste the link you received* / *Füge den Link ein,
den du bekommen hast*, *Open in the browser* / *Im Browser öffnen*, *Keep* / *Behalten* and the *Back* question.

### O — The app opened by a `veyq:` link that carries nothing (cr-open-in-the-app)

1. **`veyq:` with two forms, and nothing read.** `veyq://open` opens the app where it is; `veyq://link` opens *Open a
   link*. The core's `DeepLink.parse(uri)` answers `Open` or `Link` and reads nothing else — a path, a query, a fragment,
   user info are ignored, never stored, never logged. A custom scheme is owned by no one, so a key in it would be handed
   to whoever claimed the scheme (and to the browser's history, a desktop handler's command line). Rejected: the key in
   the deep link's fragment; the key sealed for the app (the browser shares no key with it); universal / app links (a
   self-hosted domain has no publisher's association file, owner 2026-11-18).
2. **The web's banner**, one per page, dismissible (the dismissal a per-device convenience): on the share page *Copy link
   and open the app* (the normalized link to the clipboard on that click, then `veyq://link`; the password is not
   copied); on the four mailed pages after their step succeeded, `veyq://open`.
3. **The registrations:** Android's intent filter on `MainActivity`; `CFBundleURLTypes` on Apple; the `.desktop`'s
   `x-scheme-handler/veyq` with `HANDLES_OPEN` on Linux; the MSIX's `windows.protocol` on Windows.
4. **What the server sees:** nothing — a `veyq:` navigation leaves the browser for the system. **Residual:** on the share
   page the click puts the link on the clipboard and opens whichever app holds `veyq:`; an app that claimed the scheme and
   reads the clipboard when opened learns the link — the share dialog's copy's exposure plus the launch.

## Consequences

- Both clients answer to one set of link cases; a change to the link's shape changes the file and both tests.
- A guest's session on a device is as safe as the device's wraps; a lost device leaks a write link's identity (its
  token can write as that guest until the link is re-issued), never `K_share`, which is not kept.
- A bookmark kept privately is an ordinary node of the personal space: it is in that space's rotation, its trash, its
  search (by name), never in a link or a review.
- ADR 0120's "the account-level list remains open as #435" is answered by decision 1; ADR 0149 decision 9's "the apps
  reach no link as a guest yet" by decisions 2 and 5.
- **Built 2026-11-19 (cr-app-received-links phases 1–6):** the core's `sharing/` (the link, the instance, the gates, the
  guest's tree and session port, the bookmark's meta, *Open a link*'s and *Keep*'s models), each kind's guest door, the
  Pot onto a door, and the bridge's calls; the platforms and the passes follow in that CR.

## Alternatives considered

- **A Master-Key-sealed list of links** (#435's first idea) — decision 1.
- **The share page in a web view** — decision 2.
- **The guest's session in `DeviceStore`** (as the web's `localStorage`) — a token that writes as the guest would sit in
  a plain file; decision 3.
- **The key in the `veyq:` link** — § O1.
