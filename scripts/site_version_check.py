#!/usr/bin/env python3
"""Red on a built version of the docs site that would send a reader out of its version (ADR 0087 decision 6).

A version is the site built with `VEYQ_SITE_VERSION=<major>`, served under `/v<major>/` beside the current docs at the
root. Astro prefixes that base to what it generates; a link written in a page gets it from the site's own plugin
(`site/plugins/base-links.mjs`). What neither reaches — raw HTML in a page, a component's `href`, a plugin that stopped
running after an Astro upgrade — would still read `/run/upgrade/` and land in the current major's docs, silently: the
link works, it only points at the wrong version. So this reads the build, not the sources, and holds every page to:

1. every root-relative `href`/`src` carries the base, except the project website's pages, which a version has none
   of: in a version each of them is a redirect into the version's start page, so the paths of those redirect pages
   (`/`, `/de/`) are the links kept — read from the build, never a second list of the site's languages;
2. every page carries `<meta name="robots" content="noindex">`, so no version ranks against the current docs;
3. every docs page names its version: the banner that links the current docs (`class="veyq-version"`).

    python3 scripts/site_version_check.py site/dist-version --base /v1/
    python3 scripts/site_version_check.py --selftest

Exits 0 when the build holds, 1 naming each page and link that does not, 2 on a usage error (no build there).
"""
from __future__ import annotations

import argparse
import html.parser
import pathlib
import sys
import tempfile

BANNER = "veyq-version"
REDIRECT = ('<html><head><meta name="robots" content="noindex"><meta http-equiv="refresh" content="0; url=/v1/start/">'
            '</head><body><a href="/v1/start/">/v1/start/</a></body></html>')


class Page(html.parser.HTMLParser):
    """The root-relative links, whether a robots `noindex` meta is there, and whether the version banner is."""

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.links: list[str] = []
        self.noindex = False
        self.banner = False
        self.redirect = False

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        a = {k: v or "" for k, v in attrs}
        for key in ("href", "src"):
            value = a.get(key, "")
            if value.startswith("/") and not value.startswith("//"):
                self.links.append(value)
        if tag == "meta" and a.get("name") == "robots" and "noindex" in a.get("content", ""):
            self.noindex = True
        if tag == "meta" and a.get("http-equiv", "").lower() == "refresh":
            self.redirect = True
        if BANNER in a.get("class", "").split():
            self.banner = True


def website_path(name: str) -> str:
    """The root path a redirect page stands for: `index.html` -> `/`, `de/index.html` -> `/de/`."""
    return "/" + name.removesuffix("index.html")


def problems(dist: pathlib.Path, base: str) -> list[str]:
    """Every way a page of the build in `dist` leaves its version, as `<page>: <what>` lines; empty when none does."""
    base = "/" + base.strip("/") + "/"
    pages: dict[str, Page] = {}
    for path in sorted(dist.rglob("*.html")):
        page = Page()
        page.feed(path.read_text(encoding="utf-8"))
        pages[path.relative_to(dist).as_posix()] = page
    keep = {website_path(name) for name, page in pages.items() if page.redirect and name.endswith("index.html")}
    found: list[str] = []
    for name, page in pages.items():
        for link in page.links:
            if not link.startswith(base) and link.split("?")[0].split("#")[0] not in keep:
                found.append(f"{name}: links {link} outside {base}")
        if not page.noindex:
            found.append(f"{name}: no robots noindex")
        # The redirects of `/v<major>/` and `/v<major>/<lang>/` into the version's start page carry no banner; every
        # page with content does.
        if not page.banner and not page.redirect:
            found.append(f"{name}: no version banner (class=\"{BANNER}\")")
    return found


def selftest() -> int:
    """Bites: each rule turns red on the page that breaks it, and a page that keeps all three stays green."""
    good = ('<html><head><meta name="robots" content="noindex"><link href="/v1/_astro/a.css"></head><body>'
            f'<div class="{BANNER}"><a href="https://veyq.app/start/">current</a></div>'
            '<a href="/v1/run/upgrade/">u</a><a href="/">site</a><a href="#top">t</a><a href="//cdn/x">x</a></body></html>')
    cases = {
        "green": (good, []),
        "unprefixed link": (good.replace("/v1/run/upgrade/", "/run/upgrade/"), ["links /run/upgrade/ outside /v1/"]),
        "unprefixed src": (good.replace("/v1/_astro/a.css", "/_astro/a.css"), ["links /_astro/a.css outside /v1/"]),
        "another version": (good.replace("/v1/run/", "/v2/run/"), ["links /v2/run/upgrade/ outside /v1/"]),
        "website link without its redirect page": (good.replace('href="/"', 'href="/de/#x"'), ["links /de/#x outside"]),
        "no noindex": (good.replace('content="noindex"', 'content="index"'), ["no robots noindex"]),
        "no banner": (good.replace(BANNER, "other"), ["no version banner"]),
        "redirect needs no banner": (good.replace(BANNER, "other").replace(
            "<head>", '<head><meta http-equiv="refresh" content="0; url=/v1/start/">'), []),
    }
    failed = 0
    for label, (text, expected) in cases.items():
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            (root / "start").mkdir()
            (root / "start" / "index.html").write_text(text, encoding="utf-8")
            # The website's two pages, as a version builds them: redirects, which keep `/` and `/de/` as links.
            for website in (root, root / "de"):
                website.mkdir(exist_ok=True)
                (website / "index.html").write_text(REDIRECT, encoding="utf-8")
            if label == "website link without its redirect page":
                (root / "de" / "index.html").unlink()
            got = problems(root, "/v1/")
        ok = len(got) == len(expected) and all(e in g for e, g in zip(expected, got))
        print(f"{'ok  ' if ok else 'FAIL'} {label}: {got}")
        failed += not ok
    print(f"site_version_check selftest: {len(cases) - failed}/{len(cases)} green")
    return 1 if failed else 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("dist", nargs="?", type=pathlib.Path, help="the version build's output directory")
    parser.add_argument("--base", help="the version's base, e.g. /v1/")
    parser.add_argument("--selftest", action="store_true")
    args = parser.parse_args(argv)
    if args.selftest:
        return selftest()
    if args.dist is None or args.base is None or not args.dist.is_dir() or not any(args.dist.rglob("*.html")):
        print(f"site_version_check: no built site at {args.dist} (and --base is required)", file=sys.stderr)
        return 2
    found = problems(args.dist, args.base)
    for line in found:
        print(line)
    pages = sum(1 for _ in args.dist.rglob("*.html"))
    print(f"site_version_check: {pages} page(s) under {args.base}, {len(found)} problem(s)")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
