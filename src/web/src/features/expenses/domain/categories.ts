// What an entry's category reads as (docs/apps/expenses.md § The Pot): a default category is a stable id the client
// translates (`expenses.category.<id>`, this application's catalog), a Pot's own one is a uuid named in the Pot's sealed
// meta. The Pot's own are added and renamed, never removed, in the first cut — so an own id the meta does not name is a
// meta this client has not read yet, or one written by a later client, and reads as unknown rather than as a guess.
import { DEFAULT_CATEGORIES, type DefaultCategory } from './format'

export type CategoryLabel = { key: string } | { name: string } | { unknown: string }

const isDefault = (id: string): id is DefaultCategory => (DEFAULT_CATEGORIES as readonly string[]).includes(id)

/** The label of category [id]: a catalog key for a default one, the name the Pot's meta gives its own ([own]: id → name). */
export function categoryLabel(id: string, own: ReadonlyMap<string, string>): CategoryLabel {
  if (isDefault(id)) return { key: `expenses.category.${id}` }
  const name = own.get(id)
  return name === undefined ? { unknown: id } : { name }
}
