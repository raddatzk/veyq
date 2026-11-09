// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import starlightLinksValidator from 'starlight-links-validator';

// The published documentation (ADR 0087). Static output only, so it is hostable anywhere; GitHub Pages serves it
// at the domain root of https://veyq.app/ (the custom domain is a Pages setting: a site deployed by a workflow reads
// no CNAME file), so there is no `base` and a page links another as `/run/upgrade/`. The old project URL
// https://raddatzk.github.io/veyq/… redirects there, which keeps links in shipped releases working.
export default defineConfig({
  site: 'https://veyq.app',
  integrations: [
    starlight({
      title: 'veyq',
      // English is the root locale; a German page is the English page's translation at the SAME path under
      // `de/`, which is how Starlight pairs them. A page without a translation falls back to English with
      // Starlight's notice — how Run and Security appear under /de/.
      defaultLocale: 'root',
      locales: {
        root: { label: 'English', lang: 'en' },
        de: { label: 'Deutsch', lang: 'de' },
      },
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
      // link); a `base` brought back turns it blind again.
      plugins: [starlightLinksValidator({ errorOnFallbackPages: false, errorOnInconsistentLocale: true })],
    }),
  ],
});
