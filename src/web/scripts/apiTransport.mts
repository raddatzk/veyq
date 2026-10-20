// How a dev seeder reaches the backend: the generated `…Api` clients, configured here, and nothing else
// (cr-api-callers-typed-by-the-contract). A seeder that hand-writes a request body is outside the contract's net:
// when login verify gained `nonce`, the app's login changed with the spec and `bootstrap:user` kept posting the
// old body until it failed its own login with 400. A generated client turns that into a typecheck error.
//
// This is the ONE file under scripts/ that may speak HTTP by hand (lint: `veyq/seeders-use-the-generated-client`),
// because a tenant-scoped call needs a transport `fetch` cannot be: the backend resolves the active tenant from the
// request's `Host`, and fetch/undici drops a hand-set `Host` header. So the tenant channel hands the generated client
// a `fetchApi` over `node:http` that connects to the backend's port and presents the tenant's domain as `Host` —
// which works for any domain, resolvable or not, and keeps an existing `.run/.env` working unchanged.
import { request as httpRequest } from 'node:http'
import { Agent as HttpsAgent, request as httpsRequest } from 'node:https'
import { brotliDecompressSync, gunzipSync, inflateSync } from 'node:zlib'
import type { ConfigurationParameters, FetchAPI } from '../src/api/generated/runtime'

/** The public listener: everything a member does. */
export const BASE = process.env.VEYQ_BASE ?? 'http://localhost:8080'
/** The admin listener: the only door the Server-Owner operations answer on — the public port 404s them (ADR 0065).
 *  8090 is its dev default (`veyq.admin-channel.port`). */
export const ADMIN_BASE = process.env.VEYQ_ADMIN_BASE ?? 'http://127.0.0.1:8090'

const bearer = (token?: string): Partial<ConfigurationParameters> => (token ? { accessToken: () => token } : {})

/** Account-level calls on the public listener, where no tenant Host is needed (setup status, login, register). */
export function publicChannel(token?: string): ConfigurationParameters {
  return { basePath: BASE, ...bearer(token) }
}

/** Server-Owner calls on the admin listener. */
export function adminChannel(token?: string): ConfigurationParameters {
  return { basePath: ADMIN_BASE, ...bearer(token) }
}

/** Tenant-scoped calls: the public listener, addressed as `host`. */
export function tenantChannel(host: string, token: string): ConfigurationParameters {
  return { basePath: BASE, fetchApi: hostPinnedFetch(host), ...bearer(token) }
}

/** Statuses whose response may not carry a body (`new Response` throws on one that does). */
const NULL_BODY = new Set([101, 204, 205, 304])

function hostPinnedFetch(host: string): FetchAPI {
  return (input, init = {}) => {
    const url = new URL(input instanceof Request ? input.url : input)
    const body = init.body ?? undefined
    if (body !== undefined && typeof body !== 'string') {
      return Promise.reject(new Error('apiTransport: only JSON (string) bodies are supported on the tenant channel'))
    }
    const headers: Record<string, string | number> = { ...Object.fromEntries(new Headers(init.headers).entries()), host }
    if (body !== undefined) headers['content-length'] = Buffer.byteLength(body)
    return new Promise<Response>((resolve, reject) => {
      const req = httpRequest(
        { host: url.hostname, port: url.port || 80, path: url.pathname + url.search, method: init.method ?? 'GET', headers },
        (res) => {
          const chunks: Buffer[] = []
          res.on('data', (c: Buffer) => chunks.push(c))
          res.on('end', () => {
            const status = res.statusCode ?? 0
            const responseHeaders = new Headers()
            for (const [k, v] of Object.entries(res.headers)) {
              if (v !== undefined) responseHeaders.set(k, Array.isArray(v) ? v.join(', ') : v)
            }
            const buf = Buffer.concat(chunks)
            resolve(new Response(NULL_BODY.has(status) || buf.length === 0 ? null : buf, { status, headers: responseHeaders }))
          })
        },
      )
      req.on('error', reject)
      if (body !== undefined) req.write(body)
      req.end()
    })
  }
}

/** A failed generated call, as one line: the status and the body the backend answered with, or the transport error. */
export async function describeFailure(e: unknown): Promise<string> {
  const response = (e as { response?: unknown } | null)?.response
  if (response instanceof Response) return `${response.status} ${await response.text().catch(() => '')}`
  return e instanceof Error ? `${e.message}${e.cause instanceof Error ? ` (${e.cause.message})` : ''}` : String(e)
}

// --- the volume harness's transport (cr-volume-measurement-local) ------------------------------------------------------
// The harness drives the app's OWN client code (`src/web/src`: login, unlock, `addCopiedMedia`, the tree creators) against
// a throwaway production-recipe instance, which answers only on its domain over TLS (Traefik on :443, a Pebble
// certificate). That domain does not resolve on this host and its CA is no system one, and the client code calls the
// global `fetch`, so the harness replaces the global for that domain alone: every request to it is sent over
// `node:https` to a fixed address, with the domain as SNI and `Host`, trusting the given CA and nothing else. Two more
// things only a hand transport can give: the refresh cookie is kept (the web client refreshes by cookie, which Node's
// fetch drops — a seeding run outlives an access token), and every exchange is reported with the bytes as they came
// off the wire, before decompression (`measure-volume.mts` reads time to first byte, wire bytes and the encoding sent).

/** One request and response as the wire saw it. Times are `performance.now()` milliseconds. */
export type Exchange = {
  method: string
  url: string
  status: number
  startedAt: number
  headersAt: number
  endedAt: number
  /** Body bytes as received, before any decompression. */
  wireBytes: number
  /** Body bytes after decompression (equal to wireBytes when nothing was encoded). */
  bodyBytes: number
  contentEncoding: string | null
}

export type PinnedTarget = {
  /** The instance's domain; it and every subdomain of it are pinned. */
  domain: string
  /** Where the domain is reached instead of resolved (`127.0.0.1:443` locally). */
  connectHost: string
  connectPort: number
  /** The one CA trusted for it (PEM). */
  caPem: string
  /** Called once per finished exchange to the domain. */
  onExchange?: (e: Exchange) => void
}

function headersOf(init: RequestInit | undefined, request: Request | null): Headers {
  const h = new Headers(request?.headers)
  new Headers(init?.headers).forEach((v, k) => h.set(k, v))
  return h
}

async function bodyBytes(body: unknown): Promise<Buffer | undefined> {
  if (body === undefined || body === null) return undefined
  if (typeof body === 'string') return Buffer.from(body)
  if (body instanceof Uint8Array) return Buffer.from(body.buffer, body.byteOffset, body.byteLength)
  if (body instanceof ArrayBuffer) return Buffer.from(body)
  if (ArrayBuffer.isView(body)) return Buffer.from(body.buffer, body.byteOffset, body.byteLength)
  if (body instanceof Blob) return Buffer.from(await body.arrayBuffer())
  if (body instanceof URLSearchParams) return Buffer.from(body.toString())
  throw new Error(`apiTransport: unsupported request body ${Object.prototype.toString.call(body)}`)
}

function decode(raw: Buffer, encoding: string | null): Buffer {
  switch ((encoding ?? '').trim().toLowerCase()) {
    case '': case 'identity': return raw
    case 'gzip': case 'x-gzip': return gunzipSync(raw)
    case 'br': return brotliDecompressSync(raw)
    case 'deflate': return inflateSync(raw)
    default: throw new Error(`apiTransport: unknown content-encoding ${encoding}`)
  }
}

/**
 * Replace the global `fetch` for one domain with the pinned transport described above; every other URL keeps the
 * original `fetch` (the mailbox, for one). Returns a function that restores the original. Asks for `gzip, br` unless
 * the caller set `accept-encoding`, as a browser does, and hands the caller the decoded body.
 */
export function installPinnedFetch(target: PinnedTarget): () => void {
  const original = globalThis.fetch
  const agent = new HttpsAgent({ keepAlive: true, maxSockets: 64, ca: target.caPem })
  const jar = new Map<string, string>()
  const pinned = (hostname: string) => hostname === target.domain || hostname.endsWith(`.${target.domain}`)

  const pinnedFetch = async (input: string | URL | Request, init?: RequestInit): Promise<Response> => {
    const request = input instanceof Request ? input : null
    const url = new URL(request ? request.url : input.toString())
    if (url.protocol !== 'https:' || !pinned(url.hostname)) return original(input, init)
    const method = (init?.method ?? request?.method ?? 'GET').toUpperCase()
    const headers = headersOf(init, request)
    const body = await bodyBytes(init?.body !== undefined ? init.body : request && request.body ? await request.arrayBuffer() : undefined)
    if (!headers.has('accept-encoding')) headers.set('accept-encoding', 'gzip, br')
    if (jar.size > 0) headers.set('cookie', [...jar].map(([k, v]) => `${k}=${v}`).join('; '))
    const out: Record<string, string> = Object.fromEntries(headers.entries())
    out.host = url.port ? `${url.hostname}:${url.port}` : url.hostname
    if (body !== undefined) out['content-length'] = String(body.length)
    const signal = init?.signal ?? request?.signal ?? undefined
    if (signal?.aborted) throw new DOMException('aborted', 'AbortError')
    const startedAt = performance.now()
    return new Promise<Response>((resolve, reject) => {
      const req = httpsRequest({
        host: target.connectHost, port: target.connectPort, servername: url.hostname, agent,
        path: url.pathname + url.search, method, headers: out,
      }, (res) => {
        const headersAt = performance.now()
        const chunks: Buffer[] = []
        res.on('data', (c: Buffer) => chunks.push(c))
        res.on('error', reject)
        res.on('end', () => {
          try {
            const status = res.statusCode ?? 0
            for (const line of res.headers['set-cookie'] ?? []) {
              const [pair, ...attrs] = line.split(';')
              const eq = pair.indexOf('=')
              if (eq < 1) continue
              const name = pair.slice(0, eq).trim()
              const gone = attrs.some((a) => /^\s*max-age\s*=\s*(0|-)/i.test(a))
              if (gone) jar.delete(name)
              else jar.set(name, pair.slice(eq + 1).trim())
            }
            const raw = Buffer.concat(chunks)
            const encoding = (res.headers['content-encoding'] as string | undefined) ?? null
            const decoded = decode(raw, encoding)
            const responseHeaders = new Headers()
            for (const [k, v] of Object.entries(res.headers)) {
              if (v === undefined || k === 'content-encoding' || k === 'content-length' || k === 'set-cookie') continue
              responseHeaders.set(k, Array.isArray(v) ? v.join(', ') : v)
            }
            target.onExchange?.({
              method, url: url.toString(), status, startedAt, headersAt, endedAt: performance.now(),
              wireBytes: raw.length, bodyBytes: decoded.length, contentEncoding: encoding,
            })
            resolve(new Response(NULL_BODY.has(status) || decoded.length === 0 ? null : Uint8Array.from(decoded), { status, headers: responseHeaders }))
          } catch (e) {
            reject(e)
          }
        })
      })
      req.on('error', reject)
      signal?.addEventListener('abort', () => { req.destroy(); reject(new DOMException('aborted', 'AbortError')) }, { once: true })
      if (body !== undefined) req.write(body)
      req.end()
    })
  }
  globalThis.fetch = pinnedFetch as typeof fetch
  return () => { globalThis.fetch = original; agent.destroy() }
}

/** One Server-Owner call on the admin listener with a hand-built JSON body — for an admin operation the web client does not
 *  generate (`/api/instance/store`, ADR 0089: web has no `instance` client, admin-web does). The seeder reads only the fields
 *  it names from the answer. Returns the status and the parsed body, never throws on a non-2xx. */
export async function adminCall<T>(token: string, method: 'GET' | 'PUT' | 'POST', path: string, body?: unknown): Promise<{ status: number; json: T }> {
  const res = await fetch(`${ADMIN_BASE}${path}`, {
    method,
    headers: { authorization: `Bearer ${token}`, ...(body === undefined ? {} : { 'content-type': 'application/json' }) },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  })
  const text = await res.text()
  return { status: res.status, json: (text ? JSON.parse(text) : null) as T }
}
