package org.rsmod.content.beanlab.names

import dev.or2.central.display.DisplayNamePlayerChangeResult
import dev.or2.central.display.DisplayNamePolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class NamePolicyTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    private fun check(
        raw: String,
        current: String? = "Bean",
        changedAt: Long? = null,
        staff: Boolean = false,
        held: Long? = null,
    ) = checkRename(raw, current, changedAt, now, staff, held)

    @Test
    fun `a fresh valid name is accepted`() {
        val result = assertIs<DisplayNamePlayerChangeResult.Accepted>(check("PotatoLord"))
        assertEquals("PotatoLord", result.sanitized)
        assertNull(refusal(result, null, now))
    }

    @Test
    fun `the same name is no change`() {
        assertIs<DisplayNamePlayerChangeResult.NoChange>(check("Bean"))
    }

    @Test
    fun `a recent rename is on cooldown for players but not staff`() {
        val changedAt = now - day
        val result = check("PotatoLord", changedAt = changedAt)
        assertIs<DisplayNamePlayerChangeResult.CooldownActive>(result)
        assertEquals("You can change your name again in 27 day(s).", refusal(result, changedAt, now))
        assertIs<DisplayNamePlayerChangeResult.Accepted>(
            check("PotatoLord", changedAt = changedAt, staff = true)
        )
    }

    @Test
    fun `an old rename no longer blocks`() {
        val changedAt = now - DisplayNamePolicy.BOND_OR_STAFF_RENAME_WINDOW_MILLIS
        assertIs<DisplayNamePlayerChangeResult.Accepted>(check("PotatoLord", changedAt = changedAt))
    }

    @Test
    fun `a held name is refused`() {
        assertIs<DisplayNamePlayerChangeResult.NameHeld>(check("Zezima", held = now + day))
        assertIs<DisplayNamePlayerChangeResult.Accepted>(check("Zezima", held = now - day))
    }

    @Test
    fun `bad formats are refused`() {
        assertEquals(USAGE, refusal(check(""), null, now))
        assertIs<DisplayNamePlayerChangeResult.InvalidFormat>(check("Thisnameiswaytoolong"))
        assertIs<DisplayNamePlayerChangeResult.InvalidFormat>(check("ModBean"))
        assertEquals(
            "Use letters, numbers, - or _ only (no spaces).",
            refusal(check("Potato Lord"), null, now),
        )
    }

    @Test
    fun `only the character that gave a name up may take it back`() {
        assertNull(blockingHold(now + day, holder = 7, characterId = 7))
        assertEquals(now + day, blockingHold(now + day, holder = 7, characterId = 8))
        assertEquals(now + day, blockingHold(now + day, holder = null, characterId = 8))
        assertNull(blockingHold(null, holder = 7, characterId = 8))
    }

    @Test
    fun `days until rename round up`() {
        assertEquals(0, daysUntilRename(null, now))
        val window = DisplayNamePolicy.BOND_OR_STAFF_RENAME_WINDOW_MILLIS
        assertEquals(1, daysUntilRename(now - window + 1, now))
        assertEquals(0, daysUntilRename(now - window, now))
    }
}
