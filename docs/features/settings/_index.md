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

Account settings that *do* touch the server (display name, devices, emergency kit, password) are a separate,
later concern under [account](../account/_index.md) — this feature is only the device-local look & language.
