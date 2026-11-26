package org.rsmod.content.beanlab.admin.tools

import com.github.michaelbull.logging.InlineLogger
import dev.or2.central.account.Rights
import jakarta.inject.Inject
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.script.onCommand
import org.rsmod.content.quest.manager.QUEST_STAGE_MAP_ATTR
import org.rsmod.content.quest.manager.Quest
import org.rsmod.game.cheat.Cheat
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

private const val QUEST_POINTS = "varp.qp"
private const val QUESTS_COMPLETED = "varbit.quests_completed_count"

/** What one quest needs to look complete: its stage var, end stage and quest points. */
internal data class QuestTarget(val key: String, val stage: Int, val endStage: Int, val points: Int)

internal data class QuestCompletion(val completed: Int, val addedPoints: Int)

/** Quests not yet at their end stage, and the quest points finishing them adds. */
internal fun questCompletion(quests: List<QuestTarget>): QuestCompletion {
    val open = quests.filter { it.stage < it.endStage }
    return QuestCompletion(open.size, open.sumOf { it.points })
}

/**
 * `::questsdone [display name]` (administrators): every quest OpenRune implements is set to its end
 * stage for you or an online player, with its quest points, so the quest journal shows them done.
 * Staging's `quest-requirements: assume-completed` only opens quest gates; the journal, quest
 * points and completed count stay at the real progress, which is why this still exists. No
 * rewards are given.
 */
public class QuestCommands @Inject constructor(private val playerList: PlayerList) :
    PluginScript() {
    private val logger = InlineLogger()

    override fun ScriptContext.startup() {
        onCommand("questsdone") {
            requiredRights = Rights.ADMINISTRATOR
            desc = "Mark every quest complete: ::questsdone [display name]"
            cheat { completeAll(this) }
        }
    }

    private fun completeAll(cheat: Cheat) {
        val name = cheat.args.joinToString(" ").trim()
        val target =
            if (name.isEmpty()) cheat.player
            else playerList.firstOrNull { it.displayName.equals(name, ignoreCase = true) }
        if (target == null) {
            cheat.player.mes("No online player named '$name'.")
            return
        }
        val quests = Quest.all().toList()
        val result =
            questCompletion(
                quests.map {
                    QuestTarget(it.key, it.getQuestStage(target), it.maxSteps, it.questPoints)
                }
            )
        quests.forEach { target.finish(it) }
        VarPlayerIntMapSetter.set(
            target,
            QUEST_POINTS,
            target.vars[QUEST_POINTS] + result.addedPoints,
        )
        VarPlayerIntMapSetter.set(
            target,
            QUESTS_COMPLETED,
            target.vars[QUESTS_COMPLETED] + result.completed,
        )
        logger.info {
            "[quests] ${cheat.player.displayName} completed ${result.completed} quests for " +
                "${target.displayName} (+${result.addedPoints} qp)"
        }
        val message =
            "Marked ${result.completed} quests complete (+${result.addedPoints} QP). " +
                "Relog to refresh the quest list."
        if (target === cheat.player) {
            cheat.player.mes(message)
        } else {
            cheat.player.mes("${target.displayName}: $message")
            target.mes("All quests have been marked complete. Relog to refresh.")
        }
    }

    /** Mirrors [Quest.setQuestStage]'s storage: stage map for varp-only quests, then the var. */
    private fun Player.finish(quest: Quest) {
        if (quest.questVarbit == null) {
            attr.getOrPut(QUEST_STAGE_MAP_ATTR) { mutableMapOf() }[quest.key] = quest.maxSteps
        }
        val questVar = quest.questVarbit ?: quest.questVarp
        if (vars[questVar] != quest.maxSteps) {
            VarPlayerIntMapSetter.set(this, questVar, quest.maxSteps)
        }
    }
}
