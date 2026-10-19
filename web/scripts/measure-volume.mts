// The server leg's client (cr-volume-measurement-local): one list call at a time against the throwaway
// instance, timed from this process. TEMPORARY: `cr-volume-measurement-cax` deletes it with the rest of the harness.
//
//   measure-volume.mts <photos|bare|tree|wide|members>
//
// Signs in as the instance's owner (the app's own login), then speaks a line protocol with `scripts/measure_volume.py`,
// which reads MariaDB's counters around each call — a seeder may not shell out, so the counting is the driver's:
//   out  `VOL {"ready":true,...}`                   signed in, nothing called yet
//   in   `full`  → out `VOL {...}`                  the list, unconditionally — for a media list EVERY page of it
//   in   `again` → out `VOL {...}`                  the list with the ETag of the last full call (304 when unchanged)
//   in   `quit`
// Each answer: status, time to first byte and total (request sent → last body byte, JSON parsing excluded), wire bytes
// and the `Content-Encoding` sent, the decoded bytes, what gzip would make of them, and the number of items. A media list
// is read in pages since cr-gallery-list-paging (ADR 0033 decision 6), `MEASURE_PAGE_LIMIT` items each (default 1000, the
// client's): the reading is the whole read — its pages, the first page's TTFB, the time from the first request to the
// last body byte, and every page's bytes summed.
import './volumePrelude.mts'
import 'fake-indexeddb/auto'
import { createInterface } from 'node:readline'
import { gzipSync } from 'node:zlib'
import type { Exchange } from './apiTransport.mts'
import { accounts, die, exchanges, manifest, signIn } from './volumeHarness.mts'
import { contentApi, scopesApi } from '../src/api/client'
import { mediaApi } from '../src/features/memory/api'

const axis = process.argv[2] ?? ''
const a = accounts()
const m = manifest()

type Target = { path: string; call: (etag?: string, since?: string) => Promise<Response>; paged?: boolean }
const PAGE_LIMIT = Number(process.env.MEASURE_PAGE_LIMIT ?? 1000)
function target(): Target {
  const raw = async <T extends { raw: Response }>(p: Promise<T>): Promise<Response> => {
    try { return (await p).raw } catch (e) {
      const response = (e as { response?: unknown }).response
      if (response instanceof Response) return response // 304 and every error: the status is the reading
      throw e
    }
  }
  switch (axis) {
    case 'photos': case 'bare': {
      const place = m[axis] ?? die(`the manifest has no '${axis}' Chapter — seed it first`)
      return {
        path: `/api/scopes/${place.scopeId}/nodes/${place.nodeId}/media`,
        call: (etag, since) => raw(mediaApi().listNodeMediaRaw({ scopeId: place.scopeId, nodeId: place.nodeId, ifNoneMatch: etag, since, limit: PAGE_LIMIT })),
        paged: true,
      }
    }
    case 'tree': case 'wide': {
      const place = m[axis] ?? die(`the manifest has no '${axis}' group — seed it first`)
      return {
        path: `/api/scopes/${place.scopeId}/nodes`,
        call: (etag) => raw(contentApi().listNodesRaw({ scopeId: place.scopeId, ifNoneMatch: etag })),
      }
    }
    case 'members':
      return { path: `/api/scopes/${a.tenantId}/members`, call: () => raw(scopesApi().listScopeMembersRaw({ scopeId: a.tenantId })) }
    default:
      die('usage: measure-volume.mts <photos|bare|tree|wide|members>')
  }
}

const say = (o: unknown) => process.stdout.write(`VOL ${JSON.stringify(o)}\n`)

await signIn(a.owner)
const t = target()
let lastEtag: string | undefined
let seen: Exchange | null = null
exchanges.listener = (e) => { if (new URL(e.url).pathname === t.path) seen = e }

/** One request: its exchange, its body, its items and — for a media page — whether more remain and from where. */
async function request(etag: string | undefined, since: string | undefined) {
  seen = null
  const response = await t.call(etag, since)
  const body = response.status === 304 ? new Uint8Array() : new Uint8Array(await response.arrayBuffer())
  const e = seen as Exchange | null
  if (!e) die(`no exchange was seen for ${t.path} — the path this reads is not the one the client called`)
  let items: number | null = null
  let more = false
  let cursor: string | undefined
  if (response.status === 200) {
    const json = JSON.parse(new TextDecoder().decode(body)) as unknown
    const list = Array.isArray(json) ? json : ((json as { media?: unknown[] }).media ?? null)
    items = Array.isArray(list) ? list.length : null
    more = (json as { more?: boolean }).more === true
    cursor = (json as { cursor?: string }).cursor
  }
  return { response, body, e, items, more, cursor }
}

async function once(conditional: boolean): Promise<unknown> {
  const first = await request(conditional ? lastEtag : undefined, undefined)
  if (!conditional && first.response.status === 200) lastEtag = first.response.headers.get('etag') ?? undefined
  const pages = [first]
  // A media list's later pages, from the cursor each answered, without If-None-Match (ADR 0033 decision 6).
  while (t.paged && pages[pages.length - 1].more) pages.push(await request(undefined, pages[pages.length - 1].cursor))
  const sum = (f: (p: (typeof pages)[number]) => number) => pages.reduce((n, p) => n + f(p), 0)
  return {
    status: first.response.status,
    pages: pages.length,
    pageLimit: t.paged ? PAGE_LIMIT : null,
    ttfbMs: +(first.e.headersAt - first.e.startedAt).toFixed(2),
    totalMs: +(pages[pages.length - 1].e.endedAt - first.e.startedAt).toFixed(2),
    wireBytes: sum((p) => p.e.wireBytes),
    bodyBytes: sum((p) => p.e.bodyBytes),
    gzipBytes: sum((p) => (p.body.length ? gzipSync(p.body).length : 0)),
    contentEncoding: first.e.contentEncoding,
    items: first.items === null ? null : sum((p) => p.items ?? 0),
    etag: first.response.headers.get('etag'),
    conditional,
  }
}

say({ ready: true, axis, path: t.path })
const lines = createInterface({ input: process.stdin })
for await (const line of lines) {
  const cmd = line.trim()
  if (cmd === 'quit') break
  if (cmd === 'full') say(await once(false))
  else if (cmd === 'again') say(await once(true))
  else say({ error: `unknown command ${cmd}` })
}
process.exit(0)
