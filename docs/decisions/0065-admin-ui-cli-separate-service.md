# ADR 0065 — Admin UI and CLI are a separate deployable, not backend surface

- Status: Accepted
- Built: yes
- Date: 2026-09-07
- Model: [release-and-deployment](../crosscutting/release-and-deployment.md)
- Replaced in part: the split rule (what stays in `src/web/`) by ADR 0099 (built)

## Context

Server-Owner administration (instance setup, tenant creation, member management) was built as routes inside the main
SPA, gated per route with server-side authorization on each call, reachable at the same origin and over the same
public entrypoint as every ordinary page.

Forces:

- **The admin surface is the highest-privilege code an instance serves**, yet it was exposed to every visitor who can
  reach the instance's public domain — reachability, not authorization, is the layer an operator wants to restrict
  further (firewall, SSH tunnel, a domain never linked from the public site) without touching the user-facing app.
- **A CLI is wanted alongside the UI**, for scripted use, but a second implementation of tenant and member management
  is a second place to keep correct against the backend contract.
- **[ADR 0026](0026-client-trust-tiers.md)'s trust tiers apply to any client the operator serves**, and an admin
  surface on its own origin is a new client artifact under them.
- **[ADR 0050](0050-operator-config-secret-encryption.md)'s config-secret KEK is a file on the app host**; a second
  component must not gain its own path to it or to the database, or the instance holds operator secrets in two places.
- **The admin directory was not admin-only.** Ordinary and anonymous flows imported from it (accepting a tenant
  invite, re-accepting updated terms, reading a tenant's terms), and first-run setup performs a full cold account birth
  — identity keypair, Master Key wraps, Emergency Kit — with the same crypto as any member registration. So the admin
  surface does touch client-side crypto, and setup, the *most* privileged single action an instance takes, belongs
  with it.
- **Moving the UI does not move the API.** An admin container proxying `/api/` to the same backend the public origin
  routes to leaves every Server-Owner operation reachable from the internet, and `POST /api/setup/owner` — anonymous,
  guarded only by "setup not yet complete" — open to whoever calls it first after `up -d`, on a domain that is in
  public CT logs minutes after ACME issuance. **Nothing a request carries identifies its door:** `Host`, a forwarded
  client address and the scheme are client-writable, and Quarkus's forwarding rewrites the request's own view from
  them. **Quarkus has one HTTP listener for application resources**; its management interface serves no JAX-RS.

## Decision

**The admin surface is its own container (`admin`), a pure client of the backend Admin API — no direct database or
KEK access. The backend answers that API only through it (the *admin channel*, below).**

- **Web UI — its own package, sharing only crypto with `src/web/` through a minimal workspace (pnpm since 2026-11-03).** `src/admin-web/`
  has its own build, its own narrow generated API client and its own router, state and UI. The **one** shared thing is
  `@veyq/crypto` (`src/packages/crypto/`): the `v1` suite, the account birth and the Emergency Kit codec — the one place
  duplication would be a security defect rather than a maintenance nuisance. Nothing else is shared on purpose: the
  workspace exists for that one module, not for general code-sharing.
  - **What moves**: every operation whose backend gate is the Server-Owner gate, by the rule below, **and** first-run
    setup.
  - **What stays in `src/web/`**: the tenant-invite **accept** side (an anonymous invitee), the terms **accept** side
    (any member), and **per-tenant mail settings and terms authoring**: the backend gates both with
    `403 not_tenant_owner`, not the Server-Owner gate, and a tenant owner is an ordinary user who accepted an invite;
    moving them would take a tenant's own configuration away from every owner who is not also the Server Owner.

    **The rule the split follows, and the one to apply to anything moved later: ask which gate the BACKEND applies —
    the Server-Owner gate, or another (`not_tenant_owner`, the moderator's) — never which directory the file sits in.**
    An import graph finds the anonymous and ordinary-member callers, but is blind to a file that is owner-scoped by the
    state it reads. The **moderator** ([ADR 0099](0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md) § 6)
    is such an audience: review and decisions are gated by a `MODERATOR` role and a moderation-key signature on the
    public listener, so the moderation surface stays in `src/web/`, while the roster and the moderation key's minting
    and rotation are Server-Owner operations and live here.
  - **`src/web/` carries no "is this caller a Server Owner" probe**: under the admin channel it could only ever 404.
  - **admin-web reverse-proxies `/api/*`** to the backend's **admin listener** from **its own origin** — nginx serving
    the built assets, the backend's address templated in at container start — so the session stays same-origin
    wherever the container is reached from, avoiding the cross-origin refresh-cookie cost. Authentication is the
    existing Server-Owner session; setup is the one Admin API call that needs none. **It carries its own minimal
    sign-in** (the same challenge/verify endpoints and key derivation from the shared package) and deliberately none
    of `src/web/`'s passkeys, remembered devices, session vault or recovery: it has no vault to unlock, and each would
    be another place touching key material.
- **CLI — Node.js, in the same image.** It is also an HTTP client of the Admin API and imports the **same generated
  client** the UI uses — "no duplicated business logic" means the same code, not merely the same wire contract — which
  is why it departs from the repository's Python convention: it is a shipped product feature, not dev tooling. A
  break-glass, direct-database mode is a **non-goal**: it would reopen the "second component touches secrets" problem.
  - **A fresh login per invocation, credentials from the environment, no token ever written to disk.** The Argon2id
    derivation costs a second or more per call, accepted for an occasional ops tool rather than a token cache — a new
    case of credential material at rest.
  - **Bundled at image build into one Node file** (esbuild, build-time only), never transpiled at runtime. Not Vite's
    bundler by way of Vite: pinning the CLI's build to whatever bundler Vite ships next is a choice this repo did not
    make.
  - **The image pays for Node beside nginx** — the unprivileged nginx variant, because a stock nginx runs as root and
    binds 80, which a `restricted` namespace refuses ([ADR 0064](0064-kubernetes-helm-deployment-target.md)).
  - **`node:util.parseArgs`, no CLI framework**: the command surface mirrors admin-web's operations
    (`src/admin-web/src/cli/commands.ts` is the list). Mail settings and terms authoring are not among them — they are
    tenant-owner actions. **Setup is excluded** as a decision: it is an interactive ceremony that mints key material
    and force-confirms a saved Emergency Kit, which a script cannot meaningfully do.
- **Exposure is an operator choice, not a build-time one — two modes of the same image:**
  - **Localhost-only (the default).** No route at all; reached through a port bound to the host's loopback or an SSH
    tunnel. First-run setup then needs no DNS or TLS to be live.
  - **Own domain.** An optional admin domain with its own route and certificate, from **configuration, never from the
    tenant table** (the surface is 1:1 with the instance): on Compose the ingress sink renders it beside the bootstrap
    domain's ([ADR 0063](0063-database-driven-tenant-ingress.md)), on Kubernetes it is the chart's own route. Set it
    **after** first-run setup: this mode puts the admin channel on the internet (residual (iv)).
- **Trust tier: unchanged, not new.** A self-hosted operator running the admin container is the *Self-hosted* tier of
  ADR 0026. Extraction changes **reachability**, not trust tier.
- **The backend answers the Server-Owner surface only on the admin channel** — held where every deployment has it in
  common rather than at an edge a self-hoster may replace. The behaviour, its failure modes and its acceptance criteria
  are [uc.admin.admin-channel](../features/admin/use-cases/uc.admin.admin-channel.md)'s; what is decided here:
  - **The channel is a second backend listener, read from the socket.** One more Vert.x HTTP server whose handler is
    Quarkus' own root handler, so it runs the identical pipeline — forwarding, security, JAX-RS. A request is on the
    admin channel **iff** it arrived on that listener **and** its socket peer is not globally routable, read from the
    connection's local and remote socket addresses, never from a header or the forwarding-rewritten address. The peer
    rule is a belt for topologies the shipped targets do not use.
  - **Which operations: every operation whose backend gate is the Server-Owner gate, plus `createServerOwner`**, the one
    this decision exists for. `getSetupStatus` and sign-in stay on both channels.
  - **Declared per operation in `contracts/` (`x-veyq-channel: admin`), derived — never listed — by the backend, and
    held to the gate** by a posture sweep. This is not the authorization-in-the-spec
    [ADR 0008](0008-centralized-http-auth.md) rejected: the marker names which door answers, not a role.
  - **The public channel refuses with 404, from a filter keyed on the matched resource method**, so no path spelling
    routes around it. A role-gated operation still answers an anonymous caller 401 first and executes nothing; a uniform
    404 would need a second matcher in front of JAX-RS and hides nothing — the operation list is public.
  - **Setup therefore needs no bootstrap token**: the public origin never answers it.
  - **Deployment.** The listener is not `EXPOSE`d; Compose points the admin container (and the CLI's default) at it;
    the chart gives it a Service of its own, so no route aimed at the app Service reaches it, and a NetworkPolicy
    admits only the admin pod.
  - **Residuals, named rather than discovered.** (i) Compose networks separate containers by peer, not by port
    ([ADR 0071](0071-runtime-hardening-on-both-targets.md)), so every container on the app's network reaches the
    listener — Traefik, the store, MariaDB — none of which gains anything by it. (ii) On a cluster whose CNI does not
    enforce NetworkPolicy, any pod reaches it. (iii) NAT that rewrites a public peer to a private address defeats the
    peer rule — which is why it is a belt, and the guarantee is that the shipped targets route nothing to the listener.
    (iv) **Own-domain mode puts the admin channel on the internet**: before setup that reopens the window this closes,
    so setup runs over the localhost path first — documented, not enforced.
  - **Expiry condition.** The root handler is public but lives in a Quarkus `runtime` package, not documented API. An
    upgrade that removes it fails compilation, loudly; the fallback then is the channel secret rejected below —
    rejected for its costs, not because it fails.

## Consequences

- **Smaller public attack surface on the main origin** — it does not answer the Server-Owner API at all, and an
  operator can firewall the admin container off without touching the user-facing app.
- **Every client of the Server-Owner API goes through the admin listener**, including the e2e harness, the dev seed
  scripts and backend slice tests that call a Server-Owner operation.
- **A new deployable and a new release artifact**, `veyq-admin`, versioned and signed with the app image
  ([ADR 0057](0057-release-identity-and-deployment-recipe.md)), and deployed by both targets with the same two exposure
  modes.
- **Rejected — keep it embedded, gate exposure with a feature flag.** The flag ships in the main image and origin, so a
  compromised main-app deployment still exposes admin-capable code at the same reachability; the point was to remove
  that coupling, not re-implement it as a toggle.
- **Rejected — exclude the admin paths at the edge** (a Traefik rule, a route's path match). It holds only for the
  edge this repository ships; a self-hoster's own reverse proxy loses it silently.
- **Rejected — a channel secret the admin nginx injects as a header.** Topology-independent, its real strength. But it
  is new credential material at rest in three places (`.env`, the chart Secret, the rendered nginx config), and a leaked
  copy makes the **public** origin an admin door. A listener has nothing to leak.
- **Rejected — let the admin container hold state itself** (its own database or KEK path). It breaks the fourth force,
  and drops the owner's login as a layer.
- **Rejected — a bootstrap token for setup.** Once the public origin never answers setup, a token only adds a secret
  to distribute for a window the channel already closes.
- **Rejected — the second listeners Quarkus already has.** The domain socket needs a volume both containers mount,
  which on Helm means one pod — no longer a separate deployable. The TLS port needs a certificate, and derives its
  scheme from forwarded headers. The management interface serves no JAX-RS.

## Related

- [uc.admin.admin-channel](../features/admin/use-cases/uc.admin.admin-channel.md) (the property the admin
  channel holds, and its failure modes).
- [ADR 0008](0008-centralized-http-auth.md) (authorization stays out of the contract; the channel marker is
  reachability, not a role).
- [ADR 0026](0026-client-trust-tiers.md) (trust tiers this container inherits, not extends).
- [Set up a new instance](../../site/src/content/docs/run/new-instance.mdx) (the docs site's Run area: creating the
  Server Owner on this surface before DNS and TLS, and its two exposure modes).
- [ADR 0050](0050-operator-config-secret-encryption.md) (the secret this ADR deliberately keeps
  single-homed).
- [ADR 0063](0063-database-driven-tenant-ingress.md), [ADR 0064](0064-kubernetes-helm-deployment-target.md)
  (the two deployment targets this container ships on).
- Run area of the docs site: [Hardening](../../site/src/content/docs/run/hardening.md)
- Run area of the docs site: [First-run setup and the admin console](../../site/src/content/docs/run/first-run.mdx)
