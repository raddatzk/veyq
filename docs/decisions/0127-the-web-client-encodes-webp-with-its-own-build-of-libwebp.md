# ADR 0127 — The web client encodes WebP with its own build of libwebp

- Status: Accepted
- Built: yes
- Date: 2026-11-12
- Model: [cc.security.csp](../crosscutting/cc.security.csp.md)

## Context

A space icon and a member's picture are a 256 px WebP padded to a constant 16 KiB before they are sealed, so the
operator cannot tell one picture from another by its length ([ADR 0084](0084-a-members-profile-picture-is-a-sealed-attribute-of-their-tenant-profile.md),
[uc.spaces.set-space-icon](../features/spaces/use-cases/uc.spaces.set-space-icon.md)). The client encoded it with the
canvas (`toBlob` / `convertToBlob` with `'image/webp'`). Safari cannot encode WebP that way: the HTML standard lets a
browser answer an unsupported type with a PNG, and Safari 27.2 does (`toBlob(…, 'image/webp')` answered
`image/png`, measured by the owner in Safari's console, 2026-11-12). A 256 px PNG of a photo does not fit the pad, so
every photo was refused there with "the image could not be prepared".

The format has to stay small and keep alpha: a logo with a transparent background is a common space icon. What the
alternatives cost, measured 2026-11-12 on one 886 px photo with Pillow (libwebp/libjpeg/zlib) at 256 px:

| Encoding | Bytes | Against 16 KiB |
|---|---|---|
| WebP, quality 0.9 | 11,544 | fits, alpha kept |
| JPEG, quality 0.9 | 15,823 | fits, no alpha |
| PNG, true colour | 90,589 | 5.5× over |
| PNG, 64-colour palette | 15,114 | fits, visibly banded |

Three ways around the canvas were weighed and rejected:

- **A per-browser fallback** (WebP where the canvas writes it, else the PNG if it fits, else JPEG on a fixed
  background): the bytes a picture is stored as would depend on the browser that set it, and a transparent icon set
  from Safari would be flattened for every viewer.
- **A larger pad** (128 KiB, so the PNG fits): every picture costs the pad on the wire whatever it holds, eight
  times today's for each member in each list; the sealed field would outgrow its `TEXT` column; and a noisy photo can
  still exceed it as a PNG, so the fallback stays.
- **WebCodecs VP8** (a lossy WebP is one VP8 key frame in a RIFF container; Safari 27.2 reports `vp8` encoding
  supported): no alpha, a colour-space conversion we would not control, and quality steered by bitrate.

The WebAssembly WebP encoders on npm were surveyed (npm metadata and each repository's default-branch head,
2026-11-12): `@jsquash/webp` (last release 2025-08) compiles libwebp at commit `d2e245ea` of **2020-11-24**, before
libwebp 1.2.0; `wasm-webp`, `webp-wasm`, `@saschazar/wasm-webp`, `libwebp-wasm` and `@squoosh/lib` are smaller or
stale; the actively maintained ones are whole toolkits — `@imagemagick/magick-wasm` (32 MB unpacked), `wasm-vips`
(needs `SharedArrayBuffer`, so cross-origin isolation for the whole app), `@img/sharp-wasm32` (Node only). libwebp itself
is maintained by the WebM project (release tags only, `v1.6.0` of 2025-07-08; commits on `main` the day of this
decision).

## Decision

1. **The built web client's CSP grants `'wasm-unsafe-eval'` in `script-src`**, and nothing else changes: no
   `'unsafe-eval'`, `worker-src 'self'`, Trusted Types as [ADR 0117](0117-the-web-client-enforces-trusted-types.md) has
   them. The source allows compiling WebAssembly, not evaluating JavaScript. An attacker who could feed the page a module
   would first need to run script in it, which `script-src 'self'` and Trusted Types refuse; one who already runs script
   holds the unlocked keys anyway ([security-model](../crosscutting/security-model.md)). The admin web keeps its policy:
   it encodes no images.
2. **The encoder is libwebp built by us, from a pinned upstream release tag, encoder only.** Emscripten in a
   digest-pinned image compiles the tag into one `.wasm` and its glue, with no file system, no threads and
   `-sDYNAMIC_EXECUTION=0` (no `eval`, no `new Function` in the glue). The built files are committed, beside the tag,
   the image digest and their SHA-256, so `pnpm run check` needs no toolchain; CI rebuilds them from the pin and is red
   when the bytes differ. That rebuild is what ties the binary in the client to libwebp's source.
3. **Every browser encodes with it.** The canvas crops, downscales and hands over RGBA pixels; it never encodes WebP.
   There is no fallback to another format: a picture is a WebP on every device, and an encoder that fails to load
   refuses the picture with the dialog's message. The module is fetched as bytes from the client's own origin and
   instantiated from them, so nothing depends on how the server types a `.wasm` response.
4. **A newer libwebp is found and built without waiting for anyone.** A scheduled workflow reads libwebp's stable
   release tags; on a newer one it builds against it, runs the encoder's tests and opens a pull request with the new
   pin and the rebuilt files. It never merges; a person does, after the checks. The Emscripten image digest is kept
   current like every other pinned image.
5. **The encoder lives in one directory that imports nothing of veyq**: its build, its pin, its committed output, a
   small typed API (pixels, size and quality in, WebP bytes out), its tests, and libwebp's `COPYING` and `PATENTS`. The
   client imports it only through that API. It is not a workspace package
   ([ADR 0065](0065-admin-ui-cli-separate-service.md) keeps the workspace for the one module two surfaces share); the
   cut is so that publishing it on its own needs no restructuring. Whether to publish is not decided here.

## Consequences

- One encoder means one behaviour to test, and for the first time a test can run the real one: the module runs in
  Node, so a unit test encodes pixels and asserts the WebP and its size against the pad. The canvas encoder never ran
  in a test.
- `'wasm-unsafe-eval'` is granted for the page, not for this module. Any later WebAssembly — pdf.js's own, for its
  image decoders and ICC colour (inbox #837; scanned pages draw without it, with the decoders' plain-JavaScript builds,
  [ADR 0128](0128-pdf-js-decodes-scanned-images-with-its-plain-javascript-decoders.md)) — is no longer blocked by the
  policy, but each is its own decision: what it is, where it comes from, how it is pinned. This ADR turns none of them on.
- We maintain a native build: a toolchain image, a reproducibility check, and libwebp's release cadence, which the
  workflow of decision 4 turns into a pull request rather than a reminder. If the rebuild ever stops being
  byte-identical, the check is red until the cause is found; the fix is never to drop the check.
- The client grows by the encoder (the `.wasm` is 270 kB, 114 kB gzipped, and its glue 2 kB, measured in the first
  build of libwebp 1.6.0; loaded only when a picture is encoded) and its licence
  notice: libwebp is BSD-3-Clause with Google's patent grant, carried beside the npm packages' notices in the built
  client.

## Related

- The encoder: [`src/web/webp-encoder/`](../../src/web/webp-encoder/README.md).
- Run area of the docs site: [Hardening](../../site/src/content/docs/run/hardening.md)
- Security area of the docs site: [How veyq is tested](../../site/src/content/docs/security/how-veyq-is-tested.md)
