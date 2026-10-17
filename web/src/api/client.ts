// Thin factory over the contracts-generated clients. The whole app is same-origin, so the basePath is the
// in-memory `session.apiBase` ('' → relative `/api/...`). Keeping construction here means views/features
// depend on our typed surface, never on the generator's runtime details. Every spec generates into a client package
// of its own (`./generated` for setup, `./generated/<spec>` for the rest). This file builds **core's** clients
// only: the Memories application's (`contracts/api/memories/` → `./generated/media`, `./generated/item-favorites`) are
// built in `features/memory/api.ts` on the params exported below, because core imports no application client
// (ADR 0079; `validate_web_core_imports_no_applications.py`).
import { Configuration, SetupApi } from './generated'
import {
  Configuration as AuthConfiguration,
  LoginApi,
  PairingApi,
  RecoveryApi,
  SessionApi,
  TokenApi,
} from './generated/auth'
import {
  AccountApi,
  AccountIdentityPinsApi,
  AccountDeviceRegisterApi,
  AccountEmailApi,
  AccountEmailChangeApi,
  AccountPasswordApi,
  AccountProfileApi,
  AccountRotationApi,
  AccountWrapsApi,
  Configuration as AccountConfiguration,
} from './generated/account'
import { Configuration as TenantsConfiguration, TenantsApi } from './generated/tenants'
import { Configuration as ScopesConfiguration, ScopesApi } from './generated/scopes'
import { ContentApi, Configuration as ContentConfiguration } from './generated/content'
import { BlobApi, Configuration as BlobConfiguration } from './generated/blob'
import { TasksApi, Configuration as TasksConfiguration } from './generated/tasks'
import { ModerationApi, ReportsApi, Configuration as ModerationConfiguration } from './generated/moderation'
import { SharingApi, Configuration as SharingConfiguration } from './generated/sharing'
import { FavoritesApi, Configuration as FavoritesConfiguration } from './generated/favorites'
import { OperatorApi, Configuration as OperatorConfiguration } from './generated/operator'
import { session } from '../app/session'

export function setupApi(): SetupApi {
  return new SetupApi(new Configuration({ basePath: session.apiBase }))
}

// The one bearer-credential contract every authed client shares: same-origin basePath + the access token
// from the in-memory session. Each generated package has its own Configuration class but the same parameter
// shape, so this single source feeds them all — change the token source once, here. Exported for an
// application's own clients (`features/memory/api.ts`), which core does not import (ADR 0079).
export const bearerParams = () => ({ basePath: session.apiBase, accessToken: () => session.accessToken ?? '' })

// The token-less counterpart: same-origin basePath and no credential at all, so no Authorization header is sent.
// For the calls a caller makes without a session by design ([publicPairingApi], [publicSharesApi], and an
// application's guest operations).
export const publicParams = () => ({ basePath: session.apiBase })

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

/** Device pairing (ADR 0059) — the **authenticated** half. `delegate` and `seal` are the two calls that reach
 *  the caller's account: delegation is where the channel is bound to it at all, and sealing is gated on having
 *  delegated. Both therefore carry the bearer token of the trusted, unlocked device. */
export function pairingApi(): PairingApi {
  return new PairingApi(new AuthConfiguration(bearerParams()))
}

/** Device pairing — the **unauthenticated** half (`open`, `answer`, `collect`). No token, deliberately, and
 *  for two different reasons: the device being onboarded has no session to send, and pickup is unauthenticated
 *  by design (ADR 0059 §3) — the envelope is worthless without the ephemeral private key, so gating the fetch
 *  would prove nothing and would force that device to name an account before it has one. Same shape as
 *  [publicSharesApi]: the same generated class, constructed without the bearer credential, so no Authorization
 *  header is ever sent from a device that has no business identifying itself. */
export function publicPairingApi(): PairingApi {
  return new PairingApi(new AuthConfiguration(publicParams()))
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

export function accountDeviceRegisterApi(): AccountDeviceRegisterApi {
  return new AccountDeviceRegisterApi(new AccountConfiguration(bearerParams()))
}

/** The caller's own address and its confirmation (ADR 0074) — session-authenticated, unlock not required. */
export function accountEmailApi(): AccountEmailApi {
  return new AccountEmailApi(new AccountConfiguration(bearerParams()))
}

/** Changing the sign-in address (uc.account.change-email) — session-authenticated; the request carries its own proofs. */
export function accountEmailChangeApi(): AccountEmailChangeApi {
  return new AccountEmailChangeApi(new AccountConfiguration(bearerParams()))
}

export function accountRotationApi(): AccountRotationApi {
  return new AccountRotationApi(new AccountConfiguration(bearerParams()))
}

export function accountPasswordApi(): AccountPasswordApi {
  return new AccountPasswordApi(new AccountConfiguration(bearerParams()))
}

export function tenantsApi(): TenantsApi {
  return new TenantsApi(new TenantsConfiguration(bearerParams()))
}

export function scopesApi(): ScopesApi {
  return new ScopesApi(new ScopesConfiguration(bearerParams()))
}

// The member's node pins (ADR 0068, core's): one sealed blob per (user, scope). No guest route exists.
export function favoritesApi(): FavoritesApi {
  return new FavoritesApi(new FavoritesConfiguration(bearerParams()))
}

export function contentApi(): ContentApi {
  return new ContentApi(new ContentConfiguration(bearerParams()))
}

// The platform's generic blob transfer (core's `blob` tag, ADR 0079): start an upload, sign each next window,
// presign downloads. Completing an upload is the application's (Memories' `mediaApi().completeMedia`, built in
// `features/memory/api.ts`) - or core's, for a node's cover (`contentApi().setNodeCover`, inbox #411).
export function blobApi(): BlobApi {
  return new BlobApi(new BlobConfiguration(bearerParams()))
}

export function tasksApi(): TasksApi {
  return new TasksApi(new TasksConfiguration(bearerParams()))
}

/** The moderator's operations (uc.moderation.serve-as-moderator): the role and, where an operation declares it, the key
 *  proof are the server's (`ModerationProofGate`); the proof headers come from `features/moderation/proof.ts`. */
export function moderationApi(): ModerationApi {
  return new ModerationApi(new ModerationConfiguration(bearerParams()))
}

/** The published moderation key a reporter seals to (`getModerationKey`, ADR 0099 § 2): read before any sign-in, by a
 *  member and by a guest of a link alike, so token-less — a guest's page sends no Authorization header. */
export function publicModerationApi(): ModerationApi {
  return new ModerationApi(new ModerationConfiguration(publicParams()))
}

/** A member's report of what their space holds (uc.moderation.report). A guest reports through `publicSharesApi`. */
export function reportsApi(): ReportsApi {
  return new ReportsApi(new ModerationConfiguration(bearerParams()))
}

// Public viewer shares (cr-0100). The **curator** ops (create/list/revoke) are bearer-authed like the rest.
// The **guest** ops (resolve + gated blob GET) are unauthenticated — [publicSharesApi] carries NO token (the
// anonymous /s/ viewer has no session), so no Authorization header is ever sent from the no-account page.
export function sharesApi(): SharingApi {
  return new SharingApi(new SharingConfiguration(bearerParams()))
}

export function publicSharesApi(): SharingApi {
  return new SharingApi(new SharingConfiguration(publicParams()))
}

// Who runs this instance (ADR 0093 § 11, ADR 0035 decision 7): the operator's published values, read before sign-in on
// the login and share pages. Token-less like the guest reads: nobody needs a session to learn who runs a server.
export function publicOperatorApi(): OperatorApi {
  return new OperatorApi(new OperatorConfiguration(publicParams()))
}
