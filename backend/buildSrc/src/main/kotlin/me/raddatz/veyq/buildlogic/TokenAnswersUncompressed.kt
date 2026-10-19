package me.raddatz.veyq.buildlogic

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.core.models.ParseOptions
import org.gradle.api.GradleException
import java.io.File

/**
 * **An answer that carries a token is never compressed** (ADR 0103 decision 3, BREACH): every operation whose success
 * response holds a field marked `x-veyq-visibility: token` gets `@io.quarkus.vertx.http.Uncompressed` on its GENERATED
 * JAX-RS interface method. The set is derived here, from the specs the generator just read, so a token added to a
 * response later is left uncompressed by the build that generates it — nobody has to remember it.
 *
 * Why on the interface and not on the resource: Quarkus REST reads `@Compressed`/`@Uncompressed` from the method that
 * declares the endpoint (its `CompressionScanner` asks the annotation store of that method), and a veyq endpoint is
 * declared by the generated interface; the same annotation on the implementing `override` is not read — measured: a
 * refusal `refreshSession` answered with its own `Response` went out gzip-encoded with `@Uncompressed` on `TokenResource`.
 *
 * Like [ModelCascade] a pass over the generator's OUTPUT that fails the build when a method it must mark is not where
 * the generator writes one. `TokenAnswersAreNotCompressedTest` holds the result on the wire.
 */
internal object TokenAnswersUncompressed {
    const val ANNOTATION = "@io.quarkus.vertx.http.Uncompressed"
    private const val TOKEN = "token"
    private const val VISIBILITY = "x-veyq-visibility"

    /** Marks every operation of [specs] that answers a token in the interfaces under [apiDir]; returns the ids marked. */
    fun apply(apiDir: File, specs: List<File>): Set<String> {
        val wanted = specs.flatMap(::tokenOperations).toSortedSet()
        val sources = apiDir.walkTopDown().filter { it.isFile && it.name.endsWith("Api.java") }.toList()
        for (id in wanted) {
            val declaration = Regex("""^\s*Response\s+$id\(""")
            val file = sources.firstOrNull { f -> f.readLines().any { declaration.containsMatchIn(it) } }
                ?: throw GradleException("no generated `Response $id(` to mark @Uncompressed (TokenAnswersUncompressed.kt)")
            val lines = file.readText().split("\n").toMutableList()
            val at = lines.indexOfFirst { declaration.containsMatchIn(it) }
            var top = at
            while (top > 0 && lines[top - 1].trimStart().startsWith("@")) top--
            if ((top until at).none { lines[it].trim() == ANNOTATION }) {
                lines.add(top, lines[at].takeWhile { it == ' ' } + ANNOTATION)
                file.writeText(lines.joinToString("\n"))
            }
        }
        return wanted
    }

    /** The operationIds of [spec] whose 2xx answer holds a `token` field anywhere — `$ref`s followed across files. */
    fun tokenOperations(spec: File): List<String> {
        val api: OpenAPI = OpenAPIV3Parser().readLocation(spec.absolutePath, null, ParseOptions().apply { isResolve = true }).openAPI
            ?: throw GradleException("${spec.name}: not parseable as OpenAPI")
        val schemas = api.components?.schemas.orEmpty()
        val responses = api.components?.responses.orEmpty()
        return api.paths.orEmpty().values.flatMap { item ->
            item.readOperations().mapNotNull { op ->
                val carries = op.responses.orEmpty().filterKeys { it.startsWith("2") }.values.any { r ->
                    val response = r.`$ref`?.let { responses[it.substringAfterLast('/')] } ?: r
                    response.content.orEmpty().values.any { carries(it.schema, schemas, mutableSetOf()) }
                }
                if (carries) op.operationId ?: throw GradleException("${spec.name}: a token answer without an operationId") else null
            }
        }
    }

    private fun carries(schema: Schema<*>?, schemas: Map<String, Schema<*>>, seen: MutableSet<String>): Boolean {
        if (schema == null) return false
        schema.`$ref`?.let { ref ->
            val name = ref.substringAfterLast('/')
            if (!seen.add(name)) return false
            return carries(schemas[name] ?: throw GradleException("$ref: unresolved"), schemas, seen)
        }
        if (schema.extensions?.get(VISIBILITY) == TOKEN) return true
        val nested = schema.properties.orEmpty().values + listOfNotNull(schema.items) +
            schema.allOf.orEmpty() + schema.oneOf.orEmpty() + schema.anyOf.orEmpty()
        return nested.any { carries(it, schemas, seen) }
    }
}
