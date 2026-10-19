# ADR 0103 — The application compresses its answers, except the ones that carry a token

- Status: Proposed 2026-10-19. Its decisions carry `cr-gallery-list-paging`'s recommendations; it becomes Accepted with
  the owner's go on that CR. Nothing is built.
- Date: 2026-10-19

Provenance: **(M)** measured in the worktree the CR names — the command is in `cr-gallery-list-paging`'s grounding
table; **(R)** remembered, measured by the CR before it binds.

## Context

No answer veyq sends is compressed (M): no `quarkus.http.enable-compression` in any `application*.properties`, no
`compress` middleware in the Traefik configuration the backend renders for Compose (`TraefikFileIngressSink`), no filter
on the chart's `HTTPRoute`, and the volume measurement read no `Content-Encoding` on a 23.4 MB media list although its
client sent `Accept-Encoding: gzip, br` (inbox #490). Gzip would make that list 13.3 MB and a 5,000-node tree 1.1 MB
instead of 3.0 MB (M, the harness's local `gzipSync` of the body).

The same backend serves the web client's static files on both deployment targets
([ADR 0069](0069-backend-serves-the-web-app-shell.md)): the JavaScript bundle is uncompressed today as well.

Compression over TLS is the precondition of BREACH: an attacker who can make a victim's browser send many requests whose
response **reflects attacker-chosen input beside a secret**, and who can see the encrypted length, recovers the secret a
character at a time. So where compression sits, and which answers it leaves out, is a security decision, not a tuning knob.

## Decision

1. **The application compresses; the proxies do not.** Quarkus's own compression (`quarkus.http.enable-compression`,
   build-time fixed in 3.36, gzip and deflate by default), configured once in the backend's `application.properties`.
   One place covers both targets and the static bundle, and the application knows which operation returns what. Traefik's
   `compress` middleware would cover Compose only, and the chart's Gateway is the cluster operator's choice
   ([ADR 0064](0064-kubernetes-helm-deployment-target.md)) — veyq cannot rely on its behaviour there.
   A proxy that compresses again finds a `Content-Encoding` already set and passes the body on (R, verified by the CR).
2. **By media type: JSON, HTML, CSS, JavaScript, SVG and the web manifest.** Never `application/octet-stream`, never a
   blob: blobs travel directly between the browser and the store ([ADR 0013](0013-blob-storage-presigned-direct-to-store.md)),
   and ciphertext does not compress. Gzip only: brotli needs a JNI library in the image (Quarkus's
   `quarkus.http.compressors`), which the measured gain does not justify. The level is the one the CR measures as the
   best trade on two cores at the 10,000-item list (CPU time against bytes), written into the property with its reading.
3. **An answer that carries a token is never compressed.** Every operation whose response schema holds a field marked
   `x-veyq-visibility: token` — today the session's `accessToken` (`verifyLogin`, `refreshSession`, `adoptSession`), the
   pairing's `refreshToken` (`delegatePairingSession`), a guest's `contributorToken` (`createShareIdentity`) and a tenant
   invite's `bootstrapSecret` (`createTenant`, `resendTenantInvite`, `reassignTenantInvite`, `inviteTenantOwner`, which
   also echo the owner address the operator typed) — is answered uncompressed (`@Uncompressed`). The set is derived from
   the contracts by a test, not listed by hand, so a token added to a response later is covered without anyone
   remembering it.

   **Why this is defence in depth, not the only wall.** BREACH needs the attacker to drive the victim's requests, and no
   veyq request with a secret in its answer can be driven cross-site (M): the API authenticates with an
   `Authorization: Bearer` header a foreign page cannot attach; the one cookie, the refresh cookie, is `SameSite=Strict`
   on its own path; every body is JSON, so a cross-site `POST` needs a CORS preflight the API never grants; and no veyq
   response reflects request input beside a token except the invite's address, which only the Server Owner types. There
   is no CSRF token to protect (M: none exists). The ciphertext in every other answer is not a BREACH secret: the
   network attacker BREACH models learns nothing from a wrapped key it cannot open, and the operator, the party veyq
   blinds, holds every byte of it anyway. Token answers are a few hundred bytes, so leaving them out costs nothing.

## Consequences

- **What it buys, measured by the CR:** the 10,000-item media list falls from 23.4 MB toward 13.3 MB on the wire, the tree
  and the static bundle by more; a household on a slow uplink feels it first.
- **What it costs:** CPU on the host for every compressible answer. On a two-core ARM host a 23 MB list is the worst
  case; paging the list (ADR 0033 § 6) bounds one answer, the level bounds the rate. The CR records both numbers.
- **The operator still sees sizes, now compressed sizes.** A compressed length is a function of what the server already
  holds in full, so it tells the operator nothing new; to a network observer it says less than the uncompressed length did.
- **A response that starts reflecting attacker input beside a secret is a new decision.** If an operation ever echoes a
  guest's or a stranger's input in the same body as a token, decision 3 already leaves it uncompressed; if it echoes such
  input beside another secret that is not marked `token`, the marking is wrong and is fixed there, in the contract.
- **Rejected — compress at Traefik:** covers one target of two, and the backend would no longer know which answers are
  compressed. **Rejected — compress everything, tokens included:** the attack is not reachable today (decision 3), but the
  exclusion is free, and "not reachable today" is a claim about every future endpoint. **Rejected — brotli now:** a
  native library for a few percent over gzip on base64 ciphertext. **Rejected — precompressed static files from the web
  build:** a second mechanism for the bundle only; revisit if the CR measures the bundle's on-the-fly gzip as a cost.
