# Feature: Settings

Device-local app preferences — how veyq **looks** and **speaks** on this device. These are **not** content
and **not** synced: they live in the client's own storage (`localStorage`), so they never reach the
operator-blind server.

- **Cross-cutting:** [Security Model](../../crosscutting/security-model.md) (preferences are client-only —
  nothing here touches the server).

## Use-cases

- ★ [uc.settings.appearance-language](use-cases/uc.settings.appearance-language.md) — **built (web)**
  (theme: system/light/dark + language: en/de, applied live + persisted; cr-0031)

## Note

Account settings that *do* touch the server (devices, emergency kit, password) are a separate, later concern under
[account](../account/_index.md) — this feature is only the device-local look & language. There is no account-level
display name: a name is per-tenant identity ([ADR 0023](../../decisions/0023-per-tenant-member-identity.md)), edited in
[uc.spaces.edit-profile](../spaces/use-cases/uc.spaces.edit-profile.md). The hub's *Map and place names* entry is
device-local too, but the consent it gives and withdraws is the place's, so its flow is
[uc.memory.place](../memory/use-cases/uc.memory.place.md) A5, beside the in-context prompt and the map's own *Turn map
off*.
