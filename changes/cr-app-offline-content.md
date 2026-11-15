---
id: cr-app-offline-content
title: Content kept on the device as ciphertext — a bounded cache of what was viewed and "Available offline" — opened offline on the native apps
status: planned
satisfies: []
adrs: ["0047", "0058", "0005", "0012", "0081", "0123", "0136", "0137", "0105"]
invariants:
  - "operator-blind E2EE: the device keeps only ciphertext as the server sent it (blob frames, sealed listings, wraps), each entry sealed again under a key of this account on this device; never plaintext, never a token, never a presigned URL; decrypted only in memory on view"
  - "no plaintext at rest but where the person saves it (ADR 0047's Consequences, every app) — unchanged; what changes is that the apps now keep CIPHERTEXT by choice (a pin) or as a bounded cache, stated as such"
  - "the device's key material is untouched by a missing server wrap (ADR 0058, uc.account.trust-device E1); a wrap the server stopped serving ends the local copy and the store at the next online resume — the escalation still lands once the device is reachable"
  - "every rule is the core's (ADR 0136 decisions 1 and 2); the bridge carries sizes and states, never a key, seal or token (SecretFreeSchemaTest)"
  - "no word in an app but from src/mobile/words/words.json; each app follows its platform's guideline (ADR 0123 decision 9)"
  - "the web is unchanged: it keeps nothing (no Cache Storage, no IndexedDB of content)"
---

## Goal

The owner wants the native apps to keep content on the device, available offline (2026-11-13, verbatim: "wäre aber
schon cool wenn in den nativen die Medien auch lokal gesichert, als offline verfügbar, wären" — "Wir könnten ja ein
Speicherlimit setzen, dann können Inhalte so lange wie geht offline gehalten werden, die ältesten (nach letzter
Betrachtung) fliegen dann irgendwann raus. Man kann auch explizit offline verfügbar machen, dann bleiben sie dauerhaft
bis man es wieder rückgängig macht").

On Android, iOS, macOS, Windows and Linux — never the web — the core keeps two tiers in one store per account on this
device:

- **(a) the automatic cache:** what the person viewed (tiles, previews, the frames of an original that were read, the
  listings needed to reach them), under a storage limit, the least recently viewed evicted first;
- **(b) "Available offline":** a Memory, a Chapter, a folder or a file the person pinned — fetched whole, kept until
  undone, never evicted, counted in what the page shows.

The store holds **ciphertext only**, as the server sent it, sealed again under a key this account keeps on this device.
Opening it offline needs the account unlocked on a trusted device; the Master Key is never on disk in clear. Offline,
every screen is read-only and says since when its content is. Sign-out, "Stop trusting this device" and removing the
account wipe the store.

## Context & constraints

- **Read first:** ADR 0047 (its last two Consequences, the apps' rule), ADR 0058 (decisions 3 and 4, the escalation's
  floor), ADR 0005 §9 (one convenience wrap per device), ADR 0012 (`K_blob` per blob under the node key), ADR 0081 (a
  scope's tree revision as `ETag`), ADR 0136, ADR 0137; `src/mobile/CLAUDE.md` whole, especially § Nothing on disk the
  app did not choose and § Secrets on screen; `docs/crosscutting/security-model.md` § What a trusted device rests on,
  per platform; `docs/crosscutting/native-apps.md`; `uc.account.trust-device` (E1, the anti-withholding rule).
- **Sibling CRs:** the apps' account Settings page (`uc.account.sessions`, built on every app) is the page this CR's
  "Storage on this device" section joins — phase 5 here builds on it as it is. Memories on every app (done 2026-11-15) moved the gallery, the lightbox and the uploads into core screen
  models (ADR 0136's Consequences) — `GalleryModel.Door` is where Memories' reads go through the store. The desktop packages (ADR 0139, built) decide the Flatpak's and the MSIX's data locations — D3 below names the
  paths it must keep writable.
- **Where what runs:** core tests on the JVM and macosArm64 (`./gradlew :core:jvmTest :core:macosArm64Test` from
  `src/mobile/`); the Linux container (`/usr/bin/python3 src/mobile/linux/run.py test`), the Windows VM
  (`/usr/bin/python3 src/mobile/windows/run.py test`); the live tests with `VEYQ_LIVE=http://veyq.localhost:8080`
  against the dev backend, signing a second session of the dev principal in, never touching the principal's own family.
- **Out of scope** (each an inbox row, numbers in § Inbox rows): writes made offline and sent later (#897); a scheduled
  wake-up that syncs a pinned Memory while the app is not running (#896); Expenses offline (#898). Nothing in the
  contracts or the backend changes: every read this CR keeps exists (R4).

## Measured at refine (2026-11-13)

Branch `claude/windows-cr-f6c73c` at `dcc664a10`, clean. Commands from `src/mobile/` unless named.

| # | What | Where / command |
|---|---|---|
| R1 | **Every blob read goes through one class:** `blob/ApplicationBlobs.kt` `FrameSource` presigns once (`presignBlobDownloads`, `blob-downloads-batch`), fetches each frame with `VeyqAccount.getFromStore` and opens it with `suite.openFrame(K_blob, i, chunkCount, frame)`; callers are Memories' `MemoryMedia` (`read`, `reader`, `stream`), Depot's `DepotFiles` (`reader`, `read`, `download`), Expenses' `PotSession.openReceipt` and `NodeCover.open`. | `grep -rn "RangeReader(\|framesTo(\|\.frames(\|\.download(\|FrameSource(" core/src/commonMain` |
| R2 | **A frame is 256 KiB of plaintext, AES-256-GCM** (`nonce12 ‖ ct ‖ tag16`, aad `u32be(index) ‖ u32be(total)`); the suite has no XChaCha20. The primitives are `randomBytes`, `hkdfSha256`, `aesGcmSeal/Open`, `hmacSha256`. | `grep -n "FRAME_PLAINTEXT\s*=\|fun sealFrame\|fun openFrame" core/src/commonMain/kotlin/me/raddatz/veyq/mobile/crypto/V1.kt`; `grep -n "fun " core/src/commonMain/kotlin/me/raddatz/veyq/mobile/crypto/Primitives.kt` |
| R3 | **No ciphertext is kept anywhere today;** plaintext is held in memory only: `RangeReader.held` (16 frames per reader), `MemberAvatars.pictures`, Android's `LruCache<String, ImageBitmap>` (48 MiB) and Apple's `NSCache` for media pictures; none on Windows or Linux. | `grep -rniE "LinkedHashMap|LruCache|NSCache|HashMap<String, ByteArray>" core/src/commonMain android/src/main apple/Veyq windows/Veyq linux/src` |
| R4 | **The listings exist, three with a conditional read:** `listMyScopes`, `getAccountKeyMaterial`, `listNodes` (an `ETag`, the scope's tree revision; `304` when unchanged — the core never sends `If-None-Match` today), `listNodeMedia` (`mediaRevision`/`deleteRevision`, `moved()` polls with `If-None-Match`), `listFiles`, `listApplicationBlobs` (`ETag`). Trashed nodes are not in `listNodes` (`NodeSummary` has no trashed field), they are listed by `listTrashedNodes`. | `grep -rn "operationId: \(listNodes\|listMyScopes\|getAccountKeyMaterial\|listNodeMedia\|listFiles\|listApplicationBlobs\|listTrashedNodes\)$" ../../contracts/api`; `grep -n "trees\|forgetTree\|noteRekeyed" core/src/commonMain/kotlin/me/raddatz/veyq/mobile/session/Account.kt` |
| R5 | **A re-key keeps every `K_blob`:** `NodeRekey` moves what was sealed under a node's old key to its new one and re-wraps each `K_blob`, which stays — a blob's frames and id survive a re-key, only its wrap in the listing changes. | `sed -n 28,34p core/src/commonMain/kotlin/me/raddatz/veyq/mobile/session/NodeRekey.kt` |
| R6 | **Opening an account needs the server today:** `VeyqAccount.resume` refreshes the token, then reads `key-material` for this device's `DEVICE_KEY` wrap, then `openUnlockable`; unreachable → `SignInFailure.UNREACHABLE`. The wrap is not kept on the device. A refused refresh (4xx) forgets the record; a wrap not served → `DEVICE_NOT_TRUSTED`. | `sed -n 790,835p core/src/commonMain/kotlin/me/raddatz/veyq/mobile/session/Account.kt` |
| R7 | **No offline detection exists:** no `NWPathMonitor`, `ConnectivityManager` or probe; a store read's transport failure becomes status `0`, an API call's propagates the engine's exception; most models swallow it (`runCatching`). | `grep -rniE "NWPathMonitor|ConnectivityManager|reachab|isOnline" core/src bridge/src android/src/main apple/Veyq windows/Veyq linux/src` → no hit |
| R8 | **Local state per device today:** the `DeviceStore` (string key-value: Apple `NSUserDefaults`, Android `shared_prefs/veyq.trust.xml`, Windows `%LOCALAPPDATA%\veyq\device-store.json`, Linux `$XDG_DATA_HOME/veyq/device-store.json`); `TrustMarks` keeps per-account state under a slot named by an HKDF of the Master Key — the precedent for a device-local value derived from it. No `expect` for an app data directory; `blob/Files.kt` has `openFile`, `removeFile`, `seekFile`, `pathFacts`, `listDirectory` for picked paths. | `grep -rn "expect fun\|expect val\|expect class" core/src bridge/src`; `grep -n "slot" core/src/commonMain/kotlin/me/raddatz/veyq/mobile/session/TrustMarks.kt` |
| R9 | **No database in `src/mobile/`** (Gradle catalog, Cargo, NuGet, the Apple project). | `grep -rniE "sqlite|sqldelight|room|realm|rusqlite|GRDB" --include='*.kts' --include='*.toml' --include='*.props' --include='*.yml' .` → only comments |
| R10 | **Sign-out, forget, remove:** `VeyqAccount.signOut()` forgets a `DEVICE_KEY` record, logs out, zeroes the Master Key; `forgetThisDevice()` drops the server wrap, bind key, refresh token, record and unlock keys; `removeThisDevice()` adds the register's wraps and families. None touches `TrustMarks`. | `grep -nE "fun signOut|fun forgetThisDevice|fun removeThisDevice|masterKey.fill" core/src/commonMain/kotlin/me/raddatz/veyq/mobile/session/{Account,SessionModel}.kt` |
| R11 | **The pages a reader sees:** `help/offline.mdx` ("Offline or unreachable", with its `de/` twin) tells what a suspended space and an unreachable server look like and says "Nothing on your device is lost" — it gains what the apps show offline; the other "offline" hits are a tenant taken offline or offline brute force, not this. `getting-started/install-the-app.mdx` and `account/devices-and-sessions.mdx` tell the apps' device-side behaviour; Security's `what-the-server-sees.md`, `threat-model.md` and `known-limitations.md` speak for the device adversary. | `git grep -li "offline" -- site/src/content/docs` (from the repo root), each hit read; `ls site/src/content/docs/getting-started site/src/content/docs/security` |
| R12 | **The core knows no suspended tenant:** no state, notice or answer handling for it; a suspension whose routing is withdrawn reaches the app as an unreachable server. | `grep -rn -i "suspended\|tenantOffline" core/src/commonMain` → no hit; `uc.admin.tenant-reachability` lines 14–15, 122–125 |

## Decisions

The ADR phase (1) writes D1–D10 as one ADR; these are its decisions, its Alternatives are each decision's "rejected".

### D1 — What may be kept: ciphertext as sent, re-sealed, by the person's choice or bounded

ADR 0047's rule is re-read, not reversed: **decrypted plaintext only where the person saves it** stays as it is.
`src/mobile/CLAUDE.md` § Nothing on disk the app did not choose (2026-11-13) extended that rule to ciphertext an HTTP
stack keeps on its own; it stays true for the stack. What this CR adds is ciphertext **the app chooses** to keep — a
pin is the person's choice, the automatic tier is bounded by a limit the person sets — and nothing else:

- **Kept:** blob frames exactly as the store served them; the bodies of an allowlist of listing reads exactly as the
  server answered (D5); this account's own `key-material` answer minus anything that is not a wrap (D4).
- **Never kept:** a token (access, refresh, share, password token), a presigned URL (they are bearer credentials until
  they lapse), any answer of a write, any decrypted byte, any name or id in a file name (D2).
- **Rejected:** keeping decrypted tiles (cheaper to show) — a plaintext copy at rest is exactly what ADR 0047 forbids
  and the reason the apps measured their `cache/` empty; and an HTTP-level cache (URLCache, OkHttp's) — it keys by URL,
  keeps tokens and headers, and is what the previous CR removed on purpose.

### D2 — The store: one directory per account on this device, every file sealed, names opaque

- **The key:** a random 32-byte `K_off` per account per device, made at the store's first write. It is kept in the
  store's header file wrapped (AES-256-GCM) under `HKDF(masterKey, info = "veyq/v1/offline-store")`, the `TrustMarks`
  precedent (R8): a device-local derivation, no new seal purpose in `contracts/crypto` (that table is the suite's, shared
  with the web, and the web has no store). A header that does not open under the Master Key of the unlock (a rotated
  Master Key, a store of another account) is **wiped and started empty** — the store is a cache of what the server
  holds, never the only copy.
  Rejected: an HKDF of the Master Key as the entry key itself — a rotation would strand every entry with no rewrap
  path; a random key with a wipe on mismatch costs one refetch instead.
- **Each entry** is `nonce12 ‖ AES-256-GCM(K_off, nonce, content, aad = "veyq/offline/v1" ‖ accountId ‖ logicalKey)`.
  The logical key names what it is (`server, tenant, scope, kind, id`), so an entry moved to another name, another
  account's directory or another store fails to open. **A file's name is `hex(HMAC(K_off, logicalKey))`**: a local
  attacker holding a server dump cannot match a blob id or a node id to a file.
- **A blob is one file of its frames, appended in the order fetched**, each frame sealed as an entry of its own (its
  frame index in the aad), plus a sealed **frame index** (index → offset) rewritten beside it. A partial original (a
  video watched to minute 3) is a blob file with some frames; a later read appends. Rejected: a frame per file (4,000
  files for a 1 GB video) and a preallocated file with fixed slots (sparse files are not sparse on every file system the
  apps run on, and a fixed slot would show the blob's chunk count before a frame is fetched).
- **The manifest:** what is kept — each entry's logical key, size, last view, pin references, and per listing its
  revision and `asOf` — in **256 shards** by the first byte of the opaque name, each sealed, each rewritten whole and
  atomically (write beside, `fsync`, rename) when an entry in it changes; the whole manifest is read into memory at
  unlock. Last-view updates are batched (at most one shard write per shard per 30 s, and on going to the background).
- **No database engine.** Rejected: SQLite — no driver is in the tree (R9), Kotlin/Native would need one for five
  targets including `mingwX64` (which the refine could not read from a linked artifact, so it is not claimed), and the
  store's only queries are a lookup by key and an order by last view, which an in-memory manifest answers. **Phase 2's
  first measurement** is the manifest's load at 100,000 entries on the slowest target (the Windows VM): if it exceeds
  1 s, phase 2 stops and hands the database question back with the number.
- **The directory per account** is a random id kept in the `DeviceStore` beside the trusted record, so a wipe needs no
  key and works on a locked account (Manage servers' Sign out of a locked account).
- **Per tenant:** one store per account; the tenant is part of every logical key. A tenant the account no longer holds
  is swept (D6).

### D3 — Where the store lives, and what the platform adds

The host names the directory at start, as it names the device store's path today, and the core does all reading and
writing (ADR 0137 decision 3's line); `blob/Files.kt` gains `createDirectory`, `rename`, `sync` and `freeBytes` per
`posixMain`/`mingwMain`/`jvmMain`.

| Platform | Directory | The platform's own protection |
|---|---|---|
| Android | `noBackupFilesDir/offline/` | app-private storage, file-based encryption (credential-encrypted storage, so not readable before the first unlock after boot); excluded from Auto Backup by the directory |
| iOS | `Application Support/offline/` of the app's container (never the app group — the share extension never opens the store) | `NSFileProtectionCompleteUntilFirstUserAuthentication` on the directory; `isExcludedFromBackup` set |
| macOS | `~/Library/Application Support/<bundle id>/offline/` in the app's sandbox container | the sandbox; excluded from Time Machine by `isExcludedFromBackup` |
| Windows | `%LOCALAPPDATA%\veyq\offline\` (under MSIX, the package's redirected local data; removed with the package) | the user's profile ACLs; not roamed |
| Linux | `$XDG_DATA_HOME/veyq/offline/` (in the Flatpak `~/.var/app/<id>/data/veyq/offline/`) | the user's home; in the Flatpak, the sandbox |

Rejected: a cache directory (`cacheDir`, `Library/Caches`, `$XDG_CACHE_HOME`) — the system or a cleaner may purge it,
and a pin promises to stay; the automatic tier would fit there, but two directories double the wipe paths for no gain.

### D4 — Opening offline: this device's wrap kept locally, the escalation lands at reconnect

Offline the server cannot serve the `DEVICE_KEY` wrap (R6). So the core keeps, at each online unlock, **a copy of this
device's own wrap** (the Master Key sealed under the device's unlock key, ciphertext the server already holds) in the
`DeviceStore` beside the record, and for the password trust (`DEVICE`) its wrap the same way. Offline, the unlock opens
that copy with the device's unlock key at the record's level — PRESENCE still asks the person, LOGIN still rests on the
device's sign-in — and then the store; the account's identity keys and scope grants come from the kept `key-material`
and `listMyScopes` answers (D5). The session has **no token**; it is offline and read-only (D7).

- **At reconnect** the normal resume runs (refresh, `key-material`). A refused refresh ends the record as today (R6) and
  **wipes the store**; a wrap the server no longer serves (another device's "Sign out and remove saved key", ADR 0058
  decision 2) **removes the local copy and wipes the store**. So the escalation still restores the Secret-Key floor —
  at the device's next contact instead of at its next sign-in.
- **The residual, priced against what the same attacker already reads** (the refine checklist's rule): a thief who
  holds the device, passes its authentication at the record's level and **keeps it offline** opens the content the
  store held when it went offline, after the owner escalated from another device. The same thief online, before the
  escalation, reads everything the account reads — the store adds nothing there; what it adds is the window after the
  escalation, bounded to what was kept. Owner question 1 confirms this; without the copy there is no offline opening
  after the app was closed at all (Rejected: offline only while the account stays open in memory — a phone kills a
  background app within minutes, so the feature would not exist on phones).
- Rejected: an offline grace period read from the device clock — the thief sets the clock.
- A device that keeps no trust (neither level, every start with the Secret Key) gets no offline store: the feature is
  not offered there, and the Settings section says why.

### D5 — The read path: the server first when reachable, the store as its answer's copy

- **Which reads are kept** is an allowlist in the core (one place, tested): `getAccountKeyMaterial` (the wraps and
  sealed keys only), `listMyScopes`, `listNodes` per scope, `listNodeMedia` per Memory, `listFiles` per folder,
  `listApplicationBlobs` per node, and blob frames. A test derives the allowlist's response models from the generated
  client and fails on any field whose name or contract description marks a token or a URL — the set is derived, not
  listed by hand.
- **Online, the server's answer replaces the kept one — never merged.** Where a read has a revision (`listNodes`' `ETag`,
  `listNodeMedia`'s `mediaRevision`/`deleteRevision`, `listApplicationBlobs`' `ETag`), the core sends the kept
  revision as `If-None-Match`; a `304` marks the kept body current. This is how a trashed or revoked item cannot be
  resurrected by an old copy: nothing kept is ever shown beside a newer answer, and after each replace a **sweep** drops
  every kept entry the current answers no longer reach (D6).
- **Offline:** each screen model reads the kept answer of the same read, opens it with the same code, and carries an
  `offline: asOf` (the local time the kept answer was last confirmed by the server). A blob not kept is a tile without a
  picture and a lightbox that says it is not available offline; a medium reads the frames kept and stops where they end.
- **Reachability is the core's, from request outcomes:** a transport failure (status 0, an engine exception, mapped to
  one `Unreachable` type in `VeyqAccount.call`) turns the account offline; a probe (`GET /api/setup`, the existing
  `LivePoll` backoff, 5 s to 60 s) turns it online and refreshes the shell. Platform monitors are not needed and not
  added (R7); a host may later nudge the probe.
- **Integrity of the local copy:** each entry is authenticated (D2). A local attacker who **replays** older store files
  can show an older state offline — content the account could read at that time, never another account's and never a
  forged one. Named as a residual; a monotonic counter would live in the same rollbackable disk.

### D6 — Revocation, re-key, tenants: what the sweep drops

After each online replace, an entry is kept only while a current kept answer still reaches it (mark from
`listMyScopes` down through the listings to the blobs; pinned entries are reached through their pin, which itself is
dropped when its node is no longer reached):

- **A trashed node or medium**, a **removed membership** (the scope gone from `listMyScopes`, or `listNodes` answering
  `403`/`404`), a **link or share no longer held**, a **tenant left**: no current answer reaches it → its listings, its
  blobs and its pins are dropped, the pin with a notice ("no longer available").
- **A re-key** (`NodeRekey`, a scope rotation): the listings are re-read (their revision moved), the frames stay valid
  because `K_blob` is kept (R5) — nothing is refetched. Opening a kept frame still needs the current wrap from the
  current listing, so an old listing's wrap is never used once the new one is read. (The coordinator's frame said a
  re-key makes old ciphertext unreadable; R5 shows it does not, so the CR keeps the frames.)
- **A replaced file** (a new blob id): the old blob is no longer reached and goes.
- **A node the operator blocked** (`blockedByOperator`, which `Tree.open` already skips with its subtree) and **a
  tenant the server answers as suspended** (the scope-access gate's refusal, `uc.admin.suspend-tenant`): the safe side
  — the kept entries below it are not shown and the automatic tier's are dropped; a pin stays as "not available right
  now" without its content and fetches again once the server serves it. Kept ciphertext is never a way around a hold
  or a suspension the server states.
- **Offline** nothing is swept: the screens show the kept state with its `asOf`. A suspension whose routing withdrawal
  makes the server unreachable looks exactly like offline to the app, so the kept content shows until the app reaches
  the server's refusal — a residual (Owner question 5).

### D7 — Offline is read-only; writes are not queued

Every write a screen model offers is refused offline with one notice (`offline_read_only`), its control disabled where
the model's state says so. Queued writes need a conflict model per application (revisions, epochs, re-keys while
offline) and are not trivial: inbox #897.

### D8 — Eviction and the limit

- **The unit** is an entry: a blob file (with whatever frames it holds) or a kept listing. Last view is set when a tile
  is drawn, a preview or file shown, a reader read, a listing opened.
- **The limit** is per account on this device, set on the account's Settings page (D10); `0` turns the automatic tier
  off (pins still work). The default is Owner question 2.
- **Eviction** runs after a write that takes the automatic tier over the limit: least recently viewed first, pinned
  entries never, down to 90 % of the limit. A single original larger than a quarter of the limit is not kept by the
  automatic tier (it would evict everything else for one video); its frames still stream.
- **Pinned entries count** in "used" and are shown separately; pins may exceed the limit (then the automatic tier keeps
  nothing). **A free-space floor:** neither tier writes while the volume has less than 5 % free; a pin then pauses and
  says so.
- "Clear cache" drops the automatic tier only; "Remove all offline content" drops the pins too (asked first).

### D9 — Pins and their sync

- **What can be pinned:** a Memory, a Chapter, a folder (everything below it the built applications can open — Memories
  and Depot), a file. The header's "Available offline" toggle, offered where the node's kind declares it through the
  registry (`AppKinds`), never by core calling an application.
- **A pin fetches whole:** the listings below the node, every tile, preview and original (Owner question 3), with
  progress, resumable (a blob file appends; a restart continues), before the first byte the size is shown and confirmed.
- **Sync:** at each online open of the account and on each live poll that sees a pinned node's revision move
  (`listNodes`' `ETag`, `listNodeMedia`'s `mediaRevision`), the pin re-reads its listings and fetches what is new; what
  left is swept (D6).
- **When it runs:** while the app runs with the account unlocked — the desktops whenever they run; Android as a
  WorkManager worker in the app's process, promoted to a foreground service (`dataSync`) with a notification while a
  pin fetches; iOS in the foreground and in the time `beginBackgroundTask` grants after leaving it (and
  `BGContinuedProcessingTask` where the system has it — phase 4's first measurement on the simulator decides). A
  scheduled wake-up while the app is not running needs the Master Key without the person and is not in this CR (#896).

### D10 — Where the person sees and sets it

- **Settings, per account:** a "Storage on this device" section on the account's Settings page: the
  limit (a list of sizes and Off), used by the automatic tier, used by pins, the volume's free space, the pinned items
  (each removable), "Clear cache", "Remove all offline content".
- **On a node:** the header's "Available offline" toggle with its state (fetching n of m, kept, paused: no space).
- **Offline:** the shell's banner "Offline — content as of …", each write disabled.
- **The web:** nothing. It keeps no content and offers no offline page; no web code changes.

### D11 — Wipe

The store's directory is removed (all files, then the directory, then the `DeviceStore` id) at: `signOut` of the
account (both trust methods), `forgetThisDevice`, `removeThisDevice`, a refused refresh at resume, a wrap no longer
served (D4), "Remove all offline content". A wipe interrupted by a crash is finished at the next start (the id is
removed last). R10's paths are where it is wired; `TrustMarks` stay as they are (not content).

## Phases

- [ ] **1 the ADR and the docs it changes** — the next free ADR number at phase 1 (`ls docs/decisions | tail -2`;
  `0140` at refine), titled for D1/D4 ("The apps keep content as ciphertext, sealed to the device, by choice or within
  a limit; an unlock opens it offline"), holding D1–D11 and their rejected options; ADR 0047's last Consequences point
  at it (plaintext rule unchanged, ciphertext kept by choice or bounded); ADR 0058's Consequences add that the
  escalation reaches a device's store at its next contact (D4); `src/mobile/CLAUDE.md` § Nothing on disk the app did not
  choose names the store as the one thing the app chooses, and the package `offline/`; security-model § What a trusted
  device rests on gains the offline row and the two residuals (D4's window, D5's replay; a local attacker without the
  keys learns the entries' count and sizes and the store's total, never content or ids); `native-apps.md` names the
  feature as planned. · Done when: every `/usr/bin/python3 scripts/validate_*.py` but `validate_guard_gaps.py` exits 0.

- [ ] **2 the store and its seal (core)** — `offline/OfflineStore` (D2), its directory calls in `blob/Files.kt` (D3), the
  wipe (D11, wired to R10's paths), `commonTest` over a temp directory; run on the JVM and macosArm64, compiled for
  Linux and Windows and run there by each `run.py test`. First: the manifest's load at 100,000 entries on the Windows
  VM (D2's stop condition). · Done when: `./gradlew :core:jvmTest :core:macosArm64Test` green and both `run.py test`
  green; **bites**, each one edit restored by snapshot: (a) **plaintext never on disk** — a test writes a blob whose
  plaintext carries a 64-byte random marker and a listing whose decrypted name is a second marker, then scans every byte
  of the store's directory for both markers and for the blob id and node id: an injected path that writes the opened
  frame turns it red; (b) an entry copied under another entry's name, and one copied into a second account's store,
  each refused (aad bite: drop the logical key from the aad → red); (c) a header under another Master Key → the store
  starts empty (bite: open with any key → red); (d) wipe leaves no file and a wipe interrupted after the files finishes
  at the next start (bite: remove the id first → red); (e) a torn shard write (kill between write and rename) leaves the
  previous shard readable.

- [ ] **3 the read path, offline opening and eviction (core)** — over Memories' core screen models (built): D4's
  kept wrap and offline unlock in `VeyqAccount`/`SessionModel` (a new `Gate` state is not needed: the unlock opens an
  account marked offline); D5's allowlist, `If-None-Match`, replace and sweep in `TenantSession.trees`, `MemoryMedia`,
  `DepotFiles`, `ApplicationBlobs`; reachability and the probe; each screen model's `offline` state and D7's refusals;
  D8's eviction; the bridge's states (sizes and `asOf` only, `SecretFreeSchemaTest` extended). Tests over a played door
  that can go unreachable, and `LiveOfflineTest` (`VEYQ_LIVE`) against the dev backend. · Done when: core checks green;
  `:bridge:jvmTest` green; the live test green: a gallery viewed online is opened from the store with the server
  unreachable after the account was closed and unlocked again; **bites**: (a) **a trashed medium is not shown from the
  store** after the next online read — the live test trashes it from a second session, reads online, cuts the network,
  and asserts it is absent while a neighbour medium (the positive control) is present; bite: merge instead of replace →
  red; (b) a scope the account was removed from is swept with its blobs (bite: skip the sweep for a `403` → red); (c) a
  wrap the server no longer serves wipes the store at resume (bite: keep the store → red), and a refused refresh does
  too; (d) eviction keeps a pinned entry and drops the least recently viewed (bite: order by size → red); (e) no kept
  body carries a token or a URL (the derived allowlist test; bite: add `presignBlobDownloads` to the allowlist → red);
  (f) a node answered `blockedByOperator` and a tenant answered suspended are not shown from the store while a sibling
  is (the control), and the automatic tier's entries below them are gone (bite: hide without dropping → the byte count
  stays → red).

- [ ] **4 pins and their sync (core, Android's and Apple's background)** — D9: the pin per node through `AppKinds`, the
  fetch with progress and resume, the size confirmed first, the sync on open and on a moved revision, the re-key path
  (R5: listings re-read, no frame refetched), the Android worker and the iOS background time (first: whether
  `BGContinuedProcessingTask` exists on the simulator's iOS — measured, not remembered). · Done when: core and bridge
  checks green; the live test pins a Memory, adds a medium from a second session, and after the next online open the new
  medium is kept; a scope re-key leaves every pinned frame in place (counted before and after) and the Memory opens
  offline after it; **bites**: (a) unpinning a node another pin still reaches keeps the shared blobs (bite: drop by node
  → red); (b) a pin of a node trashed on the server is dropped with its notice (bite: keep pins outside the sweep → red);
  (c) a re-key that refetched frames is caught by the count (bite: clear frames on a moved revision → red).

- [ ] **5 the Settings section and the screens on every app** — D10 on Android, iOS, macOS, Windows and Linux, on the account's
  Settings page; the header toggle, the shell's banner, disabled writes; every word in
  `words/words.json` (English and German). · Done when: `./gradlew :android:assembleDebug`, the Apple project's build,
  `run.py build`/`test` on Linux and Windows green; `scripts/validate_mobile_words.py` green; each app clicked once
  through: set the limit, pin a Memory, see used and pinned sizes, clear the cache.

- [ ] **6 the passes** — per platform against the dev instance: sign in, view a Memory, pin a Chapter, cut the network
  (airplane mode on the phones, the VM's network off, the container's network removed), close the app, start it, unlock,
  open the pinned Chapter and play a pinned video; then the device's store listed and grepped for a plaintext marker (a
  medium uploaded with a known byte pattern and a known name), then sign out and the directory listed empty; the app's
  container listed as § Nothing on disk the app did not choose asks. · Done when: each pass's commands and outputs are
  in ADR NNNN's Consequences, per platform, with what could not be run named (a real device, a Copilot+ PC).

- [ ] **7 harvest** — `help/offline.mdx` (EN and DE) gains what the apps show offline and the suspended-space case
  (D6), and a Use page "Use content offline" (`getting-started/`, EN and DE) tells the pin, the limit and the wipe, both
  walked in the apps per `changes/README.md` § A page that tells users what to click), Security's
  `what-the-server-sees.md`/`threat-model.md` lines for the device adversary, `native-apps.md` from planned to built, the
  lessons to `changes/README.md` or the lines they bite, inbox row #895 deleted, this CR deleted. · Done when: `pnpm run
  check` in `site/` green and every guard but guard_gaps green.

## Owner questions

1. **Offline opening after an escalation (D4).** A thief who keeps a stolen device offline and passes its unlock reads
   what the store held, even after you "Sign out and remove saved key" from another device; online, the escalation
   lands at the next contact. *Recommendation:* accept it, named in the security model — without it nothing opens
   offline once the app was closed.
2. **The default limit.** *Recommendation:* 2 GB on phones, 10 GB on desktops, choices Off / 500 MB / 1 / 2 / 5 / 10 /
   20 / 50 GB.
3. **What the automatic tier and a pin keep.** *Recommendation:* the automatic tier keeps tiles, previews and the frames
   of originals actually read (a video watched, a file opened), an original over a quarter of the limit excepted; a pin
   keeps originals too, so a pinned video plays and a pinned file opens fully.
4. **Copy:** "Available offline" (de: "Offline verfügbar") on a node, "Storage on this device" (de: "Speicher auf diesem
   Gerät") on Settings, the banner "Offline — content as of {time}" (de: "Offline — Stand {time}"). *Recommendation:*
   these.
5. **A suspended space seen as offline (D6, legal reading).** While the server is unreachable because a space was
   suspended (its routing withdrawn), the app shows that space's kept content until it reaches the server's refusal.
   Our reading: a hold or suspension stops the server making content available; the member's own device copy was
   already delivered to them, as a saved file would be — so no blocker, and the app hides it the moment the server says
   so. *Recommendation:* accept, named in `help/offline.mdx` and the security model.

## Inbox rows

#895 (this topic, refined into this CR), #896 (a scheduled sync of pins while the app is not running), #897 (writes
made offline and sent later), #898 (Expenses offline: a Pot's entries and receipts).

## Definition of done

Phases 1–7 checked with their Done-when met; the core, bridge, Linux and Windows checks green; the bites above each
seen red and green again; the passes recorded per platform; the Use page in EN and DE and the Security pages updated
with `pnpm run check` in `site/` green; every `scripts/validate_*.py` but guard_gaps green. No contract, backend or web
change; `git diff --stat` over `contracts/ src/backend/ src/web/` is empty at the end.

## Retrospective

_(filled at the end of the implementation)_
