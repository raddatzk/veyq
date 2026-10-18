// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import starlightLinksValidator from 'starlight-links-validator';

// The published documentation (ADR 0087). Static output only, so it is hostable anywhere; GitHub Pages serves it
// at https://raddatzk.github.io/veyq/, hence `base`.
export default defineConfig({
  site: 'https://raddatzk.github.io',
  base: '/veyq/',
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
      // Spaces), then the applications (Memories, Depot), then Sharing, the space owner's tasks, Privacy and Help.
      // A section's pages live in its folder (getting-started/, account/, spaces/, memories/, depot/, sharing/,
      // space-owners/, privacy/, help/), so a page's URL says its section. A section with no page yet is left out
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
      // Fails the build on a broken internal link or anchor — the link check of `npm run check`. A link to a
      // fallback page is valid: Run and Security are English-only by design. Not checked: a German page linking
      // the English URL instead of its /de/ counterpart — the plugin's `errorOnInconsistentLocale` matches `/de/`
      // at the start of the link, so behind this `base` it never fires (bitten 2026-10-15: green on such a link).
      plugins: [starlightLinksValidator({ errorOnFallbackPages: false })],
    }),
  ],
});
