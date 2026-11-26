package org.rsmod.content.beanlab.admin.tools

import kotlin.test.Test
import kotlin.test.assertEquals

class QuestCommandsTest {
    @Test
    fun `counts only quests not yet finished`() {
        val quests =
            listOf(
                QuestTarget("cooks_assistant", stage = 2, endStage = 2, points = 1),
                QuestTarget("rune_mysteries", stage = 3, endStage = 6, points = 1),
                QuestTarget("dragon_slayer", stage = 0, endStage = 10, points = 2),
            )
        assertEquals(QuestCompletion(completed = 2, addedPoints = 3), questCompletion(quests))
    }

    @Test
    fun `running it twice adds nothing`() {
        val done = listOf(QuestTarget("cooks_assistant", stage = 2, endStage = 2, points = 1))
        assertEquals(QuestCompletion(0, 0), questCompletion(done))
    }
}
