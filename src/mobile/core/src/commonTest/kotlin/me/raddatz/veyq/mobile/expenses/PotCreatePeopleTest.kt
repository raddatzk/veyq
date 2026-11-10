package me.raddatz.veyq.mobile.expenses

import me.raddatz.veyq.mobile.session.Person
import kotlin.test.Test
import kotlin.test.assertEquals

/** A new Pot's first people as both sheets gathered them (the web's create dialog). */
class PotCreatePeopleTest {
    private val anna = Person("u1", "anna", "Anna Adler")
    private val ben = Person("u2", "ben", "Ben Bauer")

    @Test
    fun a_name_and_a_member_count_once_and_a_blank_name_not_at_all() {
        val p = PotCreate.People().typed("  Clara ").typed("clara").typed("   ")
            .add(PotCreate.NewPerson.member(anna.userId, anna.displayName)).add(PotCreate.NewPerson.member(anna.userId, anna.displayName))
        assertEquals(listOf("Clara", "Anna Adler"), p.list.map { it.name })
        assertEquals(listOf("Anna Adler"), p.remove(PotCreate.NewPerson.name("CLARA")).list.map { it.name })
    }

    @Test
    fun another_spaces_member_goes_and_members_already_in_are_not_offered() {
        val p = PotCreate.People().typed("Clara").add(PotCreate.NewPerson.member(anna.userId, anna.displayName))
        assertEquals(listOf(ben), p.offered(listOf(anna, ben)))
        assertEquals(listOf("Clara"), p.keep(listOf(ben)).list.map { it.name })
        assertEquals("EUR", PotCreate.defaultCurrency("de"))
        assertEquals("USD", PotCreate.defaultCurrency("fr"))
    }
}
