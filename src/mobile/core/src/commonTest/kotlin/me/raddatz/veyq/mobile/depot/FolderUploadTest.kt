package me.raddatz.veyq.mobile.depot

import me.raddatz.veyq.mobile.AppKinds
import kotlin.test.Test
import kotlin.test.assertEquals

/** A directory's plan as the web's `planFolderUpload` makes it: what is made, named how, and how much it weighs. */
class FolderUploadTest {
    private fun e(path: String, size: Long = 1) = FolderUpload.Entry(path, size, "text/plain", null) { _, n -> ByteArray(n) }

    private fun shape(d: FolderUpload.Dir): String =
        d.name + "[" + (d.dirs.map { shape(it) } + d.files.map { it.first }).joinToString(",") + "]"

    @Test
    fun onlyFilesMakeNodesSubfoldersNamedFirst() {
        val plan = FolderUpload.plan(
            "Trip", listOf(e("a.txt", 3), e("Photos/x.jpg", 5), e("Photos/Day 1/y.jpg", 7), e("//b.txt"), e("Photos//z.jpg")), AppKinds::tokenInput,
        )
        assertEquals("Trip[Photos[Day 1[y.jpg],x.jpg,z.jpg],a.txt,b.txt]", shape(plan.root))
        assertEquals(5, plan.files)
        assertEquals(17L, plan.bytes)
    }

    @Test
    fun namesAreMadeSafeAndToldApartByNameAndBySlug() {
        val plan = FolderUpload.plan(
            "Up", listOf(e("Notes.txt"), e("notes.TXT"), e("a:b.txt"), e("Ma Ma/x"), e("ma-ma/y")), AppKinds::tokenInput,
        )
        // a folder "Ma Ma" and "ma-ma" fold to one slug: the second is numbered; a case clash of a file too
        assertEquals("Up[Ma Ma[x],ma-ma (2)[y],Notes.txt,notes (2).TXT,a_b.txt]", shape(plan.root))
    }

    @Test
    fun aFolderAndAFileOfOneSlugAreToldApart() {
        // a folder and a file share their group's slugs (`registry.tokenInputFor`): the file named after is numbered
        val plan = FolderUpload.plan("Up", listOf(e("report/x"), e("report")), AppKinds::tokenInput)
        assertEquals("Up[report[x],report (2)]", shape(plan.root))
    }

    @Test
    fun theDirectoryNameIsNormalised() {
        val plan = FolderUpload.plan("Café", listOf(e("a")), AppKinds::tokenInput)
        assertEquals("Café", plan.root.name)
    }
}
