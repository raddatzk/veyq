# ADR 0150 — A received link opens in the app as its guest — the share page's rules in the core, a kept link a bookmark, the app opened by a `veyq:` link that carries nothing
- Status: Accepted
- Built: decisions 1–9 yes — on every app (Android, iOS, macOS, Linux, Windows), 2026-11-19; passed on Android, Linux
  and Windows as throwaways and by the core's live tests; the macOS pass is open and the iPhone's and iPad's are the
  owner's (§ Consequences); § O yes — the web's banner and `veyq:` on every app, 2026-11-19; passed on the web,
  Android, Linux and Windows as throwaways; the Mac's `open` and the iPhone's are open (§ Consequences); § S yes — a link
  shared into the app on Android and iOS, 2026-11-24, by the core's and Android's JVM tests and the Apple builds; the
  Android and iPhone share passes are owed (§ Consequences)
- Date: 2026-11-19
- Model: [native-apps](../crosscutting/native-apps.md) (the share page: [uc.sharing.share-public-viewer](../features/sharing/use-cases/uc.sharing.share-public-viewer.md))

## Context

Inbox #435 and #438, V2 block H. A link someone sent opens only in a browser: the apps build links for others
(`ShareLinks`, `/s/<shareId>#k=<K_share>`) but parse none, hold no guest session, and list a kept link — a
`SHARE_BOOKMARK` node ([ADR 0120](0120-a-received-link-is-kept-as-a-bookmark-node-that-opens-and-reports-where-the-share-lives.md))
— with an icon that opens nothing. Measured 2026-11-19 at the refine (each claim's command was in
cr-app-received-links' § Measured at refine; the CR is deleted, the claim kept here):

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
  problem), read by the web's `bookmarkLink.test.ts` and the core's test — the search vectors' form (ADR 0146). Since
  2026-11-22 the web draws this very code ([ADR 0155](0155-the-web-draws-over-the-kotlin-core-application-by-application-after-a-measured-pilot.md)
  § The share links' slice, `@veyq/core-js/sharing`): one parser, where three inputs the two had read apart now read as
  the core did (a non-canonical key, an empty user info, a host in Unicode — each refused). The app
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

### S — A link shared into the app from another app (inbox #1015)

A handover without the clipboard: the share sheet of the chat or the browser the link arrived in hands it to veyq.

1. **The core finds the link; *Open a link* checks it.** A shared text is often more than the link ("Look at this:
   https://…", a link in angle brackets or a mark-up's parentheses). The core's `SharedText.linkIn(text)` answers *One*
   (the whole text, trimmed, when *Open a link* opens it; else the one distinct link among the words holding `http://` or
   `https://`, each read from its scheme on and without a sentence's closing punctuation — no share link ends in one),
   *Several* (two or more distinct links the app would open: which one was meant is never guessed) or *None* (no
   `http(s)` word at all). Where none of the candidates opens, the first one is the answer, so its own refusal says why
   (a foreign link `NOT_A_SHARE`, a moderator's review `MODERATION` with *Open in the browser*). `OpenLink.received(text)`
   puts *One* through the field's own check (decision 2's `ReceivedLink.open`) and refuses *None* and *Several* with words
   of their own (`link_problem_no_link_in_text`, `link_problem_several_links`; the app's alone — the web has no share
   sheet), leaving the field empty: nothing of a text that is no link to open stays on screen. In the core so every app
   shares it, never exported to the web's bundle (`SharingWeb` names what the web draws).
2. **Each platform opens it as *Open a link* does:** the field over whatever shows, the received text checked, and opened
   at once where it opens; where not, the field stays with its reason — what a paste of the same text would have shown.
   Opened over an open tenant, the guest view offers *Keep* (decision 6), as *Open on its own page* does.
   - **Android:** `ACTION_SEND` with `EXTRA_TEXT` and no `EXTRA_STREAM` (`ShareIn.textOf`; the manifest's `*/*` share
     target of ADR 0138 already takes `text/plain`). The text is taken off the intent once read, and a start from the
     recents list (which hands the task's first intent again) opens nothing — § O's rule for `veyq://`. A file shared
     with a caption stays a file's share.
   - **iOS:** the share extension (ADR 0138), whose activation rule already admits a link and a text (`public.url` and
     `public.plain-text` conform to `public.data`, measured with `UTType.conforms` 2026-11-24). A share whose attachments
     carry no file (`IncomingFiles.sharedText`: each link's or text's line) opens **in the extension**, as its guest,
     with the app's own guest screens (the extension compiles them), and the extension closes once the field is
     cancelled or the link left (`GuestLinks.onDone`). *Open in the browser* is not offered there: an extension opens no
     URL; the refusal's words say the link is the browser's.
   - **macOS, Linux, Windows:** no share sheet in scope. A Mac's Share menu would need a macOS share extension, which
     the project has none of (ADR 0138 built iOS's alone); a desktop's links arrive by the clipboard and *Open a link*.
3. **Why iOS opens it in the extension** (decided 2026-11-24, logged from the CR's decision log). An extension cannot
   open its containing app (`NSExtensionContext.open` works for widgets only; `UIApplication` is not extension API). The
   options measured against the project as it is — the app and the extension already share the app group
   `group.app.veyq` and the Keychain access group `app.veyq.shared` (`Veyq-iOS.entitlements`, `VeyqShare.entitlements`):
   - **The guest view in the extension** — no new entitlement, no key on disk: the key lives in the extension's
     model and goes with it (decision 3). **Chosen.**
   - **The link waits in the app group for the app** (ADR 0138 decision 6's way for a Memory's files) — no new
     entitlement, but `K_share` would rest on disk in the group's container until the app opened, against decision 3
     (the key in memory only), and nothing would tell the person to open the app. Rejected.
   - **The link to the app through `veyq:`** — § O1 rejects the key in a custom scheme (owned by no one), and an
     extension cannot open the URL anyway (the responder-chain `openURL:` workaround is no public API, rejected at
     review). Rejected.
   - **A universal link** — needs the Associated Domains entitlement and a publisher's file on every self-hosted domain,
     rejected in § O1 (owner 2026-11-18).
   **Residual:** a share extension runs under a tighter memory limit than the app; a large guest gallery may hit it
   (measured on no device yet — the iPhone pass's). A write link's uploads made in the extension run while it is open,
   as a Pot's receipts there do (ADR 0138 decision 2).
4. **What the server sees:** nothing new — the link opens as decision 7's guest view does. Nothing of the shared text is
   logged or kept: the core reads it once and the platforms hand it on.

## Consequences

- Both clients answer to one set of link cases; a change to the link's shape changes the file and both tests.
- A guest's session on a device is as safe as the device's wraps; a lost device leaks a write link's identity (its
  token can write as that guest until the link is re-issued), never `K_share`, which is not kept.
- A bookmark kept privately is an ordinary node of the personal space: it is in that space's rotation, its trash, its
  search (by name), never in a link or a review.
- ADR 0120's "the account-level list remains open as #435" is answered by decision 1; ADR 0149 decision 9's "the apps
  reach no link as a guest yet" by decisions 2 and 5.
- **Built 2026-11-19 on every app** (cr-app-received-links): the core's `sharing/` (`ReceivedLink`, `ShareInstance`,
  `ShareGates`, `GuestTree`, `GuestView`, `GuestAccess`, `GuestReport`, the `GuestSessions` port, `BookmarkMeta`,
  `BookmarkCreate`, `BookmarkPage`, `OpenLink` and `GuestScreen`), each kind's guest door in its application's package
  (core's `GuestLevel`, Depot's `DepotGuest`, Memories' `MemoriesGuest`, Expenses' `PotGuest` over the Pot's new
  `PotDoor`), the bridge's `Received.*` and `Bookmark.*`, and each platform's screens: Android `GuestLinks.kt`,
  `GuestScreens.kt`, `BookmarkScreen.kt`; Apple `GuestLinks.swift`, `BookmarkScreen.swift` (`PasteButton`); Linux
  `received.rs`, `bookmark_page.rs`, `bookmark_create.rs`; Windows `ReceivedView.cs`, `BookmarkPage.cs`,
  `BookmarkCreateDialog.cs`. The guest's session is in each platform's credential store under its hashed name
  (`veyq-guest:<hash>`): Android's Keystore-sealed store; the Keychain on Apple (`KeychainGuestSessions` — where the
  Keychain refuses, as an ad-hoc Mac or an unsigned simulator build's does, the session is kept in memory for the run);
  a Secret Service item of the device keys' schema on Linux (`LinuxGuestSessions`, alias `guest/veyq-guest:<hash>`, no new
  dependency); the Credential Locker on Windows (`WindowsGuestSessions`, WinRT's `PasswordVault` through C helpers, user
  `veyq`). No permission, entitlement or dependency was added.
- **The owner's answers** (2026-11-19): **no separate list** — a link kept privately is a bookmark in the personal space
  (decision 1); **_Keep_ in the apps only** as a first step, the web share page's half staying inbox #1013 (decision 6);
  **a share's root has a confirmed _Back_** that returns where the person was (decision 4); **_Open a link_ only on the
  sign-in and unlock screens** — built first also in the drawer's foot (Android), the drawer and the Mac's File menu
  (Apple) and the switcher's menu (Linux, Windows), and taken out of each the same day (decision 4). Two answers given
  while this was built belong to the shell, not to this ADR, and are recorded where its rules live
  ([src/mobile/CLAUDE.md](../../src/mobile/CLAUDE.md), the drawer and the switcher; [native-apps](../crosscutting/native-apps.md)
  § Spaces and navigation): a drawer section with nothing in it is not shown, and every switcher row names its server
  (and the account where two signed-in accounts list the same tenant).
- **The two answers together** (read in the code, not measured): *Keep* is offered where a tenant is open
  (`GuestScreen`'s `tenant`), and *Open a link* now only where none is — so a freshly received link reaches *Keep* only
  when the sign-in screen is opened over an open tenant (adding an account); otherwise a person keeps it by *New →
  Bookmark*, and *Keep* shows on a kept link's *Open on its own page*. Whether *Keep* wants a way in for a signed-in
  person is the owner's, carried in inbox #1013.
- **Where it was built otherwise than decided.** *Decision 7's names:* the bridge's calls are `Received.*`
  (`Call.ReceivedOpen(text)` → `ReceivedOpened(id)` or `ReceivedProblem(why, browser)`), since `Link.*` is device
  linking's. *Decision 6's opening:* a kept link is listed on its level as the web's *All* tab lists it and opened as
  core's page (`Shell.bookmark`, `ShellModel.openBookmark`, a search's hit by `ShellModel.found`), its page left by the
  core after its rename or trash (`ShellModel.afterRename`, `afterTrash`) — the Android pass found the shell listing and
  opening none (#1032). *The guest's reports:* a viewing guest reports from the views as the web's dialog does (the root
  whole at `&report` and *Report shared content*, a node whole or by name, a medium as an `ITEM`; `GuestReport.nodeSheet`),
  the link itself only on an upload link (#1033); a kept link's report sheet offers its name alone (`ReportSheet.offersName`
  false for a `nameOnly` sheet, #1036). *A READ_WRITE guest's level* adds a folder and files and reads the link anew after
  (`GuestLevel.writes`, `GuestView.reload`), a guest's Memory plays its music (#1034); a READ_WRITE guest replaces a file
  on the link's own queue, signed in or out (`GuestAccess.replace`, the bridge's `Received.Replace`), and a writing guest
  of a Pot gets the web's guest Settings — its people added and renamed, nothing else (`PotView.settingsOffered`,
  `metaShown`, #1035). *The guest-write record's notice* (ADR 0093 § 10) stands before every guest write on every app:
  in a READ_WRITE guest's naming question and on a drop box's and an uploader's page, with the N the resolve served
  (#1018).
- **A READ_WRITE guest moves within the link's folders** (#1063, built 2026-11-22; the web's `move.ts` `guestMover`): the
  core's `sharing/GuestMove` — one `updateShareNode` per node, its key re-wrapped and its name and slug token sealed
  under the destination folder's key, sent after the core's one naming flow (`GuestAccess.naming`; declined, nothing is
  sent or said: `NodeMove.Outcome.DECLINED`), a lost race read anew and built once more, a re-key in flight waited on.
  It is offered where the web's rights offer it (`GuestMove.moves`): a node a folder of the link holds — never the link's
  root —, not in or of an identity folder, of a kind its application lets a guest move (`ApplicationKinds.guestMoves`: a
  file, a Memory and a folder; never a chapter, a drop box or a Pot). **The browser is the member's** `MoveSheet`, its
  top the link's root (`MoveSheet.Door.top`, which `NodeMove` hands the platform's `MovePlan` as `guest`): the trail
  starts at the link named as the link, the places are its folders only, a guest copies nothing. A guest's level carries
  the member's row menu (Move) and Select (`GuestLevel.move`: `rowPlan`, `tools`, `selection`, `sheet`,
  `selectionSheet`; a guest's selection holds a move only and is cleared when the link is read anew after a write); the
  bridge carries them as `ReceivedRow.menu`, `ReceivedPage.tools` and `selection`, `Received.Move` and `Received.Select*`.
  Every app draws them with the member's own pieces: Android `GuestScreens.kt` (the long press's menu, the Select toggle,
  `SelectionBarBody`, `NodeMoveSheet`), Apple `GuestLinks.swift` (the context menu, the toolbar's Select, `SelectionBar`
  with its `guest`, `NodeMoveSheet`), Linux `received.rs` (the row's menu, the header's Select, `selection::bar_with`,
  `move_sheet::open_call`), Windows `ReceivedView.cs` (the row's flyout, the CommandBar's `Selection.GuestTool`,
  `Selection.Bar` with `Selection.Guest`, `MoveDialog.OpenWith`). Held by `GuestMoveTest` over a played link whose
  placement write re-parents the row (bitten: a Pot offered a move, the trail rooted above the link, a name sealed under
  the node's own key each turned it red) and `ReceivedBridgeTest`. **Not clicked on any app** (inbox #1063 holds the
  passes).
- **What the passes proved** (each as a throwaway owner and member, made and erased by the pass; the links minted by the
  owner through the core or the bridge, not the web). *Android* (a throwaway API 36 emulator): signed out — a READ
  Memory; a READ_WRITE folder (named, a file replaced); a password Pot (a wrong password refused, an entry added as the
  named guest, its session surviving an app restart); a drop box's and a Memory's upload links (the record's notice
  before the naming, "kept for 7 days", a file and a photo sent, the owner's read listing them as guests'); signed in as
  the member — *Keep* (two bookmarks made) and *Back* with its question; a WRITE_ONLY link's report received; the app's
  files (`run-as app.veyq`), logcat and `recent_tasks` held no key, share id or b64/hex canary, and the guest sessions sat
  under `veyq-guest:<hash>`. *Linux* (Broadway, two passes): signed out — a password READ_WRITE Memory (a wrong password
  asked again, the notice in the gallery and in the naming question, a photo added; *Back* asked, *Leave* to the
  sign-in) and a Memory's uploader link; `secret-tool search` listing `guest/veyq-guest:<hash>` labelled "veyq guest
  session", the guest still named after a restart; signed in — *Keep* into the tenant's space, *New → Bookmark*'s dialog
  with its group-space line; a READ_WRITE folder (named, a folder and a file added, `seed.txt` replaced — the owner
  listing all three; a row's report sheet), a READ_WRITE Pot (Entries, Overview, the name asked before *Add entry*), a
  READ folder kept, its bookmark listed, opened in place, renamed, moved into a folder and deleted (the page left, Undo
  offered); the app's stderr held none of the links' share ids or keys. *Windows* (the VM through UI Automation, a
  throwaway with a member on the fixed tenant `tw-win.localhost`): signed out over the unlock — a non-link refused with
  the core's words; the password gate (a wrong one asked again); a Memory's gallery with the notice, *Add photos* → the
  naming question with the notice → a photo added; the guest still named after a restart and *Not you?* removing its
  Credential Locker entry; *Back* asked (*Stay*, then *Leave* → the unlock); the uploader's and the drop box's pages; a
  folder's level, a file's page and *Report shared content*'s sheet (not sent); a Pot (named on *Add entry*, an entry
  saved as the guest); a READ_WRITE guest's file replaced signed out (revision 2); a guest Pot's Settings with its
  currency and categories hidden; signed in — *Keep*, *New → Bookmark* (a bad link refused, a good one kept), the kept
  link's page in place, *Open on its own page*, *Report shared content*, the header's report of its name, a bookmark
  deleted from its header leaving its page. `%LOCALAPPDATA%\veyq`, `%TEMP%`, INetCache and Recent (two scans, 982 and 1137
  files) held none of the links' keys (b64url, b64, hex), share ids or the password; the Credential Locker listed
  `veyq-guest:<hash>` / `veyq` only. *The core's live tests* as throwaways: `ReceivedLinkLiveTest` (a READ_WRITE Memory
  link with a password opened signed out — the instance, the password and the gates passed, the owner's photo read
  through the link byte-equal, the guest named and its photo listed by the owner as a guest's, no request of the visit
  holding `K_share` as b64url, b64 or hex; the link kept as a bookmark in the personal space and read back with its
  password; the bookmark and the Memory trashed and purged at its end, so it holds on a used throwaway, #1205), `GuestGapsLiveTest` (#1032–#1034: a READ Memory link reported whole and a medium as an `ITEM`, both received;
  a READ_WRITE folder's guest adding a folder and a file the owner lists; a bookmark listed on its level, found by search
  and opened on its own page), `GuestRemainderLiveTest` (#1035: a guest's file replaced on the link's queue and read anew;
  a Pot guest's Settings with its people alone; a kept link's page renamed and, trashed, left).
- **What no pass has proved yet.** **macOS: no pass ran** — the agents' sessions have no full-screen control
  (`src/mobile/CLAUDE.md` § Traps); the coordinator runs it from the main session (`PasteButton` on the sign-in, the
  guest screen, the record's notice before a guest's write — Apple's part of #1018, built and never seen —, the Keychain
  entry and *Not you?*, a bookmark in place). **The owner's:** an iPhone and an iPad — a link
  from Messages pasted signed out, a bookmark opened in place, the Keychain entry surviving a restart (no simulator boots
  here, #928). **On no platform:** a link of another instance (the second dev instance was not set up; a major the app
  does not speak and an instance without public links are refused in the core's tests only,
  `ShareGatesTest.anInstanceTheAppDoesNotSpeakOrThatServesNoLinksIsRefusedBeforeAnyResolve`,
  `BookmarksTest.anInstanceTheAppDoesNotOpenOffersTheLinkToTheBrowser`); terms and a note on a live link (no throwaway's
  link had them — their order is `ShareGatesTest`'s); a screen reader over the guest screens. *Android:* the fixes of
  #1032–#1036 not re-passed (a bookmark in place and on its own page, its reports, a viewing guest's report). *Linux:* a
  signed-out guest's Replace and a guest Pot's Settings (built after its passes). *Windows:* a guest file's Download, a
  row's report through its context menu (UI Automation has no context-menu step).
- **§ O on Windows: how the MSIX installs** (measured on the test VM 2026-11-19, cr-open-in-the-app phase 6): developer
  mode is off, so neither a layout's `Add-AppxPackage -Register` nor the unsigned MSIX installs (`0x800B0100`); the copy
  `run.py package --sign` signs with the host's development certificate installs in the logged-on session with no system
  change once that certificate is trusted (a one-time step the host's owner took, 2026-11-13). `veyq:` therefore exists
  only in a signed, installed package; the unpackaged development loop claims nothing and keeps one process per start.
- **§ O built** (2026-11-19): the core's `sharing/DeepLink.parse` (`Open` or `Link`, nothing else read; the bridge's
  `Call.DeepLink` → `DeepScreen`), the web's `core/openInApp/` (`OpenInAppBanner.vue`: on every page per the owner's answer,
  `veyq://link` on a share page with the copy, `veyq://open` elsewhere and on the four mailed pages after their step;
  none on `/moderation` or in a review; the dismissal per browser; hidden where Safari draws Apple's Smart App Banner,
  whose `apple-itunes-app` tag waits for an App Store id, `smartAppBanner.ts`), Android's VIEW filter on `MainActivity`,
  Apple's `CFBundleURLTypes` with `.onOpenURL`, the Linux desktop entry's `x-scheme-handler/veyq` with `HANDLES_OPEN`, the
  MSIX's `windows.protocol` with `AppInstance`'s redirect (`Program.cs`, `DeepLinks.cs`). On `Link` each app shows *Open
  a link* over whatever stands — the sign-in, the unlock or the open tenant (the owner's answer of 2026-11-19, so a
  signed-in person reaches a received link and *Keep* through the banner) — keeping what was typed when it already
  shows; on `Open` only the app in front. The Android, Linux and Windows phases' own passes (as throwaways; Apple's built only): `veyq://link#k=CANARY`,
  `veyq://LINK?u=…#k=CANARY` and `veyq://open?x=CANARY` from the system's own launcher (`am start`, `gio open`,
  `Start-Process`), cold and warm, signed out and over the shell, the canary in no log or file of the app's.
- **§ O's passes from a browser** (2026-11-19, throwaways made and erased by the pass, each link minted by the throwaway
  owner on the web). *The web* (headless system Chrome): the share page's banner reads *Copy link and open the app*, its
  `href` exactly `veyq://link`, the banner holding no key; the click put the link on the clipboard — the same link, and
  without `&report` when the page was opened with it — and Chrome tried `veyq://link` (no handler on that machine); the
  address-change confirm page showed no banner with no link and none for a link the server refused, none while the
  step's answer was pending, and *Open in the veyq app* (`veyq://open`) once the step was done — that success answered by
  the browser, not the server, since the dev backend's relay delivered no mail on that network (the request answered `503`
  after 11 s, the send failed), so no live token existed; dismissed, the banner stayed gone after a reload and on the
  app's home (`veyq.openInAppDismissed`). *Android* (a throwaway API 36 emulator, its Chrome): the share page's *Copy link
  and open the app* opened the app straight to *Open a link* over the sign-in (no chooser: one handler), *Paste* filled
  the link (the system's "veyq pasted from your clipboard"), *Open a link* showed the share as its guest; the tenant's
  sign-in page's *Open in the veyq app* brought the same task to the front on the guest view. The app's files held none
  of the link's key or share id; the system did — Android 16's window manager logs the browser task's current address,
  fragment included, as `capturedLink` when that task is left, and the recents list holds Chrome's opening intent — the
  browser's exposure, not the app's. *Linux* (the Fedora VM's GNOME session, the Flatpak bundle installed for the pass,
  Firefox): the desktop entry registered as `x-scheme-handler/veyq`'s default; Firefox asked *Allow this site to open the
  veyq link with veyq?* (answered *Open Link* once, never *Always allow*); the app started to *Open a link* over its
  sign-in, *Paste* and *Open a link* showed the share as its guest; a second click went to the running window (one
  `veyq` process, the dialog over the guest view), and the sign-in page's banner brought it to the front where it was.
  The app's data, the user journal, `/tmp` and the caches held none of the key (b64url, b64, hex) or the share id.
  *Windows* (the VM, Edge, the MSIX signed with the host's development certificate under a pass-only identity,
  `app.veyq.VeyqOiah`, installed in the logged-on session and uninstalled after with no data left): Edge asked *Diese
  Website versucht, veyq zu öffnen* (answered *Öffnen*, never *immer erlauben*); the app started to *Open a link* over its
  sign-in, *Einfügen* and *Link öffnen* showed the share as its guest (its name decrypted, so the key arrived whole); a
  second click went to the running process (one `Veyq.App`) with the dialog over the guest view, and the sign-in page's
  *In der veyq-App öffnen* brought it to the front on the guest view, no dialog. The app's log said the screen alone
  (`veyq: link opened Open a link`, `… the app where it is`); its package data, install directory and `%TEMP%` (1824
  files) held none of the key or the share id (the scan bit with a planted file). Edge kept the link in its history and
  tabs, as a browser does.
  The residual of decision O4 is in [Known limitations](../../site/src/content/docs/security/known-limitations.md#copy-link-and-open-the-app-hands-the-link-to-whichever-app-holds-veyq)
  and the banner on the guest's page in [What a guest sees](../../site/src/content/docs/sharing/what-guests-see.mdx).
  **Not passed:** the Mac's `open 'veyq://link'` (the coordinator's: an agent has no full-screen control) and the
  iPhone from Safari with the system's "Open in veyq?" and the paste control (the owner's, no simulator boots, #928); a
  mailed page's banner over a token the server issued (the relay on the passes' network sends nothing); a signed-in
  person's *Keep* reached through the banner (read in the code: *Open a link* stands over the open tenant, measured by
  the Android and Windows phases' own passes, the guest screen after it not); the foreground on Windows when the system
  grants none to the starter.
- **§ S built** (2026-11-24, inbox #1015): the core's `sharing/SharedText` and `OpenLink.received` (`SharedTextTest`, 7
  cases: a link alone, in a sentence or a mark-up, the same twice, two never guessed, a foreign one and none; the field's
  refusals and words); Android's `ShareIn.textOf` and `GuestLinks.receive` (`ShareInTextTest`, Robolectric: the text
  read and taken off the intent, a file with a caption a file's, a recents start nothing); Apple's
  `IncomingFiles.sharedText`, `GuestLinks.receive` / `onDone` and the share extension's branch. No entitlement, permission
  or dependency was added. **Not passed:** a real share from a chat and a browser on Android and on an iPhone (the
  owner's, with the iPhone's other passes), and a gallery's memory in the extension.
- **A guest's pages follow the link live** (#1017, built 2026-11-24; the web's `useLevelLive` over `shareGates.poll`,
  ADR 0081). `GuestView.live` (`startLive` for a platform that cannot run a suspend loop) runs core's `LivePoll` at the
  web's `LIVE_POLL` cadence, `LivePoll.LEVEL` (5 s ± 20 %, doubling to 2 min on errors, a catch-up at most every 2 s),
  while a platform shows the link — any page behind its gates, a level or a kind's page, as the web's share page polls
  for every page of the tree (#503). Each tick is `ShareGates.poll`: the link re-resolved conditionally on the open head's
  tag (`ShareResolve.poll`, `If-None-Match` from the `ETag` every resolve now keeps on its `ShareHead`) — a `304` reads
  and opens nothing; a moved link rebuilds the pages over the new tree (`GuestView.rebuild`: the back stack's nodes that
  are still served kept, a node gone giving way to the deepest one left); a `404` flips the gates to *unavailable*, a
  `403` to the password form (wrong), the locked shell to the form, a root no door renders to *unavailable* — the
  platform draws that state as after a load, and once a new password is entered `GuestView.enter` rebuilds over the head
  open now; a network failure, a `429` or a `5xx` is thrown to the poll, which backs off, and never reaches the state.
  A guest's gallery follows its own media as a member's does: `GalleryModel.live` asks the guest's door `moved`, a
  conditional first page of `listShareMedia` on the last listing's tag (the web's `readShareMedia`). Each platform only
  starts and stops it: Android `GuestScreens.kt`'s `GuestContent` (`repeatOnLifecycle(STARTED)`), Apple
  `GuestLinks.swift`'s `GuestContent` (`.task` while the scene is active, `GuestHeld.live`), Linux `received.rs` and
  Windows `ReceivedView.cs` (the bridge's `Watched.ReceivedLive` while the screen stands, as a drop box's poll). Held by
  `GuestLiveTest` (a `304` that leaves the page, a moved link opening the folder on screen anew and a folder gone falling
  back to the root, a revoked link and a changed password flipping the gates and the new password's page over the new
  head, a `503` backing off to three asks in 59 s and the poll ending with the view), `GuestDoorsTest`'s gallery tag and
  `ReceivedBridgeTest`'s live watch; each core case bitten (no `If-None-Match`, no rebuild on a move, a `404` taken as a
  hiccup, a failure counted as a read: each turned its test red). A guest's EXIF stays unread, as on the web, which
  serves a guest none. **Decision log** (#1017):
  1. *The terms and the note are not re-gated by a poll*, as the web's are not (`loadTerms` and `noteGateOf` run at
     `load` only): a moved link carries its new name and banner into `State.Ready`; new terms or a new must-accept note
     ask at the next open.
  2. *A kind's own page stays where it stands*: when the link moved but the node on screen is still served under the same
     role, a gallery, a file or a Pot keeps its model (its own poll follows its content; the platform's scroll, lightbox
     and selection stay) and only the back stack's names follow; a level is opened anew (its rows are the tree's).
  3. *Paused* (a tick asks nothing) while the core asks the guest's name (`GuestNaming.asking`) or a level's Select mode
     is on (its selection lives in the level a rebuild replaces) — the core-held halves of the web's "a dialog is open";
     a sheet a platform holds (a move, a report, *New folder*) keeps working over the access it was opened with, and a
     write's own `reload` and a tick never overlap (one lock; a tick finding it held is a skip).
  4. *The tag kept from every resolve*, not only the poll's (the web starts each `load` over with none): a first tick
     after a load or a write's re-read is already a `304`.
  5. *A head replaced is retired, not zeroed*: `ShareGates.load` after a flip keeps the head before (zeroed at `close`),
     as `reresolve` already did, since the page the flip left open still holds it until the view rebuilds.
  6. *A WRITE_ONLY link is not polled*, as the web's drop page is not (it is served no tree to follow).
  7. *The desktop hosts poll while the screen stands*, as their drop box's and gallery's polls do; the phones stop it in
     the background (Android `STARTED`, Apple `scenePhase == .active`).
  **Not clicked on any app**: a guest's level picking up another guest's folder, a revoked link flipping, a changed
  password asking (inbox #1017 holds the passes).

## Alternatives considered

- **A Master-Key-sealed list of links** (#435's first idea) — decision 1.
- **The share page in a web view** — decision 2.
- **The guest's session in `DeviceStore`** (as the web's `localStorage`) — a token that writes as the guest would sit in
  a plain file; decision 3.
- **The key in the `veyq:` link** — § O1.
- **iOS: a shared link waiting in the app group, or handed to the app by `veyq:`** — § S3.

Explained for users on the docs site: [Open a link in the app](../../site/src/content/docs/sharing/open-a-link-in-the-app.mdx)
(the guest view, its gates, *Keep*, the record's notice), [Keep a link someone sent you](../../site/src/content/docs/sharing/keep-a-link.mdx)
(a bookmark in the app), [What a guest sees](../../site/src/content/docs/sharing/what-guests-see.mdx); what the app holds,
[Install the app](../../site/src/content/docs/getting-started/install-the-app.mdx).
