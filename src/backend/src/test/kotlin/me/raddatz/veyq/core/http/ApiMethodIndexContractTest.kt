package me.raddatz.veyq.core.http

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.readText

/**
 * The method index [UndeclaredMethodFilter] answers from (inbox #494) is the contract's, read a second time on its own:
 * every `contracts/api` spec walked — core's and each application's, which is why this runs on the whole application —
 * and each path's methods compared with what the index read off the deployed JAX-RS resources. A path the index lacks
 * would answer every method `404`, a method it lacks `405` on a call the contract allows, and an extra one an `Allow`
 * the contract does not have — the fuzz run's `allow_header_conformance`.
 */
@QuarkusTest
class ApiMethodIndexContractTest {

    @Inject
    lateinit var declared: DeclaredMethods

    @Test
    fun theIndexDeclaresExactlyTheContractsMethodsOnEveryApiPath() {
        val contract = contractShapes()
        assertTrue(contract.size > 100, "the walk must reach every spec; found ${contract.size} paths")
        val index = declared.index().shapes().filterKeys { it == "/api" || it.startsWith("/api/") }
        assertEquals(contract.toSortedMap(), index.toSortedMap())
    }

    /**
     * The methods a cross-origin caller may be offered under `/api/shares/{shareId}` (ADR 0121 decision 3,
     * `ShareCrossOrigin`) are the index's public ones, read off `@PermitAll`; here they are held to the contract's own
     * word — an operation that declares no `bearerAuth` — path by path, so a guest operation that took a bearer, or a
     * curator's that lost it, is red here before it is offered to another origin. And every header such an operation
     * reads is one a preflight allows ([CrossOrigin.ALLOW_HEADERS]): a header missing there is a guest call no browser
     * can make cross-origin.
     */
    @Test
    fun theGuestShareOperationsOfferedCrossOriginAreTheContractsUnauthenticatedOnes() {
        val guest = guestShareOperations()
        assertTrue(guest.size > 10, "the walk must reach every spec's share paths; found ${guest.size}")
        val contract = guest.groupBy({ it.shape }, { it.verb }).mapValues { (_, v) -> v.toSortedSet() }
        val index = declared.index().shapes().keys.filter { it.startsWith("/api/shares/{}") }
            .associateWith { shape -> declared.index().match(shape.replace("{}", "x"))!!.publicMethods.toSortedSet() }
            .filterValues { it.isNotEmpty() }
        assertEquals(contract.toSortedMap(), index.toSortedMap())
        val headers = guest.flatMap { it.headers }.toSortedSet(String.CASE_INSENSITIVE_ORDER)
        val allowed = CrossOrigin.ALLOW_HEADERS.toSortedSet(String.CASE_INSENSITIVE_ORDER)
        assertTrue(allowed.containsAll(headers), "a guest operation reads ${headers - allowed} which no preflight allows")
    }

    private companion object {
        val contracts: Path = Paths.get("").toAbsolutePath().resolve("../../contracts/api").normalize()
        val VERBS = setOf("get", "put", "post", "delete", "patch", "options", "head", "trace")

        data class GuestOperation(val shape: String, val verb: String, val headers: List<String>)

        /** Every operation under `/api/shares/{shareId}` that declares no `bearerAuth`, with the header parameters it reads
         *  (a `$ref` resolved in its own file or the one it names). */
        @Suppress("UNCHECKED_CAST")
        fun guestShareOperations(): List<GuestOperation> {
            val files = Files.walk(contracts).use { s -> s.filter { it.extension == "yaml" }.toList() }
            val docs = files.associateWith { Yaml().load<Map<String, Any?>>(it.readText()) }
            fun resolve(file: Path, param: Map<String, Any?>): Map<String, Any?> {
                val ref = param["\$ref"] as? String ?: return param
                val target = if (ref.startsWith("#")) file else file.parent.resolve(ref.substringBefore('#')).normalize()
                var node: Any? = docs.getValue(target)
                ref.substringAfter("#/").split('/').forEach { node = (node as Map<String, Any?>)[it] }
                return node as Map<String, Any?>
            }
            val out = mutableListOf<GuestOperation>()
            for ((file, doc) in docs) {
                val global = doc["security"] as? List<Any?>
                val paths = doc["paths"] as? Map<String, Map<String, Any?>> ?: continue
                for ((path, item) in paths) {
                    if (!path.startsWith("/api/shares/{shareId}")) continue
                    for ((verb, op) in item.filterKeys { it in VERBS }) {
                        op as Map<String, Any?>
                        val security = (op["security"] ?: global) as? List<Map<String, Any?>> ?: emptyList()
                        if (security.any { "bearerAuth" in it }) continue
                        val headers = (op["parameters"] as? List<Map<String, Any?>> ?: emptyList()).map { resolve(file, it) }
                            .filter { it["in"] == "header" }.map { it["name"] as String }
                        val hasBody = op["requestBody"] != null
                        out += GuestOperation(path.replace(Regex("\\{[^}]+}"), "{}"), verb.uppercase(), headers + listOfNotNull("Content-Type".takeIf { hasBody }))
                    }
                }
            }
            return out
        }

        fun contractShapes(): Map<String, Set<String>> {
            val shapes = mutableMapOf<String, MutableSet<String>>()
            val files = Files.walk(contracts).use { s -> s.filter { it.extension == "yaml" }.toList() }
            for (file in files) {
                @Suppress("UNCHECKED_CAST")
                val paths = Yaml().load<Map<String, Any?>>(file.readText())["paths"] as? Map<String, Map<String, Any?>> ?: continue
                for ((path, item) in paths) {
                    val shape = path.replace(Regex("\\{[^}]+}"), "{}")
                    shapes.getOrPut(shape) { sortedSetOf() } += item.keys.filter { it in VERBS }.map { it.uppercase() }
                }
            }
            return shapes
        }
    }
}
