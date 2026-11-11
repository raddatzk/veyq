// The bundle's entry point, and nothing else — `cli.ts` holds the logic and stays importable by its tests
// without running anything. `process.exitCode` rather than `process.exit()`: stdout may still be draining.
//
// This file is what `pnpm run build:cli` hands to esbuild, so the flags it is built with belong here:
//   --platform=node --format=esm  ESM because this file uses top-level await, and because `@veyq/crypto`'s
//                                 worker module carries an `import.meta.url` that CJS cannot express. The
//                                 output lands in `src/admin-web/dist-cli/`, whose nearest package.json is this
//                                 package's `"type": "module"` — the container has to say so for itself.
//   --bundle                      `@veyq/crypto` is consumed as TypeScript SOURCE with extensionless relative
//                                 imports; nothing but a bundler resolves it. This is also what makes the
//                                 shipped file standalone: no node_modules in the image at all.
//   --define:process.env.NODE_ENV Selects Vue's production build. Vue is here because the CLI signs in
//                                 through the app's own `login()` (one sign-in path, ADR 0065) and that
//                                 reaches `app/session.ts`'s `reactive()` — so the bundle carries a UI
//                                 framework it never renders with, 1.5 MB of measured dead weight. Accepted:
//                                 the alternative is a second implementation of the sign-in sequence, which
//                                 is the thing ADR 0065 exists to avoid.
import { run } from './cli'

process.exitCode = await run(process.argv.slice(2), process.env, console.log, console.error, process.stdout.columns)
