// A Pot's settings — its base currency and its own categories — as the `pot` sub-object of the node's sealed meta
// (ADR 0108 decision 2). `contracts/expenses/pot-meta.v1.schema.json` is the source of truth; `potMeta.test.ts` reads its
// bounds from the file. The envelope (`{ v, cover?, … }`, the `node-meta` seal) is core's (`core/node/nodeMeta.ts`); this
// file knows only its own key in it, so a write keeps every sibling (core's cover) as `writeNodeMeta` hands it.
//
// Participants are not here: they are records of the Pot's list, so a guest who adds one leaves a revision. Only a member
// writes this object (the rights' `settings`), whole, with the revision it read (ADR 0077).
import { CURRENCY, UUID } from './format'
import type { BaseCurrency } from './ledger'
import type { RawNodeMeta } from '../../../core/node/nodeMeta'

/** The key of this object inside the node-meta envelope. */
export const POT_META_KEY = 'pot'

export const MAX_OWN_CATEGORIES = 200
export const MAX_CATEGORY_NAME = 100

export type OwnCategory = { id: string; name: string }

/** The Pot's settings as read: its base currency, and its own categories in the order they were added. */
export type PotMeta = { base: BaseCurrency; categories: OwnCategory[] }

/** Why a `pot` object is not a v1 one: the first rule it breaks, by the schema's path. */
export class PotMetaInvalid extends Error {
  constructor(readonly path: string) {
    super(`pot_meta_invalid: ${path}`)
    this.name = 'PotMetaInvalid'
  }
}

const fail = (path: string): never => {
  throw new PotMetaInvalid(path)
}

/**
 * The `pot` object of an opened envelope held to the v1 schema, or [PotMetaInvalid]. Unknown fields are dropped; an id
 * named twice keeps its first entry (a writer renames a category in place, it never appends a second one).
 */
export function readPotMeta(raw: unknown): PotMeta {
  if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) return fail('')
  const o = raw as Record<string, unknown>
  if (o.v !== 1) fail('/v')
  const currency = typeof o.baseCurrency === 'string' && CURRENCY.test(o.baseCurrency) ? o.baseCurrency : fail('/baseCurrency')
  const minorUnits =
    typeof o.baseMinorUnits === 'number' && Number.isInteger(o.baseMinorUnits) && o.baseMinorUnits >= 0 && o.baseMinorUnits <= 4
      ? o.baseMinorUnits
      : fail('/baseMinorUnits')
  const categories: OwnCategory[] = []
  if (o.categories !== undefined) {
    if (!Array.isArray(o.categories) || o.categories.length > MAX_OWN_CATEGORIES) fail('/categories')
    ;(o.categories as unknown[]).forEach((c, i) => {
      const item = typeof c === 'object' && c !== null ? (c as Record<string, unknown>) : fail(`/categories/${i}`)
      const id = typeof item.id === 'string' && UUID.test(item.id) ? item.id : fail(`/categories/${i}/id`)
      const len = typeof item.name === 'string' ? [...item.name].length : -1
      const name = len >= 1 && len <= MAX_CATEGORY_NAME ? (item.name as string) : fail(`/categories/${i}/name`)
      if (!categories.some((x) => x.id === id)) categories.push({ id, name })
    })
  }
  return { base: { currency, minorUnits }, categories }
}

/** The Pot's settings from its opened envelope, or null when it carries none that read (a Pot no client set up, or one
 *  a later format wrote): such a Pot shows no balance and takes no money entry until a member sets a base currency. */
export function potMetaOf(envelope: RawNodeMeta): PotMeta | null {
  try {
    return readPotMeta(envelope[POT_META_KEY])
  } catch (e) {
    if (e instanceof PotMetaInvalid) return null
    throw e
  }
}

/** The `pot` object a writer stores for [meta] (held to the schema first: a writer never seals what its reader refuses). */
export function potMetaObject(meta: PotMeta): Record<string, unknown> {
  const out = {
    v: 1,
    baseCurrency: meta.base.currency,
    baseMinorUnits: meta.base.minorUnits,
    ...(meta.categories.length ? { categories: meta.categories.map((c) => ({ id: c.id, name: c.name })) } : {}),
  }
  readPotMeta(out)
  return out
}

/** A Pot's own category names, by id — what `categoryLabel` reads. */
export const ownCategoryNames = (meta: PotMeta | null): Map<string, string> => new Map((meta?.categories ?? []).map((c) => [c.id, c.name] as const))
