# ADR 0016 — Reverse geocoding: third-party-only, provider-neutral, consented per space (Nominatim reference)

- Status: Accepted
- Date: 2026-07-01 (revised 2026-07-02)

> **Revision (2026-07-02, cr-0069 build).** The seam **returns the address hierarchy**, not a single name: the
> client picks the granularity **level** (Straße…Land) from the returned levels, decoupling *where* (the map
> position) from *how specific the name is*. So the request no longer carries a granularity `zoom` — it asks for
> the full breakdown once, and the UI offers a level picker. The principle below is unchanged (reverse-only,
> coordinate-first, third-party, veyq-blind); only the seam's return shape is refined from `→ name` to `→ level[]`.

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
- **Which provider?** **Nominatim** (OSM's canonical geocoder) offers reverse with a **`zoom`** parameter (0–18 →
  country … building) mapping a chosen resolution to a name granularity (`Atlantik → Kanaren → Teneriffa`), and
  localizes via `accept-language` with **graceful fallback** to the local name (never a hard error). Photon (the
  earlier inbox pick) was for forward search-as-you-type, which we don't need.
- **Consent must be fair in a group.** "I won't expose my coordinate — someone else can" is, unlike a hardware/
  cost gate, a *"my privacy matters more than yours"* asymmetry. It cannot be imposed, and free-riding inside a
  committed group is unfair. Consent is therefore modelled **per space**, not silently global and not owner-decreed.

Considered and rejected:

- **Commercial geocoders (Google / Mapbox / HERE).** Vendor lock-in + a leak to a data-broker — rejected.
- **Operator-self-hosted geocoder.** Convenient, but the **operator would see the coordinate** → breaks the
  invariant. Rejected (the inverse of the earlier framing).
- **Proxying the geocode through the veyq backend.** Same break — the operator sees the coordinate. Rejected;
  requests go client-direct.
- **Auto-consent on veyq signup / a single global toggle.** Coercive for a privacy choice; rejected in favour of
  the per-space model below.
- **Forward autocomplete.** Not needed by the coordinate-first flow.

## Decision

**Reverse geocoding is coordinate-first, behind a provider-neutral seam, sent client-direct to a third-party
(never the operator, never a proxy), and consented per space. Tiles + geocoding are one coupled opt-in
([ADR 0018](0018-map-tiles-provider-neutrality.md)). Nominatim is the reference provider.**

- **The seam is `reverse(lat, lng, lang) → level[]`** (the address hierarchy, finest→coarsest — road, suburb,
  city, county, state, country). The client **picks the granularity level** (and may type its own name); `lang` =
  the resolving language (see *tenant language* below). Providers map their address components onto these levels.
  *(Originally `reverse(lat,lng,zoom,lang)→name` with `zoom`=granularity; refined per the revision note above so
  granularity is a deliberate pick, not a side-effect of the map zoom.)*
- **Third-party only, veyq-blind by construction.** The request goes **client-direct to a third-party provider**
  (public Nominatim/OSM or another the operator points at that it does **not** run), **never through the veyq
  backend and never to an operator-run instance**. It carries **no veyq identity and no Memory context** — a bare
  coordinate. The **veyq operator stays blind**; the *user* consents to the third party seeing a coordinate + IP.
  A fully-blind, no-third-party path exists only via a future **on-device coarse gazetteer**.
- **Consent is per space (coupled with tiles), never global or owner-decreed:**
  - A user may **pre-opt-in in personal settings** (tiles + geocoding); then joining a space shows only an info
    pill — no per-space action needed.
  - Otherwise a user opts in **per space** explicitly.
  - A **space owner enabling** it is the owner's wish but **does not bind members**: each member is *notified* and
    consents individually (or is already covered by their personal opt-in). A **non-consenting** member's device
    makes **no external request** — no tiles (no map) and no geocode; they see already-resolved names only as
    **text**. (Not pure free-riding: they give up the map to keep their privacy.)
  - **Unanimity binds (a ratchet):** once **all** current members have consented, the opt-in becomes **binding
    for new members** (joining = consenting). Binding does **not** revert — a member who no longer wants to
    consent **leaves the space** rather than staying as a text-only free-rider.
  - A **personal space** is just the user's own two-stage toggle (no group).
- **Tenant language for shared names.** A resolved name is sealed into the shared `wrappedMeta`
  ([ADR 0012](0012-content-key-hierarchy.md)) so members (incl. non-consenting ones) see it as text. To keep it
  **consistent** rather than in the random locale of whoever resolved it first, shared names resolve in the
  **tenant's configured language** (`accept-language`). `nameAnchor` is kept so an opted-in viewer can
  **re-resolve in their own language** on demand; a personal space uses the user's own locale.
- **Operator-configurable provider, off until configured + consented.** Reference = **Nominatim** (OSM data,
  vendor-neutral). With no provider configured or no consent, the reverse feature is **absent** (manual names on
  the map still work). Manual name entry never makes an external call.

### Request discipline — the provider's policy is part of the decision, not an optimisation

**Added 2026-09-20, when the first caller that can generate volume was built** (a per-photo resolved name in the
lightbox). Everything above is about *who* may see a coordinate; nothing said *how often* we may ask. The
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

**One requirement is currently met by accident, and a plausible hardening pass would break it.** The policy also
wants "a valid HTTP Referer or User-Agent identifying the application". We send neither deliberately: the browser
default puts our origin in `Referer`, and measured 2026-09-20 the repository sets **no** `Referrer-Policy`
anywhere. Adding `Referrer-Policy: no-referrer` — an ordinary, otherwise-desirable hardening — would silently
put every instance outside the provider's policy. Whoever adds one owes this seam an explicit identifying header
instead; the trap is also written on the fetch itself, where someone editing it will see it.

**What this does not reopen.** [uc.memory.place](../features/memory/use-cases/uc.memory.place.md) rejected
per-item geocoding for a Memory's medoid — N fetches for one answer, on every open. That rejection stands
untouched. A per-photo name is a different shape: one name, once ever, for the photo in front of the user, sealed
into the tier the medoid already reads.

## Consequences

- **The veyq operator never sees a coordinate or a place name** — the request is client-direct to a party the
  operator doesn't run, identity-free, and the stored result is sealed under `K_mem`. Self-hosting the geocoder
  would forfeit this, so it is not offered.
- **No vendor lock-in.** Swapping Nominatim for another OSM-based geocoder is an endpoint + a thin adapter behind
  the same `reverse()` seam.
- **Fairness without coercion.** No one is forced; the owner can't impose; free-riding is bounded (non-consenters
  lose the map) and ends at unanimity, after which the commitment is binding and can't be silently undone.
- **Consistent shared names, still re-resolvable.** The tenant language fixes the stored string; `nameAnchor` +
  `accept-language` let a viewer re-resolve in their locale on demand.
- **Operator visibility is coarse.** The operator sees a space's map/geocoding state as **on / off / binding** —
  operational metadata, never a coordinate or a per-member consent detail.
- **A blind, leak-free path remains open.** Manual names need no request; a future **on-device coarse gazetteer**
  (a bundled `lat/lng → country/region/city` dataset) can sit behind the same seam as a fully-blind provider —
  deferred for its per-locale data cost, not excluded. Note that a platform's *built-in* geocoder is never the
  blind option — it calls its own vendor.
- **Limitation (honest).** With a third-party provider enabled, the geocoder learns a bare coordinate + client
  IP — a *consented, non-veyq* boundary, documented in [security-model](../crosscutting/security-model.md).

> The **personal-space** two-stage toggle lands with the set-location slice of `uc.memory.place` (cr-0069). The
> **group** consent state-machine (owner-enable, per-member notice/consent, unanimity-binds, leave-to-withdraw)
> and the **tenant language** land with groups/shared spaces — this ADR fixes the principle; the mechanics are
> built then.
