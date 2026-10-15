# veyq

<sub>Pronounced **/veɪk/**.</sub>

A self-hostable, end-to-end-encrypted app for keeping and sharing memories — trips, events, the small
moments. The product center is the **Memory** (nested into **Chapters**); folders only organize.

**The server is operator-blind.** It stores and serves ciphertext and opaque metadata, and nothing else. Your
unshared personal space is confidential even from whoever runs the server — including when that is you. This
is not a setting; there is no key on the server to misuse.

## Status — read this before you deploy

**veyq is pre-V1 and there is no supported deployment path yet.** What runs today is a development stack
([`infra/`](infra/README.md)): MariaDB, an optional S3-compatible object store, an optional test mailbox. There
is no upgrade procedure. A production Compose file and a backup-and-restore runbook now exist
([`infra/README.md`](infra/README.md)), but neither has been executed by any CI run — the remaining V1 work is
tracked as [the V1 line](docs/inbox.md).

Built and in use: accounts and unlock, key recovery from the Emergency Kit (on an instance that can send
mail), changing your password from a live session, personal / group / tenant spaces with key rotation, device
linking (add a second device without retyping your password), session management (list your signed-in devices,
remote sign-out), media upload with client-side encryption and derivatives, the gallery and lightbox, per-Memory
trash, and seven sharing flows (public links, contributor, uploader drop-box, shared scopes, notes, passwords,
anonymous accept).

Not yet built: search, journal, and rotate/zoom in the lightbox. The backup-and-restore + upgrade runbook above
is built but not yet CI-proven — no remote exists yet for that run. The [V1 line](docs/inbox.md) says which of
these V1 waits for and which it does not.

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
| [`backend/`](backend/CLAUDE.md) | Quarkus / Kotlin. Performs no content crypto, never sees a password |
| [`web/`](web/CLAUDE.md) | Vue 3 client. All content crypto runs here |
| [`docs/`](docs/README.md) | Internal engineering state — architecture, ADRs, features, coverage |
| [`site/`](site/src/content/docs/index.md) | The published documentation site: using veyq (EN + DE, plain language), running an instance, security |
| [`infra/`](infra/README.md) | Local dev stack (Docker Compose) |

Start at [`docs/architecture/README.md`](docs/architecture/README.md) for the whole-system view, or
[`docs/crosscutting/security-model.md`](docs/crosscutting/security-model.md) if you came to check the
encryption claim.

## Development

```bash
docker compose -f infra/docker-compose.yml up -d
```

Then the backend (`cd backend && ./gradlew quarkusDev`) and the web client (`cd web && npm run dev`). Each
surface documents its own checks: [`backend/CLAUDE.md`](backend/CLAUDE.md) (`./gradlew check`) and
[`web/CLAUDE.md`](web/CLAUDE.md) (`npm run check`).

## Security

Found a vulnerability? See [SECURITY.md](SECURITY.md). Please do not open a public issue for it.

## License

[AGPL-3.0-only](LICENSE). Brand assets are excluded — see [NOTICE](NOTICE).
