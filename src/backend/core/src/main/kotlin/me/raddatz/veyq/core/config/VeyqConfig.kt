package me.raddatz.veyq.core.config

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithConverter
import io.smallrye.config.WithDefault
import me.raddatz.veyq.core.http.ClientAddress
import me.raddatz.veyq.core.http.TrustedProxiesConverter
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
 * so this interface stays the complete inventory of core's `veyq.*`.
 *
 * Core's only: an application's own keys, should it have any, are its own mapping in its own module, under a prefix of
 * its own below `veyq` (inbox #342; none has one today), never a member of this interface — core
 * reading one would be core naming an application (ADR 0079). Its defaults are its mapping's `@WithDefault`s, not
 * lines in `:core`'s `application.properties`: SmallRye refuses a `veyq.*` key no mapping claims (SRCFG00050), so
 * such a line would fail core's boot without the application (`:core`'s tests).
 */
@ConfigMapping(prefix = "veyq")
interface VeyqConfig {
    fun version(): Version
    fun release(): Release
    fun releaseCheck(): ReleaseCheck
    fun tenant(): Tenant
    fun account(): Account
    fun session(): Session
    fun recovery(): Recovery
    fun emailConfirmation(): EmailConfirmation
    fun pairing(): Pairing
    fun moderation(): Moderation
    fun scope(): Scope
    fun node(): Node
    fun application(): Application
    fun blob(): Blob
    fun blobStore(): BlobStore

    fun bundledStore(): BundledStore
    fun storeReclaim(): StoreReclaim
    fun storeReconcile(): StoreReconcile
    fun erasureLedger(): ErasureLedger
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

    /**
     * The pre-auth version's configurable half. Its `major` is not here: it is the platform's one major, generated from
     * the repository's `VERSION` (`PlatformMajor`, ADR 0085 decision 1), so no instance can be configured to advertise a
     * major its code does not speak.
     */
    interface Version {
        /** Informational build version: hand-kept, gates nothing (ADR 0057 §4). */
        @WithDefault("1")
        fun build(): Int
    }

    /**
     * The release the running image was published under. It shares its major with the pre-auth version
     * (ADR 0085 decision 1) and differs in precision (ADR 0057 §4): the pre-auth major answers *"can these two sides
     * talk to each other?"*; this `<major>.<minor>` answers *"which bits are running, and what do I roll back to?"*
     * and is Server-Owner-only, because a precise release number is what maps an instance to a list of known
     * vulnerabilities.
     */
    interface Release {
        /**
         * `<major>.<minor>` for a release, `<major>.<minor>-SNAPSHOT` for a snapshot — derived from the tree at
         * the commit that published this image (`VERSION` holds the major, the minor counts the commits since it
         * last changed, ADR 0057 §1), bound from the image's `VEYQ_VERSION` in `application.properties`. **Absent** for any build the release pipeline
         * never stamped (a local `docker build`, a `quarkusDev` run), and that absence is reported as itself:
         * a synthesised number is the one an operator would later try to roll back *to*
         * (uc.admin.identify-instance-release A1).
         */
        fun version(): Optional<String>
    }

    /**
     * `veyq.release-check.*` — the daily look for new releases (ADR 0097). Whether it runs and where it reads are the
     * Server Owner's settings in admin-web (ADR 0089), never configuration; only its rhythm and its patience are here.
     */
    interface ReleaseCheck {
        /** How often the check runs; read by `ReleaseCheck`'s `@Scheduled`. Once a day, and once shortly after a
         *  start, because the scheduler fires an `every` job at its first trigger check. */
        @WithDefault("24h")
        fun every(): Duration

        /** How long one request to the source may take, connecting and reading. A source slower than this is a
         *  failed check, logged and retried at the next run; nothing waits on it. */
        @WithDefault("PT10S")
        fun timeout(): Duration
    }

    interface Tenant {
        fun invite(): Invite
        fun ingress(): Ingress

        /** The deletion of an active tenant (ADR 0101 decision 10): how often `TenantPurge` looks for a due one. Its
         *  window is the scope trash's (`veyq.scope.delete.retention`), on purpose one value. */
        fun deletion(): Deletion

        interface Deletion {
            fun purge(): Interval
        }

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
             * `HTTPRoute` routes it on Kubernetes. Either way the projector drops a tenant row carrying the same
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
                 * The chart's `Gateway`, in the same namespace: every projected `ListenerSet` names it as its
                 * parent, and under `none`/`shared` every projected `HTTPRoute` attaches to one of its listeners.
                 * The backend never writes it (ADR 0064: the Role grants nothing on `gateways`), and the gateway
                 * implementation behind its `gatewayClassName` is the operator's choice, so nothing here names
                 * one. The default is the chart's own name; an operator who renames the `Gateway` sets this.
                 */
                @WithDefault("veyq")
                fun gatewayName(): String

                /**
                 * The `Service` every projected `HTTPRoute` routes to, and its port — this sink's counterpart of
                 * [Upstream.app]. A route's backend is a deployment-time name the chart owns. The defaults are the chart's own names; an operator who renames the Service
                 * sets these.
                 */
                @WithDefault("veyq-app")
                fun serviceName(): String

                @WithDefault("8080")
                fun servicePort(): Int

                /** `veyq.tenant.ingress.kubernetes.tls.*` — where a projected route is served: plain HTTP, a shared HTTPS listener, or its own. */
                fun tls(): Tls

                /**
                 * Stamped **verbatim** as `metadata.annotations` on every projected `ListenerSet` — so only under
                 * `tls.mode=per-domain`, the one mode that writes one — and never interpreted: this is how the
                 * chart hands the operator's certificate issuer to objects the backend writes at runtime
                 * (cert-manager's `cert-manager.io/issuer`, whatever else the operator's tooling reads). An
                 * `HTTPRoute` carries no certificate and gets none. The same cut the compose sink makes by
                 * *referencing* the `letsencrypt` resolver by name and never configuring one: the reference is
                 * ours to stamp, the configuration is the chart's and the operator's. Property text quotes the
                 * key, because annotation keys carry dots and slashes:
                 * `veyq.tenant.ingress.kubernetes.annotations."cert-manager.io/issuer"=veyq-acme`.
                 */
                fun annotations(): Map<String, String>

                interface Tls {
                    /**
                     * [IngressTlsMode.NONE] is the default for the same reason `traefik-file` is the sink
                     * default: no existing deployment moves, and it is the right answer where TLS terminates
                     * upstream of the cluster's gateway (a cloud load balancer, a service mesh).
                     */
                    @WithDefault("none")
                    fun mode(): IngressTlsMode

                    /**
                     * The one pre-provisioned TLS `Secret` the `Gateway`'s shared HTTPS listener references, and so
                     * every route attached to it — **required by [IngressTlsMode.SHARED]** and ignored by the other
                     * modes. The sink writes no listener on the `Gateway`, so it never stamps this name itself; the
                     * value is required because a `shared` sink without it describes a listener nobody can check. `TenantIngressSinkProducer`
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

    /** `veyq.email-confirmation.*` — address-confirmation token lifetimes (ADR 0074 Decision 4). */
    interface EmailConfirmation {
        fun token(): Token
        fun purge(): Interval

        interface Token {
            /** Long enough to reach a mailbox read once a day; short, because nothing waits on it. */
            @WithDefault("PT24H")
            fun ttl(): Duration

            /** How long spent/superseded/expired rows are kept, so a replay reads as spent rather than unknown. */
            @WithDefault("P7D")
            fun retention(): Duration
        }
    }

    /** Moderation (ADR 0099, ADR 0093 § 11): how often expired key-proof challenges and moderation-log events go. */
    interface Moderation {
        fun purge(): Interval

        /** Reports (ADR 0093 § 5, uc.moderation.report): the bounds a reporter meets. */
        fun report(): Report

        /** The guest-write record (ADR 0093 § 10): how often rows past their retention go. It also runs at startup. */
        fun guestWriteRecord(): GuestWriteRecord

        interface GuestWriteRecord {
            fun purge(): Interval
        }

        interface Report {
            /** How many of a member's reports may be open at once (owner decision Q8: a generous cost bound). */
            @WithDefault("100")
            fun openCap(): Int

            /** A link's report budget per window — a guest's bound beside the per-address one (Q8). */
            @WithDefault("20")
            fun perShareMaxRequests(): Int

            /** How many receipts one address gets per window (owner decision Q6: the receipt is no mail relay). */
            @WithDefault("3")
            fun receiptsPerAddress(): Int

            @WithDefault("3600")
            fun windowSeconds(): Long
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

    /** `veyq.account.*` — the account erasure (ADR 0101) and securing the account (ADR 0111). */
    interface Account {
        fun erasure(): Erasure
        fun rotation(): Rotation

        /** The account erasure (ADR 0101 decision 3): its window, and how often `AccountPurge` looks for a due one. */
        interface Erasure {
            /** How long after the request the account is erased, its own scopes with it: a window of the erasure's
             *  own, a safeguard for a mistaken or coerced request, no longer the scope trash's
             *  (`veyq.scope.delete.retention`, which stays for a scope deleted on its own). Read at each request and
             *  stored as the request's due date, so a change applies to requests made after it.
             *  `AccountErasureWindow` refuses to start on one that is not positive, or that with one purge tick
             *  reaches past the shortest calendar month. */
            @WithDefault("P14D")
            fun window(): Duration

            fun purge(): Interval
        }

        /** `veyq.account.rotation.*` — securing the account (ADR 0111 decision 6). */
        interface Rotation {
            fun stage(): Stage

            interface Stage {
                /** How long a stage of self-grants lives after it opened (server-observed) before a publish must apply
                 *  it; past it the publish answers `409 rotation_plan_changed` and `RotationStagePurge` deletes it. */
                @WithDefault("PT1H")
                fun ttl(): Duration

                fun purge(): Interval
            }
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

    /** `veyq.application.*` — an application left switched off in a tenant (uc.platform.erase-switched-off-application). */
    interface Application {
        fun off(): Off
        fun erasure(): Erasure

        interface Off {
            /** How long an application stays switched off in a tenant before its content there is erased: the day
             *  members are told is the moment it went off plus this (ADR 0079 decision 8). One instance-wide value. */
            @WithDefault("P30D")
            fun retention(): Duration
        }

        interface Erasure {
            /** How often `ApplicationErasurePurge` mails the due notices and erases what is due. */
            fun purge(): Interval
        }
    }

    /** `veyq.store-reclaim.*` — the single store-object deleter (ADR 0062). */
    interface StoreReclaim {
        /** How often the sweep drains the reclaim list. With the not-before it is the **latency the design
         *  accepts**: bytes linger up to `veyq.blob.upload.retention` plus one interval after the rows that named
         *  them are gone, because a recorded key waits until no PUT signed for it can still land (Decision 1). */
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

    /**
     * `veyq.store-reconcile.*` — the bucket reconcile (ADR 0062 § Consequences, `StoreReconcileSweep`): a pass lists
     * the whole bucket, a few pages per tick, and counts the objects no row claims; a key that stayed one for [grace]
     * goes onto the reclaim list while the Server Owner's switches allow it.
     */
    interface StoreReconcile {
        /** How often a tick runs. A tick lists at most [pagesPerTick] pages, so this and that bound the work per minute. */
        @WithDefault("PT1M")
        fun every(): Duration

        /** The earliest a pass starts after the previous one started. */
        @WithDefault("PT6H")
        fun period(): Duration

        /** Pages one tick lists — the bound per tick, so a large bucket takes several ticks rather than one long one. */
        @WithDefault("10")
        fun pagesPerTick(): Int

        /** Keys per page: S3's own ceiling for one ListObjectsV2 answer. Lower only in tests. */
        @WithDefault("1000")
        fun pageSize(): Int

        /** How long a key must stay an orphan in every pass, from when it was first seen, before it is due. */
        @WithDefault("P30D")
        fun grace(): Duration

        /** Two listed pages further apart than this on the store's clock restart every first-seen clock: the grace
         *  counts only time the reconcile observed. Must exceed [period] plus [every] (`BlobStoreStartupCheck`). */
        @WithDefault("P1D")
        fun maxGap(): Duration
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

        fun upload(): Upload

        /** `veyq.blob.upload.*` — core's upload sessions (ADR 0013); named `veyq.media.upload.*` until inbox #342. */
        interface Upload {
            /** How long an OPEN upload session is kept after its **last issued window** before the sweep reclaims
             *  its row + staged chunk objects. Deliberately far shorter than the trash/scope/invite windows, and the
             *  floor is **measured, not chosen**: every window's presigned PUTs are signed under
             *  `veyq.blob-store.presign-ttl-seconds`, so a session with no new window past that TTL can receive no
             *  further byte. What this buffers is only a PUT that started inside the TTL and is still streaming —
             *  one 256 KiB chunk. **Also the reclaim list's not-before** (`StoreReclaimStore.record`, ADR 0062
             *  Decision 1): it carries the same meaning there, so lowering it below the TTL breaks both —
             *  `BlobStoreStartupCheck` refuses to start on a value that does not exceed the TTL. */
            @WithDefault("PT1H")
            fun retention(): Duration

            fun purge(): Interval
        }
    }

    /**
     * `veyq.blob-store.*` — what is left of the store in configuration once the store itself is a Server-Owner
     * setting in the database (ADR 0089): tuning that is no part of a connection. Which store, where, and with
     * which credentials is [me.raddatz.veyq.core.blobstore.StoreSettings]' answer; the deployment's bundled
     * store is [bundledStore].
     */
    interface BlobStore {
        @WithDefault("600")
        fun presignTtlSeconds(): Int

        /** `veyq.blob-store.garage.*` — how veyq lays out a Garage it provisions (the bundled one, or a setting's). */
        fun garage(): Garage

        interface Garage {
            /** Storage capacity advertised for the single node when veyq assigns it a layout role. */
            @WithDefault("1G")
            fun nodeCapacity(): String

            /** The zone name the single node is assigned to. One node, so its value is arbitrary and fixed. */
            @WithDefault("local")
            fun nodeZone(): String
        }
    }

    /**
     * `veyq.bundled-store.*` — **the bundled-store hint** (ADR 0089 decision 3), the one store value the
     * environment still carries: the recipe that brings a Garage along says where it is and passes its admin token,
     * because that Garage needs the token at its own start. It is a hint, not a setting: it makes *Use the bundled
     * store* available in the admin console, and choosing that stores a reference to the hint, never a copy of it.
     * The hint exists when [endpoint], [adminEndpoint] and [adminToken] are all set.
     *
     * Besides "endpoint and admin token" it carries how this deployment exposes that store to browsers
     * ([publicEndpoint], [signingMode]) and its [bucket] and [region]: the topology is the recipe's, not something
     * a Server Owner could know or choose for a store the recipe wired. Its S3 credentials are not passed at all —
     * [me.raddatz.veyq.core.blobstore.BundledStoreCredentials] derives them from [adminToken], so the bundled case
     * never depends on the config-secret key.
     */
    interface BundledStore {
        /** The bundled store's S3 endpoint as the backend reaches it (`http://garage:3900`). */
        fun endpoint(): Optional<String>

        /** The origin browsers fetch it from, when it is not [endpoint]. */
        fun publicEndpoint(): Optional<String>

        /** How presigned URLs name that origin (ADR 0067 Decision 5) — the deployment's routing shape. */
        @WithDefault("tenant-domain")
        fun signingMode(): SigningMode

        /** Garage's Admin API base URL (`http://garage:3903`) — a different port from the S3 one, never published. */
        fun adminEndpoint(): Optional<String>

        /** The bearer token that admin plane requires (`GARAGE_ADMIN_TOKEN`). Operator env, never persisted. */
        fun adminToken(): Optional<String>

        /** Fixed once an instance is installed: an install that must move pins its old name instead. */
        @WithDefault("blobs")
        fun bucket(): String

        /** Must match `s3_region` in `infra/docker/garage.toml` — SigV4 signs the region. */
        @WithDefault("us-east-1")
        fun region(): String
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
     * Which origin [me.raddatz.veyq.core.blobstore.S3BlobStore] signs a presigned URL for (ADR 0067 Decision 5),
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
         * in-cluster Service name while the browser reaches it at a host the chart's `Gateway` serves, and a SaaS tenant on their
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
     * Where the client's source port comes from (ADR 0094 decision 7, `veyq.http.client-port.source`). Property text is
     * hyphenated (`proxy-protocol`), the same SmallRye mapping [StoreType] uses.
     */
    enum class ClientPortSource {
        /** No source: the port is the one the stop entry of `X-Forwarded-For` carries, or unknown. The default. */
        NONE,

        /** The PROXY protocol header the gateway opens each connection with (Helm: Envoy Gateway, Istio). */
        PROXY_PROTOCOL,

        /** A header the edge overwrites on every request, `veyq.http.client-port.header` (Compose: the Traefik plugin). */
        HEADER,
    }

    /**
     * Which [me.raddatz.veyq.core.tenant.TenantIngressSink] the tenant→routing projection is written to
     * (ADR 0063's principle, ADR 0064's second target). Property text is hyphenated
     * (`veyq.tenant.ingress.sink=kubernetes`), the same SmallRye mapping [StoreType] and [SigningMode] use;
     * `KubernetesGatewaySinkUnreachableTest` pins that binding by selecting this sink from config rather than
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
         * Gateway API objects (`HTTPRoute`, `ListenerSet`) managed directly through the Kubernetes API, in the
         * backend's **own** namespace (ADR 0064). Needs no connection settings: the in-cluster ServiceAccount supplies both the API server
         * and the namespace.
         */
        KUBERNETES,
    }

    /**
     * Where `KubernetesGatewaySink` serves every projected route, and so whether it is TLS (ADR 0064, the TLS
     * passthrough). Named, never inferred — the same shape as [IngressSinkType]. What the sink emits is a
     * **reference** (a listener to attach to, a `certificateRefs` name, the operator's annotations); what it
     * never does is create an `Issuer`, a `Certificate` or a `Secret` — that is cert-manager's and the
     * operator's, and it needs no RBAC beyond the `httproutes`/`listenersets` verbs the sink already has. The
     * name keeps "ingress" in ADR 0063's sense (the projection), not the Kubernetes kind. Property text is
     * hyphenated (`veyq.tenant.ingress.kubernetes.tls.mode=per-domain`).
     */
    enum class IngressTlsMode {
        /**
         * Every route attaches to the `Gateway`'s plain-HTTP listener and no `ListenerSet` is written — TLS
         * terminates upstream, or the operator wires it some other way. The default.
         */
        NONE,

        /**
         * One `ListenerSet` per domain carrying one HTTPS listener whose `certificateRefs` names the object's
         * **own name**, and the domain's route attaches to it — the shape cert-manager's Gateway support reads,
         * and the one that keeps per-tenant custom domains working. Reusing the object name rather than deriving
         * a second one is load-bearing: a `Secret` name has the same 253-character cap an object name has, and
         * `KubernetesGatewaySink.nameFor` already keeps the name legal for a domain at the contract's own cap — a
         * `-tls` suffix on it would not be.
         */
        PER_DOMAIN,

        /**
         * Every projected route attaches to the `Gateway`'s one hostname-less HTTPS listener, which references
         * the pre-provisioned `Secret` named by `veyq.tenant.ingress.kubernetes.tls.secret-name` — a wildcard
         * certificate, kept as an **operator's choice** rather than as everyone's ceiling: an operator who bought one `*.example.com` certificate
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
            /**
             * Lease TTL granted on job claim and on each heartbeat: 2 min, which a worker renews every 30 s while it
             * works. Below about 60 s the renewal no longer holds it; a stopped worker's job frees only by expiry
             * (ADR 0021 §6; application.properties says why it is short).
             */
            @WithDefault("PT2M")
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

        /** `veyq.ratelimit.storage-fit.*` - the member's "would this many bytes still fit the quota?" preflight (ADR 0082):
         *  yes or no only, so what is bounded is a bisection towards the free space, per member and per tenant.
         *  Defaults deliberately set, not calibrated. */
        fun storageFit(): StorageFit

        /** `veyq.ratelimit.recovery.*` — the password-recovery limiter (cr-account-recovery, ADR 0054), keyed
         *  per-ADDRESS and per-INSTANCE. Deliberately not per-tenant like [invite]: an account is tenant-global,
         *  so a tenant key would bound neither one address nor the instance. Defaults deliberately set. */
        fun recovery(): Recovery

        /** `veyq.ratelimit.email-confirmation.*` — the address-confirmation mail limiter (ADR 0074 Decision 4),
         *  keyed per ACCOUNT only. Defaults deliberately set, not calibrated. */
        fun emailConfirmation(): EmailConfirmation

        interface EmailConfirmation {
            /** Confirmation mails one account may trigger per window. */
            @WithDefault("3")
            fun perAccountMaxRequests(): Int

            @WithDefault("3600")
            fun windowSeconds(): Long
        }

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

        interface StorageFit {
            /** Questions one **member** of a tenant may ask per window. */
            @WithDefault("10")
            fun perMemberMaxRequests(): Int

            /** Questions a whole **tenant** may ask per window. */
            @WithDefault("60")
            fun perTenantMaxRequests(): Int

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

    /**
     * `veyq.erasure-ledger.*` — the erasure ledger (ADR 0056 decision 8): the outbox's sweep into `erasures.log`, and the
     * file itself, which lives beside leg (c)'s key ([Security.configSecretFile]) unless [file] names another path.
     */
    interface ErasureLedger {
        /** How often the sweep appends the outbox's unsent rows to the file: the window an erasure is not yet in it. */
        fun sweep(): Interval

        /** How often the file is trimmed to B while the instance runs (`ErasureLedgerFile.scheduledTrim`): how long a
         *  line may outlive B. The start trims it too. */
        fun purge(): Interval

        /** The ledger's path; unset, `erasures.log` in the directory of the config-secret key file — the state
         *  directory both deployment targets persist and no restore touches. The tests give each boot its own. */
        fun file(): java.util.Optional<String>

        /** Whether the start re-applies the file (`ErasureLedgerStart`). Off only in `%test`, whose database outlives a
         *  boot: a boot would re-apply every earlier test's lines to fixtures other tests left behind. */
        @WithDefault("true")
        fun applyAtStart(): Boolean
    }

    /** `veyq.security.*` — operator-side security knobs. */
    interface Security {
        /** Path to the file holding the base64 32-byte KEK that seals server-held config secrets at rest
         *  ([ADR 0050](../../../../../../../../../../../docs/decisions/0050-operator-config-secret-encryption.md)).
         *  The server **generates and manages** this file on first start (`0600`, base64 32-byte key) if it does
         *  not exist; an operator supplying their own key mounts it and points this at it (the K8s/Docker-secret
         *  shape). Default `config-secret.key` (relative to the process CWD) — put it on a **persisted volume** in
         *  a container, else every recreate loses the key (the DB fingerprint tripwire then flags it loudly). The
         *  old env **value** `veyq.security.config-secret-key` is gone. */
        @WithDefault("config-secret.key")
        fun configSecretFile(): String
    }

    /** `veyq.http.*` — response headers the app shell sends on every response (ADR 0069 Decision 4), and whom the
     *  app believes about a client's address (ADR 0094). */
    interface Http {
        fun hsts(): Hsts

        /**
         * `veyq.http.trusted-proxies` (`VEYQ_HTTP_TRUSTED_PROXIES`), ADR 0094 decisions 3 and 5: the hops **in front
         * of** the gateway, such as a load balancer, as comma-separated IP addresses and CIDR ranges. Never the
         * gateway's own pod range: the gateway is the socket peer, vouched for by the network. Unset, the default on
         * both targets, means the gateway is the only hop. A hostname, a bad prefix or an empty element fails the
         * conversion, so the app does not start. Read by [ClientAddress] through its request accessor only.
         */
        @WithConverter(TrustedProxiesConverter::class)
        fun trustedProxies(): Optional<ClientAddress.TrustList>

        /** `veyq.http.client-port.*` — where the client's source port comes from (ADR 0094 decision 7). */
        fun clientPort(): ClientPort

        interface ClientPort {
            /**
             * `veyq.http.client-port.source` (`VEYQ_HTTP_CLIENT_PORT_SOURCE`): the one source of the client's port
             * where the `X-Forwarded-For` entry the walk stops at carries none. Configured, never detected — a header
             * the edge does not overwrite is one any client writes. `proxy-protocol` requires
             * `quarkus.http.proxy.use-proxy-protocol=true` and every other value requires it off, or the app does not
             * start (`ClientPortSourceCheck`). An unknown value fails the mapping.
             */
            @WithDefault("none")
            fun source(): ClientPortSource

            /**
             * `veyq.http.client-port.header` (`VEYQ_HTTP_CLIENT_PORT_HEADER`): the header the edge overwrites with the
             * client's `address:port` on every request, read only under `source=header`. On Compose the app's routing
             * file hands this name to the Traefik plugin, so the two cannot drift apart.
             */
            @WithDefault(ClientAddress.PORT_HEADER)
            fun header(): String
        }

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

        /** `veyq.share.expiry-close.*` — the end-date sweep (ADR 0114 decision 6, `ShareExpiryCloseSweep`). */
        fun expiryClose(): ExpiryClose

        interface ExpiryClose {
            /** How often the sweep closes the links whose end date has passed; read by its `@Scheduled`. A write into
             *  the subtree between an end date and the next run still lands under the old key — the interval is that
             *  exposure (the decision's stated residual), so it is short by default. */
            @WithDefault("1m")
            fun every(): Duration
        }

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
