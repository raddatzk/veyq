package me.raddatz.veyq.buildlogic

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
        // The cascade of a generated list moves onto its element type (HV000271) — see ModelCascade.
        ModelCascade.apply(modelsDir.get().asFile.resolve("src/main/kotlin"))
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
