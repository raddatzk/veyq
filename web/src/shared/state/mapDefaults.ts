// The map's one client-side constant (ADR 0018): the deepest zoom Leaflet asks the tile service for. The addresses are
// the server's (the `memories.map` / `memories.places` capabilities, uc.admin.configure-external-services).
export const DEFAULT_TILE_MAX_ZOOM = 19

// The reference providers a new instance starts with (the backend's migration seeds them, ADR 0016/0018). Only the dev
// server's Content-Security-Policy reads them (`vite.config.ts`): a dev instance seeded with the defaults works, one
// pointed elsewhere edits its dev policy. Production's policy is the backend's, following the configured services.
export const DEV_TILE_ORIGIN = 'https://tile.openstreetmap.org'
export const DEV_GEOCODER_ORIGIN = 'https://nominatim.openstreetmap.org'
export const DEV_BREACH_ORIGIN = 'https://api.pwnedpasswords.com'
