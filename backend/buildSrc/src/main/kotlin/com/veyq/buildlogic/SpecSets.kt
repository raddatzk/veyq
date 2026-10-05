// Not `com.veyq.build`: the repository's `.gitignore` drops every `**/build/` directory, a source package included.
package com.veyq.buildlogic

import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.core.models.ParseOptions
import org.gradle.api.GradleException
import java.io.File

// Spec-first generation (ADR 0079): core's specs (`contracts/api/*.openapi.yaml`) generate in `:core` into
// `com.veyq.core.generated.{api,model}`, the Memories application's (`contracts/api/memories/*.openapi.yaml`) in the
// project that holds Memories into `com.veyq.memories.generated.{api,model}`. Specs $ref shared components from
// `_common.openapi.yaml` (ADR 0038), which is core's: openapi-generator resolves the external ref in place, so no
// bundling or committed artifact is needed. Core generates every `_common` schema a core spec names; the Memories set
// maps every `_common` schema to core's class (`schemaMappings`), so a shared schema has ONE generated type, core's —
// and a Memories model that still shares a core model's name fails the build (`OpenApiGenerate`).
// Slice Resources implement the generated `*Api` interfaces (one tag per API resource — ADR 0001).

/** One owner's specs and where they generate to. */
class SpecSet(val owner: String, val specs: List<File>, val packageRoot: String, val mapsCommon: Boolean) {
    val apiPackage get() = "$packageRoot.api"
    val modelPackage get() = "$packageRoot.model"
}

/** Every owner's spec set, read from `contracts/api` (`contractsDir`). */
class SpecSets(val contractsDir: File) {
    val commonSpec: File = contractsDir.resolve(COMMON_SPEC)

    // `_common.openapi.yaml` is a shared-component $ref target (ADR 0038), not a spec — never fed to the generator on its own.
    private val coreSpecs = specsIn(contractsDir).filter { it.name != COMMON_SPEC }
    private val memoriesSpecs = specsIn(contractsDir.resolve("memories"))

    val all = listOf(
        SpecSet(coreOwner, coreSpecs, "com.veyq.core.generated", mapsCommon = false),
        SpecSet("memories", memoriesSpecs, "com.veyq.memories.generated", mapsCommon = true),
    )

    fun of(owner: String): SpecSet = all.singleOrNull { it.owner == owner }
        ?: throw GradleException("no spec set is owned by `$owner` (buildSrc SpecSets knows ${all.map { it.owner }})")

    /**
     * A spec in no set would silently generate nothing (its routes would 404 with no error anywhere): refused while
     * the build is configured. An EMPTY set is refused only when it would generate (`OpenApiGenerate`): the
     * Dockerfile's warm-up (`gradlew help` in the `backend-build-base` stage) configures the build before `contracts/`
     * is copied in, and a configuration-time refusal broke that warm-up — so the image did not build — with no
     * `./gradlew check` red.
     */
    fun requireNoStray() {
        val placed = all.flatMap { it.specs }.map { it.canonicalFile }.toSet() + commonSpec.canonicalFile
        val stray = contractsDir.walkTopDown().filter { it.isFile && it.name.endsWith(SPEC_SUFFIX) }
            .filter { it.canonicalFile !in placed }.toList()
        if (stray.isNotEmpty()) throw GradleException("specs owned by no generation set (buildSrc SpecSets): ${stray.sorted()}")
    }

    companion object {
        const val coreOwner = "core"
        const val COMMON_SPEC = "_common.openapi.yaml"
        private const val SPEC_SUFFIX = ".openapi.yaml"

        private fun specsIn(dir: File): List<File> =
            dir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(SPEC_SUFFIX) }.sorted()

        private fun parse(spec: File) = OpenAPIV3Parser()
            .readLocation(spec.absolutePath, null, ParseOptions().apply { isResolve = false }).openAPI

        /** Every schema `_common` defines — read from the file, never listed, so a schema moved there is mapped the day it lands. */
        fun commonSchemaNames(commonSpec: File): List<String> {
            val api = parse(commonSpec) ?: throw GradleException("${commonSpec.name}: not parseable as OpenAPI")
            return api.components?.schemas?.keys?.sorted().orEmpty()
                .ifEmpty { throw GradleException("${commonSpec.name} defines no schema") }
        }

        /** The `x-veyq-channel` markers of an owner's specs: the operationIds marked `admin`, and what is malformed. */
        fun channelMarkers(owner: String, specs: List<File>, contractsDir: File): Pair<Set<String>, List<String>> {
            val marker = "x-veyq-channel"
            val marked = sortedSetOf<String>()
            val problems = mutableListOf<String>()
            specs.forEach { spec ->
                val name = spec.relativeTo(contractsDir).invariantSeparatorsPath
                val api = parse(spec) ?: throw GradleException("$name: not parseable as OpenAPI")
                api.paths.orEmpty().forEach { (path, item) ->
                    if (item.extensions?.containsKey(marker) == true) problems += "$name $path: $marker on the path item marks nothing — put it on the operation"
                    item.readOperationsMap().forEach { (method, op) ->
                        val value = op.extensions?.get(marker) ?: return@forEach
                        val id = op.operationId ?: "$method $path"
                        if (owner != coreOwner) problems += "$name $id: $marker on an application's operation — the admin channel is core's, its gate reads core's set only"
                        else if (value != "admin") problems += "$name $id: $marker must be `admin`, was `$value`"
                        else if (op.operationId == null) problems += "$name $method $path: a marked operation needs an operationId"
                        else marked += id
                    }
                }
            }
            return marked to problems
        }
    }
}
