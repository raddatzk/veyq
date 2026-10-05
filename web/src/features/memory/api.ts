// The Memories application's API clients — the factories over the clients generated from its own specs
// (`contracts/api/memories/`: `generated/media`, `generated/favorites`). They live here, not in core's
// `api/client.ts`, because core imports no application client (ADR 0079;
// `validate_web_core_imports_no_applications.py` derives these directories from `generate:api`). The credential
// is core's: [bearerParams] for a member's call, [publicParams] for a guest's, so the token source stays one.
import { BucketsApi, Configuration as MediaConfiguration, MediaApi } from '../../api/generated/media'
import {
  Configuration as FavoritesConfiguration,
  FavoritesApi,
  ItemFavoritesApi,
  OwnItemFavoritesApi,
} from '../../api/generated/favorites'
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

export function favoritesApi(): FavoritesApi {
  return new FavoritesApi(new FavoritesConfiguration(bearerParams()))
}

// The item marks inside one Memory (ADR 0068) — the `item-favorites` tag on the favorites spec → its own
// ItemFavoritesApi, same config/auth. A separate resource from `favoritesApi` because the two objects have
// different audiences, not because the paths differ.
export function itemFavoritesApi(): ItemFavoritesApi {
  return new ItemFavoritesApi(new FavoritesConfiguration(bearerParams()))
}

// The caller's own item marks across every scope (ADR 0068 §9) — the `own-item-favorites` tag, read only by an
// account-key rotation's re-tag.
export function ownItemFavoritesApi(): OwnItemFavoritesApi {
  return new OwnItemFavoritesApi(new FavoritesConfiguration(bearerParams()))
}

// ── Token-less: a guest's calls from the anonymous /s/ page ────────────────────────────────────────────────

/**
 * The media configuration with **no** credential, as core's `publicSharesApi()` sits beside `sharesApi()`: the
 * anonymous /s/ page has no session, so no Authorization header is ever sent from it. No Memories guest operation
 * exists in the media spec yet — cr-platform-contract-split slice 6 moves the guest media read here and adds
 * `publicShareMediaApi()` on this configuration; until then nothing calls it.
 */
export function publicMediaConfiguration(): MediaConfiguration {
  return new MediaConfiguration(publicParams())
}
