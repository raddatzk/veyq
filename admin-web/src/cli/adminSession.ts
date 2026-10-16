// The CLI's session: read the credential out of the environment, sign in, done. It is the same `login()` the
// admin UI calls — challenge → @veyq/crypto derivation → signature → session — because a second sign-in path
// would be a second place touching key material, which is the one thing ADR 0065 will not multiply.
//
// Two things about running that flow in Node, both measured rather than assumed (cr-admin-cli's bite test):
//   1. `@veyq/crypto/argonWorker` has no `Worker` global to find here and takes its synchronous fallback, so
//      the CLI needs no crypto path of its own. Measured on Node 23: ~1s per derivation, resolving correctly.
//   2. `@veyq/crypto` is consumed as TypeScript SOURCE with extensionless relative imports, so plain `node`
//      cannot import it even with type-stripping — only a bundler resolves it. That is why the CLI ships as
//      an esbuild bundle (`npm run build:cli`) and never as loose files.
//
// Fresh login per invocation, nothing written to disk (ADR 0065): no token cache, no refresh across runs, no
// "remember me". (Within one run the clients are the console's, so a run that outlives its 15-minute access token
// is rotated in memory by the same retry-on-401, `app/sessionRefresh.ts`.) The Argon2id cost (~1–3s) is paid on
// every call and is the accepted price of never holding a session that outlives the process. If that ever blocks a real workflow it is a new decision, not a local shortcut.
import { session } from '../app/session'
import { login } from '../features/auth/login'
import { CliError } from './output'

/** The backend's admin listener under its service name in `infra/docker-compose.prod.yml` — the same default
 *  `docker-entrypoint.d/30-veyq-backend-url.sh` gives nginx, read from the same variable so the two can never drift
 *  into disagreeing about one instance. Never the public port: it 404s every Server-Owner operation (ADR 0065). */
export const DEFAULT_BACKEND_URL = 'http://app:8090'

/**
 * Where the backend is. The CLI is a server-side process inside the admin container, so it calls the backend
 * DIRECTLY rather than looping through its own nginx. The `Host` it then sends resolves to no tenant, which is
 * correct: every Server-Owner endpoint is tenant-independent.
 */
export function backendUrl(env: Record<string, string | undefined>): string {
  return (env.BACKEND_URL || DEFAULT_BACKEND_URL).replace(/\/+$/, '')
}

/** Point the shared API clients at the backend and sign the Server Owner in. Throws {@link CliError} when the
 *  credential is absent — a missing password must fail loudly here, not as a 401 three seconds later. */
export async function signIn(env: Record<string, string | undefined>): Promise<void> {
  const email = env.VEYQ_ADMIN_EMAIL
  const password = env.VEYQ_ADMIN_PASSWORD
  if (!email) throw new CliError('VEYQ_ADMIN_EMAIL is not set')
  if (!password) throw new CliError('VEYQ_ADMIN_PASSWORD is not set')
  session.apiBase = backendUrl(env)
  await login(email, password)
}
