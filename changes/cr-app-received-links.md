---
id: cr-app-received-links
title: A received link opened in the app as its guest — the share page's gates and each kind's guest door in the core, a bookmark kept, opened and reported as on the web
status: planned
satisfies: []
adrs: ["0120", "0121", "0122", "0123", "0136", "0140", "0148", "0149", "0085", "0093"]
invariants:
  - "operator-blind E2EE: K_share stays in the memory of the open link's model; it is in no request (path, query, header, body), no store of the device, no recent use, no offline entry, no log and no bridge update — the server sees exactly the share page's requests"
  - "share view = member view (owner principle #401): a guest's screens are the member's, handed a guest's door and access; rights only remove functions; no guest-only UI"
  - "the apps behave exactly like the web; only the look is the platform's (owner 2026-11-19; src/mobile/CLAUDE.md § Each platform's guidelines)"
  - "each kind's guest answer is its application's (ADR 0079, ADR 0138 decision 2): core asks AppKinds, never knows an application's rules"
  - "nothing on disk the app did not choose (src/mobile/CLAUDE.md): a guest's session is named there before it is written"
  - "no word in an app but from src/mobile/words/words.json"
  - "no pass signs in as the seeded principal or member: every pass is a throwaway's (ADR 0148 decision 12)"
---

## Goal

Inbox #435, V2 block H, the first of two CRs (the second, [cr-open-in-the-app](cr-open-in-the-app.md), registers the
`veyq:` scheme and puts the web's *Open in the app* banner on top of this one). Today a link someone sent opens only in
a browser: the apps build links for others (`ShareLinks`) but cannot open one they received, and a kept link — a
`SHARE_BOOKMARK` node, ADR 0120 — is listed in the apps with an icon and opens nothing. After this CR a person pastes a
received link into the app (signed in or not, as the web's share page needs no session) and it opens as that link's
guest: the share page's gates (password, terms, the note), then the member's own screens of each kind with a guest's
rights, a write link's naming and uploads included — for a link of this instance or of any other veyq instance of a
major the app speaks. A bookmark is created, opened, shown in place and reported in the apps as on the web.

## Context & constraints

- **Read first:** ADR 0120 and 0121 (all), [uc.sharing.keep-a-received-link](../docs/features/sharing/use-cases/uc.sharing.keep-a-received-link.md),
  [uc.sharing.browse-a-received-share-in-place](../docs/features/sharing/use-cases/uc.sharing.browse-a-received-share-in-place.md),
  [uc.sharing.share-public-viewer](../docs/features/sharing/use-cases/uc.sharing.share-public-viewer.md), the web's
  `src/web/src/views/ShareView.vue` (its header comment is the share page's map), ADR 0148 decision 12 (throwaways),
  `src/mobile/CLAUDE.md` § Nothing on disk, § Each platform's guidelines, § Prove your work.
- **Where what runs** (from `src/mobile/`, `src/mobile/CLAUDE.md` § Prove your work): `./gradlew :core:jvmTest
  :core:macosArm64Test :bridge:jvmTest`; `ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :android:assembleDebug`;
  the Apple builds after `./gradlew :core:assembleVeyqCoreDebugXCFramework`; `python3 linux/run.py test|build`;
  `python3 windows/run.py build` over ssh to the VM; live cases as a throwaway (`VEYQ_LIVE`, `VEYQ_LIVE_THROWAWAY_ENV`).
  **No iOS simulator boots on this Mac (#928)**: the iPhone and iPad passes are the owner's; the macOS app runs here
  when the screen is unlocked (often it is not: then the macOS pass is named open, not faked). Android passes run on a
  throwaway AVD. The Windows VM maps `tw-win.localhost` only: a throwaway for it is created with a fixed tenant name —
  `--tenant tw-win` is on branch `claude/win-passes` (`89107e8e8`), **planned** here, not on this branch; re-read it when
  it lands, else the Windows pass stays open as #983's did.
- **Out of scope:** the `veyq:` scheme and the web banner (cr-open-in-the-app); ~~*Keep* from the share page (#1013)~~ — in scope since the owner's answer to question 1;
  a moderator's review link (`#moderation`) in the apps (#1014, new); a link shared into veyq from another app's share
  sheet (#1015, new); a guest's upload going on with the app left (#1016, new); several instances *signed in* across
  majors (#436 — this CR opens a foreign instance as a guest only, at a major the app speaks).

## Measured at refine (2026-11-19)

Branch `claude/h-refine` at `9cc562ae2`, clean but for this CR. `C=src/mobile/core/src/commonMain/kotlin/me/raddatz/veyq/mobile`,
`W=src/web/src`.

| # | What | Where / command |
|---|---|---|
| R1 | **The web's link and its key:** route `/s/:shareId`, no session gate, `layout: 'plain'`; the link is `<origin>/s/<id>#k=<b64url K_share>`; the key is read from `route.hash` and **never sent**: `resolveShare` takes the id and `X-Share-Password` only. In-tree navigation keeps the fragment (`?node=<id>`). | `grep -n "path: '/s/" $W/router.ts`; `grep -n "k=\|route.hash" $W/views/ShareView.vue`; `grep -n "resolveShare" $W/core/sharing/resolvePublicShare.ts` |
| R2 | **The share page's parts to port:** `resolvePublicShare.ts` (291 lines: resolve, key check, the password token `Argon2id(password, pwSalt)`), `shareGates.ts` (332: password → terms → note → content), `guestIdentity.ts` (157: the guest's session in `localStorage`, one per link, keyed `<prefix>@<origin>:<id>` for another instance), `shareNote.ts` (91), `shareInstance.ts` (81: `/api/setup`, `EMBED_MAJORS = [EXPECTED_MAJOR]`, `core.public-shares` + `.embeddable`), `shareAt.ts` (43). | `wc -l $W/core/sharing/{resolvePublicShare,shareGates,guestIdentity,shareNote,shareInstance,shareAt}.ts`; `sed -n 1,30p $W/core/sharing/shareInstance.ts` |
| R3 | **What the web draws behind the gates:** READ / READ_WRITE → `ShareTreePage.vue` (core's level at a folder, the kind's `detail` elsewhere, handed the guest's access); WRITE_ONLY → the root kind's `shareBody`: Depot's `DropBoxShareBody` (DEPOT_DROPBOX), Memories' `MemoryShareBody` (MEMORY, CHAPTER, 345 lines). | `sed -n 1,20p $W/views/ShareView.vue`; `git grep -n "shareBody(" -- $W/features`; `wc -l $W/features/memories/components/MemoryShareBody.vue` |
| R4 | **Bookmarks on the web:** `parseShareLink` / `keptLink` (https, or http on loopback / `*.localhost`; no credentials; query and other fragment parameters dropped), `reportLinkOf` (`&report`), `openBookmark` (same origin: in this tab, the password handed in memory, `handedPassword.ts`; another: a new `noopener` tab after the password is shown to copy), meta `{v:1, bookmark:{v:1, link, password?, pad}}` sealed under `K_node`, padded to a multiple of 512. | `grep -n "export function" $W/core/bookmark/bookmarkLink.ts $W/core/bookmark/openBookmark.ts`; `sed -n 1,35p $W/core/bookmark/bookmarkMeta.ts`; `contracts/platform/share-bookmark-meta.v1.schema.json` |
| R5 | **The apps open no received link.** The core builds links (`ShareLinks.kt`: `/s/<shareId>#k=<K_share>`) and parses none; no guest session; `SHARE_BOOKMARK` is a kind in `AppKinds` only (slug, re-key NONE, placement, header order `open-own-page, favorite, move`) and `open-own-page` is drawn nowhere; each app maps its icon. | `git grep -n '"/s/\|#k=' -- $C`; `git grep -n 'SHARE_BOOKMARK' -- $C`; `git grep -n 'open-own-page' -- src/mobile` → AppKinds.kt:170 only |
| R6 | **The core already holds the guest's crypto and client:** the generated `SharingApi` covers `contracts/api/sharing.openapi.yaml` (`resolveShare`, `getShareTerms`, `createShareIdentity`, `getShareGuestNotice`, …); `V1.sharePasswordToken` (Argon2id 3/32768/1/32); the conformance's `share_write_token`. | `grep -n 'val coreSpecs' src/mobile/core/build.gradle.kts`; `grep -n 'fun sharePasswordToken' $C/crypto/V1.kt`; `grep -n 'share_write_token' $C/crypto/Conformance.kt` |
| R7 | **The kinds' models already take a source and a guest's access:** `GalleryModel(node, door, …, access: MemoryRights.Access = Member())` with `Access.Guest(role)` ("a link's guest is a source, never another view"); `FileModel(node, door, shares, viewer: FileRights.Viewer = MEMBER)` with `READ`, `READ_WRITE`. **The Pot does not:** `PotSession(tenant: TenantSession, node)` calls `ExpensesApi(tenant.base, …)` directly (624 lines). | `grep -n 'access:\|class Guest' $C/memories/GalleryModel.kt $C/memories/MemoryRights.kt`; `grep -n 'enum class Viewer\|viewer:' $C/depot/FileModel.kt`; `grep -n 'class PotSession\|ExpensesApi(' $C/expenses/PotSession.kt` |
| R8 | **The capability set is read per tenant** (`getTenantCapabilities` → the ids `on`), so `core.share-embedding` can be asked as the web asks it. | `grep -n 'getTenantCapabilities' $C/session/Account.kt` |
| R9 | **The device store is no place for a secret:** `DeviceStore` "holds the trust marks, which are no secret, and nothing else"; the web keeps a guest's session (its identity key and token) in `localStorage`. | `sed -n 12,20p $C/session/TrustMarks.kt`; `sed -n 120,135p $W/core/sharing/guestIdentity.ts` |
| R10 | **"New" has no bookmark** in the apps: `AppKinds.Create` = FOLDER, POT, UPLOAD_FILES, UPLOAD_FOLDER, MEMORY, CHAPTER; the words have `search_kind_bookmark` only. | `sed -n 192,203p $C/AppKinds.kt`; `grep -n -i bookmark src/mobile/words/words.json` |
| R11 | **What the server sees of a guest today** (the web): the share id in the path, `X-Share-Password` / `X-Share-Write` headers, never the key; a write link's guest's address, port, time and `Origin` per write (ADR 0093 § 10); of a bookmark, a node of the kind, its place, its time and a padded ciphertext. A link's subtree serves no bookmark (`SharingStore`), and the backend refuses a `NODE` report of one (`422 report_target_kind`). | `grep -n "/api/shares/{shareId}" contracts/api/sharing.openapi.yaml`; `git grep -n "SHARE_BOOKMARK" -- src/backend/core/src/main` |
| R12 | **native-apps.md names the gap:** "Opening a received link in the app, as its guest, and the handover … (#435); a kept link (`SHARE_BOOKMARK`) has no page yet"; ADR 0149 decision 9: "The apps reach no link as a guest yet (#435): the guest's paths are the core's tests." | `grep -n 'received link' docs/crosscutting/native-apps.md`; `grep -n 'reach no link as a guest' docs/decisions/0149-*.md` |
| R13 | **The Use pages** are `site/src/content/docs/{,de/}sharing/{keep-a-link,what-guests-see}.mdx`; none says a link opens in the app. | `ls site/src/content/docs/sharing site/src/content/docs/de/sharing`; `grep -c -i 'app' site/src/content/docs/sharing/keep-a-link.mdx` → 0 |

## Decisions

Written into **ADR 0150** in phase 1 (the next number: 0147 is taken on another branch, 0149 is this branch's highest,
`git log --all --name-only --format= | grep -o 'docs/decisions/01[45][0-9]-' | sort -u`). The ADR also carries
cr-open-in-the-app's decisions (its § O), so block H has one ADR. Its title: *A received link opens in the app as its
guest — the share page's rules in the core, a kept link a bookmark, the app opened by a `veyq:` link that carries
nothing.*

### H1 — No account-private list: a link kept privately is a bookmark in the personal space

#435's remainder asked for a list sealed under the Master Key, not a node. Rejected: a `SHARE_BOOKMARK` in the member's
personal scope (one per member per tenant, `contracts/api/scopes.openapi.yaml` `createPersonalScope`) already is private
to the account — the scope has no other member, its key is under the Master Key, and ADR 0120 decision 7 keeps a
bookmark out of every link and every review — and every client of the account reaches it once the apps open bookmarks
(this CR). A second store would mean a new endpoint, a second sealed format, a second list on every client and a second
answer to rotation, for the same privacy. What the operator learns is the same either way (that something of this kind
exists and when; ADR 0120 decision 3). ADR 0120's Consequences line "the account-level list remains open as #435" is
amended. *Owner question 1* (product: whether a list outside the spaces is still wanted).

### H2 — A received link opens as its guest through the core's port of the share page

`core/…/sharing/` (a new package; core, not an application — a link is core's, ADR 0079 decision 3):
- **`ReceivedLink.parse(text)`** ports `parseShareLink` and `keptLink` exactly. Both clients answer to one set of cases,
  **`contracts/platform/share-links/cases.v1.json`** (accepted inputs with their normalized link; refused ones with their
  problem), read by the web's `bookmarkLink.test.ts` and the core's test — the search vectors' form (ADR 0146). A
  `#moderation` link is refused with its own problem (H8); `&report` is kept as a request to open the report (ADR 0120
  decision 6), never stored.
- **`ShareInstance`** ports `readShareInstance`/`embeddability` for **every** link, this instance's too: the app is a
  foreign client to every instance, so it reads `/api/setup` of the link's origin and opens only a major in the web's
  `EMBED_MAJORS` rule (its own, today) with `core.public-shares` on. `.embeddable` is not asked: it gates a page of
  another origin inside a web page (ADR 0121 decision 3), and the app is no web page.
- **`ShareGates`** ports `shareGates.ts`: the locked resolve, the password token, the terms, the note, then the content,
  in that order; the guest notice (`getShareGuestNotice`) and a write link's naming (`guestIdentity.ts`) as the web.
- **`GuestTree`** holds the resolved subtree and `K_share` and hands each node's key down as `ShareTreePage` does; the
  level is core's level model handed the guest's tree; a node is its kind's model through **`AppKinds.guestDoor(kind,
  role)`** — each application's answer in its own package (H5).
- Rejected: **a web view of the share page inside the app** — it would hand the key to an embedded browser's history and
  storage, and the owner's rule is the app's own screens with the platform's look.

### H3 — The key and the guest's session: memory, and the platform's credential store

`K_share` lives only in the open link's `GuestTree` and goes with it: no `DeviceStore`, no offline store (ADR 0140 is an
account's, and a guest has none), no recent use (ADR 0122 decision 6 records a member's objects only), no log, no bridge
`Update` (H7). A write link's **guest session** (identity key and token, the web's `localStorage` entry) is a secret
(R9), so the apps keep it where they keep the device's wraps — the Keychain, the Android Keystore-wrapped store, the
Secret Service, the Windows credential locker — under a name that is `sha256(origin ‖ shareId)` truncated, never the id
itself (changes/README § A device-local defence is filed under a key the server cannot name); the terms' and the note's
acceptances, no secret, in `DeviceStore` under the same name. *Not you?* forgets it as the web does. Named in
`src/mobile/CLAUDE.md` § Nothing on disk in phase 1.

### H4 — Where it opens: *Open a link*, before and after the sign-in

The app gains **Open a link** — a text field with the platform's paste control (iOS `PasteButton`, so no paste prompt;
elsewhere a Paste button that reads the clipboard on the tap only, never on its own) — on the sign-in screen (the web's
share page needs no session) and in the shell (*owner question 2* places it). The guest view is **a screen above the
shell**: its own header (the share's root name, a close), its own back stack, no drawer and no bar (ADR 0122's are a
tenant's); closing it returns where the person was. The password of a link opened from a bookmark is handed in memory
(ADR 0120 decision 5's same-origin path — the app has no tabs, so every origin takes it).

### H5 — Each kind's guest door is its application's

| Kind | READ / READ_WRITE | WRITE_ONLY |
|---|---|---|
| FOLDER (core) | core's level over `GuestTree` | — |
| FILE (Depot) | `FileModel`, `Viewer.READ` / `READ_WRITE` | — |
| DEPOT_DROPBOX (Depot) | — | the drop page: the guest names itself, uploads (`DropBoxShareBody`'s port) |
| MEMORY, CHAPTER (Memories) | `GalleryModel`, `Access.Guest(role)` | the guest uploader (`MemoryShareBody`'s port, its `K_up`) |
| EXPENSES_POT (Expenses) | `PotSession` over a door (R7: today a tenant's) | — |

`PotSession` is refactored onto a `Door` first (the member's door the tenant's, unchanged behaviour), its existing tests
the refactor's proof, then the guest's door over the Expenses guest reads (`contracts/api/expenses.openapi.yaml`'s share
paths). A kind without an answer opens the web's "not shown here" state of `ShareTreePage`, never a half view.

### H6 — Bookmarks in the apps, as the web's

- **New → Bookmark** (`AppKinds.Create.BOOKMARK`) at a space's top or in a folder: the web's dialog — the link checked as
  it is typed by H2's parse, its password, the name defaulting to the host, the group space's re-sharing warning
  (ADR 0120 § Consequences) — sealing the meta per `share-bookmark-meta.v1.schema.json`, padded to 512.
- **Opening:** while `core.share-embedding` is on for the tenant (R8), a row opens the bookmark's page, which shows the
  share in place under its header (ADR 0121 decision 1: H2's guest view embedded); **Open on its own page** (the
  header's `open-own-page`, R5) opens H4's screen. Off: the row opens H4's screen. A meta that does not read: the web's
  E2 state with its removal.
- **Report shared content** opens the guest view with its report dialog open (`&report`'s meaning, in process), to the
  share's own operator; **Report name** reports the name (`NAME`, ADR 0093 § 9) to this instance. A selection holding a
  bookmark reports its name only; no path hands this instance a bookmark's key (the backend refuses one, R11).
- Download and copy already leave the kind out (`AppKinds.doors`' `else`: `copies = false, downloads = false`; `grep -n 'fun doors' -A15 $C/AppKinds.kt`); phase 5 checks that a folder's download and copy *name* the bookmark they leave out, as the web's do (ADR 0120 decision 8).

### H7 — What the server sees, and the bridge

The server sees what the web's share page makes it see (R11) — the same requests in the same order, the app's own
`User-Agent` — plus the link instance's `/api/setup`, which the web's bookmark page reads too. Nothing about the app's
*Open a link*. On the bridge (Linux, Windows): `Call.LinkOpen(text)` → `Answer.LinkOpened(id)` and the guest view's
updates by that id; the host hands the pasted text in once, and **no `Update` carries the key or the link**
(the guest view's state names the share's root and nodes only).

### H8 — A link the app does not open

A `#moderation` link (a moderator's review), a major the app does not speak, an instance whose `core.public-shares` is
off, a non-veyq answer: the screen says which, with the web's words where the web has them, and offers **Open in the
browser** — the link as received, handed to the system browser, the same thing a click on it in the chat would have
done. A revoked, expired or re-keyed link shows the share page's *unavailable* state.

### H9 — Words

New keys in `src/mobile/words/words.json` (EN+DE), the web's own words wherever the web has the screen (the gates, the
naming, the bookmark dialog are ported with their strings' meaning); new only *Open a link* / *Link öffnen*, *Paste the
link you received* / *Füge den Link ein, den du bekommen hast*, *Open in the browser* / *Im Browser öffnen* (*owner
question 2*).

## Phases

- [ ] **1 ADR 0150 and the docs it binds** — ADR 0150 (H1–H9 and cr-open-in-the-app's § O), its row in
  `docs/decisions/README.md`; ADR 0120's Consequences amended (H1); ADR 0149 decision 9's "no link as a guest yet"
  pointed at ADR 0150; `native-apps.md` § Open's line (R12) narrowed to what stays open; `src/mobile/CLAUDE.md`
  § Nothing on disk names the guest session (H3); the three sharing use-cases gain an *In the apps* alternate flow.
  · Done when: every guard but guard_gaps green (`/usr/bin/python3 scripts/validate_*.py`).
- [ ] **2 contracts — the link cases** — `contracts/platform/share-links/cases.v1.json` from the web's
  `bookmarkLink.test.ts` cases plus `#moderation` and `&report`; the web's test reads the file (a test change only —
  `parseShareLink` unchanged). · Done when: `pnpm run check` in `src/web/` green. **Bite:** a case's expected
  normalization altered in the file → the web's test red (injection, restored by snapshot).
- [ ] **3 core — the link, the instance, the gates** (H2, H3, H8) — `ReceivedLink`, `ShareInstance`, `ShareGates`,
  the guest session store behind an interface each platform implements. Tests: the cases file; a locked share asks the
  password and a wrong token keeps the form; terms before the note before the content; a foreign major refused.
  **Bites:** (a) the gate order swapped so the content resolves before the terms → red; (b) **the key canary**: a
  mock engine records every request of a whole guest session (resolve, terms, a blob, a naming, an upload) and the test
  asserts no request's path, query, headers or body holds `K_share` as b64url, b64std or hex — bitten by appending the key
  as a query parameter → red; (c) the guest session written to `DeviceStore` instead of the credential store → red (a
  fake `DeviceStore` that fails on a secret-shaped write).
- [ ] **4 core — each kind's guest door** (H5) — `PotSession` onto a door first (`PotShareTest` and the Pot's tests
  unchanged and green: the refactor's proof); `AppKinds.guestDoor`; the folder level, a file, a Memory, a Pot read as a
  guest; a drop box's and a Memory's WRITE_ONLY upload. Tests: a READ guest's Memory offers no upload, no marks, no
  contributions; a READ_WRITE guest's file offers replace; a READ guest's Pot no entry. **Bites:** (a) `Access.Member()`
  handed to a guest → red; (b) `AppKinds.guestDoor` routing a MEMORY to the Depot file's door → the per-kind
  guest test (each kind opened from a fixture share holding every kind) red.
- [ ] **5 core — bookmarks and *Open a link*** (H4, H6) — `Create.BOOKMARK` and its dialog's model; the bookmark's page
  (in place or not by `core.share-embedding`); `open-own-page`; the two reports; *Open a link*'s model before and after
  the sign-in. Tests: a meta the web sealed (a fixture) opens; a meta the core seals is padded to 512 and validates
  against the schema; Report name sends `NAME` only. **Bites:** (a) padding dropped → the length test red; (b) a
  bookmark's `NODE` item sent → the report test red.
- [ ] **6 bridge** (H7) — the calls and updates; the generated Rust and C# bindings. · Done when: `:bridge:jvmTest`
  green. **Bite:** an `Update` serialized with the link in it → the bridge test that scans every update of a guest
  session for the key's canaries red.
- [ ] **7 Android** — *Open a link* (sign-in and shell), the guest screen, the bookmark's page, dialog and reports; the
  guest session in the Keystore-wrapped store. · Done when: `:android:assembleDebug` and the guidelines and words
  guards green; § Passes' Android row.
- [ ] **8 Apple** — the same; `PasteButton`; the Keychain. · Done when: both Apple builds green; the macOS row run here
  or named open (a locked screen); iOS the owner's.
- [ ] **9 Linux** — the same over the bridge; the Secret Service. · Done when: `python3 linux/run.py test` and `build`
  green; § Passes' Linux row.
- [ ] **10 Windows** — the same; the credential locker. · Done when: `python3 windows/run.py build` green on the VM;
  § Passes' Windows row (or #983's reason named open).
- [ ] **11 the passes** — § Passes, each as a throwaway, erased after; results into ADR 0150's Consequences.
- [ ] **12 harvest** — the Use pages (EN+DE): `keep-a-link.mdx` (a bookmark in the apps) and `what-guests-see.mdx`
  (a link opened in the app); `native-apps.md` § Built; #435's row narrowed to cr-open-in-the-app or deleted when both
  CRs are done; this CR deleted. · Done when: `pnpm run check` in `site/` and every guard but guard_gaps green.

## Passes

| Pass | Who | What it proves |
|---|---|---|
| Android AVD, two throwaways (owner + `--with-member`) | agent | the owner makes a READ link of a Memory, a READ_WRITE link of a folder with a file, a password link of a Pot and an upload link of a drop box on the web; the app opens each by *Open a link* **signed out**, then signed in as the member: the gates in order, the member's screens without the functions the role lacks, a guest's naming and upload seen on the web; a bookmark created in the app, opened in place and on its own page, its share reported (the report on the web's moderation list), its name reported; afterwards `run-as app.veyq` lists the app's files and greps them and `logcat` for the key's canaries: none |
| a link of another instance | agent | a second dev instance (or the throwaway tenant's link opened from the other tenant's session) opened by *Open a link* and as a bookmark; a major the app does not speak refused with *Open in the browser* (a stub `/api/setup` in the core's live test) |
| Linux (Flatpak build), a throwaway | agent | the Android pass's READ and upload links and a bookmark; the Secret Service holds the guest session under a hashed name (`secret-tool search`) |
| Windows VM, a throwaway on `tw-win.localhost` | agent | the same as Linux; open as #983 if the fixed tenant name has not landed |
| macOS, a throwaway | agent (screen unlocked) | the same as Linux, `PasteButton` |
| iPhone and iPad | **owner** | a link from Messages pasted into *Open a link* signed out; a bookmark opened in place; the Keychain entry survives an app restart and *Not you?* forgets it |

## Owner questions

1. **No separate list (H1).** A link kept privately is a bookmark in one's own space (the personal one), reachable from
   every client; the Master-Key list #435 first asked for is not built. *Recommendation:* yes — same privacy, one
   format, nothing new on the server; #435 then closes with block H.
   **Answered (owner 2026-11-19): no separate list — but a button to keep the link somewhere for good.** So #1013's
   *Keep* comes into this CR: the guest view (apps) and the share page (web, for someone signed in on that device)
   offer *Keep*, which creates the `SHARE_BOOKMARK` node in a place the person picks (the personal space first). The
   apps behave as the web: both clients get it. Phase 1 writes it into ADR 0150 and the phases that build bookmarks
   (core, web, each platform) take it; the web's share page has no session, so how it reaches the signed-in tab's
   keys is decided in phase 1 and recorded.
2. **Where *Open a link* sits, and its words (H4, H9).** *Recommendation:* on the sign-in screen under the sign-in, and
   in the account menu (the drawer's foot on the phones, the account menu on the desktops); "Open a link" / "Link
   öffnen", "Paste the link you received" / "Füge den Link ein, den du bekommen hast", "Open in the browser" / "Im
   Browser öffnen".

## Definition of done

Phases 1–12 checked; every bite seen red and green; the passes run as throwaways and erased, the owner's named open
where not run; the Use pages in EN and DE; every guard but guard_gaps green. `git diff --stat` over `src/backend/` is
empty; over `src/web/` only phase 2's test change; over `contracts/` only the cases file.

## Retrospective

_(filled at the end of the implementation)_
