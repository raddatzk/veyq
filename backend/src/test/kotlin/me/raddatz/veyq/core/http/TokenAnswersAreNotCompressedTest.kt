package me.raddatz.veyq.core.http

import me.raddatz.veyq.core.adminchannel.givenAdminChannel
import me.raddatz.veyq.core.testing.MainOutputs
import me.raddatz.veyq.core.testing.TestFixtures.CONTENT_KEY
import me.raddatz.veyq.core.testing.TestFixtures.bearer
import me.raddatz.veyq.core.testing.TestFixtures.seedMemberScope
import me.raddatz.veyq.core.testing.TestFixtures.seedNode
import me.raddatz.veyq.memories.node.MemoriesNodeKinds
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.http.Uncompressed
import io.restassured.RestAssured.given
import io.restassured.config.DecoderConfig
import io.restassured.config.RestAssuredConfig
import io.restassured.response.Response
import io.restassured.specification.RequestSpecification
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import kotlin.io.path.extension
import kotlin.io.path.readText

/**
 * **An answer that carries a token is never compressed** (ADR 0103 decision 3, BREACH): the application compresses its
 * answers by media type (`quarkus.http.enable-compression`), and every operation whose success response holds a field
 * marked `x-veyq-visibility: token` is left out — `@Uncompressed` on its GENERATED interface method, which the build
 * writes from the same contracts (`buildSrc`'s `TokenAnswersUncompressed`: Quarkus REST reads the annotation from the
 * method that declares the endpoint, never from an implementing `override`). This test derives the set a second time, on
 * its own, from the YAML: every `contracts/api` spec walked, `$ref`s resolved across files, through properties, `items`
 * and the `allOf`/`oneOf`/`anyOf` spellings — so neither derivation can lose an operation the other keeps.
 *
 * Three checks, each with its control on the same predicate:
 *  1. the derivation finds the token answers (the login's `accessToken` among them — a walk that resolved nothing would
 *     pass every other check vacuously), and not a list of ciphertext;
 *  2. each derived operation's generated interface method carries `@Uncompressed`, and no other does;
 *  3. on the wire, each derived operation — called with `Accept-Encoding: gzip`, on the port its channel answers on — is
 *     answered without a `Content-Encoding`. A refusal its method answers itself (`refreshSession`'s `400`) is the case
 *     that reaches the annotation; one refused before the method (a body bound and validated first) is never compressed
 *     anyway. The control: a member's media list — a method's own JSON answer — and a static file ARE gzip-encoded, so
 *     a green here is not a server that compresses nothing.
 */
@QuarkusTest
class TokenAnswersAreNotCompressedTest {

    private data class Operation(val operationId: String, val verb: String, val path: String, val adminChannel: Boolean)

    @Test
    fun theDerivationFindsTheTokenAnswers() {
        val ids = tokenOperations.map { it.operationId }.toSet()
        assertTrue("verifyLogin" in ids, "the walk must reach the login's accessToken through its \$refs; found $ids")
        assertTrue("listNodeMedia" !in ids, "a list of ciphertext carries no token; found $ids")
    }

    @Test
    fun exactlyTheTokenAnswersAreUncompressedOnTheirGeneratedInterface() {
        val loader = Thread.currentThread().contextClassLoader
        val endpoints = MainOutputs.classNames()
            .filter { ".generated.api." in it }
            .mapNotNull { runCatching { Class.forName(it, false, loader) }.getOrNull() }
            .filter { it.isInterface }
            .flatMap { it.declaredMethods.toList() }
        val ids = tokenOperations.map { it.operationId }.toSet()
        assertTrue(endpoints.size > 100, "the generated interfaces were not found (${endpoints.size} methods)")
        assertTrue(endpoints.map { it.name }.toSet().containsAll(ids), "an operation has no generated method: ${ids - endpoints.map { it.name }.toSet()}")
        val marked = endpoints.filter { it.getAnnotation(Uncompressed::class.java) != null }.map { it.name }.toSet()
        assertEquals(ids, marked, "the @Uncompressed endpoints are the token answers, no fewer and no more")
    }

    @TestFactory
    fun noTokenAnswerIsCompressedOnTheWire(): List<DynamicTest> = tokenOperations.map { op ->
        DynamicTest.dynamicTest(op.operationId) {
            val answer = call(if (op.adminChannel) givenAdminChannel() else given(), op)
            assertTrue(answer.statusCode < 500, "${op.operationId} answered ${answer.statusCode}")
            assertTrue(answer.header("Content-Type").orEmpty().contains("json"), "${op.operationId}: a JSON answer, the compressible kind")
            assertEncoding(null, answer, op.operationId)
        }
    }

    @Test
    fun aMethodsOwnJsonAnswerAndAStaticFileAreCompressed() {
        val (userId, scopeId) = seedMemberScope()
        val memory = seedNode(scopeId, MemoriesNodeKinds.MEMORY, wrappedContentKey = CONTENT_KEY)
        val list = given().config(raw).header("Authorization", bearer(userId)).header("Accept-Encoding", "gzip")
            .`when`().get("/api/scopes/$scopeId/nodes/$memory/media")
        assertEquals(200, list.statusCode)
        assertEncoding("gzip", list, "listNodeMedia")

        val asset = given().config(raw).header("Accept-Encoding", "gzip").`when`().get("/compression-probe.js")
        assertEquals(200, asset.statusCode)
        assertEncoding("gzip", asset, "a static file")
    }

    private fun call(spec: RequestSpecification, op: Operation): Response {
        val path = op.path.replace(Regex("\\{(\\w+)}")) { m -> if (m.groupValues[1] == "shareId") "A".repeat(43) else UUID.randomUUID().toString() }
        val request = spec.config(raw).header("Accept-Encoding", "gzip")
        return when (op.verb) {
            "get" -> request.`when`().get(path)
            "delete" -> request.`when`().delete(path)
            else -> request.contentType("application/json").body("{}").`when`().request(op.verb.uppercase(), path)
        }
    }

    private fun assertEncoding(expected: String?, answer: Response, what: String) {
        val encoding = answer.header("Content-Encoding")?.takeIf { it != "identity" }
        assertEquals(expected, encoding, "$what: Content-Encoding")
    }

    private companion object {
        /** RestAssured unzips by itself; the header is the reading, so it must see the body as sent. */
        val raw: RestAssuredConfig = RestAssuredConfig.config().decoderConfig(DecoderConfig.decoderConfig().noContentDecoders())

        val contracts: Path = Paths.get("").toAbsolutePath().resolve("../contracts/api").normalize()

        val specs: Map<Path, Map<String, Any?>> by lazy {
            Files.walk(contracts).use { s -> s.filter { it.extension == "yaml" }.toList() }
                .associate { it.normalize() to Yaml().load<Map<String, Any?>>(it.readText()) }
        }

        val tokenOperations: List<Operation> by lazy {
            specs.flatMap { (file, spec) ->
                @Suppress("UNCHECKED_CAST")
                val paths = spec["paths"] as? Map<String, Map<String, Any?>> ?: emptyMap()
                paths.flatMap { (path, verbs) ->
                    verbs.filterKeys { it in setOf("get", "post", "put", "patch", "delete") }.mapNotNull { (verb, raw) ->
                        @Suppress("UNCHECKED_CAST")
                        val operation = raw as Map<String, Any?>
                        @Suppress("UNCHECKED_CAST")
                        val responses = operation["responses"] as? Map<Any?, Any?> ?: emptyMap()
                        val carries = responses.filterKeys { it.toString().startsWith("2") }.values.any { r ->
                            val (rf, resolved) = resolve(file, r)
                            @Suppress("UNCHECKED_CAST")
                            val content = (resolved as? Map<String, Any?>)?.get("content") as? Map<String, Map<String, Any?>>
                            content.orEmpty().values.any { carriesToken(rf, it["schema"], mutableSetOf()) }
                        }
                        if (!carries) null
                        else Operation(operation["operationId"] as String, verb, path, operation["x-veyq-channel"] == "admin")
                    }
                }
            }.sortedBy { it.operationId }
        }

        /** Follow a `$ref` (local or into another spec) to its target: the file it lives in and the node. */
        fun resolve(file: Path, node: Any?): Pair<Path, Any?> {
            @Suppress("UNCHECKED_CAST")
            val ref = (node as? Map<String, Any?>)?.get("\$ref") as? String ?: return file to node
            val (target, pointer) = ref.split("#", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            val targetFile = if (target.isEmpty()) file else file.parent.resolve(target).normalize()
            var at: Any? = specs.getValue(targetFile)
            for (part in pointer.split("/").filter { it.isNotEmpty() }) {
                @Suppress("UNCHECKED_CAST")
                at = (at as Map<String, Any?>)[part]
            }
            return resolve(targetFile, at)
        }

        fun carriesToken(file: Path, schema: Any?, seen: MutableSet<Pair<Path, Any?>>): Boolean {
            val (f, s) = resolve(file, schema)
            @Suppress("UNCHECKED_CAST")
            val node = s as? Map<String, Any?> ?: return false
            if (!seen.add(f to System.identityHashCode(node))) return false
            if (node["x-veyq-visibility"] == "token") return true
            @Suppress("UNCHECKED_CAST")
            val properties = node["properties"] as? Map<String, Any?> ?: emptyMap()
            @Suppress("UNCHECKED_CAST")
            val variants = listOf("allOf", "oneOf", "anyOf").flatMap { (node[it] as? List<Any?>).orEmpty() }
            return (properties.values + listOfNotNull(node["items"]) + variants).any { carriesToken(f, it, seen) }
        }
    }
}
