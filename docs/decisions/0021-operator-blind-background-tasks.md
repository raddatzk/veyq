# ADR 0021 — Operator-blind background tasks (pull-based job mechanism, equal worker pool)

- Status: Accepted
- Built: partly (not built: § 3's sealed body)
- Date: 2026-07-04
- Model: [background-tasks](../crosscutting/background-tasks.md)
- Replaced in part: § 6's lease holder (a worker, not the account) and § 9's first content consumer by ADR 0132 (not built)

## Context

Because veyq is operator-blind, any work over content the **server itself cannot do** must run on a device that
holds the keys: deliver a scope grant to a new member ([ADR 0020](0020-grant-distribution-invite-wrap.md)),
transcode a video, run on-device ML / face-recognition (Agent mode — inbox #10/#23).
There was no such mechanism. Member-add's grant delivery surfaced the need; the media/agent roadmap will
lean on it heavily. We want the foundation built **cleanly and blind**, designed from the heavy future consumers
but validated first by the trivial one — no time pressure ([docs/ai/README](../ai/README.md), *structured over
fast*).

Constraints: **operator-blind** (the coordinator must learn neither content, nor results, nor gratuitously the
*kind* of work); **self-hostable** (no mandatory extra broker/daemon); **a worker is any client that holds the
scope key** — today the web client, later a headless agent, so the protocol may not assume one runtime.

## Decision

A **pull-based background-task mechanism inside the veyq backend** (no external broker): the server stores
opaque, scope-bound **job envelopes**; a pool of **equal, self-selecting keyed workers** (any interactive client
*or* the headless agent) pulls the jobs it is eligible + capable for, executes on-device, and posts a **sealed
result**.

1. **Coordinator = the veyq backend** (a job table), not MQTT/a broker. Discovery is **pull / reconcile-on-entry**
   now; a **push channel (SSE/WebSocket from the same backend)** is a later seam — same trust boundary, no extra
   daemon.
2. **Equal worker pool, self-selecting.** Interactive clients and the agent are equivalent workers on one
   protocol. A blind server cannot schedule work it can't read, so workers pull and self-select — the pull model
   *falls out of* the invariant.
3. **Split envelope.** Cleartext (server-visible — already-known or bookkeeping): the job's id, its scope (the
   routing key — the server already knows membership), an opaque capability tag, an opaque resource id, and the
   status, lease and attempt bookkeeping. **Sealed body**: the task **type** + parameters, sealed under the scope key.
   The server routes by scope + opaque tag; only a scope-key-holding worker unseals the body to act. No job carries
   a body yet: the first job that seals one adds its purpose to the typed seal union, with a vector
   ([ADR 0072](0072-a-seal-binds-its-object.md)).
4. **Capability matching at pull time.** A worker's pull carries its **capability tags**; the server returns
   claimable jobs where `scope ∈ my memberships ∧ tag ∈ my tags`. No standing presence/advertisement table (added
   only with the later push channel). **Eligibility is per tag.** Holding the scope key is the floor; a tag whose
   work only some key-holders may complete narrows it, because a job offered to a worker that can never complete it
   spends an attempt on every claim and dead-letters before the right worker comes online. `scope-rotation` narrows
   it to the scope's **owners**: only an owner may commit a rotation
   ([ADR 0014](0014-group-scopes-and-governance.md) §4), and a non-owner could not build a complete one anyway,
   since a rotation re-grants a pending consent proposal, which is hidden from it (what a rotation must carry is
   [ADR 0024](0024-scope-key-rotation.md)'s and
   [ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)'s).
   One claim that names several tags still applies each tag's own eligible set.
5. **Tags = a fixed, versioned, public registry** in `contracts/` (cross-surface, like the crypto vectors). This
   keeps the wire/DB free of plaintext semantic labels (a bare dump sees `0x9f3a`, not `face-recognition`). It
   does **not** hide the type from a *correlating* operator (see consequences) — keyed-per-scope tags
   (`PRF(K_SC-derived, type)`) are a documented **seam** for future low-footprint, ambiguous-processing types,
   where correlation would not already reveal the kind.
6. **Lease = at-least-once + idempotent.** An atomic claim — a compare-and-set on the job's `status` — sets
   an opaque lease holder + expiry. The job carries a **TTL**; long jobs **heartbeat** to extend it. The TTL is
   short, so a stopped worker frees its job soon, and longer than a renewal a hidden tab's throttled timers delay,
   so a working tab keeps it (the values: [background-tasks § Lifecycle](../crosscutting/background-tasks.md#lifecycle)).
   A worker that stops — a tab closed, reloaded or navigated away — frees its job only when the lease runs out. There
   is **no release endpoint** called on `pagehide` — a request sent while a page unloads is not reliably delivered, and
   the short TTL already bounds what a closed tab costs, so a release would be a second path that sometimes fires —
   and a holder does **not take over its own** still-valid lease: the same user's next page claims the job like any other worker, after the
   expiry. A crashed worker's lease **expires → lazy reclaim** (the claim query treats `leased ∧ expiry<now` as
   free).
   `attempts` + a max → **dead-letter** (`failed`, surfaced). Exactly-once is not attempted; effects and the
   `jobId`-keyed result post are idempotent. **Every claimed job has a holder, and only the holder acts on it:**
   heartbeat, complete, fail and decline all require the current lease (a conflict otherwise), and
   completing an already-`done` job stays a no-op success. A result post that skipped the claim would also skip
   everything the claim decides — above all a worker's own decline, which keeps a key-holder that cannot anchor a
   delivery's target from being offered it again ([ADR 0028](0028-identity-authenticity-tofu-verification.md) Decision 2).
   Claims, like every other job write, happen only in a live scope of a non-suspended tenant
   ([uc.spaces.scope-access](../features/spaces/use-cases/uc.spaces.scope-access.md)).
7. **Result = a sealed artifact, usually via existing paths.** Most results are normal veyq writes (a grant onto
   a membership, a derivative blob, a node update) → the worker performs that sealed write and marks the job
   `done`; the originator observes it through the normal path. A job-specific output rides an optional
   **`sealedResult`** field (ciphertext, opaque). No generic plaintext result store.
8. **Origination.** The server may originate only **structural, non-sensitive** jobs it already fully understands
   (a **stale-epoch** membership → a `grant-delivery` job — a member a scope-key rotation skipped,
   [ADR 0024](0024-scope-key-rotation.md), or a newcomer whose invite a rotation declared pending; every membership is
   born with a grant, [ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
   decisions 1–5), and an **event** it witnessed
   but cannot complete itself (a co-shared member cut → a `scope-rotation` job; an account rotation queues none, its
   rotator mints its scopes' epochs itself, [ADR 0060](0060-key-rotation-after-compromise.md) Decision 4).
   **Content-processing jobs are originated by a client/agent** and posted ready, so the server never *picks* a
   content-semantic task type. The converse holds too: a tag the registry names as server-originated
   (`server-structural`, `server-event`) is refused at a client's enqueue, so no client fabricates a delivery
   target, or a rotation that no event asked for and that would carry none of the event's exclusions.
9. **The first consumer is grant delivery** ([ADR 0020](0020-grant-distribution-invite-wrap.md)): a trivial,
   blind body, a universal tag, a short lease like every job (§6; its completion is idempotent, first-online-wins) —
   it validated the core loop end-to-end. Built for member-add, it now serves only a member whose grant is behind,
   since a newcomer grants itself
   ([ADR 0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
   decision 5); the second is `scope-rotation` (§8). Transcode + face-recognition/ML are the **informing** heavy
   consumers that shaped the lease/tags/result but are built later (floor, not ceiling).
10. **Persistence: buy, don't build.** What is bought is the database's own atomic primitive, not a job framework: the
    queue/claim/lease machinery is a thin compare-and-set on `status`, the same on the H2 test database and MariaDB. A server-side job framework
    (JobRunr, db-scheduler) does not fit: it runs jobs *on the server*, and the workers here are remote key-holders
    that pull over HTTP, because a blind server holds no key. MariaDB's `SKIP LOCKED` stays available as a throughput
    optimisation if contention ever warrants it, never needed for correctness. Only the **blind layer** (envelope, key-eligibility, sealed body/result, tag registry) is veyq-specific. That blind
    layer stays a thin, decoupled module so it *could* later be extracted as an OSS spec — but not now.

## Consequences

- **The blind server coordinates work it cannot understand.** Grants, transcodes, future ML run on keyed
  devices; the server stores opaque envelopes and relays sealed results.
- **The residual leak is correlation-bounded, not label-bounded (the load-bearing honesty).** Even with opaque
  tags, a correlating operator infers the *character* of work from what it already sees: blob **sizes**, **timing**
  (a large upload from user U, then a job on that resource), **which worker** takes it (agent vs. phone), how long
  it runs, and the **output artifact** that appears. Big blob → job on it → agent processes → a derivative appears
  ≈ "a transcode", inferable regardless of tag scheme. So the tags' real job is to keep gratuitous plaintext
  labels out of the DB (defense vs. a bare dump), **not** to defeat a correlating/hosted operator — which is the
  already-declared, accepted gap ([security-model](../crosscutting/security-model.md); self-hosted makes it moot).
  **Content and results stay sealed throughout — that is the guarantee that holds.**
- **No new mandatory infra.** The backend is the coordinator; self-host adds nothing. Push (SSE) later rides the
  same server.
- **Equal pool → resilience + no bottleneck.** "Who *should* take a heavy job" (battery/compute suitability) is a
  later policy hint (a job may carry `prefers-agent`), not v1 — first-eligible-capable claims.
- **Rejected — MQTT / a broker.** Topic routing re-leaks type+scope to the broker (= the operator), adds a
  mandatory daemon, and still needs the claim/lease in the DB anyway. Reconsider only for true many-agent
  fan-out — and then route by an opaque scope-token only.
- **Rejected — the server knows the semantic type and matches by it.** Buys tiny efficiency at veyq scale for a
  real (if correlation-redundant) label leak; the opaque-tag pull-match gives the same routing, blind.
- **Rejected — keyed tags now.** Correlation already reveals the kind for the near-term media jobs, so keyed
  tags' complexity buys little today; kept as a seam for future low-footprint types.
- **Rejected — extract as an OSS library now.** One consumer, polyglot (it is a spec + per-surface adapters, not
  a shared binary), premature. Structure for later extraction; harvest the spec when a second consumer exists.
