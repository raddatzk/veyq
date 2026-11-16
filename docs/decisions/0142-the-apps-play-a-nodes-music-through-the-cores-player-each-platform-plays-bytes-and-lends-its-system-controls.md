# ADR 0142 — The apps play a node's music through the core's player; each platform plays bytes and lends its system controls
- Status: Accepted
- Built: no
- Date: 2026-11-15
- Model: [native-apps](../crosscutting/native-apps.md) (the player: [en.platform.audio-player](../features/platform/enablers/en.platform.audio-player.md),
  the use case: [uc.memory.background-music](../features/memory/use-cases/uc.memory.background-music.md))

## Context

[ADR 0135](0135-background-music-is-a-nodes-own-audio-media-played-by-cores-player.md) built a Memory's and a
Chapter's background music on the web: the node's own audio media, played by core's player, shuffled, endless,
crossfading over 5 s, in a bar that stays while the viewer moves on. The apps leave audio out of the gallery and do
nothing else with it (inbox #906). The owner made music the first web catch-up for the apps (2026-11-15), with one
thing the web cannot give: **on the phones music keeps playing in the background and with the screen off**, with the
system's controls; on the desktops the system's media controls where the platform has them.

What the apps have to build on (read at refine, 2026-11-15):

- **The web's player is one module with a small seam** (`src/web/src/core/audio/`): an application hands a queue of
  tracks, each with an id, a title, a trimmed span and an `open()` that returns a playable source and a `release()`;
  the order (`order.ts`) and the fades (`fades.ts`) are pure; `player.ts` drives two elements through one `GainNode`
  each, opens the next track 15 s before its fade, skips a track that fails and ends the queue `unplayable` when none
  plays. Its tests (`order`, `fades`, `player`, and Memories' `musicQueue`, `audioFacts`, `frames`, `wav`, `audio`) are
  the list a port mirrors.
- **Bytes already reach a player through a core reader on every platform**
  ([ADR 0137](0137-the-bridge-carries-shown-bytes-beside-the-json-ranges-through-a-reader-and-files-by-path.md)):
  `RangeReader` under Android's `MediaPlayer`, Apple's `AVPlayer`, Windows' `MediaPlayer` and Linux's
  `gtk::MediaFile`, as a video and a Depot file play. No platform has background audio or system media controls yet,
  and nothing locks a session when an app goes to the background or the screen turns off.
- **The apps' upload path refuses audio** and trusts the claimed type; `Sniff.stream`, the web's `sniffStream` with the
  audio containers, is used by Depot only. No medium-meta write and no `ITEM` report exist in the apps.
- **The offline store** ([ADR 0140](0140-the-apps-keep-content-as-ciphertext-sealed-to-the-device-by-choice-or-within-a-limit-an-unlock-opens-it-offline.md)
  decision 8) keeps a video's original and a Depot file's, never a photo's.
- **On the web a lightbox video and the music both sound**: the lightbox makes no call into the player. The owner
  (2026-11-15) decided that this changes everywhere, the web included (decision 13).

[ADR 0136](0136-an-apps-logic-is-the-kotlin-cores-each-platform-draws-only-its-own-ui.md) decides that an app's logic
is the Kotlin core's and each platform draws only its own UI; this ADR applies it to music and adds no rule to ADR
0135's playlist.

## Decision

1. **The player is the core's, a port of the web's.**
   - **`core/…/audio/`** (platform-generic, as the web's `core/audio/` is): `Order` (randomness from the suite's
     `randomBytes`, injectable for tests), `Fades`, `MusicPlayer` (the web's state machine: queue, snapshot, the 250 ms
     tick, opening 15 s ahead, skip, `unplayable`, replace with a crossfade, at most two open sources) and its state
     `NowPlaying`. Its API is the web's: `play(queue)`, `stop`, `pause`, `resume`, `next`, `previous`, `seek(ms)`, and
     `interrupt()` (decision 13).
   - **`core/…/memories/music/`** (Memories' side): `MusicQueue`, `AudioFacts` (parse and validate the listed tier's
     facts exactly as the web's `audioFactsFrom`; the silence scan; `untrimmed`), `Frames` (MP3/ADTS parsing,
     `adtsDurationMs`), `Wav` (header and PCM at both ends), `MusicUpload` (decision 7), `TrackLabel` (decision 10) and
     the Music view's model (decision 8).
   - **The player gets readers, never keys** (ADR 0135 decision 8's line, kept): a track's `open()` is Memories', returns
     a `RangeReader` with its MIME (`MemoryMedia.reader`, with decision 12's `keeps`), and `release()` closes it.
   - **One player per signed-in account in the process**, built by the shell beside `mediaUploads` and stopped where
     `closeTenant()` closes them: a lock, a sign-out, a tenant switch — as the web's shell unmount stops it.
2. **A platform plays bytes: two decks behind one core port.**
   - **The port:** `load(reader, mime) → durationMs` (fails when the platform cannot open it, decision 9), `play`,
     `pause`, `seek(ms)`, `positionMs`, `ramp(to, overMs)` (a volume ramp the platform runs smoothly, steps ≤ 50 ms),
     `clear` (stop, release the platform's player, close the stream), and the events `ended` and `failed`.
   - **The crossfade is the core's timing and the platform's ramp.** The core asks `ramp(1, 5000)` of the incoming deck
     and `ramp(0, 5000)` of the outgoing one, as the web drives its two `GainNode`s; it never steps the volume itself,
     which would be a 20 Hz stream of calls across the bridge for nothing.
   - **Per platform**, on the reader glue each has: Android two `MediaPlayer`s over `ReaderSource`, the ramp a
     `VolumeShaper`; Apple two `AVPlayer`s over `ReaderLoader`, the ramp `AVPlayer.volume` stepped (settable on iOS,
     unlike the web's element), the loader's MIME-to-UTI map gaining the audio types; Windows two `MediaPlayer`s over
     `ReaderStream`, `Volume` stepped on the dispatcher queue; Linux two `gtk::MediaFile`s over the `gio::InputStream`,
     `set_volume` stepped on a GLib timeout.
   - **Across the bridge** (Linux, Windows) the decks are the host's: the core reaches them through host requests
     (`Audio.Load|Play|Pause|Seek|Ramp|Clear|Position`, as `Media.Duration` is one), the host reports `ended` and
     `failed` as calls, and the reader crosses as ADR 0137's does.
3. **Background play and the system's controls.** The core owns what the system shows and which commands exist
   (`NowPlaying` and `MusicPlayer`'s actions, as the web's MediaSession binding); each platform wires them to its
   system surface, set when a queue loads and cleared at `stop`:

   | Platform | Keeps playing | System controls | Also |
   |---|---|---|---|
   | Android | a `Service` with `foregroundServiceType="mediaPlayback"`, started from the press that plays, stopped at `stop`; `FOREGROUND_SERVICE_MEDIA_PLAYBACK` declared | a framework `MediaSession` (title, node name, position, play/pause/next/previous/seek) and a `Notification.MediaStyle` notification bound to it — the lock screen and Bluetooth/headset buttons follow | audio focus (`AUDIOFOCUS_GAIN`; pause on a loss, duck on a transient one), pause on `ACTION_AUDIO_BECOMING_NOISY` |
   | iOS | `AVAudioSession` category `.playback`, `UIBackgroundModes` `audio` | `MPNowPlayingInfoCenter` + `MPRemoteCommandCenter` (play, pause, toggle, next, previous, change position) | pause on an interruption, resume when it ends with `.shouldResume`; pause when the route's old device goes away |
   | macOS | the app runs | `MPNowPlayingInfoCenter` + `MPRemoteCommandCenter` (Now Playing, the media keys) | — |
   | Windows | the app runs, minimised included | one SMTC for the window (`SystemMediaTransportControlsInterop.GetForWindow`), both decks' `CommandManager` off, so the flyout and the media keys drive the core's player, not a deck | — |
   | Linux | the app runs (with its window closed as the app's other background work does) | MPRIS (`org.mpris.MediaPlayer2` and `.Player` on the session bus through gio's D-Bus), bus name `org.mpris.MediaPlayer2.app.veyq.Veyq`; the Flatpak gains `--own-name=` for it | — |

   - **Frames keep coming in the background** because the process does: the reader fetches as it does in front, and the
     offline store keeps what it keeps (decision 12). The session stays unlocked with the screen off, since nothing
     locks it on leaving; this decision adds no unlock rule.
   - **The notification permission:** a media-session notification is exempt from `POST_NOTIFICATIONS` on Android 13+
     (remembered, measured in the Android pass); if it is not shown without the grant, the play press asks for it, as a
     pin does.
4. **The queue: the node's tracks, a snapshot, one numbering.** The web's `musicQueue`: the node's unblocked audio
   media in listing order, snapshot at `play`; `id` the medium's id; the span from `AudioFacts` (a track without valid
   facts plays whole); `key` `memories:<scope>:<node>`. **An untitled track is numbered once:** its `Track {n}` takes
   `n` from the Music view's sorted order, and the queue uses the same title. The web numbers its sorted tab and its
   unsorted queue apart; that defect is not ported (inbox #933).
5. **The bar, its node link, and the lightbox's compact controls.**
   - **The bar** (the web's: title · node name, previous, play/pause, next, a progress over the trimmed span that seeks
     on release, elapsed and total, close) is drawn by each platform while a queue is loaded, at the shell's bottom: on
     the phones above the application's bottom bar (the mini-player of the platforms' own music apps), on the desktops
     along the window's bottom edge. Its state is the core's `NowPlaying`; Linux and Windows read it as a watched
     `PlayerShown`.
   - **The node link:** the node's name in the bar opens the node that started the queue, by a navigation **route**
     (scope, node) the queue carries, which the shell opens as it opens a search result. A node no longer readable opens
     nothing and says the shell's existing not-found word.
   - **The lightbox's compact controls** (title, previous, play/pause, next) sit in its top bar while a queue is loaded,
     a row of their own on a phone. Closing the lightbox stops nothing, and the slideshow's own pause ignores them. A
     video there interrupts the music (decision 13).
6. **Where a node's music shows: the header toggle and the Music view.**
   - **The header's music toggle** (ADR 0135 decision 9): a node with music shows one header action, play/stop, through
     the gallery's header actions as `UPLOAD` is added there, never as a core `NodeHeader` action: music is Memories'.
   - **The Music view** is a view switch inside the Memory screen (Photos | Music), shown where the node has music or the
     viewer may upload: Android a `PrimaryTabRow` under the top bar, Apple a segmented `Picker`, Windows a
     `SelectorBar`, Linux an `AdwToggleGroup`. It lists the tracks (title, the file's whole length as the web shows
     it), the playing one marked; pressing a track plays it first, then the rest shuffled (ADR 0135 decision 6). A
     track's menu: rename (decision 10), download (where the gallery's save is offered), remove (the gallery's trash
     with Undo), report (decision 11). "Add music" where the viewer may upload.
7. **Adding a track: the web's refusals, its facts, and a narrower trim.** Only from the Music view (the gallery's picker
   keeps refusing audio), through each platform's document picker filtered to audio (Android `OpenMultipleDocuments`
   with `audio/*`, no `READ_MEDIA_AUDIO`; Apple `fileImporter` with `.audio`; Windows `FileOpenPicker`; Linux
   `GtkFileDialog`), through `MediaUploads` as a music upload.
   - **Refusals, in the web's order, by the core:** over **200 MiB** → `tooLarge`; `Sniff.stream` of the head is not an
     audio container → `notAudio`; the platform cannot read its duration → `unplayable`.
   - **The sealed type is the sniff's** (`audio/mp4` for an M4A, never `video/mp4`), not the claimed one, for a music
     upload; the gallery's photo path keeps its order.
   - **The duration** comes from the platform's reader of the picked file (Android `MediaMetadataRetriever`, Apple
     `AVURLAsset`, Windows and Linux the `Media.Duration` host request, extended to audio where it refuses it), except a
     raw ADTS file, whose frames the core counts (`adtsDurationMs`, the web's rule).
   - **The trim is found only where it is cheap without a platform decoder: WAV.** The core reads the PCM at both ends
     and runs ADR 0135 decision 4's scan. **Every decoded format is sealed untrimmed** (`trimStartMs = 0`,
     `trimEndMs = durationMs`), and since the facts are never rewritten, such a track keeps its edges' silence wherever
     it plays (inbox #932).
   - **The listed tier** gains `durationMs`, `trimStartMs`, `trimEndMs` beside what `metaTier` writes, field for field
     the web's; no derived tiers; the blob-meta name is the file's.
8. **The Music view's model is the core's.** `MusicModel` (per node, from `ShellModel` as `galleryModel` is): the
   tracks (sorted by title with a collator, the untitled numbered, the length, the playing one), the rights (upload,
   rename, download, report), the uploads under way (Memories' existing batches) and the actions. It reads the same
   `MemoryMedia.list` and live poll the gallery reads. The bridge carries it as `Watched.Music` → `MusicShown`
   (titles, lengths, states, words — never a key, a seal or a token).
9. **A format a device cannot play is skipped, marked and said.**
   - **At upload** a file the device cannot read is refused `unplayable` (decision 7).
   - **At playback** (ADR 0135 decision 3) a track whose deck fails to load is dropped from the round and skipped; the
     Music view marks it "doesn't play on this device" for the rest of the session (the player's skipped ids in
     `NowPlaying`); when none plays, the bar says so for 5 s and the queue ends. Download still offers the file.
   - **Windows ships no codec** (owner 2026-11-12, kept): what plays is what Media Foundation and the extensions
     Windows 11 provisions decode. The format matrix — MP3, M4A (AAC and ALAC), raw ADTS, WAV (16/24-bit, float), FLAC,
     Ogg Vorbis, Ogg Opus — is measured on each platform (Windows with the Web Media extension present and removed,
     Linux in the container and the Flatpak, Apple on the macOS build, Android on the emulator) and recorded in the
     Consequences; the docs site names each platform's gaps from it.
10. **Renaming a track: the web's raw read-modify-write.** `TrackLabel.rename(medium, name?)` opens the medium's raw
    listed tier, sets or deletes `trackName` (an emptied name gives the file's back), keeps every other field
    verbatim, re-seals under the medium's key and calls `updateMediaMeta` at the listed `metaRevision` and node epoch
    ([ADR 0077](0077-a-write-carries-the-version-it-read.md)). On `409 stale_revision` it retries once on the returned
    tier: a peer's change of the **label** answers `rename_conflict` and shows the peer's name; any other peer's write
    merges. Offered where the medium's meta may be written (a member with write rights; the apps open galleries as
    members only).
11. **A track is reported as an `ITEM`.** `NodeReport` gains an `ITEM` scope with the medium's id and the port of the
    web's `mediumReportKeys`: every blob's `K_blob` by blob id, the `media-meta` field key, and `media-exif`'s (the
    apps' viewer is a member). The report sheet is the existing one. The moderator hears it on the web (ADR 0135
    decision 10, [ADR 0093](0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)).
12. **Offline: a pinned Memory's tracks are kept like a video's original.** A track's original is what the app reads
    to play it, so `MemoryMedia.keeps` and `pinned` treat an audio medium as a video: a pin of a Memory or Chapter
    fetches its tracks whole and a pinned node's music plays offline; the automatic tier keeps the frames played, its
    quarter-of-the-limit rule unchanged (a 200 MiB track is under a quarter of the phones' 2 GB default). Offline the
    Music view is read-only (ADR 0140 decision 7): no add, rename, remove or report; playing works.
13. **A lightbox video interrupts the music, on every surface — the web and the apps** (owner 2026-11-15).
    - **The rule:** while a video in the lightbox plays, a playing queue pauses; when the video pauses or ends, the
      lightbox moves to another item (by hand or by the slideshow), or the lightbox closes, the music starts again by
      itself, as the bar's play would.
    - **The seam is the player's:** `interrupt()` pauses a playing queue and returns an end; the end resumes the queue
      only when it was playing when interrupted and nothing came between — a listener's play or pause, a new queue, a
      `stop`. A listener who presses play during the video hears both by their own choice; one who presses pause keeps
      it paused after the video. While interrupted, the bar, the compact controls and the system controls show the
      queue paused. **A track still loading** when the video starts — the queue's first, or the next one on its way in —
      is held, by the core's `MusicPlayer` and the web's `player.ts` alike: it does not start during the video, and the
      end starts it (if nothing came between).
      A listener's pause holds a loading track the same way (as built 2026-11-16, both players): a track — the first, or
      the next one on its way in — that finishes loading after the pause becomes the active deck, paused and shown
      paused, and only a play (or a track change) starts it; a video's end after such a pause starts nothing.
    - **Who raises it:** the lightbox, from its video's own playing, pause and ended events, its item change and its
      close — on the web `MediaLightbox.vue` over `core/audio/player.ts`; in the apps the core's lightbox model over
      `MusicPlayer`, each platform's video player reporting playing and paused. A muted video counts as playing: the
      lightbox does not read its volume.
    - **Android:** the lightbox's video plays without requesting audio focus of its own, or the music service's focus
      listener would take it as a loss and pause with no end to resume it.

## Consequences

- **What the server learns is unchanged:** a track is a plain medium (ADR 0135 decision 2); playing it in an app shows
  up as reads of one node's blobs, as a video does. No type, title, duration, trim or order leaves a client unsealed.
  The contracts and the backend do not change; every read and write the apps call exists.
- **The web changes only by decision 13** (`player.ts` gains `interrupt()`, the lightbox raises it); this replaces the
  web's "both sound" (ADR 0135's header line).
- **ADR 0135's background residual does not hold for the apps.** A phone may suspend a hidden tab, and so the web's
  music with it; the apps play on with the screen off (decision 3), because the process that reads the frames keeps
  running.
- **A decoded format uploaded from an app keeps its silence** (decision 7, inbox #932): a track added from the web is
  trimmed where ADR 0135 decision 4 finds it cheaply, the same file added from an app only where it is a WAV.
- **Each platform's format gaps** are what its matrix (decision 9) measures; none has been measured for audio yet, and
  until then no doc says which formats an app plays.
- **New platform permissions:** Android `FOREGROUND_SERVICE_MEDIA_PLAYBACK` and a `mediaPlayback` service; iOS
  `UIBackgroundModes` `audio`; the Flatpak `--own-name=org.mpris.MediaPlayer2.app.veyq.Veyq`. No new dependency.
- **A pinned Memory's music plays offline** (decision 12), which [ADR 0140](0140-the-apps-keep-content-as-ciphertext-sealed-to-the-device-by-choice-or-within-a-limit-an-unlock-opens-it-offline.md)
  decisions 8 and 9 take as one more kept original.
- **Not decided here:** a Chapter playing its Memory's music (inbox #847, the web has it neither); a guest's view of a
  received link in the apps (#435), so a guest listens on the web until then; reporting a photo or video from the
  apps' lightbox (#891); GPX tracks in the apps (#907).
- **The passes** — each platform's background play, system controls, the video's interruption and the offline play —
  are recorded here once run, with what could not be run named.

## Alternatives considered

- **Each platform's own player logic** (Media3's playlist, `AVQueuePlayer`, a GStreamer playlist) — five copies of the
  order and fade rules, which ADR 0136 decision 1 forbids, and none of them crossfades.
- **One player per tenant, kept alive across a switch** — the web stops at a switch, and keeping a tenant's readers
  alive after its session closed is what `closeTenant()` exists to prevent.
- **Media3/ExoPlayer on Android** — a new dependency for what the framework player and `VolumeShaper` already do; its
  session model is built around its own `Player`, which would mirror the core's state a second time.
- **One platform player with a gapless playlist** — no crossfade, and a trimmed span inside a file is not a playlist
  feature on every platform.
- **iOS without a background mode, Android without a service** (play only in front) — the owner decided that music plays
  on with the screen off (2026-11-15).
- **MPRIS through `zbus` directly** — it is in the tree only through `ashpd`; gio's D-Bus is what the app already links.
- **Carrying the web's `href` to the node** — the apps navigate by route, not by URL.
- **A bar tab for music** — Memories' bar is application-level ([ADR 0122](0122-one-app-holds-every-application-the-drawer-chooses-the-bar-belongs-to-it.md)),
  so a Music tab there would be every Memory's at once. **A sheet from the header** — the view is a list people return
  to and act on, as the web's tab is.
- **Four platform decoders now** (MediaCodec, AVAudioFile, Media Foundation, GStreamer) behind a `decodeSlice` host
  request, to trim the decoded formats — four native paths for a nicety ADR 0135 already leaves out for a long M4A,
  FLAC or Ogg; inbox #932 keeps it, with `AVAudioFile` as the cheap first one.
- **Sealing no facts for a decoded format** — the web would then play it whole too, but an export would carry no `audio`
  field and an import would detect anew; sealing the duration keeps the length in the Music view.
- **Re-sealing a rename from the parsed `MemoryMedia.Meta`** — it parses the photo fields only and would drop the rest.
- **A lightbox video and the music both sound** (the web's behaviour before decision 13) — two sounds at once are what
  the platforms' own audio rules (Android's audio focus, iOS's `AVAudioSession`) avoid, and the video's sound is what the
  viewer just chose to hear.
- **Ducking the music under the video** — still two sounds, at a level that suits neither; Android's
  may-duck focus is meant for short sounds such as a notification, not a video. The owner chose a pause (2026-11-15).
- **Stopping the queue for a video, or pausing it without resuming** — the listener would have to start the music
  again by hand after every video; the owner wants it to start again by itself.
