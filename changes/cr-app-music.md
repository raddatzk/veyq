---
id: cr-app-music
title: A Memory's music in the apps — the core's player (order, crossfade, queue), each platform's decks and system controls, background play on the phones, the Music view, adding, renaming and reporting a track
status: in-progress
satisfies: []
adrs: ["0142", "0135", "0136", "0137", "0140", "0122", "0123", "0130", "0093", "0077", "0139"]
invariants:
  - "operator-blind E2EE: a track is a plain medium (ADR 0135 decision 2); the server learns nothing it does not learn of a video — no type, title, duration, trim or order leaves a client unsealed"
  - "every rule of ADR 0135 decisions 2-9 is the core's (ADR 0136 decisions 1 and 2): order, fades, the queue, the facts, refusals; a platform plays bytes it is handed through a core reader (ADR 0137) and draws — it decides nothing"
  - "a listed tier the apps write is the web's, field for field (`durationMs`, `trimStartMs`, `trimEndMs`, `trackName`); a rename rewrites the RAW tier with every other field verbatim (the web's `setTrackLabel`)"
  - "the bridge carries titles, times, states and words only — never a key, a seal or a token (SecretFreeSchemaTest)"
  - "no word in an app but from src/mobile/words/words.json; each app follows its platform's guideline (ADR 0123 decision 9)"
  - "the contracts and the backend are unchanged; the web changes only by D13, a lightbox video interrupting the music (phase 2)"
---

## Goal

ADR 0135 built a Memory's and a Chapter's background music on the web: the node's own audio media, played by core's
player, shuffled, endless, crossfading over 5 s, in a bar that stays while the viewer moves on. The apps leave audio out
of the gallery today and do nothing else with it (inbox #906). The owner made music **the first web catch-up for the
apps** (2026-11-15), with one addition the web cannot give: **on the phones music keeps playing in the background and
with the screen off**, with the system's controls — Android a media foreground service with its notification and media
session, iOS a background audio session with the lock screen's Now Playing; the desktops the system's media controls
where the platform has them (MPRIS on Linux, SMTC on Windows, Now Playing on macOS).

On Android, iOS, macOS, Windows and Linux the apps then: play a node's tracks as the web does (ADR 0135 decisions 5–7),
across pages, with a bar and the lightbox's compact controls; show a node's Music view (its tracks with title and length,
each with download, rename, remove and report); add a track from that view with its facts sealed into the listed tier as
the web seals them; keep a pinned Memory's tracks offline like a video's original (ADR 0140).

## Context & constraints

- **Read first:** ADR 0135 (all of it — this CR adds no rule to it, it ports it), ADR 0136 (the logic is the core's),
  ADR 0137 (the bridge's bytes: a reader beside the JSON, files by path), ADR 0140 decision 8 (which originals the store
  keeps), ADR 0122 (the bar belongs to the application), ADR 0130 (a GPX track, the pattern ADR 0135 followed);
  `src/mobile/CLAUDE.md` whole; `docs/crosscutting/native-apps.md` (§ Memories, the Windows codec paragraph);
  [en.platform.audio-player](../docs/features/platform/enablers/en.platform.audio-player.md) and
  [uc.memory.background-music](../docs/features/memory/use-cases/uc.memory.background-music.md).
- **The web is the reference implementation.** Its tests are the list the core's tests mirror (R13); where the web and
  this CR differ, the CR says so and why (D6's ADTS count is the web's; D7's trim is narrower; D9's notice says "device").
- **Where what runs:** core tests on the JVM and macosArm64 (`./gradlew :core:jvmTest :bridge:jvmTest
  :core:macosArm64Test` from `src/mobile/`); the Linux container (`/usr/bin/python3 src/mobile/linux/run.py test`), the
  Windows VM (`/usr/bin/python3 src/mobile/windows/run.py test`); live tests with `VEYQ_LIVE=http://veyq.localhost:8080`
  against the dev backend, as `LiveOfflineTest` does.
- **Out of scope** (each an inbox row, § Inbox rows): the silence trim of a decoded format (MP3, AAC, M4A, FLAC, Ogg)
  uploaded from an app (#932, D7); a Chapter playing its Memory's music (#847, the web has it neither); a guest's view of a
  received link in the apps (#435 — the apps open a gallery as a member only, so a guest's listening is the web's until
  then); reporting a photo or video from the apps' lightbox (the ITEM report D11 builds serves it; drawing it there is
  #891's curating slice); GPX tracks (#907). Nothing in the contracts or the backend changes: every read and write this
  CR calls exists (R9, R10, R11). The web changes only by D13 (phase 2).

## Measured at refine (2026-11-15)

Branch `claude/refine-music` at `3cfd93474` (cut from `claude/windows-cr-f6c73c`), clean. Commands from `src/mobile/`
unless they name `src/web/` or the repo root; `C=core/src/commonMain/kotlin/me/raddatz/veyq/mobile`.

| # | What | Where / command |
|---|---|---|
| R1 | **The web's player is one module with a small seam.** An application hands `PlayerQueue {key, album?, href?, tracks, startId?}` of `PlayerTrack {id, title, startMs?, endMs?, open(): {url, release}}`; the bar reads `NowPlaying {key, trackId, title, album?, href?, playing, positionMs, durationMs}` (positions within the span); the API is `play(queue)`, `stop`, `pause`, `resume`, `next`, `previous`, `seek(ms)` and a `notice: 'unplayable'`. Two decks, one `GainNode` each; a 250 ms tick opens the next track 15 s before its fade (`OPEN_AHEAD_MS`), starts the fade at the span's end minus the fade, clears the outgoing deck after fade + 50 ms; a track whose `open`, load or `play` fails is dropped from the order and skipped, and the queue ends with `unplayable` when none is left or failures exceed the tracks; a late `open` after the queue changed is released. MediaSession: title, album, play/pause/next/previous/seek, position once a second, everything cleared at `stop`. | `grep -n "PlayerTrack\|PlayerQueue\|NowPlaying\|OPEN_AHEAD_MS\|function skip\|endQueue\|setActionHandler\|setPositionState" ../web/src/core/audio/player.ts` |
| R2 | **Order and fades are pure.** `order.ts`: Fisher–Yates over the randomness handed in, a round's first never the last played, `previous` restarts past 3 s, `peek` fixes the next, `drop`; `fades.ts`: 5 s, 1 s on a press, a third of a short span, a seek into the window shortens it. | `grep -n "export" ../web/src/core/audio/order.ts ../web/src/core/audio/fades.ts` |
| R3 | **The bar and the compact controls.** `AudioBar.vue`: title · album (a link to the queue's `href`, which `MemoryDetail` sets to the page's URL when music starts), previous, play/pause, next, a range over the span that seeks on release, `m:ss` elapsed and total, close; mounted once per shell while signed in and unlocked (`AppShell.vue`) and on the share page; its unmount stops the player. `AudioMiniControls.vue` (title, previous, play/pause, next; its unmount stops nothing) is used only by `MediaLightbox.vue`, in the top bar, on a phone a row of its own; the slideshow's pause-on-press leaves it out. A video in the lightbox does not pause the music (no call from the lightbox into the player). | `grep -rn "AudioBar\|AudioMiniControls" ../web/src --include='*.vue'`; `grep -n "audioPlayer()" ../web/src/features/memories/components/MediaLightbox.vue` → `musicShown` only |
| R4 | **Memories' side of the web:** `musicQueue.ts` (the node's unblocked tracks in listing order, a snapshot; `key = memories:<scope>:<node>`; title = `trackName`, else the file name without extension, else `Track {n}`; span from the facts); `audio.ts` (`MUSIC_MAX_BYTES` = 200 MiB; refusal order `tooLarge` → `notAudio` (sniff of the first 4096 bytes) → `unplayable` (measuring threw)); `audioFacts.ts` (constants: −60 dBFS peak, 20 ms windows, 30 s per end, 1 s minimum, 7 min whole-decode cap, 10 s metadata timeout; pure: the silence scan, `factsFromSilence`, `untrimmed`, `audioFactsFrom` — three non-negative integers, start < end ≤ duration; browser-bound: `decodeAudioData`, the `<audio>` element's duration); `frames.ts` (MP3/ADTS frame parsing, `adtsDurationMs`) and `wav.ts` (PCM header and samples at both ends) are pure but for `Blob.slice`. The Music tab numbers its **sorted** list for `Track {n}`, the queue its **unsorted** one — the same untitled track can get two numbers (a web defect the port does not copy, D4). | `grep -n "export" ../web/src/features/memories/music/*.ts \| grep -v test`; `sed -n 63,76p ../web/src/features/memories/components/MemoryMusic.vue`; `sed -n 46,66p ../web/src/features/memories/music/musicQueue.ts` |
| R5 | **The listed tier's fields and the export.** `MediaMeta` carries `durationMs`, `trimStartMs`, `trimEndMs` (sealed at upload or import, never rewritten) and `trackName`; `setTrackLabel` rewrites the RAW tier (every other field verbatim) at the listed revision, and on a `409` retry throws `MetaConflictError` only where a peer changed the label itself. The export's medium `audio {durationMs, trimStartMs, trimEndMs}` and `track.name` are in `contracts/export/memories/content.schema.json`; the listed tier itself is client-defined (`media-meta` seal), not in `contracts/api`. | `grep -n "trimStartMs\|trackName" ../web/src/features/memories/media.ts`; `sed -n 1142,1170p ../web/src/features/memories/media.ts`; `grep -rn "trimStartMs\|trackName" ../../contracts/` (repo-relative `contracts/`) |
| R6 | **The core already filters audio out and nothing else:** `GalleryMedium.isAudio` (`audio/` prefix) and `shows()` feed `GalleryModel`'s rows only; no `trackName`, `durationMs`, `trimStartMs` or `trimEndMs` is read anywhere in core, bridge or the apps; `MemoryMedia.Meta`/`metaOf` parse the photo fields only. | `cat -n $C/memories/GalleryMedium.kt`; `grep -rn "GalleryMedium" --include='*.kt' --include='*.swift' --include='*.cs' --include='*.rs' .`; `grep -rnE "trackName\|trimStartMs\|trimEndMs\|\"durationMs" core/src bridge/src android/src apple/Veyq windows/Veyq linux/src \| wc -l` → 0 |
| R7 | **Bytes reach a player through a core reader on every platform** (ADR 0137): `RangeReader` (`$C/blob/RangeReader.kt`, 4 frames read ahead, 16 held, `close()` ends it) over `ApplicationBlobs.FrameSource`, built by `MemoryMedia.reader(tenant, item, blob)` with `keep = keeps(item, blob)` for the offline store. Players: Android `android.media.MediaPlayer` over `ReaderSource : MediaDataSource` (`android/.../ReaderPlayer.kt`; no Media3 in the build); Apple `AVPlayer` over `ReaderLoader : AVAssetResourceLoaderDelegate` (`apple/Veyq/ReaderPlayback.swift`, the UTI from the MIME, `video/mp4` as fallback); Windows `MediaPlayer` over `MediaSource.CreateFromStream(ReaderStream…)` (`windows/Veyq/MediumPlayer.cs`, `ReaderStream.cs`); Linux `gtk::MediaFile::for_input_stream` over a `gio::InputStream` that calls `Reader.Read` (`linux/src/player.rs`, `reader_stream.rs`). Depot plays audio through the same players (`depot/FileView.kt` uses `Sniff.stream`). | `grep -rlnE "ExoPlayer\|AVPlayer\|AVAssetResourceLoader\|MediaPlayer\|MediaFile" android apple windows linux`; `grep -n "media3\|exoplayer" android/build.gradle.kts gradle/libs.versions.toml` → none; `grep -n "contentType" apple/Veyq/ReaderPlayback.swift` |
| R8 | **No background audio and no system media controls exist:** the only hit is WorkManager's `dataSync` service; Android declares `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS` (minSdk 31, targetSdk 37); iOS `Info.plist` has no `UIBackgroundModes`; the MSIX's capabilities are `internetClient`, `runFullTrust`, `webcam`; the Flatpak has `--socket=pulseaudio` and talks to `org.freedesktop.secrets` only. Nothing locks a session when the app goes to the background or the screen turns off. | `grep -rniE "MediaSession\|AVAudioSession\|NowPlaying\|SystemMediaTransportControls\|mpris\|foregroundServiceType\|mediaPlayback\|UIBackgroundModes" .`; `grep -n "minSdk\|targetSdk" gradle/libs.versions.toml`; `grep -n "finish-args" -A8 linux/flatpak/manifest.yml`; `grep -rniE "didEnterBackground\|ON_STOP\|autoLock" android/src/main apple/Veyq core/src/commonMain` → no lock on leaving |
| R9 | **The upload path refuses audio and trusts the claimed type.** `MediaUploads.upload` partitions by `MediaUpload.isMedia` (`image/`, `video/`, or a name in `EXTENSIONS` — images and videos only) and answers the rest `MEMORY_NOT_MEDIA`; `typeOf` takes the given type, then the extension, then `MediaUpload.sniff` (no audio). `Sniff.stream` — the web's `sniffStream`, audio containers included — is used by Depot only. The original goes as `ApplicationBlobs.Outgoing(ORIGINAL, …, type, name, …)`, the listed tier by `MediaMetadata.metaTier`; derived tiers only when both exist. A video's duration is read per platform (Android `MediaMetadataRetriever`, Apple `AVURLAsset`, Windows and Linux the host request `Media.Duration`) for the poster only, never sealed. No upload size limit in core. | `grep -n "fun isMedia\|EXTENSIONS\|fun typeOf\|fun sniff" $C/memories/MediaUpload.kt`; `grep -rn "Sniff\.stream" --include='*.kt' .`; `grep -n "MediaDuration" bridge/src/commonMain/kotlin/me/raddatz/veyq/mobile/bridge/{Wire,Host}.kt` |
| R10 | **No medium meta write and no ITEM report in the apps.** `updateMediaMeta` (revision-checked, `409 stale_revision` with the current blob) has no caller in core; `NodeReport.Scope {WHOLE, NAME}` maps to `NODE`/`NAME` while the contract's kinds are `[NODE, ITEM, NAME, LINK]`; the web's `mediumReportKeys` hands over each blob's `K_blob` and the `media-meta` field key (and `media-exif`'s for a member). | `grep -n "operationId: updateMediaMeta" -A16 ../../contracts/api/memories/media.openapi.yaml`; `grep -n "Kind\." $C/session/NodeReport.kt`; `grep -n "mediumReportKeys" -A40 ../web/src/features/memories/media.ts` |
| R11 | **Offline keeps a video's original only:** `MemoryMedia.keeps` = `role != ORIGINAL \|\| item.isVideo` (passed as `keep=` to every reader) and `pinned` adds `item.original.takeIf { item.isVideo }` — a track's original is neither pinned nor kept when played. The automatic tier refuses a blob over a quarter of the limit (`AccountOffline.keepFrames`). | `sed -n 176,196p $C/memories/MemoryMedia.kt`; `grep -n "l / 4" $C/offline/AccountOffline.kt` |
| R12 | **Where a player can live:** `ShellModel` is per tenant and outlives pages; it already builds tenant-wide models (`galleryModel`, `mediaUploads`). The bridge's `closeTenant()` closes them at a session change, a sign-out and a lock (`SessionModel.locked`). Memories' bar is application-level (`memories.all`, `memories.map`); there is **no per-node tab model** in the apps, and the map tab draws a placeholder. The lightbox (`GalleryModel.Lightbox`) has slideshow, details, save, trash, play/stop for a video; the reader it opens is video-only (`GalleryModel.reader()`). | `grep -n "fun galleryModel\|fun mediaUploads\|class ShellModel" $C/navigation/ShellModel.kt`; `grep -n "fun closeTenant" -A20 bridge/src/commonMain/kotlin/me/raddatz/veyq/mobile/bridge/Bridge.kt`; `sed -n 44,52p $C/navigation/Applications.kt`; `grep -nE "fun (go\|slideshow\|play\|stop\|reader)\b" $C/memories/GalleryModel.kt` |
| R13 | **The web tests a port mirrors:** `order.test.ts` (10 cases), `fades.test.ts` (4), `player.test.ts` (13: trimmed start with a 1 s fade-in, 5 s crossfade opening ahead, a seek shortening the fade, skip, nothing plays, replace over 1 s and release, stop clears the lock screen, a late source released, next/previous, no fade without a gain, a track without facts whole, pause/resume reaching the lock screen), `musicQueue.test.ts`, `audioFacts.test.ts` (the scan, the measuring rules), `frames.test.ts` (5), `wav.test.ts` (4), `audio.test.ts`. | `grep -cn "^\s*it(" ../web/src/core/audio/*.test.ts ../web/src/features/memories/music/*.test.ts` |
| R14 | **Codecs per platform:** Windows ships none (owner 2026-11-12); the VM decoded VP9/Opus and H.264/AAC and refused ProRes (`C00D5212`, said `file_unsupported`); the MSIX depends on no media extension, Windows 11 provisions Web Media, VP9, AV1 and HEVC. Linux: the Flatpak's GNOME 51 runtime carries GStreamer with codecs-extra (VP9, H.264, Opus); the container has `plugins-good`, `plugins-bad-free`, `openh264` — no ffmpeg. Apple: AVFoundation plays no WebM. **Not measured for audio on any platform** — no MP3, FLAC, ADTS or Ogg was played by an app. | `sed -n 225,236p ../../docs/crosscutting/native-apps.md`; `grep -n "codecs-extra\|gstreamer" linux/flatpak/manifest.yml linux/Containerfile` |
| R15 | **Words and pages:** `words/words.json` has no music, track or audio word; the web's copy (EN+DE) is `audio.*` in `src/web/src/i18n/index.ts` and `memories.music.*` in `features/memories/i18n.ts`, three of whose texts say "browser". Site: `memories/add-background-music.mdx` (EN+DE) tells the web only; `getting-started/install-the-app.mdx` (EN+DE) says music is the browser's ("For those, keep using the browser"). | `grep -ciE 'music\|track\|audio' words/words.json` → 0; `grep -n "nothingPlays\|unplayable" ../web/src/i18n/index.ts ../web/src/features/memories/i18n.ts`; `git grep -lniE "music\|musik" -- site/src/content/docs` (repo root) |

## Decisions

The ADR phase (1) writes D1–D13 as ADR 0142 ("The apps play a node's music through the core's player; each platform
plays bytes and lends its system controls"); these are its decisions, each "rejected" its Alternatives.

### D1 — The player is the core's, a port of the web's: `audio/` in core, Memories' part in `memories/music/`

- **`core/…/audio/`** (platform-generic, as the web's `core/audio/` is): `Order` (R2's rules; randomness from the
  suite's `randomBytes`, injectable for tests), `Fades` (R2), `MusicPlayer` (R1's state machine: queue, snapshot, the
  250 ms tick, opening 15 s ahead, skip, `unplayable`, replace with a crossfade, at most two open sources) and its
  state `NowPlaying` (R1's fields; `album` the node's name, and a **route** instead of an `href`, D5). Its API is the
  web's: `play(queue)`, `stop`, `pause`, `resume`, `next`, `previous`, `seek(ms)`, and `interrupt()` (D13).
- **`core/…/memories/music/`** (Memories' application side): `MusicQueue` (R4: a node's unblocked tracks, the
  snapshot, the titles, the spans, the key), `AudioFacts` (parse and validate R5's fields exactly as `audioFactsFrom`;
  the silence scan; `untrimmed`), `Frames` (MP3/ADTS parsing, `adtsDurationMs`), `Wav` (header and PCM at both ends),
  `MusicUpload` (D7), `TrackLabel` (D10), and the Music view's model (D8).
- **The player gets readers, never keys** (ADR 0135 decision 8's line, kept): a track's `open()` is Memories', returns a
  `RangeReader` with its MIME (`MemoryMedia.reader`, D12's `keeps`), and `release()` closes it.
- **One player per signed-in account in the process**, built by the shell beside `mediaUploads` and stopped where
  `closeTenant()` closes them (R12): a lock, a sign-out, a tenant switch — as the web's shell unmount stops it.
- Rejected: **each platform's own player logic** (Media3's playlist, `AVQueuePlayer`, a GStreamer playlist) — five
  copies of the order and fade rules, which ADR 0136 decision 1 forbids, and none of those crossfades. Rejected:
  **one player per tenant** kept alive across a switch — the web stops at a switch; keeping a tenant's readers alive
  after its session closed is exactly what `closeTenant()` exists to prevent.

### D2 — A platform plays bytes: two decks behind one core port

The core drives two **decks**; each platform implements the port once:

- `load(reader, mime) → durationMs` (fails when the platform cannot open it — D9), `play`, `pause`, `seek(ms)`,
  `positionMs`, `ramp(to, overMs)` (a volume ramp the platform runs smoothly, steps ≤ 50 ms), `clear` (stop, release the
  platform's player, close the stream), and the events `ended` and `failed`.
- **The crossfade is the core's timing and the platform's ramp.** The core asks `ramp(1, 5000)` on the incoming deck and
  `ramp(0, 5000)` on the outgoing one, as the web drives its two `GainNode`s; it never steps volume itself (a 20 Hz
  stream of calls across the bridge for nothing).
- **Per platform**, on the reader glue each already has (R7): Android two `MediaPlayer`s over `ReaderSource`, the ramp a
  `VolumeShaper` (API 26+, minSdk 31); Apple two `AVPlayer`s over `ReaderLoader`, the ramp `AVPlayer.volume` stepped (it
  is settable on iOS, unlike the web's element) — the `ReaderLoader`'s MIME→UTI map gains the audio types (R7: it falls
  back to `video/mp4` today); Windows two `MediaPlayer`s over `ReaderStream`, `Volume` stepped on the dispatcher queue;
  Linux two `gtk::MediaFile`s over the `gio::InputStream`, `set_volume` stepped on a GLib timeout.
- **Across the bridge** (Linux, Windows): the decks are host-side; the core reaches them through new host requests
  (`Audio.Load|Play|Pause|Seek|Ramp|Clear|Position`, as `Media.Duration` is one, R9) and the host reports `ended` and
  `failed` as calls. The reader crosses as ADR 0137's reader does.
- Rejected: **Media3/ExoPlayer on Android** — a new dependency for what the framework player and `VolumeShaper` already
  do, and its session model is built around its own `Player`, which would mirror the core's state a second time.
  Rejected: **one platform player with a gapless playlist** — no crossfade, and the trim span (start/end inside a file)
  is not a playlist feature on every platform.

### D3 — Background play and the system's controls

The core owns *what* the system shows and *which* commands exist (`NowPlaying`, `MusicPlayer`'s actions, as the web's
`bindSession`/`unbindSession`); each platform wires it to its system surface, set when a queue loads and cleared at
`stop`:

| Platform | Keeps playing | System controls | Also |
|---|---|---|---|
| Android | a `Service` with `foregroundServiceType="mediaPlayback"`, started from the press that plays (the app is in front), stopped at `stop`; `FOREGROUND_SERVICE_MEDIA_PLAYBACK` declared | a framework `MediaSession` (title, album, position, play/pause/next/previous/seek) and a `Notification.MediaStyle` notification bound to it — the lock screen and Bluetooth/headset buttons follow | audio focus (`AUDIOFOCUS_GAIN`; pause on a loss, duck on a transient one), pause on `ACTION_AUDIO_BECOMING_NOISY` (headphones out) |
| iOS | `AVAudioSession` category `.playback`, `UIBackgroundModes` `audio` | `MPNowPlayingInfoCenter` + `MPRemoteCommandCenter` (play, pause, toggle, next, previous, change position) — the lock screen, Control Center, headphones | pause on an interruption, resume when it ends with `.shouldResume`; pause when the route's old device goes away |
| macOS | the app runs | `MPNowPlayingInfoCenter` + `MPRemoteCommandCenter` (Control Center's Now Playing, the media keys) | — |
| Windows | the app runs (minimised included) | one SMTC for the window (`SystemMediaTransportControlsInterop.GetForWindow`), both decks' `CommandManager` off, so the flyout and the media keys drive the core's player, not a deck | — |
| Linux | the app runs (its window closed: as the app's other background work; no new rule) | MPRIS (`org.mpris.MediaPlayer2` and `.Player` on the session bus through gio's D-Bus, no new crate), the bus name `org.mpris.MediaPlayer2.app.veyq.Veyq`; the Flatpak gains `--own-name=org.mpris.MediaPlayer2.app.veyq.Veyq` | — |

- **Frames keep coming in the background** because the process does: the reader fetches as it does in front, and the
  offline store keeps what it keeps (D12). The session stays unlocked with the screen off: nothing locks it on leaving
  (R8), so this CR adds no unlock rule. ADR 0135's residual ("a phone may suspend a hidden tab") does not hold for the
  apps — ADR 0142 says so.
- **Notification permission:** a media-session notification is exempt from `POST_NOTIFICATIONS` on Android 13+
  (remembered; measured in phase 8a — if it is not shown without the grant, the play press asks for it, as a pin does).
- Rejected: **iOS without a background mode** (play only in front) and **Android without a service** — the owner's
  decision (2026-11-15) is that music plays on with the screen off. Rejected: **MPRIS through `zbus` directly** — it is
  in the tree only through `ashpd`; gio's D-Bus is what the app already links.

### D4 — The queue: the node's tracks, a snapshot, one numbering

Port of `musicQueue.ts` (R4): the node's unblocked audio media in listing order, snapshot at `play`; `id` the medium's
id; the span from `AudioFacts` (a track without valid facts plays whole); `key` `memories:<scope>:<node>`. **Titles are
numbered once**: an untitled track's `Track {n}` takes its `n` from the Music view's sorted order, and the queue uses the
same title — the web's two numberings (R4) are not ported, and the web's defect gets an inbox row (#933).

### D5 — The bar, its node link, and the lightbox's compact controls

- **The bar** (R3's content: title · node name, previous, play/pause, next, a progress over the trimmed span that
  seeks on release, elapsed and total, close) is drawn by each platform while a queue is loaded, at the shell's bottom:
  on the phones above the application's bottom bar (the mini-player of Material's and Apple's own music apps); on the
  desktops along the window's bottom edge. Its state is the core's `NowPlaying`; Linux and Windows read it through a
  watched `PlayerShown`.
- **The node link:** the node's name in the bar opens the node that started the queue — a navigation **route** (scope,
  node) the queue carries, which the shell opens as it opens a search result; a node no longer readable opens nothing
  and says the shell's existing not-found word. Rejected: carrying a URL as the web's `href` — the apps navigate by
  route.
- **The lightbox's compact controls** (R3: title, previous, play/pause, next) sit in the lightbox's top bar while a
  queue is loaded, a row of their own on a phone; closing the lightbox stops nothing; the slideshow's own pause ignores
  them. **A video in the lightbox interrupts the music** (D13, owner question 2).

### D6 — Where a node's music shows: the header toggle and the Music view

- **The header's music toggle** (ADR 0135 decision 9): a node with music shows one header action, play/stop, through the
  gallery's header actions as `UPLOAD` is added there (R12), never as a core `NodeHeader` action — music is Memories'.
- **The Music view** sits beside the node's gallery as a **view switch inside the Memory screen** (Photos | Music), shown
  where the node has music or the viewer may upload: Android a `PrimaryTabRow` under the top bar, Apple a segmented
  `Picker`, Windows a `SelectorBar`, Linux an `AdwToggleGroup`. It lists the tracks (title, the file's whole length as
  the web shows it), the one playing marked; pressing a track plays it first, then the rest shuffled (ADR 0135
  decision 6). A track's menu: rename (where the medium's meta may be written, D10), download (where the gallery's save
  is offered), remove (the gallery's trash with Undo), report (D11). "Add music" where the viewer may upload.
- Rejected: **a bar tab** — Memories' bar is application-level (ADR 0122, R12); a Music tab there would be every
  Memory's at once. Rejected: **a sheet from the header** — the view is a list people return to and act on, as the web's
  tab is.

### D7 — Adding a track: the web's refusals, its facts, and a narrower trim

Only from the Music view (the gallery's picker keeps refusing audio, R9), through each platform's document picker
filtered to audio (Android `OpenMultipleDocuments` with `audio/*` — no `READ_MEDIA_AUDIO`; Apple `fileImporter` with
`.audio`; Windows `FileOpenPicker`; Linux `GtkFileDialog`), through `MediaUploads` as a music upload:

- **Refusals, in the web's order, by the core:** over **200 MiB** → `tooLarge`; `Sniff.stream` of the head is not an
  audio container → `notAudio`; the platform cannot read its duration → `unplayable`. **The sealed type is the sniff's**
  (`audio/mp4` for an M4A, never `video/mp4`), not the claimed one — for a music upload; the gallery's photo path keeps
  its order (R9), which a later CR may align.
- **The duration** from the platform's reader of the picked file (Android `MediaMetadataRetriever`, Apple `AVURLAsset`,
  Windows and Linux the existing `Media.Duration` host request, extended to audio if its `MediaClip` path refuses audio —
  phase 7's first measurement), **except a raw ADTS file, whose frames the core counts** (`adtsDurationMs`, the web's
  rule: an ADTS length is an estimate everywhere).
- **The trim is found where it is cheap without a platform decoder: WAV only** — the core reads the PCM at both ends
  (`Wav`) and runs the scan (−60 dBFS, 20 ms windows, 30 s per end, a trim leaving < 1 s keeps the whole). **Every
  decoded format is sealed untrimmed** (`trimStartMs = 0`, `trimEndMs = durationMs`, the web's `untrimmed`), and since the
  facts are never rewritten (R5) such a track keeps its edges' silence wherever it plays. Rejected: **four platform
  decoders now** (MediaCodec, AVAudioFile, Media Foundation, GStreamer) behind a `decodeSlice` host request — four new
  native paths for a nicety the web's own ADR leaves out for long M4A/FLAC/Ogg already; inbox #932 keeps it, with
  `AVAudioFile` as the cheap first one. Rejected: **sealing no facts** — the web then plays it whole too, but an export
  would carry no `audio` field and an import re-detects; sealing the duration keeps the length in the Music view.
- **The listed tier** gains `durationMs`, `trimStartMs`, `trimEndMs` beside what `metaTier` writes; no derived tiers;
  the blob-meta name is the file's.

### D8 — The Music view's model is the core's

`MusicModel` (per node, from `ShellModel` as `galleryModel` is): the tracks (sorted by title with a collator, the
untitled numbered, the length, the playing one), the rights (upload, rename, download, report), the uploads under way
(Memories' existing batches), and the actions; it reads the same `MemoryMedia.list` the gallery reads and the gallery's
live poll. The bridge carries it as `Watched.Music` → `MusicShown` (titles, lengths, states, words).

### D9 — A format a device cannot play: skipped, marked, and said

- **At upload:** a file the device cannot read is refused `unplayable` (D7) — on a Windows without the Web Media
  extension that is an Ogg file, on Apple likely every Ogg (R14 — measured in phases 6 and 7, not assumed).
- **At playback** (ADR 0135 decision 3): a track whose deck fails to load is dropped from the round and skipped; the
  Music view marks it "doesn't play on this device" for the rest of the session (the player's skipped ids in
  `NowPlaying`); when none plays, the bar says so for 5 s and the queue ends (R1's `unplayable`). Download still offers
  the file.
- **Windows ships no codec** (owner 2026-11-12, kept): what plays is what Media Foundation and the extensions Windows 11
  provisions decode. Phase 7's first measurement is the format matrix on the VM — MP3, M4A (AAC and ALAC), raw ADTS,
  WAV (16/24-bit, float), FLAC, Ogg Vorbis, Ogg Opus — with the Web Media extension present and removed; ADR 0142 and
  the site name what does not play and that the app shows it as above. The same matrix runs on Linux (container and
  Flatpak) and Apple (the macOS build), so the Use page names each platform's gaps from measurements.

### D10 — Renaming a track: the web's raw read-modify-write

`TrackLabel.rename(medium, name?)`: open the medium's RAW listed tier, set or delete `trackName` (an emptied name gives
the file's back), keep every other field verbatim, re-seal under the medium's key and `updateMediaMeta` at the listed
`metaRevision` and node epoch; on `409 stale_revision` retry once on the returned tier — a peer's **label** change
answers `rename_conflict` and shows the peer's name, any other peer's write merges (R5). Offered where the medium's meta
may be written (a member with write rights; the apps open galleries as members only). Rejected: re-sealing from
`MemoryMedia.Meta` — it parses the photo fields only (R6) and would drop the rest.

### D11 — Reporting a track as an ITEM

`NodeReport` gains an `ITEM` scope with the medium's id and the port of `mediumReportKeys` (R10): every blob's `K_blob`
by blob id, the `media-meta` field key, and `media-exif`'s (the apps' viewer is a member). The report sheet is the
existing one (`ReportSheet`). The moderator hears it on the web (ADR 0135 decision 10, unchanged).

### D12 — Offline: a pinned Memory's tracks are kept like a video's original

A track's original is what the app reads to play it, so `MemoryMedia.keeps` and `pinned` treat an audio medium as a
video (`item.isVideo || item.isAudio`, R11): a pin of a Memory or Chapter fetches its tracks whole, and a pinned node's
music plays offline; the automatic tier keeps the frames played, its quarter-of-the-limit rule unchanged (a 200 MiB track
is under a quarter of the phones' 2 GB default). ADR 0140 decision 8's list of kept originals gains "a music track's"
(phase 1). Offline the Music view is read-only (ADR 0140 D7): no add, rename, remove or report; playing works.

### D13 — A lightbox video interrupts the music, on every surface (the web included)

Owner question 2, answered 2026-11-15: everywhere — the apps **and the web** — the music pauses while a video in the
lightbox plays and starts again by itself when the video stops or the lightbox moves on.

- **The player's seam:** `interrupt()` pauses a playing queue and returns an end. The end resumes the queue only when it
  was playing when interrupted and nothing came between: a listener's play or pause, a new queue, a `stop`. A play pressed
  during the video sounds both (the listener's choice) and the end then does nothing; a pause pressed keeps it paused
  after the video. While interrupted, `NowPlaying.playing` is false, so the bar, the compact controls and the system
  controls show it paused. Resuming is the bar's play.
- **The lightbox raises it** from its video's own events: playing → `interrupt()`; pause, ended, an item change (by hand or
  the slideshow) and the lightbox's close → the end. A muted video counts as playing (the lightbox reads no volume). The
  web: `MediaLightbox.vue`'s `<video>` over `core/audio/player.ts` (phase 2); the apps: `GalleryModel.Lightbox` over
  `MusicPlayer` (phase 4), each platform's video player reporting playing and paused through the lightbox model it
  already drives.
- **Android trap:** the lightbox's video plays without requesting audio focus of its own, or D3's focus listener takes it
  as a loss and pauses with no end to resume it.
- Rejected: **both sound** (the web's behaviour until now, R3) — two sounds at once are what the platforms' audio rules
  (audio focus, `AVAudioSession`) avoid, and the video's sound is what the viewer just chose. Rejected: **ducking** the
  music under the video — still two sounds, at a level that suits neither; Android's may-duck focus is for short sounds.
  Rejected: **stopping the queue, or pausing it without resuming** — the listener would restart the music by hand after
  every video.

## Phases

- [x] **1 the ADR and the docs it changes** — ADR 0142 (the next free number, `ls docs/decisions | tail -3`) holding
  D1–D13 and their rejected alternatives, its Consequences: the background residual of ADR 0135 lifted on the apps, the
  untrimmed decoded formats (#932), the per-platform format gaps as phases 6 and 7 will measure them; ADR 0135 and ADR
  0140 point at it by a `Replaced in part` line each (`docs/README.md`: never a mark in an earlier ADR's body) — 0135's
  decision 8 (a video interrupts the music) and its hidden-tab residual for the apps, 0140's decisions 8 and 9 (a track's
  original kept); en.platform.audio-player and uc.memory.background-music carry the native realisation and D13 in a
  "Decided, not built" section; `native-apps.md` § Open's Memories names music as planned (ADR 0142, #906);
  `src/mobile/CLAUDE.md` names the packages `audio/` and `memories/music/`; the topic table lists 0142 under Native app
  and Memories. · Done when: every `/usr/bin/python3 scripts/validate_*.py` but `validate_guard_gaps.py` exits 0.

- [x] **2 the web: a lightbox video interrupts the music (D13)** — `src/web/src/core/audio/player.ts` gains
  `interrupt()` (D13's rules: pause a playing queue, the end resumes only what it paused and only when no listener's
  play or pause, new queue or `stop` came between); `MediaLightbox.vue`'s `<video>` interrupts on `playing` and ends it
  on `pause`, `ended`, an item change (the slideshow's included) and its unmount. en.platform.audio-player and
  uc.memory.background-music fold D13 from their "Decided, not built" into the built text (the uc's main flow step 4
  no longer lets both sound); `memories/add-background-music.mdx` (EN+DE) says it where it speaks of the lightbox, if it
  does. · Done when: `pnpm run check` in `src/web/` green, with new `player.test.ts` cases (interrupt pauses and the lock
  screen shows paused; its end resumes; a listener's pause during it keeps the music paused after; a listener's play
  during it makes the end do nothing; a `stop` or a new queue during it makes the end do nothing) and
  `MediaLightbox.test.ts` cases (a video's `playing` pauses the player; its `pause`, `ended`, the next item and closing
  each resume it); **bites**, each one edit restored from a snapshot, each red at its own assertion: (a) the end resumes
  unconditionally → the listener's-pause case red; (b) the lightbox does not end the interruption on an item change →
  the next-item case red; (c) the end not dropped at `stop` → the stop case red.
  *Done (2026-11-15):* as worded; besides, the two `AudioPlayer` fakes (`AudioBar.test.ts`, `AudioMiniControls.test.ts`)
  gained an `interrupt` stub for the type, and uc.memory.background-music's coverage a criterion `a-video-pauses-the-music`.
  A dropped end after a `stop` has nothing left to play, so the stop case asserts the end wakes not even the
  `AudioContext` — the one thing a live end does where no deck holds a source; bite (c) went red there. The add-music Use
  pages (EN, DE) do not speak of the lightbox: unchanged.
  *As built after phase 4 (2026-11-16):* the web's `interrupt()` paused only a playing queue, so a track still loading
  when the video started sounded during it; `player.ts` now holds it as the core's `MusicPlayer` does (a `starting`
  flag; a track that loads while the interruption is live becomes the active deck, paused, and only the end starts it;
  every listener action frees the hold). New `player.test.ts` cases: the first track loading → silent until the end;
  the next track loading (a crossfade ahead) → the same; a listener's play while it loads → it starts once loaded and
  the end does nothing. The first two red before the fix; the third green before it (nothing was held then) and kept
  as the hold's release. Bites: the hold removed → both loading cases red; `interrupt()` blind to a loading track →
  the first-track case red.

- [x] **3 the player (core `audio/`)** — `Order`, `Fades`, `MusicPlayer` with `interrupt()` (D13), `NowPlaying`, the
  deck port (D2) and the system controls' port (D3's core half), with a played deck for tests. · Done when: `./gradlew
  :core:jvmTest :core:macosArm64Test` green, and R13's `order`, `fades` and `player` cases and phase 2's interrupt cases
  each have a Kotlin twin (the list mapped test by test in the phase's report); **bites**, each one edit restored from a snapshot, each red at its own assertion:
  (a) a round's first may be the last played → the 1,000-round test red; (b) `previous` restarts at any position → the
  2 s case red; (c) the press fade 5 s instead of 1 s → the replace case red; (d) a failed load not dropped from the order
  → the nothing-plays case loops instead of ending; (e) `stop` not clearing the system controls → its case red; (f) a
  late `open()` not released → the leaked-reader count red.
  *Done 2026-11-15:* `audio/` holds `Order.kt`, `Fades.kt`, `Deck.kt` (the `Deck` port, `SystemControls`,
  `PlayerCommands`), `NowPlaying.kt` (`PlayerTrack`, `TrackSource`, `PlayerQueue` with a `route: ObjectRef`, `NowPlaying`,
  `PlayerNotice`) and `MusicPlayer.kt` (`State {nowPlaying, notice, skipped}` — D9's skipped ids sit beside `nowPlaying`, not in
  it, so the Music view keeps its marks after a queue that played nothing ended). Tests `OrderTest` (11), `FadesTest` (4),
  `MusicPlayerTest` (21) over `PlayedAudio` on virtual time. Twins: every `order` and `fades` case; of `player`'s 13 the
  AudioContext-wake case has none (no deck needs a press's stack) and "no fade without a gain" became "a file that ends
  before its span moves on at once without a fade" (every deck ramps, D2); phase 2's interrupt cases each a twin, plus a
  queue paused before the video staying paused. Bites (a)–(f) each red at its own case and green again, and two more:
  the end resuming unconditionally → the listener's-pause/-play cases red; a new queue not counted → its case red.
  A track still loading when `interrupt()` came started during the video (D13 paused only a playing queue) — fixed in
  phase 4 (coordinator 2026-11-15): such a track is held, silent until the interruption's end starts it; ADR 0142
  decision 13 says so. The web's player held it too from 2026-11-16 (phase 2's as-built note).
  *As built 2026-11-16 (both players):* a listener's pause while a track loaded did not stick — the track started once
  loaded, with or without a video. `MusicPlayer.kt` and `player.ts` now keep a `pausedByListener`/`paused` flag (set by
  `pause`, cleared by a play, `next`/`previous`, a new queue, `stop`) that holds a load as an interruption does, and
  `interrupt()` no longer counts a load the listener paused as something to resume. Cases on both surfaces: pause while
  the first track loads → silent and paused once loaded, resume plays it; pause during a video while it loads → silent
  after the end; pause before a video while it loads → the end starts nothing; pause while the next track opens ahead →
  silent, the outgoing one paused (green before the fix: a paused deck's tick never begins the fade); pause while the
  next track is on its way in (the fade begun) → silent and paused, resume plays it. All but the open-ahead case red
  before the fix. Bites: the hold's condition without the flag → the four cases red (web and core); `interrupt()`
  blind to the flag → the pause-before-a-video case red (web).

- [x] **4 Memories' music (core `memories/music/`)** — `MusicQueue`, `AudioFacts`, `Frames`, `Wav`, `MusicUpload` (D7),
  `TrackLabel` (D10), the ITEM report (D11), `MusicModel` (D8), the header toggle (D6), the lightbox's compact-control
  state (D5) and its video's interruption (D13), D12's `keeps`/`pinned`. Tests: R13's Memories cases ported; a **cross-surface fixture** — a listed tier as
  the web's `media.ts` seals it (`durationMs`, `trimStartMs`, `trimEndMs`, `trackName`, plus a field core does not know)
  opened by `AudioFacts`, and after a rename every field but `trackName` byte-equal; live tests (`VEYQ_LIVE`): upload a
  WAV with 2 s of silence at each end and an MP3, read both back (the WAV trimmed within 20 ms, the MP3 untrimmed with its
  duration), rename the MP3 from one session while a second renames it → the conflict word with the peer's name, report
  the WAV as an ITEM (the report accepted, its keys open the blob), pin the Memory and count both tracks' frames in the
  store. · Done when: core and `:bridge:jvmTest` green, the live tests green; **bites**: (a) a rename re-sealed from the
  parsed meta → the unknown field gone, red; (b) the claimed type sealed instead of the sniff's → an M4A sealed
  `video/mp4`, red; (c) `keeps` without audio → the pinned tracks' frame count 0, red; (d) an `audio` fact with
  start ≥ end accepted → `AudioFacts` validation case red; (e) the queue numbering its unsorted list → D4's one-numbering
  case red; (f) a 200 MiB + 1 byte file accepted → red.
  *Done 2026-11-16:* `memories/music/` holds `Frames`, `Wav`, `AudioFacts` (with `Decoded`), `MusicQueue` (with
  `MusicTrack`: the view's sorted list numbers an untitled track once, the queue plays in listing order under the same
  title), `MusicUpload` (`check` → `Check.Taken|Refused`, the platform's length through `MusicUpload.Measure`; `Job` seals
  the sniffed type, the listed tier `metaTier` = the file's date + the three facts, no derived tier), `TrackLabel` (raw
  read-modify-write over `NodeMetaWrite`'s state, at most two sends) and `MusicModel` (D8, over the gallery). Beside it:
  `memories/MediumReport` (D11's keys) with `NodeReport.reportItem` (a function, not a `Scope` value: `Scope` maps to
  NODE/NAME and an ITEM's keys are the application's); `ReportSheet(offersName = false)` for an item; `MemoryMedia.Meta`
  gains `trackName` and `facts`, `Item` its `metaRevision`, raw `wrappedMeta` and `isAudio`; `keeps`/`pinned` play an audio
  original as a video's (D12); `GalleryModel` takes `GalleryModel.Music(player, untitled)` — `State.music`/`musicHere`, the
  header's `MUSIC` action (after `share`, the web's order) with `toggleMusic`/`playMusic`, a track's trash with Undo — and
  its `Lightbox` gains `State.music`, `musicControls` and `videoPlaying(Boolean)` (item change, `stop`, `close` end the
  interruption); `MediaUploads.uploadMusic` with `Door.musicJob`; `ShellModel.musicPlayer(newDeck, controls)` and
  `musicModel(gallery, uploads, measure, left)`. Nine words in `words.json` (`music_play|stop|add|untitled|too_large|
  not_audio|unplayable|rename_conflict|rename_failed`, EN+DE, the unplayable one the owner's copy). The untitled title
  is a `(Int) -> String` handed in (the web's seam): the core renders no word, so phase 5 needs it from the host.
  Tests: `FramesTest` (5), `WavTest` (4), `AudioFactsTest` (12: the scan's 7, the apps' measuring 4, `audioFactsFrom`), `MusicQueueTest`
  (7), `MusicUploadTest` (6), `TrackLabelTest` (7, the cross-surface fixture: a tier as `media.ts` seals it with two
  fields no surface knows, opened by the core, renamed with every other field byte-equal in its place), `MusicModelTest`
  (13, the lightbox's interruption among them). Where the web measures by decoding, the apps' twin asserts the narrower
  D7 rule (a WAV whose format is not read, and every decoded format, untrimmed with nothing read). `MusicLiveTest`
  (`VEYQ_LIVE`) green: WAV trimmed 2000..5000 of 7000 ms, MP3 untrimmed 10449 ms, typed by its bytes against the claimed
  `video/mp4`, rename conflict naming the peer's "Alpha", ITEM report SENT with its BLOB key opening the WAV, pin keeps both
  originals whole; the Memory trashed and purged, every device removed. Each live run leaves one open ITEM report in the
  dev instance's moderation queue. Bites (a)–(f) each red at its own assertion and green again; (c) also live (the pin's
  plan counts no blob).

- [x] **5 the bridge** — `Watched.Player` → `PlayerShown`, `Watched.Music` → `MusicShown`, the calls (`Player.Play|
  Pause|Resume|Next|Previous|Seek|Stop|OpenNode`, `Music.Play|Rename|Remove|Download|Report|Add(paths)`), the deck host
  requests and events (D2), the system-controls commands as calls; `PlayerShown` and `MusicShown` under
  `SecretFreeSchemaTest`; a player's readers dropped with its tenant (`DispatchTest`'s pattern). · Done when:
  `:bridge:jvmTest` green; **bites**: (a) a `K_blob` field added to `MusicShown` → `SecretFreeSchemaTest` red; (b) the
  player left out of `closeTenant()` → a reader outliving the sign-out, red.
  *Done 2026-11-16:* `Wire.kt` gains the calls `Gallery.Music` (the header's play or stop: the CR's `Player.Play` is no
  player command — the bar never starts a queue — so it is the gallery's toggle), `Lightbox.Video(playing)` (D13),
  `Player.Pause|Resume|Next|Previous|Seek|Stop|OpenNode` (the system's commands come back as these, the system's play is
  `Resume`), `Music.Open|Close|Play|Rename|Remove|Download|Report|Add|Seen`, the deck events `Audio.Ended|Failed(deck,
  load)`; the shown `PlayerShown`, `NowPlayingShown` (no queue key: `key` is a secret's name on the wire), `MusicShown`,
  `MusicTrackShown`, `GalleryShown.musicView|musicHere`, `LightboxShown.music`; the host requests `Audio.Load(deck, load,
  reader, size, mediaType)|Play|Position` (asked) and `Audio.Pause|Seek|Ramp|Clear`, `Controls.Bind|Show|Clear` (told:
  `Host.tell`, the reply dropped), and `Words.Say(notice)` — the untitled title the core needs as a string, the host's words
  (`HostAudio.untitled`). The decks ask through `Host.asking`, which suspends instead of blocking the player's lane; a load's
  reader is held by id like any (`HeldReaders`) and dropped at the deck's clear; each load is numbered so a late event of a
  cleared deck is dropped. A picked track's length is the existing `Media.Duration` (`HostAudio.measure`). The player is
  made at a tenant's open (`Tenant.musicPlayer`, `Composition.audio`) and stopped in `closeTenant()`. One word added,
  `music_nothing_plays` (the owner's copy of question 1), for `PlayerShown.notice`. Tests: `MusicDispatchTest` (4: decks,
  events, commands, controls and the lightbox's interruption; a queue none of whose tracks loads; the Music view's calls;
  the tenant's end), `WireTest`'s samples for every new variant. Bites: (a) `K_blob` on `MusicShown` stayed **green** —
  `SecretFreeSchemaTest`'s name rule did not see the web's key symbols; the rule gained `^[kK](_|[A-Z])` (with its own
  self-check case), then (a) red at `nothingTheCoreSendsCarriesAKeyASealATokenASecretOrBytes` and green restored; (b) the
  player's stop removed from `closeTenant()` → red at "a track's reader outlived the sign-out", green restored.

- [ ] **6 Android and Apple (iOS, macOS)** — D2's decks, D3's background and system controls, D5's bar and compact
  controls, D6's view switch and Music view, D7's picker, every word from `words.json` (EN+DE). First, measured on the
  emulator and the macOS build: which of D9's formats each platform's player loads from a reader (the matrix in the
  phase's report). · Done when: `./gradlew :android:assembleDebug`, the Apple project's builds (iOS simulator SDK,
  macOS) and `scripts/validate_mobile_words.py` green; each app clicked once: add a track, play, next, seek, rename,
  leave the Memory and keep hearing it, play a video in the lightbox (the music pauses and resumes, D13), close the bar.

- [x] **7 Windows and Linux** — the same over the bridge, D3's SMTC and MPRIS, the Flatpak's `--own-name`. First,
  measured: D9's format matrix on the VM (Web Media extension present and removed) and in the container and the Flatpak;
  whether `Media.Duration`'s `MediaClip` path reads audio (else the host answers it another way). · Done when: `run.py
  build`/`test` green on both; each app clicked once as phase 6's.
  *Done 2026-11-16:* the matrices are ADR 0142's Consequences (Windows: all ten with Web Media, both Oggs `C00D36C4`
  without it; Linux: the container all but ALAC, the Flatpak's runtime all ten). `Media.Duration` did not read a track on
  either: Windows' `MediaClip` refuses Ogg (`80070057`) — `MediaTiers` falls back to `MediaSource.OpenAsync`; Linux's
  video-only `playbin` says no length for any track — `media_tiers` measures it by its sound (`a_tracks_length_is_read_from_its_sound`,
  red with the video path alone). Built per platform: Linux `music_host` (the decks over `reader_stream`, answered from
  GTK's main loop through `bridge::reply`; MPRIS over gio's D-Bus while a queue is loaded; `Words.Say`), `music_bar` (a
  `GtkActionBar` under the split view, `Shell.root`), `music_view` (clamped boxed list, rows updated in place so an open
  menu survives the next track), `memory_page`'s `AdwToggleGroup`, the header's `music`, the lightbox's compact controls
  and `Lightbox.Video`; Windows `MusicHost` (decks, SMTC
  through `GetForWindow`, decks' `CommandManager` off) routed by `Core.Player` on the core's thread in order, `MusicRamp`,
  `MusicBar`, `MusicView`, `MemoryPage`'s `SelectorBar`, the header's music, `LightboxView`'s compact controls,
  `MediumPlayer`'s `Lightbox.Video`. The Flatpak's `--own-name` is **not** added: `flatpak-builder-lint` refused it
  (`finish-args-unnecessary-appid-mpris-own-name`, "granted by default"), so the manifest names why instead and ADR 0142
  no longer lists it. One bridge gap closed (minimal, no core change): `ReportShown.offersName`, so a
  track's report sheet shows no "only its name" (both hosts hide the choice). A refusal at add was said twice (the
  answer's list and the view's `said` carry the last one): both hosts say the answer's list but its last.
  Clicked live against the dev backend — Linux under Broadway (headless Chrome, its own home volume, removed after):
  add five files (the ALAC refused `unplayable`), play, next, a seek by the bar's scale, rename,
  download (byte-equal), remove and its Undo, a track's report sheet without the scope choice, Home with the music
  going on, the bar's node link back, the header's Stop and Play, a lightbox video pausing the music and its pause and the
  lightbox's close resuming it, the bar's close; MPRIS with `playerctl` and `gdbus` in the container (listed as
  `app.veyq.Veyq`, Pause/Play/Next acting, gone after the close); offline: the Memory made available (4.1 MB, its tracks
  included), the forwards cut, the Music view read-only and its tracks playing from the store. Windows on the VM (the
  fork's pass): add four tracks, play, next, seek, rename, download, remove and Undo, Home with the bar on, the node link,
  a lightbox video pausing and its end and the close resuming, the SMTC session read in the logged-on session
  (`GlobalSystemMediaTransportControlsSessionManager`: `Playing`, `TrySkipNext`/`TryPause`/`TryPlay` acting, gone after
  the close). Not run here: a track that does not play on this device during a session (Windows had the extension; the
  container's only unplayable file was refused at add), Windows minimised and the media keys, the Flatpak's MPRIS from a
  desktop session (phase 8 (d) and (e)).

- [ ] **8 the passes** — per platform against the dev instance: play a Memory's music, walk to another Memory and a
  Chapter, open the lightbox (the compact controls; a video pauses the music and its end, the next item and closing resume it, D13), (a) **Android**: screen
  off for 5 minutes with the music going on through a track change, the lock screen's and a headset's next, the
  notification without `POST_NOTIFICATIONS` granted (D3), headphones out pauses; (b) **iOS** on a device (the owner's):
  the same with the lock screen and Control Center, a call's interruption; (c) **macOS**: Now Playing and the media keys;
  (d) **Windows**: the SMTC flyout and the media keys, minimised; (e) **Linux**: `playerctl` (or `busctl`) lists the app
  and its next/pause act, in the Flatpak; then on one phone: pin the Memory, airplane mode, close, unlock, play.
  · Done when: each pass's commands and outputs are in ADR 0142's Consequences, with what could not be run named.

- [ ] **9 harvest** — `memories/add-background-music.mdx` (EN+DE) tells the apps (background play, the system controls,
  each platform's formats that do not play from phase 6/7's matrices, the untrimmed decoded formats), `install-the-app.mdx`
  (EN+DE) no longer sends music to the browser; Security's `what-the-server-sees.md` unchanged unless a pass found
  otherwise; en/uc and `native-apps.md` from planned to built; the lessons to `changes/README.md` or the lines they bite;
  inbox #906 deleted, this CR deleted. · Done when: `pnpm run check` in `site/` green and every guard but guard_gaps green.

## Owner questions

Answered 2026-11-15: **1** — the copy as recommended (EN and DE). **2** — *everywhere* (the apps **and the web**) the
music pauses while a video in the lightbox plays and starts again by itself when the video stops or the lightbox moves
on. The web half is a change of this CR: a small web phase beside the core's (`src/web/src/core/audio/player.ts` and the
lightbox's video), so the Definition of done's empty `src/web/` diff no longer holds — only `contracts/` and
`src/backend/` stay untouched.

1. **Copy for the apps' new words** (EN / DE). The web's `audio.*` and `memories.music.*` are reused as they are, except
   three that say "browser", and four the apps add. *Recommendation:*
   - `music_unplayable`: "{name} can't be played on this device." / "{name} kann auf diesem Gerät nicht abgespielt
     werden." (web: "…in this browser")
   - `music_nothing_plays`: "None of these tracks can be played on this device." / "Keiner dieser Titel kann auf diesem
     Gerät abgespielt werden."
   - `music_track_unplayable` (the mark in the Music view): "Doesn't play on this device" / "Wird auf diesem Gerät nicht
     abgespielt"
   - `music_view_photos` / `music_view_music` (the view switch): "Photos" / "Fotos", "Music" / "Musik"
   - `music_notification_channel` (Android's channel name in the system settings): "Music" / "Musik"
2. **A video in the lightbox while music plays.** The web lets both sound. *Recommendation:* the apps pause the music while
   a video plays and resume it when the video stops or the lightbox moves on — on a phone two sounds at once are what the
   platforms' own guidelines (audio focus, AVAudioSession) avoid; the web may follow later.

## Inbox rows

#906 (this topic, refined into this CR), #932 (the silence trim of decoded formats uploaded from an app), #933 (the web's
two numberings of an untitled track), #847, #435, #891, #907 (out of scope above).

## Definition of done

Phases 1–9 checked with their Done-when met; `pnpm run check` in `src/web/` and the core, bridge, Linux and Windows
checks green; the bites above each seen red and green again; the format matrices and the passes recorded per platform in
ADR 0142; the Use pages in EN and DE updated with `pnpm run check` in `site/` green; every `scripts/validate_*.py` but
guard_gaps green. No contract or backend change: `git diff --stat` over `contracts/ src/backend/` is empty at the end;
`src/web/`'s diff is phase 2's alone (`core/audio/player.ts`, `MediaLightbox.vue`, their tests, and the two player fakes'
`interrupt` stub).

## Retrospective

_(filled at the end of the implementation)_
