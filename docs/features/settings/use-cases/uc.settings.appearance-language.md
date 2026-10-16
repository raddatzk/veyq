# UC: Set appearance & language

- **ID:** uc.settings.appearance-language
- **Primary actor:** Any signed-in user (on their device)
- **Goal:** Choose how the app **looks** (theme: system / light / dark) and what **language** it speaks
  (system / English / Deutsch), applied immediately and remembered on this device.
- **Preconditions:** The app is running (no account or unlock needed — these are device-local preferences).
- **Postconditions (success):** The chosen theme + language are applied to the whole UI at once and persisted
  locally; they survive a relaunch. Nothing is sent to the server.

## Main flow

1. The user opens the profile / settings surface (the sidebar ProfileMenu).
2. **Theme** — picking *system / light / dark* flips the whole palette live (toggling `.veyq-dark` on
   `<html>`). The adaptive design tokens recolour everything.
3. **Language** — picking *system / English / Deutsch* re-renders every visible string live (`vue-i18n`),
   **without** reloading content (the open Memory tree stays put).
4. Both choices are written to local storage and re-read on the next launch.

## Alternate / exception flows

- A1 — **"system" (both axes, the default):** theme follows the OS appearance and language follows the OS
  language **live** — no override. The app ships only en + de, so any non-German OS language resolves to
  English. Switching the OS dark/light or language updates the app while it runs.
- A2 — **first run:** with no stored preference, **both default to *system*** — the app adopts the OS
  appearance + language until the user picks a concrete value.

## Quality notes

- **Device-local, server-blind.** Theme + language live only in client storage (`localStorage`); they are
  never sent to the operator-blind server and are not part of any account/content.
- **Live, no reload.** Switching language must not recreate app state — the i18n locale is reactive, so the
  Memory tree isn't reloaded.
- **Names are data, not strings.** Decrypted folder/Memory names are user content — they are shown verbatim
  and are **not** translated when the language changes.

## User impact

You choose a light or dark look and English or German, and the choice stays on this device.

## Related

- Feature: [Settings](../_index.md)
- Concepts: [Security Model](../../../crosscutting/security-model.md) (client-only, server-blind)
- Work orders: cr-0031 (the ProfileMenu — theme + language)
- End-user view (docs site): [Appearance and language](../../../../site/src/content/docs/account/appearance-and-language.mdx)
