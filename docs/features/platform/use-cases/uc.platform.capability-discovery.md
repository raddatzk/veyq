# UC: Discover what an instance can do (and refuse to break silently)

- **ID:** uc.platform.capability-discovery
- **Primary actor:** any client, **before and after login** — the discovery surface is pre-auth.
- **Goal:** A self-hosted instance and its version-drifting clients stay **honest** with each other: the client
  learns the instance's **version** and **active capability set**, hard-stops on an incompatible `major`, hides
  what the instance doesn't serve, and never breaks silently mid-flow.
- **Preconditions:** The instance is reachable. No identity, no session — this runs before login, and its answer
  is identical for every caller (so it leaks nothing about who is asking).
- **Postconditions (success):** The client holds the instance's `version` + active `capabilities` set for this
  session and gates its entry points on them. On an incompatible `major` the client is in a blocking state and
  has **not** attempted login.

## Main flow

1. On startup (before login), the client fetches `GET /api/setup` → `{ setupRequired, suites, instanceSalt,
   version: { major, build }, capabilities: [{ id }] }`.
2. The client compares `server.version.major` to the **`major` it was compiled against**. Equal → continue.
   (Mismatch → A1/A2. `build` never gates.)
3. The client keeps the `capabilities` set and exposes `hasCapability(id)`.
4. Each feature that is **optional per instance** gates its entry points on its capability: present → offered,
   absent → **hidden** (A3), never invoked.

## Alternate / exception flows

- **A1 — server `major` > client:** a **blocking** "update your app" screen, **before** login — never a partial,
  mysterious failure later.
- **A2 — server `major` < client:** a **blocking** "update this server" screen, same timing.
- **A3 — capability absent:** the feature is hidden and never invoked — graceful degradation, never a crash.
- **E1 — the client ignores A3 (stale or hostile):** the server **rejects** the endpoint with
  `422 capability_disabled`. The manifest is a UX hint; the server is the authority. An operator who disables a
  capability gets both halves: it is **omitted** from the manifest *and* enforced at the endpoint.

## Quality notes

- **A capability is not a permission.** It is instance-wide and pre-auth; it says what this *server* offers, not
  what *you* may do. A feature is offered only when **(capability present) AND (policy permits here)** — e.g.
  `core.public-shares` present **and** the node is one the caller may share. Conflating the two
  is the modelling error this use-case exists to prevent ([ADR 0032](../../../decisions/0032-capability-discovery-version-gating.md) §6).
- **Operator-blind-safe.** The response is identical for every caller and carries no per-user state, so a
  pre-auth manifest leaks nothing — that is what lets it sit outside the auth boundary.
- **The catalog is a coarser projection over use-cases, not a 1:1 rename.** Each entry in
  `contracts/platform/capabilities.json` declares a `serves: [uc.…]` back-ref (catalog-only, never on the wire);
  `scripts/validate_capabilities.py` enforces that every `serves` resolves to a real use-case, so the catalog
  cannot drift from the graph. It is **append-only**: an id may be reserved before its first advertisement and is
  advertised active only once its use-case(s) are built.
- **Seeded with seven operator-policy capabilities**, all under `core` and arranged as a tree (ADR 0088), sharing being
  the worked end-to-end proof; each is advertised, soft-gated by `hasCapability` and refused with `422` at its
  create/enable path. The authoritative list is the catalog file, not this page. The Server Owner turns one off for
  the instance in admin-web or its CLI ([uc.admin.switch-capabilities](../../admin/use-cases/uc.admin.switch-capabilities.md));
  no tenant level is built.

## What this means for you / Was das für dich bedeutet

**EN —** veyq can be run by anyone, so the server you use and the app you open it with may not be the same age,
and not every server offers every feature. When they don't fit together, veyq tells you straight away — before
you sign in — instead of letting you get halfway through something that then fails. And a feature your server
doesn't offer simply isn't shown, rather than sitting there and breaking when you touch it.

**DE —** veyq kann jede und jeder selbst betreiben — der Server, den du nutzt, und die App, mit der du ihn
öffnest, sind also nicht zwangsläufig gleich alt, und nicht jeder Server bietet jede Funktion an. Wenn beides
nicht zusammenpasst, sagt veyq dir das sofort — noch vor der Anmeldung — statt dich mitten in etwas laufen zu
lassen, das dann scheitert. Und was dein Server nicht anbietet, wird gar nicht erst angezeigt, anstatt da zu
sein und beim Antippen kaputtzugehen.

## Related

- Feature: [Platform](../_index.md)
- Decisions: [ADR 0032](../../../decisions/0032-capability-discovery-version-gating.md) (the discovery +
  version-gating model, and why a capability is not a permission)
- Contract: `contracts/platform/capabilities.json` (the append-only catalog — the authoritative capability list)
- **Deferred** (see the feature index): the deprecation lifecycle (`state`, `sunsetAt`, `supersededBy`), per-user
  "new feature" notices/acks, audience filtering, folding `suites` into the unified manifest, and client device
  self-capabilities. (`directTransport` was **removed** rather than folded in — a byte-path store is now a hard
  requirement, cr-0105.)
