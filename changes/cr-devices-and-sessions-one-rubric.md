---
id: cr-devices-and-sessions-one-rubric
title: Fold "This device" into the sessions rubric — one place that says what this device is
status: planned
satisfies: [uc.account.trust-device, uc.account.sessions]
adrs: ["0022", "0058"]
invariants: [operator-blind-e2ee, self-hostable-by-default]
---

## Goal

The settings hub has two Persönlich rubrics about the same object. **Dieses Gerät** renders one status
sentence and, on a device with no convenience wrap, no action at all — a sidebar entry that usually resolves
to a single sentence. **Sitzungen** already lists every signed-in device *including this one* (`sessions.thisDevice`
+ the `sessions.currentBadge` "Du bist hier" row), already offers the escalation *"sign out and remove this
device's saved key"*, and already carries a whole § *Geräte mit gespeichertem Schlüssel*. So the fact "this
device holds a saved key" is stated in two places, in two vocabularies, one rubric apart.

Fold the device-trust status + its forget action into the sessions panel's own row for the current device,
rename the rubric to **Geräte und Sitzungen**, and drop `device` as a separate section. `DeviceTrustPanel`
stays a component — its logic is device-local and does not belong inside the sessions code — it is mounted
by the row instead of by the hub. One rubric then answers both questions a user actually has about a device:
*where am I signed in* and *how does this one unlock*.

## Context & constraints

- [uc.account.trust-device](../docs/features/account/use-cases/uc.account.trust-device.md) § Untrust — the
  local half (server DEVICE wrap + IndexedDB entry) and its endpoint whitelist.
- [uc.account.sessions](../docs/features/account/use-cases/uc.account.sessions.md) — the list, the remote
  revoke, the escalation, § Devices with a saved key.
- [ADR 0022](../docs/decisions/0022-settings-information-architecture.md) — the hub IA. This CR is an
  *application* of it, not an amendment: ADR 0022 sets the sidebar↔detail split and the scope×role filter,
  it does not enumerate the Persönlich rubrics. Its own Consequences establish that a pre-deploy route is
  **replaced, not redirected**, which is the precedent for dropping `/settings/device`.
- [ADR 0058](../docs/decisions/0058-revoke-never-takes-key-material.md) — why a plain revoke does not take
  key material, and why the escalation is separately named. **This is the line the CR must not blur.**
- Invariants at risk: none of the crypto ones. Nothing here moves key material, changes a wrap format, or
  adds a server field — it is a web-only re-arrangement of two existing panels.

### What must stay distinct after the merge

Two actions end up in one row and they look alike. They are not:

| | forget this device (`unlock.untrust`) | sign out + remove saved key (`sessions.escalate`) |
|---|---|---|
| reach | this device, local key **and** server wrap | any device, server wrap only |
| session | untouched — you stay signed in | ended |
| the device's local key | deleted | unreachable; survives as an unusable handle (§E1) |

They keep **separate labels, separate confirms, separate severities**. The cheap-looking simplification —
one "remove this device" button — is wrong, because the server can never reach a remote device's local key,
only its wrap; that asymmetry is what forces two names. The sessions panel's own header comment already
carries the neighbouring rule ("never a checkbox on the revoke, never a default"); this CR must not make the
harmless tidy-up look like the action that sends someone to their Emergency Kit.

### Locators (run, don't estimate)

```bash
# every mount / route / nav reference to the 'device' section
rg -n "'device'|\"device\"|settings\.device\b" web/src

# every i18n key under settings.* that the merged rubric re-homes (both locales)
rg -n "device(Title|Sub|Remembered|Trusted|Untrusted|Unsupported)|untrustConfirm|untrustFailed" web/src/i18n/index.ts

# the tests that anchor the current two-panel shape
rg -ln "DeviceTrustPanel|settings\.device" web/src --glob '*.test.ts'

# coverage criteria that name the panel or the rubric
rg -n "DeviceTrustPanel|This device|settings hub" docs/coverage
```

### Gating — verified, not assumed

Both panels live behind `/settings/:section?` with `meta: { requiresUnlock: true }`
([web/src/router.ts](../web/src/router.ts) (the `settings` route)), so inside the hub there is **no gating asymmetry** to
preserve. The locked entry point is `LockedSignOut` in the sign-in card (uc.account.sessions §E3) and this CR
does not touch it. Separately: the trust status reads are pure IndexedDB —
`isTrusted()` ([trustDevice.ts](../web/src/features/account/trustDevice/trustDevice.ts)) and
`isRemembered()` ([deviceKeyWrap.ts](../web/src/features/account/rememberMe/deviceKeyWrap.ts)) — no
vault, no network, so mounting them inside the sessions panel cannot make the list wait on anything. That is
the property the merge rests on, and it is the property the bite test below attacks.

## Phases

- [ ] 1 web — mount the device-trust status + forget action on the sessions panel's current-device row;
      drop the `device` hub section and re-home its i18n keys under `sessions.*` (EN + DE); rename the rubric
      to *Devices and sessions* / *Geräte und Sitzungen*; keep `DeviceTrustPanel` as the component that owns
      the local logic · Done when: `npm run check` green in `web/`
- [ ] 2 coverage — `uc.account.trust-device.yaml` criterion `untrust-from-settings-ui` re-pointed at where
      the action now lives (and its text corrected — it names the *"This device" panel* today);
      `uc.account.sessions.yaml` gains the criterion that the current-device row states this device's unlock
      tier · Done when: `python3 scripts/validate_coverage.py` green
- [ ] 3 docs — uc.account.trust-device § Untrust: the one sentence that locates the untrust control follows
      it; shape of the correction = *the control is named by its row in the sessions rubric, not by a rubric
      of its own* · Done when: `scripts/validate_*.py` (trust-chain) green

## Bite tests

Per bite: the lever, and the test that must go **red** when it moves.

1. **The forget action is offered only when a convenience tier is actually present.** Lever: force
   `refresh()`'s catch path (make `isTrusted` reject). The row must read `none` and offer **no** forget
   button — the existing best-effort default. Red when the fallback is flipped to "offer it anyway".
   *This bite is live today in `DeviceTrustPanel.test.ts`; the CR must prove it still bites from the new
   mount point, because a row that renders its actions from the parent's session data could lose it silently.*
2. **The list never waits on the trust read.** Lever: make `isRemembered()` hang (never-resolving promise).
   The session list must still render and every revoke must still work — the panel's stated trap ("the panel
   must never disable the action it exists to offer"). Red when the row's status read is awaited by the
   loader instead of resolved beside it. **This is a new assertion**: nothing tests it today, because the two
   panels could not block each other while they were separate. It is the defect the merge *introduces the
   possibility of*, so it is written before the merge, not after.
3. **The two actions stay two.** Lever: a test that asserts the current-device row exposes both labels with
   distinct confirm copy. Red when someone later collapses them into one button — which is exactly the
   simplification this CR predicts and ADR 0058 forbids.

## Definition of done

`npm run check` green in `web/`, `python3 scripts/validate_coverage.py` green, trust-chain green. Bite 2 is
written and shown red against a deliberately-awaited variant before the real wiring lands (order:
test → red → wire → green). No guard promotion: the structural claim here is a UI composition property,
which a component test asserts directly — there is no cross-file invariant for a `scripts/` guard to scan.

## Retrospective

<!-- filled at close -->
