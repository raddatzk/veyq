---
id: cr-config-secret-state-on-admin-channel
title: The Server Owner reads the config-secret state on the admin channel
status: planned
satisfies: ["uc.admin.back-up-and-restore-instance"]
adrs: ["0050", "0065", "0056"]
invariants:
  - "Operator-blind E2EE: the state is operational (OK / ABSENT / MISMATCH), never key material or a sealed value"
  - "API-first: the new operation is declared in contracts/api/instance.openapi.yaml before any code"
---

## Goal

Let the Server Owner read the instance's config-secret state — `OK`, `ABSENT` or `MISMATCH`
([ADR 0050](../docs/decisions/0050-operator-config-secret-encryption.md)) — on the admin channel, in the admin
console's Server section and through `veyq-admin`, without owning a tenant. Today the only read is a tenant's mail
settings, owner-gated to that tenant, so the upgrade and restore runbooks' verification step ("read the
config-secret state as the Server Owner") cannot be run by the person it names. Reading must change nothing:
today's `ConfigSecretKeyGuard.state()` adopts a fingerprint when none is stored, which a GET must not do.

## Context & constraints

- [uc.admin.back-up-and-restore-instance](../docs/features/admin/use-cases/uc.admin.back-up-and-restore-instance.md)
  step 11 and its criterion `the-server-owner-reads-the-config-secret-state`.
- [ADR 0065](../docs/decisions/0065-admin-ui-cli-separate-service.md): Server-Owner operations live on the admin
  listener only; `x-veyq-channel: admin` on the operation, `AdminChannelFilter` and
  `AdminChannelPostureSweepTest` keep marker and gate in step.
- `contracts/api/instance.openapi.yaml`: the two existing Server-Owner reads (`getInstanceRelease`,
  `getInstanceStoreProvisioning`) are the pattern; `InstanceResource` carries `@RolesAllowed(SERVER_OWNER)` and
  `@RateLimitExempt`; `OperatorBlindProbeTest` lists them in its Server-Owner exclusion bucket.
- `backend/core/.../crypto/ConfigSecretKeyGuard.kt`: `state()` is `@Transactional` and adopts the fingerprint on
  first read; `TenantMailSettingsHandler` and `TenantMailSender` rely on that.
- `cr-store-connection-in-admin-web` will move to a fingerprint per sealed value (ADR 0089 decision 4); this CR
  reads the state as it is today and does not anticipate that shape.

### Locators

| What | Locator |
|---|---|
| The state and its callers | `git grep -n "ConfigSecretKeyGuard\|ConfigSecretKeyState\|configSecretState"` |
| The admin-channel instance reads | `git grep -n "getInstanceRelease\|getInstanceStoreProvisioning"` |
| Runbooks and docs that tell an operator to read the state | `git grep -n "configSecretState\|config-secret state" -- site docs infra` |

## Phases

- [x] 1 contracts + backend — a Server-Owner read on the admin channel in `instance.openapi.yaml` (enum
      `x-veyq-visibility`), a read-only state on the guard (the adopting path stays for the mail handlers), the
      handler on `InstanceResource`, the operation in `OperatorBlindProbeTest`'s Server-Owner bucket. · Done when:
      `./gradlew check` green; bites: a non-Server-Owner caller gets no state, on the public port the operation
      404s, and a read on an instance with no stored fingerprint leaves it with none (the adopting call swapped in
      turns that test red).
- [x] 2 admin-web + CLI — the state shown in the Server section beside release and store provisioning, with what
      ABSENT and MISMATCH mean for the operator; `veyq-admin config-secret` printing it. · Done when: `npm run check`
      in `admin-web/` green; a browser pass shows the state on a dev instance.
- [x] 3 docs + site — the runbooks' verification step (`site/src/content/docs/run/upgrade.mdx`,
      `backup-and-restore.md`, `new-instance.mdx`) reads the state on the admin channel instead of through a
      tenant's mail settings; the coverage criterion gets its backend ref. · Done when: trust-chain green and
      `npm run check` in `site/` green.

## Definition of done

A Server Owner who owns no tenant reads the config-secret state in the console and the CLI, the read changes
nothing, and every runbook step that asks for the state names a surface the Server Owner can reach.
`uc.admin.back-up-and-restore-instance`'s new criterion carries its backend ref.

## Retrospective

(filled at close)
