// Where a share lives, as this device addresses it (ADR 0121 decision 5): its id, and the API base of the instance that
// serves it - `''` for this origin, whose share page and every call it makes stay exactly as they were, or another
// instance's origin (`https://photos.example.org`), whose link a bookmark's page shows in place. A bare id is this
// origin's share, so every caller that never knew of another instance keeps passing one; a guest's `GuestAccess` is
// itself a `ShareAt` (it carries `shareId` and `apiBase`), which is what a view hands every guest read and write.
//
// **What this device keeps of a share is keyed by both** ({@link deviceKey}): this origin's keys are byte-identical to
// before (`<prefix>:<id>`), so a guest's identity, its accepted terms and notes and its own uploads still read; another
// instance's carry its origin (`<prefix>@<origin>:<id>`). The character after the prefix tells the two apart - `:` here,
// `@` there - so no id this origin's route hands a share page, whatever it holds, reads another origin's entry.

/** A share as this device addresses it: a bare id (this origin), or the id with its instance's API base. */
export type ShareAt = string | { readonly shareId: string; readonly apiBase: string }

/** The API base of this origin: the generated clients' same-origin `basePath`. */
export const THIS_ORIGIN = ''

/** The share's id. */
export function shareIdOf(at: ShareAt): string {
  return typeof at === 'string' ? at : at.shareId
}

/** The API base of the share's instance (`''`: this origin). */
export function apiBaseOf(at: ShareAt): string {
  return typeof at === 'string' ? THIS_ORIGIN : at.apiBase
}

/** The key under which this device keeps something of `id` - a share's id, or a tenant's for the terms - on the instance
 *  at `apiBase` (see the header): this origin's unchanged, another's carrying its origin. */
export function originKey(prefix: string, apiBase: string, id: string): string {
  return apiBase === THIS_ORIGIN ? `${prefix}:${id}` : `${prefix}@${apiBase}:${id}`
}

/** The key under which this device keeps something of the share `at` ({@link originKey}). */
export function deviceKey(prefix: string, at: ShareAt): string {
  return originKey(prefix, apiBaseOf(at), shareIdOf(at))
}

/** The API base a bookmark's page reads a link of `origin` through, seen from a document at `here`: `''` for its own
 *  origin (same-origin calls, this origin's device keys), else that origin. */
export function apiBaseFor(origin: string, here: string): string {
  return origin === here ? THIS_ORIGIN : origin
}
