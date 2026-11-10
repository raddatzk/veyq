package me.raddatz.veyq.mobile.memories

import me.raddatz.veyq.mobile.memories.MediaDetails.Field
import me.raddatz.veyq.mobile.memories.MediaDetails.Row
import me.raddatz.veyq.mobile.memories.MediaDetails.Shown
import me.raddatz.veyq.mobile.navigation.NodeRef
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** A detail row's label and value as both apps show them, and a Memory's chapters (ADR 0127). */
class MediaDetailsShownTest {
    @Test
    fun every_field_has_its_label_and_a_value_shows_in_words_as_a_size_a_moment_or_as_read() {
        Field.entries.forEach { MediaDetails.label(it) } // every field labelled (a `when` without else)
        assertEquals(Msg.MEMORY_FIELD_FILE_NAME, MediaDetails.label(Field.FILE_NAME))
        assertEquals(Msg.MEMORY_FIELD_DIRECTION, MediaDetails.label(Field.DIRECTION_MAGNETIC))
        assertEquals(Notice(Msg.MEMORY_FLASH_FIRED), (MediaDetails.shown(Row(Field.FLASH, "", 1)) as Shown.Words).notice)
        assertEquals(Notice(Msg.MEMORY_FLASH_NOT_FIRED), (MediaDetails.shown(Row(Field.FLASH, "", 0)) as Shown.Words).notice)
        assertEquals(Notice(Msg.MEMORY_DIRECTION_TRUE, 270), (MediaDetails.shown(Row(Field.DIRECTION_TRUE, "", 270)) as Shown.Words).notice)
        assertEquals(1234L, (MediaDetails.shown(Row(Field.FILE_SIZE, "", 1234)) as Shown.Size).bytes)
        assertEquals(99L, (MediaDetails.shown(Row(Field.CAPTURED, "", 99)) as Shown.Moment).epochMs)
        assertIs<Shown.Plain>(MediaDetails.shown(Row(Field.CAMERA, "Pixel")))
    }

    @Test
    fun a_memory_lists_its_own_chapters_and_a_chapter_none() {
        val nodes = listOf(
            NodeRef("s", "c1", "m", "CHAPTER", "Day 1"),
            NodeRef("s", "c2", "other", "CHAPTER", "Elsewhere"),
            NodeRef("s", "f", "m", "FOLDER", "Not a chapter"),
        )
        assertEquals(listOf("c1"), MediaDetails.chapters("MEMORY", "m", nodes).map { it.nodeId })
        assertEquals(emptyList(), MediaDetails.chapters("CHAPTER", "m", nodes))
    }

    @Test
    fun the_uploader_is_named_and_stands_before_the_place() {
        assertEquals(me.raddatz.veyq.mobile.words.Piece.Plain("Anna"), MediaDetails.uploaderWords(MediaDetails.Uploader(MediaDetails.UploaderKind.MEMBER, "Anna")))
        assertEquals(me.raddatz.veyq.mobile.words.Piece.Said(Notice(Msg.MEMORY_UPLOADER_FORMER)), MediaDetails.uploaderWords(MediaDetails.Uploader(MediaDetails.UploaderKind.FORMER)))
        assertEquals(me.raddatz.veyq.mobile.words.Piece.Said(Notice(Msg.MEMORY_UPLOADER_GUEST)), MediaDetails.uploaderWords(MediaDetails.Uploader(MediaDetails.UploaderKind.GUEST, "x")))
        val rows = listOf(Row(Field.CAMERA, "Pixel"), Row(Field.PLACE, "Berlin"), Row(Field.ALTITUDE, "30 m"))
        assertEquals(1, MediaDetails.uploaderAt(rows))
        assertEquals(1, MediaDetails.uploaderAt(listOf(Row(Field.CAMERA, "Pixel"))))
    }
}
