// The operator's `ssh -L <ADMIN_PORT>:127.0.0.1:<ADMIN_PORT>` (run/first-run.mdx § Reach the console), inside the
// browser's container: a loopback listener on the admin port that forwards every connection, byte for byte, to the
// admin container on the run's `admin` network. The browser therefore opens the console at `http://localhost:<port>`
// exactly as an operator does — a secure context, the same origin, the same cookies — without the run publishing
// anything the production recipe does not (ADR 0090 decision 3). Playwright's global setup: it lives in the runner's
// main process, which outlasts every test.
import { createConnection, createServer } from 'node:net'
import { stack } from './stack'

export default async function openTunnel(): Promise<() => Promise<void>> {
  const { adminOrigin, adminUpstream } = stack()
  const listen = new URL(adminOrigin)
  const [upstreamHost, upstreamPort] = adminUpstream.split(':')
  const server = createServer((client) => {
    const upstream = createConnection({ host: upstreamHost, port: Number(upstreamPort) })
    client.pipe(upstream).pipe(client)
    client.on('error', () => upstream.destroy())
    upstream.on('error', () => client.destroy())
  })
  await new Promise<void>((resolve, reject) => {
    server.once('error', reject)
    server.listen(Number(listen.port), '127.0.0.1', resolve)
  })
  return () => new Promise<void>((resolve) => server.close(() => resolve()))
}
