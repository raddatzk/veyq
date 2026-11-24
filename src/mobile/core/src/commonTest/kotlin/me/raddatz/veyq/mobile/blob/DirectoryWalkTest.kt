package me.raddatz.veyq.mobile.blob

import kotlin.test.Test
import kotlin.test.assertEquals

/** A walked directory's name ([DirectoryWalk.rootName]): what a dropped folder is said by and Depot's upload makes. */
class DirectoryWalkTest {
    @Test
    fun theRootIsTheDirectorysOwnName() {
        assertEquals("Trip", DirectoryWalk.rootName("Trip", "primary:Download/Other"), "the name the picker shows wins")
        assertEquals("Trip", DirectoryWalk.rootName(null, "/Users/me/Trip/"))
        assertEquals("Trip", DirectoryWalk.rootName(" ", "primary:Download/Trip"))
        assertEquals("Download", DirectoryWalk.rootName(null, "primary:Download"))
        assertEquals("Reise 2026", DirectoryWalk.rootName(null, "C:\\Users\\me\\Reise 2026"))
    }
}
