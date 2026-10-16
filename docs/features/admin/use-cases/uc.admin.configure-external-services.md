# UC: Configure the external services a browser calls

- **ID:** uc.admin.configure-external-services
- **Primary actor:** The **Server Owner**, in the admin console (admin-web) or its CLI
- **Goal:** Decide which outside services a member's browser may call, and at which address: the map tiles, the
  geocoder that names places, and the breached-password check
  ([ADR 0088](../../../decisions/0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md) decision 7,
  [ADR 0089](../../../decisions/0089-instance-settings-live-in-admin-web-the-environment-only-bootstraps.md)). An
  instance without internet, or one that wants no third party, runs with all three unconfigured.
- **Preconditions:** The Server Owner holds a session on the admin channel.
- **Postconditions (success):** The instance holds each service's address or none. A service is in use only while it
  has an address and its register entry (`memories.map`, `memories.places`, `core.breached-password`) is on; every
  client reads the address from the manifest, and the Content Security Policy the backend serves names its origin
  only then. Nothing a member stored changes.

## Main flow

1. The Server Owner opens **Server → External services**, or runs `veyq-admin services list`. A new instance shows
   OpenStreetMap's tiles with their attribution, Nominatim as the geocoder and the HIBP range API.
2. They set an address (the map tiles: a `{z}/{x}/{y}` template and the attribution the provider asks for), or remove
   one so the service is not used at all.
3. The backend stores it and applies it at once, without a restart: the next page a member loads gets the new address
   with the capability, and the served policy allows exactly the origins of the services in use.
4. A member's browser calls the service only while its entry is in the set it reads; with none, a place is named by
   hand, no map is shown, and a chosen password is not checked against leaks.

## Alternate / exception flows

- **A1, an address that is no http(s) URL, a tile template without `{z}`, `{x}` or `{y}`, or an attribution for a
  service that takes none:** `400 invalid_request`, nothing stored.
- **A2, a tenant owner's switch:** a tenant may switch the maps and place names off for its own members (its set leaves
  them out); the served policy follows the instance level only, because it is computed off the request path.
- **A3, admin-web's own first-run screen** checks a new password against the default HIBP origin its own policy
  names: a mirror at another address is not reachable there and reads as "could not check".
- **E1, not the Server Owner:** `403 not_server_owner`.

## Quality notes

- **Self-hostable.** No service is required: every one can be unconfigured, and what depends on it degrades to the
  hand-made version (a typed place name, no map, no leak check).
- **Operator-blind.** The address is instance configuration. The browser calls the service directly; the server sees
  no coordinate, no tile and no password hash prefix.

## User impact

Whoever runs your server decides which outside services your browser talks to for maps, place names and the password
check, or that it talks to none.

## Related

- **Feature:** [admin](../_index.md)
- **Use-cases:** [uc.admin.switch-capabilities](uc.admin.switch-capabilities.md),
  [uc.memory.place](../../memory/use-cases/uc.memory.place.md),
  [uc.account.change-password](../../account/use-cases/uc.account.change-password.md)
- **Decisions:** [ADR 0088](../../../decisions/0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md),
  [ADR 0089](../../../decisions/0089-instance-settings-live-in-admin-web-the-environment-only-bootstraps.md),
  [ADR 0069](../../../decisions/0069-backend-serves-the-web-app-shell.md) (the served policy),
  [ADR 0016](../../../decisions/0016-geocoding-provider-neutrality.md), [ADR 0018](../../../decisions/0018-map-tiles-provider-neutrality.md)
- **On the docs site:** [Capabilities § External services](../../../../site/src/content/docs/run/capabilities.mdx)
  (the console, the CLI, the defaults) and [Set the location](../../../../site/src/content/docs/memories/set-the-location.mdx)
  (what a member sees without a map).
