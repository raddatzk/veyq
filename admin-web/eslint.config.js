import pluginVue from 'eslint-plugin-vue'
import { defineConfigWithVueTs, vueTsConfigs } from '@vue/eslint-config-typescript'
import { argonThroughTheWorker } from '@veyq/crypto/eslint-argon-door'

// Flat config (ESLint 10). The type-checked ruleset is deliberately NOT enabled: this is a
// baseline guardrail (cr-0131 / audit P1a), not a strictness sweep. Unused-locals/params are
// covered by tsconfig (`noUnusedLocals`/`noUnusedParameters`); ESLint adds the Vue-template
// rules that tsc cannot see — notably `vue/no-v-html`, which the app relies on being enforced
// wherever it renders decrypted content.
export default defineConfigWithVueTs(
  {
    name: 'veyq-admin/files',
    files: ['**/*.{ts,mts,vue}'],
  },
  {
    name: 'veyq-admin/ignores',
    ignores: [
      'dist/**',
      // The CLI bundle (`npm run build:cli`) — generated, never hand-edited, and a lint run over a bundled
      // copy of every dependency reports on code this repo does not own.
      'dist-cli/**',
      'node_modules/**',
      'src/api/generated/**', // generated client — mirrors the contracts, never hand-edited
    ],
  },
  pluginVue.configs['flat/essential'],
  vueTsConfigs.recommended,
  {
    name: 'veyq-admin/rules',
    rules: {
      // The console confirms through its own dialog (`shared/state/useConfirm`), never the browser's: a native
      // box carries no product voice, no danger colour, and here it carries the sentence that has to spell out
      // that taking a tenant offline changes nothing inside it (cr-admin-web-own-confirm-dialog). This is what
      // keeps that swap from being a one-time sweep. Deliberately NOT exempted for test files: nothing in `src`
      // may reach for a native dialog, and a fixture is the easiest place to quietly re-introduce one — which
      // here would be worse than a style lapse, since a stubbed `window.confirm` passes a confirm-gating test
      // by confirming nothing at all.
      'no-alert': 'error',
      // Security backstop: the server is operator-blind and the client renders decrypted HTML.
      // Any `v-html` must be an explicit, reviewed opt-out with a sanitising helper — never silent.
      'vue/no-v-html': 'error',
      // The "dead imports/refs unguarded" guardrail (audit P1a). This carries the intent of
      // tsconfig `noUnusedLocals`/`noUnusedParameters` but — unlike those unscopable compiler
      // flags — it honours the ESLint ignore list, so the generated API client (regenerated,
      // gitignored, never hand-edited; its typescript-fetch template emits inherently-unused
      // params like `ignoreDiscriminator`) is not falsely flagged. `args: 'all'` matches
      // `noUnusedParameters`; the `^_` escape hatch matches tsc's own convention.
      '@typescript-eslint/no-unused-vars': [
        'error',
        {
          args: 'all',
          argsIgnorePattern: '^_',
          varsIgnorePattern: '^_',
          caughtErrorsIgnorePattern: '^_',
          destructuredArrayIgnorePattern: '^_',
        },
      ],
    },
  },
  // Argon2id only through @veyq/crypto/argonWorker — the rule, its exemptions and its limit live with the crypto
  // package (eslint-argon-door.js), written once for the three configs that include it.
  argonThroughTheWorker,
  {
    // Test files, test kits, dev seeders and build config may use `any` for terse fixtures /
    // untyped tooling boundaries. Production `src` code stays held to no-explicit-any (currently
    // zero violations — the guard exists to keep it that way).
    name: 'veyq-admin/tests-and-tooling',
    files: [
      '**/*.test.ts',
      '**/*.spec.ts',
      'vite.config.ts',
      'eslint.config.js',
    ],
    rules: {
      '@typescript-eslint/no-explicit-any': 'off',
    },
  },
)
