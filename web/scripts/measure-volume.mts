// The server leg's client (cr-volume-measurement-local, decision 5): one list call at a time against the throwaway
// instance, timed from this process. TEMPORARY: `cr-volume-measurement-cax` deletes it with the rest of the harness.
//
//   measure-volume.mts <photos|bare|tree|wide|members>
//
// Signs in as the instance's owner (the app's own login), then speaks a line protocol with `scripts/measure_volume.py`,
// which reads MariaDB's counters around each call — a seeder may not shell out, so the counting is the driver's:
//   out  `VOL {"ready":true,...}`                   signed in, nothing called yet
//   in   `full`  → out `VOL {...}`                  the list, unconditionally
//   in   `again` → out `VOL {...}`                  the list with the ETag of the last full call (304 when unchanged)
//   in   `quit`
// Each answer: status, time to first byte and total (request sent → last body byte, JSON parsing excluded), wire bytes
// and the `Content-Encoding` sent, the decoded bytes, what gzip would make of them, and the number of items.
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

type Target = { path: string; call: (etag?: string) => Promise<Response> }
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
        call: (etag) => raw(mediaApi().listNodeMediaRaw({ scopeId: place.scopeId, nodeId: place.nodeId, ifNoneMatch: etag })),
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

async function once(conditional: boolean): Promise<unknown> {
  seen = null
  const response = await t.call(conditional ? lastEtag : undefined)
  const body = response.status === 304 ? new Uint8Array() : new Uint8Array(await response.arrayBuffer())
  const e = seen as Exchange | null
  if (!e) die(`no exchange was seen for ${t.path} — the path this reads is not the one the client called`)
  if (!conditional && response.status === 200) lastEtag = response.headers.get('etag') ?? undefined
  let items: number | null = null
  if (response.status === 200) {
    const json = JSON.parse(new TextDecoder().decode(body)) as unknown
    const list = Array.isArray(json) ? json : ((json as { media?: unknown[] }).media ?? null)
    items = Array.isArray(list) ? list.length : null
  }
  return {
    status: response.status,
    ttfbMs: +(e.headersAt - e.startedAt).toFixed(2),
    totalMs: +(e.endedAt - e.startedAt).toFixed(2),
    wireBytes: e.wireBytes,
    bodyBytes: e.bodyBytes,
    gzipBytes: body.length ? gzipSync(body).length : 0,
    contentEncoding: e.contentEncoding,
    items,
    etag: response.headers.get('etag'),
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
