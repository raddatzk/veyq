# ADR 0016 — Reverse geocoding: third-party-only, provider-neutral, consented by the member (Nominatim reference)

- Status: Accepted
- Built: partly (not built: a space owner's enabling notice, the tenant language for shared names)
- Date: 2026-07-01
- Model: [Memories § Places and the map](../apps/memories.md#places-and-the-map)

## Context

A Memory can carry a **named location** ([uc.memory.place](../features/memory/use-cases/uc.memory.place.md)):
a map position derived from the Memory's own photo GPS, plus a **name**. Turning a coordinate into a human name
needs a **geocoding service** — the part of the location story that reaches outside the device (together with the
basemap tiles, [ADR 0018](0018-map-tiles-provider-neutrality.md)). The tensions:

- **Operator-blindness is about the *veyq* operator.** A coordinate leaving the device to a geocoder is a
  *different* trust boundary than the veyq server seeing it — **as long as the geocoder is not the operator**. If
  the operator ran the geocoder, the client's coordinate would land on the operator's own service → the operator
  would no longer be blind. So the geocoder **must be a party the operator does not control** — a third party, or
  (fully blind) the device itself. **Self-hosting the geocoder is therefore rejected, not preferred.**
- **Which direction?** The flow is **coordinate-first** (the medoid, or a pin framed on the map) → name. There is
  never a "type a place name to search" step; the manual fallback is a **free-typed string**, not a geocoder
  search. So veyq needs **reverse** geocoding only, never forward autocomplete.
- **Which provider?** **Nominatim** (OSM's canonical geocoder) answers a reverse request with the coordinate's whole
  address breakdown (road … country), from which a name of any granularity can be picked
  (`Atlantik → Kanaren → Teneriffa`), and localizes via `accept-language` with **graceful fallback** to the local name
  (never a hard error). Photon is built for forward search-as-you-type, which we don't need.
- **Consent must be free, and so must its withdrawal.** "I won't expose my coordinate — someone else can" is a
  choice about the member's own device and address, not the group's: it cannot be imposed by an owner or by the rest
  of a group, and withdrawing it must be as easy as giving it and cost nothing else (GDPR Art. 7(3) and (4), recitals
  42 and 43). A member who declines gives up the map, not their membership.

Considered and rejected:

- **Commercial geocoders (Google / Mapbox / HERE).** Vendor lock-in + a leak to a data-broker — rejected.
- **Operator-self-hosted geocoder.** Convenient, but the **operator would see the coordinate** → breaks the
  invariant. Rejected (the inverse of the earlier framing).
- **Proxying the geocode through the veyq backend.** Same break — the operator sees the coordinate. Rejected;
  requests go client-direct.
- **Auto-consent on veyq signup.** Coercive for a privacy choice: a consent bundled into creating an account is not
  given for this purpose. Rejected; the consent is asked in context, before the first request.
- **A unanimity ratchet in a group** — once every current member consented, joining the space would be consenting,
  and a member who no longer consents would leave the space. Rejected: a consent tied to joining a group is not freely
  given, and a withdrawal that costs one's membership is not without detriment (GDPR Art. 7(4), recitals 42 and 43).
  The fairness worry it answered — a member who sees the map's names without exposing their own coordinate — is
  bounded already: such a member has no map and sees resolved names as text only.
- **Forward autocomplete.** Not needed by the coordinate-first flow.
- **Hiding resolved names from a member whose map services are off** (owner's argument, 2026-11-09): member A accepted
  that a provider may log their IP to resolve "Rome"; member B, who declined, reads the name for free - like copying
  someone's homework, unfair to A. Rejected, for two reasons. **The law:** a consent is free only if declining costs
  the service and nothing else (GDPR Art. 7(4), recital 42); a member who loses the place names of their family's
  shared Memories by declining is pressed to consent. That is our reading, not a reviewed answer, and the safe side is
  cheap: show the name. **The sharing:** A did not resolve the name for themself alone but wrote it into a SHARED
  Memory; a private Memory was open to them. What A shares is a contribution to the space, as a photo A uploads is -
  B reading it costs A nothing further and asks no provider. The free-riding stays bounded as decided: B gets no map
  and no lookup of their own.

## Decision

**Reverse geocoding is coordinate-first, behind a provider-neutral seam, sent client-direct to a third-party
(never the operator, never a proxy), and consented by the member, per browser, withdrawable at any time. Tiles +
geocoding are one coupled opt-in ([ADR 0018](0018-map-tiles-provider-neutrality.md)). Nominatim is the reference
provider.**

- **The seam is `reverse(lat, lng, lang) → level[]`** (the address hierarchy, finest→coarsest — road, suburb,
  city, county, state, country). The client **picks the granularity level** (and may type its own name); `lang` =
  the resolving language (see *tenant language* below). Providers map their address components onto these levels.
  The request carries **no granularity `zoom`**: it asks for the full breakdown once and the UI offers a level
  picker (Straße…Land), so *where* (the map position) is decoupled from *how specific the name is* — granularity
  is a deliberate pick, never a side-effect of the map zoom.
- **Third-party only, veyq-blind by construction.** The request goes **client-direct to a third-party provider**
  (public Nominatim/OSM or another the operator points at that it does **not** run), **never through the veyq
  backend and never to an operator-run instance**. It carries **no veyq identity and no Memory context** — a bare
  coordinate. The **veyq operator stays blind**; the *user* consents to the third party seeing a coordinate + IP.
  A fully-blind, no-third-party path exists only via a future **on-device coarse gazetteer**.
- **The consent is the member's, one per browser (coupled with tiles), never owner-decreed:**
  - **One consent per browser**, applied in every space. It is device-local
    ([ADR 0022](0022-settings-information-architecture.md)): the server holds no consent state, and a consent given
    on one device does not reach another.
  - **Given in context or in settings.** The place editor or a map asks before the first request; the member may
    also turn it on under Settings → *Map and place names*, which names the tile and geocoder hosts this server
    configured and what each receives.
  - **The consent names its recipients, and a new one is asked again** (GDPR Art. 4(11) and 7: a consent is informed
    only about the recipients it named). The consent records the tile host and the geocoder host it was given for, and
    holds only while those are the hosts the server serves: a new tile host, or a new or changed geocoder host, makes
    the map show the prompt again, naming the new host, and nothing is requested from it until the member agrees. A
    geocoder the Server Owner removed needs no new consent — a recipient fewer, never a new one. A consent recorded
    before hosts were, which names none, is asked once more. The settings show the hosts an earlier consent named
    where they differ from the current ones.
  - **Withdrawn in settings or at the map, at once.** The same settings switch turns it off, and every map carries a
    *Turn map off* control beside its attribution — so withdrawing is as easy as giving, and a share guest, who has
    no settings, withdraws there too. A withdrawal takes effect immediately: every open map is torn down, no further
    tile or geocoding request leaves, and the consent prompt stands where the map was. What is already sealed —
    resolved names, positions — is not touched.
  - A **non-consenting** member's device makes **no external request** — no tiles (no map) and no geocode; they see
    already-resolved names only as **text**, and they **name a place by hand**: their place editor is the name field
    of a server without a map, with the map's place offering to turn the map services on - never a dialog whose only
    ways out are *Cancel* or *Turn on* (found by lane L1, 2026-11-09). Declining costs the map and nothing else: no
    membership, no other function.
  - **Worded as a setting, not as a cookie banner** (owner, 2026-11-09): the member's choice reads *Map services: on /
    off* (de *Kartendienste: an / aus*), off until turned on, and turning it on names the hosts and what they receive,
    as above. In law it stays a consent (GDPR Art. 4(11), 7); the wording only says plainly what it switches.
  - A **space owner enabling** maps for a space is the owner's wish and **never binds members**: each member is
    *notified* and consents on their own device (or is already covered by their consent). Not built.
- **Tenant language for shared names.** A resolved name is sealed into the shared `wrappedMeta`
  ([ADR 0012](0012-content-key-hierarchy.md)) so members (incl. non-consenting ones) see it as text. To keep it
  **consistent** rather than in the random locale of whoever resolved it first, shared names resolve in the
  **tenant's configured language** (`accept-language`). `nameAnchor` is kept so an opted-in viewer can
  **re-resolve in their own language** on demand; a personal space uses the user's own locale.
- **Operator-configurable provider, gated by the member's consent.** Reference = **Nominatim** (OSM data,
  vendor-neutral): a fresh instance comes configured with public Nominatim, and the Server Owner may point it at
  another provider or remove it. Without the member's consent, or with no provider configured, the reverse feature is
  **absent** (manual names on the map still work). Manual name entry never makes an external call.

### Request discipline — the provider's policy is part of the decision, not an optimisation

A caller can generate volume (a per-photo resolved name in the lightbox). Everything above is about *who* may see a coordinate; nothing said *how often* we may ask. The
reference provider's own policy does, and it is not silent — public Nominatim (measured 2026-09-20 against
<https://operations.osmfoundation.org/policies/nominatim/>) sets an **absolute maximum of 1 request per second**,
says bulk geocoding **is not encouraged**, requires that results **must be cached** on our side, and warns that a
client "sending repeatedly the same query may be classified as faulty and blocked". So:

- **Cache, then seal.** A resolved name is sealed into the tier and read from there forever. A surface may ask
  the provider **once per place**, never once per open and never once per photo — the client cache is keyed on
  the coordinate **rounded to ~110 m**, because photos from one spot differ in the fifth decimal and would
  otherwise each be "repeatedly the same query" by the provider's definition.
- **Serialise, do not merely count.** Requests queue behind a ≥1 s interval. A rate *counter* still permits a
  burst; a queue cannot.
- **Failure is not retried into the policy.** A provider error resolves to nothing and the surface keeps showing
  the raw coordinate. There is no retry loop, because a retry against a blocked client is what turns a refusal
  into a ban.

**One requirement is met by the `Referer` alone, and a plausible hardening pass would break it.** The policy also
wants "a valid HTTP Referer or User-Agent identifying the application". We send neither deliberately: the app
shell's `Referrer-Policy: strict-origin-when-cross-origin` ([ADR 0069](0069-backend-serves-the-web-app-shell.md))
keeps our origin in `Referer` cross-origin, and never a path. Tightening it to `no-referrer` — an ordinary,
otherwise-desirable hardening — would silently put every instance outside the provider's policy. Whoever
tightens it owes this seam an explicit identifying header instead; the trap is also written on the fetch itself,
where someone editing it will see it.

**What this does not reopen.** Reading each medium's sealed EXIF to compute a Memory's medoid was rejected — N fetches
for one answer, on every open; the GPS rides the listed capture tier instead
([uc.memory.place](../features/memory/use-cases/uc.memory.place.md)). That rejection stands untouched. A per-photo name
is a different shape: one name, once ever, for the photo in front of the user, sealed into the tier the medoid already
reads.

## Consequences

- **The veyq operator never sees a coordinate or a place name** — the request is client-direct to a party the
  operator doesn't run, identity-free, and the stored result is sealed in the node's meta, under a field key derived from `K_node`. Self-hosting the geocoder
  would forfeit this, so it is not offered.
- **No vendor lock-in.** Swapping Nominatim for another OSM-based geocoder is an endpoint + a thin adapter behind
  the same `reverse()` seam.
- **Freedom without coercion.** No one is forced and the owner can't impose; a member may withdraw at any time at no
  cost but the map, and free-riding is bounded (non-consenters lose the map and see names as text).
- **Consistent shared names, still re-resolvable.** The tenant language fixes the stored string; `nameAnchor` +
  `accept-language` let a viewer re-resolve in their locale on demand.
- **The operator sees no consent.** The consent lives in the member's browser; the operator sees which services the
  instance offers, never who consented, a coordinate or a place name.
- **A blind, leak-free path remains open.** Manual names need no request; a future **on-device coarse gazetteer**
  (a bundled `lat/lng → country/region/city` dataset) can sit behind the same seam as a fully-blind provider —
  deferred for its per-locale data cost, not excluded. Note that a platform's *built-in* geocoder is never the
  blind option — it calls its own vendor.
- **Limitation (honest).** With a third-party provider enabled, the geocoder learns a bare coordinate + client
  IP — a *consented, non-veyq* boundary, documented in [security-model](../crosscutting/security-model.md).
