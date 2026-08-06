// Reference map-provider defaults (ADR 0016/0018): public OpenStreetMap raster tiles + the Nominatim reverse
// geocoder. These are the built-in fallbacks when no operator override is set (env `VITE_VEYQ_MAP_*` or a
// per-device localStorage override). Kept in their own module because two consumers must agree on the SAME
// values: `useMapConfig` (the runtime provider the client fetches from) and the build-time CSP generator
// (`vite.config.ts` derives the allowed tile/geocoder origins from these) — one source, so the policy can
// never drift from the provider it is meant to allow.

/** Basemap raster tile template; Leaflet fetches these as `<img>` (→ CSP `img-src`). */
export const DEFAULT_TILE_URL = 'https://tile.openstreetmap.org/{z}/{x}/{y}.png'
export const DEFAULT_TILE_ATTRIBUTION = '© OpenStreetMap contributors'
export const DEFAULT_TILE_MAX_ZOOM = 19
/** Reverse geocoder base; fetched via XHR (→ CSP `connect-src`). */
export const DEFAULT_GEOCODER_URL = 'https://nominatim.openstreetmap.org'
