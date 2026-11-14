# ADR 0135 — Background music is a node's own audio media, played by core's player

- Status: Accepted
- Built: no
- Date: 2026-11-14
- Model: [uc.memory.background-music](../features/memory/use-cases/uc.memory.background-music.md) (the player:
  [en.platform.audio-player](../features/platform/enablers/en.platform.audio-player.md))

## Context

[ADR 0131](0131-a-memorys-background-music-plays-through-the-vendors-sdk-in-a-frame-of-its-own-origin.md) built
background music on Spotify and Apple Music playlists: a vendor SDK in a frame on a music host of its own origin. It
shipped dark, and before it was switched on the owner weighed what it needs (2026-11-14):

- **Spotify:** an app in Development Mode lets **at most five people** authorize it, and its owner needs Premium
  (Spotify's developer blog, 2026-02-06; the migration guide). Extended Quota needs a registered business and 250k
  monthly users, which no self-hoster has. The limit counts the people who authorize the Client ID, so keeping a
  listener's token sealed under their own key, or anywhere else, does not lift it. An unofficial client like Spotifast
  avoids it only by having every listener register an app of their own.
- **Apple Music:** a paid developer membership, a token re-signed every six months, and a subscription per listener.
- **Both:** a music host, a widened CSP, a different COOP, a consent per browser and service, a privacy-notice passage,
  the vendors' logo files, and a live pass with real accounts still owed. A guest of a link almost never has the
  account the service needs.

The owner (2026-11-14): Spotify is the largest service, and if it is this impractical, then no streaming at all, only
the members' own audio files: any number per Memory and per Chapter, forming a playlist that is always shuffled, loops
forever and crossfades. The client offers play, pause, next, previous and a seekable progress bar. Whether the uploader
holds the rights is not veyq's question (owner 2026-11-14). A track stays reportable like any medium.

What exists to build on, read on `main` at `8574dda3b`:

- [ADR 0130](0130-a-gpx-track-is-a-medium-of-its-memory-drawn-on-its-map.md) did the storage half for GPX tracks:
  a plain medium, recognised on the client by its sealed `blob-meta` type and filtered from every photo view; labels
  sealed into the listed tier at birth; an additive export field; reported as an `ITEM`; a tab of its own.
- Core already streams a sealed blob into an `<audio>` element. The service worker holds no key: it answers the
  element's `Range` requests with `206` from plaintext chunks that the registering page decrypts (`sw/`,
  `registerPlayback`, inbox #412). Depot plays audio this way. The URL is same-origin (`/__media/…`), and the app's CSP
  already allows `media-src 'self' blob:` and `autoplay=(self)`.
- Core's `sniffStream` tells the audio containers apart (MP3, M4A/AAC, Ogg, WAV, FLAC, ADTS). Memories' upload filter
  drops audio silently, and its `sniffMediaType` reads every `ftyp` brand as `video/mp4`, an M4A included.

## Decision

1. **No streaming service.** Spotify and Apple Music are removed, along with everything that served only them: the
   music frame and host, the vendor configuration, the `deployment` capability source and the three `memories.music`
   entries, the widened CSP / Permissions-Policy / COOP, the privacy-notice passage, the playlists in a node's meta and
   in the export, and the release flag. ADR 0131 is superseded. Rejected alternatives:
   - **One Client ID per listener** (Spotifast's way). It lifts the limit, but every listener has to work through
     Spotify's developer dashboard once, and a link's guest never will.
   - **The vendors' embeds.** They need no registration, but in Safari and Firefox they play only previews (no
     cross-site cookie), they look like the vendor's player, and the page cannot control them.
   - **Apple Music alone.** It keeps all of the frame machinery for one vendor, with a fee and a token renewal for the
     Server Owner and a subscription for every listener.
   - **The SDK in the app origin.** It puts vendor code beside the unlocked keys
     ([ADR 0026](0026-client-trust-tiers.md)).
2. **A music track is a medium of its Memory or Chapter**, following ADR 0130's pattern.
   - **Recognised:** by its sealed `blob-meta` type `audio/*`. The server keeps no role, column or route that marks
     it: that would be a tell, as ADR 0130 found.
   - **Kept out of photo views:** every reader that treats a medium as a photo filters it (the gallery, the lightbox,
     search, the cover choice, the space's media list), as tracks are filtered.
   - **Inherited for free:** trash, quota, buckets, the guest-write record, export, re-key and reporting.
   - **Not a node attachment:** an attachment would need numbered slots, and the node's meta is near its cap (ADR 0130,
     rejected alternatives).
   - **Added from the node's Music tab only.** The gallery's picker keeps refusing audio. There is no count limit; the
     quota bounds it, and the client refuses a file over **200 MiB**.
   - **Who adds one:** whoever may upload media to that node — a member, or a guest whose link may upload. Share
     view = member view.
3. **The sealed type comes from the bytes.** It is taken from core's `sniffStream`, not from the browser's MIME
   guess, and never `video/mp4` for an M4A. A file whose container is not one of those audio types, or that the
   uploading browser cannot decode, is refused before anything is written: its facts (decision 4) could not be
   measured. At playback, a track the listening browser cannot play is skipped.
4. **Its facts are sealed into the listed tier at birth.** They are the duration and the trimmed span (`trimStartMs`,
   `trimEndMs`), beside the file's name in `blob-meta`.
   - **How the span is found:** at upload the client decodes the file in an `OfflineAudioContext` at a low sample rate
     and scans 20 ms windows from both ends. Silence is any window whose peak stays below **−60 dBFS**. A trim that
     would leave less than one second keeps the whole file.
   - **Why at upload:** detecting at playback would decode every track on every device.
   - **Where it lives:** in the listed tier, so a re-key carries it unchanged (ADR 0109).
   - **Export:** an additive `audio` field on the medium. An import restores it, and runs the detection where the
     field is absent.
   - **The title** is the file's name without its extension. Reading ID3 or MP4 tags is left out: it would need a
     parser dependency for a nicety.
5. **The playlist is the node's own music, nothing inherited.** A Chapter with no tracks has no playlist. Music started
   at its Memory plays on while the viewer opens the Chapter. Inheriting can come later (inbox #847). What plays is a
   snapshot taken when the playlist starts; starting it again picks up tracks added since.
6. **The order is always shuffled and endless; there is no toggle.**
   - **Each round:** a fresh permutation (Fisher–Yates over `crypto.getRandomValues`). With two or more tracks, its
     first is never the last one played.
   - **Next:** goes forward in the permutation.
   - **Previous:** restarts the current track when it is more than 3 s in; otherwise it goes to the track played
     before it.
   - **Starting from a track:** pressing a track in the Music tab plays it first, then shuffles the rest.
7. **Tracks crossfade over 5 s, at their trimmed edges.**
   - **Shorter fades:** a hand-pressed next or previous fades over 1 s. A track shorter than two fades fades over a
     third of its trimmed length. A seek into the fade window shortens the fade to what is left.
   - **How:** volume runs through Web Audio: one `AudioContext`, resumed by the listener's press, with one
     `MediaElementAudioSourceNode` and one `GainNode` per element. iOS keeps `HTMLMediaElement.volume` read-only, so a
     volume fade on the element would not fade there. Where Web Audio is missing or refuses the element, tracks
     change without a fade.
8. **The player belongs to core** ([en.platform.audio-player](../features/platform/enablers/en.platform.audio-player.md)).
   - **What the application hands over:** a queue of tracks, each with its id, title, trimmed span and a way to open a
     playable source (the stream handle `registerPlayback` returns, or a whole-file `blob:` where no service worker
     controls the page).
   - **What core owns:** the order, the crossfade, the bar, and the MediaSession metadata and actions (lock screen and
     media keys).
   - **The keys stay with the application.** Core's player receives URLs, never a key.
   - **The bar:** core draws it below the shell's footer. It shows the title, previous, play/pause, next, a progress bar
     over the trimmed span that the listener can drag, and close. It is drawn only while a queue is loaded.
   - **One player:** a new queue replaces the one playing, with a crossfade.
   - **Lifetime:** the member's shell keeps it while the session is signed in and unlocked. A lock unmounts it and
     revokes its playback handles; a sign-out and a tenant switch load a new document. The share page keeps it as long
     as the page.
   - **Retired seams:** the registry's `app.footerBar` slot and the `onSessionEnding` hook had music as their only
     user, and go with it.
   - **Depot is unchanged:** its file viewer keeps its own inline `<audio>`. It shows one file and is not background
     music.
9. **Where it starts.**
   - **The header:** a node with music shows one header button, `music`, that toggles its playlist.
   - **The Music tab:** sits beside the map tab and lists the node's tracks with title and length. A track's menu
     offers download, delete (to the trash) and report.
   - **When the tab shows:** where the node has music, or where the viewer may add some.
10. **A reported track can be heard.** The moderator's review of a medium plays an audio track from the report's blob
    keys, without its trim (the listed tier is not in the report, ADR 0093 § 1). A track has to be heard to be judged;
    a GPX track only needs to be looked at.

## Consequences

- **What the server learns** is what any medium tells it: a blob's size, the medium's row, and the times its chunks
  are read. Playing music shows up as repeated reads of one node's blobs, as watching its videos does. The server sees
  no type, title, duration or order.
- **Nothing third-party loads.** Nothing is asked of the Server Owner, and the instance's policies are as before ADR 0131.
- **A guest of a link hears the music exactly as a member does.**
- **Residuals:**
  - A phone may suspend a hidden tab, and with it the page that decrypts the chunks, so background playback with the
    screen off is up to the browser.
  - Decoding a long file at upload costs memory on a phone. The 200 MiB cap and the low sample rate are the bounds, and
    the build measures them.
  - Leftovers stay in place and nothing reads them: playlists sealed into a node's meta while the flag was on, a
    `memories.music…` switch row, the browser's `veyq:music-consent` key. The switch rows are filtered out by
    `CapabilityIds.ADVERTISED`.
- **Not built:** a Chapter inheriting its Memory's music (inbox #847), and reading the title from tags.
