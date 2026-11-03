# ADR 0116 — The JavaScript packages are installed by pnpm

- Status: Accepted
- Built: yes
- Date: 2026-11-03

## Context

The repository installed its JavaScript packages with npm: one workspace at the root (`src/packages/crypto`,
`src/web`, `src/admin-web`, `tests/e2e`, [ADR 0065](0065-admin-ui-cli-separate-service.md)) and `site/` on its own
([ADR 0087](0087-published-docs-are-a-starlight-site-docs-is-for-development.md)). Two costs had grown:

- **Disk per checkout.** Work runs in many git worktrees, and npm gives each its own `node_modules` — measured
  2026-11-03: 18 worktrees ≈ 8.5 GB, ~470 MB each, plus 485 MB for `site/`.
- **Undeclared imports pass.** npm hoists every package to the root `node_modules`, so a member resolves what only
  another member declares. `jsqr` built green in `src/admin-web/` and failed only in the admin image, which never sees
  `src/web/`.

## Decision

1. **pnpm installs every JavaScript package**, pinned exactly in the root `package.json` `packageManager`
   (`pnpm@12.16.1` when this was written). The workspace members are listed in `pnpm-workspace.yaml`, never globbed;
   `site/` keeps a pnpm root of its own with its own lockfile. pnpm's store is shared by every checkout on a machine,
   and its strict `node_modules` links each member only what it declares.
2. **The pin is held, not trusted.** A pnpm of another version run in the repository switches itself to the pinned
   one, and `pnpm-lock.yaml`'s first document pins pnpm's own executables by integrity. Every place a build installs
   pnpm — `npm install -g pnpm@<version>` in the workflows and Dockerfiles, the one npm install left — names the pinned
   version; `scripts/validate_pnpm_installs_gated.py` holds them equal. Corepack is not used: Node is unbundling it,
   and it could not run pnpm 12 when this was written.
3. **Dependency install scripts are a decision.** pnpm runs no dependency's lifecycle script unless
   `pnpm-workspace.yaml` `allowBuilds` allows it, and refuses an install that meets an undecided one. Each entry
   says why it is allowed or refused.
4. **The deprecation gate stays a gate** (root CLAUDE.md, no deprecated dependencies):
   `scripts/pnpm_install_no_deprecated.py` installs the lockfile frozen and refuses a locked package that the
   lockfile or the registry marks deprecated. A frozen pnpm install prints no deprecation warning of its own, so the
   gate's registry half asks about every locked package, once per lockfile and commit.

## Alternatives

Checked 2026-11-03 against the registry and the projects' own pages, not remembered:

- **pnpm 11** (JavaScript, `latest-11` 11.28.9 of 2026-10-27, still released) works through Corepack, but pnpm 12 is
  the npm `latest` (12.16.1 of 2026-10-27) and the line the project calls current; its default branch had commits the
  same day. Starting on the older major buys a forced migration soon.
- **Yarn Plug'n'Play** removes `node_modules` altogether and needs its loader and editor SDKs for Vite, Vitest,
  ESLint, Astro, Playwright and IntelliJ. Yarn with a `node_modules` linker saves no disk.
- **Bun's isolated installs** come closest to pnpm's layout, but a package in Bun's store can still resolve
  dependencies it never declared unless hoisting is turned off, and the package manager is one vendor's runtime.

Sources: [registry.npmjs.org/pnpm](https://registry.npmjs.org/pnpm) (dist-tags and release dates),
[pnpm installation](https://pnpm.io/installation), [pnpm v12.0.0 release notes](https://github.com/pnpm/pnpm/releases/tag/v12.0.0),
[Bun isolated installs](https://bun.com/docs/pm/isolated-installs).

## Consequences

- **A checkout's install costs ~16 MB of disk instead of ~470 MB** (measured 2026-11-03 on APFS, the free-space delta
  of a frozen `--ignore-scripts` install in a fresh checkout with a warm store, twice: 16.0 and 16.8 MiB, in 4.1 s each;
  `du` still reports 437 MB, because it counts each cloned file in full). The generator JAR
  (`node_modules/.openapi-generator/`, 31 MB) is still fetched per checkout.
- **An install that changes nothing runs no lifecycle script**, so a contracts change no longer regenerates a client
  through `prepare` alone; the launcher's `bootstrap` tasks run `generate:api` after the install.
- **A checkout nested inside another one still resolves the outer one's `node_modules`.** Node walks up from the
  importing file, so in a worktree under `.claude/worktrees/` an undeclared import can resolve from the main
  checkout's install. A proof that a member declares what it imports runs where no outer `node_modules` exists: the
  image builds, or a checkout outside the repository.
- CI keeps no package cache for now: setup-node's cache needs pnpm on the `PATH` before it runs.
