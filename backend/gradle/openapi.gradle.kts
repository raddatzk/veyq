buildscript {
    repositories { mavenCentral() }
    dependencies {
        classpath("org.openapitools:openapi-generator:7.23.0")
    }
}

// Spec-first generation: Kotlin models + JAX-RS (Quarkus) interfaces from the contracts, one spec set per owner
// (ADR 0079): core's specs (`contracts/api/*.openapi.yaml`) into `com.veyq.core.generated.{api,model}`, the Memories
// application's (`contracts/api/memories/*.openapi.yaml`) into `com.veyq.memories.generated.{api,model}`. Specs $ref
// shared components from `_common.openapi.yaml` (ADR 0038), which is core's: openapi-generator resolves the external
// ref in place, so no bundling or committed artifact is needed. Core generates every `_common` schema a core spec
// names; the Memories set maps every `_common` schema to core's class (`schemaMappings`), so a shared schema has ONE
// generated type, core's — and a Memories model that still shares a core model's name fails the build (below).
// Slice Resources implement the generated `*Api` interfaces (one tag per API resource — ADR 0001).

val contractsDir = file("../contracts/api")
val commonSpec = contractsDir.resolve("_common.openapi.yaml")
val generatedRoot = layout.buildDirectory.dir("generated/openapi")
val generatedChannelDir = layout.buildDirectory.dir("generated/admin-channel")

/** One owner's specs and where they generate to. */
class SpecSet(val owner: String, val specs: List<File>, val packageRoot: String, val mapsCommon: Boolean) {
    val apiPackage get() = "$packageRoot.api"
    val modelPackage get() = "$packageRoot.model"
}

// `_common.openapi.yaml` is a shared-component $ref target (ADR 0038), not a spec — never fed to the generator on its own.
val coreSpecs = fileTree(contractsDir) {
    include("*.openapi.yaml")
    exclude("_common.openapi.yaml")
}.files.sorted()
val memoriesSpecs = fileTree(contractsDir.resolve("memories")) { include("*.openapi.yaml") }.files.sorted()
val specSets = listOf(
    SpecSet("core", coreSpecs, "com.veyq.core.generated", mapsCommon = false),
    SpecSet("memories", memoriesSpecs, "com.veyq.memories.generated", mapsCommon = true),
)

// A spec in neither set would silently generate nothing (its routes would 404 with no error anywhere): refuse it.
run {
    val placed = specSets.flatMap { it.specs }.map { it.canonicalFile }.toSet() + commonSpec.canonicalFile
    val stray = fileTree(contractsDir) { include("**/*.openapi.yaml") }.files.filter { it.canonicalFile !in placed }
    if (stray.isNotEmpty()) throw GradleException("specs owned by no generation set (gradle/openapi.gradle.kts): ${stray.sorted()}")
}
// An empty set is refused when it would generate, not while the build is configured: the Dockerfile's warm-up
// (`gradlew help` in the `backend-build-base` stage) configures the build before `contracts/` is copied in, and a
// configuration-time refusal broke that warm-up — so the image did not build — with no `./gradlew check` red.
fun requireSpecs(set: SpecSet) {
    if (set.specs.isEmpty()) throw GradleException("the ${set.owner} spec set is empty — the glob lost its specs")
}

/** Every schema `_common` defines — read from the file, never listed, so a schema moved there is mapped the day it lands. */
fun commonSchemaNames(): List<String> {
    val options = io.swagger.v3.parser.core.models.ParseOptions().apply { isResolve = false }
    val api = io.swagger.v3.parser.OpenAPIV3Parser().readLocation(commonSpec.absolutePath, null, options).openAPI
        ?: throw GradleException("${commonSpec.name}: not parseable as OpenAPI")
    return api.components?.schemas?.keys?.sorted().orEmpty().ifEmpty { throw GradleException("${commonSpec.name} defines no schema") }
}

fun runGenerator(
    set: SpecSet,
    generator: String,
    specPath: String,
    outDir: Provider<Directory>,
    globalProperties: Map<String, String>,
    additional: Map<String, String>,
    library: String? = null,
) {
    val cfg = org.openapitools.codegen.config.CodegenConfigurator()
        .setGeneratorName(generator)
        .setInputSpec(specPath)
        .setOutputDir(outDir.get().asFile.absolutePath)
        .setGlobalProperties(globalProperties)
        .setAdditionalProperties(additional)
        .setApiPackage(set.apiPackage)
        .setModelPackage(set.modelPackage)
        .setInvokerPackage(set.packageRoot)
        .setPackageName(set.packageRoot)
    // Measured (cr-platform-gradle-modules step 5): both generators honour a schema mapping — the Kotlin models and
    // the jaxrs-spec interfaces name the core FQN and emit no type of their own for it. `importMappings` alone is
    // the wrong lever: it still generates the model, as a junk file named after the FQN.
    if (set.mapsCommon) commonSchemaNames().forEach { cfg.addSchemaMapping(it, "com.veyq.core.generated.model.$it") }
    library?.let(cfg::setLibrary)
    org.openapitools.codegen.DefaultGenerator().opts(cfg.toClientOptInput()).generate()
}

fun apiDir(set: SpecSet) = generatedRoot.map { it.dir("${set.owner}/api") }
fun modelsDir(set: SpecSet) = generatedRoot.map { it.dir("${set.owner}/models") }
fun modelNames(set: SpecSet): Set<String> =
    modelsDir(set).get().asFile.resolve("src/main/kotlin/${set.modelPackage.replace('.', '/')}")
        .listFiles().orEmpty().filter { it.name.endsWith(".kt") }.map { it.name.removeSuffix(".kt") }.toSet()

val generateTasks = specSets.map { set ->
    tasks.register("openApiGenerate${set.owner.replaceFirstChar(Char::uppercase)}") {
        group = "openapi tools"
        description = "Generate Kotlin models + JAX-RS interfaces from the ${set.owner} specs."
        inputs.files(set.specs + commonSpec)
        outputs.dirs(apiDir(set), modelsDir(set))
        doFirst {
            requireSpecs(set)
            delete(apiDir(set), modelsDir(set))
        }
        doLast {
            set.specs.forEach { spec ->
                runGenerator(
                    set, "kotlin", spec.absolutePath, modelsDir(set),
                    mapOf("models" to "", "supportingFiles" to "false"),
                    mapOf(
                        "serializationLibrary" to "jackson",
                        "enumPropertyNaming" to "original",
                        "collectionType" to "list",
                    ),
                )
                runGenerator(
                    set, "jaxrs-spec", spec.absolutePath, apiDir(set),
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
        }
    }
}

// Every schema has ONE generated type. A Memories model named like a core one is a second definition of a shared
// schema — the schema mapping above did not apply (a schema copied instead of aliased to `_common`, or the mapping
// dropped) — and the two would drift; the build refuses it rather than let the two packages each hold one.
generateTasks.last().configure {
    dependsOn(generateTasks.first())
    doLast {
        val (core, app) = specSets
        val clash = modelNames(core) intersect modelNames(app)
        if (clash.isNotEmpty()) {
            throw GradleException(
                "the ${app.owner} specs generate types core already has: ${clash.sorted()} — alias the schema to " +
                    "`_common.openapi.yaml` (it is then mapped to core's class) or give it a name of its own",
            )
        }
    }
}

tasks.register("openApiGenerateSpecs") {
    group = "openapi tools"
    description = "Generate every owner's Kotlin models + JAX-RS interfaces from contracts/api."
    dependsOn(generateTasks)
}

// The admin channel's operation set (ADR 0065, uc.admin.admin-channel): every operation a spec marks
// `x-veyq-channel: admin`, emitted as Kotlin so the backend's channel filter reads the CONTRACT and nobody lists the
// set by hand. `admin` is the extension's only value; any other — or the marker on a path item instead of an
// operation, where it would silently mark nothing — fails the build. `AdminChannelPostureSweepTest` then holds the
// set to the backend's Server-Owner gate in both directions. The channel is core's: the gate reads core's set, so a
// marker on an APPLICATION's operation would mark nothing that is enforced — it fails the build too.
tasks.register("generateAdminChannelOperations") {
    group = "openapi tools"
    description = "Derive the admin-channel operationIds from the x-veyq-channel markers in contracts/api."
    inputs.files(specSets.flatMap { it.specs })
    outputs.dir(generatedChannelDir)
    doLast {
        val marker = "x-veyq-channel"
        val options = io.swagger.v3.parser.core.models.ParseOptions().apply { isResolve = false }
        val marked = sortedSetOf<String>()
        val problems = mutableListOf<String>()
        specSets.forEach { set ->
            set.specs.forEach { spec ->
                val name = spec.relativeTo(contractsDir).invariantSeparatorsPath
                val api = io.swagger.v3.parser.OpenAPIV3Parser().readLocation(spec.absolutePath, null, options).openAPI
                    ?: throw GradleException("$name: not parseable as OpenAPI")
                api.paths.orEmpty().forEach { (path, item) ->
                    if (item.extensions?.containsKey(marker) == true) problems += "$name $path: $marker on the path item marks nothing — put it on the operation"
                    item.readOperationsMap().forEach { (method, op) ->
                        val value = op.extensions?.get(marker) ?: return@forEach
                        val id = op.operationId ?: "$method $path"
                        if (set.owner != "core") problems += "$name $id: $marker on an application's operation — the admin channel is core's, its gate reads core's set only"
                        else if (value != "admin") problems += "$name $id: $marker must be `admin`, was `$value`"
                        else if (op.operationId == null) problems += "$name $method $path: a marked operation needs an operationId"
                        else marked += id
                    }
                }
            }
        }
        if (problems.isNotEmpty()) throw GradleException(problems.joinToString("\n"))
        val out = generatedChannelDir.get().file("src/main/kotlin/com/veyq/core/generated/AdminChannelOperations.kt").asFile
        out.parentFile.mkdirs()
        out.writeText(
            """
            |package com.veyq.core.generated
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

extensions.configure<org.gradle.api.tasks.SourceSetContainer>("sourceSets") {
    named("main") { java { specSets.forEach { set -> srcDir(apiDir(set).map { it.dir("src/gen/java") }) } } }
}

@Suppress("UNCHECKED_CAST")
val kotlinSourceSets = extensions.getByName("kotlin").javaClass.methods
    .single { it.name == "getSourceSets" && it.parameterCount == 0 }
    .invoke(extensions.getByName("kotlin")) as org.gradle.api.NamedDomainObjectContainer<Any>

kotlinSourceSets.named("main") {
    val kotlinSourceDirectorySet = javaClass.methods
        .single { it.name == "getKotlin" && it.parameterCount == 0 }.invoke(this)
    val srcDir = kotlinSourceDirectorySet.javaClass.methods
        .first { it.name == "srcDir" && it.parameterCount == 1 }
    specSets.forEach { set -> srcDir.invoke(kotlinSourceDirectorySet, modelsDir(set).map { it.dir("src/main/kotlin") }) }
    srcDir.invoke(kotlinSourceDirectorySet, generatedChannelDir.map { it.dir("src/main/kotlin") })
}

tasks.named("compileKotlin") { dependsOn(tasks.named("openApiGenerateSpecs"), tasks.named("generateAdminChannelOperations")) }
tasks.named("compileJava") { dependsOn(tasks.named("openApiGenerateSpecs")) }
