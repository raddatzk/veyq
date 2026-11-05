// Flat config (ESLint 10) for lane L1's browser suite. Narrow like packages/crypto's: no Vue plugin (there are no
// components here), the unused-import guardrail and typescript-eslint's recommended set.
import tseslint from 'typescript-eslint'

export default tseslint.config(
  { name: 'veyq-e2e/ignores', ignores: ['node_modules/**', 'test-results/**'] },
  ...tseslint.configs.recommended,
  {
    name: 'veyq-e2e/rules',
    files: ['**/*.ts'],
    rules: {
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
    // Every test runs under the policy-violation fixture (src/cspViolations.ts, ADR 0117): a spec that took `test` from
    // Playwright itself would run with a violation counted as nothing.
    name: 'veyq-e2e/csp-violation-fixture',
    files: ['tests/**/*.ts'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          paths: [
            {
              name: '@playwright/test',
              importNames: ['test'],
              message: "Import `test` from src/cspViolations: there a page's Content-Security-Policy violation fails the test.",
            },
          ],
        },
      ],
    },
  },
)
