# UC: Keep a link someone sent as a bookmark in a space

- **ID:** uc.sharing.keep-a-received-link
- **Primary actor:** a **member** of a space who received a public link — of this instance or of any other veyq instance.
- **Secondary actor:** the **share's own operator** (this instance or another), whose share page opens the link and takes
  its reports.
- **Goal:** keep the link, with its password where it has one, as a node at a folder level, and open or report it from
  there as the link itself would be opened or reported.
- **Preconditions:** a member who may create at the level (core's folder create: a writer, the space not pending
  deletion, not in a guest's identity folder), keys open.
- **Postconditions (success):** a `SHARE_BOOKMARK` node whose sealed meta holds the normalized link and the password
  ([ADR 0120](../../../decisions/0120-a-received-link-is-kept-as-a-bookmark-node-that-opens-and-reports-where-the-share-lives.md)
  decision 3); the server holds a node of that kind and ciphertext, nothing of the link.

## Main flow

1. The member chooses **New → New bookmark** at a folder level, pastes the link and, where it has one, its password.
2. The client checks the link as it is typed (a share link with its key, `https` or a development host, no credentials)
   and names the bookmark after the link's host until the member names it; nothing is fetched.
3. The client creates the node: a fresh `K_node` wrapped under the parent's key, the name sealed like every node's, and
   the meta `bookmark: { link, password? }` sealed under `K_node`, padded to a multiple of 512 bytes.
4. The member opens the bookmark from its row (or its page): a link of this origin opens in this tab, its password
   handed to the share page in memory and entered there once; a link of another origin opens in a new tab with no
   opener and no referrer, after the member is shown its password to copy where there is one.
5. The share page opens as the link would — or says the link is unavailable when it was revoked, expired or re-keyed
   away; nothing in the space changes.

## Alternate / exception flows

- **A1 Report:** the member chooses **Report shared content**: the link opens as in step 4 with the fragment parameter
  `report`, and the share page — of this instance or another of this version — opens its own report dialog for what the
  link shows, as its guest would report it. The report goes to the share's own operator. An older share page ignores the
  parameter; the member reports there through the page's own action.
- **A2 Report the name:** the bookmark's name is content on this instance: **Report name** reports it (`NAME`) to this
  instance's moderators, with the name's field key alone. A selection that holds a bookmark reports it the same way.
- **E1 Malformed link:** a text that is no link, `http` on a public host, a user name or password in the address, another
  path, or no whole key is refused with its reason, and nothing is created.
- **E2 Unreadable bookmark:** a bookmark whose meta does not read (another build's format) opens nothing; its page says so
  and offers its removal.
- **E3 A guest above it:** a public link of an enclosing folder does not serve the bookmark at all, and its guest can
  neither report, move nor rename it.
- **E4 Rotation:** the space's rotation re-keys the bookmark like every node; its link and password read the same under
  the new key.
- **E5 Download and copy:** a download and a copy leave the bookmark out and name it; a member pastes the link again where
  it should be.

## Quality notes

- **Operator-blind:** the server learns that a node of this kind exists, where, and when — not the instance, the share id
  or whether there is a password; a padded meta hides the link's length.
- **Re-sharing:** whoever can open the bookmark's place in the space holds the link's key ([security-model](../../../crosscutting/security-model.md)
  § Sharing). The create dialog says so.
- **This instance's moderators never receive a bookmark's key** (the server refuses a `NODE` report item of one,
  `422 report_target_kind`).

## User impact

A link someone sent you lives next to what it belongs to, keeps its password for you, and opens or is reported where it
was shared — without this server learning anything about it.

## Related

- Enabler: [en.sharing.public-link](../enablers/en.sharing.public-link.md) (the link's shape, key and gates)
- **Feature:** [sharing](../_index.md)
- **Decisions:** [ADR 0120](../../../decisions/0120-a-received-link-is-kept-as-a-bookmark-node-that-opens-and-reports-where-the-share-lives.md),
  [ADR 0093](../../../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md) (reporting),
  [ADR 0109](../../../decisions/0109-a-scope-rotation-re-keys-every-node-below-it.md) (the re-key it survives),
  [ADR 0079](../../../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md) (a core kind).
- **Planned:** browsing a foreign share in place (inbox #829); a received link kept in the account (inbox #435).
- End-user view (docs site): [Keep a link someone sent you](../../../../site/src/content/docs/sharing/keep-a-link.mdx)
