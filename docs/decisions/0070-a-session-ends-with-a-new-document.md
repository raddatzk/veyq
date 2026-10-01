# ADR 0070 — A session ends with a new document, not a store reset

- Status: Accepted
- Date: 2026-09-23

## Context

The web client keeps decrypted state in module-level stores: the vault, the epoch-key chain, the decrypted
tree and breadcrumb names, favorites, covers, place names, upload batches that hold a Memory key and the
plaintext file, and a pending deep link that can carry a share key in its fragment. The media service worker
holds a decrypted read-ahead per playback ([ADR 0047](0047-inline-video-playback-range-streaming.md)).

Sign-out reset these from an explicit list, and the list failed in every way a list can fail. It missed stores:
the tree was never reset, and several stores had a reset nobody called or no reset at all. A screen carried its
own copy of the sequence. Two entry points skipped most of it. A refresh the server rejected dropped the tokens
and nothing else, so a tab whose family was revoked from another device kept showing plaintext until it was
closed. Other tabs heard nothing.

A session ends for three reasons: the user signs out, the server rejects a refresh, or another tab ended the
session ([uc.account.sign-out](../features/account/use-cases/uc.account.sign-out.md)).

## Decision

1. **All three triggers run one ending**, in a web `app/` module that imports no feature:
   1. drop the local convenience tiers;
   2. tell the media service worker to drop every playback;
   3. announce *ended* to the other tabs on a same-origin `BroadcastChannel`, only when this tab ended it;
   4. load the sign-in page as a **new document** (`location.replace`).

   **The new document is the wipe.** It discards the page's whole heap, so every store goes, including one
   added next year.
2. **Signing out is one sequence in one module**: it deletes the convenience wraps and revokes the family
   while the session still works, then runs the ending. Screens call that sequence; none has its own. **A
   failed revoke never skips the ending.** It is reported on the sign-in page, because the refresh cookie is
   then still live, and on someone else's browser the person has to know that.
3. **Only an authoritative refresh rejection ends a session** — a 4xx from the refresh endpoint, other than a
   408 or 429 (*not now*, which a proxy may answer, rather than *no*). A network failure or a 5xx keeps the
   session and its tokens. At boot, before a tab holds a session, the same line decides whether the device's
   local convenience tiers are dropped: only a refusal drops them; a boot that could not get an answer keeps
   them and says the instance is unreachable.
4. **A tab that holds no session never runs the ending.** The sign-in page at boot, a public share and a
   device waiting in a pairing ceremony have nothing to end, and an ending there would reload the sign-in page
   into itself.

## Consequences

- **Completeness belongs to the platform, not to a list.** Nothing has to register, and no guard has to find
  every shape of module state. The one change that voids the guarantee is replacing the document load with an
  in-app route change for a smoother transition. So the ending's test pins the document navigation, and the
  reason sits as a comment at that line.
- **No late write can land in a wiped store.** In-flight requests die with the document; a reset-based design
  needs a fence for responses that arrive after the reset.
- **The ending imports nothing, so every trigger can reach it.** The refresh path calls it without importing
  a feature, which removes the import cycle that folding the reset list into `logout()` would have closed.
- **What lives outside the page heap needs explicit handling, and there are exactly two such places.** The
  service worker gets its message. Persistent storage is either cleared by name or listed as staying, in
  uc.account.sign-out § *What stays on the device*.
- **Cost:** signing out reloads visibly, and boot runs again. Signing out is rare and boot is cheap.
- **Rejected:**
  - *A registry of resets*, each store registering at module load, plus a guard over module-level state. That
    is completeness by list again: the guard's predicate is a paraphrase of what module state looks like and
    misses the shape nobody thought of. It also needs a fence against late writes.
  - *Extending today's list.* That is the design that failed.
  - *Clearing only the vault and relying on decryption failing afterwards.* Names and media that are already
    decrypted stay.
- **Not covered by this decision:** another browser or device (that is
  [uc.account.sessions](../features/account/use-cases/uc.account.sessions.md)), and a sign-in to a different
  account in another tab of the same browser.
