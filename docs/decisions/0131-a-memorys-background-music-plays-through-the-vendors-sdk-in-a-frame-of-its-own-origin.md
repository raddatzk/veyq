# ADR 0131 — A Memory's background music plays through the vendor's SDK in a frame of its own origin

- Status: Accepted
- Built: yes
- Date: 2026-11-12
- Model: [uc.memory.music](../features/memory/use-cases/uc.memory.music.md) · [security-model](../crosscutting/security-model.md) § third-party services · [cc.security.csp](../crosscutting/cc.security.csp.md) § The music frame

## Context

The owner (2026-11-12): a Memory holds Spotify and Apple Music playlists, and a viewer who signs in with their own
account hears one as background music while viewing the Memory. This replaces inbox #36's earlier default (an uploaded
audio file), which stays a residual. The owner's layout of 2026-11-13 moved the player: no longer a bar above the
Memory's tabs that stopped when the viewer left the page, but **a bar at the very bottom of every page, below the footer,
in veyq's own look, that keeps playing across pages, started by a button in the Memory's header** (decisions 5 and 6).

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
   configuration, the one playlist to play and the app's theme, and the frame answers with its state. **No key, no node, no other content
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
5. **Started from the Memory's header, played in the shell's music bar, which plays on across pages.** A Memory or a
   chapter with a playlist shows one header button per service (`music-spotify`, `music-apple-music`, after Share in the
   kind's header order), for every viewer of its page. Where this server plays the service here it is a toggle that starts
   the playlist in the **music bar**; pressed while that playlist is the bar's, it ends it. Where it does not (no music host,
   the service unconfigured or switched off, an Apple token past its expiry), the button is **Open in Spotify / Apple
   Music**: a link the viewer follows, which loads nothing into veyq - that link lives in the header now, the bar is never
   drawn for such a service. Setting the playlists stays the ⋯ menu's **Background music** (`editMeta`).
   - **The bar belongs to the shell, not to the page.** The registry gives an application one place in the shell, the
     **footer bar** (`app.footerBar`, core's `core/footerBar/FooterBars.vue`), which core mounts once per shell below its
     footer - in the page's flow, sticky at the viewport's bottom edge - and Memories fills with its music bar. Core imports
     no application for it (ADR 0079; the web core-import guard). Because the shell owns it, the frame survives every change
     of page: the music plays on while the viewer moves through veyq, across spaces too - what the bar holds is one playlist
     reference already handed to the frame, never a key or a node.
   - **Another Memory's playlist** changes nothing until the viewer presses its button: then a playlist of the same service
     goes to the same frame, which switches and keeps the vendor's sign-in; another service's gets a new frame (one vendor
     per frame's life).
   - **Teardown:** the signed-in app mounts the bar only while the session is signed in and unlocked, so a **sign-out** or
     a **lock** unmounts it and ends the music; a **tenant switch** is a full navigation to the other tenant's domain and
     ends it with the page. Memories switched off here gets no bar.
   - **A sign-out forgets the vendor's sign-in; a lock does not** (owner 2026-11-13, inbox #858): on a shared computer
     the next person's start of a service must not be signed in as the one who signed out. Every session ending - this
     tab's sign-out (the locked screen's included), a refresh the server refused, an account erasure, another tab's
     announcement - first runs the applications' ending steps (an application's lifecycle hook `onSessionEnding`, run
     by `app/sessionEnd.ts` through `app/sessionEndingSteps.ts`, all together bounded at 2 s, a failing step ignored),
     and only then loads the sign-in page. Memories' step (`features/memories/music/musicEnding.ts`):
     - **a frame is mounted:** the bar tells it `forget` and waits for `forgotten`, at most 1.5 s - the wait of **Turn
       music off**, and a sign-out right after Turn music off joins that forget rather than sending another;
     - **none is** (the bar was closed, or nothing played on this page): only Apple Music's sign-in outlives its frame
       (MusicKit keeps it in the music origin's storage; Spotify's token lives in the frame's memory), so only a browser
       whose consent covers Apple Music at the music host served now, and only in the tab that started the ending, gets
       a **hidden frame** of the music host mounted to forget: handed no playlist and no credential, it loads no vendor
       script, and on `forget` clears its origin's local and session storage - the music origin keeps nothing of its own
       there, so everything in it is a vendor's. Every `forget` clears that storage, behind a player's own sign-out too.
     The **consent stays** - the owner's decision (2026-11-13: a sign-out does *not* withdraw the music consent): it is
     this browser's, not the account's, and only Turn music off withdraws it. A **lock** keeps the session and
     the person - it is reached by a new document (a reload) or a rotation whose outcome is unknown, which leave no frame
     behind to ask, and the locked screen's own sign-out is a sign-out - so it forgets nothing. A tenant switch forgets
     nothing either: the same person, signed in.
   - **The share page** mounts the same footer bar below its own footer, for a guest exactly as for a member (share view =
     member view): it plays on while the guest moves through the link's nodes and ends when the guest leaves the page.

   **The opt-in is the viewer's, per browser and per service, given and withdrawn at the music bar.** The first start of a
   service in a browser opens the bar on its opt-in: which service would load, from which music host, and what it learns,
   with **Play here**, the link into the service and **Close**. The consent records the music host and the service; it holds
   only while the host is the one served now, as the map's names its hosts. With consent the frame loads at once. The bar's
   **Turn music off** withdraws it: the frame is told to forget the vendor's sign-in and is kept until it says so (at most a
   moment), then removed; **Close** ends the music and keeps the consent. There is no Settings entry: the consent is given
   only at the bar, so it is withdrawn there too.
6. **The player is veyq's, drawn by the frame; no autoplay.** The SDKs are headless, so the frame draws its own player in
   veyq's look, not a vendor's: the service's sign-in, previous / play-pause / next, and what plays - its cover, title and
   artist - with the link back into the vendor's app.
   - **The look is veyq's tokens.** The frame shares no stylesheet with the app at run time (another origin), so the build
     takes the app's own token sheet (`src/web/src/styles/base.css`, the `--app-*` roles light and dark) into the frame's
     sheet - one source, no copy that can drift - and the app sends its **resolved theme** (`light` | `dark`) with `load`
     and in a `theme` message when it changes: a viewer's chosen theme can differ from the system's, which is all the frame
     could read by itself. The frame takes exactly `dark` or else `light` and only toggles the `veyq-dark` class with it; the
     accent is a token, not a message. Nothing else of the look crosses.
   - **What plays stays in the frame:** the title, the artist and the cover are read from the SDK and drawn there (as text,
     a cover only from an https address); the app hears only the state.
   - **The vendors' attribution, the minimum they require** (read 2026-11-13; *read*, not measured, and not a legal review):
     Spotify's Developer Terms § III.1.1 bind an app to its Branding/Design Guidelines
     (`developer.spotify.com/terms`, `developer.spotify.com/documentation/design`), which require content from Spotify -
     metadata and cover art - to be attributed with Spotify's **logo** (the full logo, or the icon alone where space is tight,
     at least 21 px), a **link back** to Spotify ("Listen on Spotify" is one of its wordings), cover art uncropped, with
     nothing over it and rounded corners, the full metadata viewable where it is cut, and a control Spotify restricts
     (`disallows`) not offered. Apple's Apple Music Identity Guidelines (`marketing.services.apple/apple-music-identity-guidelines`)
     have no section on a player; the closest is the **Apple Music icon**, Apple's own file only, for linking to music
     content inside an app where the badge does not fit, always with a link to Apple Music. So the frame keeps a small mark
     inside its "Listen on …" link and nothing more of either brand: no vendor colours, no vendor player. Spotify's
     playing-view guidance also recommends a background taken from the artwork or `#191414`; that is a recommendation of
     its look, which the owner's veyq look replaces - stated, not followed.
   - **The marks are not in the repository yet.** The logo files must be the vendors' own (downloaded from their brand
     pages), so until each is added (`src/web/src/music-frame/brand.ts`) the frame shows **nothing the vendor attributes**
     for that service - no title, no artist, no cover - only its state, its controls and the link back (inbox #857). That is
     the safe side of an obligation the frame cannot meet without the file.
   - **No autoplay.** Browsers refuse sound without a gesture, and a gesture in the app does not reach a frame's popup: the
     viewer presses the frame's sign-in and play. Once the frame was clicked to play, a playlist switched to later starts at
     once (the vendor's player is already activated in it).
7. **A viewer without the right account sees why.** A Spotify account without Premium, or an Apple ID without a
   subscription, gets the vendor's refusal in plain words and the link to open the playlist in the service's app. No
   preview is played — previews are an embed's feature, not the SDKs'.
8. **Released dark — off until the owner switches it on** (owner 2026-11-13). The music landed before the owner wanted to
   release it, so it ships off on every instance, one that sets a music host included, behind ONE **temporary release
   flag** kept apart from decisions 1-7: `VEYQ_MUSIC_RELEASED=true` switches it on (Compose `MUSIC_RELEASED=true`, the
   chart's `music.released: true`), default false. Switched on, the music is exactly as decisions 1-7 build it (the
   `memories.music` entries decide, a missing music host shows the playlists as links); off, the instance is what it was
   before this ADR. The flag is read through one helper on each side and by nothing else:
   - **the backend:** `MusicDeployment.released` (`veyq.music.released`), whose one use is `MusicSettings.deployed`: off,
     none of the three music values is read (a malformed one refuses no boot), so all three entries are unconfigured -
     and with them everything decisions 2-4 hang on them: no music entry or value on the wire, the app's CSP keeps
     `frame-src 'none'`, its Permissions-Policy grants no music origin, its COOP stays `same-origin`, no host serves the
     frame, the Compose router takes no music host (`TenantIngressSinkProducer` asks `MusicFrameHost`), the privacy
     notice's passage is not rendered (its `switch` is `memories.music`, and a revision bump prompts nobody: nothing
     accepts the notice). It serves the flag to the web as the setup status's `musicReleased`; the chart renders no
     music route, listener or variable while `music.released` is false (`veyq.musicDomain`);
   - **the web:** `features/memories/music/musicRelease.ts` `musicReleased()`, called at the four surfaces: the
     header's buttons, the ⋯ menu's entry, the footer bar (`app.footerBar(MusicBar, musicReleased)`: core mounts not
     even its empty frame) and Memories' sign-out step, which returns at once - no frame asked or mounted, no timer
     (the ending's 2 s budget is started only while a step is registered and cleared the moment the steps settle).
   **Removal at release:** `git grep -iE 'music[._-]?released'` lists every site - the definition (`VeyqConfig.Music.released`,
   the setup status's field, `capabilityState.musicReleased`, `musicRelease.ts`), each helper call, the tests that assert
   the music absent while off (`MusicDarkSliceTest`, the `musicReleased off` cases, the test profile's music block and the
   web test setup's line), Compose's, the chart's and the e2e overlay's variable, and these doc lines; deleting them leaves
   the music as built (inbox #859).

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
- **Withdrawal waits for the frame.** Removing the frame in the same tick as the `forget` would destroy it before the
  message is handled, and MusicKit's sign-in in the music origin's storage would stay; the bar keeps the frame until
  `forgotten` (at most 1.5 s). A sign-out waits the same way, so it can take up to 1.5 s longer where music was played.
- **What a sign-out's forget leaves** (decision 5): the hidden frame clears only the storage of the music origin the
  browser lets this site's frame see - in a browser that partitions a frame's storage by top-level site, this site's
  partition, which is the one the bar's frame wrote. A page whose capabilities do not name the music host when the session
  ends (the music switched off since, or a page that has not read them yet) mounts no frame, and a browser closed
  without signing out forgets nothing; the next veyq sign-out in it does. That clearing the storage is what MusicKit's
  `unauthorize()` does locally is *remembered*, not measured - the owed live pass checks it.
- **Released dark** (decision 8, `musicReleased`): lane L1's overlay switches the flag on, so the browser suite sees the
  music as built; the dark form is proven by the backend's `MusicDarkSliceTest` (this suite's default profile sets a
  music host and both values but not the flag) and the web's `musicReleased off` unit cases.
- **Not built:** the uploaded audio file (inbox #36's residual), a chapter inheriting its Memory's playlist (#847), the
  vendors' logo files (#857).

Published for readers outside the project in [Add background music](../../site/src/content/docs/memories/add-background-music.mdx),
[Capabilities § Background music](../../site/src/content/docs/run/capabilities.mdx) and
[Configuration](../../site/src/content/docs/run/configuration.md).
