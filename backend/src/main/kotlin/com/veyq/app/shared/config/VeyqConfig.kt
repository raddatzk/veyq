package com.veyq.app.shared.config

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import java.time.Duration
import java.util.Optional

/**
 * The single typed model of the project-owned `veyq.*` configuration (styleguide §8, audit P3c / cr-0134).
 * Replaces the ~28 scattered per-value `@ConfigProperty` injections with one `@ConfigMapping` so every knob is
 * discoverable in one place, typed, and defaulted here rather than repeated at each injection site.
 *
 * Property names are derived from the method path in kebab-case, so `tasks().maxAttempts()` ⇒
 * `veyq.tasks.max-attempts`. Values still come from `application.properties` (+ `%dev`/`%test` overrides); the
 * `@WithDefault`s mirror those files and the previous annotation defaults — they are the fallback when a key is
 * absent, not a second source of truth.
 *
 * Framework config (`quarkus.*`, `mp.jwt.*`, …) stays on direct injection (styleguide §8) and is not modeled here.
 *
 * The `*.purge.every` / `*.reconcile.every` intervals are consumed by the sibling `@Scheduled(every = "{…}")`
 * beans as raw property expressions (a `@Scheduled` string cannot read a mapping), but they are declared here too
 * so this interface stays the complete inventory of `veyq.*`.
 */
@ConfigMapping(prefix = "veyq")
interface VeyqConfig {
    fun version(): Version
    fun release(): Release
    fun capabilities(): Capabilities
    fun tenant(): Tenant
    fun session(): Session
    fun recovery(): Recovery
    fun pairing(): Pairing
    fun scope(): Scope
    fun media(): Media
    fun node(): Node
    fun blob(): Blob
    fun blobStore(): BlobStore
    fun storeReclaim(): StoreReclaim
    fun tasks(): Tasks
    fun ratelimit(): RateLimit
    fun share(): Share
    fun security(): Security
    fun http(): Http
    fun adminChannel(): AdminChannel

    /**
     * `veyq.admin-channel.*` — the backend's second, internal listener: the only door the Server-Owner operations
     * answer on (ADR 0065, uc.admin.admin-channel). Only the admin container is ever pointed at it.
     */
    interface AdminChannel {
        /**
         * Not 9000 (Quarkus' management-interface default) and not 8081 (compose's `ADMIN_PORT` host default), so
         * neither can be mistaken for it. `0` binds a free port — the `%test` value, read back through
         * `AdminListener.port()`.
         */
        @WithDefault("8090")
        fun port(): Int
    }

    /** A scheduled cleanup interval (`*.purge.every` / `*.reconcile.every`); read by the `@Scheduled` sibling. */
    interface Interval {
        @WithDefault("1h")
        fun every(): Duration
    }

    interface Version {
        /** Hard compatibility-gate major version. */
        @WithDefault("1")
        fun major(): Int

        /** Informational build version. */
        @WithDefault("1")
        fun build(): Int
    }

    /**
     * The release the running image was published under — a **different** number from [Version], never merged
     * with it (ADR 0057 §4). [Version] answers *"can these two sides talk to each other?"* and is pre-auth;
     * this answers *"which bits are running, and what do I roll back to?"* and is Server-Owner-only, because a
     * precise release number is what maps an instance to a list of known vulnerabilities.
     */
    interface Release {
        /**
         * `<major>.<minor>` — the tree's `VERSION` at the commit that published this image, bound from the
         * image's `VEYQ_VERSION` in `application.properties`. **Absent** for any build the release pipeline
         * never stamped (a local `docker build`, a `quarkusDev` run), and that absence is reported as itself:
         * a synthesised number is the one an operator would later try to roll back *to*
         * (uc.admin.identify-instance-release A1).
         */
        fun version(): Optional<String>
    }

    interface Capabilities {
        /** Operator off-switch: capability ids to disable (absent ⇒ all enabled). */
        fun disabled(): Optional<List<String>>
    }

    interface Tenant {
        fun invite(): Invite
        fun ingress(): Ingress

        /**
         * The DB→reverse-proxy routing projection (ADR 0063). The `%dev`/`%test` default is a clean no-op: a
         * self-hoster running the backend without any reverse proxy must not need a value here.
         */
        interface Ingress {
            /**
             * **Which** proxy the projection is written to (ADR 0064 gave it a second one). Named, never
             * inferred from which of the blocks below happens to be populated — ADR 0067 Decision 1 made the
             * identical call for the object store after rejecting inference, and this is the same shape of
             * ambiguity.
             *
             * [IngressSinkType.TRAEFIK_FILE] is the default because it is what every deployment did before this
             * member existed, and it is inert without [dynamicConfigPath] — the same reasoning that keeps
             * `veyq.blob-store.signing-mode`'s default where it is, so "no existing deployment moves" is shown
             * by untouched tests rather than by edited ones.
             */
            @WithDefault("traefik-file")
            fun sink(): IngressSinkType

            fun kubernetes(): Kubernetes

            /**
             * Where `TraefikFileIngressSink` writes the Traefik file-provider config. **Absent ⇒ the projector
             * does nothing at all** — not an error, and not a degraded mode: an instance with no proxy in front
             * of it has nothing to project into, so `TenantIngressSinkProducer` hands it `DisabledIngressSink`.
             */
            fun dynamicConfigPath(): Optional<String>

            /**
             * The deployment-time bootstrap domain (`VEYQ_DOMAIN`), routable before any tenant row exists. The
             * Traefik sink renders its router pair from this value on every regenerate; the chart's own
             * `Ingress` routes it on Kubernetes. Either way the projector drops a tenant row carrying the same
             * domain, because two routers on one `Host` make Traefik drop both.
             */
            fun bootstrapDomain(): Optional<String>

            /**
             * The Server-Owner surface's own domain (ADR 0065 § Own domain). Absent — the compose default — the
             * admin surface is reachable only on its localhost port and the Traefik sink renders no router for
             * it. Read only by that sink: on Kubernetes the chart routes the admin surface itself.
             */
            fun adminDomain(): Optional<String>

            /**
             * `veyq.tenant.ingress.upstream.*` — where the Traefik sink's services point. The recipe owns the
             * topology and passes it in; the defaults are the production compose file's service names. None of
             * them may ever be the backend's admin listener ([AdminChannel.port]): a public router in front of it
             * would put every Server-Owner operation on the internet (ADR 0065 § admin channel).
             */
            fun upstream(): Upstream

            interface Upstream {
                /** The app's PUBLIC listener. */
                @WithDefault("http://app:8080")
                fun app(): String

                /** The store's S3 API — the same-origin blob route's backend (ADR 0013 §4). */
                @WithDefault("http://garage:3900")
                fun blobs(): String

                /** The admin container (ADR 0065) — its own nginx, which alone talks to the admin listener. */
                @WithDefault("http://admin:8080")
                fun admin(): String
            }

            /** `veyq.tenant.ingress.kubernetes.*` — read only when [sink] is [IngressSinkType.KUBERNETES]. */
            interface Kubernetes {
                /**
                 * **An override, never a requirement.** In a cluster the namespace comes from the pod's own
                 * mounted ServiceAccount token, which is what lets one image serve every install with no
                 * per-install rebuild — the property the compose/admin-web work already established. This
                 * exists for a local or test run against a cluster the process is not inside of.
                 */
                fun namespace(): Optional<String>

                /**
                 * The `spec.ingressClassName` to stamp on every projected `Ingress`. Absent leaves the field
                 * unset, which hands the object to the cluster's default IngressClass — the right answer when
                 * there is exactly one controller, and the reason this is not required. ADR 0064's controller is
                 * operator-chosen, so this is passed through and never interpreted.
                 */
                fun ingressClassName(): Optional<String>

                /**
                 * The `Service` every projected `Ingress` routes to, and its port — this sink's counterpart of
                 * [Upstream.app]. An `Ingress` cannot omit a backend, and the backend is a deployment-time name
                 * the chart owns. The defaults are the chart's own names; an operator who renames the Service
                 * sets these.
                 */
                @WithDefault("veyq-app")
                fun serviceName(): String

                @WithDefault("8080")
                fun servicePort(): Int

                /** `veyq.tenant.ingress.kubernetes.tls.*` — whether and how every projected `Ingress` carries `spec.tls`. */
                fun tls(): Tls

                /**
                 * Stamped **verbatim** as `metadata.annotations` on every projected `Ingress`, and never
                 * interpreted — this is how the chart hands the operator's certificate issuer to objects the
                 * backend writes at runtime (cert-manager's `cert-manager.io/cluster-issuer`, Traefik's CRD
                 * annotations, whatever the operator's controller reads). The same cut the compose sink makes by
                 * *referencing* the `letsencrypt` resolver by name and never configuring one: the reference is
                 * ours to stamp, the configuration is the chart's and the operator's. Property text quotes the
                 * key, because annotation keys carry dots and slashes:
                 * `veyq.tenant.ingress.kubernetes.annotations."cert-manager.io/cluster-issuer"=letsencrypt`.
                 */
                fun annotations(): Map<String, String>

                interface Tls {
                    /**
                     * [IngressTlsMode.NONE] is the default for the same reason `traefik-file` is the sink
                     * default: no existing deployment moves, and it is the right answer where TLS terminates
                     * upstream of the cluster's ingress (a cloud load balancer, a service mesh).
                     */
                    @WithDefault("none")
                    fun mode(): IngressTlsMode

                    /**
                     * The one pre-provisioned TLS `Secret` every projected `Ingress` points at — **required by
                     * [IngressTlsMode.SHARED]** and ignored by the other modes. `TenantIngressSinkProducer`
                     * refuses `shared` without it the way it refuses an unresolvable namespace: loud and
                     * disabled, never a failed boot.
                     */
                    fun secretName(): Optional<String>
                }
            }
        }

        interface Invite {
            /** Owner-invite validity window. */
            @WithDefault("P7D")
            fun ttl(): Duration

            /** How long terminal (accepted/expired) invites are kept before purge. */
            @WithDefault("P30D")
            fun retention(): Duration

            /** Dev-only JSONL sink for invite links (absent in prod). */
            fun devFile(): Optional<String>

            fun purge(): Interval
        }
    }

    interface Session {
        fun cookie(): Cookie
        fun purge(): Interval

        interface Cookie {
            /** `Secure` attribute on the refresh cookie (off in `%dev`/`%test`). */
            @WithDefault("true")
            fun secure(): Boolean
        }
    }

    /** Password recovery (ADR 0054) — one-time token lifetimes. */
    interface Recovery {
        fun token(): Token
        fun purge(): Interval

        interface Token {
            /** Recovery-link validity. Much shorter than an invite's: it is redeemed at the mailbox, not later. */
            @WithDefault("PT1H")
            fun ttl(): Duration

            /** How long spent/expired rows are kept, so a replay reads as spent rather than unknown. */
            @WithDefault("P7D")
            fun retention(): Duration
        }
    }

    /** Device linking (ADR 0059) — how long a pairing channel lives, and how long a spent one is kept. */
    interface Pairing {
        fun channel(): Channel
        fun purge(): Interval

        interface Channel {
            /** How long a channel stays open. Shorter than anything else here on purpose: both devices are in
             *  the person's hands for the whole ceremony, so this bounds an abandoned one, not a slow user. */
            @WithDefault("PT10M")
            fun ttl(): Duration

            /** How long terminal rows are kept, so a replayed handle reads as spent rather than unknown
             *  (uc.account.onboard-device E1). */
            @WithDefault("P1D")
            fun retention(): Duration
        }
    }

    interface Scope {
        fun delete(): Delete

        interface Delete {
            /** Group soft-delete grace window before the hard purge. */
            @WithDefault("P30D")
            fun retention(): Duration

            fun purge(): Interval
        }
    }

    interface Node {
        fun trash(): Trash

        interface Trash {
            /** Trashed-subtree restorable window before `NodeSubtreePurge` hard-erases it (uc.memory.delete-node). */
            @WithDefault("P30D")
            fun retention(): Duration

            fun purge(): Interval
        }
    }

    interface Media {
        fun trash(): Trash
        fun upload(): Upload

        interface Trash {
            /** Trashed-media restorable window before the hard purge. */
            @WithDefault("P30D")
            fun retention(): Duration

            fun purge(): Interval
        }

        interface Upload {
            /** How long an OPEN upload session is kept after its **last issued window** before the sweep reclaims
             *  its row + staged chunk objects. Deliberately far shorter than the trash/scope/invite windows, and the
             *  floor is **measured, not chosen**: every window's presigned PUTs are signed under
             *  `veyq.blob-store.presign-ttl-seconds`, so a session with no new window past that TTL can receive no
             *  further byte. What this buffers is only a PUT that started inside the TTL and is still streaming —
             *  one 256 KiB chunk. */
            @WithDefault("PT1H")
            fun retention(): Duration

            fun purge(): Interval
        }
    }

    /** `veyq.store-reclaim.*` — the single store-object deleter (ADR 0062). */
    interface StoreReclaim {
        /** How often the sweep drains the reclaim list. This is also the **latency the design accepts**: bytes
         *  linger up to one interval after the rows that named them are gone (Decision 3). */
        fun sweep(): Interval

        /** Refusals a row survives before it is stamped abandoned and its bytes become a countable leak. An
         *  unbounded retry was rejected in Decision 5 — a queue that grows silently is the same disease one
         *  level up. */
        @WithDefault("5")
        fun maxAttempts(): Int

        /** Base retry delay after a refusal, doubling per attempt. The sweep interval is the resolution floor,
         *  so a value below it simply means "next tick"; at the defaults a dead store is given up on after
         *  roughly eight hours. */
        @WithDefault("PT15M")
        fun backoff(): Duration

        /** Rows one tick attempts. Bounds the batch handed to the store, not the list — the rest waits a tick. */
        @WithDefault("500")
        fun batchSize(): Int
    }

    interface Blob {
        /**
         * Per-blob declared byte cap — a sanity ceiling on one declaration, not a limit anyone plans around (ADR 0013
         * §3). It bounds a file, never a total; a total is the storage quota's (ADR 0073).
         */
        @WithDefault("21474836480")
        fun maxBytes(): Long

        /**
         * How many chunks one presign window signs (ADR 0013 §2): `start` signs a blob's first window and each
         * window request the next, so the presign TTL has to cover one window rather than one file, and one request
         * signs at most 256 items × this. 64 chunks is 16 MiB of plaintext.
         */
        @WithDefault("64")
        fun presignWindow(): Int
    }

    interface BlobStore {
        /**
         * Which **kind** of store this connection points at, and therefore how far veyq may go in bringing it
         * up (ADR 0067 Decision 1). The default is [StoreType.S3] deliberately: an unknown store is **never
         * poked**, so pointing veyq at somebody else's bucket changes nothing about it.
         *
         * The member is named `type` and not `storeType` on purpose — property names are derived from the
         * method path (see this interface's KDoc), there is no `@WithName` anywhere in this codebase to
         * override that, and `storeType()` would silently bind `veyq.blob-store.store-type` instead of
         * `veyq.blob-store.type`.
         *
         * It belongs to the **connection**, not to the environment: there is one connection in V1, and a SaaS
         * instance with several (`docs/inbox.md` #41) gets a type per connection without reshaping anything.
         */
        @WithDefault("s3")
        fun type(): StoreType

        /** Internal S3 endpoint (blank/absent fails startup — media is not gracefully degraded). */
        fun endpoint(): Optional<String>

        /** Client-reachable URL used purely to SIGN presigned URLs (blank ⇒ endpoint). */
        fun publicEndpoint(): Optional<String>

        /**
         * **Which origin a presigned URL is signed for** (ADR 0067 Decision 5) — a named mode, not a shape
         * inferred from the other members. The default is [SigningMode.TENANT_DOMAIN], which is bit-for-bit
         * what every deployment did before this member existed: no instance moves, and the compose recipe
         * needs no new line.
         *
         * **Never inferred**, and the tempting inference is a specific one: *"the public endpoint's host
         * matches no tenant domain, so the store must be on its own origin"*. That reads the `tenant` table,
         * which is runtime state — creating a tenant would silently change how a running instance signs.
         *
         * Read once at boot like everything else under `veyq.blob-store.*`: [com.veyq.app.shared.blobstore.S3BlobStore]
         * caches one presigner per resolved endpoint and never invalidates it, so a runtime-settable mode
         * would leave stale presigners behind with no code path that notices.
         */
        @WithDefault("tenant-domain")
        fun signingMode(): SigningMode

        fun accessKey(): Optional<String>

        fun secretKey(): Optional<String>

        @WithDefault("veyq")
        fun bucket(): String

        @WithDefault("us-east-1")
        fun region(): String

        @WithDefault("600")
        fun presignTtlSeconds(): Int

        /** `veyq.blob-store.garage.*` — reached only when [type] is [StoreType.GARAGE]. */
        fun garage(): Garage

        /**
         * The bundled store's own admin plane, nested so these members are structurally scoped to the type
         * that uses them rather than free-floating on `veyq.blob-store.*` (the shape of [Tasks.Lease] and
         * [RateLimit.Recovery]). Both absent is the correct state of every non-Garage instance.
         */
        interface Garage {
            /**
             * Garage's Admin API base URL (`http://garage:3903`), which is a **different port from the S3
             * endpoint** and is deliberately not published outside the compose network. Absent ⇒ provisioning
             * cannot run; it reports that rather than failing the boot.
             */
            fun adminEndpoint(): Optional<String>

            /** The bearer token that admin plane requires (`GARAGE_ADMIN_TOKEN`). Operator env, never persisted. */
            fun adminToken(): Optional<String>

            /** Storage capacity advertised for the single bundled node when veyq assigns it a layout role. */
            @WithDefault("1G")
            fun nodeCapacity(): String

            /** The zone name the single bundled node is assigned to. One node, so its value is arbitrary and fixed. */
            @WithDefault("local")
            fun nodeZone(): String
        }
    }

    /**
     * The kinds of object store veyq knows how to talk to, which is the same thing as *how far it may go in
     * setting one up* (ADR 0067). It is the codebase's first enum-typed config member; SmallRye maps the
     * property text onto the constant name case-insensitively, so `veyq.blob-store.type=garage` selects
     * [GARAGE].
     */
    enum class StoreType {
        /**
         * Any S3-compatible store veyq did not bring up. **No provisioning runs at all** — not a bucket
         * create, not a CORS policy: the operator owns that store and veyq changes nothing about it
         * (ADR 0067 Decision 1, which spells out why the two steps that *would* generalise still do not run
         * here). Being the default is what makes that the safe answer for every store nobody measured.
         *
         * The change this invites is "surely it could at least create the bucket / write the CORS policy" —
         * ADR 0013 §4 is the reason it may not: the `*` policy veyq would write is measured and justified for
         * the **bundled** store and for nothing else. Read that decision before widening this.
         */
        S3,

        /**
         * The bundled Garage (ADR 0013 §1). veyq owns both sides here, so it takes the store from *running* to
         * *usable* end to end over Garage's Admin API — layout, bucket, key, grant — plus the one genuinely-S3
         * step, the bucket CORS policy.
         */
        GARAGE,
    }

    /**
     * Which origin [com.veyq.app.shared.blobstore.S3BlobStore] signs a presigned URL for (ADR 0067 Decision 5),
     * one constant per deployment shape ADR 0013 §4 already distinguishes. The three are **mutually exclusive and total** — `endpointFor` is a single `when`
     * over them with no fallthrough and no second predicate — which is the whole reason this is an enum and not
     * the boolean the change looks like. A boolean can name two of these; the third then hides in a blank
     * `public-endpoint`, which is the shape that let `uc.admin.tenant-reachability` A5 be only half serveable.
     *
     * Property text is hyphenated (`veyq.blob-store.signing-mode=store-origin`), the same mapping [StoreType]
     * uses; `S3BlobStoreSigningModeConfigTest` pins that binding rather than trusting it.
     */
    enum class SigningMode {
        /**
         * **The store is same-origin behind this deployment's own proxy** — the docker-compose path route
         * (ADR 0063). `public-endpoint`'s **host** is swapped for the resolved tenant's own registered domain,
         * so each tenant's browser fetches from the origin it is already on and no CORS policy has to exist.
         * A request that resolved to no tenant signs against `public-endpoint` unchanged.
         *
         * The default, because it is what every deployment did before this member existed.
         */
        TENANT_DOMAIN,

        /**
         * **The store is reachable at an origin of its own, which is not the endpoint the backend uses.**
         * `public-endpoint` is signed against **verbatim**: every tenant gets the same store host, because the
         * store's origin has nothing to do with which tenant is asking. Two callers, neither a special case of
         * the other (ADR 0067 Decision 5): ADR 0064's Kubernetes branch, where the backend reaches Garage at an
         * in-cluster Service name while the browser reaches it at an Ingress host, and a SaaS tenant on their
         * own bucket.
         *
         * **This mode needs a bucket CORS policy to work end to end** — it is ADR 0013 §4's non-same-origin
         * branch. Signing is server-side and cannot tell whether that policy exists: a missing one is a green
         * server and a browser-blocked fetch.
         */
        STORE_ORIGIN,

        /**
         * **The store is already publicly reachable at exactly the endpoint the backend uses** (an external S3,
         * ADR 0013 §4's original other branch). The internal endpoint is signed against and a tenant domain
         * plays no part — substituting one would sign for a bucket host that does not exist.
         *
         * A blank `public-endpoint` resolves here whatever this member says, because it is then the only
         * answer left: there is no public endpoint either to swap into or to use verbatim.
         */
        INTERNAL_ENDPOINT,
    }

    /**
     * Which [com.veyq.app.shared.tenant.TenantIngressSink] the tenant→routing projection is written to
     * (ADR 0063's principle, ADR 0064's second target). Property text is hyphenated
     * (`veyq.tenant.ingress.sink=kubernetes`), the same SmallRye mapping [StoreType] and [SigningMode] use;
     * `KubernetesIngressSinkUnreachableTest` pins that binding by selecting this sink from config rather than
     * trusting the mapping.
     */
    enum class IngressSinkType {
        /**
         * A Traefik file-provider config on a volume the proxy watches — the docker-compose deployment
         * (ADR 0063). Inert without `veyq.tenant.ingress.dynamic-config-path`, which is the `%dev`/`%test`
         * default and what lets a self-hoster run the backend with no proxy at all.
         */
        TRAEFIK_FILE,

        /**
         * `Ingress` objects managed directly through the Kubernetes API, in the backend's **own** namespace
         * (ADR 0064). Needs no connection settings: the in-cluster ServiceAccount supplies both the API server
         * and the namespace.
         */
        KUBERNETES,
    }

    /**
     * Whether and how `KubernetesIngressSink` emits `spec.tls` on every projected `Ingress` (ADR 0064, the
     * TLS passthrough). Named, never inferred — the same shape as [IngressSinkType]. What the sink emits is a
     * **reference** (a `secretName`, plus the operator's annotations); what it never does is create an
     * `Issuer`, a `Certificate` or a `Secret` — that is the ingress controller's and the operator's, and it
     * needs no RBAC beyond the `Ingress` verbs the sink already has. Property text is hyphenated
     * (`veyq.tenant.ingress.kubernetes.tls.mode=per-domain`).
     */
    enum class IngressTlsMode {
        /** No `spec.tls` at all — TLS terminates upstream, or the operator wires it some other way. The default. */
        NONE,

        /**
         * One `spec.tls` entry per domain, `secretName` = the `Ingress` object's **own name**. cert-manager's
         * ingress-shim shape, and the one that keeps per-tenant custom domains working. Reusing the object name
         * rather than deriving a second one is load-bearing: a `Secret` name has the same 253-character cap an
         * object name has, and `KubernetesIngressSink.nameFor` already keeps the name legal for a domain at the
         * contract's own cap — a `-tls` suffix on it would not be.
         */
        PER_DOMAIN,

        /**
         * Every projected `Ingress` points at the one pre-provisioned `Secret` named by
         * `veyq.tenant.ingress.kubernetes.tls.secret-name` — a wildcard certificate, kept as an **operator's
         * choice** rather than as everyone's ceiling: an operator who bought one `*.example.com` certificate
         * and runs no ACME gets a working instance, and gives up custom domains knowingly.
         */
        SHARED,
    }

    interface Tasks {
        fun lease(): Lease

        /** Max attempts before a job is dead-lettered. */
        @WithDefault("5")
        fun maxAttempts(): Int

        fun grantDelivery(): GrantDelivery

        interface Lease {
            /** Lease TTL granted on job claim. */
            @WithDefault("PT5M")
            fun ttl(): Duration
        }

        interface GrantDelivery {
            fun reconcile(): Interval
        }
    }

    /** `veyq.ratelimit.*` — the boundary rate limiters. Values live here, never in an annotation: the
     *  `@RateLimit(key)` posture (ADR 0051) declares the *axis* so a self-hosted instance can still tune the
     *  *numbers* ([invariant] self-hostable). */
    interface RateLimit {
        /** `veyq.ratelimit.auth.*` — the per-IP bound. The prefix is historical (it began as the auth-only
         *  limiter); the bound is `@RateLimit(key = IP)` wherever it is declared. Renaming it would break every
         *  self-hosted instance's config file for no behavioural gain, so it stays. */
        fun auth(): Auth

        /** `veyq.ratelimit.user.*` — the per-principal bound for `@RateLimit(key = USER)`. Deliberately generous:
         *  an authenticated caller is attributable and revocable, so this bounds runaway automation, not abuse. */
        fun user(): User

        /** `veyq.ratelimit.invite.*` — the member-invite mail limiter (cr-email-invite-send-hardening): the real
         *  anti-bombing guard, keyed per-(tenant,recipient) AND per-tenant, applied in the handler before the mint.
         *  Defaults are **deliberately set, not empirically calibrated — revidierbar**: 3 per recipient / 20 per
         *  tenant, per hour. */
        fun invite(): Invite

        /** `veyq.ratelimit.recovery.*` — the password-recovery limiter (cr-account-recovery, ADR 0054), keyed
         *  per-ADDRESS and per-INSTANCE. Deliberately not per-tenant like [invite]: an account is tenant-global,
         *  so a tenant key would bound neither one address nor the instance. Defaults deliberately set. */
        fun recovery(): Recovery

        interface Recovery {
            /** The anti-bombing guard: recovery mails one **address** may trigger per window. */
            @WithDefault("3")
            fun perAddressMaxRequests(): Int

            /** The volume cap against a scraper walking a list of addresses. */
            @WithDefault("60")
            fun perInstanceMaxRequests(): Int

            @WithDefault("3600")
            fun windowSeconds(): Long
        }

        interface Auth {
            @WithDefault("60")
            fun maxRequests(): Long

            @WithDefault("60")
            fun windowSeconds(): Long
        }

        interface User {
            @WithDefault("600")
            fun maxRequests(): Long

            @WithDefault("60")
            fun windowSeconds(): Long
        }

        interface Invite {
            /** The tighter, primary guard: how many invites one tenant may send to the **same recipient** per window. */
            @WithDefault("3")
            fun perRecipientMaxRequests(): Int

            /** The volume cap against an owner-as-sender: total invites a **tenant** may send per window. */
            @WithDefault("20")
            fun perTenantMaxRequests(): Int

            @WithDefault("3600")
            fun windowSeconds(): Long
        }
    }

    /** `veyq.security.*` — operator-side security knobs. */
    interface Security {
        /** Path to the file holding the base64 32-byte KEK that seals server-held config secrets at rest
         *  ([ADR 0050](../../../../../../../docs/decisions/0050-operator-config-secret-encryption.md)).
         *  The server **generates and manages** this file on first start (`0600`, base64 32-byte key) if it does
         *  not exist; an operator supplying their own key mounts it and points this at it (the K8s/Docker-secret
         *  shape). Default `config-secret.key` (relative to the process CWD) — put it on a **persisted volume** in
         *  a container, else every recreate loses the key (the DB fingerprint tripwire then flags it loudly). The
         *  old env **value** `veyq.security.config-secret-key` is gone. */
        @WithDefault("config-secret.key")
        fun configSecretFile(): String
    }

    /** `veyq.http.*` — response headers the app shell sends on every response (ADR 0069 Decision 4). */
    interface Http {
        fun hsts(): Hsts

        /**
         * `Strict-Transport-Security` (cc.security.csp § *The other response headers*). On by default; the one
         * reason to switch it off is a deployment whose host is also served over plain HTTP somewhere, or whose
         * own proxy already sets the header. There is no `includeSubDomains` and no `preload` to configure:
         * tenant domains are arbitrary hosts, and the ADR decides both away rather than leaving them to a knob.
         */
        interface Hsts {
            @WithDefault("true")
            fun enabled(): Boolean

            /** Moderate on purpose: a browser keeps the entry for the whole `max-age` even after the header is
             *  switched off, so this is also how long switching it off takes to reach every returning browser. */
            @WithDefault("P180D")
            fun maxAge(): Duration
        }
    }

    /** `veyq.share.upload.rate.*` — the uploader-link rate limiter. */
    interface Share {
        fun upload(): Upload

        /** cr-0165: the brute-force bound on wrong share-password attempts (per share, per window). Distinct budget
         *  from [upload] — an order of magnitude tighter, since a password guess is an attack and an upload is not. */
        fun password(): Password

        /** `veyq.share.identity.rate.*` — the bound on anonymous contributor-identity creation, the one guest call
         *  that writes a row before any upload exists. A real guest names itself **once** per share, so this is
         *  deliberately much tighter than [upload] and still far above honest traffic. */
        fun identity(): Identity

        interface Upload {
            fun rate(): Rate

            interface Rate {
                @WithDefault("300")
                fun maxPerWindow(): Int

                @WithDefault("60")
                fun windowSeconds(): Long
            }
        }

        interface Identity {
            fun rate(): Rate

            interface Rate {
                @WithDefault("30")
                fun maxPerWindow(): Int

                @WithDefault("3600")
                fun windowSeconds(): Long
            }
        }

        interface Password {
            fun rate(): Rate

            interface Rate {
                @WithDefault("10")
                fun maxPerWindow(): Int

                @WithDefault("60")
                fun windowSeconds(): Long
            }
        }
    }
}
