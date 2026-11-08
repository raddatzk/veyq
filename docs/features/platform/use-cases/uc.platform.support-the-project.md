# UC: Learn that veyq can be supported, once

- **ID:** uc.platform.support-the-project
- **Primary actor:** A **member** in the signed-in app, or a **link guest** on a share page
- **Goal:** Learn once, without being held up, that veyq is free software whose development can be supported, and
  find the way to the project's donation page and to why veyq is secure from every page's footer
  ([ADR 0124](../../../decisions/0124-veyq-asks-once-for-a-donation-on-every-instance.md)).
- **Preconditions:** None beyond opening a page of the signed-in app or a share page.
- **Postconditions (success):** The banner was shown until it was dismissed; a dismissal holds forever — in this
  browser, and for a member on their account too, so it stays gone on every device they sign in on.

## Main flow

1. A member opens the signed-in app, or a guest opens a share link. A banner at the top of the page says that veyq is
   free and open source and can be supported, with a link to the donation page on the project's website
   (`https://veyq.app/donate/`, the German page `https://veyq.app/de/donate/` in a German client) and a dismiss
   button (×). It is a labelled region; the × is a labelled button. It blocks nothing and asks nothing of the page.
2. The link opens the donation page in a tab of its own, so a share page keeps its key in the URL fragment.
3. The reader dismisses the banner with the ×. It goes at once, and this browser keeps the dismissal. For a member,
   the dismissal is also stored on the account (`donationBannerDismissed`, account-global, the profile update): a
   member's banner shows only while neither the account nor this browser holds a dismissal.
4. On every page that carries the instance's footer — the signed-in app, a share page, the login page, the operator
   section of the terms and the privacy notice — the footer links "Why is veyq secure?" (the website's
   [Is my data safe?](../../../../site/src/content/docs/privacy/is-my-data-safe.md) page) and "Donate" (the
   [donation page](../../../../site/src/content/docs/donate.md)), each in the reader's language and in a tab of its
   own, beside the instance's own terms, privacy notice and legal notice
   ([uc.admin.operator-details](../../admin/use-cases/uc.admin.operator-details.md)).

## Alternate / exception flows

- **A1 — Signing in, joining and recovering are never disturbed.** The login page, the tenant invite and join pages,
  the recovery pages and every other page outside the signed-in app and the share page show no banner; the login
  page's footer still carries both links. The Server-Owner console (`src/admin-web/`) shows neither.
- **A2 — A bookmark's share shown in place** ([ADR 0121](../../../decisions/0121-a-received-share-is-browsed-in-place-through-its-own-api.md))
  sits inside the member's page, which already carries the banner: one banner per page, never a second one for the
  embedded share.
- **A3 — The browser's storage cannot be read or written.** A dismissal that cannot be read counts as none, so the
  banner shows; a dismissal that cannot be written still hides the banner for the rest of the visit.
- **A4 — The member's profile cannot be read.** The banner shows (as for a guest); dismissing it still writes the
  account's flag, and a write that fails leaves the browser's dismissal in place.
- **A5 — No switch.** The banner shows on every instance; neither the Server Owner nor a space owner can turn it off.

## User impact

veyq is built by an independent, non-commercial project that pays for its own development; the people who use it
learn once that they can support it, are never asked twice, and are never held up on their way in.

## Quality notes

- **Server-visible, never content.** The account's `donationBannerDismissed` is low-sensitivity operational metadata:
  the operator learns that the account holder dismissed the banner, nothing else; it is served only to the account
  holder ([security model § What is encrypted vs. server-visible](../../../crosscutting/security-model.md#what-is-encrypted-vs-server-visible)).
  A guest's dismissal never leaves the browser.
- **Nothing is loaded from a third party.** The banner and the footer are plain links; the donation page itself
  links the ways to donate and embeds nothing.

## Related

- [ADR 0124](../../../decisions/0124-veyq-asks-once-for-a-donation-on-every-instance.md) (the ask, once, on every
  instance, with no switch); [ADR 0087](../../../decisions/0087-published-docs-are-a-starlight-site-docs-is-for-development.md)
  (the website the links point to)
- [uc.admin.operator-details](../../admin/use-cases/uc.admin.operator-details.md) (the footer the links sit in)
- Docs site: [Support veyq](../../../../site/src/content/docs/donate.md) (the donation page, and the banner in the
  app), [What a guest sees](../../../../site/src/content/docs/sharing/what-guests-see.mdx) (the banner and the footer
  links on a share page), [Is my data safe?](../../../../site/src/content/docs/privacy/is-my-data-safe.md) (where "Why
  is veyq secure?" leads)
