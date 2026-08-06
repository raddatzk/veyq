// Thin factory over the contracts-generated clients. The whole app is same-origin, so the basePath is the
// in-memory `session.apiBase` ('' → relative `/api/...`). Keeping construction here means views/features
// depend on our typed surface, never on the generator's runtime details. The setup, auth and account specs
// generate into separate client packages (`./generated`, `./generated/auth`, `./generated/account`).
import { Configuration, SetupApi } from './generated'
import {
  Configuration as AuthConfiguration,
  LoginApi,
  RecoveryApi,
  SessionApi,
  TokenApi,
} from './generated/auth'
import {
  AccountApi,
  AccountIdentityPinsApi,
  AccountProfileApi,
  AccountWrapsApi,
  Configuration as AccountConfiguration,
} from './generated/account'
import { Configuration as TenantsConfiguration, TenantsApi } from './generated/tenants'
import { Configuration as ScopesConfiguration, ScopesApi } from './generated/scopes'
import { ContentApi, FavoritesApi, Configuration as ContentConfiguration } from './generated/content'
import { MediaApi, BucketsApi, Configuration as MediaConfiguration } from './generated/media'
import { TasksApi, Configuration as TasksConfiguration } from './generated/tasks'
import { SharingApi, Configuration as SharingConfiguration } from './generated/sharing'
import { session } from '../app/session'

export function setupApi(): SetupApi {
  return new SetupApi(new Configuration({ basePath: session.apiBase }))
}

// The one bearer-credential contract every authed client shares: same-origin basePath + the access token
// from the in-memory session. Each generated package has its own Configuration class but the same parameter
// shape, so this single source feeds them all — change the token source once, here.
const bearerParams = () => ({ basePath: session.apiBase, accessToken: () => session.accessToken ?? '' })

export function loginApi(): LoginApi {
  return new LoginApi(new AuthConfiguration(bearerParams()))
}

export function tokenApi(): TokenApi {
  return new TokenApi(new AuthConfiguration(bearerParams()))
}

export function sessionApi(): SessionApi {
  return new SessionApi(new AuthConfiguration(bearerParams()))
}

/** Password recovery (ADR 0054). Every call here is unauthenticated by necessity — the caller has forgotten
 *  the password that would get them a session — so the bearer params only supply the basePath. */
export function recoveryApi(): RecoveryApi {
  return new RecoveryApi(new AuthConfiguration(bearerParams()))
}

export function accountApi(): AccountApi {
  return new AccountApi(new AccountConfiguration(bearerParams()))
}

export function accountWrapsApi(): AccountWrapsApi {
  return new AccountWrapsApi(new AccountConfiguration(bearerParams()))
}

export function accountProfileApi(): AccountProfileApi {
  return new AccountProfileApi(new AccountConfiguration(bearerParams()))
}

export function accountIdentityPinsApi(): AccountIdentityPinsApi {
  return new AccountIdentityPinsApi(new AccountConfiguration(bearerParams()))
}

export function tenantsApi(): TenantsApi {
  return new TenantsApi(new TenantsConfiguration(bearerParams()))
}

export function scopesApi(): ScopesApi {
  return new ScopesApi(new ScopesConfiguration(bearerParams()))
}

export function contentApi(): ContentApi {
  return new ContentApi(new ContentConfiguration(bearerParams()))
}

export function favoritesApi(): FavoritesApi {
  return new FavoritesApi(new ContentConfiguration(bearerParams()))
}

export function mediaApi(): MediaApi {
  return new MediaApi(new MediaConfiguration(bearerParams()))
}

// Uploader buckets (cr-0113) — the `buckets` tag on the media spec → its own BucketsApi, same media config/auth.
export function bucketsApi(): BucketsApi {
  return new BucketsApi(new MediaConfiguration(bearerParams()))
}

export function tasksApi(): TasksApi {
  return new TasksApi(new TasksConfiguration(bearerParams()))
}

// Public viewer shares (cr-0100). The **curator** ops (create/list/revoke) are bearer-authed like the rest.
// The **guest** ops (resolve + gated blob GET) are unauthenticated — [publicSharesApi] carries NO token (the
// anonymous /s/ viewer has no session), so no Authorization header is ever sent from the no-account page.
export function sharesApi(): SharingApi {
  return new SharingApi(new SharingConfiguration(bearerParams()))
}

export function publicSharesApi(): SharingApi {
  return new SharingApi(new SharingConfiguration({ basePath: session.apiBase }))
}
