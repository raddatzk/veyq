// Thin factory over the contracts-generated clients. The whole app is same-origin, so the basePath is the
// in-memory `session.apiBase` ('' → relative `/api/...`). Keeping construction here means views/features
// depend on our typed surface, never on the generator's runtime details. Every spec generates into a client package
// of its own (`./generated` for setup, `./generated/<spec>` for the rest). This file builds **core's** clients
// only: the Memories application's (`contracts/api/memories/` → `./generated/media`, `./generated/item-favorites`) are
// built in `features/memories/api.ts` on the params exported below, because core imports no application client
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
  AccountErasureApi,
  AccountPasswordApi,
  AccountProfileApi,
  AccountRotationApi,
  AccountWrapsApi,
  Configuration as AccountConfiguration,
} from './generated/account'
import { Configuration as TenantsConfiguration, TenantsApi } from './generated/tenants'
import { Configuration as ScopesConfiguration, KeyChainApi, ScopesApi } from './generated/scopes'
import { ContentApi, Configuration as ContentConfiguration, RekeyApi } from './generated/content'
import { ApplicationBlobsApi, BlobApi, CaseApplicationBlobsApi, Configuration as BlobConfiguration, ShareApplicationBlobsApi } from './generated/blob'
import { TasksApi, Configuration as TasksConfiguration } from './generated/tasks'
import { ModerationApi, ReportsApi, StatementsApi, Configuration as ModerationConfiguration } from './generated/moderation'
import { CaseReviewApi, SharingApi, Configuration as SharingConfiguration } from './generated/sharing'
import { FavoritesApi, Configuration as FavoritesConfiguration } from './generated/favorites'
import { OperatorApi, Configuration as OperatorConfiguration } from './generated/operator'
import { session } from '../app/session'

/**
 * The fetch every generated client sends through. A `304` (a live poll's "unchanged", ADR 0081) has no body, and none
 * of ours is read: the generated runtime throws it as a `ResponseError` whose status is all a caller looks at. But
 * Chromium cancels the load of an empty body nobody reads, and reports the request `net::ERR_ABORTED` although the
 * server answered — every tree and media poll of a Memory then read as a failed request in the V1 heavy stage
 * (ADR 0105 § Targets, "no failed request"). Reading the empty body to its end lets the load finish. `fetch` is looked
 * up at each call, so a test's stubbed global is the one sent through.
 */
export const fetchApi: typeof fetch = async (input, init) => {
  const response = await fetch(input, init)
  if (response.status === 304) await response.arrayBuffer().catch(() => undefined)
  return response
}

/** The instance's pre-auth setup status (ADR 0032). `apiBase` is another instance's origin where a bookmark's page asks
 *  that instance what it serves (ADR 0121 decision 4): token-less either way, and `/api/setup` answers any origin there
 *  while it lets its links be embedded. Omitted: this origin. */
export function setupApi(apiBase = ''): SetupApi {
  return new SetupApi(new Configuration(publicParams(apiBase)))
}

// The one bearer-credential contract every authed client shares: same-origin basePath + the access token
// from the in-memory session. Each generated package has its own Configuration class but the same parameter
// shape, so this single source feeds them all — change the token source once, here. Exported for an
// application's own clients (`features/memories/api.ts`), which core does not import (ADR 0079).
export const bearerParams = () => ({ basePath: session.apiBase, accessToken: () => session.accessToken ?? '', fetchApi })

// The token-less counterpart: no credential at all, so no Authorization header is sent. For the calls a caller makes
// without a session by design ([publicPairingApi], [publicSharesApi], and an application's guest operations). `apiBase`
// is where the call goes: `''` for this origin (`session.apiBase`), or the origin of the instance that serves a share a
// bookmark's page shows in place (ADR 0121 decision 5, `core/sharing/shareAt.ts`) - REQUIRED, so no guest call reaches a
// base its caller did not choose. Nothing of this origin's session ever travels with it: no token here, and `fetch`
// sends no cookie to another origin by default (`credentials: 'same-origin'`), which the generated runtime leaves alone.
export const publicParams = (apiBase: string) => ({ basePath: apiBase || session.apiBase, fetchApi })

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
  return new PairingApi(new AuthConfiguration(publicParams('')))
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

/** Erasing the caller's own account (uc.account.erase-account, ADR 0101) — session-authenticated; the request carries
 *  its own password proof. */
export function accountErasureApi(): AccountErasureApi {
  return new AccountErasureApi(new AccountConfiguration(bearerParams()))
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

// A person's key chain (ADR 0111 decision 7, the `key-chain` tag): read by the pin check when a served head moved, and by
// a profile for the date of the newest step. Served to co-members of a tenant only.
export function keyChainApi(): KeyChainApi {
  return new KeyChainApi(new ScopesConfiguration(bearerParams()))
}

// The member's node pins (ADR 0068, core's): one sealed blob per (user, scope). No guest route exists.
export function favoritesApi(): FavoritesApi {
  return new FavoritesApi(new FavoritesConfiguration(bearerParams()))
}

export function contentApi(): ContentApi {
  return new ContentApi(new ContentConfiguration(bearerParams()))
}

// A scope's re-key after a rotation (ADR 0109, core's `rekey` tag): list every node with its epoch, a node's core parts,
// stage, commit. Any member holding the current grant reaches it, the nodes only an owner re-keys excepted (ADR 0111
// decision 11).
export function rekeyApi(): RekeyApi {
  return new RekeyApi(new ContentConfiguration(bearerParams()))
}

// The platform's generic blob transfer (core's `blob` tag, ADR 0079): start an upload, sign each next window,
// presign downloads. Completing an upload is the application's (Memories' `mediaApi().completeMedia`, built in
// `features/memories/api.ts`) - or core's, for a node's cover (`contentApi().setNodeCover`, inbox #411).
export function blobApi(): BlobApi {
  return new BlobApi(new BlobConfiguration(bearerParams()))
}

// Core's blob service for the blobs an application stores (ADR 0112, tags `application-blobs`, `share-application-blobs`,
// `case-application-blobs`): a member's read and completion, a guest's on a link (no token), a moderator's case read.
// The transport over them is `core/blob/applicationBlobs.ts`.
export function applicationBlobsApi(): ApplicationBlobsApi {
  return new ApplicationBlobsApi(new BlobConfiguration(bearerParams()))
}

export function publicShareApplicationBlobsApi(apiBase: string): ShareApplicationBlobsApi {
  return new ShareApplicationBlobsApi(new BlobConfiguration(publicParams(apiBase)))
}

export function caseApplicationBlobsApi(): CaseApplicationBlobsApi {
  return new CaseApplicationBlobsApi(new BlobConfiguration(bearerParams()))
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
 *  member and by a guest of a link alike, so token-less — a guest's page sends no Authorization header. `apiBase`: the
 *  instance the report goes to — a guest of another instance's link reports there (ADR 0121 decision 8). */
export function publicModerationApi(apiBase: string): ModerationApi {
  return new ModerationApi(new ModerationConfiguration(publicParams(apiBase)))
}

/** A member's report of what their space holds (uc.moderation.report). A guest reports through `publicSharesApi`. */
export function reportsApi(): ReportsApi {
  return new ReportsApi(new ModerationConfiguration(bearerParams()))
}

/** An affected member's statements of reasons and their objection (uc.moderation.object-to-a-measure): bearer-authed,
 *  no role and no key proof — the server tells a member only what names them. */
export function statementsApi(): StatementsApi {
  return new StatementsApi(new ModerationConfiguration(bearerParams()))
}

// Public viewer shares (cr-0100). The **curator** ops (create/list/revoke) are bearer-authed like the rest.
// The **guest** ops (resolve + gated blob GET) are unauthenticated — [publicSharesApi] carries NO token (the
// anonymous /s/ viewer has no session), so no Authorization header is ever sent from the no-account page.
export function sharesApi(): SharingApi {
  return new SharingApi(new SharingConfiguration(bearerParams()))
}

/** `apiBase`: the instance that serves the link (`''`: this origin; another's for a link a bookmark's page shows in place). */
export function publicSharesApi(apiBase: string): SharingApi {
  return new SharingApi(new SharingConfiguration(publicParams(apiBase)))
}

/** A moderator's review of a case (uc.moderation.review-a-case): the held snapshot in the guest viewer's shapes, behind
 *  the MODERATOR role and a key proof — bearer-authed, never a guest's read (`core/sharing/review.ts`). */
export function caseReviewApi(): CaseReviewApi {
  return new CaseReviewApi(new SharingConfiguration(bearerParams()))
}

// Who runs this instance (ADR 0093 § 11, ADR 0035 decision 7): the operator's published values, read before sign-in on
// the login and share pages. Token-less like the guest reads: nobody needs a session to learn who runs a server.
export function publicOperatorApi(): OperatorApi {
  return new OperatorApi(new OperatorConfiguration(publicParams('')))
}
