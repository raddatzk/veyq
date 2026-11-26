# ADR 0157 — A kit's layout has a version, and a sealed record says which one the account last made

- Status: Accepted
- Built: yes (cr-kit-layout-record; its browser pass, 2026-11-22, is recorded in the use case)
- Date: 2026-11-22
- Model: [uc.account.renew-emergency-kit](../features/account/use-cases/uc.account.renew-emergency-kit.md),
  [en.account.key-model § The kit record](../features/account/enablers/en.account.key-model.md#the-kit-record)

## Context

The first person to sign in with an Emergency Kit typed its first printed line alone: the kit prints the Secret Key as
three lines of three blocks, and one line looks like a whole key. The sign-in screen now greys its submit until the key
is whole, and the kit labels the key "Secret Key · 9 blocks in 3 lines" (owner 2026-11-22, the change that precedes this
ADR). Every kit printed before that change still carries the old sheet, and nothing tells its holder that a clearer one
exists. The owner asked (2026-11-22) that veyq remember which kit the account last made and suggest a new one when the
kit changed.

What the tree holds (read 2026-11-22 on `main` `28e3312c0`):

- **A kit has no layout version.** The `veyqkit:` token's `v` is the *token format's* version, pinned by
  `contracts/crypto/vectors/v1.json` § `emergency_kit_token` for `@veyq/crypto` and the native apps, and every reader
  refuses any other `v`. The PDF's document information carries only a title and the token.
- **No per-account setting is stored for the client.** The per-account sealed documents are the device register and the
  identity-pin head (`/api/account/device-register`, `/api/account/identity-pins`), each one blob sealed under the Master
  Key; the favorites are per scope. The only account-wide flags are plaintext on the per-tenant profile.
- **Kits are made in four places**: an account's birth by joining a tenant (`web`, no session yet: the register request
  is session-less), the Server Owner's birth at setup (`admin-web`, no session, its Master Key zeroed in the request
  builder), securing the account (`web`, the kit prints the new Secret Key before the rotation is published), and
  confirming a new sign-in address (`web`, the same Secret Key, proved by password and Secret Key).
- **A new kit needs the Secret Key in plaintext, and a device does not keep it.** A linked device holds the Master Key
  but never the Secret Key; the address change already makes its kit only after the person proved password and Secret
  Key (`checkColdCredential`).

## Decision

1. **A kit's layout has a version, `KIT_LAYOUT`, an integer.** It lives beside each writer's layout code (`web`'s and
   `admin-web`'s `emergencyKit.ts`, kept line for line alike), never in `@veyq/crypto` (no UI there, ADR 0065) and never
   in the token (a new token version would make every printed kit unreadable). The kits made before this ADR are layout
   **1**; the kit with the labelled key is layout **2**.
2. **The version is raised only when the change matters to someone using the kit.** A sheet that helps a person type,
   scan or find their way back counts — the labelled key counts, because a kit without it failed a sign-in. A new
   colour, font, wording of the keeping advice or a moved logo does not. Each raise comes with one sentence saying what
   the new kit does better (one catalog key per layout, both languages), shown wherever the suggestion is. A suggestion
   that arrives for cosmetics teaches people to ignore it, and the next one may matter.
3. **The account keeps a kit record: one blob sealed under the Master Key**, holding the layout of the last kit the
   account made (`{ "layout": n }`). It is stored server-side so every device of the account knows it, and sealed so
   the server learns nothing it does not already observe — that the account wrote a small document at a time. The seal
   binds its object (ADR 0072) through its key: purpose `kit-record` with no binding field, because the Master Key is the
   account's alone and an account has one record — and a birth seals it before the server has given the account an id.
   The server serves it only to the account and deletes it with the account.
   - **Last writer wins.** Two devices that write it at once both write the newest layout they know. A server that
     serves an older record makes the client suggest a kit the person does not need (a nuisance, nothing opens); one
     that serves a newer record hides a suggestion (nothing weaker is opened either). Neither is worth a revision and a
     trust mark.
   - **An account without a record made its kit before this ADR**, so it reads as layout 1. Every account born after it
     has a record from its birth (decision 4), so "no record" never means a fresh kit.
4. **Every request that makes a kit writes the record in the same step.**
   - **The two births carry it in their request**: the tenant join's `registerAndAcceptTenantInvite` and setup's
     `createServerOwner` take an optional sealed `kitRecord` (optional: a client that predates it still births, and the
     account then reads as layout 1), sealed by the client under the new Master Key it holds at
     that moment. A birth has no session to write it after, and `admin-web` runs on another origin, so a later write from
     `web` cannot know it happened.
   - **Securing the account carries it in the rotation request** (`rotateAccountKeys`), sealed under the new Master Key,
     as the device register is, and **required** like every field of that request (ADR 0060 decision 1: an optional
     field is a mode spelled by omission): the ceremony always prints a kit of the current layout, the record belongs to
     the new Secret Key and commits with it, and the row the old Master Key sealed is replaced.
   - **The address change and the renewal write it with `PUT /api/account/kit-record`** once the PDF was saved: both have
     a session, the Master Key and the same Secret Key. Each seals the record while its proof holds the Master Key and
     sends the sealed blob only once the person confirmed the kit is kept, so no key is held across their save (built
     so: `writeKitRecord` takes the blob, not the key).
   - **Recovery and a password change write nothing**: neither makes a kit, and the kit the account has stays valid.
5. **The suggestion is a quiet one** (owner 2026-11-22). Settings → Security says which kit the account has: current, or
   "a clearer kit exists" with the layout's sentence (decision 2) and **Make a new kit**. Once per browser and account,
   the app shell shows a dismissible notice that links there, as the profile prompt does; it never blocks and never
   returns after it was dismissed. The dismissal stores the layout the notice spoke of (built so), so a later raise of
   `KIT_LAYOUT` — a new sheet with a sentence of its own (decision 2) — is told once more. Both say that **the kit the person has keeps working**: the Secret Key does not change,
   so nobody believes they are locked out, and the old sheet may be destroyed once the new one is kept.
6. **Making the new kit proves the Secret Key first**: the person enters their password and Secret Key (the field takes
   the old kit by upload or scan), the client checks both against the stored floor (`proveColdCredential`, the vault
   being unlocked there; the address change's page, which may be locked, uses `checkColdCredential`), and only then
   renders the current layout from the key that passed. A kit printed from an unchecked key would be a sheet that opens
   nothing.

## Consequences

- One new account operation pair (`GET`/`PUT /api/account/kit-record`), one table, one seal purpose with its vector, and
  an optional field on three existing requests. The account erasure deletes the row.
- `KIT_LAYOUT` exists twice, as the layout code does. A raise touches both writers and both catalogs' sentence.
- The native apps (V2) make kits too once their account flows land: they write the record by the same rule, and their
  sheet takes the number of the web sheet it matches or a number of its own — decided when they print a kit.
- A person who never opens Settings and dismissed the notice keeps the old kit. That is accepted: it works, and the
  sign-in screen counts the blocks for everyone.

## Alternatives considered

- **Remember the layout per device (local storage).** No contract and no backend, but every device would ask on its
  own, a device that did not make the kit would never learn it was made, and `admin-web`'s birth is on another origin.
  Rejected.
- **A plaintext field on the account.** As cheap to build as the sealed record, but it would be a new kind of fact the
  operator stores about a person (which sheet they printed, client-reported) where a sealed blob tells it nothing new.
  Rejected.
- **A generic sealed "account settings" document.** Reusable, but it puts unrelated writers under one blob and one
  concurrency rule before a second setting exists. Rejected until there is one; the record's shape then decides whether
  it moves.
- **Read the layout from an uploaded kit.** The document information could carry it, but a printed-to-PDF copy, a photo
  and a scan carry none, and the question is asked when no kit is at hand. Rejected as the source; nothing prevents a
  later writer from also stating it there.
- **Raise the version on every layout change.** Simple, and it spends the reader's attention on cosmetics (decision 2).
  Rejected.
