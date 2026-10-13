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

**Status: planned — nothing here is built.**

## Main flow — name the participants

1. With a Memory open, a member chooses **Participants** in the header (a capability, `memoryCapabilities`
   `participants`, true for a member only and fail-closed).
2. The dialog lists the members of the Memory's scope — tenant members for a tenant Memory, group members for a group
   Memory — from `listScopeMembers`, each by display name (real name when it opens, else username).
3. The member ticks and unticks; the client accepts only ids the directory returned (the server cannot check: the list
   is sealed).
4. On save the client writes the `participants` sub-object (user ids only) into the Memory's `wrappedMeta` under `K_mem`
   with the node-meta compare-and-set, stating the `metaVersion` it read.
5. The header re-renders with the participants, each a chip with the display name and a letter avatar.

## Main flow — open a person

1. A member clicks a participant chip (or an uploader's name on a photo that a member uploaded). The location becomes
   `#/people/<userId>`.
2. The profile shows the **display name**, a **letter avatar**, and a list of **Memories** in which this person is a
   participant and the viewer has access.
3. The list comes from a lazily built, in-memory index `Map<userId, Set<memoryRef>>`: the client lists the tenant scope
   and every group scope the viewer is in (`fetchTreeRows`, revalidated by ETag, ADR 0081), and unseals each Memory's
   meta. The personal scope is not read.
4. A Memory the viewer cannot list is never read, so it never shows — access holds without a further check. A size cap
   ends the walk early and the page says the list is **partial**.

## Alternate / exception flows

- **A1 — a participant left the scope:** the id stays and renders greyed as "former member", no link, no auto-prune. A
  member may remove it by hand in the dialog.
- **A2 — a concurrent meta write landed first** (`409 stale_version`): the client re-reads the blob and re-applies only
  its own participants change ([uc.memory.concurrent-curation](uc.memory.concurrent-curation.md)).
- **A3 — the meta does not open:** the participants are not shown and the blob is never rebuilt from empty.
- **A4 — an id the directory does not know:** treated as a former member.
- **E1 — a guest:** a guest sees **no participants**. The sub-object is stripped from the meta re-sealed for a share, no
  name snapshot is stored, and the capability is false for every share role. A per-share opt-in is a possible future
  decision, not now. A guest never reaches `#/people/<userId>`.
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
- **Guests are an exception to "share = member view".** Rights only remove functions everywhere else; here the data
  itself is withheld, because it names other members.
- **Display name rule:** real name when it opens, else username — the same in the dialog, the chip, the profile and the
  uploader name.
- **Routing:** the profile is a fragment route handled only in `core/node/location.ts`; `people` is a reserved route
  segment. A history-mode path would reach the server's access log.

## What this means for you / Was das für dich bedeutet

**EN —** You can say who was there: add the people of your space to a memory, and they show in its header. Click a name
to see that person and every memory of theirs you can open. People who later leave the space stay listed, greyed out as
"former member". Only members see this; someone who gets a memory through a link does not see who was in it. The server
never learns who was on which memory — the list is sealed with the memory.

**DE —** Du kannst festhalten, wer dabei war: Füge Personen deines Bereichs zu einer Erinnerung hinzu, sie erscheinen in
deren Kopfzeile. Mit einem Klick auf einen Namen siehst du die Person und alle ihre Erinnerungen, die du öffnen darfst.
Wer den Bereich später verlässt, bleibt eingetragen und wird grau als „ehemaliges Mitglied“ gezeigt. Das sehen nur
Mitglieder; wer eine Erinnerung über einen Link bekommt, sieht nicht, wer dabei war. Der Server erfährt nie, wer bei
welcher Erinnerung dabei war – die Liste ist mit der Erinnerung versiegelt.

## Related

- Feature: [Memory](../_index.md)
- Enablers: [en.memory.node-meta](../enablers/en.memory.node-meta.md) (the sealed `wrappedMeta`)
- Use-cases: [uc.memory.concurrent-curation](uc.memory.concurrent-curation.md) (the versioned meta write),
  [uc.memory.buckets](uc.memory.buckets.md) (the uploader axis), [uc.memory.gallery](uc.memory.gallery.md) (the lightbox)
- Applications: [docs/apps/memories.md](../../../apps/memories.md) (the capability matrix)
- Decisions: [ADR 0083](../../../decisions/0083-memory-participants-are-a-sealed-list-in-the-memorys-metadata.md),
  [ADR 0077](../../../decisions/0077-a-write-carries-the-version-it-read.md) (the versioned write),
  [ADR 0081](../../../decisions/0081-a-scopes-tree-revision-is-the-level-views-change-signal.md) (revalidated tree reads)
