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
      sidebar: [
        {
          label: 'Use',
          translations: { de: 'Benutzen' },
          items: [
            { slug: 'index', label: 'Start', translations: { de: 'Start' } },
            'is-my-data-safe',
            'files',
            'sharing',
            'people-in-your-memories',
            'download-and-import',
            'account-keys-and-recovery',
            'who-gets-into-your-space',
            'adding-another-device',
            'quantum-computers-and-your-photos',
          ],
        },
        {
          label: 'Run',
          translations: { de: 'Betreiben' },
          items: [
            { slug: 'run', label: 'Overview', translations: { de: 'Überblick' } },
            'run/verify-a-release',
            'run/new-instance',
            'run/kubernetes',
            'run/upgrade',
            'run/backup-and-restore',
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
