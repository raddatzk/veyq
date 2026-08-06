// Deep-link survival across (re)authentication (cr-0031). When the router bounces a locked/anonymous visitor
// off an in-app route to /login, it remembers where they were headed — including the #fragment (`#~me/…`) —
// so the login view can restore it after unlock. Without this, a bookmarked deep link would always land on the
// personal root. In-memory only; the fragment never reaches the server regardless.
let pending: string | null = null

/** Remember an intended in-app destination (full path incl. #fragment). The bare root is not worth keeping. */
export function rememberRoute(fullPath: string): void {
  if (fullPath && fullPath !== '/') pending = fullPath
}

/** Consume the remembered destination (once), or null if there is none. */
export function takeRoute(): string | null {
  const p = pending
  pending = null
  return p
}
