# Screenshots of the Use pages

One PNG per screenshot and language: `<id>.en.png` and `<id>.de.png`, where `<id>` is the `id` a page passes to
`<Screenshot>` (`site/src/components/Screenshot.astro`). The English and German page place the same id; each takes
its own language's file, taken of the web client in that language. Until a file is here, its page shows a
placeholder that says what the shot must show. `pnpm run shots` in `site/` lists every one and which files exist.
