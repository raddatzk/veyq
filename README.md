# veyq

<sub>Pronounced **/veɪk/**.</sub>

A self-hostable, end-to-end-encrypted app for keeping and sharing memories — trips, events, the small
moments. The product center is the **Memory** (nested into **Chapters**); folders only organize.

**The server is operator-blind.** It stores and serves ciphertext and opaque metadata, and nothing else. Your
unshared personal space is confidential even from whoever runs the server — including when that is you. This
is not a setting; there is no key on the server to misuse.

## Status

**v1.0 is the first stable release** (2026-11-10; [releases](https://github.com/raddatzk/veyq/releases)). It installs
on Docker Compose through `veyqctl` or on Kubernetes through the Helm chart, each from the release's signed files
([the Run area](site/src/content/docs/run/index.md) of the docs site, which says how far each procedure is proven).
Every later 1.x release upgrades an instance in place. For development there is a local stack
([`infra/`](infra/README.md)): MariaDB, an S3-compatible object store, a test mailbox.

Built and in use: accounts and unlock, key recovery from the Emergency Kit (on an instance that can send
mail), changing your password from a live session, personal / group / tenant spaces with key rotation, device
linking (add a second device without retyping your password), session management (list your signed-in devices,
remote sign-out), media upload with client-side encryption and derivatives, the gallery and lightbox, per-Memory
trash, seven sharing flows (public links, contributor, uploader drop-box, shared scopes, notes, passwords,
anonymous accept), search across every space, Depot for files, Expenses for shared costs, and moderation.

What is still open is tracked in [the inbox](docs/inbox.md).

## Why self-hosting is the security guarantee

Confidentiality against a *passive* operator holds anywhere. Against an **active** one — an operator who
serves you a modified client — it holds only where you serve the client yourself. So self-hostability is not
just a sovereignty preference here; anything that erodes it is a security regression
([ADR 0026](docs/decisions/0026-client-trust-tiers.md)). The honest boundary is written out in the
[security model](docs/crosscutting/security-model.md).

## Repository shape

| Path | What it is |
|---|---|
| [`contracts/`](contracts/) | OpenAPI + crypto vectors — the source of truth code is **generated** from |
| [`backend/`](src/backend/CLAUDE.md) | Quarkus / Kotlin. Performs no content crypto, never sees a password |
| [`web/`](src/web/CLAUDE.md) | Vue 3 client. All content crypto runs here |
| [`docs/`](docs/README.md) | Internal engineering state — architecture, ADRs, features, coverage |
| [`site/`](site/src/content/docs/start.md) | The project website (what veyq is, its applications, EN + DE) and the published documentation: using veyq (EN + DE, plain language), running an instance, security |
| [`infra/`](infra/README.md) | Deployment — `infra/docker/` (the app image, the dev and production Compose files), `infra/kubernetes/` (the Helm chart) — and the local dev stack |
| `tests/` | The harnesses: lane L1's browser suite (`tests/e2e/`), the API fuzzer (`tests/fuzz/`) |

Start at [`docs/architecture/README.md`](docs/architecture/README.md) for the whole-system view, or
[`docs/crosscutting/security-model.md`](docs/crosscutting/security-model.md) if you came to check the
encryption claim.

## Development

```bash
docker compose -f infra/docker/docker-compose.yml up -d
```

Then the backend (`cd src/backend && ./gradlew quarkusDev`) and the web client (`cd src/web && pnpm run dev`). Each
surface documents its own checks: [`backend/CLAUDE.md`](src/backend/CLAUDE.md) (`./gradlew check`) and
[`web/CLAUDE.md`](src/web/CLAUDE.md) (`pnpm run check`).

## Security

Found a vulnerability? See [SECURITY.md](SECURITY.md). Please do not open a public issue for it.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md): contributions stay AGPL-3.0-only, with a DCO sign-off on every commit and
no CLA.

## License

[AGPL-3.0-only](LICENSE). Brand assets are excluded — see [NOTICE](NOTICE).
