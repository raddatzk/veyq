package me.raddatz.veyq.core.http

import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.PATCH
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import java.io.File
import java.util.jar.JarFile

/**
 * Every operation of the contract, derived from the generated API interfaces of every owner (core and each
 * application, as `buildSrc` derives them from `contracts/api/`), never listed: the sweeps that must not miss an
 * operation (`OperatorBlindProbeTest`, `ApplicationSwitchSweepTest`) read them here, so a new one is red in each on the day
 * it is added.
 */
object GeneratedApiOperations {

    data class Operation(val id: String, val verb: String, val path: String) {
        private val pattern = Regex("^" + path.replace(Regex("\\{[^}]+}"), "[^/]+") + "$")
        fun matches(concrete: String) = pattern.matches(concrete)
    }

    /**
     * Every method of every interface in [apiPackages] carrying an HTTP verb — the method name is the operationId.
     * Found on the test classpath rather than listed, which is the point: a listed set is exactly what the hand-kept
     * probe rows were, and they missed `restoreNode` and `listTrashedNodes`.
     */
    fun contractOperations(): List<Operation> = of(apiPackages())

    /** The operations of [packages] — one owner's, or several. */
    fun of(packages: List<String>): List<Operation> {
        val names = packages.flatMap(::generatedApiClassNames)
        return names.flatMap { name ->
            val type = Class.forName(name, false, GeneratedApiOperations::class.java.classLoader)
            val base = type.getAnnotation(Path::class.java)?.value.orEmpty()
            type.declaredMethods.mapNotNull { m ->
                val verb = VERBS.entries.firstOrNull { m.isAnnotationPresent(it.value) }?.key ?: return@mapNotNull null
                Operation(m.name, verb, (base + (m.getAnnotation(Path::class.java)?.value ?: "")).replace("//", "/"))
            }
        }
    }

    /**
     * One generated API package per owner — never listed here (a hand list let a new application's operations go
     * unprobed until someone remembered it, inbox #368). The build hands over `buildSrc`'s own derivation from
     * `contracts/api/` (`SpecSets`: core plus every `<application>/`, root `build.gradle.kts`), and the classpath is read
     * against it both ways: a derived package that yields no interface (its module not composed, a package renamed)
     * is red, and so is a generated API package on the classpath the derivation does not name — its operations would
     * otherwise pass unclassified.
     */
    fun apiPackages(): List<String> {
        val derived = System.getProperty(PACKAGES_PROPERTY)?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        check(CORE_API_PACKAGE in derived) {
            "the build handed over no generated API packages with core's ($PACKAGES_PROPERTY=$derived) — run the test through Gradle (`./gradlew :test`), whose root build derives them"
        }
        val empty = derived.filter { generatedApiClassNames(it).isEmpty() }
        check(empty.isEmpty()) { "derived generated API packages with no interface on the classpath (is the module composed into the root?): $empty" }
        val onClasspath = classpathEntries().flatMap { entry ->
            when {
                entry.isDirectory -> File(entry, VEYQ_ROOT).listFiles().orEmpty()
                    .filter { File(it, "generated/api").listFiles().orEmpty().any { f -> f.name.endsWith(".class") } }
                    .map { it.name }
                entry.isFile && entry.name.endsWith(".jar") -> JarFile(entry).use { jar ->
                    jar.entries().asSequence().mapNotNull { GENERATED_API_CLASS.matchEntire(it.name)?.groupValues?.get(1) }.toList()
                }
                else -> emptyList()
            }
        }.map { "${VEYQ_ROOT.replace('/', '.')}.$it.generated.api" }.toSet()
        val uncovered = onClasspath - derived.toSet()
        check(uncovered.isEmpty()) { "generated API packages on the classpath the build's derivation does not name — their operations would go unprobed: ${uncovered.sorted()}" }
        return derived
    }

    private fun classpathEntries() = System.getProperty("java.class.path").split(File.pathSeparator).map(::File)

    private fun generatedApiClassNames(apiPackage: String): List<String> {
        val dir = apiPackage.replace('.', '/')
        return classpathEntries().flatMap { entry ->
            when {
                entry.isDirectory -> File(entry, dir).listFiles().orEmpty().map { it.name }
                entry.isFile && entry.name.endsWith(".jar") -> JarFile(entry).use { jar ->
                    jar.entries().asSequence().map { it.name }.filter { it.startsWith("$dir/") && it.count { c -> c == '/' } == dir.count { c -> c == '/' } + 1 }
                        .map { it.substringAfterLast('/') }.toList()
                }
                else -> emptyList()
            }
        }.filter { it.endsWith(".class") && '$' !in it }.map { "$apiPackage.${it.removeSuffix(".class")}" }.distinct()
    }

    /** Set by the root `build.gradle.kts` from `buildSrc`'s `SpecSets`. */
    private const val PACKAGES_PROPERTY = "operatorBlindProbe.apiPackages"
    private const val CORE_API_PACKAGE = "me.raddatz.veyq.core.generated.api"
    private const val VEYQ_ROOT = "me/raddatz/veyq"
    /** A class directly in some `me.raddatz.veyq.<owner>.generated.api` — the shape `SpecSets.packageRootOf` generates. */
    private val GENERATED_API_CLASS = Regex("^$VEYQ_ROOT/([^/]+)/generated/api/[^/]+\\.class$")
    private val VERBS = linkedMapOf(
        "GET" to GET::class.java, "POST" to POST::class.java, "PUT" to PUT::class.java,
        "PATCH" to PATCH::class.java, "DELETE" to DELETE::class.java,
    )
}
