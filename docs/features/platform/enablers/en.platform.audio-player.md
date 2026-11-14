# Enabler: Core's audio player — one shuffled, crossfading queue that plays on across pages

- **ID:** en.platform.audio-player
- **Type:** **Enabler** — structural foundation, no standalone end-user goal
  ([features/README → use-case vs enabler vs ADR vs change-request](../../README.md#use-case-vs-enabler-vs-adr-vs-change-request)).
- **Status:** **built** — decided in [ADR 0135](../../../decisions/0135-background-music-is-a-nodes-own-audio-media-played-by-cores-player.md)
  decisions 6–8: `src/web/src/core/audio/` (`order.ts`, `fades.ts`, `player.ts`, `AudioBar.vue`), mounted by
  `AppShell.vue` and `ShareView.vue`.
- **Provides:** to every application, one player in the web shell. The application hands it a queue of tracks, and
  core plays it: in shuffled order, endlessly, crossfading, with a bar below the shell's footer that stays while the
  viewer moves between pages and spaces.
- **Serves (use-cases):** [uc.memory.background-music](../../memory/use-cases/uc.memory.background-music.md). A
  later audio application would hand over its queue the same way.
- **Depends on:** core's playback route (`sw/`, `registerPlayback`: the service worker answers an element's `Range`
  requests from chunks the page decrypts, ADR 0047, inbox #412). An application may use core, never the reverse
  ([ADR 0079](../../../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md)).

## What an application hands over

A queue: an ordered list of tracks and, optionally, the one to start with. Each track has:

| Field | Meaning |
|---|---|
| `id` | Stable within the queue; the order and the history refer to it. |
| `title` | Shown in the bar and handed to the MediaSession. |
| `startMs`, `endMs` | The trimmed span: what plays, what the progress bar spans, where the fades sit. |
| `open()` | Resolves to a playable source (a stream handle, or a whole-file `blob:`) and a `release()`. |

**Core never holds a key.** `open()` is the application's: it registers the playback with the chunk provider that
holds the keys, exactly as the lightbox does, and core plays the URL it gets. `release()` revokes the handle.

## What core does with it

- **Order:** shuffled, endlessly (ADR 0135 decision 6). Each round is a fresh permutation, and its first track is never
  the last one played. Next goes forward. Previous restarts the current track when it is more than 3 s in, and
  otherwise goes back through the tracks played. A start track plays first.
- **Crossfade:** over 5 s at the trimmed edges, 1 s when next or previous is pressed, and shorter for short tracks or
  after a seek into the fade window (decision 7). Two media elements alternate. Each element runs through its own
  `MediaElementAudioSourceNode` and `GainNode` in one `AudioContext`, which the listener's first press resumes. Without
  Web Audio, tracks change without a fade.
- **Opening tracks:** a track is opened and loaded 15 s before its fade starts and released after it has faded out, so
  at most two sources are open. A track that fails to open or to play is skipped. If no track plays, the queue ends and
  the bar says so for a few seconds. A source the service worker streams is handed to the element only once the worker
  confirmed its registration (protocol 6, [en.memory.media](../../memory/enablers/en.memory.media.md)): a fetch that
  reached the worker first was a `404`, which ended a playlist at its third track.
- **In the press's own stack:** `play` and `resume` create or resume the `AudioContext` and start each element on a
  silent `blob:` before anything is awaited, so iOS lets the later `play()` of a source that opened after an `await`
  sound. Measured in Chrome: an `AnalyserNode` after an element's gain reads non-zero samples on a `/__media/` stream
  and on a `blob:`, so neither is tainted (Safari is not measured).
- **The bar:** shows the title, previous, play/pause, next, a progress bar over the trimmed span that the listener can
  drag, and close. It is drawn below the shell's footer and only while a queue is loaded. Close ends the queue.
- **MediaSession:** the title, and play, pause, next, previous and seek, so the lock screen and the media keys work.
- **One queue at a time:** a new queue replaces the one playing, crossfading into it.

## Lifetime

| Where | Lives as long as | Ended by |
|---|---|---|
| The member's shell | the session is signed in and unlocked | a lock (unmounts, releases every source); a sign-out or tenant switch (a new document) |
| The share page | the page | leaving the page |

Every source is released when the player unmounts. A sign-out also ends every playback in the service worker
(`veyq-end-session`), as it does today.

## Not here

- **Which tracks form a queue:** the application decides.
- **Depot's file viewer:** keeps its own inline `<audio>`.
- **A volume control:** the device has one.

## Related

- Decision: [ADR 0135](../../../decisions/0135-background-music-is-a-nodes-own-audio-media-played-by-cores-player.md)
- Use-case: [uc.memory.background-music](../../memory/use-cases/uc.memory.background-music.md)
- Feature: [Platform](../_index.md)
- End-user view (docs site): [Add background music](../../../../site/src/content/docs/memories/add-background-music.mdx)
