package me.raddatz.veyq.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Element
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/** Hands the task one module's POM; the build script resolves it through the build's own, checksum-verified resolution
 *  online, and under `--offline` out of Gradle's file store against the same pin ([CachedPoms]). */
fun interface PomResolver {
    fun pom(group: String, name: String, version: String): File
}

/**
 * The backend's third-party notices: one text file naming every jar the fast-jar ships in `quarkus-app/lib`, its
 * license, the LICENSE/NOTICE files the jar carries itself, and the full text of every license that occurs. An image
 * pushed to a registry is a distribution, and Apache-2.0 §4, the BSD/MIT notice clauses, the EPL and the LGPL each ask
 * for their text (and Apache for the NOTICE contents) to travel with the binary; half of the shipped jars carry no
 * license file of their own (measured 2026-10-16: 135 of 267), so the jars alone do not.
 *
 * The shipped jars are the input, not a configuration: a jar in `lib` that maps to no resolved module fails the task,
 * so a dependency Quarkus adds at packaging cannot slip past. Each module's license comes from its POM, or the nearest
 * parent POM that declares one. [KNOWN] maps every license NAME a POM may give to its SPDX id, and is the decision: a
 * name it does not hold fails the build, so a new license family is read by a person before it ships beside an
 * AGPL-3.0-only server — add it there, and its text under `src/backend/licenses/`, only once it is known to be compatible.
 * An MIT/BSD jar that carries no license file of its own needs the project's under `src/backend/licenses/notices/`, since
 * the SPDX template has no copyright holder; the task fails without one, and on one no shipped jar needs any more.
 */
abstract class ThirdPartyLicenses : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val libDir: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val licenseTexts: DirectoryProperty

    /** The resolved runtime artifacts: what the jars in [libDir] are matched against. [libDir] is their fingerprint. */
    @get:Internal
    abstract val artifacts: SetProperty<ResolvedArtifactResult>

    @get:Internal
    abstract val pomResolver: Property<PomResolver>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun write() {
        val byJarName = mutableMapOf<String, ModuleComponentIdentifier>()
        val ownJarSuffixes = mutableSetOf<String>()
        for (artifact in artifacts.get()) {
            when (val id = artifact.id.componentIdentifier) {
                // Quarkus names a module's jar in lib/ `<group>.<file name>` (fast-jar layout).
                is ModuleComponentIdentifier -> byJarName["${id.group}.${artifact.file.name}"] = id
                else -> ownJarSuffixes += ".${artifact.file.name}"
            }
        }

        val lib = libDir.get().asFile
        val jars = listOf("boot", "main").flatMap { sub ->
            lib.resolve(sub).listFiles { f -> f.name.endsWith(".jar") }.orEmpty().sortedBy { it.name }.map { sub to it }
        }
        if (jars.isEmpty()) throw GradleException("No jars under $lib: run quarkusBuild first.")

        val texts = licenseTexts.get().asFile
        val supplied = texts.resolve(NOTICES).listFiles { f -> f.name.endsWith(".txt") }.orEmpty().associateBy { it.name }
        val suppliedUsed = mutableSetOf<String>()

        val problems = mutableListOf<String>()
        val components = mutableListOf<Component>()
        for ((sub, jar) in jars) {
            val id = byJarName[jar.name]
            if (id == null) {
                if (ownJarSuffixes.none { jar.name.endsWith(it) }) problems += "lib/$sub/${jar.name}: no resolved module ships as this jar"
                continue
            }
            val declared = licensesOf(id.group, id.module, id.version)
            if (declared.isEmpty()) problems += "${id.displayName}: no POM in its parent chain declares a license"
            val spdx = declared.map { (name, url) ->
                spdxOf(name, url) ?: run {
                    problems += "${id.displayName}: license '$name' ($url) is not in ThirdPartyLicenses.KNOWN"
                    "?"
                }
            }.distinct()
            var notices = embeddedNotices(jar)
            // An MIT or BSD text is a template until it carries its holder's copyright line, which only the project's own
            // file has. A jar that ships none needs that file under licenses/notices/, named `<group>.<module>.txt`.
            if (notices.isEmpty() && spdx.any { it in NEEDS_HOLDER }) {
                val name = "${id.group}.${id.module}.txt"
                val file = supplied[name]
                if (file == null) {
                    problems += "${id.displayName}: ships no license file of its own; add the project's as src/backend/licenses/$NOTICES/$name"
                } else {
                    suppliedUsed += name
                    notices = listOf("src/backend/licenses/$NOTICES/$name" to file.readText().trim())
                }
            }
            components += Component(id, "lib/$sub/${jar.name}", spdx, notices)
        }
        // A file no shipped jar needs is either a dependency that left or one that now carries its own: both stale.
        for (name in supplied.keys - suppliedUsed) problems += "src/backend/licenses/$NOTICES/$name: no shipped jar needs it"

        val used = components.flatMap { it.spdx }.filter { it != "?" }.flatMap { it.split(" WITH ") }.toSortedSet()
        for (spdx in used) if (!texts.resolve("$spdx.txt").isFile) problems += "license text src/backend/licenses/$spdx.txt is missing"
        // A text no shipped jar is under any more: its dependency left (MPL-2.0 went with H2), and so does its admission.
        val held = texts.listFiles { f -> f.name.endsWith(".txt") }.orEmpty().map { it.name.removeSuffix(".txt") }
        for (spdx in held - used) problems += "license text src/backend/licenses/$spdx.txt: no shipped jar is under it"
        if (problems.isNotEmpty()) throw GradleException("Third-party licenses:\n  " + problems.joinToString("\n  "))

        val rendered = render(components, used, texts)
        if ('\u0000' in rendered) throw GradleException("Third-party licenses: the report would carry binary bytes")
        report.get().asFile.apply { parentFile.mkdirs() }.writeText(rendered)
    }

    private data class Component(
        val id: ModuleComponentIdentifier,
        val path: String,
        val spdx: List<String>,
        val notices: List<Pair<String, String>>,
    )

    private fun licensesOf(group: String, module: String, version: String, depth: Int = 0): List<Pair<String, String>> {
        val pom = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(pomResolver.get().pom(group, module, version)).documentElement
        val licenses = pom.child("licenses")?.children("license").orEmpty()
            .map { (it.child("name")?.textContent?.trim().orEmpty()) to (it.child("url")?.textContent?.trim().orEmpty()) }
        if (licenses.isNotEmpty() || depth > 10) return licenses
        val parent = pom.child("parent") ?: return emptyList()
        fun field(name: String) = parent.child(name)?.textContent?.trim() ?: throw GradleException("$group:$module:$version: parent without $name")
        return licensesOf(field("groupId"), field("artifactId"), field("version"), depth + 1)
    }

    private fun embeddedNotices(jar: File): List<Pair<String, String>> = ZipFile(jar).use { zip ->
        zip.entries().asSequence().filter { !it.isDirectory && NOTICE_FILE.matches(it.name) }.sortedBy { it.name }
            .map { it.name to zip.getInputStream(it).readBytes().toString(Charsets.UTF_8).trim() }.toList()
    }

    private fun render(components: List<Component>, used: Set<String>, texts: File): String = buildString {
        appendLine("Third-party software in the veyq backend")
        appendLine("========================================")
        appendLine()
        appendLine("veyq itself is licensed under the GNU Affero General Public License version 3 only (LICENSE, beside this")
        appendLine("file). The backend in /work/quarkus-app also carries the components below, each under its own license.")
        appendLine("Generated by the backend build (buildSrc ThirdPartyLicenses) from the jars it ships and their Maven POMs.")
        appendLine("Where a component lists several licenses, its POM offers them; the texts of all of them follow.")
        appendLine()
        appendLine("Components")
        appendLine("----------")
        for (c in components) appendLine("${c.id.displayName}  [${c.spdx.joinToString(", ")}]  ${c.path}")

        // One copy of each distinct file, with every component that carries it: most jars ship the same Apache text.
        val distinct = linkedMapOf<String, Pair<String, MutableList<String>>>()
        for (c in components) for ((entry, text) in c.notices) {
            distinct.getOrPut(sha256(text)) { text to mutableListOf() }.second += "${c.id.displayName} ($entry)"
        }
        appendLine()
        appendLine("License and notice files shipped inside the components")
        appendLine("------------------------------------------------------")
        for ((text, carriers) in distinct.values) {
            appendLine()
            carriers.forEach { appendLine("==> $it") }
            appendLine()
            appendLine(text)
        }
        appendLine()
        appendLine("License texts")
        appendLine("-------------")
        for (spdx in used) {
            appendLine()
            appendLine("==> $spdx")
            appendLine()
            appendLine(texts.resolve("$spdx.txt").readText().trim())
        }
    }

    companion object {
        private const val NOTICES = "notices"

        /** The licenses whose text names a copyright holder (SPDX templates hold a placeholder); MIT-0 asks for none. */
        private val NEEDS_HOLDER = setOf("MIT", "BSD-2-Clause", "BSD-3-Clause")

        // Exactly the names a jar's own notices take (measured 2026-10-16: all 194 in META-INF/, bare, .txt or .md). Not
        // a looser `LICENSE*` anywhere: that matched classes such as Flyway's LicenseGuard.class and wrote their bytes in.
        private val NOTICE_FILE = Regex("""^(META-INF/)?(LICEN[CS]E|NOTICE|COPYING)(\.txt|\.md)?$""", RegexOption.IGNORE_CASE)

        /**
         * Every license name a shipped module's POM gives, as its SPDX id — matched exactly (after trimming and
         * lower-casing), so a new spelling is read once rather than guessed. The Eclipse Distribution License 1.0 is
         * SPDX's BSD-3-Clause. Adding an entry is admitting that license next to AGPL-3.0-only code; see the class doc.
         */
        val KNOWN: Map<String, String> = mapOf(
            "apache license, version 2.0" to "Apache-2.0",
            "apache license version 2.0" to "Apache-2.0",
            "apache license 2.0" to "Apache-2.0",
            "the apache license, version 2.0" to "Apache-2.0",
            "the apache software license, version 2.0" to "Apache-2.0",
            "apache 2.0" to "Apache-2.0",
            "apache-2.0" to "Apache-2.0",
            "eclipse public license - v 1.0" to "EPL-1.0",
            "eclipse public license - v 2.0" to "EPL-2.0",
            "eclipse public license v. 2.0" to "EPL-2.0",
            "eclipse public license 2.0" to "EPL-2.0",
            "eclipse public license, version 2.0" to "EPL-2.0",
            "epl 2.0" to "EPL-2.0",
            "epl-2.0" to "EPL-2.0",
            "eclipse distribution license - v 1.0" to "BSD-3-Clause",
            "eclipse distribution license v. 1.0" to "BSD-3-Clause",
            "edl 1.0" to "BSD-3-Clause",
            "bsd-2-clause" to "BSD-2-Clause",
            "bsd-3-clause" to "BSD-3-Clause",
            "gpl2 w/ cpe" to "GPL-2.0-only WITH Classpath-exception-2.0",
            "gnu general public license, version 2 with the gnu classpath exception" to "GPL-2.0-only WITH Classpath-exception-2.0",
            "gpl-2.0-with-classpath-exception" to "GPL-2.0-only WITH Classpath-exception-2.0",
            "lgpl-2.1-or-later" to "LGPL-2.1-or-later",
            "mit" to "MIT",
            "mit-0" to "MIT-0",
        )

        /** "Public Domain" alone names no license; only the CC0 dedication it points at does. */
        fun spdxOf(name: String, url: String): String? {
            val key = name.trim().lowercase()
            if (key == "public domain") return if ("cc0-1.0" in url.lowercase()) "CC0-1.0" else null
            return KNOWN[key]
        }

        private fun sha256(text: String) =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

        private fun Element.children(tag: String): List<Element> =
            (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

        private fun Element.child(tag: String): Element? = children(tag).firstOrNull()
    }
}
