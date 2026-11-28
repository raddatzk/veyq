// @ts-check
import { defineConfig, envField } from 'astro/config';
import starlight from '@astrojs/starlight';
import starlightLinksValidator from 'starlight-links-validator';
import { satteri } from '@astrojs/markdown-satteri';
import baseLinks from './plugins/base-links.mjs';

// The published documentation (ADR 0087). Static output only, so it is hostable anywhere; GitHub Pages serves it
// at the domain root of https://veyq.app/ (the custom domain is a Pages setting: a site deployed by a workflow reads
// no CNAME file), so the root build has no `base` and a page links another as `/run/upgrade/`. The old project URL
// https://raddatzk.github.io/veyq/… redirects there, which keeps links in shipped releases working.
//
// Versions (ADR 0087 decision 6): `main` is the root build; an older major's docs are the build of its newest
// release, made with VEYQ_SITE_VERSION=<major> and served under `/v<major>/`. Only that variable sets the base, so
// a version cannot be built under a path of another. VEYQ_SITE_OLDER (`1` or `1,2`) is the root build's list of the
// older versions the deploy serves beside it, named in every root page's header; the two are never set together.
const major = (/** @type {string | undefined} */ value, /** @type {string} */ name) => {
  if (value && !/^[1-9][0-9]*$/.test(value)) throw new Error(`${name} is a major (1, 2, …), not ${JSON.stringify(value)}`);
  return value || undefined;
};
const version = major(process.env.VEYQ_SITE_VERSION, 'VEYQ_SITE_VERSION');
const older = (process.env.VEYQ_SITE_OLDER ?? '').split(',').filter(Boolean).map((m) => major(m, 'VEYQ_SITE_OLDER'));
if (version && older.length) throw new Error('VEYQ_SITE_OLDER names the root build\'s older versions; a version build has none');
const base = version ? `/v${version}/` : '/';

const locales = {
  root: { label: 'English', lang: 'en' },
  de: { label: 'Deutsch', lang: 'de' },
};
// The project website's pages, one per locale (decision 7), exist at the root only: a version's docs link them there.
const websitePages = ['/', ...Object.keys(locales).filter((key) => key !== 'root').map((key) => `/${key}/`)];

export default defineConfig({
  site: 'https://veyq.app',
  base,
  // Read by the components the version shows itself with (src/components/starlight/, the website's pages).
  env: {
    schema: {
      VEYQ_SITE_VERSION: envField.string({ context: 'server', access: 'public', optional: true }),
      VEYQ_SITE_OLDER: envField.string({ context: 'server', access: 'public', optional: true }),
    },
  },
  // Sätteri is Astro's default Markdown processor; it is named here only to hand it the plugin. Starlight adds its own
  // plugins to whichever processor is configured.
  markdown: {
    processor: satteri({ hastPlugins: [baseLinks({ base, keep: websitePages })] }),
  },
  integrations: [
    starlight({
      title: 'veyq',
      // English is the root locale; a German page is the English page's translation at the SAME path under
      // `de/`, which is how Starlight pairs them. A page without a translation falls back to English with
      // Starlight's notice — how Run and Security appear under /de/.
      defaultLocale: 'root',
      locales,
      // A version names its major on every page and links the current docs; the root names the older versions in
      // its header. A version is kept out of search engines, so it never ranks against the current docs.
      components: {
        Banner: './src/components/starlight/Banner.astro',
        SocialIcons: './src/components/starlight/SocialIcons.astro',
      },
      head: version ? [{ tag: 'meta', attrs: { name: 'robots', content: 'noindex' } }] : [],
      // One group per area and reader: Use (EN+DE, users), Run (EN, administrators), Security (EN, researchers).
      // Use is task-first in nine sections (cr-use-area-content): platform first (Getting started, Your account,
      // Spaces), then the applications (Memories, Depot, Expenses), then Sharing, the space owner's tasks, Privacy and Help.
      // A section's pages live in its folder (getting-started/, account/, spaces/, memories/, depot/, expenses/,
      // sharing/, space-owners/, privacy/, help/), so a page's URL says its section. A section with no page yet is left out
      // rather than shown empty. The labels are the web client's own words in each language.
      sidebar: [
        {
          label: 'Use',
          translations: { de: 'Benutzen' },
          items: [
            { slug: 'start', label: 'Start', translations: { de: 'Start' } },
            {
              label: 'Getting started',
              translations: { de: 'Erste Schritte' },
              items: [
                'getting-started',
                'getting-started/accept-an-invitation',
                'getting-started/emergency-kit',
                'getting-started/sign-in',
                'getting-started/install-the-app',
                'getting-started/use-content-offline',
                'getting-started/use-veyq-on-a-tv',
                'getting-started/share-into-veyq',
                'getting-started/keyboard-shortcuts',
              ],
            },
            {
              label: 'Your account',
              translations: { de: 'Dein Konto' },
              items: [
                'account/devices-and-sessions',
                'account/adding-another-device',
                'account/change-password',
                'account/forgot-password',
                'account/change-email',
                'account/replace-your-keys',
                'account/your-profile',
                'account/appearance-and-language',
                'account/verify-a-person',
                'account/moderate',
                'account/erase-your-account',
              ],
            },
            {
              label: 'Spaces',
              translations: { de: 'Bereiche' },
              items: [
                'spaces',
                'spaces/folders',
                'spaces/move-things',
                'spaces/copy-things',
                'spaces/select-several',
                'spaces/search',
                'spaces/views-and-favorites',
                'spaces/give-a-folder-a-cover',
                'spaces/trash',
                'spaces/download-and-import',
                'spaces/create-a-group',
                'spaces/group-members',
                'spaces/group-invitations',
                'spaces/manage-a-group',
              ],
            },
            {
              label: 'Applications',
              translations: { de: 'Anwendungen' },
              items: [
                { slug: 'applications', label: 'Overview', translations: { de: 'Überblick' } },
                {
                  label: 'Memories',
                  translations: { de: 'Erinnerungen' },
                  items: [
                    { slug: 'memories', label: 'What Memories is for', translations: { de: 'Wofür Erinnerungen da sind' } },
                    'memories/create-a-memory-and-chapters',
                    'memories/add-photos-and-videos',
                    'memories/the-gallery',
                    'memories/set-the-date',
                    'memories/set-the-location',
                    'memories/people-in-your-memories',
                    'memories/set-a-cover',
                    'memories/add-a-banner',
                    'memories/add-gpx-tracks',
                    'memories/add-background-music',
                    'memories/marked-photos',
                    'memories/contributions',
                    'memories/photo-trash',
                  ],
                },
                {
                  label: 'Depot',
                  items: [
                    { slug: 'depot', label: 'What Depot is for', translations: { de: 'Wofür das Depot da ist' } },
                    'depot/upload-files-and-folders',
                    'depot/view-a-file',
                    'depot/replace-a-file',
                    'depot/rename-a-file',
                    'depot/delete-a-file',
                    'depot/drop-boxes',
                  ],
                },
                {
                  label: 'Expenses',
                  translations: { de: 'Ausgaben' },
                  items: [
                    { slug: 'expenses', label: 'What Expenses is for', translations: { de: 'Wofür Ausgaben da ist' } },
                    'expenses/record-an-expense',
                    'expenses/settle-up',
                    'expenses/share-a-pot',
                  ],
                },
              ],
            },
            {
              label: 'Sharing',
              translations: { de: 'Teilen' },
              items: [
                'sharing',
                'sharing/create-a-link',
                'sharing/request-uploads',
                'sharing/manage-links',
                'sharing/what-guests-see',
                'sharing/keep-a-link',
                'sharing/open-a-link-in-the-app',
              ],
            },
            {
              label: 'For space owners',
              translations: { de: 'Wenn dir der Bereich gehört' },
              items: [
                'space-owners/who-gets-into-your-space',
                'space-owners/terms',
                'space-owners/email',
                'space-owners/storage',
                'space-owners/capabilities',
              ],
            },
            {
              label: 'Privacy',
              translations: { de: 'Privatsphäre' },
              items: ['privacy/is-my-data-safe', 'privacy/quantum-computers-and-your-photos'],
            },
            {
              label: 'Help',
              translations: { de: 'Hilfe' },
              items: [
                'help',
                'help/lost-emergency-kit',
                'help/lost-device',
                'help/key-changed',
                'help/start-a-list-over',
                'help/offline',
              ],
            },
            { slug: 'donate', label: 'Support veyq', translations: { de: 'veyq unterstützen' } },
          ],
        },
        {
          label: 'Run',
          translations: { de: 'Betreiben' },
          items: [
            { slug: 'run', label: 'Overview', translations: { de: 'Überblick' } },
            'run/plan',
            'run/verify-a-release',
            'run/new-instance',
            'run/first-run',
            'run/kubernetes',
            'run/gitops',
            'run/configuration',
            'run/capabilities',
            'run/networking',
            'run/storage-and-email',
            'run/upgrade',
            'run/backup-and-restore',
            'run/operations',
            'run/troubleshooting',
            'run/hardening',
            'run/moderation',
            'run/data-protection',
          ],
        },
        {
          label: 'Security',
          translations: { de: 'Sicherheit' },
          items: [
            'security/start-here',
            'security/threat-model',
            'security/what-the-server-sees',
            'security/cryptography',
            'security/key-hierarchy',
            'security/protocols',
            'security/how-veyq-is-tested',
            'security/verify-it-yourself',
            'security/known-limitations',
            'security/roadmap',
            'security/reporting',
          ],
        },
      ],
      // Fails the build on a broken internal link or anchor — the link check of `pnpm run check`. A link to a
      // fallback page is valid: Run and Security are English-only by design. A German page linking the English URL
      // instead of its /de/ counterpart fails too (`errorOnInconsistentLocale`). That option matches `/de/` at the
      // start of the link, so it never fired while the site had a `/veyq/` base (bitten 2026-10-15: green on such a
      // link); a `base` brought back turns it blind again. So it runs on the root build alone: a version is built
      // from a commit whose root build it already passed, and the base it adds is held by site_version_check.py.
      plugins: version ? [] : [starlightLinksValidator({ errorOnFallbackPages: false, errorOnInconsistentLocale: true })],
    }),
  ],
});
