# UC: Sign out — and every other way a session ends

- **ID:** uc.account.sign-out
- **Primary actor:** Account holder in a browser tab that holds a session — unlocked, locked, or held at the
  terms gate
- **Goal:** End the session on this device so that nothing decrypted, and no key that opens content without
  the password, is left behind — in this tab or in any other tab of the same instance.
- **Preconditions:** The tab holds a session (an access token in memory, or an unlocked vault).
- **Postconditions (success):** The session family is `REVOKED` (sign-out) or already dead (the other two
  triggers). Every tab of this instance in this browser shows the sign-in page as a **new document**: no
  Master Key, no epoch key, no decrypted name or media in memory, and none in the media service worker either.
  What stays on the device is exactly § *What stays on the device*.

## Three triggers, one ending

A session ends for one of three reasons, and all three run **the same ending**
([ADR 0070](../../../decisions/0070-a-session-ends-with-a-new-document.md)):

- **The user signs out** — from the profile menu, by revoking their own session in the sessions list
  ([uc.account.sessions](uc.account.sessions.md) E2), by declining the terms
  ([uc.account.accept-terms](uc.account.accept-terms.md)), from the sign-in card, or from the locked screen.
- **The server rejects a refresh** — the family was revoked from another device, or it expired.
- **Another tab of the same instance ended its session.**

This is a property with several triggers, so it lives here and not under any one of them.

## Main flow — sign out here

1. The user chooses *sign out*, in any of the five places above. Every one of them calls the same sign-out; no
   screen carries its own sequence.
2. While the session still works, the client deletes this device's password-less convenience tiers: the
   session vault and the remember-me key, both locally and the remember-me wrap on the server
   ([uc.account.remember-this-device](uc.account.remember-this-device.md)).
3. The client asks the server to revoke its session family
   ([uc.account.login](uc.account.login.md), `logout-revokes-family`).
4. The session ends locally — § *The ending*. It runs even when step 2 or 3 failed: an unreachable server is no
   reason to leave keys in the page. A failed revoke is reported on the sign-in page instead (E4).

## The ending — the same for every trigger

1. The local convenience tiers are dropped (a no-op after a sign-out's step 2; on the other two triggers the
   server half is out of reach, since the session is already dead).
2. The media service worker is told to drop every playback it holds, together with its decrypted read-ahead
   ([ADR 0047](../../../decisions/0047-inline-video-playback-range-streaming.md)). It lives outside the page, so
   the page going away does not take it along.
3. The other tabs of this instance are told the session ended — only by the tab that ended it, so the message
   never echoes.
4. The tab loads the sign-in page as a **new document**. That discards everything the page held in memory,
   which is what makes *every* decrypted store go without a list of stores to keep complete. (The vault's own
   key buffer is zeroed just before, as hygiene for the heap the old page leaves behind — not as the wipe.)

## Alternate / exception flows

- **E1 — a refresh is rejected.** An authoritative rejection (the refresh endpoint answers 4xx: revoked,
  expired, unknown) in a tab that holds a session runs the ending. A network failure or a 5xx is **not** a
  rejection: the session and its tokens stay, and the next call tries again. Losing the connection for a
  moment must not cost an unlock. Neither is a 408 or a 429, which a proxy in front of the instance may answer:
  they say *not now*, not *no* — nor a `403 tenant_suspended`, which a suspended tenant's own domain answers to
  every call without looking at the session ([uc.spaces.scope-access](../../spaces/use-cases/uc.spaces.scope-access.md)
  A7). The same line decides E4: a sign-out refused that way did not revoke the family.
- **E2 — the tab holds no session.** The sign-in page at boot, a public share, a device waiting in a pairing
  ceremony: such a tab ignores the other-tab message, and a refresh rejected at boot is the ordinary *not
  signed in* path, not an ending. Without this, an ending on the sign-in page would load the sign-in page
  again, forever.
- **E3 — another tab ended the session.** This tab runs the ending and asks the server nothing. Its tabs share
  one family through the refresh cookie, so that family is already revoked or dead.
- **E4 — the revoke did not reach the server** (offline, 5xx). The ending still runs, and the sign-in page says
  that the sign-out did not reach the server. The refresh cookie is then still live, so this browser can come
  back signed in, though locked. The sign-in card's own sign-out is the retry, the same fallback
  [uc.account.sessions](uc.account.sessions.md) E3 names for the locked entry. The notice carries no account
  data across the new document: it is one word in this tab's `sessionStorage`, and the sign-in page removes it
  when it reads it.

## What stays on the device

Only what cannot open content on its own, or does not belong to the account:

- the **password device-trust wrap**, kept so that the next sign-in on a trusted device asks for the password
  and not the Secret Key ([uc.account.trust-device](uc.account.trust-device.md)); removing it is *untrust*,
  a separate action;
- the **device id**, which holds no key and names this browser in the sealed device register;
- a **public-share contributor capability** and a share visitor's **terms consent**, which belong to a share
  link or its tenant, not to the account;
- display preferences, including which contributor bucket a Memory's picker last opened on (two opaque ids, no
  name).

## Quality notes

- **The new document is the guarantee; never replace it with an in-app route change.** A route change keeps
  the page's memory, and a list of stores to reset is how decrypted names outlived every sign-out before
  ([ADR 0070](../../../decisions/0070-a-session-ends-with-a-new-document.md)).
- **One sign-out sequence, in one place.** A screen with its own copy is how a sign-out that skips half the
  sequence ships.
- **The other-tab message carries nothing but *ended*** — no account, no id. It reaches only tabs of the same
  origin, which is the same instance host.
- **Another browser or device is out of reach here** — ending a session there is
  [uc.account.sessions](uc.account.sessions.md).

## What this means for you / Was das für dich bedeutet

**EN —** When you sign out, veyq closes everything it had opened: your keys and everything it decrypted are
gone from the page, and every other veyq tab in the same browser signs out with it. The same happens when
your session is ended from another device. What your browser keeps cannot open your memories on its own —
at most a saved key that still needs your password, if you chose to trust this device.

**DE —** Wenn du dich abmeldest, schließt veyq alles, was es geöffnet hatte: deine Schlüssel und alles
Entschlüsselte verschwinden aus der Seite, und jeder andere veyq-Tab im selben Browser meldet sich mit ab.
Dasselbe passiert, wenn deine Sitzung von einem anderen Gerät aus beendet wird. Was dein Browser behält,
kann deine Erinnerungen nicht allein öffnen — höchstens einen gespeicherten Schlüssel, der weiterhin dein
Passwort braucht, falls du diesem Gerät vertraust.

## Related

- Feature: [Account & auth](../_index.md)
- Decisions: [ADR 0070](../../../decisions/0070-a-session-ends-with-a-new-document.md) (the ending),
  [ADR 0004](../../../decisions/0004-session-tokens.md) (families, revocation),
  [ADR 0058](../../../decisions/0058-revoke-never-takes-key-material.md) (a revoke takes no key material)
- Related use-cases: [login](uc.account.login.md) (the server revoke), [sessions](uc.account.sessions.md)
  (ending a session elsewhere), [remember-this-device](uc.account.remember-this-device.md),
  [trust-device](uc.account.trust-device.md), [accept-terms](uc.account.accept-terms.md)
