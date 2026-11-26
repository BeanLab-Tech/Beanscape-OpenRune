package org.rsmod.content.beanlab.admin.api

import org.rsmod.map.CoordGrid

/**
 * BeanLab teleport catalog. Plain data: edit a line, rebuild, restart.
 *
 * Coordinates are (x, z, level) in standard OSRS world coords — the same numbers RuneLite's "World
 * location" / ::mypos show. If a spot lands you somewhere odd, stand where you want it, run
 * ::mypos, and paste the x/z/level here.
 */
public data class TeleportDestination(
    val name: String,
    val x: Int,
    val z: Int,
    val level: Int = 0,
) {
    val coords: CoordGrid
        get() = CoordGrid(x, z, level)
}

public data class TeleportCategory(val name: String, val destinations: List<TeleportDestination>)

private fun d(name: String, x: Int, z: Int, level: Int = 0) = TeleportDestination(name, x, z, level)

public object TeleportCatalog {
    public val categories: List<TeleportCategory> =
        listOf(
            TeleportCategory(
                "Cities",
                listOf(
                    d("Lumbridge", 3222, 3218),
                    d("Varrock", 3212, 3424),
                    d("Falador", 2965, 3380),
                    d("Edgeville", 3087, 3496),
                    d("Draynor Village", 3093, 3247),
                    d("Al Kharid", 3293, 3178),
                    d("Port Sarim", 3023, 3208),
                    d("Rimmington", 2957, 3214),
                    d("Camelot", 2757, 3478),
                    d("Seers' Village", 2726, 3485),
                    d("Catherby", 2808, 3434),
                    d("East Ardougne", 2662, 3305),
                    d("Yanille", 2606, 3093),
                    d("Taverley", 2895, 3443),
                    d("Burthorpe", 2899, 3544),
                    d("Rellekka", 2643, 3676),
                    d("Canifis", 3493, 3488),
                    d("Port Phasmatys", 3687, 3502),
                    d("Brimhaven", 2760, 3178),
                    d("Shilo Village", 2852, 2955),
                    d("Tree Gnome Stronghold", 2461, 3444),
                    d("Lletya", 2341, 3171),
                    d("Pollnivneach", 3359, 2970),
                    d("Nardah", 3428, 2916),
                    d("Prifddinas", 3239, 6075),
                    d("Kourend Castle", 1643, 3673),
                    d("Hosidius", 1744, 3517),
                    d("Arceuus", 1712, 3883),
                    d("Lovakengj", 1504, 3826),
                    d("Port Piscarilius", 1803, 3748),
                ),
            ),
            TeleportCategory(
                "Important areas",
                listOf(
                    d("Grand Exchange", 3164, 3486),
                    d("Edgeville bank", 3094, 3491),
                    d("Ferox Enclave", 3130, 3631),
                    d("Wilderness (north of Edgeville)", 3087, 3524),
                    d("Mage Bank", 2538, 4716),
                    d("TzHaar City", 2480, 5175),
                    d("Slayer Tower", 3428, 3537),
                    d("Catacombs of Kourend", 1666, 10050),
                    d("Taverley Dungeon", 2884, 9798),
                    d("Brimhaven Dungeon", 2713, 9564),
                    d("Fremennik Slayer Dungeon", 2808, 10002),
                    d("Edgeville Dungeon", 3097, 9868),
                ),
            ),
            TeleportCategory(
                "Bosses & raids",
                listOf(
                    d("God Wars Dungeon", 2882, 5311, 2),
                    d("Zulrah (Zul-Andra)", 2200, 3055),
                    d("King Black Dragon", 2271, 4680),
                    d("Corporeal Beast", 2966, 4383, 2),
                    d("Dagannoth Kings", 2899, 4449),
                    d("Giant Mole", 1752, 5232),
                    d("Barrows", 3565, 3289),
                    d("Chaos Elemental", 3271, 3913),
                    d("TzTok-Jad (Fight Caves)", 2438, 5168),
                    d("Kalphite Queen (lair entrance)", 3228, 3108),
                    d("Chambers of Xeric", 1233, 3565),
                    d("Theatre of Blood", 3650, 3219),
                ),
            ),
            TeleportCategory(
                "NPCs",
                listOf(
                    d("Turael (Slayer, Burthorpe)", 2931, 3536),
                    d("Mazchna (Slayer, Canifis)", 3510, 3507),
                    d("Vannaka (Slayer, Edgeville Dungeon)", 3146, 9914),
                    d("Nieve (Slayer, Gnome Stronghold)", 2432, 3423),
                    d("Duradel (Slayer, Shilo Village)", 2869, 2982),
                    d("Konar (Slayer, Mount Karuulm)", 1308, 3786),
                    d("Krystilia (Slayer, Edgeville)", 3109, 3514),
                    d("Wise Old Man (Draynor)", 3088, 3253),
                    d("Hans (Lumbridge Castle)", 3221, 3219),
                    d("Aubury (Varrock rune shop)", 3253, 3401),
                    d("Zaff (Varrock staffs)", 3203, 3434),
                    d("Horvik (Varrock armour)", 3229, 3437),
                    d("Thessalia (Varrock clothes)", 3206, 3417),
                    d("Oziach (Edgeville)", 3068, 3516),
                    d("Make-over Mage", 2919, 3322),
                ),
            ),
            TeleportCategory(
                "Minigames",
                listOf(
                    d("Pest Control", 2662, 2655),
                    d("Castle Wars", 2442, 3090),
                    d("Barbarian Assault", 2531, 3577),
                    d("Warriors' Guild", 2880, 3546),
                    d("Wintertodt", 1630, 3944),
                    d("Tempoross", 3141, 2840),
                    d("Nightmare Zone", 2609, 3115),
                    d("TzHaar Fight Pits", 2399, 5178),
                    d("Fishing Trawler", 2676, 3170),
                    d("Mage Training Arena", 3363, 3318),
                ),
            ),
            TeleportCategory(
                "Skilling",
                listOf(
                    d("Woodcutting Guild", 1658, 3505),
                    d("Fishing Guild", 2611, 3392),
                    d("Mining Guild", 3046, 9756),
                    d("Crafting Guild", 2933, 3285),
                    d("Cooking Guild", 3143, 3442),
                    d("Farming Guild", 1249, 3718),
                    d("Blast Furnace", 1940, 4958),
                    d("Catherby fishing", 2837, 3436),
                    d("Varrock east mine", 3285, 3365),
                    d("Al Kharid mine", 3300, 3300),
                    d("Edgeville yews", 3086, 3480),
                    d("Seers' maples", 2727, 3502),
                    d("Gnome Stronghold agility", 2474, 3438),
                    d("Draynor rooftop agility", 3103, 3279),
                    d("Seers' rooftop agility", 2729, 3488),
                    d("Canifis rooftop agility", 3507, 3488),
                    d("Ardougne rooftop agility", 2673, 3297),
                    d("Barbarian Outpost agility", 2552, 3556),
                    d("Wilderness agility", 2998, 3916),
                ),
            ),
        )

    public val all: List<TeleportDestination> = categories.flatMap { it.destinations }

    public fun search(query: String): List<TeleportDestination> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return all.filter { it.name.lowercase().contains(q) }.distinctBy { it.name }
    }
}
