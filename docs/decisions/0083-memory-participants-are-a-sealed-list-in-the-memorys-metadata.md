# ADR 0083 — Memory participants are a sealed list in the Memory's metadata

- Status: Accepted
- Date: 2026-10-13

## Context

Inbox #20 ("People & participants") asks that a Memory can say **who was there** — the people of a trip, a wedding —
and that a person can be opened to see the Memories they took part in. Two things make it a design question rather than
a field: the server is **operator-blind** (a relation "person P is on Memory M" is exactly the co-participation graph the
operator must not learn), and a **share is the member view** with rights removed ([inbox #401](../inbox.md), the owner's
principle of 2026-10-09), which raises what a guest may see of it.

A Memory already has the right home for sealed per-Memory facts: the `wrappedMeta` blob under `K_mem` (purpose
`node-meta`) that carries its date, place and cover, written with a compare-and-set on `metaVersion`
([ADR 0077](0077-a-write-carries-the-version-it-read.md), [en.memory.node-meta](../features/memory/enablers/en.memory.node-meta.md)).

## Decision

Decided by the owner, 2026-10-13. The sealed variant of decision 2 is the owner's choice after the leak was weighed; see
the first Consequence for what it costs.

**1. Who can be a participant.** Anyone **in the scope** of the Memory: tenant members for a Memory in the tenant scope,
group members for one in a group scope. A Memory in a personal scope has no participants feature (one member only). The
**client** validates the pick against the scope's member directory (`listScopeMembers`); the **server cannot enforce
it**, because the list is sealed.

**2. Storage: a sealed `participants` sub-object in the Memory's `wrappedMeta`.** It holds **user ids only**, sits next
to `date`, `location` and `cover` under `K_mem` (purpose `node-meta`), and is written through the same
compare-and-set write as the rest (`writeNodeMeta`, stating the `metaVersion` it read). **No server-visible relation**:
no `veyq_node_participant` table. Such a table would hand the operator who was on which trip.

**3. Leaving the scope does not remove a participant.** The id stays; it renders greyed as "former member" with **no
link**. Nothing prunes it automatically — the list is the Memory's record of who was there, not a membership mirror.

**4. Guests do not see participants.** The `participants` sub-object is **stripped from every meta re-sealed for a
share** (the share path in `memories/share.ts` and `core/sharing/guestAccess.ts`), and **no name snapshot** is stored
for a share. This is an exception the owner chose to the "share = member view" principle: rights only remove functions,
but a participant list is a statement about *other members*, who agreed to be in the space, not to be named to a link
holder. A per-share opt-in is a possible later decision, **not made now**. The header component is the same for a guest
and a member; whether it shows the participants is a capability flag in `memoryCapabilities` (`participants`), **fail
closed** like every other right there.

**5. A participant opens a member profile — members only.** Clicking a participant opens a profile page at the fragment
**`#/people/<userId>`**, never reachable from a share. It is routed in `web/src/core/node/location.ts` (`parseHash` /
`buildHash`, the one place `validate_web_fragment_paths_via_location.py` allows), and `people` is added to
`RESERVED_ROUTE_SEGMENTS` in `core/applications/registry.ts` so a folder cannot be called `people` at the tenant root.
Not `~people` (the `~` prefix is the personal-scope token), and not a history-mode path: those reach the server's access
logs, a fragment does not.
*To correct:* the comment on `isPersonalScope` in `location.ts` says a foreign `~username` is "the later profile view";
with this decision it is not — a foreign `~username` stays a personal-scope token and the profile is `#/people/<id>`.

**6. Profile content.** The **display name** (the sealed real name, falling back to the username — the rule in decision
9), an **avatar** (a letter only for now; a user avatar is its own later slice), and below them **every Memory in which
this person is a participant and the viewer has access**. The list is computed **in the client**: a lazily built
in-memory index `Map<userId, Set<memoryRef>>` over the tenant scope and every group scope the viewer is in
(`fetchTreeRows` with ETag / `If-None-Match` revalidation, [ADR 0081](0081-a-scopes-tree-revision-is-the-level-views-change-signal.md)),
unsealing each Memory's meta. The **personal scope is excluded**. Access is enforced for free: only Memories the viewer
can list are ever read. A **size cap** stops the walk and the page says honestly that the list is **partial**.

**7. Uploader attribution is independent of participants.** The photo's detail (`MediaLightbox`) shows the **uploader's
name**. For a `user:` owner key the name **links to the profile** (members only); for a `share:` key, and in any share
view, it is shown **without a link**. A non-participant uploader is still shown. What a guest sees today, exactly:
guests get **no uploader names at all** ([inbox #190](../inbox.md): the public resolve serves no name), so in a share view
the uploader of a member-uploaded photo stays **unresolved** — nothing is shown, not a placeholder. A guest-uploaded
photo's `share:` key resolves for members only, as a non-clickable name. The bucket tab keeps usernames until it is aligned
with the display-name rule.

**8. Files is out of the first version.** `files.openapi.yaml` has no uploader field; showing an uploader on a file needs
a contract and a backend field first (a guest-uploaded file then shows a non-clickable name). That is a separate slice.
The first version is **Memories only**.

**9. Display-name rule.** The **real name** when it opens, else the **username**.

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
- **Follow-up slices:** a user avatar (contract, backend, upload); an uploader field on Files nodes (contract, backend).
- **Rejected:** a `veyq_node_participant` table (the leak above); a name snapshot on shares (stale names, and it hands
  a link holder people's names); a `~people` or history-mode route (collides with the personal-scope token, or reaches
  the access log).
