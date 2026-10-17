// Thin factory over the contracts-generated clients — `admin-web`'s own, narrower than `web/`'s: four specs
// (setup, auth, tenants, instance) and nothing else, because this surface does exactly four things (bootstrap
// an instance, sign a Server Owner in, administer tenants, read the release). No content, media, sharing or
// scopes client exists here, and that absence is the point: `admin-web` cannot reach user content even by
// mistake (ADR 0065 — a pure Admin-API client).
//
// The whole app is same-origin: nginx (or the dev proxy) serves the static assets and reverse-proxies `/api/*`
// to the backend from THIS origin, so `basePath` is the in-memory `session.apiBase` ('' → relative `/api/...`)
// and the refresh cookie stays same-origin wherever the container is reached from.
import { Configuration, SetupApi } from './generated'
import { Configuration as AuthConfiguration, LoginApi, SessionApi } from './generated/auth'
import { Configuration as TenantsConfiguration, TenantsApi } from './generated/tenants'
import { Configuration as InstanceConfiguration, InstanceApi, InstanceModerationApi } from './generated/instance'
import { session } from '../app/session'
import { refreshOnUnauthorized } from '../app/sessionRefresh'

export function setupApi(): SetupApi {
  return new SetupApi(new Configuration({ basePath: session.apiBase }))
}

// The one bearer-credential contract every authed client shares: same-origin basePath, the access token from the
// in-memory session, and the retry-on-401 that rotates an expired token once (inbox #449, `app/sessionRefresh.ts`).
// Each generated package has its own Configuration class but the same parameter shape. Every factory below builds
// from this, which `client.sweep.test.ts` holds; the rotation itself is not one of them (it lives beside the
// middleware, without it).
const bearerParams = () => ({
  basePath: session.apiBase,
  accessToken: () => session.accessToken ?? '',
  middleware: [refreshOnUnauthorized],
})

export function loginApi(): LoginApi {
  return new LoginApi(new AuthConfiguration(bearerParams()))
}

export function sessionApi(): SessionApi {
  return new SessionApi(new AuthConfiguration(bearerParams()))
}

export function tenantsApi(): TenantsApi {
  return new TenantsApi(new TenantsConfiguration(bearerParams()))
}

// Server-Owner operational reads about the running instance (ADR 0057). Bearer-authed by necessity, not by
// convention: the release version is deliberately absent from the pre-auth surface.
export function instanceApi(): InstanceApi {
  return new InstanceApi(new InstanceConfiguration(bearerParams()))
}

// The moderation roster and the instance moderation key's epochs (ADR 0099) — the `instance-moderation` tag of the same
// instance spec, so admin-web still has four specs: a fifth client CLASS, not a fifth contract.
export function instanceModerationApi(): InstanceModerationApi {
  return new InstanceModerationApi(new InstanceConfiguration(bearerParams()))
}
