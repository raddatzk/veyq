# ADR 0134 — Core pushes a list's new position to the clients that edit it, on a stream

- Status: Accepted
- Built: no
- Date: 2026-11-13
- Model: [Applications § How an application publishes a revisioned list](../apps/README.md#how-an-application-publishes-a-revisioned-list)

## Context

The live journal ([ADR 0133](0133-a-memorys-journal-is-a-sealed-crdt-log-whose-snapshots-only-members-write.md)) is
written by several people at once, and co-editing needs sub-second delivery. The platform's only live mechanism is
[ADR 0033](0033-live-media-updates-revision-polling.md)'s poll (about 20 s for a gallery); the backend has no
WebSocket, no SSE and no push of any kind (measured: `grep -rlE 'WebSocket|websockets|SseBroadcaster|text/event-stream|RestStreamElementType' src/backend contracts`
finds nothing). ADR 0033 and [ADR 0081](0081-a-scopes-tree-revision-is-the-level-views-change-signal.md) rejected push
for the gallery and the level views, partly because a held connection is a sharper presence signal to the operator
than a coarse poll, and deferred it to "a later push channel shared with the live journal".
[ADR 0021](0021-operator-blind-background-tasks.md) left the push seam open as "the same backend, no broker".

| Option | Latency | Cost and fit |
|---|---|---|
| ADR 0033's poll at a 1–3 s cadence | the cadence | nothing new; a request per open editor per tick |
| Long-poll on the position | sub-second | a request held per open editor |
| **SSE down, ordinary POSTs up** | sub-second | one stream per page; plain HTTP through Traefik and a Gateway |
| WebSocket both ways | lowest | an authentication, rate-limit, `Origin` and guest-gate path beside core's REST gates; `wss:` in the CSP; an upgrade through every proxy |

Both deployment targets run **one** backend (measured: the chart's `app-deployment.yaml` sets `replicas: 1`, and
`values.yaml` says raising it is a decision about the migration mechanism, not a knob).

## Decision

### 1. Server-sent events down, every write an ordinary operation up

A client that **edits** a list opens one stream; every write stays an ordinary operation behind core's existing gates
(session or the share guest gate, `Origin`, rate limit, the switch's writability, a moderator's `451`, the guest-write
record), described in the contract like every other. Only the read side is new.

### 2. The stream carries positions, never content — the read is the truth

An event says **"list L of node N moved to position p"**: core publishes it from the items-revision bump
([docs/apps](../apps/README.md) § How an application publishes a revisioned list) after the writer's transaction
commits. The client answers it with the list's ordinary conditional delta read (ADR 0033 decisions 2–3), which
returns what moved. So the stream is the poll's timer replaced by a push: it needs no resume — on every (re)connect the
client reads each list once (a `304` where nothing moved) — and a lost event loses nothing, because the next event or
the reconnect read catches up. It carries **no sealed byte**, so it reveals nothing the read does not, needs no
per-recipient filtering of content, and stays application-agnostic: core never learns what a list holds.
**Rejected:** the item itself inside the event — one round trip less (about one request's latency), for a core path
that carries application data, filters it per recipient and must resume by cursor.

### 3. A stream subscribes to a bounded set of lists the caller may read

The stream is opened for a set of (node, list) pairs, at most 16, each checked as the list's read is: a member's read
of the node, or a link guest's gate for a `READ_WRITE` link onto it. **An application declares which of its lists may
be streamed** — the journal's yes; the gallery's and every level view's no, so they stay on the poll and ADR 0033's
and ADR 0081's reasons stand for them. A changed set reopens the stream. The gate is checked again at every heartbeat;
a list whose gate now refuses (the link closed, the membership ended, the session expired, a block) ends the stream
with an event that names no reason, and the client's next read meets the real refusal.

### 4. Who is here, from the stream itself

The server knows who holds a stream onto a list, so it sends a **`present`** event whenever that set changes: to a
member the writers (member ids and link identity ids, which a member's client names), to a link guest each other
holder's **alias** — an opaque per-list handle the list's application derives, never a name and never a member id
([ADR 0030](0030-contributor-public-share.md) point 8). The journal's alias is the one its contributors list serves a
guest ([ADR 0133](0133-a-memorys-journal-is-a-sealed-crdt-log-whose-snapshots-only-members-write.md) decision 9: an
HMAC of the writer under the journal's own key, which the guest's client names "Anonymous Fox"), so a guest sees the
same pseudonym in both places (owner 2026-11-13: pseudonyms, not a count). An application registers the alias with
the list it declares streamable; a list whose application derives none tells a guest only how many others are there.
Only editors stream (decision 3), so "here" means "has it open for editing". This tells the operator nothing it does
not hold already: it holds the streams, and the alias is computed from them.

**Live cursors are a later slice** (inbox #854), and their shape is decided here: an ephemeral message sealed under the
list's content key by the sender (for the journal, `K_jrev`), of one fixed size, posted at most a few times a second
and relayed by the stream to the list's other holders with the server-attested sender — **never stored**. The operator
sees that they flow, not where anyone's cursor is. Yjs's own awareness protocol sends names and cursors in the clear
and is not used as it is.

### 5. In-process fan-out; no broker

A commit's bump hands its event to the streams of the same process. Should more replicas ever be decided, each replica
tails the items-revision table by position for the lists it has streams for — the database it already has. A broker
(Redis, NATS) would be the first extra service a self-hoster must run, and is rejected.

### 6. The transport, on both targets

- **Heartbeat:** a comment line every 15 s, inside every proxy's idle timeout; a client that hears nothing for 40 s
  reconnects with jittered backoff, and reads on ADR 0033's poll while it has no stream, so the page works where a
  proxy breaks streaming.
- **The web client reads the stream with `fetch` and a stream reader**, not `EventSource`, which cannot send the
  bearer `Authorization` header every member request carries.
- **No compression on the stream** ([ADR 0103](0103-the-application-compresses-its-answers-except-the-ones-that-carry-a-token.md)
  compresses answers by default; a compressed stream is buffered until it is flushed).
- **Compose:** Traefik passes the stream as it is. **Helm:** the chart's `HTTPRoute` gets a rule for the stream path
  whose request timeout is off (`0s`, Gateway API's "no timeout"); whether the operator's Gateway implementation
  buffers is theirs, and the Run docs say what the stream needs. Both targets are checked, as every infra decision is
  ([ADR 0106](0106-deployment-under-infra-tests-under-tests.md)).
- **Bounds:** at most 4 open streams per session (tabs), an instance-wide maximum (a configuration value, 2,000 by
  default) past which the open answers `503` and the client stays on the poll, and the open under core's rate limit.

### 7. What the operator learns, and what is kept

With the stream the operator learns **who has which list open for editing, from when to when** — sharper than a poll,
which only shows a request every few seconds. That is the price ADR 0033 named; it is paid only where a person edits
with others (the journal), never for viewing. Nothing about a stream is stored: the subscription set lives in memory,
and the logs name no person ([ADR 0092](0092-logs-are-operational-and-name-no-person.md)). Opening a stream is no
write, so a guest's stream leaves no row in the guest-write record.

## Consequences

- **The first long-held connection in veyq**: a thread-free reactive path in the backend, a `text/event-stream`
  operation in the contract (its generated shape is the build's first question), and one more line on each deployment
  target.
- **Sub-second co-editing** for the journal; the gallery and the level views are unchanged.
- **A held connection per editing page** costs memory per stream, bounded by decision 6.
- **ADR 0033's deferred push is this record**; that ADR's poll stays the fallback and the reader's path.

### Rejected alternatives

- **WebSocket:** a second authentication, rate-limit, `Origin` and guest-gate path beside core's REST gates, a `wss:`
  source in the CSP and an upgrade through every proxy, for latency the journal does not need below SSE's.
- **Long-poll:** a held request per editor per change, the same presence signal, and a reconnect per event.
- **The poll at 1–3 s:** a request per open editor per tick, and still seconds of latency while two people type.
- **Streaming every list** (galleries, level views): the presence signal for everyone who merely looks.
