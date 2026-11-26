// Puts the site's `base` in front of every root-relative link a docs page writes (ADR 0087 decision 6). Astro prefixes
// the base to what it generates itself — Starlight's navigation, locale switch, assets, search index — but never to a
// link written in Markdown or MDX, and the pages link each other as `/run/upgrade/`. Built under `/v1/`, an older
// major's docs would otherwise send every such link into the current major's docs at the root.
//
// A hast plugin of Sätteri, Astro's Markdown processor (`satteri({ hastPlugins })` in astro.config.mjs), run on `.md`
// and `.mdx` alike. Left alone: a link that is not root-relative (`https://…`, `#…`, `//host/…`), one that already
// carries the base, and the project website's pages (`keep`: `/` and one `/<lang>/` per further locale), which exist
// at the root only — a version has no website. At `base: '/'` it changes nothing, so the root build the link check
// proves is the build it was before. A link it cannot see (raw HTML, a component's `href`) is caught after the build:
// `scripts/site_version_check.py` is red on a version build that carries an internal link without the base.

/**
 * @param {{ base: string, keep: Iterable<string> }} options
 */
export default function baseLinks({ base, keep }) {
  const prefix = base.replace(/\/+$/, '');
  const kept = new Set(keep);

  /** @param {unknown} href */
  const rewrite = (href) => {
    if (!prefix || typeof href !== 'string' || !href.startsWith('/') || href.startsWith('//')) return undefined;
    if (href === prefix || href.startsWith(`${prefix}/`)) return undefined;
    return kept.has(href.split(/[?#]/)[0]) ? undefined : prefix + href;
  };

  return {
    name: 'veyq-base-links',
    element: {
      filter: ['a'],
      visit(/** @type {any} */ node, /** @type {any} */ ctx) {
        const href = rewrite(node.properties?.href);
        if (href !== undefined) ctx.setProperty(node, 'href', href);
      },
    },
  };
}
