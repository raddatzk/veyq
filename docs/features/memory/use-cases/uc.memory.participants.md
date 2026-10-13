# UC: Memory participants — who was there, and a profile that lists the Memories of a person

- **ID:** uc.memory.participants
- **Primary actor:** A **member** with an unlocked vault who has a Memory open (to name who was there) or opens a
  person's profile (to see their Memories).
- **Goal:** Record, on a Memory, **who took part**, show it in the Memory's header, and let a member open a person to see
  every Memory of theirs that the member may open — without the server learning who was on which Memory.
- **Preconditions:** The Memory's `K_mem` is held and its `wrappedMeta` exists
  ([en.memory.node-meta](../enablers/en.memory.node-meta.md)); the Memory lives in a tenant or group scope (a personal
  scope has no participants); the scope's member directory is readable (`listScopeMembers`).
- **Postconditions (success):** The Memory's sealed `wrappedMeta.participants` holds the chosen user ids, one version
  later; the header shows them; a profile page lists the Memories its person is on. The server stored one opaque blob and
  nothing else ([ADR 0083](../../../decisions/0083-memory-participants-are-a-sealed-list-in-the-memorys-metadata.md)).

**Status: built (web only, no backend or contract change).** The avatar is the member's profile picture when they have one, else the letter ([uc.spaces.set-profile-picture](../../spaces/use-cases/uc.spaces.set-profile-picture.md), [inbox #414](../../../inbox.md)). Not built:
the uploader on **Files** ([inbox #415](../../../inbox.md)), a per-share opt-in for guests. The profile's cover tiles load for the
Memories of the scopes read in that visit only (no cover cache across scope switches); the index is bounded at
500 Memories per scope and says so when it is partial.

## Main flow — name the participants

1. With a Memory open, a member chooses **Participants** in the header (a capability, `memoryCapabilities`
   `participants`, true for a member only and fail-closed).
2. The dialog lists the members of the Memory's scope — tenant members for a tenant Memory, group members for a group
   Memory — from `listScopeMembers`, each by display name (real name when it opens, else username).
3. The member ticks and unticks; the client accepts only ids the directory returned (the server cannot check: the list
   is sealed).
4. On save the client writes the `participants` sub-object (user ids only) into the Memory's `wrappedMeta` under `K_mem`
   with the node-meta compare-and-set, stating the `metaVersion` it read.
5. The header re-renders with the participants as overlapping avatars (the member's profile picture, else the letter): up to three, and past three the third slot is a "+N" (two avatars and "+2" for four people). Hovering the
   group, focusing it by keyboard or tapping it opens a popover with everyone — avatar and display name — and Escape
   closes it.

## Main flow — open a person

1. A member clicks a participant in the popover (or an uploader's name on a photo that a member uploaded). The location becomes
   `#people/<userId>` (the reader also takes the spelling `#/people/<userId>`; `location.ts` builds the first). The fragment is token-less:
   it opens the tenant scope's shell, whichever scope the Memory was in.
2. The profile shows the **display name**, an **avatar** (the profile picture, else the letter), and a list of **Memories** in which this person is a
   participant and the viewer has access. The list is drawn with core's level items (a larger cover, the name, the kind, the Memory's date and place
   where it has them, and the group's name) as rows or as tiles; the same rows/tiles switch as the folder view sits beside the heading, and the
   choice is the one per-user setting the folder view uses. There is no map here ([inbox #417](../../../inbox.md)).
3. The list comes from a lazily built, in-memory index `Map<userId, Set<memoryRef>>`: the client lists the tenant scope
   and every group scope the viewer is in (`fetchTreeRows`, revalidated by ETag, ADR 0081), and unseals each Memory's
   meta. The personal scope is not read.
4. A Memory the viewer cannot list is never read, so it never shows — access holds without a further check. A size cap
   ends the walk early and the page says the list is **partial**.

## Alternate / exception flows

- **A1 — a participant left the scope:** the id stays and renders greyed as "former member", no link, no auto-prune. A
  member may remove it by hand in the dialog.
- **A2 — a concurrent meta write landed first** (`409 stale_version`): the client re-reads the blob and **merges** (the union
  of both lists; an id the editing person removed stays removed) through its own edit function, not the same-field
  conflict of date and place ([uc.memory.concurrent-curation](uc.memory.concurrent-curation.md)).
- **A3 — the meta does not open:** the participants are not shown and the blob is never rebuilt from empty.
- **A4 — an id the directory does not know:** treated as a former member.
- **E1 — a guest:** the interface shows a guest **no participants**: the capability is false for every share role, so
  there is no section, no control and no profile link, and a guest never reaches `#/people/<userId>`. This is an
  interface boundary, **not a cryptographic one**: a share serves the node's `wrappedMeta` unchanged and the guest holds
  `K_mem`, so a link holder with developer tools could read the **count and the opaque ids** — but no names (the list
  holds ids only, no snapshot is stored, a guest has no member directory, [inbox #190](../../../inbox.md)). Accepted
  residual, comparable to [ADR 0030](../../../decisions/0030-contributor-public-share.md) point 8; it must be re-decided
  if participants ever carry names. A per-share opt-in is a possible future decision, not now.
- **A5 — the list is full:** at 100 participants (`wrappedMeta` is limited to 8192 characters) the dialog adds no more.
- **E2 — the scope is pending deletion:** the dialog is not offered.
- **E3 — the index is too large:** the profile lists what it found and says it is partial.

## Uploader attribution in a photo's detail

- In `MediaLightbox` the photo's **uploader's name** is shown. For a `user:` owner key it links to that person's profile
  (members only). For a `share:` key, and in **any** share view, the name is shown **without** a link.
- A guest today gets **no uploader names** ([inbox #190](../../../inbox.md)): in a share view, a member-uploaded photo's
  uploader stays unresolved — nothing is shown. A guest-uploaded photo shows a non-clickable name to members.
- The uploader is independent of the participant list: a non-participant uploader is still shown. The bucket tab keeps
  usernames until it is aligned with the display-name rule.
- **Files are not in the first version:** `files.openapi.yaml` has no uploader field (inbox follow-up).

## Quality notes

- **No server-visible relation, by design.** A `veyq_node_participant` table would give the operator a co-participation
  graph (who was on which trip). The cost is stated in the ADR: no server-side notifications and no server-side query.
- **Guests are an exception to "share = member view".** Rights only remove functions everywhere else; here the
  interface withholds a section, because it points at other members (ids only; see E1 for the residual).
- **Display name rule:** real name when it opens, else username — the same in the dialog, the popover, the profile and the
  uploader name.
- **Routing:** the profile is a fragment route handled only in `core/node/location.ts`; `people` is a reserved route
  segment (an application cannot claim it, and a folder, file or drop box called "People" at a scope root is numbered `people-2`). A history-mode path would reach the server's access log.

## What this means for you / Was das für dich bedeutet

**EN —** You can say who was there: add the people of your space to a memory, and they show in its header as a row of avatars. Hover or tap them for the names, click a name
to see that person and every memory of theirs you can open. People who later leave the space stay listed, greyed out as
"former member". Only members see this; the page of someone who gets a memory through a link shows nobody. The server
never learns who was on which memory — the list is sealed with the memory. The list holds only anonymous ids, never names.

**DE —** Du kannst festhalten, wer dabei war: Füge Personen deines Bereichs zu einer Erinnerung hinzu, sie erscheinen in
deren Kopfzeile als Reihe von Avataren. Fahre darüber oder tippe darauf, um die Namen zu sehen; mit einem Klick auf einen Namen siehst du die Person und alle ihre Erinnerungen, die du öffnen darfst.
Wer den Bereich später verlässt, bleibt eingetragen und wird grau als „ehemaliges Mitglied“ gezeigt. Das sehen nur
Mitglieder; wer eine Erinnerung über einen Link bekommt, bekommt keine Personen angezeigt. Die Liste enthält nur anonyme IDs, nie Namen. Der Server erfährt nie, wer bei
welcher Erinnerung dabei war – die Liste ist mit der Erinnerung versiegelt.

## Where it is built (web)

- Model and write: `web/src/features/memory/memoryParticipants.ts` (`participants: { userIds }`, cap 100, the merging edit);
  `editParticipantsOf` in `core/node/nodeMeta.ts`; the capability `participants` in `capabilities.ts`; the door
  `NodeMetaDoor.setParticipants` in `source.ts`.
- Header and picker: `components/MemoryParticipants.vue`, `MemoryParticipantsDialog.vue`, `useMemoryParticipants.ts`; names
  from `core/people/directory.ts` (real name, else username; read from the tenant's member list).
- Profile: `core/people/PersonProfile.vue` (core's frame, sections claimed through `registry.claimPersonSection`), Memories'
  section `components/PersonMemories.vue` over `participantIndex.ts`; the route in `core/node/location.ts` and `views/MemoryHomeView.vue`.
- Uploader in the photo detail: `useUploaderNames.ts` and `MediaLightbox.vue`.

## Related

- Feature: [Memory](../_index.md)
- End-user view (wiki): [People in your Memories](../../../../wiki/en/people-in-your-memories.md)
- Enablers: [en.memory.node-meta](../enablers/en.memory.node-meta.md) (the sealed `wrappedMeta`)
- Use-cases: [uc.memory.concurrent-curation](uc.memory.concurrent-curation.md) (the versioned meta write),
  [uc.memory.buckets](uc.memory.buckets.md) (the uploader axis), [uc.memory.gallery](uc.memory.gallery.md) (the lightbox)
- Applications: [docs/apps/memories.md](../../../apps/memories.md) (the capability matrix)
- Decisions: [ADR 0083](../../../decisions/0083-memory-participants-are-a-sealed-list-in-the-memorys-metadata.md),
  [ADR 0077](../../../decisions/0077-a-write-carries-the-version-it-read.md) (the versioned write),
  [ADR 0081](../../../decisions/0081-a-scopes-tree-revision-is-the-level-views-change-signal.md) (revalidated tree reads)
