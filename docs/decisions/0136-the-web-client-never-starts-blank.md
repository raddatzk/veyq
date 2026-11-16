# ADR 0136 — The web client never starts blank

- Status: Accepted
- Built: no
- Date: 2026-11-16
- Model: [uc.account.remember-this-device](../features/account/use-cases/uc.account.remember-this-device.md) E5

## Context

The owner reported (2026-11-16): on iOS Safari, a veyq tab left in the background for a long time comes back as a white
page. It looks as if it reloaded for a moment; only a manual reload brings the app back.

What the code makes of that (read 2026-11-16, not reproduced on a device):

- `src/web/src/main.ts` mounts the app only after `boot()` and `router.isReady()` settle. Until then `#app` in
  `index.html` is empty: there is no first paint of any kind.
- `boot()` awaits, in turn, the setup status (a request), the session refresh (a request) and the remembered vault's
  restore (IndexedDB and WebCrypto), none with a deadline. A step that **fails** is handled: the shell shows "This server
  can't be reached right now". A step that **never settles** leaves the page empty for good.
- `void bootstrap()` and `router.isReady().then(mount)` have no rejection handler: an unexpected throw is a blank page too.
- iOS Safari discards a long-backgrounded tab and reloads it when it is shown again (the "reloaded for a moment"). On
  such a reload WebKit has repeatedly been reported to leave the first `indexedDB.open` unanswered (the reason
  `safari-14-idb-fix` exists) and to hold a request on a dead connection for a long time. A manual reload clears both,
  which is what the owner sees. Which of them it is was not measured; a Web Inspector capture would tell.
- What is ruled out: `index.html` and `sw.js` are served `no-cache` (`AppShellRoutes`), so no stale shell asks for
  missing chunks; the media Service Worker answers only `/__media` and `/__download`, never a page.

The fix must not depend on which step hangs, because the next WebKit release may hang a different one.

## Decision

1. **The first paint is in `index.html`.** `#app` carries a static start screen: the wordmark and a progress
   indicator, no text, so it needs no language. Vue's mount replaces it. It is plain markup styled by the app's own
   stylesheet, which the built `index.html` links before any script runs, so it shows while the scripts load or hang.
   It follows `prefers-color-scheme` (the stored theme is applied by script, later) and `prefers-reduced-motion`.
2. **A start watchdog that does not share the app's fate.** A second module entry of its own, with no imports, listed
   in `index.html` before the app's entry, served same-origin like every script (the CSP's `script-src 'self'`; no
   inline script, ADR 0117's Trusted Types untouched, since it writes no HTML: it only toggles prepared markup and sets
   text). It waits for the app's "mounted" signal. Without it after **15 s**, or at once on the app's "failed" signal:
   - the **first** time within 5 minutes it reloads the page once, the reload the owner does by hand today;
   - otherwise it shows, over the start screen, "Taking longer than usual" with a **Reload** button and the boot step
     that has not finished (setup status, session, vault), so a report can name it.

   "Within 5 minutes" is a timestamp in `sessionStorage`; where storage throws, there is no automatic reload, only the
   button. So a server that hangs every request costs one automatic reload, never a loop. 15 s is above any boot this
   app has (two small requests and a local unwrap; no KDF runs on a remembered device) and below the point where a
   person gives up and reloads.
3. **The app reports its start.** `boot()` marks the step it is waiting for on the document; `main.ts` signals "mounted"
   after the mount and "failed" when `bootstrap()` or `router.isReady()` rejects. The signals are document attributes
   or events, nothing stored, nothing sent.
4. **Every chunk failure reloads the same way.** A `vite:preloadError` (a chunk the server no longer has after an
   upgrade) goes through decision 2's once-per-5-minutes reload, else its button.
5. **"This server can't be reached right now" gets a Reload button.** Today the banner names the state and offers
   nothing; a request that failed at start is retried by starting again.
6. **No deadline inside `boot()`.** A deadline per step would have to decide, per step, what a timeout means for the
   remembered device's tiers (uc.account.remember-this-device E2 keeps them on "no answer") and would abort a slow but
   working start. The watchdog's reload already is the right answer to any hang, and the step marker says which one.

## Consequences

- A hang at start is at worst 15 s of the start screen and one reload, never a blank page. A hang that survives the
  reload is a visible screen with a button and a named step.
- A genuinely unreachable server still lands where it did: its request fails, the banner shows, now with a button.
- The watchdog is the one script of the client that is not part of the app's module graph. It stays import-free, so it
  loads even when the app's chunks do not; a test holds that.
- `src/admin-web/` has the same mount-after-boot shape and is left as it is: it is opened on the Server Owner's
  machine, localhost by default (ADR 0065), not a phone tab Safari discards. Revisit if it is ever reported.
- Nothing about the cause is learned automatically. The named step on the screen and a Web Inspector capture are how
  it is found; a client log sent to the server is not on the table (ADR 0092 keeps logs operational, and a client
  report would be a new channel).

## Rejected alternatives

- **Mount first, boot after.** Renders the shell before the router knows the instance's state, so every first route
  would need a loading variant; the start screen in `index.html` gives the same "never blank" for none of that.
- **Deadlines per boot step** — decision 6.
- **Cache the shell in the Service Worker (offline first).** Shows the last shell at once, but the content behind it
  needs the vault, which lives in memory only, so it would be a shell without content; and it puts the Service Worker
  into every navigation, which ADR 0047 kept it out of.
- **Reload on `pageshow` / `visibilitychange` after a long absence.** Would reload tabs that resumed fine and lose what
  was on screen; the discarded tab is already reloaded by Safari, so the gap is the hang, not the missing reload.

## Related

[ADR 0047](0047-inline-video-playback-range-streaming.md) (the media Service Worker) ·
[ADR 0069](0069-backend-serves-the-web-app-shell.md) (the shell and its caching) ·
[ADR 0117](0117-the-web-client-enforces-trusted-types.md) (Trusted Types) ·
[cc.security.csp](../crosscutting/cc.security.csp.md)
