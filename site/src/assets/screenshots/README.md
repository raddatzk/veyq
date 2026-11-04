# Screenshots of the Use pages

One PNG per screenshot and language: `<id>.en.png` and `<id>.de.png`, where `<id>` is the `id` a page passes to
`<Screenshot>` (`site/src/components/Screenshot.astro`). The English and German page place the same id; each takes
its own language's file, taken of the web client in that language. Until a file is here, its page shows a
placeholder that says what the shot must show. `pnpm run shots` in `site/` lists every one and which files exist.

The files are written by the capture (ADR 0087 §1): `pnpm run capture` in `site/` (`python3 scripts/site_capture.py`
from the repo root, after `./gradlew backend:image admin:image`) boots a throwaway instance, fills it with made-up
content and drawn pictures, and takes every shot in both languages. A new `<Screenshot>` needs a scenario in
`tests/e2e/shots/site.shots.ts`, or its id and the reason in `tests/e2e/shots/left-out.json` — the capture refuses to
start otherwise. An image replaced by hand is overwritten by the next capture unless its id is left out there.
