// What the orchestrator booted, read from the one file it writes before it starts Playwright (ADR 0090). The suite
// never guesses an address or a credential: everything it may reach is named here, and `egress.ts` refuses the rest.
// Read lazily, so `pnpm run check` can list the tests without a stack; a test that touches it without one is told
// how the suite is meant to be started.
import { readFileSync } from 'node:fs'

export interface Stack {
  /** The bootstrap domain the app answers on (VEYQ_DOMAIN), e.g. `veyq.test`. */
  domain: string
  /** `https://<domain>` — Traefik, with a certificate from the run's Pebble. */
  appOrigin: string
  /**
   * Where the browser reaches Traefik: its service name on the run's `edge` network. Chromium resolves the domain
   * and every subdomain to it (`--host-resolver-rules`), the way public DNS resolves an operator's domain to the
   * host; the port stays 443, Traefik's own.
   */
  traefikHost: string
  /**
   * `http://localhost:<ADMIN_PORT>` — the admin console as an operator opens it through `ssh -L` (run/first-run.mdx).
   * A loopback origin on purpose: it is a secure context, which admin-web needs (`crypto.subtle` for the breach
   * check, `navigator.clipboard` for Copy link, Secure cookies over plain HTTP), and which `http://admin:8080` is not.
   */
  adminOrigin: string
  /** `admin:8080` — the admin container on the run's `admin` network, the far end of that tunnel (`tunnel.ts`). */
  adminUpstream: string
  /**
   * `http://mail.relay.test:8025` — the HTTP API of the run's mail relay (the overlay's Mailpit, on the `admin`
   * network), where the suite reads what the instance mailed. Its SMTP side is what a Server Owner types into
   * Server → Mail: host `mail.relay.test`, port 1025, no STARTTLS.
   */
  mailApi: string
  /**
   * The release check's test double (tests/e2e/stack/docker-compose.e2e.yml, `release-mirror`; inbox #464): the release the
   * run stamped its instance with, and the newer ones the stub mirror at `source` lists — the next minor, the next
   * major (planned, published on `plannedDate`) and the one after (marked unscheduled). Each a `<major>.<minor>`.
   */
  releases: {
    source: string
    running: string
    newer: string
    planned: string
    plannedDate: string
    unscheduled: string
  }
  /** Where the run keeps its evidence: traces, the report, the state the setup project hands on. */
  outDir: string
}

let loaded: Stack | undefined

export function stack(): Stack {
  if (loaded) return loaded
  const path = process.env.VEYQ_STACK_FILE
  if (!path) {
    throw new Error('VEYQ_STACK_FILE is unset: start this suite through scripts/run_stack_e2e.py, which boots the stack it describes.')
  }
  loaded = JSON.parse(readFileSync(path, 'utf8')) as Stack
  return loaded
}
