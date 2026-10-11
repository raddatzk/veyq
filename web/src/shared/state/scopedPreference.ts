// A stable client setting that belongs to WHO looks (inbox #408): the level view's tab and the view mode. Persisted in
// `localStorage`, no secrets, but never one value for the whole browser — that made a share guest's tab flip a member's
// stored choice and two accounts on one browser share a setting. The storage key carries the audience:
//
// - an account:  `<base>:u:<userId>` — the signed-in account's opaque id (`app/account`, set by every unlock path), so
//   two accounts on one browser keep two settings. Until the id is known (before the key material is read) the value
//   lives in memory only: nothing is written under a key that does not name its owner.
// - a share guest: `<base>:guest` — one setting for every share page in the browser (a guest has no account; not
//   per share: a link is not an identity, and a guest wants the same look on the next one). The share page declares it
//   (`setPreferenceAudience('guest')`, `views/ShareView.vue`).
//
// The old un-keyed `<base>` is not read: it was every role's at once. A one-time reset to the defaults is cheaper than
// guessing whose it was. The setting stays per BROWSER — no server sync: a preference that follows the account would
// have to be stored sealed per user, which is a store of its own (proposed, not built).
//
// Every storage access is guarded (blocked storage = an in-memory setting), and the VALUE a level shows is the level's
// question: an invalid stored token is read as the default and never rewritten here.
import { computed, reactive, ref, type Ref } from 'vue'
import { account } from '../../app/account'

export type PreferenceAudience = 'account' | 'guest'
const audience = ref<PreferenceAudience>('account')

/** Declare who this document's pages are for: a share page is a guest's. Reset to `'account'` when it goes away. */
export function setPreferenceAudience(who: PreferenceAudience): void {
  audience.value = who
}

/** The storage key of `base` for who looks now, or null while there is no owner to name (memory only). */
export function preferenceStorageKey(base: string): string | null {
  if (audience.value === 'guest') return `${base}:guest`
  return account.userId ? `${base}:u:${account.userId}` : null
}

function storage(): Storage | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage
  } catch {
    return null // storage disabled (private mode / blocked) → in-memory default only
  }
}

/** One setting `base`: `parse` turns a stored string (or null) into the value, `fallback` is the default. */
export function createScopedPreference<T extends string>(base: string, parse: (raw: string | null) => T | undefined, fallback: T) {
  // One ref per storage key (null = memory only), read from storage the first time that owner is looked at.
  const cells = reactive(new Map<string, Ref<T>>())
  const MEMORY = ''
  const cellFor = (key: string | null): Ref<T> => {
    const id = key ?? MEMORY
    let cell = cells.get(id)
    if (!cell) {
      let stored: string | null = null
      try {
        stored = key ? (storage()?.getItem(key) ?? null) : null
      } catch {
        stored = null
      }
      cell = ref(parse(stored) ?? fallback) as Ref<T>
      cells.set(id, cell)
    }
    return cell
  }
  const value = computed<T>(() => cellFor(preferenceStorageKey(base)).value)
  function set(next: T): void {
    const key = preferenceStorageKey(base)
    cellFor(key).value = next
    try {
      if (key) storage()?.setItem(key, next)
    } catch {
      // quota / blocked: the choice lives in memory only
    }
  }
  return { value, set }
}
