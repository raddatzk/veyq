# ADR 0131 — A Memory's background music plays through the vendor's SDK in a frame of its own origin

- Status: Accepted
- Built: yes
- Date: 2026-11-12
- Model: [uc.memory.music](../features/memory/use-cases/uc.memory.music.md) · [security-model](../crosscutting/security-model.md) § third-party services · [cc.security.csp](../crosscutting/cc.security.csp.md) § The music frame

## Context

The owner (2026-11-12): a Memory holds Spotify and Apple Music playlists, and a viewer who signs in with their own
account hears one as background music while viewing the Memory. This replaces inbox #36's earlier default (an uploaded
audio file), which stays a residual.

Three things are fixed before any choice: the playlist is **content**, so the server never learns which one; **nothing
third-party loads before the viewer opts in**, per browser and withdrawable, as the map does
([ADR 0016](0016-geocoding-provider-neutrality.md), [ADR 0018](0018-map-tiles-provider-neutrality.md)); and a vendor's
tokens **never reach the veyq server**.

The fourth is where the work was. The app origin holds the viewer's unlocked keys in memory. A vendor script running in
it — the Spotify Web Playback SDK, Apple's MusicKit JS — can read every key and every plaintext the viewer can open, and a
compromise of the vendor's CDN becomes one of veyq ([ADR 0026](0026-client-trust-tiers.md)). The owner allowed the SDKs
(full playback, a real sign-in) and asked that they run isolated from the app origin wherever that keeps the same
function; where it cannot, the owner's answer was to load the SDK into the app origin after the opt-in, with the risk
stated. So the question was measured, not assumed.

**Measured** (2026-11-12, headless Google Chrome 157 through the workspace's Playwright, a page served with
`Cross-Origin-Opener-Policy: same-origin` framing test pages; the probe scripts are in the CR's history, not kept):

- **An opaque origin cannot hold either SDK.** In a frame sandboxed without `allow-same-origin` (by attribute or by a
  CSP `sandbox` header) `localStorage`, `sessionStorage`, `indexedDB` and `document.cookie` all throw `SecurityError`, and
  `requestMediaKeySystemAccess` is refused by the permissions policy unless the parent allows `encrypted-media` to every
  origin (`*`); even then a frame nested in the sandboxed one is opaque too and refused. The Spotify SDK plays in exactly
  such a nested frame: `spotify-player.js` v1.10.0 creates a hidden iframe of `https://sdk.scdn.co/embedded/index.html`
  with `allow="encrypted-media; autoplay"` and speaks to it by `postMessage` (read in the script's source). MusicKit JS v3
  calls `requestMediaKeySystemAccess` and `window.open` in its own document and keeps its sign-in in `localStorage`
  (read in its source; it tolerates missing storage, not a refused EME).
- **A frame of a real other origin has everything.** Cross-origin, with `allow="encrypted-media; autoplay"` and the
  parent's `Permissions-Policy` naming that origin, the frame and a frame nested in it get storage and EME (ClearKey; this
  Chrome build ships no Widevine CDM headless, so Widevine itself is unmeasured).
- **A sign-in popup the frame opens under the app's `same-origin` COOP has no opener** (`window.open` returns null, the
  popup's `window.opener` is null), but a `BroadcastChannel` and a `storage` event of the frame's origin reach the frame
  from the popup — same-site and cross-site alike in this Chrome. With the app's COOP at `same-origin-allow-popups` the
  popup keeps its opener and `postMessage` to it arrives. Spotify's authorize and login pages and Apple's
  `authorize.music.apple.com` send no COOP of their own (`curl -D -`).

What was **not** measured, because it needs real accounts: a Spotify or Apple sign-in and DRM playback end to end. That
pass is owed (inbox #846); it is where this ADR's isolation would meet a vendor-side refusal if there is one.

The rest is **remembered or read from the vendors' pages** (2026-11-12): the Web Playback SDK needs a Spotify Premium
account, a client ID and an access token; an app in Spotify's Development Mode is limited to five allowlisted users and
its owner needs Premium (Spotify's developer blog, 2026-02-06), and Extended Quota is open only to organisations with
250k monthly users — out of reach for a self-hoster. MusicKit JS needs a developer token, an ES256 JWT signed with a
MusicKit key from a paid Apple Developer Program membership, valid at most six months, and its licence forbids re-hosting
the script. The vendors' plain embeds (`open.spotify.com/embed`, `embed.music.apple.com`) need no registration but play
only previews unless the browser lets the embed read the vendor's own sign-in cookie, which Safari and Firefox do not
across sites, and they have no documented control from the page.

## Decision

1. **A playlist reference is sealed content.** A Memory or a chapter keeps at most **one playlist per service** —
   Spotify and Apple Music — in its `wrappedMeta` as `music: [{ service: 'spotify', id }, { service: 'apple-music', id,
   storefront }]` ([en.memory.node-meta](../features/memory/enablers/en.memory.node-meta.md)). The server sees ciphertext.
   It is set in the node's ⋯ menu (**Background music**, `editMeta`), by pasting the playlist's share link; a link that is
   not a playlist of either service is refused in the dialog. Re-keyed with the rest of the meta
   ([ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md)); exported and imported as the node's `music`
   ([ADR 0082](0082-a-node-and-everything-under-it-exports-as-a-plaintext-zip-and-imports-under-the-importer.md)). A chapter
   plays its own playlist, never its Memory's — inheriting would need the parent's key in the chapter's view (inbox #847).
2. **Playback runs in the music frame, a page of its own origin.** The Server Owner gives the instance a **music host**
   (`MUSIC_DOMAIN` on Compose, `music.domain` in the chart); the backend serves one page there, `/music-frame`, and the
   assets it loads, and answers nothing else on that host (no API, no app). The app embeds it as an iframe with
   `allow="encrypted-media; autoplay"` and speaks to it by `postMessage` only: it hands the frame the service's public
   configuration and the one playlist to play, and the frame answers with its state. **No key, no node, no other content
   ever crosses.** The vendor's SDK loads only inside the frame, only for the service being played. The app origin never
   runs vendor code — the owner's fallback (the SDK in the app origin) is not built, because the isolation keeps every
   function the primitives can show; if the owed pass shows a vendor refusing it, that is the owner's question again.
   - **Same site is the recommended host** — a sibling of the instance's domain (`music.example.org` beside
     `veyq.example.org`). Firefox and Safari partition a cross-site frame's storage, which would cut the frame off from its
     own sign-in popup. A tenant on a domain of another site still plays where the browser does not partition.
   - **Same site does not weaken the isolation:** sessions are bearer tokens the app origin holds, not cookies
     ([cc.security.authorization](../crosscutting/cc.security.authorization.md)), so a request from the music origin
     carries no credential, and the music host answers no API.
3. **The viewer signs in inside the frame, with the vendor, and the tokens stay in the frame.** Spotify: the
   Authorization Code flow with PKCE, the frame's own popup, the redirect URI `https://<music host>/music-frame/spotify-callback`
   registered in the Server Owner's Spotify app; the callback page hands code and state to the frame over a
   `BroadcastChannel` (and `postMessage` to an opener where there is one), and the frame exchanges the code at Spotify with
   the verifier only it holds. The access token lives in the frame's memory for the page's life: a viewer signs in again
   per visit, a click when the vendor still knows them. Apple: MusicKit's own `authorize()` popup, whose answer reaches its
   opener — so **while the music frame is served, the app's COOP is `same-origin-allow-popups`**, not `same-origin`: pages
   that open veyq still get no handle on it, only popups opened from it keep theirs. MusicKit keeps its own sign-in in the
   music origin's storage, as it does on any page. The server is never in either flow: the authorization code passes the
   music host's URL once, and is worthless without the verifier.
4. **The configuration is optional and per instance.** A Spotify **client ID** (`MUSIC_SPOTIFY_CLIENT_ID`) and an Apple
   **developer token** (`MUSIC_APPLE_DEVELOPER_TOKEN`), each public by nature (every viewer's browser receives it). The
   capability register gains `memories.music` and its children `memories.music.spotify` and `memories.music.apple-music`,
   of a new source **`deployment`**: active only while the deployment configures them — the music host and, for a child,
   its credential; an Apple token past its `exp` counts as unconfigured — and then switchable off by the Server Owner and a
   tenant owner like any entry ([ADR 0088](0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md)).
   `memories.music` carries the frame's address (`serviceUrl`), the children their credential. **An instance without it
   works fully:** playlists are still stored, shown and opened in the service's own app or site by a plain link.
5. **The opt-in is the viewer's, per browser and per service, given and withdrawn at the music bar.** A Memory with a
   playlist shows a bar above its tabs. Without consent it says which service would load and what it learns, and offers
   **Play here** and **Open in Spotify / Apple Music** (a link the viewer follows, which loads nothing into veyq). The
   consent records the music host and the service; it holds only while the host is the one served now, as the map's names
   its hosts. With consent the frame loads; the bar's **Turn music off** withdraws it at once: the frame is told to forget
   the vendor's sign-in, then removed. A share guest gets exactly this (share view = member view); a read-only right only
   removes the edit. There is no Settings entry: the consent is given only at the bar, so it is withdrawn there too.
6. **No autoplay.** Browsers refuse sound without a gesture, and a gesture in the app does not reach a frame's popup: the
   viewer presses the frame's sign-in and play. Play, pause and next sit in the frame, which is part of the Memory's page.
7. **A viewer without the right account sees why.** A Spotify account without Premium, or an Apple ID without a
   subscription, gets the vendor's refusal in plain words and the link to open the playlist in the service's app. No
   preview is played — previews are an embed's feature, not the SDKs'.

## Consequences

- **Policies.** While `memories.music` is active at the instance level the app's CSP gains `frame-src <music host>`, its
  `Permissions-Policy` allows `encrypted-media` and `autoplay` to that origin, and its COOP becomes
  `same-origin-allow-popups`; off, all three are as before. The music host's own page carries a policy of its own: scripts
  from itself, `sdk.scdn.co` and `js-cdn.music.apple.com`; `frame-ancestors` the instance's domain and its tenants'; no
  Trusted Types (MusicKit writes `innerHTML`). [cc.security.csp](../crosscutting/cc.security.csp.md) holds the values.
- **What the vendor learns** — the viewer's address, their account with the vendor, the playlist and what they play — is
  stated at the opt-in, in the privacy notice and in the security model. veyq's server learns nothing new: not the
  playlist, not whether a viewer played it.
- **Residuals.** A viewer signed in to both services in one browser has both vendors' scripts on the one music origin at
  different times, so either could read the sign-in MusicKit keeps there. Same-site origins may share a renderer process
  in some browsers, which leaves the isolation to the browser's process model against a side channel (Spectre), never to
  a readable key. Spotify's Development Mode caps an instance at five allowlisted listeners, each added by the Server
  Owner by e-mail, and the owner of the Spotify app needs Premium; Apple's membership costs a yearly fee and the token is
  re-minted at least every six months — the Run docs say so.
- **Not built:** the uploaded audio file (inbox #36's residual), a chapter inheriting its Memory's playlist (#847),
  music that keeps playing across pages (#848).

Published for readers outside the project in [Add background music](../../site/src/content/docs/memories/add-background-music.mdx),
[Capabilities § Background music](../../site/src/content/docs/run/capabilities.mdx) and
[Configuration](../../site/src/content/docs/run/configuration.md).
