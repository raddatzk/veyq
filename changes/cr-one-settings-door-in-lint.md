---
id: cr-one-settings-door-in-lint
title: The one door into the settings hub is a lint rule over every spelling, not a regex over .vue files
status: planned
satisfies: []
adrs: []
invariants: []
---

## Open for the owner

**Third round answered by the owner, 2026-10-01: every recommendation added since the second answer is taken too** (questions added by the third refine wave and by implementation hand-backs). Rows those questions proposed exist in `docs/inbox.md` (#233–#243).

**Second round answered by the owner, 2026-10-01: every recommendation added since the first answer is taken too** (questions added by the 2026-09-28 second refine wave). Rows those questions proposed exist as #222–#232.

**Answered by the owner, 2026-09-28: every recommendation below is taken, and implementation has the go.** Read each question's recommendation as its decision. Where a question proposed a separate inbox row, that row exists (#197–#221, `docs/inbox.md`).

1. **Move the check into ESLint, or widen the vitest scan?** The row asked whether to widen the scan and whether
   to promote it to `scripts/`. **Recommendation: neither. Make it an ESLint `no-restricted-syntax` block**,
   like `veyq/one-sign-out` in the same file. The case for it, measured below:
   - `npm run lint` already parses every `.ts` and `.vue` under `src`, so the 3× file set costs nothing extra
     and the timeout `b0b96ad` had to justify goes away.
   - It matches AST nodes rather than text, so a comment that names the route stops being a false positive.
     The widened regex would be red on the untouched tree for exactly that reason: `AddDevicePanel.vue:11` has
     a comment naming `/settings/:section?`.
   - A second legitimate exception becomes one `ignores` entry, which answers the test's own *"promote it"*
     trigger without a new guard file.
2. **Is `SettingsHubView.vue` exempt as a whole file?** It is today. **Recommendation: yes.** Its deep links
   (`/settings/${id}`, `/settings/group-${scopeId}`) navigate *inside* the hub, and the old test excluded it for
   the same reason. What that costs: a door added *to the hub itself* is not seen. That door would lead to
   the hub from the hub, so there is nothing to catch.

## Goal

Inbox #183. Today `ProfileMenu.test.ts` › *only ProfileMenu navigates to /settings* regex-scans `*.vue` for two
spellings. After this CR a lint rule refuses every **static** spelling of a navigation to the settings hub, in
every non-test `.ts` and `.vue` under `web/src`, except the three files that legitimately hold one. The
spellings are: the path, a deep link, the named route, a static `to=`/`href=`, and a bound `:to`. A positive
mount test keeps the one real door honest.

## Context & constraints

- The rule's home: `web/eslint.config.js`. Its precedent is `veyq/one-sign-out`, a `no-restricted-*` block
  with its **LIMIT** stated in the comment. `eslint-plugin-vue` 10.9.2 ships `vue/no-restricted-syntax` (the
  selectors, applied to template expressions) and `vue/no-restricted-static-attribute`.
- **Measured on** `faf9ef4`, in worktree `worktree-agent-a988acad05eff2335`, 2026-09-28. Re-run the locators.
- **Not a live defect.** Both angles return only the three legitimate files, plus one comment:
  - the row's grep:
    `grep -rnE "push\(['\"]/settings['\"]\)|to=[\"']/settings[\"']|['\"\`]/settings/|name: *['\"]settings['\"]" web/src --include='*.ts' --include='*.vue' | grep -v '\.test\.ts:'`;
  - a looser one:
    `grep -rnE "/settings|name: *['\"]settings|['\"]settings['\"]" web/src --include='*.ts' --include='*.vue' | grep -v '\.test\.ts:' | grep -v api/generated`.

  The hits are `router.ts:38` (the declaration), `ProfileMenu.vue:55` (the door), `SettingsHubView.vue:137/167/168/173`
  (inside the hub) and `AddDevicePanel.vue:11` (a comment). The file set is now 68 `.vue` and 146 non-test `.ts`
  outside `api/generated`; the row counted 65 and 135.
- **The prototype, run at refine and then deleted.** It was a flat config extending `eslint.config.js` with the
  block below, run as `npx eslint -c <probe> src`:
  - **green** on the untouched tree;
  - **red on all 8 injected spellings**: in a `.ts`, `push('/settings')`, `push({ name: 'settings' })`,
    `` push(`/settings/${s}`) `` and `replace({ path: '/settings/devices' })`; in a `.vue`, `to="/settings"`,
    `:to="{ name: 'settings' }"`, `@click="$router.push('/settings/devices')"` and `href="/settings"`;
  - **silent on the two comments** injected alongside them.

  The selectors that did it:
  - `Literal[value=/^\/settings(\/|$)/]`
  - `TemplateElement[value.raw=/^\/settings(\/|$)/]`
  - `Property[key.name='name'][value.value='settings']`

  These go into both `no-restricted-syntax` and `vue/no-restricted-syntax`. Add
  `vue/no-restricted-static-attribute` for `to` and `href` with value `/^\/settings(\/|$)/`. The `ignores` are
  `src/**/*.test.ts`, `src/router.ts`, `src/components/app/ProfileMenu.vue` and `src/views/SettingsHubView.vue`.

## Decisions

1. **The block `veyq/one-settings-door`** as prototyped above. Its comment states the property (*the shell
   offers exactly one door into the settings hub*) and why each ignored file is ignored. It also states the
   **LIMIT**: a path assembled at runtime is not a literal, so the rule does not see it (`'/' + 'settings'`,
   a variable passed as `path`, `window.location`). Neither did the test it replaces.
2. **Delete the source-scan `describe` from `ProfileMenu.test.ts`,** including its `readdirSync`/`join`
   imports and the timeout comment. That comment's reason goes away with it.
3. **Add the positive control the scan used to carry implicitly.** The old `toEqual(['components/app/ProfileMenu.vue'])`
   also asserted the door *exists*, and a lint rule only asserts absence elsewhere. So: mount `ProfileMenu`,
   click the settings item, and assert the router's `push` was called with `'/settings'`. The router mock has
   to hand back one shared `push` spy; today every `useRouter()` call returns a fresh `vi.fn()`.

## Phases

- [ ] 1 web: Decisions 1–3. · Done when: `npm run check` in `web/` is green, and the bites below are recorded.

## Definition of done

- Before the first edit, run the guard baseline and paste it: `for f in scripts/validate_*.py; do python3 "$f";
  done`. It stays green.
- **Red first, by injection.** The defect is not live, so there is no untouched-tree red to show. Bite with
  snapshot-and-restore: add a throwaway `src/features/<x>/probe.ts` and a `Probe*.vue` carrying the 8
  spellings above, plus one comment naming `/settings/`. `npx eslint src` must report **8** errors from the
  new block and none for the comment. Delete both files. The rule's lever is its `ignores` list: add
  `src/features/**` to it, re-run, and the `.ts` errors must disappear. Then restore. That shows the scope is
  what gates it.
- **Bite the positive control:** change `ProfileMenu.vue`'s settings `command` to push `'/'`. The new mount
  test goes red. Restore.
- Inbox #183 → done. This file is deleted after harvest.

## Retrospective

_(filled at implement)_
