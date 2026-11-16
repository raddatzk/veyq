# UC: Background music — a Memory's or Chapter's own audio files, shuffled, looped and crossfaded

- **ID:** uc.memory.background-music
- **Status:** **built** ([ADR 0135](../../../decisions/0135-background-music-is-a-nodes-own-audio-media-played-by-cores-player.md),
  the player [en.platform.audio-player](../../platform/enablers/en.platform.audio-player.md)): Memories'
  `features/memories/music/` and `MemoryMusic.vue`. It replaces the Spotify and Apple Music playlists, which ADR 0135
  removed. A lightbox video's pause of the music is built on the web
  ([ADR 0142](../../../decisions/0142-the-apps-play-a-nodes-music-through-the-cores-player-each-platform-plays-bytes-and-lends-its-system-controls.md)
  decision 13); the apps' music is built by the same ADR ([§ In the apps](#in-the-apps)) and passed on Android, Windows
  and Linux; Apple's passes are open ([native-apps](../../../crosscutting/native-apps.md) § Open).
- **Primary actor:** Anyone who may upload into the Memory or Chapter on screen (`memoryRights.upload`): a member who
  may write its scope, or a guest of a link that may upload. **Secondary:** anyone who opens it — a member, or a guest
  of any link onto it — listens.
- **Goal:** A Memory or Chapter carries its own music, any number of audio files. Whoever opens it plays them as one
  endless, shuffled, crossfading playlist that keeps playing while they move on through veyq, and the server never
  learns that the node holds music.
- **Preconditions:** The node's `K_node` is held; to add or remove, the scope is not pending deletion and Memories is
  switched on.
- **Postconditions (success):** Each added file is a medium of the node: its original is the file, its `blob-meta` is
  typed `audio/*`, and its duration and trimmed span are sealed into its listed tier. Its Music tab lists it, and it is
  never a tile in the gallery.

## Main flow — add music

1. With the Memory or Chapter open, the user opens its **Music** tab and chooses **Add music**.
2. They pick one or more audio files.
3. On the device, each file is identified from its bytes and its length is read. Where that is cheap, the silence at
   its start and end is found (ADR 0135 decisions 3–4). A file that is too large, is no audio, or that this browser
   cannot play is refused with its own message, and nothing of it is uploaded (E1).
4. Each accepted file is uploaded as a medium through the upload queue, with its facts sealed by the same upload. It
   shows in the top bar's upload status.
5. Once it is listed, the track appears in the Music tab with its title (the file name without its extension) and its
   length.

## Main flow — listen

1. A node with music shows a **Play music** button (a play icon; a stop icon while its music plays) in its header, for
   every viewer.
2. Pressing it starts the node's tracks in core's player. The bar appears below the page's footer, naming the track and
   the node, which links back to the node's page, and the first, shuffled track fades in from where its silence ends.
3. Each track crossfades into the next over 5 s at their trimmed edges. After the last track of a round a newly
   shuffled round starts, and it never repeats the track just played.
4. In the bar the listener pauses and resumes, goes to the next or previous track, and drags the progress bar. The lock
   screen and the media keys offer the same, and so does the lightbox's top bar while it covers the page; a slideshow
   there runs on beside the music. **A video pauses the music** (ADR 0142 decision 13): while a video in the lightbox
   plays, the music is paused and shows paused; when the video pauses or ends, the lightbox moves on (by hand or the
   slideshow), or it closes, the music starts again by itself, unless the listener pressed play or pause meanwhile. A
   play pressed during the video lets both sound.
5. The listener moves on — another Memory, Settings, another space — and the music plays on. Pressing the header
   button again, or **Close** in the bar, ends it.

## Alternate / exception flows

- **A1 — Start from a track.** Pressing a track in the Music tab starts the playlist with that track, then the rest
  shuffled.
- **A2 — A Chapter.** A Chapter plays only its own tracks; with none it has no button. Music started at its Memory plays
  on while the Chapter is open.
- **A3 — Another node's music.** Starting another node's playlist replaces the one playing, with a crossfade.
- **A4 — Remove.** A track's menu in the Music tab moves it to the trash like any medium. It also offers download and
  report (ADR 0093). A playing snapshot that loses a track skips it.
- **A5 — Lock, sign-out, tenant switch, leaving a share.** A lock removes the bar and ends the music, and a sign-out or
  tenant switch loads a new page. A guest's music lasts as long as the share page.
- **A6 — Memories switched off.** No button, no tab, no bar for Memories' music.
- **A7 — Rename.** A track's menu offers **Rename** where a medium's meta may be written (a member who may write; never a
  guest). The name is sealed into the track's listed tier; an emptied name gives the file's back. A peer's rename that
  came first is shown in the dialog. The tab, the bar and the lock screen show the new name.
- **E1 — Refused file.** Over 200 MiB, not an audio container core knows, or not playable in this browser: refused
  before upload, with the reason. A file whose silence cannot be found cheaply is not refused; it plays whole.
- **E2 — Unplayable here.** A track this browser cannot play is skipped. If none plays, the bar says so and closes.
- **E3 — No Web Audio.** The tracks follow each other without a fade.

## Quality notes

- **Operator-blind:** the server sees media rows and blob sizes, as for any medium, and the times chunks are read; it
  sees no type, title, length or order, and no marker that a medium is music.
- **No third party:** nothing loads from outside the instance; nothing is asked of the Server Owner.
- **Share view = member view:** a guest hears and sees exactly what a member does; only adding and removing depend on
  rights.
- **Core and application:** the player, its order and the bar are core's; which tracks form the playlist is Memories'.
  Core is never handed a key.
- **Export, import and rotation:** a track exports as its file with its `audio` facts; an import restores them, and
  detects them where they are absent; a re-key carries them unchanged.

## User impact

A Memory sounds the way it was: its own songs play in the background for everyone who opens it, guests included, with
no account at any service and nothing leaving the instance.

## In the apps

[ADR 0142](../../../decisions/0142-the-apps-play-a-nodes-music-through-the-cores-player-each-platform-plays-bytes-and-lends-its-system-controls.md):
the apps (Android, iOS, macOS, Windows, Linux) follow the flows above, with these differences.

- **Where:** the Music tab is a view switch inside the Memory's page (**Photos** | **Music**); the header's **Play
  music**/**Stop music** sits among the page's header actions (an icon where it fits, else the header's menu); **Add
  music** is the Music view's own creating action. A track's menu offers rename, download, remove and report (as an
  `ITEM`, with no "only its name" choice); a remove offers **Undo** like any medium's.
- **Add (step 3):** the length is read by the platform's own reader of the picked file (a raw ADTS file's by counting
  its frames), and **the silence is found only in a WAV**: every decoded format added from an app is sealed untrimmed and
  keeps its silent edges wherever it plays (inbox #932). A file the device cannot read is refused with "can't be played
  on this device".
- **Listen:** the bar sits above the application's bar on a phone and along the window's bottom on a desktop; the
  lightbox's compact controls sit in its top bar. **On the phones the music plays on in the background and with the
  screen off**, with the notification, lock screen and headset buttons (Android: a media foreground service; iOS: a
  background audio session); Android pauses when the headphones go. The desktops lend their system media controls (Now
  Playing on a Mac, the SMTC flyout and media keys on Windows, MPRIS on Linux). A lightbox video pauses the music as
  step 4 says (decision 13).
- **E2 on a device:** a track the device's player cannot decode is skipped and marked "Doesn't play on this device" in
  the Music view for the rest of the session; measured (ADR 0142's Consequences): Android's emulator does not play an
  ALAC M4A, Windows without the Web Media extension no Ogg, Linux's Flatpak and the Mac play all ten formats tried.
- **Offline:** a Memory made available offline keeps its tracks whole and plays them without a connection; its Music
  view is then read-only (no add, rename, remove or report).
- **Not in the apps:** a guest's listening (the apps open a gallery as a member only, inbox #435).

## Related

- Feature: [Memory](../_index.md)
- Decision: [ADR 0135](../../../decisions/0135-background-music-is-a-nodes-own-audio-media-played-by-cores-player.md),
  the apps and a video's pause of the music: [ADR 0142](../../../decisions/0142-the-apps-play-a-nodes-music-through-the-cores-player-each-platform-plays-bytes-and-lends-its-system-controls.md),
  the medium pattern of [ADR 0130](../../../decisions/0130-a-gpx-track-is-a-medium-of-its-memory-drawn-on-its-map.md)
- Enablers: [en.platform.audio-player](../../platform/enablers/en.platform.audio-player.md),
  [en.memory.media](../enablers/en.memory.media.md)
- Use-cases: [uc.memory.tracks](uc.memory.tracks.md) (the same "a medium that is no photo" pattern)
- End-user view (docs site): [Add background music](../../../../site/src/content/docs/memories/add-background-music.mdx)
