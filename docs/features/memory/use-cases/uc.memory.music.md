# UC: Background music — a Memory's playlist, played from the viewer's own Spotify or Apple Music account

- **ID:** uc.memory.music
- **Status:** **built** ([ADR 0131](../../../decisions/0131-a-memorys-background-music-plays-through-the-vendors-sdk-in-a-frame-of-its-own-origin.md)).
  A sign-in and playback with real vendor accounts is owed (inbox #846); lane L1's
  `tests/e2e/tests/features/memories/memory-music.spec.ts` covers setting a playlist and the header's link where the
  server plays none, never the vendor. The vendors' logo files are not added yet (inbox #857): until then the frame draws
  no title, artist or cover.
- **Primary actor:** A member who may write the node's meta (`memoryRights.editMeta`) sets the playlists. **Secondary:**
  anyone who opens the Memory or chapter — a member, or a guest of any link role — listens.
- **Goal:** A Memory carries one Spotify and one Apple Music playlist; a viewer who starts it from the Memory's header,
  opts in and signs in with their own account hears it in the music bar at the bottom of every page, while moving on
  through veyq, and the server never learns which playlist it is.
- **Preconditions:** The node's `K_node` is held and its `wrappedMeta` opens
  ([en.memory.node-meta](../enablers/en.memory.node-meta.md)). In-page playback also needs `memories.music` and the
  service's child active ([ADR 0088](../../../decisions/0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md)):
  the deployment configures the music host and the service's credential (the site's Run area, *Background music*).
- **Postconditions (success):** The node's sealed `wrappedMeta.music` lists at most one playlist per service; the viewer
  who opted in hears it in the music frame.

## Main flow

1. The member chooses **Background music** in the node's ⋯ menu. The dialog has one field per service; the member pastes
   a playlist's share link (`https://open.spotify.com/playlist/…`, `spotify:playlist:…`,
   `https://music.apple.com/<country>/playlist/<name>/pl.…`) and saves. The meta is re-sealed with `music` (read-modify-write,
   revision-checked, [ADR 0077](../../../decisions/0077-a-write-carries-the-version-it-read.md)).
2. Any viewer of the node sees one **play button per service** in the node's header (a toggle, pressed while that
   playlist plays in the bar). Pressing it starts the playlist in the **music bar**, which the shell draws below its
   footer on every page (ADR 0131 decision 5).
3. Where this browser has not opted in to the service, the bar says that playing loads the service's player from the
   music host, which learns the viewer's address, their account with the service and the playlist, and offers **Play
   here**, **Open in Spotify / Apple Music** and **Close**.
4. **Play here** records this browser's consent for that service and the music host, and the bar mounts the music frame
   (ADR 0131 decision 2), which loads only that service's player; with a standing consent the frame mounts at once.
5. In the frame - veyq's own player, in the app's theme - the viewer signs in with the service (its own popup) and presses
   play; previous, play/pause and next, what plays and the link back into the service are the frame's (decision 6).
6. The viewer moves on - another Memory, Settings, another space: the bar and its frame stay, the music plays on. Opening
   another Memory with a playlist changes nothing until its button is pressed; then a playlist of the same service
   switches in the same frame, keeping the sign-in, another service's gets a new frame (after its own opt-in).
7. **Close** (or the pressed header button) ends the music and removes the frame; the consent stays.

## Alternate / exception flows

- **A1 — Withdraw.** **Turn music off** on the bar removes the consent for that service at once, tells the frame to forget
  the sign-in, keeps it until it said so (at most a moment), then removes it and ends the music; nothing loads from the
  service again until the viewer opts in again. Another tab of the browser removes its frame at once.
- **A2 — A new music host.** A consent names the host it was given for; when the server serves another, the bar asks again.
- **A3 — Not offered.** Where `memories.music` or the service's child is off — unconfigured, switched off by the Server
  Owner or by the tenant owner, or an Apple token past its expiry — the header's button is the link that opens the playlist
  in the service's own app or site, and no bar is drawn. Nothing is loaded into veyq. A service that stops being offered
  while it plays leaves the bar.
- **A6 — Sign-out, lock, tenant switch, leaving a share.** The bar lives as long as the signed-in, unlocked session (the
  share page: as long as the page): a sign-out or a lock removes it and ends the music, a tenant switch is a navigation to
  another domain. **A sign-out also forgets the vendor's sign-in** (owner 2026-11-13; ADR 0131 decision 5), so the next
  person on a shared computer does not start the service signed in as this one: before the sign-in page loads, a mounted
  frame is told to forget and waited for (at most a moment); with none mounted, a browser that agreed to Apple Music - the
  one service whose sign-in outlives its frame - gets a hidden frame of the music host that clears the music origin's
  storage, loading nothing of the vendor. The consent stays. A lock and a tenant switch forget nothing: the same person
  is still signed in.
- **A7 — Memories switched off.** No music bar is mounted.
- **A4 — No subscription.** The frame says so in plain words (Spotify Premium; an Apple Music subscription) and keeps the
  link; nothing plays.
- **A5 — Remove.** Clearing a field and saving drops that service's playlist; clearing both drops `music`.
- **E1 — Not a playlist.** A link that is not a playlist of either service is refused in the dialog; nothing is written.
- **E2 — Conflict.** A peer's change of `music` meanwhile re-opens the dialog on it (ADR 0077); a change of another field
  merges.
- **E3 — Unreadable entries.** A stored entry that is not a valid playlist reference is skipped when read, never shown.

## Quality notes

- **Operator-blind:** the playlist is sealed content; the server sees neither it nor whether anyone played it. Vendor
  tokens live in the music frame only.
- **No vendor code in the app origin:** the SDK runs only in the music frame, whose origin holds no veyq key
  ([security-model](../../../crosscutting/security-model.md) § third-party services).
- **Share view = member view:** a guest sees the same header buttons and the same bar, below the share page's footer;
  the edit is a member's.
- **Core and application:** the bar is Memories' filling of core's one shell slot, the footer bar (`app.footerBar`); core
  imports no application for it (ADR 0079).
- **The vendors' attribution:** what plays is drawn only beside the vendor's own mark, with the link back (ADR 0131
  decision 6).
- **Export and rotation:** `music` is exported and imported with the node, and re-keyed with the rest of the meta.

## User impact

A Memory can carry the music it belongs with, and whoever opens it hears it from their own Spotify or Apple Music account,
without the server learning the playlist and without the service's code ever running where the keys are.

## Related

- Feature: [Memory](../_index.md)
- End-user view (docs site): [Add background music](../../../../site/src/content/docs/memories/add-background-music.mdx);
  for Server Owners: [Capabilities § Background music](../../../../site/src/content/docs/run/capabilities.mdx)
- Decision: [ADR 0131](../../../decisions/0131-a-memorys-background-music-plays-through-the-vendors-sdk-in-a-frame-of-its-own-origin.md)
- Enabler: [en.memory.node-meta](../enablers/en.memory.node-meta.md) (the sealed `wrappedMeta` the `music` field lives in)
- Use-cases: [uc.memory.place](uc.memory.place.md) (the map's consent, which this opt-in mirrors), [uc.memory.banner](uc.memory.banner.md)
