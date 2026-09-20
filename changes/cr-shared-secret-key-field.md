---
id: cr-shared-secret-key-field
title: One Secret-Key control, with kit import, everywhere the Secret Key is asked for
status: planned
satisfies: ["uc.account.unlock"]
adrs: ["0054", "0059", "0060", "0006"]
invariants: ["operator-blind E2EE — the kit is decoded on-device and no part of it is sent anywhere"]
---

## Goal

The Emergency-Kit import (upload the kit PDF, scan its QR, or paste its token) exists and works, but its
affordance is copy-pasted markup living in exactly **one** view — the sign-in screen. Every other place that
asks for the Secret Key renders a bare text input, so the person is told to type a 32-byte base64 key by hand:
the tenant-invite acceptance, the recovery reset, the authenticated password change and the key rotation. Three
of those four already *document* the import as the norm, so this is doc↔code drift rather than a new feature.
Extract the control into one `SecretKeyField` component, give it the paste-a-kit-token normalisation that only
the recovery view has today, and use it in all four places. Where the expected account e-mail is known, a kit
belonging to a different account **warns** and never blocks — today that mistake surfaces only as a generic
AEAD tag mismatch ("unlock failed"), which is the worst possible diagnosis for "you took the wrong kit".

## Context & constraints

- [uc.account.unlock](../docs/features/account/use-cases/uc.account.unlock.md) — owns the property: the Secret
  Key may be typed/pasted **or imported from the Emergency Kit**. This CR makes that one control and states it.
- [uc.account.recover-access](../docs/features/account/use-cases/uc.account.recover-access.md) §5,
  [uc.account.change-password](../docs/features/account/use-cases/uc.account.change-password.md) §2 — both
  already assert "typed or imported from the Emergency Kit"; neither view offers the import.
- [uc.admin.create-tenant](../docs/features/admin/use-cases/uc.admin.create-tenant.md) +
  [ADR 0006](../docs/decisions/0006-cold-tenant-scope-birth.md) — the invite-acceptance screen this started
  from. Only its *existing-account* branch asks for a Secret Key; the cold-register branch mints one.
- [ADR 0059](../docs/decisions/0059-device-linking.md) — one scan entry serves both of this app's
  QR payloads, and recognising a payload is the **caller's** job. The shared control must therefore keep
  `accepts` a caller-supplied predicate and hand back a decoded string it did not recognise, or the sign-in
  screen's pairing dispatch breaks.
- `web/src/features/account/emergencyKit/emergencyKit.ts` — `parseKit` / `importKitFromFile` already exist and
  are framework-free. Nothing about the kit **format** changes here.
- `admin-web/src/features/setup/emergencyKit.ts` is a deliberate write-only fork (jsQR is an argued dependency
  exception granted to `web/` only, see its header). **Out of scope — do not unify.**
- Operator-blind: the kit is read with `File.text()` / a canvas + jsQR, on-device. No new network call at all.

## Phases

- [ ] 1 docs — `uc.account.unlock` states the shared control + the warn-not-block mismatch rule; the three
      neighbouring use-cases point at it instead of restating it ·
      Done when: `python3 scripts/validate_doc_links.py` + `validate_feature_format.py` green
- [ ] 2 web — `web/src/shared/ui/SecretKeyField.vue` + its four hosts + 2 new i18n keys (EN+DE) ·
      Done when: `npm run check` green in `web/`
- [ ] 3 coverage — `uc.account.unlock.yaml`: extend `secret-key-import-from-kit`, add the mismatch criterion ·
      Done when: `python3 scripts/validate_coverage.py` green

Locators (run, don't estimate):
```
grep -rn "secretKey" web/src --include=*.vue | grep -i "InputText\|secret-field"
grep -rn "parseKit\|importKitFromFile\|KitScanner" web/src
```

## Definition of done

`npm run check` green in `web/`, every `scripts/validate_*.py` green, and `docs/coverage/` carries the new
behaviour. The structural invariant this rests on — **the Secret-Key control exists once** — is proved by the
first locator above returning no bare `InputText` bound to a `secretKey` model outside the shared component.
That is a one-shot proof, not a promoted guard: a fifth bare copy would be a UI regression a reviewer sees in
the diff, and the guard registry's bar is "a defect a human would not notice" — this one is visible on screen.

## Retrospective

(filled at close)
