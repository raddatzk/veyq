package me.raddatz.veyq.buildlogic

import com.fasterxml.jackson.databind.ObjectMapper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.openapitools.codegen.DefaultGenerator
import org.openapitools.codegen.config.CodegenConfigurator
import java.io.File

/**
 * Generates one owner's Kotlin models + JAX-RS interfaces (`openApiGenerateCore`, `openApiGenerateMemories`). Every
 * input is a task property, so the action reads no project state (configuration-cache safe).
 */
@CacheableTask
abstract class OpenApiGenerate : DefaultTask() {
    /** The owner's specs, generated in path order. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val specs: ConfigurableFileCollection

    /** `_common.openapi.yaml`: resolved in place by every spec's $ref, and the schemas an application maps to core's. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val commonSpec: RegularFileProperty

    @get:Input
    abstract val owner: Property<String>

    @get:Input
    abstract val packageRoot: Property<String>

    /** True for an application's set: every `_common` schema is mapped to core's generated class. */
    @get:Input
    abstract val mapsCommon: Property<Boolean>

    /**
     * Core's generated model package directory, for an application's set only (empty for core's): an application
     * model named like a core one fails the build.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val coreModels: ConfigurableFileCollection

    /** `contracts/api`, for naming a spec in an error message only. */
    @get:Internal
    abstract val contractsDir: DirectoryProperty

    @get:OutputDirectory
    abstract val apiDir: DirectoryProperty

    @get:OutputDirectory
    abstract val modelsDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val set = SpecSet(owner.get(), specs.files.sorted(), packageRoot.get(), mapsCommon.get())
        if (set.specs.isEmpty()) throw GradleException("the ${set.owner} spec set is empty — the glob lost its specs")
        listOf(apiDir, modelsDir).forEach { it.get().asFile.deleteRecursively() }
        val commonSchemas = if (set.mapsCommon) SpecSets.commonSchemaNames(commonSpec.get().asFile) else emptyList()
        set.specs.forEach { spec ->
            run(
                set, commonSchemas, "kotlin", spec.absolutePath, modelsDir.get().asFile,
                mapOf("models" to "", "supportingFiles" to "false"),
                mapOf(
                    "serializationLibrary" to "jackson",
                    "enumPropertyNaming" to "original",
                    "collectionType" to "list",
                ),
            )
            run(
                set, commonSchemas, "jaxrs-spec", spec.absolutePath, apiDir.get().asFile,
                mapOf("apis" to "", "supportingFiles" to "false"),
                mapOf(
                    "interfaceOnly" to "true",
                    "useJakartaEe" to "true",
                    "useSwaggerAnnotations" to "false",
                    "useSwaggerV3Annotations" to "false",
                    "useMicroProfileOpenAPIAnnotations" to "false",
                    "openApiNullable" to "false",
                    "hideGenerationTimestamp" to "true",
                    "useTags" to "true",
                    "useBeanValidation" to "true",
                    "returnResponse" to "true",
                ),
                library = "quarkus",
            )
        }
        // A JSON `null` binds only where the contract allows one — see NullRefused.
        NullRefused.apply(
            modelsDir.get().asFile.resolve("src/main/kotlin"),
            SpecSets.nonNullableOptionals(set.specs + commonSpec.get().asFile, commonSpec.get().asFile),
        )
        // The cascade the generator drops on a nullable nested object comes back — see NullableObjectCascade.
        NullableObjectCascade.apply(
            modelsDir.get().asFile.resolve("src/main/kotlin"),
            SpecSets.nullableObjectAnnotations(set.specs + commonSpec.get().asFile),
        )
        // The cascade of a generated list moves onto its element type (HV000271) — see ModelCascade.
        ModelCascade.apply(modelsDir.get().asFile.resolve("src/main/kotlin"))
        // An answer that carries a token is never compressed (ADR 0103) — see TokenAnswersUncompressed.
        TokenAnswersUncompressed.apply(apiDir.get().asFile, set.specs)
        if (set.owner == SpecSets.coreOwner) return
        // Every schema has ONE generated type. An application model named like a core one is a second definition of a
        // shared schema — the schema mapping did not apply (a schema copied instead of aliased to `_common`, or the
        // mapping dropped) — and the two would drift; the build refuses it rather than let the two packages each hold
        // one. Core's names are read from `:core`'s own generated output, so core generates first.
        val own = modelsDir.get().asFile.resolve("src/main/kotlin/${set.modelPackage.replace('.', '/')}")
        val clash = coreModels.files.flatMap(::modelNames).toSet() intersect modelNames(own)
        if (clash.isNotEmpty()) {
            throw GradleException(
                "the ${set.owner} specs generate types core already has: ${clash.sorted()} — alias the schema to " +
                    "`_common.openapi.yaml` (it is then mapped to core's class) or give it a name of its own",
            )
        }
        // The admin channel is core's (ADR 0065): a marker on an application's operation would mark nothing that is
        // enforced, so it fails the build here, where the application's specs are generated.
        val (_, problems) = SpecSets.channelMarkers(set.owner, set.specs, contractsDir.get().asFile)
        if (problems.isNotEmpty()) throw GradleException(problems.joinToString("\n"))
    }

    private fun run(
        set: SpecSet,
        commonSchemas: List<String>,
        generator: String,
        specPath: String,
        outDir: File,
        globalProperties: Map<String, String>,
        additional: Map<String, String>,
        library: String? = null,
    ) {
        val cfg = CodegenConfigurator()
            .setGeneratorName(generator)
            .setInputSpec(specPath)
            .setOutputDir(outDir.absolutePath)
            .setGlobalProperties(globalProperties)
            .setAdditionalProperties(additional)
            .setApiPackage(set.apiPackage)
            .setModelPackage(set.modelPackage)
            .setInvokerPackage(set.packageRoot)
            .setPackageName(set.packageRoot)
        // Measured (cr-platform-gradle-modules step 5): both generators honour a schema mapping — the Kotlin models and
        // the jaxrs-spec interfaces name the core FQN and emit no type of their own for it. `importMappings` alone is
        // the wrong lever: it still generates the model, as a junk file named after the FQN.
        commonSchemas.forEach { cfg.addSchemaMapping(it, "${SpecSets.packageRootOf(SpecSets.coreOwner)}.model.$it") }
        library?.let(cfg::setLibrary)
        DefaultGenerator().opts(cfg.toClientOptInput()).generate()
    }

    /** The model class names a generated model package directory holds. */
    private fun modelNames(packageDir: File): Set<String> =
        packageDir.listFiles().orEmpty().filter { it.name.endsWith(".kt") }.map { it.name.removeSuffix(".kt") }.toSet()
}

/**
 * The admin channel's operation set (ADR 0065, uc.admin.admin-channel): every operation a core spec marks
 * `x-veyq-channel: admin`, emitted as Kotlin so the backend's channel filter reads the CONTRACT and nobody lists the
 * set by hand. `admin` is the extension's only value; any other — or the marker on a path item instead of an
 * operation, where it would silently mark nothing — fails the build. `AdminChannelPostureSweepTest` then holds the
 * set to the backend's Server-Owner gate in both directions. The channel is core's: the gate reads core's set, so a
 * marker on an APPLICATION's operation fails that application's generation (`OpenApiGenerate`).
 */
@CacheableTask
abstract class GenerateAdminChannelOperations : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val specs: ConfigurableFileCollection

    /** `contracts/api`, for naming a spec in an error message only. */
    @get:Internal
    abstract val contractsDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val (marked, problems) = SpecSets.channelMarkers(SpecSets.coreOwner, specs.files.sorted(), contractsDir.get().asFile)
        if (problems.isNotEmpty()) throw GradleException(problems.joinToString("\n"))
        val out = outputDir.get().file("src/main/kotlin/me/raddatz/veyq/core/generated/AdminChannelOperations.kt").asFile
        out.parentFile.mkdirs()
        out.writeText(
            """
            |package me.raddatz.veyq.core.generated
            |
            |/** Generated from `x-veyq-channel: admin` in contracts/api (ADR 0065) — never edited, never listed by hand. */
            |object AdminChannelOperations {
            |    val operationIds: Set<String> = setOf(
            |${marked.joinToString("") { "        \"$it\",\n" }}    )
            |}
            |""".trimMargin(),
        )
    }
}

/**
 * The capability register as code (ADR 0032, ADR 0088 decisions 12 and 13): `CapabilityIds` — one constant per entry
 * of `contracts/platform/capabilities.json` and `ADVERTISED`, every entry in catalog order — and `CapabilityTexts`, each
 * entry's `title`, `purpose` and `whenOff` in English and German, which `GET /api/instance/capabilities` serves. The
 * catalog is the one place an id and its words are written, so the backend never keeps a copy that could drift from it:
 * an entry added there is a constant here on the next compile.
 *
 * A constant's name is the id without its `core.` root, upper-cased, `.` and `-` turned into `_`
 * (`core.public-shares.drop-box` → `PUBLIC_SHARES_DROP_BOX`); an entry under another root keeps the root in its name.
 * Every entry is advertised, because `scripts/validate_capabilities.py` holds every catalog entry to a built use-case
 * (ADR 0032 §6); the lifecycle that reserves an id ahead of its use-cases would add the skip here and there together.
 * `TENANT_SWITCHABLE` is every entry whose `levels` name `tenant` (ADR 0088 decision 12), and `CapabilityRequirements`
 * every entry's `requirements` with their kind and words (decision 8), each name unique in the catalog, and
 * `EXTERNAL_SERVICES` every entry whose `source` is `external-service` (decision 7). The build refuses an id that is not
 * lowercase-dotted, two ids that name one constant, an entry without its six texts, one without its levels, and a
 * `modes` (the operating mode's preset, retired with the mode, ADR 0088 decision 14), rather than generate a register
 * with a hole in it; the guard says the same about the catalog before any build.
 */
@CacheableTask
abstract class GenerateCapabilityRegister : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val catalog: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val file = catalog.get().asFile
        val entries = ObjectMapper().readTree(file).path("capabilities")
        if (!entries.isObject || entries.isEmpty) throw GradleException("${file.name}: no `capabilities` entries")
        val problems = mutableListOf<String>()
        val register = entries.properties().map { (id, entry) ->
            if (!ID.matches(id)) problems += "$id: not a lowercase-dotted id"
            val texts = TEXT_FIELDS.associateWith { field ->
                LANGUAGES.associateWith { lang ->
                    entry.path(field).path(lang).takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }
                        ?: "".also { problems += "$id: $field.$lang is missing or blank" }
                }
            }
            val levels = entry.path("levels").takeIf { it.isArray }?.map { it.asText() }.orEmpty()
            if ("instance" !in levels || levels.any { it !in LEVELS }) problems += "$id: levels must hold instance, and tenant at most"
            val requirements = entry.path("requirements").properties().map { (name, req) ->
                val kind = req.path("kind").asText("")
                if (kind !in REQUIREMENT_KINDS) problems += "$id: requirement $name has no flag or days kind"
                val reqTexts = REQUIREMENT_TEXTS.associateWith { field ->
                    LANGUAGES.associateWith { lang ->
                        req.path(field).path(lang).takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }
                            ?: "".also { problems += "$id: requirement $name $field.$lang is missing or blank" }
                    }
                }
                Requirement(name, kind, reqTexts)
            }
            if (entry.has("modes")) problems += "$id: modes was the operating mode's preset, retired with the mode (ADR 0088 decision 14)"
            Entry(
                id, constantName(id), entry.path("description").asText(""), texts, "tenant" in levels, requirements,
                entry.path("source").asText("") == "external-service",
            )
        }
        register.groupBy { it.constant }.filterValues { it.size > 1 }.forEach { (name, clash) ->
            problems += "${clash.joinToString { it.id }}: all name the constant $name"
        }
        register.flatMap { e -> e.requirements.map { it.name } }.groupBy { it }.filterValues { it.size > 1 }.keys.forEach {
            problems += "requirement $it is named by two entries: a name is one property of the wire's Capability"
        }
        if (problems.isNotEmpty()) throw GradleException("${file.name}:\n" + problems.joinToString("\n"))

        val dir = outputDir.get().asFile.resolve("src/main/kotlin/${PACKAGE.replace('.', '/')}")
        dir.deleteRecursively()
        dir.mkdirs()
        dir.resolve("CapabilityIds.kt").writeText(
            """
            |package $PACKAGE
            |
            |/**
            | * Generated from contracts/platform/capabilities.json (buildSrc's GenerateCapabilityRegister) — never edited, never
            | * listed by hand. The client-facing capability ids (ADR 0032, ADR 0088): instance-wide and pre-auth, a tree of
            | * dotted paths that [CapabilityRegistry] ANDs along. Each is advertised on `GET /api/setup` while it is active and
            | * enforced at its feature's gate (`422 capability_disabled`).
            | */
            |object CapabilityIds {
            |${register.joinToString("\n") { "    /** ${kdoc(it.description)} */\n    const val ${it.constant} = ${literal(it.id)}\n" }}
            |    /** Every entry, in catalog order, a parent before its children: the register this build serves. */
            |    val ADVERTISED: List<String> = listOf(
            |${register.joinToString("") { "        ${it.constant},\n" }}    )
            |
            |    /** The entries a tenant owner may switch off for their tenant too (the catalog's `levels`, ADR 0088 decision 12);
            |     *  every other entry is the Server Owner's alone. */
            |    val TENANT_SWITCHABLE: Set<String> = setOf(
            |${register.filter { it.tenant }.joinToString("") { "        ${it.constant},\n" }}    )
            |
            |    /** The entries that are an outside service the browser calls (the catalog's `source: external-service`, ADR 0088
            |     *  decision 7): active only while their address is configured. */
            |    val EXTERNAL_SERVICES: Set<String> = setOf(
            |${register.filter { it.external }.joinToString("") { "        ${it.constant},\n" }}    )
            |}
            |""".trimMargin(),
        )
        dir.resolve("CapabilityTexts.kt").writeText(
            """
            |package $PACKAGE
            |
            |/** One text of a register entry, in each language the catalog carries (ADR 0088 decision 13). */
            |data class CapabilityText(val en: String, val de: String)
            |
            |/** An entry's words for the Server Owner: what it is, what it is for, and what switching it off does. */
            |data class CapabilityEntryTexts(val title: CapabilityText, val purpose: CapabilityText, val whenOff: CapabilityText)
            |
            |/** Generated from contracts/platform/capabilities.json (buildSrc's GenerateCapabilityRegister) — never edited. */
            |object CapabilityTexts {
            |    /** Every entry of [CapabilityIds.ADVERTISED] to its words. */
            |    val BY_ID: Map<String, CapabilityEntryTexts> = mapOf(
            |${register.joinToString("") { e -> "        CapabilityIds.${e.constant} to CapabilityEntryTexts(\n${TEXT_FIELDS.joinToString("") { f -> "            CapabilityText(${literal(e.texts.getValue(f).getValue("en"))}, ${literal(e.texts.getValue(f).getValue("de"))}),\n" }}        ),\n" }}    )
            |}
            |""".trimMargin(),
        )
        dir.resolve("CapabilityRequirements.kt").writeText(
            """
            |package $PACKAGE
            |
            |/** A requirement's kind (ADR 0088 decision 8): set or not (stored as `1`), or a number of days. */
            |enum class RequirementKind { FLAG, DAYS }
            |
            |/** One requirement of a register entry: the property it is on the wire's `Capability`, its kind and its words. */
            |data class CapabilityRequirement(
            |    val name: String, val capabilityId: String, val kind: RequirementKind, val title: CapabilityText, val whenSet: CapabilityText,
            |)
            |
            |/** Generated from contracts/platform/capabilities.json (buildSrc's GenerateCapabilityRegister) — never edited. The
            | *  requirements of every entry (the catalog's `requirements`), each name unique in the catalog. */
            |object CapabilityRequirements {
            |${register.flatMap { e -> e.requirements.map { "    const val ${requirementConstant(it.name)} = ${literal(it.name)}\n" } }.joinToString("")}
            |    /** Every requirement, in catalog order. */
            |    val ALL: List<CapabilityRequirement> = listOf(
            |${register.flatMap { e -> e.requirements.map { r -> "        CapabilityRequirement(\n            ${literal(r.name)}, CapabilityIds.${e.constant}, RequirementKind.${r.kind.uppercase()},\n${REQUIREMENT_TEXTS.joinToString("") { f -> "            CapabilityText(${literal(r.texts.getValue(f).getValue("en"))}, ${literal(r.texts.getValue(f).getValue("de"))}),\n" }}        ),\n" } }.joinToString("")}    )
            |
            |    val BY_NAME: Map<String, CapabilityRequirement> = ALL.associateBy { it.name }
            |}
            |""".trimMargin(),
        )
    }

    private data class Entry(
        val id: String, val constant: String, val description: String, val texts: Map<String, Map<String, String>>, val tenant: Boolean,
        val requirements: List<Requirement>, val external: Boolean,
    )

    private data class Requirement(val name: String, val kind: String, val texts: Map<String, Map<String, String>>)

    private companion object {
        const val PACKAGE = "me.raddatz.veyq.core.platform.capabilities"
        const val CORE_ROOT = "core."
        /** A dotted path under a root; one segment is an application's own switch (`memories`, ADR 0079 decision 8). */
        val ID = Regex("^[a-z0-9]+(\\.[a-z0-9-]+)*$")
        val TEXT_FIELDS = listOf("title", "purpose", "whenOff")
        val LANGUAGES = listOf("en", "de")
        val LEVELS = setOf("instance", "tenant")
        val REQUIREMENT_KINDS = setOf("flag", "days")
        val REQUIREMENT_TEXTS = listOf("title", "whenSet")

        /** `linkMaxDays` → `LINK_MAX_DAYS`. */
        fun requirementConstant(name: String): String = name.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").uppercase()

        fun constantName(id: String): String = id.removePrefix(CORE_ROOT).uppercase().replace('.', '_').replace('-', '_')

        /** [value] as a Kotlin string literal: a catalog text may hold a quote, a dollar sign or a backslash. */
        fun literal(value: String): String = buildString {
            append('"')
            value.forEach { c ->
                when {
                    c == '\\' -> append("\\\\")
                    c == '"' -> append("\\\"")
                    c == '$' -> append("\\$")
                    c == '\n' -> append("\\n")
                    c < ' ' -> append("\\u%04x".format(c.code))
                    else -> append(c)
                }
            }
            append('"')
        }

        /** [value] on one KDoc line: Kotlin comments nest, so neither an opener nor a closer may survive in it. */
        fun kdoc(value: String): String =
            value.replace(Regex("\\s+"), " ").replace("*/", "* /").replace("/*", "/ *").trim()
    }
}
