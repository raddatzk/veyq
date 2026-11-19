# ADR 0140 — The apps keep content as ciphertext, sealed to the device, by choice or within a limit; an unlock opens it offline
- Status: Accepted
- Built: partly — built on every app and passed on Android, Linux and Windows (§ Consequences); Apple's passes, on a
  real iPhone and a signed macOS build, are the owner's ([native-apps](../crosscutting/native-apps.md) § Open)
- Date: 2026-11-15
- Model: [native-apps](../crosscutting/native-apps.md)
- Replaced in part: decision 8's and decision 9's kept originals (a music track's too) by ADR 0142 (built); decision 8's kept originals (a GPX track's too) by ADR 0143 (built); decision 9's kept blobs (a banner's too) by ADR 0144 (built); decision 5's kept reads (a map tile's in the automatic tier too) by ADR 0143 decision 4 as amended (built); decision 5's kept reads (a Memory's item marks and buckets too) by ADR 0149 decisions 4 and 6 (built)

## Context

The owner wants the native apps to keep content on the device, available offline (2026-11-13): a storage limit under
which what was viewed stays as long as it fits, the least recently viewed leaving first, and content made "available
offline" explicitly, kept until undone. The web keeps nothing and stays so.

What the apps do today (measured 2026-11-13, commands from `src/mobile/` unless named):

- **Every blob read goes through one class**, `blob/ApplicationBlobs.kt`'s `FrameSource`: it presigns once, fetches each
  frame from the store and opens it with `suite.openFrame(K_blob, i, chunkCount, frame)`; Memories' `MemoryMedia`,
  Depot's `DepotFiles`, Expenses' `PotSession.openReceipt` and `NodeCover.open` call it
  (`grep -rn "RangeReader(\|framesTo(\|\.frames(\|\.download(\|FrameSource(" core/src/commonMain`). A frame is 256 KiB
  of plaintext under AES-256-GCM, its index and count in the aad
  (`grep -n "FRAME_PLAINTEXT\s*=\|fun sealFrame\|fun openFrame" core/src/commonMain/kotlin/me/raddatz/veyq/mobile/crypto/V1.kt`).
- **No ciphertext is kept anywhere;** plaintext is held in memory only, each cache bounded
  (`grep -rniE "LinkedHashMap|LruCache|NSCache|HashMap<String, ByteArray>" core/src/commonMain android/src/main apple/Veyq windows/Veyq linux/src`).
  [ADR 0047](0047-inline-video-playback-range-streaming.md)'s Consequences hold the apps to *no plaintext at rest but
  where the person saves it*, and `src/mobile/CLAUDE.md` § Nothing on disk the app did not choose extends that to what an
  HTTP stack keeps on its own — ciphertext included, because the app never chose to keep it.
- **Opening an account needs the server:** `VeyqAccount.resume` refreshes the token, reads `key-material` for this
  device's `DEVICE_KEY` wrap, then opens it; unreachable is `SignInFailure.UNREACHABLE`, and the wrap is not kept on the
  device (`sed -n 790,835p core/src/commonMain/kotlin/me/raddatz/veyq/mobile/session/Account.kt`).
- **The reads exist, three with a conditional answer:** `listNodes` (the scope's tree revision as `ETag`,
  [ADR 0081](0081-a-scopes-tree-revision-is-the-level-views-change-signal.md)), `listNodeMedia`
  (`mediaRevision`/`deleteRevision`) and `listApplicationBlobs` (`ETag`); the core sends no `If-None-Match` to `listNodes`
  today, and trashed nodes are not in it, they are listed by `listTrashedNodes`
  (`grep -rn "operationId: \(listNodes\|listMyScopes\|getAccountKeyMaterial\|listNodeMedia\|listFiles\|listApplicationBlobs\|listTrashedNodes\)$" contracts/api`
  from the repository root).
- **A re-key keeps every `K_blob`:** `NodeRekey` re-wraps each blob key under the node's new key, so a blob's frames and
  id survive it and only its wrap in the listing changes
  (`sed -n 28,34p core/src/commonMain/kotlin/me/raddatz/veyq/mobile/session/NodeRekey.kt`).
- **No offline detection, no database, no app data directory:** no platform network monitor
  (`grep -rniE "NWPathMonitor|ConnectivityManager|isOnline" core/src bridge/src android/src/main apple/Veyq windows/Veyq linux/src`
  → no hit), no database engine in a build file
  (`grep -rniE "sqlite|sqldelight|room|realm|rusqlite|GRDB" --include='*.kts' --include='*.toml' --include='*.props' --include='*.yml' .`
  → comments only), no `expect` for an app data directory (`grep -rn "expect fun\|expect val\|expect class" core/src bridge/src`).
  The device store is string key-value per platform; `TrustMarks` keeps per-account state under a slot named by an HKDF
  of the Master Key (`grep -n "slot" core/src/commonMain/kotlin/me/raddatz/veyq/mobile/session/TrustMarks.kt`) — the
  precedent for a device-local value derived from it.
- **The core knows no suspended tenant:** a suspension whose routing is withdrawn reaches the app as an unreachable
  server (`grep -rn -i "suspended\|tenantOffline" core/src/commonMain` → no hit;
  [uc.admin.suspend-tenant](../features/admin/use-cases/uc.admin.suspend-tenant.md)).

## Decision

1. **What may be kept: ciphertext as the server sent it, re-sealed, by the person's choice or within a limit.**
   ADR 0047's rule is re-read, not reversed: decrypted plaintext stays only where the person saves it, and the HTTP stack
   still keeps nothing. What this adds is ciphertext **the app chooses** to keep — a pin is the person's choice, the
   automatic tier is bounded by a limit the person sets — and nothing else.
   - **Kept:** blob frames exactly as the store served them; the bodies of an allowlist of listing reads exactly as the
     server answered (decision 5); this account's own `key-material` answer, its wraps and sealed keys only (decision 4).
   - **Never kept:** a token (access, refresh, share, password token), a presigned URL (a bearer credential until it
     lapses), any answer of a write, any decrypted byte, any name or id in a file name (decision 2).
2. **The store: one directory per account on this device, every file sealed, every name opaque.**
   - **Its key** is a random 32-byte `K_off` per account per device, made when the store is first opened and kept in the
     store's header wrapped (AES-256-GCM) under `HKDF(masterKey, info = "veyq/v1/offline-store")` — a device-local
     derivation as `TrustMarks`' is, and no new seal purpose in `contracts/crypto` (that table is the suite's, shared with
     the web, which has no store). A header that does not open under the unlocked Master Key (a rotated Master Key,
     another account's store) is **wiped and the store started empty**: the store is a copy of what the server holds,
     never the only one.
   - **Each entry** is `nonce12 ‖ AES-256-GCM(K_off, nonce, content, aad = "veyq/offline/v1" ‖ accountId ‖ logicalKey)`.
     The logical key names what the entry is (server, tenant, scope, kind, id), so an entry moved under another name,
     into another account's directory or into another store fails to open. **A file's name is
     `hex(HMAC(K_off, logicalKey))`:** a local attacker holding a server dump cannot match a blob id or a node id to a
     file.
   - **A blob is one file of its frames, appended in the order fetched,** each frame sealed as an entry of its own (its
     index in the aad), with a sealed frame index (index → offset) rewritten beside it. A video watched to minute 3 is a
     blob file with some frames; a later read appends.
   - **The manifest** — each entry's logical key, size, last view and pin references, and per listing its revision and
     `asOf` — is **256 shards** by the first byte of the opaque name, each sealed and rewritten whole and atomically
     (written beside, `fsync`, renamed) when an entry in it changes, all of it read into memory at unlock. Last-view
     updates are batched: at most one write per shard per 30 s, and one on going to the background.
   - **No database engine.** The store's only queries are a lookup by key and an order by last view, which the
     in-memory manifest answers. **The manifest's load at 100,000 entries on the slowest target** (the Windows VM) is
     measured before anything is built on the store; above 1 s the database question is reopened with that number.
   - **The directory's name** is a random id kept in the device store beside the trusted record, so a wipe needs no key
     and works on a locked account. **One store per account;** the tenant is part of every logical key.
3. **Where the store lives.** The host names the directory at start, as it names the device store's path, and the core
   does all reading and writing ([ADR 0137](0137-the-bridge-carries-shown-bytes-beside-the-json-ranges-through-a-reader-and-files-by-path.md)
   decision 3's line); `blob/Files.kt` gains `createDirectory`, `rename`, `sync` and `freeBytes` per platform source set.

   | Platform | Directory | The platform's own protection |
   |---|---|---|
   | Android | `noBackupFilesDir/offline/` | app-private storage under file-based encryption (credential-encrypted, so unreadable before the first unlock after boot); outside Auto Backup by its directory |
   | iOS | `Application Support/offline/` of the app's container, never the app group (the share extension never opens the store) | `NSFileProtectionCompleteUntilFirstUserAuthentication` on the directory; `isExcludedFromBackup` |
   | macOS | `~/Library/Application Support/<bundle id>/offline/` in the app's sandbox container | the sandbox; `isExcludedFromBackup` keeps it out of Time Machine |
   | Windows | `%LOCALAPPDATA%\veyq\offline\` (in the MSIX, the package's redirected local data, removed with the package) | the user's profile ACLs; not roamed |
   | Linux | `$XDG_DATA_HOME/veyq/offline/` (in the Flatpak `~/.var/app/<id>/data/veyq/offline/`) | the user's home; in the Flatpak, the sandbox |

   The desktop packages ([ADR 0139](0139-the-desktop-apps-ship-as-a-flatpak-bundle-and-an-msix-built-in-checks-and-promoted-with-the-release.md))
   keep these paths writable.
4. **Opening offline: this device's own wrap kept locally; an escalation lands at the next contact.** Offline the server
   cannot serve the `DEVICE_KEY` wrap, so at each online unlock the core keeps **a copy of this device's own wrap** — the
   Master Key sealed under the device's unlock key, ciphertext the server already holds — in the device store beside the
   record, and for the password trust (`DEVICE`) its wrap the same way. Offline, the unlock opens that copy with the
   device's unlock key at the record's level (PRESENCE still asks the person, LOGIN still rests on the device's sign-in),
   then the store; the identity keys and scope grants come from the kept `key-material` and `listMyScopes` answers. That
   session holds **no token** and is read-only (decision 7).
   - **At reconnect** the normal resume runs. A refused refresh ends the record as today and **wipes the store**; a wrap
     the server no longer serves (another device's "Sign out and remove saved key",
     [ADR 0058](0058-revoke-never-takes-key-material.md) decision 2) **removes the local copy and wipes the store**. The
     escalation still restores the Secret-Key floor — at the device's next contact instead of its next sign-in. The
     device's own key material stays as [uc.account.trust-device](../features/account/use-cases/uc.account.trust-device.md)
     E1 keeps it (its unlock and bind keys are never removed on a server's answer), so an operator who withholds the
     wrap costs the device only its copy and its store — a copy of what that server holds anyway.
   - **The residual, accepted (owner 2026-11-15):** a thief who holds the device, passes its authentication at the
     record's level and **keeps it offline** opens what the store held when it went offline, even after the owner
     escalated from another device. The same thief online, before the escalation, reads everything the account reads;
     the store adds only the window after the escalation, bounded to what was kept.
   - **A device that keeps no trust** (every start with the Secret Key) gets no store: the feature is not offered there,
     and its Settings section says why.
5. **The read path: the server first when reachable, the store as a copy of its answer.**
   - **Which reads are kept** is one allowlist in the core (`offline/KeptRead`): `getAccountKeyMaterial` (wraps and sealed
     keys only), `listMyTenants` (the tenants' domains an offline unlock needs; it carries no token or address),
     `listMyScopes`, `listNodes` per scope, `listScopeMembers` per scope (the directory that names a Memory's participants
     offline: usernames, sealed real names, avatar revisions and public keys, never a picture; reached while the scope is
     the account's and kept by a pin of a node in it), `listNodeMedia` per Memory, `listFiles` per folder,
     `listApplicationBlobs` per node, and blob frames. A test derives the allowlisted answers' models from the generated client and fails on any
     field whose name or contract description marks a token or a URL — the set is derived, not listed by hand.
     **What may be kept (owner 2026-11-18):** any answer the app can read may be kept sealed on the device, the
     directory of a scope's members included; the allowlist is a gate against tokens and URLs and a list of what is
     built, not a privacy boundary — a read missing from it is missing because nobody needed it offline yet.
   - **Online, the server's answer replaces the kept one — never merged.** Where a read has a revision, the core sends
     the kept one as `If-None-Match`, and a `304` marks the kept body current. Nothing kept is ever shown beside a newer
     answer, and after each replace a sweep drops what the current answers no longer reach (decision 6) — so a trashed or
     revoked item cannot come back from an old copy.
   - **Offline,** each screen model reads the kept answer of the same read, opens it with the same code, and carries
     `offline: asOf`, the local time the server last confirmed it. A blob not kept is a tile without a picture and a
     lightbox that says it is not available offline; a medium plays the frames kept and stops where they end.
   - **Reachability is the core's, from request outcomes:** a transport failure (mapped to one `Unreachable` type in
     `VeyqAccount.call`) turns the account offline; a probe (`GET /api/setup` on the existing `LivePoll` backoff, 5 s to
     60 s) turns it online and refreshes the shell. A host may later nudge the probe.
   - **The local copy's integrity:** each entry is authenticated (decision 2). **A local attacker who replays older
     store files can show an older state offline** — content the account could read then, never another account's and
     never a forged one. A residual: a monotonic counter would live on the same rollbackable disk.
6. **Revocation, re-key, tenants: what the sweep drops.** After each online replace an entry stays only while a current
   kept answer reaches it, marked from `listMyScopes` down through the listings to the blobs; a pinned entry is reached
   through its pin, and the pin is dropped when its node is no longer reached.
   - **A trashed node or medium, a removed membership** (the scope gone from `listMyScopes`, or `listNodes` answering
     `403`/`404`), **a link or share no longer held, a tenant left:** its listings, blobs and pins go, a pin with the
     notice that it is no longer available.
   - **A re-key** (`NodeRekey`, a scope rotation, [ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md)):
     the listings are re-read; the frames stay, since `K_blob` is kept, so nothing is refetched. A kept frame is opened
     only with the wrap from the current listing.
   - **A replaced file** (a new blob id): the old blob is no longer reached and goes.
   - **A node the operator blocked** (`blockedByOperator`) **and a tenant the server answers as suspended**
     ([ADR 0066](0066-tenant-suspension.md)): the safe side — what is kept below it is not shown and the automatic tier's
     entries there are dropped; a pin stays as "not available right now" without content and fetches again once the
     server serves it. Kept ciphertext is never a way around a hold or a suspension the server states.
   - **Offline nothing is swept.** A suspension that withdraws the tenant's routing looks exactly like offline to the
     app, so the kept content shows until the app reaches the server's refusal. Our reading (accepted, owner
     2026-11-15), not a reviewed answer: a hold or a suspension stops the server making content available; the member's
     own device copy was already delivered to them, as a saved file would be, and the app hides it the moment the server
     says so.
7. **Offline is read-only; no write is queued.** Every write a screen model offers is refused offline with one notice
   (`offline_read_only`), its control disabled where the model's state says so.
8. **Eviction and the limit.**
   - **The unit** is an entry: a blob file (whatever frames it holds) or a kept listing. Its last view is set when a tile
     is drawn, a preview or file shown, a reader read, a listing opened.
   - **The limit** is per account on this device, set on the account's Settings page; `0` turns the automatic tier off,
     and pins still work. **The default is 2 GB on phones and 10 GB on desktops, and the person sets any size**, not one
     of a list of steps (owner 2026-11-15).
   - **Lowering the limit** below what the automatic tier holds **evicts at once** down to it, not at the next write
     (owner 2026-11-15). Pins are never evicted by it.
   - **Eviction** runs after a write that takes the automatic tier over the limit: least recently viewed first, pinned
     entries never, down to 90 % of the limit. A single video larger than a quarter of the limit is not kept by the
     automatic tier — it would evict everything else; its frames still stream.
   - **Originals** (owner 2026-11-15): an original is kept only where the app reads one to show the item — today a
     video's frames, a music track's ([ADR 0142](0142-the-apps-play-a-nodes-music-through-the-cores-player-each-platform-plays-bytes-and-lends-its-system-controls.md)
     decision 12) and a Depot file, which has no other tier — and a GPX track's ([ADR 0143](0143-the-apps-draw-a-memorys-places-and-maps-mapkit-on-apple-the-cores-raster-map-elsewhere-one-consent-per-device.md)
     decision 9). **A photo's original is never kept by either tier;** its
     preview stands in, as it does online. Where a later tier replaces a video's original for playback, that tier is
     kept instead.
   - **Pins count** in what is used and are shown apart; they may exceed the limit, and then the automatic tier keeps
     nothing. **A free-space floor:** neither tier writes while the volume has less than 5 % free; a pin then pauses and
     says so.
   - "Clear cache" drops the automatic tier only; "Remove all offline content" drops the pins too, asked first.
9. **Pins and their sync.**
   - **What can be pinned:** a Memory, a Chapter, a folder (everything below it the built applications open) and a file,
     through the header's "Available offline" toggle, offered where the node's kind declares it through the registry
     (`AppKinds`) — never by core calling an application.
   - **A pin fetches whole:** the listings below the node, every tile and preview, every node's cover below it (named by
     the node's sealed meta, which the store cannot read, so the pin's own sealed record keeps the covers' blob ids) — and,
     since [ADR 0144](0144-the-apps-curate-a-memorys-participants-a-persons-profile-a-mediums-capture-time-and-the-banner-the-webs-rules-in-the-core.md) decision 7, each Memory's `BANNER` and
     `BANNER_POSTER` blobs, kept by the same record —, and
     the originals decision 8 keeps (a video's, a music track's, a Depot file's, never a photo's), with progress and resumable (a blob file appends; a restart
     continues); the size is shown and confirmed before the first byte.
   - **Sync:** at each online open of the account and on each live poll that sees a pinned node's revision move
     (`listNodes`' `ETag`, `listNodeMedia`'s `mediaRevision`), the pin re-reads its listings and fetches what is new; what
     left is swept (decision 6).
   - **When it runs:** while the app runs with the account unlocked — the desktops whenever they run; Android as a
     WorkManager worker in the app's process, promoted to a `dataSync` foreground service with a notification while a
     pin fetches; iOS in the foreground and in the time `beginBackgroundTask` grants after it (and
     `BGContinuedProcessingTask` where the system has it, measured before it is relied on). A scheduled wake-up while the
     app is not running would need the Master Key without the person and is not part of this decision.
10. **Where the person sees and sets it.**
    - **The account's Settings page** gains "Storage on this device" (de: "Speicher auf diesem Gerät"): the limit (a free
      size field and Off), what the automatic tier and the pins use, the volume's free space, the pinned items (each
      removable), "Clear cache", "Remove all offline content".
    - **A node's header** carries "Available offline" (de: "Offline verfügbar") with its state: fetching n of m, kept,
      paused for lack of space.
    - **Offline,** the shell's banner says "Offline — content as of {time}" (de: "Offline — Stand {time}") and every
      write is disabled. The copy is the owner's (2026-11-15); every word lives in `src/mobile/words/words.json`.
    - **The web: nothing.** It keeps no content and offers no offline page.
11. **Wipe.** The store's directory is removed — every file, then the directory, then its id in the device store — at the
    account's sign-out (both trust methods), "Stop trusting this device", removing the device, a refused refresh at
    resume, a wrap no longer served (decision 4) and "Remove all offline content". A wipe a crash interrupted is finished
    at the next start, because the id goes last. `TrustMarks` stay: they are not content.

## Consequences

- **A map tile is kept in the automatic tier too** (ADR 0143 decision 4 as amended 2026-11-18, inbox #939): not a server's
  answer but a tile host's, kept as served under `KeptRead.TILE`, per tenant; reached by no listing, so the sweep keeps it
  while its tenant is listed; never pinned, never read offline (it is read only behind the map's gate). Its plaintext is
  public map imagery, but which tiles were viewed maps where the Memories are — so it is sealed and named opaquely like
  every entry, and dropped with the consent.
- **The apps now keep ciphertext on disk, and the docs say so.** ADR 0047's Consequences and `src/mobile/CLAUDE.md`
  § Nothing on disk the app did not choose name the store as the one thing the app chooses to keep; the HTTP stack's
  rule is unchanged. The web is unchanged.
- **What a local attacker without the keys learns:** the number of entries, their sizes and the store's total — never
  content, names or ids. With the device's unlock at the record's level, decision 4's window and decision 5's replay
  are the two residuals; [security-model](../crosscutting/security-model.md) § What a trusted device rests on, per
  platform names both.
- **ADR 0058's escalation reaches a device's store at its next contact,** not the moment it is pressed; its
  Consequences say so.
- **No contract, backend or web change:** every read the store keeps exists. The core gains a store, a reachability
  state, an offline unlock and a read path per screen model; the bridge carries sizes, states and `asOf`, never a key,
  seal or token (`SecretFreeSchemaTest`, [ADR 0136](0136-an-apps-logic-is-the-kotlin-cores-each-platform-draws-only-its-own-ui.md)
  decisions 1 and 2).
- **A device whose trust is the Secret Key at every start gets no offline content** — the cost of decision 4.
- **Decision 2's measurement held** (2026-11-15, `OfflineManifestLoadTest`, its `veyq-measure:` line; 100,000
  entries, 21,268 KiB in 256 shards, the open being the header and every shard read, opened and decoded): the Windows VM's
  release test binary 173–305 ms over three runs of five (a temporary `binaries.test(RELEASE)`, not kept), its debug
  binary's median 779–869 ms (one run with 1,526 and 1,931 ms outliers); the Linux container's debug binary 455–463 ms;
  macOS debug 417–433 ms warm, 1,507 ms first; the JVM 143–153 ms warm, 1,049–1,174 ms first (the JIT); ART below. So no
  database.
- **As built, beside the decisions:** the store's files go through the core's common seam `blob/DeviceFiles.kt`
  (`Files.kt`'s calls are `nativeMain` only; the JVM's are `java.nio`'s), the ids of every account's store in one
  device-store key (`veyq.offline-stores`, a wipe marked before its files go). **One store per account in the process**
  (`OfflineStores.openShared`), closed before its wipe whoever holds it: a write that lands after a wipe would leave a
  file in a directory that is gone. **A second connection of an account already held retires** (`VeyqAccount.retire`):
  its session and keys end, never the record, the wrap copy or the store, which go only when the account leaves the
  device (decision 11) — signed out instead, it wiped the store and forgot the record the held connection had just
  enrolled. **Frames are kept in batches** of 16 (`ApplicationBlobs.KEEP_BATCH`): each keep rewrites the frame index and
  its shard. **Offline the account has no members, capabilities or services:** a scope opens only at the very key this
  device holds. **A pin dropped by the sweep** is named from the names the shell read in this run, else without one (the
  store keeps no name). **Every app's host names its directory** (decision 3) and its kind's default limit; the core
  holds no path helper.
- **The passes, per platform (phase 6).** **Android** — 2026-11-15, a throwaway AVD (API 36 `google_apis` arm64,
  `sdk_gphone64_arm64`, deleted after), the debug build against the dev backend (`veyq.localhost:8080`), the app's
  language German (`adb shell cmd locale set-app-locales me.raddatz.veyq --locales de-DE`):
  - *Decision 2's load on ART, cold:* the core's JVM classes and the test classes dexed (`d8 --min-api 36`) and run by
    `dalvikvm64` (ART 2.1.0, JIT on — as in the app's process, whose maps hold `jit-cache`). `OfflineManifestLoadTest`
    itself, five fresh processes: first open **199–225 ms**, then 182–192 ms. Since that test writes the 100,000 entries
    in the same process before it opens them, the store was also written in one process and opened in fresh ones with
    the page cache dropped first (`echo 3 > /proc/sys/vm/drop_caches`, root): first open **255–277 ms**, then 182–214 ms
    (page cache warm: 218–224 ms first). Within decision 2's 1 s and below phase 2's first runs (JVM 1,049–1,174 ms,
    macOS 1,507 ms). With the JIT off (`-Xusejit:false`, no app runs so) the open takes 4.1–5.1 s.
  - *The pass:* `android/devlogin.py` signed in (password trust, no offer accepted); a Memory and a Chapter in it made in
    the app, a video (`ffmpeg` test pattern, 436,169 bytes, two frames) and a JPEG uploaded into the Chapter through the
    photo picker, each carrying the marker `VEYQPLAINMARKER6a1f0c93e2b54d17` in its metadata (the picker hands media ids,
    so the media were named `18.mp4` and `19.jpg`; the known names grepped are those and the nodes' "Offline Pass 6a",
    "Kapitel 6a"); the Chapter made available offline ("458 kB" asked, "Auf diesem Gerät · 586 kB" after: covers and
    listings beside the frames); Settings' limit set to "1,5" (the field read "1,5 GB") and "Cache leeren" left
    "Zuletzt angesehen 0 B". Then `adb shell cmd connectivity airplane-mode enable` (`nc 10.0.2.2 8080`: "Network is
    unreachable"), `am force-stop` (no process left), the app started and unlocked by `devlogin.py --unlock`: the shell
    showed "Offline — Stand 15.11.2026 17:55", the pinned Chapter its two items, and the video played from the store (its
    frame 5 s in on screen, the decoder's rendering-start in logcat). The Memory itself, not pinned and its listing
    cleared, said "Die Galerie konnte nicht geladen werden." — what is not kept read as a failed load offline; since then
    the core says "Nicht offline verfügbar" there (`GalleryModel.State.failure`, `offline_not_kept`; `GalleryModelTest`),
    seen on Linux and Windows below. Back online the banner went within about 35 s (the probe's backoff).
  - *The store:* `run-as`/root `find /data/data/me.raddatz.veyq -type f` listed `no_backup/offline/<32-hex account
    dir>/header` and 60 files named by 32 hex digits (1,076 KiB; the largest 436,281 bytes, the video's sealed frames), and
    nothing else of the store. The directory pulled (`adb pull`) and `grep -rlaF` for the marker, the four names,
    `offlinemarker`, `ftypisom` and `Lavf`: no hit (the same grep over the uploaded originals finds the marker in both).
  - *Sign-out:* Settings' "Abmelden" left `no_backup/offline/` empty (the account's directory gone, the trust record
    kept: the app showed its unlock). The rest of the container then: `no_backup/androidx.work.workdb` with `-wal`/`-shm`,
    Room's lock file under `cache/`, `files/profileInstalled`, `shared_prefs/veyq.trust.xml` (the trust record, the
    limit by account key, the instance salt), `veyq-recent.xml` (node ids), `veyq.memories.xml` — `src/mobile/CLAUDE.md`
    § Nothing on disk the app did not choose names each. The pass's families were signed out from Settings and the device
    stopped trusting before the last sign-out (a password-trusted unlock is a login: each start left a family).
  - *Not run here:* a real phone (Play-signed release build, a physical Keystore, the system killing the app rather than
    `force-stop`), the cold boot's credential-encrypted storage before the first unlock, and the presence level's
    offline unlock (it needs the emulator's screen-lock PIN typed, which a pass does not do).
  **Linux** — 2026-11-15, the debug build in `linux/run.py run` (Fedora 44 container, GTK under `gtk4-broadwayd`, driven
  from headless system Chrome through the workspace's Playwright), its home on a volume of its own (`--home
  veyq-6b-home`, removed after), German (`LANGUAGE=de VEYQ_LOCALE=de_DE.UTF-8`), against the dev backend
  (`VEYQ_LIVE=http://veyq.localhost:8080`, `VEYQ_LIVE_STORE=http://veyq.localhost:8081`):
  - *The pass:* `run --devlogin` signed in (password trust: "Nicht jetzt" on the offer); a Memory "Offline Pass 6b" and
    its Chapter "Kapitel 6b" made in the app, `veyq6b-clip.mp4` (`ffmpeg` testsrc, 4 s, 28,675 bytes) and
    `veyq6b-photo.jpg` (30,320 bytes) uploaded into the Chapter through the app's file chooser, each carrying the marker
    `VEYQPLAINMARKER6b…` (in the video's `comment`/`title` metadata and the JPEG's `COM` segment; `grep -c` finds it
    in both originals). The Memory viewed, the Chapter made available offline ("104,1 KB" asked, "Auf diesem Gerät ·
    258,1 KB" after), "Cache leeren" left "Zuletzt angesehen 0 Bytes". *The network cut:* the app reaches the server
    only through the container's `socat` forwards to the host (the backend picks the tenant from the Host, so the
    origin's name stays on the container's loopback); those were killed (`docker exec … kill`, then
    `/dev/tcp/127.0.0.1/8080` and `/8081`: "Connection refused"), since removing the container's network would also
    remove Broadway's published port, the pass's only way to the screen. The Memory then said "Offline — Stand …" and
    "Nicht offline verfügbar". The window closed (the container ended), the app started again with
    `VEYQ_LIVE=http://veyq.localhost:18089` (a port nothing serves: the container's forward leads nowhere, and the
    app's own server `veyq.localhost:8080` is refused) and `--devlogin --unlock`: the password opened the account
    offline ("session Signed"), the Memory showed its Chapter, the Chapter its two items, and the video played from the
    store ("lightbox medium: reader 17, 28675 B", "medium prepared: 4 s", a later frame on screen — its colours shifted
    under Broadway; Windows drew the same frame true).
  - *Two faults the first try found, fixed and bitten in the core:* a password-trusted device that signed in and was
    closed without an online unlock could not open offline ("Der Server ist nicht erreichbar.": only an unlock kept the
    server's instance salt — now the sign-in keeps it, `StayLevelTest.aSignInKeepsTheInstanceSaltAnOfflineUnlockNeeds`),
    and an account opened offline read no pins (a pinned Chapter's toggle "Off", Settings' list "Noch nichts ist
    offline verfügbar" beside 258,1 KB pinned — now the offline open reads them, `OfflinePinsTest` bite (e); seen right
    on Windows). The pass above is the second, from a fresh home.
  - *The store:* `find ~/.local/share/veyq -type f` listed `device-store.json` and `offline/<32-hex account dir>/` with
    `header` and 30 files named by 32 hex digits (mode 700 on both directories; 380 KiB; the largest 106,991 bytes).
    The directory copied out (`docker cp`) and `grep -rlaF` for the marker, the two file names, "Offline Pass 6b",
    "Kapitel 6b", `ftypisom` and `Lavf`: no hit; nor in `device-store.json`.
  - *Sign-out* (offline, Settings' "Abmelden") left `offline/` empty; the home then held `veyq/device-store.json` (its keys:
    the device id, the trust record, the kept DEVICE wrap, the instance salt, `veyq.recent` and the hidden set — ids,
    no names), gnome-keyring's `keyrings/` and `veyq-run/keyring` (`run.py`'s own), and outside the volume
    GStreamer's plugin registry `~/.cache/gstreamer-1.0/registry.aarch64.bin` — no content.
  - *Not run here:* the Flatpak (installed from its bundle, its sandbox's `~/.var/app/<id>/data`), a desktop session's
    own keyring and network manager (the container's are the pass's), the network removed rather than the route, a real
    x86_64 machine.

  **Windows** — 2026-11-15, the debug build unpackaged in `windows/run.py run` on the test VM (Windows 11 on arm64,
  x64 under Prism), driven by `Veyq.Drive` (`run.py drive`), German (`VEYQ_LANGUAGE=de-DE`), the data folder
  `%LOCALAPPDATA%\veyq` (the pass's `device-store.json` set aside and put back after):
  - *The route:* the coordinator's own tunnel held 8080 and 8081 on the VM, so the app signed in to
    `http://veyq.localhost:18480` instead — the pass's own `run.py run` tunnel (`-R 18480`) to a forwarder on the Mac
    (`127.0.0.1:18480` → the backend's 8080); the tenant is the Host's name without its port. Blobs went to the
    store's public endpoint (`veyq.localhost:8081`) over the coordinator's tunnel, which never served the app's API.
  - *The pass:* `run --devlogin` signed in (password trust, `offer-not-now`); the Memory viewed, "Kapitel 6b" made
    available offline (`header:offline`, `PrimaryButton`: "Kept"), Settings showed "252 KB" pinned and "Cache leeren"
    left "0 Bytes". *The network cut:* the tunnel's `ssh` killed (by PID): `Invoke-WebRequest
    http://veyq.localhost:18480/api/setup` on the VM: "Die Verbindung mit dem Remoteserver kann nicht hergestellt
    werden.". The Memory said "Offline — Stand 15.11.2026 19:17" and "Nicht offline verfügbar" (`memory-status`). The
    window closed (`invoke Close`, no process left), the app started again with `--devlogin --unlock` and its tunnel on
    18481 (nothing behind it): unlocked offline with the password, the Chapter's toggle read "Kept" and Settings listed
    "Kapitel 6b" (the pins read offline), and `lightbox-play` played the video from the store ("medium opened: 4 s,
    640×360", its frame 2 s in on screen).
  - *The store:* 30 files under `offline\<32-hex>\` (`header` and 29 named by 32 hex digits, 280,183 bytes, the largest
    106,991), copied out (`Compress-Archive`, `scp`) and grepped as on Linux: no hit. The files the last 30 minutes
    wrote under `%TEMP%`, `INetCache` and the data folder (`Select-String -SimpleMatch`): no hit either.
  - *Sign-out* (offline, `settings-sign-out`) left `offline\` empty; the data folder then held `device-store.json` (the
    same keys as Linux's, and the Credential Manager's bind key's alias) and `log.txt`; no `%APPDATA%\veyq`.
  - *Not run here:* the MSIX (its package's `LocalState`), Windows Hello's presence level offline (the person types the
    PIN), a Copilot+ PC, the VM's own network off (it carries the pass's ssh; its settings are the owner's).
  The passes' families were revoked from Settings, the devices stopped trusting, and the test Memory went to the trash.
  **iOS and macOS — not run.** *What could be read without a device* (Xcode 27.2, iOS SDK 27.2):
  `BackgroundTasks.framework`'s `BGTask.h` and `BGTaskRequest.h` declare `BGContinuedProcessingTask` and its request
  `API_AVAILABLE(ios(26.0))`, unavailable on macOS (the app's deployment target is iOS 26, so no availability check);
  the request's identifier is `<bundle id>.<context>.*`-wildcarded (`Info.plist`'s
  `BGTaskSchedulerPermittedIdentifiers`: `me.raddatz.veyq.offline.*`); the throwing `submit` is deprecated from iOS 27.
  Both build (`xcodebuild`, `CODE_SIGNING_ALLOWED=NO`, macOS and the iOS Simulator SDK,
  2026-11-15), but no pass ran: the iOS Simulators do not boot on the build Mac (CoreSimulator out of date), the
  Simulator cannot answer the background question anyway (`BGTaskScheduler.h`: no background processing there), an
  ad-hoc macOS build stores no device trust and so keeps no store, and the Mac's screen was locked for the click-through.
  The passes above, on a real iPhone and on a signed macOS build — including whether `BGContinuedProcessingTask`'s submit
  is accepted with the wildcard identifier and how long `beginBackgroundTask` lasts — are the owner's.
- **What the docs site says.** [Use content offline](../../site/src/content/docs/getting-started/use-content-offline.mdx)
  (EN and DE) tells a person the pin, the limit, what offline looks like and what wipes the store;
  [Offline or unreachable](../../site/src/content/docs/help/offline.mdx) what the app shows offline and a suspended space
  seen as offline (decision 6); [Known limitations](../../site/src/content/docs/security/known-limitations.md) § The
  apps' offline store the residuals, [Threat model](../../site/src/content/docs/security/threat-model.md)'s stolen device
  decision 4's window, and [What the server sees](../../site/src/content/docs/security/what-the-server-sees.md) what a
  pin's fetch and sync show the operator.
- **Left out, each an inbox row:** writes made offline and sent later (#897, a conflict model per application); a
  scheduled sync of pins while the app is not running (#896); Expenses offline (#898).

## Alternatives considered

- **Keeping decrypted tiles** (cheaper to show) — a plaintext copy at rest is exactly what ADR 0047 forbids, and the
  reason the apps measured their `cache/` empty.
- **An HTTP-level cache** (URLCache, OkHttp's) — it keys by URL, keeps tokens and headers, and is what the apps removed
  on purpose (`src/mobile/CLAUDE.md` § Nothing on disk the app did not choose).
- **An HKDF of the Master Key as the entry key itself** — a Master Key rotation would strand every entry with no rewrap
  path; a random key with a wipe on mismatch costs one refetch.
- **A frame per file** (4,000 files for a 1 GB video) and **a preallocated blob file with fixed slots** — sparse files
  are not sparse on every file system the apps run on, and a fixed slot would show the blob's chunk count before a frame
  is fetched.
- **SQLite** — no driver is in the tree, Kotlin/Native would need one for five targets including `mingwX64` (whose
  build of one the refine could not read from a linked artifact, so it is not claimed), and the store's two queries need
  none; reopened only if decision 2's measurement fails.
- **A cache directory** (`cacheDir`, `Library/Caches`, `$XDG_CACHE_HOME`) — the system or a cleaner may purge it, and a
  pin promises to stay; the automatic tier would fit there, but two directories double the wipe paths for no gain.
- **Opening offline only while the account stays open in memory** — a phone kills a background app within minutes, so
  the feature would not exist on phones.
- **An offline grace period read from the device clock** — the thief sets the clock.
- **Merging a newer answer into the kept one** — an old copy would bring back a trashed or revoked item.
- **The platforms' network monitors** (`NWPathMonitor`, `ConnectivityManager`) — the request outcomes already say it,
  in one place for every platform.
- **Queuing writes made offline** — each application would need a conflict model (revisions, epochs, re-keys while
  offline); inbox #897.
- **A list of limit steps** (Off, 500 MB … 50 GB) — the owner chose a free size (2026-11-15).
