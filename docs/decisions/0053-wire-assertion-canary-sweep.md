# ADR 0053 — The wire is the oracle: a canary sweep for operator-blindness

- Status: Accepted
- Date: 2026-07-31

## Context

[ADR 0044](0044-field-visibility-declared-at-the-field.md) classifies every field in `contracts/` and names its
own sharpest limit: *"This checks the contract, not the traffic."* A field carrying `contentEncoding: base64` is
**permitted** to be opaque; nothing observes whether the client put ciphertext in it. A client that writes
plaintext into a field marked opaque satisfies that ADR completely. It also lists the surfaces it cannot see at
all — path params, query params, `X-Contributor-Token` / `X-Share-Password`, presigned-URL query strings, 67 DB
column names with no contract, error `message` free text, and the S3 object key — and assigns them to nobody,
closing with: *"a different sensor, with the wire as its oracle, and it does not exist yet."* This ADR decides
that sensor. It is the whole of operator-blindness that no automated check has ever observed.

Two things changed to make it decidable. `scripts/run_web_e2e.py` now drives real flows against a real backend
and a real object store with a fresh instance per file, so the substrate exists. And a spike measured what such
a sweep actually finds: after `memoryLocation.e2e.test.ts`, its sealed values (`Teneriffa`, the memory title, the
coordinates, the date) appear **zero** times in the instance's database, while `e2e.test` appears three times —
in `veyq_tenant.owner_email` and the invite row, beside the tenant name in cleartext.

**That result is the design input.** The hard part is not finding strings; it is telling deliberate plaintext
from a leak. Both hits are declared: email is `x-veyq-visibility: pii`, `tenantName` is `public` ("operational
cleartext"). A sensor that flags them is a sensor that gets switched off.

## Decision

**1 · The oracle is canary *absence*, not allowlist conformance.** The sweep proves a positive fact about
specific values — *this plaintext, which the client sealed, appears nowhere the operator can read* — rather than
attempting the negative *nothing unaccounted-for is readable*.

Allowlist conformance is rejected on measurement, not taste: it needs a mapping from every observable byte back
to a declared field, and ADR 0044 measured **67 DB columns with no contract at all**, so the mapping cannot be
built for the surface that most needs it. Where the mapping *does* exist (JSON bodies), a conformance check is
dominated by an **opacity check** — assert each of the 110 `contentEncoding: base64` fields does not decode to
readable text — which tests the content instead of the bookkeeping and needs no mapping. So:

| Surface | Structure | Oracle |
|---|---|---|
| DB columns, S3 object keys + bytes, server log | bytes with no schema | canary absence |
| JSON request/response bodies | every field carries a visibility marker | opacity of the declared-opaque fields |

**2 · Canaries are derived, never hand-listed** — and the derivation point is **the client unsealing the
server's own responses**, not an instrumented crypto primitive. Whatever the client sealed, the client can
unseal; so the canary set is every string leaf recovered by opening the sealed fields the server returned, using
the keys the suite already holds. Nothing is registered, so nothing can be forgotten.

A hook in the AEAD primitive was the obvious alternative and is rejected twice over. `packages/crypto/src/v1.ts`
is **conformance-pinned** to `contracts/crypto/vectors/v1.json` and sits in the Stryker mutation scope — it is
the last file in the repo that should grow a test affordance. And it is the wrong altitude regardless: `aeadSeal`
is reached by `symWrap`/`asymWrap` as well, so it would record **key material** as canaries, and searching a
database for random bytes proves nothing while burying the strings that matter.

**3 · Two sensors, disjoint surfaces, one seam.** The surfaces split by who can reach them, and neither side
covers the other:

- the **suite** makes the calls, so only it sees path params, query strings, capability headers, presigned URLs
  and error bodies;
- the **orchestrator** holds Docker, the database root credential, the store credentials and the captured boot
  log, so only it sees the DB, the object store and the server log.

They are joined by a canary file the suite writes and the orchestrator reads. `docker exec` stays in the
orchestrator: reaching for it from a Vitest suite is exactly the coupling that made the previous seeded
bootstrap unrunnable in CI, and it was deleted from that path on purpose.

**4 · The orchestrator must retain the boot log.** It currently writes each boot's log into a per-run temp
directory that is discarded on exit, so the log surface — where an error `message` or a stack trace would carry
plaintext — is not merely unswept but unavailable.

**5 · Slice by surface, database first.** The DB is the surface the spike has already read end to end, so the
first change-request builds the derivation, the seam and the sweep against it alone. The store, the log and the
HTTP opacity check follow as their own change-requests once the mechanics stand. The S3 object key keeps the
separate guard ADR 0044 promised it.

## Consequences

- **An agent cannot satisfy this by weakening an assertion**, because the oracle is outside the code: the bytes
  are in a database this process does not write. That is weaker than tamper-proof and should not be overstated —
  a canary can still be removed, and that removal is a visible diff, exactly like widening an allowlist would be.
  What the external oracle buys is that *passing* cannot be arranged from inside the client.
- **The residual gap is coverage, not correctness.** The sweep only sees flows some suite drives. With four
  suites today, most of the product is unobserved — which is why e2e breadth is tracked as debt in
  [architecture §11](../architecture/README.md), and why this sensor gets stronger for free as suites are added
  rather than needing to be revisited.
- **Derivation moves the residual risk rather than removing it.** A value the client never seals is not a canary,
  correctly — but a value that *should* have been sealed and was sent in the clear is invisible to this sensor,
  because there is nothing to derive it from. That case is the opacity check's (decision 1) and the field-marker
  guard's, not this one's; naming the split is what keeps a green sweep from being read as "operator-blind,
  verified".
- **A green sweep proves less than its name suggests, and the guard's docstring must say so** — per
  `changes/README.md` § trust-chain guards, whose third required field is exactly this. It proves: for the values
  this flow sealed, on the surfaces this sensor reads, no plaintext copy exists. Not that the flow sealed
  everything it should have, and not that an unswept surface is clean.
- **It cannot join the buildless `validate_*` glob.** Like the orchestrator it needs Docker and a booted backend,
  so its enforcement is the `web-e2e` CI job — a red job is the guard. That is the same trade
  [ADR 0040](0040-coverage-gaps-and-http-not-a-surface.md) governs, and the same obligation applies at the grain
  that ADR's note fixes, the job: `web-e2e` is a row a run admitted before the 2026-08-06 import, so the sweep
  counts on that job's declared run like any suite it gates, and a job no run has executed would admit nothing.
- **Rejected — asserting on ciphertext entropy instead of plaintext absence.** Tempting because it needs no
  canaries at all, but ciphertext and random garbage are indistinguishable by construction, so it cannot tell a
  correct seal from a broken one. It answers a question nobody asked while leaving the one that matters open.
- **Rejected — folding this into ADR 0044.** That ADR defined this sensor *out* of its scope deliberately and in
  writing; re-absorbing it would blur a boundary that is currently the clearest thing about either check. 0044
  stays the design sensor, this is the traffic sensor, and they do not overlap.
