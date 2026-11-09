// The documents that live in no space (ADR 0125 decision 9): the members of this tenant and the settings sections. Both are
// made from what the client already holds - the tenant roster the directory read (`core/people/directory.ts`) and the
// settings list with its gates (`features/settings/sections.ts`, the one the rail reads) - so nothing new is fetched or
// sent, and a section the rail hides is never a result. Pure: the caller hands in how a result opens.
import type { Person } from '../people/directory'
import type { SettingsCategory, SettingsSection } from '../../features/settings/sections'
import { PERSON_DOCUMENT, SETTINGS_DOCUMENT, searchDocument, type SearchDocument } from './model'

/** One document per member: named as the directory names them, found by their `~username` too, opened by `open`. */
export function personDocuments(people: readonly Person[], open: (userId: string) => void): SearchDocument[] {
  return people.map((p) =>
    searchDocument({
      scopeId: '', kind: PERSON_DOCUMENT, id: p.userId, nodeId: p.userId, revision: '', name: p.displayName, path: [], facets: {},
      aliases: [`~${p.username}`], subtitle: `~${p.username}`, open: () => open(p.userId),
    }),
  )
}

/** One document per section offered now, found by its label and by its words ("E-Mail", "Kennwort"), its heading as its
 *  second line, opened by `open` with the section's id. */
export function settingsDocuments(
  offered: readonly { section: SettingsSection; category: SettingsCategory }[], open: (sectionId: string) => void,
): SearchDocument[] {
  return offered.map(({ section, category }) =>
    searchDocument({
      scopeId: '', kind: SETTINGS_DOCUMENT, id: section.id, nodeId: section.id, revision: '', name: section.label, path: [],
      facets: {}, keywords: section.words ?? [], subtitle: category.label, icon: section.icon, open: () => open(section.id),
    }),
  )
}
