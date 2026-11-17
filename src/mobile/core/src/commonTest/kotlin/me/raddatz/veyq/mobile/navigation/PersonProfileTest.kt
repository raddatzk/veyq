package me.raddatz.veyq.mobile.navigation

import me.raddatz.veyq.mobile.memories.MediaDetails
import me.raddatz.veyq.mobile.memories.Participants
import me.raddatz.veyq.mobile.session.Person
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A person's profile, core's frame, and its ways in (ADR 0144 decision 11; the web's `PersonProfile.test.ts` where the apps
 * share its rules): the display name, `~username` under a real name, a former member for an id the read directory does
 * not list, the sections of the applications on; a current member's name in a Memory's participants and a photo's
 * uploader open it, a former member's and a guest's open nothing.
 */
class PersonProfileTest {
    private val ada = Person("u-ada", "ada", "Ada Lovelace", avatarRevision = 3)
    private val ben = Person("u-ben", "ben", "ben")
    private val people = mapOf(ada.userId to ada, ben.userId to ben)

    @Test
    fun aMemberIsNamedByTheDirectory_aRealNameWithTheUsernameUnderIt() {
        val p = PersonProfile.of("u-ada", people, emptySet())
        assertEquals("Ada Lovelace", p.name)
        assertEquals("~ada", p.handle)
        assertFalse(p.former)
        assertEquals(3, p.person?.avatarRevision, "the picture is the member's, by user id")
        assertEquals(listOf(ApplicationId.MEMORIES), p.sections)
        val plain = PersonProfile.of("u-ben", people, emptySet())
        assertEquals("ben", plain.name)
        assertNull(plain.handle, "no ~username under a title that already is the username")
    }

    @Test
    fun anIdTheReadDirectoryDoesNotListIsAFormerMember_anUnreadDirectoryNamesNobody() {
        val gone = PersonProfile.of("u-gone", people, emptySet())
        assertNull(gone.name)
        assertTrue(gone.former)
        assertEquals(listOf(ApplicationId.MEMORIES), gone.sections, "a former member can have been on a Memory: the sections still run")
        val unread = PersonProfile.of("u-ada", emptyMap(), emptySet())
        assertNull(unread.name)
        assertFalse(unread.former, "without the directory nobody is called former")
        assertEquals(emptyList(), PersonProfile.of("u-ada", people, setOf("memories")).sections, "Memories switched off adds no section")
    }

    @Test
    fun aCurrentMembersNameInTheParticipantsOpensTheProfile_aFormerMembersNot() {
        val line = Participants.line(listOf("u-ada", "u-gone"), listOf(ada), edits = false)!!
        assertEquals(listOf(true, false), line.people.map { it.opensProfile })
        val unread = Participants.line(listOf("u-ada"), null, edits = false)!!
        assertFalse(unread.people.single().opensProfile, "nobody opens while the members did not read")
    }

    @Test
    fun aCurrentMembersUploaderNameOpensTheProfile_aFormerMembersAndAGuestsNot() {
        val keys = setOf("user:u-ada", "user:u-gone", "share:link-1")
        val u = MediaDetails.uploaders(keys, listOf("u-ada" to "ada", "u-ben" to "ben"), people)
        assertEquals("Ada Lovelace", u.getValue("user:u-ada").name)
        assertEquals("u-ada", u.getValue("user:u-ada").profile)
        assertEquals(MediaDetails.UploaderKind.FORMER, u.getValue("user:u-gone").kind)
        assertNull(u.getValue("user:u-gone").profile, "a former member's uploader name opens nothing")
        assertNull(u.getValue("share:link-1").profile, "a guest opens nothing")
        assertEquals(setOf("share:link-1"), MediaDetails.uploaders(keys, null, people).keys, "unread members name no member, nor call one former")
    }
}
