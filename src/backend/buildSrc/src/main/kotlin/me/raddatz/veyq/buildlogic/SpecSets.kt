// Not `me.raddatz.veyq.build`: the repository's `.gitignore` drops every `**/build/` directory, a source package included.
package me.raddatz.veyq.buildlogic

import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.core.models.ParseOptions
import org.gradle.api.GradleException
import java.io.File

// Spec-first generation (ADR 0079). A spec's owner is where it sits, never a list: core's specs are the top level
// (`contracts/api/*.openapi.yaml`) and generate in `:core` into `me.raddatz.veyq.core.generated.{api,model}`; an
// application's sit in a directory named for it (`contracts/api/<application>/*.openapi.yaml`) and generate in its module
// `:apps:<application>` into `me.raddatz.veyq.<application>.generated.{api,model}` (`packageRootOf`). Specs $ref shared components from
// `_common.openapi.yaml` (ADR 0038), which is core's: openapi-generator resolves the external ref in place, so no
// bundling or committed artifact is needed. Core generates every `_common` schema a core spec names; an application's
// set maps every `_common` schema to core's class (`schemaMappings`), so a shared schema has ONE generated type, core's —
// and an application model that still shares a core model's name fails the build (`OpenApiGenerate`).
// Slice Resources implement the generated `*Api` interfaces (one tag per API resource — ADR 0001).

/** One owner's specs and where they generate to. */
class SpecSet(val owner: String, val specs: List<File>, val packageRoot: String, val mapsCommon: Boolean) {
    val apiPackage get() = "$packageRoot.api"
    val modelPackage get() = "$packageRoot.model"
}

/**
 * Every owner's spec set, derived from `contracts/api` (`contractsDir`) and the application modules the settings
 * include (`applicationModules`: the `<application>` of every `:apps:<application>`).
 */
class SpecSets(val contractsDir: File, private val applicationModules: Set<String>) {
    val commonSpec: File = contractsDir.resolve(COMMON_SPEC)

    /** The application directories: every directory directly below `contracts/api` that holds a spec. */
    val applications: List<String> =
        contractsDir.listFiles().orEmpty().filter { it.isDirectory && specsIn(it).isNotEmpty() }.map { it.name }.sorted()

    /**
     * The set `owner` generates. An application's is its directory's specs, possibly none: an EMPTY set is refused
     * only when it would generate (`OpenApiGenerate`) — the Dockerfile's warm-up (`gradlew help` in the
     * `backend-build-base` stage) configures the build before `contracts/` is copied in, and a configuration-time
     * refusal broke that warm-up — so the image did not build — with no `./gradlew check` red.
     */
    fun of(owner: String): SpecSet = when (owner) {
        // `_common.openapi.yaml` is a shared-component $ref target (ADR 0038), not a spec — never fed to the generator on its own.
        coreOwner -> SpecSet(coreOwner, specsIn(contractsDir).filter { it.name != COMMON_SPEC }, packageRootOf(coreOwner), mapsCommon = false)
        else -> {
            applicationNameProblem(owner)?.let { throw GradleException("no spec set is owned by `$owner`: $it") }
            SpecSet(owner, specsIn(contractsDir.resolve(owner)), packageRootOf(owner), mapsCommon = true)
        }
    }

    /**
     * A spec in no set would silently generate nothing (its routes would 404 with no error anywhere): refused while
     * the build is configured. A spec belongs to a set only where its place says so — the top level (core) or a
     * directory directly below it named like an application whose module `:apps:<dir>` the settings include. A spec
     * nested deeper, in a directory no name can own, or in one with no module is stray.
     */
    fun requireNoStray() {
        val problems = contractsDir.walkTopDown().filter { it.isFile && it.name.endsWith(SPEC_SUFFIX) }.sorted().mapNotNull { spec ->
            val path = spec.relativeTo(contractsDir).invariantSeparatorsPath
            val dirs = path.split('/').dropLast(1)
            when {
                dirs.isEmpty() -> null
                dirs.size > 1 -> "$path: nested deeper than one directory — a spec is core's (the top level) or an application's (`<application>/`)"
                else -> applicationNameProblem(dirs[0])?.let { "$path: `${dirs[0]}/` $it" }
                    ?: if (dirs[0] !in applicationModules) {
                        "$path: `${dirs[0]}/` is no application — the settings include no `:apps:${dirs[0]}`, so its specs would generate nothing"
                    } else {
                        null
                    }
            }
        }.toList()
        if (problems.isNotEmpty()) throw GradleException("specs owned by no generation set (buildSrc SpecSets):\n" + problems.joinToString("\n"))
    }

    companion object {
        const val coreOwner = "core"
        const val COMMON_SPEC = "_common.openapi.yaml"
        private const val SPEC_SUFFIX = ".openapi.yaml"
        private val APPLICATION_PATH = Regex("^:apps:([^:]+)$")
        private val APPLICATION_NAME = Regex("^[a-z][a-z0-9]*$")

        /** The generated package root of `owner` — the one place it is spelled (validate_backend_module_graph.py rule (d) reads it). */
        fun packageRootOf(owner: String) = "me.raddatz.veyq.$owner.generated"

        /** The owner a Gradle project generates: `:core` → core, `:apps:<application>` → `<application>`; any other project generates none. */
        fun ownerOf(projectPath: String): String {
            if (projectPath == ":$coreOwner") return coreOwner
            val application = APPLICATION_PATH.matchEntire(projectPath)?.groupValues?.get(1)
                ?: throw GradleException("$projectPath generates no spec set: only `:$coreOwner` and an application `:apps:<application>` own specs")
            applicationNameProblem(application)?.let { throw GradleException("$projectPath generates no spec set: `$application` $it") }
            return application
        }

        /** The application names the settings include: the `<application>` of every `:apps:<application>` project path. */
        fun applicationModules(projectPaths: Iterable<String>): Set<String> =
            projectPaths.mapNotNull { APPLICATION_PATH.matchEntire(it)?.groupValues?.get(1) }.toSet()

        /** Why `name` cannot own an application's specs (it becomes a package segment and a task name), or null. */
        private fun applicationNameProblem(name: String): String? = when {
            name == coreOwner -> "is core's name — core's specs are the top level"
            !APPLICATION_NAME.matches(name) -> "is no application name (lowercase letters and digits, a letter first: it becomes a package segment)"
            else -> null
        }

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

        /**
         * Every component-schema property spelled `oneOf`/`anyOf` [a schema, `null`] that carries an
         * `x-field-extra-annotation`, as (schema, property, annotation) — the extensions the `kotlin` generator drops
         * (see [NullableObjectCascade]).
         */
        fun nullableObjectAnnotations(specs: List<File>): List<Triple<String, String, String>> = specs.flatMap { spec ->
            val api = parse(spec) ?: throw GradleException("${spec.name}: not parseable as OpenAPI")
            api.components?.schemas.orEmpty().flatMap { (schemaName, schema) ->
                schema.properties.orEmpty().mapNotNull { (propName, prop) ->
                    val branches = prop.oneOf ?: prop.anyOf ?: return@mapNotNull null
                    val annotation = prop.extensions?.get("x-field-extra-annotation") as? String ?: return@mapNotNull null
                    if (branches.none { it.types?.contains("null") == true || it.type == "null" }) return@mapNotNull null
                    Triple(schemaName, propName, annotation)
                }
            }
        }

        /**
         * Every OPTIONAL component-schema property whose schema does not allow `null` — its type, a `oneOf`/`anyOf`
         * branch, or the scalar component it `$ref`s — as (schema, property): the properties a JSON `null` may not
         * bind to (see [NullRefused]). A required one needs nothing: its Kotlin type is non-null.
         */
        fun nonNullableOptionals(specs: List<File>, commonSpec: File): List<Pair<String, String>> {
            val common = parse(commonSpec)?.components?.schemas.orEmpty()
            return specs.flatMap { spec ->
                val api = parse(spec) ?: throw GradleException("${spec.name}: not parseable as OpenAPI")
                val own = api.components?.schemas.orEmpty()
                own.flatMap { (schemaName, schema) ->
                    val required = schema.required.orEmpty().toSet()
                    schema.properties.orEmpty().mapNotNull { (propName, prop) ->
                        if (propName in required || allowsNull(prop, own, common)) null else schemaName to propName
                    }
                }
            }
        }

        private fun allowsNull(
            schema: io.swagger.v3.oas.models.media.Schema<*>,
            own: Map<String, io.swagger.v3.oas.models.media.Schema<*>>,
            common: Map<String, io.swagger.v3.oas.models.media.Schema<*>>,
            depth: Int = 0,
        ): Boolean {
            if (schema.types?.contains("null") == true || schema.type == "null" || schema.nullable == true) return true
            if ((schema.oneOf.orEmpty() + schema.anyOf.orEmpty()).any { allowsNull(it, own, common, depth + 1) }) return true
            val ref = schema.`$ref` ?: return false
            if (depth > 8) throw GradleException("$ref: a \$ref chain deeper than 8")
            val name = ref.substringAfterLast('/')
            val target = (if (ref.startsWith("#")) own[name] else common[name]) ?: common[name] ?: return false
            return allowsNull(target, own, common, depth + 1)
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
