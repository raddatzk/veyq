package me.raddatz.veyq.core.http

import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import io.quarkus.security.Authenticated
import jakarta.annotation.security.DenyAll
import jakarta.annotation.security.PermitAll
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.inject.spi.BeanManager
import jakarta.ws.rs.HttpMethod
import jakarta.ws.rs.Path
import java.lang.reflect.Method

/**
 * **Which methods each API path declares** (inbox #494): the per-path method index Quarkus REST builds for its own
 * routing and exposes nowhere. Read once at startup from the deployed JAX-RS resources — every resource implements
 * its generated `…Api` interface, so the paths and methods read here are the contract's, each application's included
 * without core naming one — and asked by [UndeclaredMethodFilter] for the path a request names.
 *
 * A path is its segments, a template segment (`{scopeId}`) matching any one segment. Two declarations of the same
 * shape are one path whatever their parameters are called. When several paths match a request, the most literal one
 * answers — a literal segment beats a template at the first place they differ — which is OpenAPI's rule ("concrete
 * paths before their templated counterparts") and the one the contract relies on: `/api/scopes/invitations` is not a
 * `/api/scopes/{scopeId}` with a malformed id. `ApiMethodIndexContractTest` holds the index to the contract, path by
 * path and method by method.
 */
class ApiMethodIndex private constructor(private val paths: List<DeclaredPath>) {

    /**
     * One path's shape — a literal segment, or `null` for a template — and its methods, each with its operationId.
     * [publicMethods] are those whose operation takes no authentication: `@PermitAll` on the resource's method, or on its
     * class with no `@Authenticated`/`@RolesAllowed`/`@DenyAll` on the method (Quarkus' own precedence). They are the
     * only methods a cross-origin caller may be offered (ADR 0121 decision 3, `ShareCrossOrigin`).
     */
    class DeclaredPath(val shape: List<String?>, val methods: Map<String, String>, val publicMethods: Set<String> = emptySet())

    /** The declared path [path] names, or `null` when it names none. [path] is the normalised path routing matches. */
    fun match(path: String): DeclaredPath? {
        val segments = segmentsOf(path)
        return paths.asSequence()
            .filter { it.shape.size == segments.size && it.shape.zip(segments).all { (s, v) -> s == null || s == v } }
            .minWithOrNull(MOST_LITERAL_FIRST)
    }

    /** Every declared path, as `"/a/{}/b" to methods` — for the test that holds the index to the contract. */
    fun shapes(): Map<String, Set<String>> =
        paths.associate { p -> "/" + p.shape.joinToString("/") { it ?: "{}" } to p.methods.keys }

    companion object {
        /** A template segment and nothing else: `{name}`. A regex template or a mixed segment fails the build of the index. */
        private val TEMPLATE = Regex("""\{[A-Za-z_][\w.-]*}""")

        private val MOST_LITERAL_FIRST = Comparator<DeclaredPath> { a, b ->
            a.shape.zip(b.shape).firstOrNull { (x, y) -> (x == null) != (y == null) }?.let { (x, _) -> if (x == null) 1 else -1 } ?: 0
        }

        /** The segments of a path: no empty ones, so a trailing or doubled separator is the path routing also answers. */
        fun segmentsOf(path: String): List<String> = path.split('/').filter { it.isNotEmpty() }

        /** The index of [resources] — JAX-RS resource classes, whose `@Path` sits on the class or on an interface it implements. */
        fun of(resources: Collection<Class<*>>): ApiMethodIndex {
            val byShape = linkedMapOf<List<String?>, MutableMap<String, String>>()
            val publicByShape = mutableMapOf<List<String?>, MutableSet<String>>()
            for (cls in resources) {
                for ((template, verb, operationId, public) in endpoints(cls)) {
                    val shape = segmentsOf(template).map { segment ->
                        when {
                            TEMPLATE.matches(segment) -> null
                            '{' in segment || '}' in segment -> error("${cls.name}: the template segment `$segment` of $template is not a plain `{name}`")
                            else -> segment
                        }
                    }
                    val methods = byShape.getOrPut(shape) { sortedMapOf() }
                    val previous = methods.put(verb, operationId)
                    check(previous == null || previous == operationId) { "$verb $template is declared twice: $previous and $operationId" }
                    if (public) publicByShape.getOrPut(shape) { sortedSetOf() } += verb
                }
            }
            return ApiMethodIndex(
                byShape.map { (shape, methods) -> DeclaredPath(shape, methods.toMap(), publicByShape[shape].orEmpty().toSet()) },
            )
        }

        fun isResource(cls: Class<*>): Boolean =
            !cls.isInterface && !cls.isAnnotation && holders(cls).any { it.isAnnotationPresent(Path::class.java) }

        private data class Endpoint(val template: String, val verb: String, val operationId: String, val public: Boolean)

        /** The class and every interface above it: a generated `…Api` carries the annotations, not the implementation. */
        private fun holders(cls: Class<*>): List<Class<*>> {
            val seen = linkedSetOf<Class<*>>()
            fun visit(c: Class<*>) {
                if (!seen.add(c)) return
                c.interfaces.forEach(::visit)
                c.superclass?.takeIf { it != Any::class.java }?.let(::visit)
            }
            visit(cls)
            return seen.toList()
        }

        private fun endpoints(cls: Class<*>): List<Endpoint> {
            val holders = holders(cls)
            val root = holders.firstNotNullOfOrNull { it.getAnnotation(Path::class.java)?.value }.orEmpty()
            return holders.flatMap { holder ->
                holder.declaredMethods.filter { !it.isBridge && !it.isSynthetic }.mapNotNull { m ->
                    val verb = m.annotations.firstNotNullOfOrNull { it.annotationClass.java.getAnnotation(HttpMethod::class.java)?.value }
                        ?: return@mapNotNull null
                    Endpoint("$root/${m.getAnnotation(Path::class.java)?.value.orEmpty()}", verb, m.name, isPublic(cls, m))
                }
            }.distinct()
        }

        /** Whether [declared]'s operation takes no authentication on [cls]: the annotations sit on the implementing method
         *  (the generated interface carries none), and a method's own beats its class's. */
        private fun isPublic(cls: Class<*>, declared: Method): Boolean {
            val impl = runCatching { cls.getMethod(declared.name, *declared.parameterTypes) }.getOrNull() ?: return false
            if (impl.isAnnotationPresent(PermitAll::class.java)) return true
            val guarded = listOf(Authenticated::class.java, RolesAllowed::class.java, DenyAll::class.java)
            return guarded.none { impl.isAnnotationPresent(it) } && cls.isAnnotationPresent(PermitAll::class.java)
        }
    }
}

/**
 * The index of what this application deploys: every CDI bean that is a JAX-RS resource (Quarkus REST makes each one a
 * bean — `RateLimitPostureSweepTest` holds that the deployed set is the compiled one). Built at startup, so a path the
 * index cannot read fails the boot instead of a request.
 */
@ApplicationScoped
class DeclaredMethods(private val beans: BeanManager) {

    @Volatile
    private var built: ApiMethodIndex? = null

    fun build(@Observes event: StartupEvent) {
        index()
    }

    fun index(): ApiMethodIndex = built ?: synchronized(this) {
        built ?: ApiMethodIndex.of(
            beans.getBeans(Any::class.java, jakarta.enterprise.inject.Any.Literal.INSTANCE).map { it.beanClass }.filter(ApiMethodIndex::isResource).toSet(),
        ).also { built = it }
    }
}
