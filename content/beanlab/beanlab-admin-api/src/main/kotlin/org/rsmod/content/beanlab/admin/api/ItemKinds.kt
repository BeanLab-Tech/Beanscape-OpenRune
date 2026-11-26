package org.rsmod.content.beanlab.admin.api

import dev.openrune.types.ItemServerType

/**
 * Sorts items into what an admin can actually use them for, so the console can list working items
 * first and hide the rest. The cache has every item ever drawn, including quest leftovers, event
 * relics, cut content and test objects; most of those do nothing and should not clutter a search.
 */
public object ItemKinds {
    public const val EQUIPMENT: String = "equipment"
    public const val CONSUMABLE: String = "consumable"
    public const val USABLE: String = "usable"
    public const val MISC: String = "misc"
    public const val JUNK: String = "junk"

    private val CONSUMABLE_OPS = setOf("eat", "drink")
    private val PLAIN_OPS = setOf("drop", "use", "examine", "destroy", "discard", "release", "take")
    private val JUNK_NAME =
        Regex(
            """\b(null|unused|dummy|placeholder)\b|\((historical|beta|unobtainable|deadman|broken)\)""",
            RegexOption.IGNORE_CASE,
        )

    /** True for names the cache uses for objects that are not real items. */
    public fun isJunkName(name: String): Boolean = JUNK_NAME.containsMatchIn(name)

    public fun classify(type: ItemServerType): String {
        val ops =
            type.interfaceOptions.mapNotNull { it?.trim()?.lowercase() }.filter { it.isNotEmpty() }
        return when {
            isJunkName(type.name) || type.isDummyItem -> JUNK
            type.isEquipable -> EQUIPMENT
            ops.any { it in CONSUMABLE_OPS } -> CONSUMABLE
            ops.any { it !in PLAIN_OPS } -> USABLE
            // Nothing to do with it, cannot be traded, and worth nothing: a leftover.
            !type.tradeable && !type.stockmarket && type.cost <= 0 -> JUNK
            else -> MISC
        }
    }
}
