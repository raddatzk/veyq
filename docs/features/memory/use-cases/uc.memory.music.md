# UC: Background music — a Memory's playlist, played from the viewer's own Spotify or Apple Music account

- **ID:** uc.memory.music
- **Status:** **built** ([ADR 0131](../../../decisions/0131-a-memorys-background-music-plays-through-the-vendors-sdk-in-a-frame-of-its-own-origin.md)).
  A sign-in and playback with real vendor accounts is owed (inbox #846); lane L1's
  `tests/e2e/tests/features/memories/memory-music.spec.ts` covers setting a playlist and the opt-in, never the vendor.
- **Primary actor:** A member who may write the node's meta (`memoryRights.editMeta`) sets the playlists. **Secondary:**
  anyone who opens the Memory or chapter — a member, or a guest of any link role — listens.
- **Goal:** A Memory carries one Spotify and one Apple Music playlist; a viewer who opts in and signs in with their own
  account hears it while viewing, and the server never learns which playlist it is.
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
2. Any viewer of the node sees the **music bar** above the tabs, naming each service the node has a playlist for.
3. Where the service plays here and this browser has not opted in, the bar says that playing loads the service's player,
   which learns the viewer's address, their account with the service and the playlist, and offers **Play here** and
   **Open in Spotify / Apple Music**.
4. **Play here** records this browser's consent for that service and the music host, and the bar mounts the music frame
   (ADR 0131 decision 2), which loads only that service's player.
5. In the frame the viewer signs in with the service (its own popup) and presses play; play, pause and next stay in the
   frame. The music stops when the viewer leaves the page.

## Alternate / exception flows

- **A1 — Withdraw.** **Turn music off** on the bar removes the consent for that service at once, tells the frame to forget
  the sign-in, and removes the frame; nothing loads from the service again until the viewer opts in again. Another tab of
  the browser follows.
- **A2 — A new music host.** A consent names the host it was given for; when the server serves another, the bar asks again.
- **A3 — Not offered.** Where `memories.music` or the service's child is off — unconfigured, switched off by the Server
  Owner or by the tenant owner, or an Apple token past its expiry — the bar offers only the link that opens the playlist in
  the service's own app or site. Nothing is loaded into veyq.
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
- **Share view = member view:** a guest sees the bar and the same choices; the edit is a member's.
- **Export and rotation:** `music` is exported and imported with the node, and re-keyed with the rest of the meta.
