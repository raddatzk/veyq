# webp-encoder

The web client's WebP encoder: [libwebp](https://chromium.googlesource.com/webm/libwebp) compiled by us to
WebAssembly, from a pinned upstream release tag, encoder only. It encodes a space icon and a member's picture in every
browser alike — Safari's canvas cannot write WebP — ([ADR 0127](../../../docs/decisions/0127-the-web-client-encodes-webp-with-its-own-build-of-libwebp.md)).

This directory imports nothing of veyq, and the client imports it only through `encodeWebp.ts`
(`scripts/validate_webp_encoder_imports.py` holds both). It is not a workspace package; its tests run with the web
client's (`pnpm run check` in `src/web/`).

## What is here

| File | What it is |
|---|---|
| `encodeWebp.ts` | The API: `encodeWebp(rgba, width, height, quality)` → WebP bytes; `loadWebpEncoder(bytes?)` |
| `encodeWebp.test.ts` | The real module run in Node, asserting on the bytes it writes |
| `encoder.c` | The one C entry into libwebp (`WebPEncodeRGBA`) |
| `build.py`, `Dockerfile` | The build: a digest-pinned Emscripten image compiles the pinned tag |
| `libwebp.pin` | Tag, commit, image, and the SHA-256 of each output |
| `wasm/webp_encoder.wasm`, `wasm/webp_encoder.mjs` | The committed output: the module and its glue (built, never edited) |
| `wasm/webp_encoder.d.mts` | Hand-written types for the glue |
| `COPYING`, `PATENTS` | libwebp's licence (BSD-3-Clause) and Google's patent grant, copied from the tag by the build |
| `fixtures/` | A generated photo-like picture (raw RGBA) and the script that writes it |

The build compiles with `-sDYNAMIC_EXECUTION=0` (no `eval`, no `new Function` in the glue), `-sFILESYSTEM=0`, no
threads, `-sMODULARIZE -sEXPORT_ES6`. The module is always instantiated from bytes — handed in, or fetched from the
client's own origin — never streamed. Compiling it needs `'wasm-unsafe-eval'` in the page's `script-src`
([cc.security.csp](../../../docs/crosscutting/cc.security.csp.md)).

## Rebuild

Needs Docker and Python 3. From the repository root:

```sh
python3 src/web/webp-encoder/build.py            # rebuild, compare with libwebp.pin and the committed files
python3 src/web/webp-encoder/build.py --write    # rebuild and write the files and their hashes
```

A rebuild of the pinned tag must be byte-identical: Checks' `webp-encoder` job runs the first line whenever this
directory changed, and is red on any difference. Measured when it landed: two rebuilds from a clean checkout, and one
under amd64 emulation on an arm64 Mac, wrote the pinned bytes. If it ever differs, the check stays red until the cause is found — it is
never dropped (ADR 0127 Consequences).

## A newer libwebp, a newer Emscripten

- **libwebp:** `python3 build.py --newest` names libwebp's newest stable `vX.Y.Z` tag, its commit, and whether it is
  newer than the pin; `build.py --tag <tag> --commit <sha> --write` then rebuilds from it, and the tests run against
  the new module. ADR 0127 decision 4 has a scheduled workflow do exactly this weekly and open a pull request that a
  person merges; until that workflow lands (its write permissions wait for the owner), it is done by hand.
- **Emscripten:** Dependabot bumps the `Dockerfile`'s digest. A new toolchain writes new bytes, so that pull request is
  red (the pin's `image` no longer matches the `Dockerfile`, and the rebuild differs) until `build.py --write` is run
  on its branch and the result committed.

## Fixture

`fixtures/photo-256.rgba.gz` is generated, never photographed: `python3 fixtures/make_photo_fixture.py` writes it
again, byte for byte.
