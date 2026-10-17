// The Memories application's API clients — the factories over the clients generated from its own specs
// (`contracts/api/memories/`: `generated/media`, `generated/item-favorites`, `generated/share-media`). They live here, not in core's
// `api/client.ts`, because core imports no application client (ADR 0079;
// `validate_web_core_imports_no_applications.py` derives these directories from `generate:api`). The credential
// is core's: [bearerParams] for a member's call, [publicParams] for a guest's, so the token source stays one.
import { BucketsApi, Configuration as MediaConfiguration, MediaApi } from '../../api/generated/media'
import {
  Configuration as ItemFavoritesConfiguration,
  ItemFavoritesApi,
  OwnItemFavoritesApi,
} from '../../api/generated/item-favorites'
import { CaseMediaApi, Configuration as ShareMediaConfiguration, ShareMediaApi } from '../../api/generated/share-media'
import { bearerParams, publicParams } from '../../api/client'

// ── Bearer: a member's calls, authenticated by the session's access token ──────────────────────────────────

/** Memories' media operations (the `media` tag): complete an upload, list, trash, EXIF, sealed meta, cover. */
export function mediaApi(): MediaApi {
  return new MediaApi(new MediaConfiguration(bearerParams()))
}

// Uploader buckets (cr-0113) — the `buckets` tag on the media spec → its own BucketsApi, same media config/auth.
export function bucketsApi(): BucketsApi {
  return new BucketsApi(new MediaConfiguration(bearerParams()))
}

// The item marks inside one Memory (ADR 0068) — the `item-favorites` tag of its own spec → ItemFavoritesApi. The node
// pins are the other object and core's (`favoritesApi`, `api/client.ts`): different audiences, different owners.
export function itemFavoritesApi(): ItemFavoritesApi {
  return new ItemFavoritesApi(new ItemFavoritesConfiguration(bearerParams()))
}

// The caller's own item marks across every scope (ADR 0068 §9) — the `own-item-favorites` tag, read only by an
// account-key rotation's re-tag.
export function ownItemFavoritesApi(): OwnItemFavoritesApi {
  return new OwnItemFavoritesApi(new ItemFavoritesConfiguration(bearerParams()))
}

// ── Token-less: a guest's calls from the anonymous /s/ page ────────────────────────────────────────────────

/**
 * A guest's media on a share link (the `share-media` tag: list them, complete an upload, delete one's own) with **no**
 * credential, as core's `publicSharesApi()` sits beside `sharesApi()`: the anonymous /s/ page has no session, so no
 * Authorization header is ever sent from it. The link's password and the guest's token travel as the operations'
 * own headers and body fields.
 */
export function publicShareMediaApi(): ShareMediaApi {
  return new ShareMediaApi(new ShareMediaConfiguration(publicParams()))
}

/** A moderator's read of a case's held media (`listCaseMedia`, uc.moderation.review-a-case) — the review's stand-in for
 *  [publicShareMediaApi]'s listing, bearer-authed and key-proved (`core/sharing/review.ts`). */
export function caseMediaApi(): CaseMediaApi {
  return new CaseMediaApi(new ShareMediaConfiguration(bearerParams()))
}
