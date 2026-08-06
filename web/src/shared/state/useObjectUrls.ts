// object-URL lifecycle (web) — the one owner of browser blob object-URL lifecycle
// (`URL.createObjectURL` / `URL.revokeObjectURL`), extracted into a composable (cr-0148, audit #74/P3d).
// Decrypted media/cover bytes are shown as `blob:` object URLs; each MUST be revoked exactly once — on replace,
// on drop, on unmount — or the tab leaks the decrypted blob. Hand-rolling that dance at every call site was the
// bug surface this replaces: the URL is minted ONLY inside here, and the composable auto-revokes on unmount.
import { ref, onBeforeUnmount, type Ref } from 'vue'

/** A single owned object URL: `set` revokes the previous before minting the next; unmount revokes the last one. */
export function useObjectUrl(): {
  url: Ref<string | null>
  set: (blob: Blob | null) => void
  clear: () => void
} {
  const url = ref<string | null>(null)
  function clear() {
    if (url.value) URL.revokeObjectURL(url.value)
    url.value = null
  }
  function set(blob: Blob | null) {
    clear()
    if (blob) url.value = URL.createObjectURL(blob)
  }
  onBeforeUnmount(clear)
  return { url, set, clear }
}

/** A keyed collection of owned object URLs (e.g. a thumbnail map): `set` revokes a replaced key, `drop` revokes
 *  one, `clear` revokes all, unmount revokes all. Exposes a `Ref<Map<string,string>>` and reassigns it on every
 *  mutation, preserving the Map-churn reactivity consumers + templates read (`urls.value.get(id)`). */
export function useObjectUrls(): {
  urls: Ref<Map<string, string>>
  get: (key: string) => string | undefined
  set: (key: string, blob: Blob) => void
  adopt: (key: string, url: string) => void
  drop: (key: string) => void
  clear: () => void
} {
  const urls = ref<Map<string, string>>(new Map())
  const get = (key: string) => urls.value.get(key)
  // Store `url` under `key`, revoking whatever was there (unless it IS `url`), then churn the Map for reactivity.
  function put(key: string, url: string) {
    const prev = urls.value.get(key)
    if (prev && prev !== url) URL.revokeObjectURL(prev)
    urls.value.set(key, url)
    urls.value = new Map(urls.value)
  }
  const set = (key: string, blob: Blob) => put(key, URL.createObjectURL(blob))
  // Take ownership of an **externally-minted** URL (e.g. an optimistic-preview donation): same single-owner
  // lifecycle as `set` (revoke on replace/drop/clear/unmount), but for a URL the caller already created.
  const adopt = (key: string, url: string) => put(key, url)
  function drop(key: string) {
    const prev = urls.value.get(key)
    if (prev) URL.revokeObjectURL(prev)
    if (urls.value.delete(key)) urls.value = new Map(urls.value)
  }
  function clear() {
    for (const u of urls.value.values()) URL.revokeObjectURL(u)
    urls.value = new Map()
  }
  onBeforeUnmount(clear)
  return { urls, get, set, adopt, drop, clear }
}
