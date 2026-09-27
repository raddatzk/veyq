import pluginVue from 'eslint-plugin-vue'
import { defineConfigWithVueTs, vueTsConfigs } from '@vue/eslint-config-typescript'

// Flat config (ESLint 10). The type-checked ruleset is deliberately NOT enabled: this is a
// baseline guardrail (cr-0131 / audit P1a), not a strictness sweep. Unused-locals/params are
// covered by tsconfig (`noUnusedLocals`/`noUnusedParameters`); ESLint adds the Vue-template
// rules that tsc cannot see — notably `vue/no-v-html`, which the app relies on being enforced
// wherever it renders decrypted content.
export default defineConfigWithVueTs(
  {
    name: 'veyq/files',
    files: ['**/*.{ts,mts,vue}'],
  },
  {
    name: 'veyq/ignores',
    ignores: [
      'dist/**',
      'node_modules/**',
      'src/api/generated/**', // generated client — mirrors the contracts, never hand-edited
    ],
  },
  pluginVue.configs['flat/essential'],
  vueTsConfigs.recommended,
  {
    name: 'veyq/rules',
    rules: {
      // The app confirms through its own dialog (`shared/state/useConfirm`), never the browser's: a native
      // box carries no product voice, no danger colour, and on the sessions panel the longest sentence the
      // app owns (cr-web-own-confirm-dialog). This is what keeps that swap from being a one-time sweep.
      // Deliberately NOT exempted for test files: nothing in `src` may reach for a native dialog, and a
      // fixture is the easiest place to quietly re-introduce one.
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
  {
    // One sign-out (uc.account.sign-out, ADR 0070): a screen calls `features/account/login/signOut`, never the
    // ending in `app/sessionEnd` — a screen that could reach the ending could assemble its own sign-out, and a
    // screen-level copy that skipped half the sequence is what this replaced. LIMIT: a `.ts` composable is not
    // covered, and neither is a screen that calls the session API's revoke directly and then navigates.
    name: 'veyq/one-sign-out',
    files: ['**/*.vue'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              regex: '(^|/)sessionEnd(\\.[jt]s)?$',
              message: 'A screen signs out through features/account/login/signOut, never the session ending itself.',
            },
          ],
        },
      ],
    },
  },
  {
    // A test renders the REAL catalog, through `src/test/i18n.ts` (inbox #171). A hand-written stub catalog, or a
    // mocked `t`, keeps a test green on a string the app no longer shows: stub keys had outlived the catalog
    // when this rule was written, among them the retired `members.copyLink`. LIMIT: a stub reached some other way
    // (a hand-rolled plugin providing `$t`) is not caught.
    name: 'veyq/tests-use-the-real-catalog',
    files: ['src/**/*.test.ts'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          paths: [
            {
              name: 'vue-i18n',
              importNames: ['createI18n'],
              message: 'Mount with realI18n() from src/test/i18n — the real catalog, where a missing key fails the test.',
            },
          ],
        },
      ],
      'no-restricted-syntax': [
        'error',
        {
          selector: "CallExpression[callee.object.name='vi'][callee.property.name=/^(mock|doMock)$/][arguments.0.value='vue-i18n']",
          message: 'Do not mock vue-i18n; mount with realI18n() from src/test/i18n and assert through its t().',
        },
      ],
    },
  },
  {
    // Test files, test kits, dev seeders and build config may use `any` for terse fixtures /
    // untyped tooling boundaries. Production `src` code stays held to no-explicit-any (currently
    // zero violations — the guard exists to keep it that way).
    name: 'veyq/tests-and-tooling',
    files: [
      '**/*.test.ts',
      '**/*.spec.ts',
      'scripts/**/*.mts',
      'src/features/memory/components/galleryFakeStore.ts', // documented gallery test kit (imports vitest)
      'vite.config.ts',
      'eslint.config.js',
    ],
    rules: {
      '@typescript-eslint/no-explicit-any': 'off',
    },
  },
)
