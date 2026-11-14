---
id: cr-live-stream
title: Core's live stream — SSE that announces a list's new position to the clients that edit it; the journal moves onto it and shows who is here
status: planned
satisfies: ["uc.memory.journal"]
adrs: ["0134", "0133", "0033", "0081", "0021", "0103", "0106", "0092", "0030"]
invariants:
  - "the stream carries positions and who is present, never a sealed or plaintext byte of a list (ADR 0134 decision 2)"
  - "only lists an application declares streamable can be subscribed; the gallery and the level views stay on the poll (ADR 0134 decision 3)"
  - "a link guest is told each other present editor's alias (the journal's, ADR 0133 decision 9), never a name or a member id (ADR 0134 decision 4)"
  - "nothing about a stream is stored, and no log line names a person (ADR 0134 decision 7)"
---

## Goal

Build [ADR 0134](../docs/decisions/0134-core-pushes-a-lists-new-position-to-the-clients-that-edit-it-on-a-stream.md):
core serves one server-sent-events stream per editing page, subscribed to at most 16 streamable lists the caller may
read, announcing each list's new position after the writer's commit and who else holds a stream onto it; the web
client reads it with `fetch`, answers each event with the list's ordinary conditional read, and falls back to ADR 0033's
poll whenever it has no stream. The journal ([ADR 0133](../docs/decisions/0133-a-memorys-journal-is-a-sealed-crdt-log-whose-snapshots-only-members-write.md))
is its first and only user: edits reach the other editors within a second, and the editor shows who is here. Both
deployment targets carry it.

**Entry condition:** `cr-live-journal` is done (the journal list exists and is read through ADR 0033's conditional
read). This CR cites it as planned; re-read its retrospective before phase 1.

## Context & constraints

- Measured 2026-11-13 on `main` (`480955ec2`):
  - No push exists: `grep -rlE 'WebSocket|websockets|SseBroadcaster|text/event-stream|RestStreamElementType' src/backend contracts`
    finds nothing. The backend runs Quarkus REST (`io.quarkus:quarkus-rest` in `src/backend/core/build.gradle.kts`);
    the server stubs come from openapi-generator 7.27.0 (`src/backend/buildSrc/build.gradle.kts`).
  - One replica on both targets: `infra/kubernetes/charts/veyq/templates/app-deployment.yaml` sets `replicas: 1`.
  - The chart routes through Gateway API (`templates/app-route.yaml`, an `HTTPRoute` with one `PathPrefix /` rule);
    the implementation is the operator's (`gateway.className`). Compose fronts the app with Traefik
    (`infra/docker/docker-compose.prod.yml`).
  - Member requests carry a bearer header (`src/web/src/api/client.ts`), which `EventSource` cannot send.
- **Spot-check before phase 1:** whether openapi-generator's server stub for a `text/event-stream` response compiles
  to something Quarkus REST can stream from (`Multi<…>` with `@RestStreamElementType`). If it does not, the operation
  is declared in the contract and implemented by a resource outside the generated interface, classified in
  `ModuleCompositionTest` and `OperatorBlindProbeTest` with that reason — a decision this CR takes, recorded in the
  phase 1 commit.
- Not measured at refine (docs-only session): the gates' exit codes on `main`; each phase records them before its
  first edit.

## Decisions (technical, settled here)

1. **Contract — core's spec, a new tag `live`:** `openLiveStream` (`GET /api/live?list=<nodeId>:<list>&…`, at most
   16, `text/event-stream`) for a member, and `openShareLiveStream` under the share route for a `READ_WRITE` guest.
   Events as schemas: `position` (`nodeId`, `list`, `revision`), `present` (`nodeId`, `list`, and for a member
   `members` and `identities` ids, for a guest `others` as the other holders' aliases, base64 of 8 bytes each, and
   `self` its own), `closed` (`nodeId`, `list`, no reason) and the
   heartbeat comment. Refusals before the stream starts: `403/404` as the list's read, `409` for a list not
   streamable, `429` past the session's 4 streams, `503 live_capacity` past the instance maximum.
2. **Streamable lists:** an application declares them on its registrar (Memories: the journal list); core refuses any
   other. No list of the gallery or a level view is declared. A declaration may carry the list's **alias** function
   `(node, list, holder) → 8 bytes` (ADR 0134 decision 4); Memories' is the journal's (ADR 0133 decision 9, built by
   `cr-live-journal`), so the hub serves a guest the same alias the contributors list does. A list without one gives a
   guest a count (no such list exists yet; its case is a test, not a feature).
3. **Publishing:** core's items-revision bump registers an after-commit hook that hands `(node, list, revision)` to
   the in-process hub; a rolled-back write publishes nothing. The hub fans out to the streams subscribed to that list.
4. **Gate:** each list's read gate at open and again at every heartbeat (15 s); a failing list sends `closed` and is
   dropped; the stream ends when its last list is dropped or its session ends.
5. **Presence:** the hub keeps per list the set of stream holders (member id or link identity id); a change sends
   `present` to each holder, shaped per holder (decision 1): a guest's lists the other holders' aliases, computed when
   it is sent (the alias key is read once per list per hub and dropped with the list's last stream). In memory only.
6. **Transport:** no compression on the stream (ADR 0103's exemption list), a 15 s heartbeat, the configuration value
   `veyq.live.max-streams` (2,000) and 4 per session, the open under core's rate limit. The chart's `HTTPRoute` gets a
   rule for `/api/live` and the share stream's path with `timeouts.request: 0s` and `timeouts.backendRequest: 0s`;
   Compose needs no line unless phase 2 measures otherwise.
7. **Web:** `core/live/liveStream.ts` reads the stream with `fetch` and a stream reader (the session's headers, or the
   share's token-less form), parses SSE, reconnects with jittered backoff after 40 s without a byte, and on every
   (re)connect triggers one read per list; `itemsLive.ts`'s loop takes the stream as its tick source and keeps its
   timer as the fallback (ADR 0033's cadence while streaming, the journal's 2 s while not). The journal editor shows
   who is here (names for a member from the member list and guest names; for a guest the pseudonyms of
   `cr-live-journal`'s `journalPseudonym`, the same as its contributors popover, never a count), reusing the
   participants' avatar row.

## Inventory (locators — run, read as lists)

| What | Command |
|---|---|
| The bump and its after-commit seam | `git grep -n -e "fun bump" -e afterCompletion -e TransactionSynchronization -- src/backend/core/src/main` |
| Compression's exemptions and their test | `git grep -n -i compress -- src/backend/core/src/main/resources/application.properties` and `src/backend/src/test/kotlin/me/raddatz/veyq/core/http/TokenAnswersAreNotCompressedTest.kt` |
| The live loop's users (switch to the stream as tick source) | `git grep -ln -e createLivePoller -e itemsPollTick -- src/web/src` |
| The route templates and the Compose router | `infra/kubernetes/charts/veyq/templates/app-route.yaml`, `git grep -n -i traefik -- infra/docker` |
| Chart checks that pin routes | `git grep -ln -i httproute -- scripts infra tests` |
| Seams core asks an application (where "streamable" goes) | `docs/apps/README.md` § The seams core asks, and the composed set `git grep -n "fun seams" -- src/backend/src/test` |
| Site pages to touch | `git grep -ln -e "what-the-server-sees" -e "operator" -- site/src/content/docs/security` and `git grep -ln -i -e gateway -e traefik -- site/src/content/docs/run` |

## Phases

**Phase 1 — contracts.** Decision 1 and the spot-check above. Done when: `pnpm run generate:api` and the contracts
guards green; the new operations classified in `OperatorBlindProbeTest`; the predicted backend reds named.

**Phase 2 — backend and both deployment targets.** Decisions 2–6. Tests: a journal update's commit reaches a
subscribed stream within a second and a rolled-back one never; a gallery list is refused (`409`, with the journal's
positive control); a member and a `READ_WRITE` guest each open one, a `READ` guest is refused (with its `READ_WRITE`
control); `present` names ids to a member and to a guest only the other holders' aliases — each equal to that writer's alias
in the guest's contributors list, none a member or identity id, and different for the same writer in another journal; a closed link and an ended membership end the
list at the next heartbeat; the 5th stream of a session `429`s and the instance maximum `503`s; the stream is never
compressed. On both targets: a stream through Compose's Traefik and through the chart on a kind cluster with one
Gateway implementation stays open past 2 minutes and delivers an event; the chart's check pins the new rule. Done
when: `./gradlew check` and the chart's check green, and both target proofs recorded in the commit.

**Phase 3 — web.** Decision 7. Unit tests: the SSE parser (events, comments, split chunks); reconnect after silence
with one read per list; the fallback timer only while no stream; the presence row for a member (names) and a guest (pseudonyms, the contributors popover's for the same alias). The lane
L1 spec of `cr-live-journal` asserts the other context sees a typed word within 2 s and shows the other as present.
Done when: `pnpm run check` green, the spec green once, and a **browser pass** on the dev instance: two profiles in
one journal see each other's typing within a second and each other in the presence row; stopping the backend shows
the fallback, restarting it resumes the stream.

**Phase 4 — docs, site, coverage.** `uc.memory.journal`'s `live-and-present` criterion into refs; ADR 0134's `Built`
line; `docs/apps/README.md` § How an application publishes a revisioned list (a streamable list, the hub);
`docs/apps/journal.md`; the security model's operator-visible metadata (who has a journal open for editing). Site:
Security — what the server sees (the stream's presence signal); Run — the stream's needs on Compose and on a Gateway
(no request timeout, no buffering, `veyq.live.max-streams`); the Use page *Write a journal together* (EN+DE) gains who
is here. Done when: the trust-chain guards and the site's own check green.

## Definition of done

The four phases' done-whens; the CR's lessons moved to where they bind and this file deleted.

## Retrospective

_(filled by the implementer)_
