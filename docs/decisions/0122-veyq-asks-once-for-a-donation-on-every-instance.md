# ADR 0122 — veyq asks once for a donation, on every instance

- Status: Accepted
- Built: yes
- Date: 2026-11-08
- Model: [uc.platform.support-the-project](../features/platform/use-cases/uc.platform.support-the-project.md)

## Context

veyq is an independent, non-commercial project. Whoever uses it — on the project's own instance or on one somebody
runs for their family or club — never meets the project itself: the client shows the instance's name, its terms
and its operator. The owner wants the people who use veyq to learn that its development can be supported, without a
nag and without standing between anyone and their content.

## Decision

1. **One banner, the first time.** The signed-in app and a share page show a banner at the top that says veyq is free
   and open source and can be supported, with a link to the donation page on the project's website and a dismiss
   button. A member and a guest see the same banner — the access decides only where the dismissal is kept (the owner
   principle that a share looks like the member view).
2. **Dismissed forever.** A guest's dismissal is kept in the browser. A member's is kept in the browser and on the
   account (`donationBannerDismissed`, account-global, set through the profile update), so it holds on every device;
   the banner shows only while neither holds one.
3. **Never on the way in.** The login, invite, join and recovery pages show no banner, and the Server-Owner console
   shows none.
4. **No switch.** It shows on every instance; there is no setting for the Server Owner or a space owner.
5. **The footer links the project** on every page that carries the instance's footer, the login page included: "Why
   is veyq secure?" and "Donate", both to the project's website, in the reader's language and in a tab of their own.
   The share page's former security link is this one.
6. **The donation page lives on the project's website** (`/donate/`, `/de/donate/`), lists the ways to donate as plain
   links and loads nothing from a third party.

## Consequences

- The server learns one more fact per account — that its holder dismissed the banner — which is low-sensitivity
  operational metadata, served only to the holder, and stored as a boolean on the account.
- A Server Owner who would rather not show the ask cannot turn it off. This is deliberate: the project asks, not the
  operator, and the ask is one dismissible line, shown once.
- Rejected: **a Server-Owner switch** — it would make the ask the operator's choice and, on most instances, silently
  absent; **an ask that returns** (each release, each year) — a nag for a free product; **a browser-only dismissal for
  members** — a member would be asked again on every new device; **a banner on the login page** — it would stand
  between a member and signing in; **an embedded donation widget or button** — it would load a third party into the
  client or the website.
