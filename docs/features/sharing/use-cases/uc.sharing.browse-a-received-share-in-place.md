# UC: Browse a received share in place, inside the space

- **ID:** uc.sharing.browse-a-received-share-in-place
- **Primary actor:** a **member** who keeps a received link as a bookmark ([uc.sharing.keep-a-received-link](uc.sharing.keep-a-received-link.md)).
- **Secondary actor:** the **share's instance** (this one or another), whose public share API serves the member's
  browser directly.
- **Goal:** see and, where the link allows, add to a share someone sent — of any veyq instance — on the bookmark's page,
  as if it were a folder of the space.
- **Preconditions:** `core.share-embedding` effective for the member's tenant; the share's instance serves the same
  major, `core.public-shares` and `core.public-shares.embeddable` (for another origin: and answers CORS).
- **Postconditions (success):** the member read (and wrote) through the link as its guest; this instance learned nothing
  of it ([ADR 0121](../../../decisions/0121-a-received-share-is-browsed-in-place-through-its-own-api.md)).

## Main flow

1. The member opens the bookmark (its row, a favorite, its address).
2. The client reads the share's `/api/setup` and checks its major and its two capabilities.
3. The client resolves the link through the share's API, entering the bookmark's password where the link asks for one,
   then shows the share's tenant's terms and the note's acceptance where they apply.
4. The bookmark's page draws the link's content under the bookmark's header — a folder's level, the root kind's page, or
   a drop page — and the member browses below the root with the page's own breadcrumb.
5. On a `READ_WRITE` link the member names themselves as its guest at the first write, and creates, uploads or moves
   there; on a `WRITE_ONLY` link they drop files.
6. A report from the embedded view goes to the share's instance as its guest's report.

## Alternate / exception flows

- **A1 Not embeddable:** another major, a missing capability, no CORS, a network error, or embedding switched off here →
  the page says why in one line and offers **Open link**, ADR 0120's open.
- **A2 Open on its own page:** the member opens the link on the share page instead, as before.
- **E1 Link gone:** revoked, expired or re-keyed away → the embedded view says the link is unavailable; the bookmark stays.
- **E2 Wrong password:** the bookmark's password is refused → the password form, as on the share page.

## Quality notes

- The member's access, keys and doors never reach the embedded view; the guest's never reach the member's tree.
- What a guest keeps on the device is keyed by the share's origin and id.
- The CSP widening (`connect-src https:`) holds only while `core.share-embedding` is effective; its cost is in
  [cc.security.csp](../../../crosscutting/cc.security.csp.md).

## User impact

A share someone sent you reads like a folder of your own space — on any veyq server — and this server still learns
nothing about it.

## Related

- **Feature:** [sharing](../_index.md); enabler [en.sharing.public-link](../enablers/en.sharing.public-link.md)
- **Decisions:** [ADR 0121](../../../decisions/0121-a-received-share-is-browsed-in-place-through-its-own-api.md),
  [ADR 0120](../../../decisions/0120-a-received-link-is-kept-as-a-bookmark-node-that-opens-and-reports-where-the-share-lives.md),
  [ADR 0088](../../../decisions/0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md)
- Work order: `changes/cr-browse-a-received-share-in-place.md` (not built yet)
