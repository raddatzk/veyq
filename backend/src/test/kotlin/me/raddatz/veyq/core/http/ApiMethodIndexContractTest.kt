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

    private companion object {
        val contracts: Path = Paths.get("").toAbsolutePath().resolve("../contracts/api").normalize()
        val VERBS = setOf("get", "put", "post", "delete", "patch", "options", "head", "trace")

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
