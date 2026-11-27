# UC: Make a new Emergency Kit when a clearer one exists

- **ID:** uc.account.renew-emergency-kit
- **Status:** built (contracts + backend + web + admin-web, cr-kit-layout-record); decided 2026-11-22 with the owner
  ([ADR 0157](../../../decisions/0157-a-kits-layout-has-a-version-and-a-sealed-record-says-which-one-the-account-last-made.md)).
  **What ran (2026-11-22):** a browser pass in lane L1's isolated throwaway stack (`scripts/run_stack_e2e.py --keep`,
  images built at `dc7c8861c`), headless installed Chrome, German UI, driven with `tests/e2e/shots/world.ts`'s helpers.
  All five states passed: (1) an account born with a record (a join birth; the admin-web Server Owner's birth wrote its
  row too) shows no notice and "Dein Notfall-Kit ist aktuell"; (2) with its `veyq_kit_record` row deleted, the shell
  notice shows layout 2's sentence and "Dein bisheriges Kit funktioniert weiter"; (3) "Jetzt nicht" and a reload keep the
  notice gone while the Security block still shows the older state; (4) the renewal refuses a wrong password with no kit
  and no `PUT`, accepts the old kit's PDF uploaded into the Secret-Key field, offers the kit, and only the "kept"
  confirmation sends exactly one `PUT /api/account/kit-record` (204), after which the block says current — the
  downloaded PDF carries "SECRET KEY · 9 BLÖCKE IN 3 ZEILEN" and imports again (same address and key); (5) a second
  fresh browser context shows no notice and "aktuell". The console held only the expected 401 of `/api/token/refresh`
  before sign-in; the teardown left the host's containers and volumes as they were.
- **Primary actor:** A signed-in, unlocked account holder whose last kit has an older layout than the one this client
  prints.
- **Goal:** Hold the clearest kit veyq can print, without changing the Secret Key and without being pushed to.
- **Preconditions:** a session and the vault unlocked; the account's kit record reads a layout below the client's
  `KIT_LAYOUT`, or the account has no record (a kit made before ADR 0157: layout 1). Making the kit needs the password
  and the Secret Key.
- **Postconditions (success):** the person saved a PDF of the current layout carrying the same Secret Key, the same
  sign-in address and this page's server; the kit record says the current layout. Nothing else changed: no key, no wrap,
  no session.

## Main flow

1. On unlock the client reads the kit record (`GET /api/account/kit-record`) and opens it with the Master Key.
2. The layout is older than `KIT_LAYOUT`. Settings → Security says so: "A clearer Emergency Kit exists", the sentence
   of what the newest layout does better, that **the kit the person has keeps working**, and **Make a new kit**. Once per
   browser and account the app shell shows a dismissible notice that links to that place.
3. The person chooses **Make a new kit** and enters their password and Secret Key. The Secret-Key field takes the old kit
   by upload or scan, as on every screen that asks for the key ([uc.account.unlock](uc.account.unlock.md) § One
   Secret-Key control), and keeps the submit grey until the key reads.
4. The client checks both against the stored `PASSWORD` floor (`proveColdCredential`: the vault is unlocked here), as
   the address change does before its kit ([uc.account.change-email](uc.account.change-email.md)) — which, on a page
   that may be locked, runs `checkColdCredential`.
5. The client renders the current layout from the key that passed and offers the PDF; the person saves it and confirms
   they keep it somewhere safe, as at every kit.
6. The client seals `{ "layout": KIT_LAYOUT }` under the Master Key while the proof holds it, and writes the sealed blob
   (`PUT /api/account/kit-record`) only once the person confirmed the kit is kept — no key is held across their save. The
   section now says the kit is current; the notice does not come back on any device.

## Alternate / exception flows

- **E1 — wrong password or Secret Key:** the check fails; no kit is rendered and the record is not written. The screen
  says the password or Secret Key did not match, as the address change does.
- **E2 — the person dismisses the notice:** it does not come back in this browser for this account. The section in
  Settings → Security still says a clearer kit exists until one is made. The dismissal remembers the layout the notice
  spoke of, not a flag, so a later raise of `KIT_LAYOUT` (a new sheet with a sentence of its own) is told once more.
- **E3 — the record cannot be written** (network, server): the kit was saved and is valid. The section keeps suggesting
  a new kit and the person may make it again later; the client says the kit was saved and that the next attempt only
  updates what veyq remembers.
- **E4 — the record does not open** (a blob that is not this account's, or garbage): read as no record, layout 1. The
  person is offered a kit they may not need; nothing is opened by it.
- **E5 — a kit is made another way** (a birth, securing the account, a new sign-in address): that flow writes the record
  itself ([ADR 0157](../../../decisions/0157-a-kits-layout-has-a-version-and-a-sealed-record-says-which-one-the-account-last-made.md)
  decision 4), so the suggestion disappears without this flow.

## Quality notes

- **Nothing about the key changes.** The new kit carries the same Secret Key; the old sheet keeps opening the account, and
  the screen says so before the person decides anything. A person who believes their old kit stopped working is the
  failure this flow must not cause.
- **Quiet.** A section that states the kit's state, and one notice that goes when dismissed. It never blocks and never
  returns; a raise of `KIT_LAYOUT` is reserved for a sheet that helps the person use it (ADR 0157 decision 2).
- **Beside other notices (current behaviour, not a decided policy).** The notice waits while the unconfirmed-address
  banner shows (`AppShell.vue`'s `otherNoticesOn` gates on that banner alone), but it can stand beside the donation
  banner and the profile prompt, as those two already stand together. Whether it should wait for them too is open with
  the owner.
- **Operator-blind.** The server stores one blob sealed under the Master Key and learns when it was written — that the
  account made a kit then, which a kit made at a birth or a rotation already tells it. It never sees the layout, the key
  or the sheet.
- **Proven before printed.** A kit is rendered only from a Secret Key that just opened the floor.

- **The apps** (built 2026-11-27, [ADR 0157](../../../decisions/0157-a-kits-layout-has-a-version-and-a-sealed-record-says-which-one-the-account-last-made.md)
  § Consequences; [native-apps](../../../crosscutting/native-apps.md)): the same section in each app's Settings →
  Security, the same one-time notice in each shell, the renewal proven before printed and recorded once kept, in the
  core (`KitRecord`, `KitStatus`, `KitRenewal`). Tests (no coverage surface, ADR 0040): `KitRenewalTest`,
  `AccountFlowsDispatchTest`, live `LiveAccountFlowsTest`.

## User impact

When veyq learns to print a clearer Emergency Kit, you hear about it once, and can print the new one in a minute with your
password and your old kit. Your old kit keeps working either way.

## Related

- Feature: [Account](../_index.md)
- Enabler: [en.account.key-model § The kit record](../enablers/en.account.key-model.md#the-kit-record)
- Use-cases: [uc.account.change-email](uc.account.change-email.md) (the proof before a kit),
  [uc.account.unlock](uc.account.unlock.md) (the Secret-Key field), [uc.account.rotate-keys](uc.account.rotate-keys.md)
  (a new key's kit, which writes the record)
- **End-user view (docs site):** [Your Emergency Kit](../../../../site/src/content/docs/getting-started/emergency-kit.mdx),
  [Lost Emergency Kit](../../../../site/src/content/docs/help/lost-emergency-kit.mdx)
