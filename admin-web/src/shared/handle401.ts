// End-the-session on an unauthorized response — the reaction half of `isUnauthorized` (cr-0155, audit #74/P3d),
// once, for every panel. Curried so it drops straight into a `useAsyncData`/`useAsyncAction` `onError`; inline
// `catch` sites call `handle401(router)(e)`.
//
// By the time a `401` reaches here the client has already rotated the token and retried (`app/sessionRefresh.ts`),
// so it means the session is over. It used to push `login` only, with the dead token still set, and the router's
// guard sent `login` straight back to `console` — the owner saw nothing (inbox #449). `endExpiredSession` drops
// the session before it navigates and has the sign-in say why.
import type { Router } from 'vue-router'
import { isUnauthorized } from './httpError'
import { endExpiredSession } from '../app/sessionEnd'

export function handle401(router: Router): (e: unknown) => void {
  return (e) => {
    if (isUnauthorized(e)) void endExpiredSession(router)
  }
}
