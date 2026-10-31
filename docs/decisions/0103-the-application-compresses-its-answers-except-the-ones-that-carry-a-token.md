# ADR 0103 — The application compresses its answers, except the ones that carry a token

- Status: Accepted
- Built: yes
- Date: 2026-10-19
- Model: [developer-styleguide](../crosscutting/developer-styleguide.md)

## Context

When this was decided, no answer veyq sent was compressed: neither the backend, nor the Traefik configuration it
renders for Compose, nor the chart's `HTTPRoute` compressed anything, and the volume measurement read no
`Content-Encoding` on a 23.4 MB media list although its client accepted gzip and brotli. Gzip would make that list
13.3 MB and a 5,000-node tree 1.1 MB instead of 3.0 MB.

The same backend serves the web client's static files on both deployment targets
([ADR 0069](0069-backend-serves-the-web-app-shell.md)), so the JavaScript bundle went out uncompressed as well.

Compression over TLS is the precondition of BREACH: an attacker who can make a victim's browser send many requests whose
response **reflects attacker-chosen input beside a secret**, and who can see the encrypted length, recovers the secret a
character at a time. So where compression sits, and which answers it leaves out, is a security decision, not a tuning knob.

## Decision

1. **The application compresses; the proxies do not.** Quarkus's own compression, configured once in the backend's
   `application.properties`. One place covers both targets and the static bundle, and the application knows which
   operation returns what. Traefik's `compress` middleware would cover Compose only, and the chart's Gateway is the
   cluster operator's choice ([ADR 0064](0064-kubernetes-helm-deployment-target.md)) — veyq cannot rely on its
   behaviour there. A proxy that compresses again finds a `Content-Encoding` already set and passes the body on (not
   measured: Compose's Traefik has no `compress` middleware to try it against, and the chart's Gateway is the
   operator's). The admin listener (ADR 0065) is a second server on default options and compresses nothing; its
   answers are small and loopback-only.
2. **By media type: the text types — JSON, HTML, CSS, JavaScript — plus SVG and the web manifest.** Never
   `application/octet-stream`, never a blob: blobs travel directly between the browser and the store
   ([ADR 0013](0013-blob-storage-presigned-direct-to-store.md)), and ciphertext does not compress. **Gzip and deflate,
   no brotli:** brotli needs a native library in the image, which the measured gain does not justify. **Level 1:** the
   answers are mostly base64 of ciphertext, which leaves deflate little past its first pass, so a higher level buys a
   few percent of the bytes for markedly more CPU — the scarcer resource on a two-core host. The measurement sits
   beside the property. All of it is build-time in Quarkus: a change is a new image.
3. **An answer that carries a token is never compressed.** Every operation whose success response holds a field marked
   `x-veyq-visibility: token` — the session's access token, the pairing's refresh token, a guest's contributor token,
   a tenant invite's bootstrap secret (whose answers also echo the owner address the operator typed), and the
   **presigned URLs**, whose signature is a bearer capability — is answered uncompressed. **The build writes it**: it
   derives the set from the specs it generates from and marks each generated interface method uncompressed, because
   Quarkus REST reads the annotation from the method that declares the endpoint, and the same annotation on an
   implementing `override` is not read (measured: a refresh refusal went out gzip-encoded with it there). A test
   derives the set again from the YAML on its own, holds that exactly those endpoints carry the annotation, and calls
   each with `Accept-Encoding: gzip`, beside a control that is compressed. So a token added to a response later is left
   uncompressed by the build that generates it, without anyone remembering it.

   **Why this is defence in depth, not the only wall.** BREACH needs the attacker to drive the victim's requests, and no
   veyq request with a secret in its answer can be driven cross-site: the API authenticates with an
   `Authorization: Bearer` header a foreign page cannot attach; the one cookie, the refresh cookie, is `SameSite=Strict`
   on its own path; every body is JSON, so a cross-site `POST` needs a CORS preflight the API never grants; and no veyq
   response reflects another party's input beside a token except the invite's address, which only the Server Owner
   types. There is no CSRF token to protect. The ciphertext in every other answer is not a BREACH secret: the network
   attacker BREACH models learns nothing from a wrapped key it cannot open, and the operator, the party veyq blinds,
   holds every byte of it anyway. Most token answers are a few hundred bytes; a presign batch is larger, and is left
   uncompressed by the same rule — the exclusion is the rule, not a size judgement.

## Consequences

- **What it buys:** on the production recipe the media list's wire bytes fall to about 0.6 of the body and the
  JavaScript bundle to about a third, and the tree by more; a household on a slow uplink feels it first.
- **What it costs:** CPU on the host for every compressible answer. On a two-core ARM host a 23 MB list is the worst
  case; paging the list (ADR 0033 § 6) bounds one answer, the level bounds the rate.
- **The operator still sees sizes, now compressed sizes.** A compressed length is a function of what the server already
  holds in full, so it tells the operator nothing new; to a network observer it says less than the uncompressed length did.
- **A response that starts reflecting attacker input beside a secret is a new decision.** If an operation ever echoes a
  guest's or a stranger's input in the same body as a token, decision 3 already leaves it uncompressed; if it echoes such
  input beside another secret that is not marked `token`, the marking is wrong and is fixed there, in the contract.
- **Rejected — compress at Traefik:** covers one target of two, and the backend would no longer know which answers are
  compressed. **Rejected — compress everything, tokens included:** the attack is not reachable today (decision 3), but the
  exclusion is free, and "not reachable today" is a claim about every future endpoint. **Rejected — brotli now:** a
  native library for a few percent over gzip on base64 ciphertext. **Rejected — precompressed static files from the web
  build:** a second mechanism for the bundle only; revisit if the bundle's on-the-fly gzip measures as a cost.
