# ADR 0120 — A received link is kept as a bookmark node, which opens and reports where the share lives

- Status: Accepted
- Built: yes
- Date: 2026-11-06
- Model: [en.sharing.public-link](../features/sharing/enablers/en.sharing.public-link.md)

## Context

A public link is a bearer secret: `https://<instance>/s/<shareId>#k=<K_share>`, the key in the fragment, sometimes a
password beside it that the share page asks for ([ADR 0029](0029-public-viewer-share.md),
[ADR 0037](0037-password-protected-shares.md)). Someone who receives one keeps it in a chat or a mail, outside veyq.
Inbox #435 wanted a received link kept in the account, sealed under the account's own key; the owner decided (2026-11-06)
that its first form is **a node in a space**: a member pastes a link they received — of this instance or of any other
veyq instance — and places it at a folder level like any other node, and opening it opens the share page as the link
itself would. Browsing a foreign share *in place*, as if it were a folder of the space, is wanted later (inbox #829); it
needs a wider `connect-src` and capability discovery, and is not decided here.

Three things had no answer: where such a node's kind lives, what a member of a group space learns by holding one, and
what "Report" means on a node whose content lives on another operator's server — which must not end with that content,
or the key to it, at this instance's moderators.

## Decision

1. **A bookmark is a core kind, `SHARE_BOOKMARK`.** A link is core's ([ADR 0079](0079-veyq-is-a-platform-memories-is-its-first-application.md)
   decision 3: "around a node is core's", links included), and a link of any application can be kept. Core answers for the
   kind beside `FOLDER` on both sides: `ShareBookmarkNodeRules` on the backend, and on the web a descriptor in core
   (`core/bookmark/`) that the composition root registers through `registry.registerCoreKind` before the applications —
   its rows, page and create entry served like an application's kind's, owned by none. It is no application's, so no switch
   turns it off and it claims no route segment (its names are numbered among the plain names, as a Depot file's are). The product word is **bookmark**
   (*Lesezeichen*), never a favorite, which is a member's private pin of a node ([ADR 0086](0086-one-word-per-meaning.md)).
2. **Where it lives, and what it holds.** At a scope's top level or directly in a `FOLDER` — nowhere else, as a Depot file is
   not placed in a Memory. It holds no children, no blob, no cover, and is the root of no share (`ShareRoots` names no role
   for it), so nobody shares a bookmark onward through veyq. Its name is its own, chosen when it is created, unique among
   its siblings like any node's.
3. **The link is sealed in the node's meta, and nothing of it is in the clear.** The meta (`node-meta`, under the node's
   own `K_node`, which is wrapped under its parent's key like every node's — [ADR 0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md))
   carries `bookmark: { link, password? }`: the link normalized to `<origin>/s/<shareId>#k=<key>` and the share's
   password where one was given, padded to the next 512 characters so the meta's size says nothing of the host's length
   or of a password. **The operator learns that a node of this kind exists**, where it sits in the tree, when it was
   written, and nothing of the link: not the instance, not the share id, not whether it has a password. A rotation
   re-seals the meta under the node's new key like any node's ([ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md)
   point 2); the bookmark stores no key of its own beside it.
4. **The link is checked when it is pasted, never fetched.** The create dialog takes a link whose path is `/s/<shareId>`
   and whose fragment carries a `k=` that decodes to a 32-byte key; `https`, or `http` only for a loopback or
   `*.localhost` host (a development instance); no user name or password in the URL; anything else in the fragment
   (`#moderation`, `&report`) and any query are dropped. Nothing is resolved at create time — a foreign instance is out of
   `connect-src`, and a request would tell that operator, and this one, who keeps the link. So a bookmark of a link already
   closed is created, and says so when it is opened.
5. **Opening it opens the share page as the link would.** A link of this instance's own origin opens in the same tab
   (the router's navigation to `/s/<shareId>`), and its password, where the bookmark holds one, is handed to the share
   page in memory and entered there once; a wrong one leaves the page's own password form. A link of any other origin
   opens in a new tab (`noopener`, `noreferrer`); its password never travels in a URL, so before the tab opens the
   member is shown it with a copy button. A link revoked, expired or re-keyed away opens the share page's *unavailable*
   state; nothing in the space changes, and the member removes the bookmark like any node.
6. **Reporting a bookmark reports the share, to the share's own operator.** The bookmark's *Report* opens the link with
   one more fragment parameter, `&report`, and the share page — of this instance or another — opens its own report dialog
   for what the link shows, as that link's guest would ([ADR 0093](0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
   § 1): the root of a `READ` / `READ_WRITE` link, the link itself for an upload link. The report goes to whoever
   moderates that share — this instance's moderators for one of its own links, the foreign operator's for a foreign one —
   and nothing of the share is relayed to this instance. An instance that predates the parameter ignores it (the fragment
   parser reads only `k=`), and the member reports through that page's own action. This instance's moderators **never
   receive a bookmark's key**: wherever core would hand over a node's key for a report — the row, the header, a selection,
   an enclosing node offered above the cap — a bookmark is reported by its **name** alone (`NAME`, ADR 0093 § 9), the one
   thing of it that is content on this instance, and the bookmark's menu offers that as *Report name*. The server refuses
   a `NODE` report item of this kind (`422 report_target_kind`), so a client that would send the key is refused.
7. **A link guest never sees a bookmark.** A member's received link is not the space's content: a public link of a folder
   that holds one does not serve it — not its entry, its name, its wrapped key or its meta — and a moderator's review of a
   reported folder, which reads the same subtree, does not either. A `READ_WRITE` guest moves or renames no bookmark (the
   folder's guest rules refuse it for this kind), so a guest who learned its id acts on nothing it is not shown.
8. **Download and copy leave a bookmark out, and say so.** A download writes the link nowhere: a ZIP is a file the member
   hands on, and a bearer key inside it would leave the space with it. The kind declares no export content, so core's
   export leaves it out and lists it ([ADR 0082](0082-a-node-and-everything-under-it-exports-as-a-plaintext-zip-and-imports-under-the-importer.md)); a copy (decision 11 there) does
   the same and names it before anything is written. A member who wants it in another space pastes the link again there.

## Consequences

- **A new re-sharing path inside a space.** Every member of a group space who can open the bookmark's folder holds the
  link's key — and, with it, whatever the link opens, on whichever instance. Placing a bookmark in a group space is
  passing the link to its members, as forwarding the message would be; the create dialog says so before it saves. A
  member removed from the space keeps what they read, as with any content, and the space's rotation re-keys the
  bookmark node but cannot close a link on another instance: only the link's own owner closes it (a revoke there re-keys
  their subtree, [ADR 0114](0114-closing-a-link-re-keys-the-subtree-below-it.md)). [security-model](../crosscutting/security-model.md)
  § Sharing lists it.
- **Residual: a key above the bookmark opens it, with a dump.** The bookmark's key is wrapped under its folder's, so
  whoever holds a key above it — a link guest of an enclosing folder, a moderator of a reported enclosing folder — reads
  the link with a database dump, though no honest server serves it to them (decision 7). It is the same attacker that
  already reads every other node below that key; what is new is that the bookmark's link opens a share elsewhere, which
  that link's own operator cannot see is being reached. Accepted for the bookmark; the embedded successor revisits it.
- **The bookmark's name is reportable here, the share's content there.** A moderator of this instance judges the name a
  member gave the bookmark and nothing behind it; a foreign operator receives an ordinary guest report.
- **Rejected: a bookmark sealed under the account's key** (inbox #435's first idea). It would keep the link private to
  one person, but a node is what the owner asked for — placed beside the content it belongs to, shared with the space by
  being in it — and the account-level list remains open as #435.
- **Rejected: a report of the bookmark node to this instance's moderators.** It hands them `K_node` and so the link's
  key: they would read a foreign operator's content, which this instance neither hosts nor may act on.
- **Rejected: resolving the link when it is pasted** (to show the share's name or check it is live). It needs a
  `connect-src` to every veyq instance a member might paste, and tells two operators who keeps the link. The member names
  the bookmark.
- **Rejected for now: a password in the link's fragment.** It would let a foreign page fill the password in, but no
  share page reads one today, and a password in a URL lands in the browser's history and in every copy of the link.
