# ADR 0083 — Memory participants are a sealed list in the Memory's metadata

- Status: Accepted
- Built: yes
- Date: 2026-10-13
- Model: [Application: Memories](../apps/memories.md)

## Context

Inbox #20 ("People & participants") asks that a Memory can say **who was there** — the people of a trip, a wedding —
and that a person can be opened to see the Memories they took part in. Two things make it a design question rather than
a field: the server is **operator-blind** (a relation "person P is on Memory M" is exactly the co-participation graph the
operator must not learn), and a **share is the member view** with rights removed (the owner's principle,
[ADR 0079](0079-veyq-is-a-platform-memories-is-its-first-application.md) Consequences), which raises what a guest may
see of it.

A Memory already has the right home for sealed per-Memory facts: the `wrappedMeta` blob under a field key derived from `K_node` (purpose
`node-meta`) that carries its date, place and cover, written with a compare-and-set on `metaRevision`
([ADR 0077](0077-a-write-carries-the-version-it-read.md), [en.memory.node-meta](../features/memory/enablers/en.memory.node-meta.md)).

## Decision

The sealed variant of decision 2 is the owner's choice after the leak was weighed; the first Consequence says what it
costs.

**1. Who can be a participant.** Anyone **in the scope** of the Memory: tenant members for a Memory in the tenant scope,
group members for one in a group scope. A Memory in a personal scope has no participants feature (one member only). The
**client** validates the pick against the scope's member directory (`listScopeMembers`); the **server cannot enforce
it**, because the list is sealed.

**2. Storage: a sealed `participants` sub-object in the Memory's `wrappedMeta`.** It holds **user ids only**, sits next
to `date`, `location` and `cover` in that blob (purpose `node-meta`), and is written through the same
compare-and-set write as the rest (`writeNodeMeta`, stating the `metaRevision` it read). **No server-visible relation**:
no `veyq_node_participant` table. Such a table would hand the operator who was on which trip.

**3. Leaving the scope does not remove a participant.** The id stays; it renders greyed as "former member" with **no
link**. Nothing prunes it automatically — the list is the Memory's record of who was there, not a membership mirror.

**3a. Shape, cap and concurrent edits.** The sub-object is `participants: { userIds: string[] }`, user ids only, unique,
validated on read (non-strings, duplicates dropped). It is **capped at 100 ids**: `wrappedMeta` is limited to 8192
characters of base64 (about 6 KB sealed), and an id is about 40 bytes of JSON next to the date, place and cover. Unknown
sibling fields are preserved. A write is a read-modify-write through `writeNodeMeta`; because the list is a *set* that two
members may extend at once, it uses **its own edit function, not `editField`** (which throws `MetaConflictError` when the
same field moved and would drop a peer's addition). On `409 stale_revision` the client re-reads and **merges**: the union
of the stored and the editor's list, except that an id the person editing **removed** stays removed. This is the
reversible choice about storage too: the list stays in `wrappedMeta` rather than a separate column, because the profile
page lists Memories as level-view rows with cover, date and place and so needs the full meta anyway; moving it later is a
client-side migration.

**4. Guests do not see participants — in the interface, not cryptographically.** The header component is the same for
a guest and a member; whether it shows the participants is a right in `memoryRights` (`participants`),
**fail closed** like every other right there, so no share view for any role (`READ`, `READ_WRITE`, `WRITE_ONLY`, folder
or Memory link) renders the section, its controls or a profile link. This is an exception the owner chose to the
"share = member view" principle: rights only remove functions, but a participant list is a statement about *other
members*, who agreed to be in the space, not to be named to a link holder.
The exclusion is **not** cryptographic, because **no meta is re-sealed for a share**: a share serves the node's
`wrappedMeta` unchanged (`ShareNode.wrappedMeta`, [sharing.openapi.yaml](../../contracts/api/sharing.openapi.yaml)) and the
guest holds the node's `K_node` through the key chain, so a link holder can open the very blob a member writes. Making the
exclusion cryptographic would need a new AEAD purpose (a nested seal under a key guests lack) or a share-specific meta
on the server — both rejected for the first version.
**Accepted residual:** a link holder with developer tools can read the participant **count and the opaque user ids**
from the blob, **but no names**: the list holds ids only, and a guest has no member directory
(a link is served no name, [ADR 0030](0030-contributor-public-share.md) point 8). This is comparable to the residual already accepted in
[ADR 0030](0030-contributor-public-share.md) point 8, where a guest's device record of its own uploads is likewise a
client-side boundary. **No name snapshot is ever stored** — in the list or on a share. **If participants ever carry
names, snapshots or anything beyond opaque ids, this decision must be re-decided** (the residual then discloses people).
A per-share opt-in is a possible later decision, **not made now**.

**5. A participant opens a member profile — members only.** Clicking a participant opens a profile page at the fragment
**`#people/<userId>`**, never reachable from a share. `people` is a reserved route segment, so no application claims it
and no node at a scope root ever has the profile's path (the routing is
[uc.memory.participants](../features/memory/use-cases/uc.memory.participants.md)'s).
Not `~people` (the `~` prefix is the personal-scope token), and not a history-mode path: those reach the server's access
logs, a fragment does not.
A foreign `~username` stays a personal-scope token, never a profile
([ADR 0015](0015-operator-blind-readable-urls.md)).

**6. Profile content.** The **display name** (the sealed real name, falling back to the username — the rule in decision
9), an **avatar** (the member's picture, [ADR 0084](0084-a-members-profile-picture-is-a-sealed-attribute-of-their-tenant-profile.md), else a letter), and below them **every Memory in which
this person is a participant and the viewer has access**. The list is computed **in the client**: a lazily built
in-memory index from person to Memories over the tenant scope and every group scope the viewer is in (each level's
tree revalidated by its ETag, [ADR 0081](0081-a-scopes-tree-revision-is-the-level-views-change-signal.md)),
unsealing each Memory's meta. The **personal scope is excluded**. Access is enforced for free: only Memories the viewer
can list are ever read. A **size cap** stops the walk and the page says honestly that the list is **partial**.

**7. Uploader attribution is independent of participants.** The photo's detail in the lightbox shows the **uploader's
name**. For a `user:` owner key the name **links to the profile** (members only); for a `share:` key, and in any share
view, it is shown **without a link**. A non-participant uploader is still shown. Guests get **no uploader names at
all** (a link is served no name, ADR 0030 point 8), so in a share view the uploader of a member-uploaded photo stays
**unresolved** — nothing is shown, not a placeholder. A guest-uploaded photo's `share:` key resolves for members only, as
a non-clickable name.

**8. A file's page names its uploader.** Depot's file page names whoever uploaded the file's **current** content (a
replace moves it; who created the node is not recorded), to members only. Its mechanism — the attachment's
`uploader_user_id` / `share_identity_id`, the listing's `uploaderKey` and `uploaderName`, the guest's listing without
either — is [Depot](../apps/depot.md)'s, and the flow is [uc.depot.view-file](../features/depot/use-cases/uc.depot.view-file.md).
What is decided here:

- **A member's upload is recorded by the server**, as a user id on the attachment beside the guest identity, exactly
  one of the two set. This is a new row of the class Memories already discloses (a medium's `owner_key`,
  [ADR 0034](0034-media-uploader-buckets.md)), taken for the same reasons; what it discloses is a Consequence below. A
  sealed uploader inside the blob meta was rejected, because an uploader could claim anyone.
- **A member is named by decision 9's rule and links to the profile**; one who left is a "former member". A link's
  guest is named by the name it gave itself, without a link, and wherever a member reads a guest's name it is marked
  as a guest's ("Ben (guest)"), because a guest names itself freely and could otherwise pass for a member. Its sealed
  name reaches a member as a ciphertext the server already stores, so no new plaintext and no new relation.
- **A share guest's listing carries no uploader** (ADR 0030 point 8): it has an item schema of its own, so the
  member's uploader field cannot reach it by accident.

**9. Display-name rule.** A person is named by the one display rule of member identity
([en.spaces.member-identity](../features/spaces/enablers/en.spaces.member-identity.md) § The display name,
[ADR 0023](0023-per-tenant-member-identity.md)); participants, uploaders and the profile all use it.

## Consequences

- **No server-side notifications or queries on participation — by design.** "Tell Anna she was added", "which Memories
  is Anna on" as a server query, or a participant count in a list are impossible without the relation decision 2 refuses.
  Everything is computed by a client that holds the keys. This is the trade-off the owner accepted; the alternative
  (a visible table) is cheaper to build and leaks a co-participation graph, so it is rejected.
- **The profile's list costs a walk.** The first open of a profile unseals every reachable Memory's meta; the ETag
  revalidation keeps later opens cheap and the cap keeps the first bounded, at the price of a possibly partial list.
- **No cross-user consistency is enforced.** A sealed list can name a user who never was in the scope (a hostile or buggy
  client); the reader treats an id its directory does not know as a former member.
- **A participants write is a Memory-meta write**, so it is a member's right like date and place: not for a guest, not
  during a pending scope deletion.
- **The operator learns which member uploaded which file's current bytes** (decision 8), which it otherwise saw only
  during the upload. The same class Memories discloses through `owner_key`; never a name.
- **A file's uploader shows on its page only.** A listing's row names no uploader.
- **Rejected:** a `veyq_node_participant` table (the leak above); a name snapshot on shares (stale names, and it hands
  a link holder people's names); a `~people` or history-mode route (collides with the personal-scope token, or reaches
  the access log); a file's uploader sealed inside its blob meta (an uploader could claim anyone, decision 8).
