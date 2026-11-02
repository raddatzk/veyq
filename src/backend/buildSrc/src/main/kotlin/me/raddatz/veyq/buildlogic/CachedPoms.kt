package me.raddatz.veyq.buildlogic

import org.gradle.api.GradleException
import org.w3c.dom.Element
import java.io.File
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A module's POM read out of Gradle's file store, accepted only when its SHA-256 is the one
 * `gradle/verification-metadata.xml` pins for it: [ThirdPartyLicenses]' POMs under `--offline`.
 *
 * Why it exists: the graph's resolution reads every POM as METADATA (the store's resource index), but an artifact query
 * for the same POM (`createArtifactResolutionQuery` with `MavenPomArtifact`, or a `g:a:v@pom` dependency) is answered from
 * the ARTIFACT index, which only an online query fills. Offline, on a cache whose artifact index holds no POM, every one
 * of them fails "No cached version available for offline mode" although the file is on disk (measured 2026-11-02, Gradle
 * 9.8.2: 0 of 273 shipped modules' POMs resolved offline, every file present). The store's layout,
 * `files-2.1/<group>/<module>/<version>/<sha1>/<file>`, is the one Gradle's read-only dependency cache documents; a file
 * whose hash the metadata does not pin is refused, so reading it outside Gradle's resolution trusts nothing it would not.
 */
class CachedPoms(private val filesStore: File, private val verificationMetadata: File) {
    private val pinned: Map<String, Set<String>> by lazy {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }.newDocumentBuilder().parse(verificationMetadata)
        val out = mutableMapOf<String, MutableSet<String>>()
        val components = doc.getElementsByTagName("component")
        for (i in 0 until components.length) {
            val c = components.item(i) as Element
            val coordinates = "${c.getAttribute("group")}:${c.getAttribute("name")}:${c.getAttribute("version")}"
            val artifacts = c.getElementsByTagName("artifact")
            for (j in 0 until artifacts.length) {
                val a = artifacts.item(j) as Element
                val hashes = out.getOrPut("$coordinates/${a.getAttribute("name")}") { mutableSetOf() }
                val sha256s = a.getElementsByTagName("sha256")
                for (k in 0 until sha256s.length) {
                    val sha256 = sha256s.item(k) as Element
                    hashes += sha256.getAttribute("value")
                    // An `also-trust` below a `sha256` names a second accepted file of the same algorithm.
                    val alsoTrusted = sha256.getElementsByTagName("also-trust")
                    for (m in 0 until alsoTrusted.length) hashes += (alsoTrusted.item(m) as Element).getAttribute("value")
                }
            }
        }
        out
    }

    fun pom(group: String, name: String, version: String): File {
        val file = "$name-$version.pom"
        val expected = pinned["$group:$name:$version/$file"].orEmpty()
        if (expected.isEmpty()) throw GradleException("$group:$name:$version: $file is not pinned in ${verificationMetadata.name}")
        val candidates = filesStore.resolve(group).resolve(name).resolve(version).listFiles().orEmpty()
            .map { it.resolve(file) }.filter { it.isFile }
        return candidates.firstOrNull { sha256(it) in expected }
            ?: throw GradleException(
                "$group:$name:$version: no $file in Gradle's file store matches its pinned SHA-256" +
                    (if (candidates.isEmpty()) " (none cached; build once online)" else " (${candidates.size} cached, none verified)"),
            )
    }

    private fun sha256(f: File): String =
        MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
}
