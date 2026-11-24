import org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

// The composition (ADR 0155 decision 7.1; ADR 0154 decision 3): the one module that names every application. It installs
// each application's part into the platform's registry (`AppComposition`), holds what reads several of them (the shell's
// model, the drop, Swift's adapters over several), and is what every client links: the Apple framework `VeyqCore`
// (the platform and every application exported, nothing transitively), the bridge's and Android's one dependency, and
// the JavaScript package `@veyq/core-js` (each module its own file). Its targets and hierarchy are the core modules'
// convention (src/mobile/build.gradle.kts).
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// `jsPackage` copies the platform's conformance runner, a binary of the platform module's own, and each application's
// runners, a binary of that module's own (Expenses' decode runner).
evaluationDependsOn(":core")
evaluationDependsOn(":apps:expenses")

kotlin {
    // The package's library (ADR 0155 decision 6): one file per module (Kotlin's per-module granularity), so an entry
    // pays only for the modules it imports.
    js {
        binaries.library()
    }

    // The Apple app's one framework (ADR 0123), its name kept: Swift's `import VeyqCore` and every Objective-C name stay.
    val xcf = XCFramework("VeyqCore")
    listOf(iosArm64(), iosSimulatorArm64(), macosArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "VeyqCore"
            isStatic = true
            export(project(":core"))
            export(project(":apps:depot"))
            export(project(":apps:expenses"))
            export(project(":apps:memories"))
            xcf.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            api(project(":apps:depot"))
            api(project(":apps:expenses"))
            api(project(":apps:memories"))
        }
        commonTest.dependencies {
            implementation(project(":testing"))
        }
    }
}

// The compiled modules — one file each, the platform's `kotlin_veyq_mobile_core.mjs`, each application's and this module's —,
// their TypeScript definitions and source maps, into `@veyq/core-js`'s `dist/` (ADR 0155 decision 6): the one directory
// where Gradle and pnpm meet. The platform's conformance binary lands in `dist/conformance/`, Expenses' runners in
// `dist/conformance/expenses/`, beside and never inside
// the shipped modules. The package's `build` runs this task by name (src/packages/core-js/scripts/build.py).
val coreJsDist = rootDir.resolve("../packages/core-js/dist")
// The Kotlin version the module was compiled with, for the web's licence file: the standard library is bundled into what
// the web ships, and its notice names the version (src/web/vite.config.ts, `verify:license-sources`).
val jsKotlinVersion = tasks.register("jsKotlinVersion") {
    val version = getKotlinPluginVersion()
    val out = layout.buildDirectory.file("core-js/kotlin-stdlib.version")
    inputs.property("version", version)
    outputs.file(out)
    doLast { out.get().asFile.writeText("$version\n") }
}
// KT-68281: Kotlin 2.4's production link lists a class's interfaces in an order that differs between compiler processes.
// Its dead-code elimination (`UselessDeclarationsRemover.collectUsedSuperTypes`) replaces an interface it removed by that
// interface's surviving supertypes, gathered in a `HashSet` of IR symbols, whose iteration follows their identity hashes:
// `MutableList` dropped, `AbstractMutableList`'s `initMetadataForClass` names `[KtList, Collection]` in one build and
// `[Collection, KtList]` in the next. Fixed upstream in 2.5.0 (a `LinkedHashSet`); no compiler flag, execution strategy
// or `-Xir-keep` holds it in 2.4 (measured 2026-11-21). Until then `jsPackage` sorts such a list by name — only where it
// cannot change what runs: `initMetadataFor` copies each listed interface's prototype onto the class's in list order
// (`Object.assign`), so the order is free exactly when no listed interface, nor a super-interface of one, carries a
// default member (`protoOf(I).m = …`) — checked per list, every interface declared in that module; a list naming an
// imported interface is left as the compiler wrote it. Byte-identical builds are ADR 0155's criterion.
fun sortFreeInterfaceLists(module: String): String {
    val ident = """[A-Za-z_$][\w$]*"""
    val supers = Regex("""^initMetadataForInterface\(($ident), '[^']*'[^\[\n]*(?:\[([^\]]*)\])?""", RegexOption.MULTILINE)
        .findAll(module).associate { m -> m.groupValues[1] to m.groupValues[2].split(", ").filter { it.isNotEmpty() } }
    val withDefaults = Regex("""protoOf\(($ident)\)\.""").findAll(module).map { it.groupValues[1] }.toSet()
    fun free(name: String): Boolean = name in supers && name !in withDefaults && supers.getValue(name).all(::free)
    return Regex("""^(initMetadataFor\w*\([^\[\n]*)\[($ident(?:, $ident)+)\]""", RegexOption.MULTILINE).replace(module) { m ->
        val names = m.groupValues[2].split(", ")
        if (names.all(::free)) "${m.groupValues[1]}[${names.sorted().joinToString(", ")}]" else m.value
    }
}
// One declaration file per façade (ADR 0155 decision 5): several façades share one module file (the platform's search and
// tree in `kotlin_veyq_mobile_core.mjs`), so `dist/<entry>.d.mts` re-exports exactly the declarations the façade's package
// exports (`jsMain/…/<entry>/web/`), read from those sources' `@JsExport` declarations, never listed by hand, from the
// composition's module (`veyq-mobile-composition.mjs`, whose one `.d.mts` declares every module's exports).
// `package.json` maps `@veyq/core-js/<entry>`'s types to it and its code to the module file itself, so a name of another
// façade does not type-check through an entry and the web's direction guard, which reads the entry's name, judges every
// name by its owner. The code is the module file, not a file re-exporting from it: through such a hop a test's `vi.mock` of
// one export no longer reached the module's own (the web's `SearchField.test.ts`, measured 2026-11-22). Each module's façades re-export from that module's file, Kotlin's
// per-module name (`:core` → `kotlin_veyq_mobile_core.mjs`, `:apps:<a>` → `kotlin_veyq_mobile_apps_<a>.mjs`).
val facades: Map<String, FileTree> = (listOf(project(":core")) + rootProject.project(":apps").subprojects).associate { p ->
    "kotlin_veyq_mobile_" + p.path.removePrefix(":").replace(':', '_') + ".mjs" to p.fileTree("src/jsMain/kotlin") { include("**/web/*.kt") }
}
fun writeEntries(dist: File) {
    val declaration = Regex("""@JsExport\s+(?:@[\w.]+(?:\([^)]*\))?\s+)*(external\s+interface|class|object|fun|val)\s+(?:<[^>]*>\s*)?(\w+)""")
    val seen = HashSet<String>()
    // One declaration file for every module (the composition's): two façades exporting one name would merge there —
    // TypeScript merges two `interface`s of one name into one with both's fields (Expenses' ledger entry into the tree's,
    // measured 2026-11-24) — so a name belongs to one façade.
    val owner = HashMap<String, String>()
    facades.toSortedMap().forEach { (module, tree) ->
        tree.files.groupBy { it.parentFile.parentFile.name }.toSortedMap().forEach { (entry, files) ->
            check(seen.add(entry)) { "two façades named $entry" }
            val found = files.sortedBy { it.name }.flatMap { f -> declaration.findAll(f.readText()).map { it.groupValues[1] to it.groupValues[2] } }
            check(found.isNotEmpty()) { "the façade $entry exports nothing" }
            val all = found.map { it.second }.sorted()
            all.forEach { name -> owner.put(name, entry)?.let { other -> error("$name is exported by both façades $other and $entry") } }
            val head = "// @veyq/core-js/$entry: the declarations of the façade …mobile.$entry.web in $module, written by :composition:jsPackage.\n"
            dist.resolve("$entry.d.mts").writeText(head + "export { ${all.joinToString(", ")} } from './veyq-mobile-composition.mjs';\n")
        }
    }
}
val jsPackage = tasks.register<Sync>("jsPackage") {
    group = "veyq"
    description = "Compiles the core's modules for JavaScript and writes them and the conformance runners (the platform's, Expenses') into src/packages/core-js/dist."
    from(tasks.named<org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrLink>("compileProductionLibraryKotlinJs").flatMap { it.destinationDirectory })
    from(project(":core").tasks.named<org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrLink>("compileConformanceProductionLibraryKotlinJs").flatMap { it.destinationDirectory }) {
        into("conformance")
    }
    from(project(":apps:expenses").tasks.named<org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrLink>("compileConformanceProductionLibraryKotlinJs").flatMap { it.destinationDirectory }) {
        into("conformance/expenses")
    }
    from(jsKotlinVersion)
    into(coreJsDist)
    inputs.files(facades.values).withPropertyName("facades")
    val kotlinVersion = getKotlinPluginVersion()
    doLast {
        check(KotlinVersion(2, 5) > kotlinVersion.split('.', '-').take(2).map(String::toInt).let { KotlinVersion(it[0], it[1]) }) {
            "Kotlin $kotlinVersion carries the fix for KT-68281: remove sortFreeInterfaceLists from composition/build.gradle.kts"
        }
        coreJsDist.walk().filter { it.isFile && it.name.endsWith(".mjs") }.forEach { file ->
            val module = file.readText()
            val sorted = sortFreeInterfaceLists(module)
            if (sorted != module) file.writeText(sorted)
        }
        writeEntries(coreJsDist)
    }
}
// The dev server's build (`build.py --watch`, src/web/vite.config.ts): the development link of the shipped modules alone —
// no dead-code elimination, no conformance binary — into the same `dist/`, so a saved rule reaches the open page inside
// ADR 0155's 15 s (the production link is ~7 s more, measured 2026-11-20). Never what a check or an image ships: those
// run `jsPackage`, which writes `dist/` anew.
tasks.register<Sync>("jsPackageDev") {
    group = "veyq"
    description = "The development build of the core's modules for JavaScript into src/packages/core-js/dist, for the web's dev server."
    from(tasks.named<org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrLink>("compileDevelopmentLibraryKotlinJs").flatMap { it.destinationDirectory })
    from(jsKotlinVersion)
    into(coreJsDist)
    inputs.files(facades.values).withPropertyName("facades")
    doLast { writeEntries(coreJsDist) }
}
// The plugin's check of the generated `.d.ts` runs `tsc` under Kotlin's own Node (ignored by default today); the package's
// own typecheck reads them instead, so the task never runs.
tasks.withType<org.jetbrains.kotlin.gradle.targets.js.typescript.TypeScriptValidationTask>().configureEach { enabled = false }
