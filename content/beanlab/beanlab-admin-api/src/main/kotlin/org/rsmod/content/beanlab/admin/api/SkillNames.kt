package org.rsmod.content.beanlab.admin.api

/**
 * The API names skills the way the RuneLite client does, so the console can match levels to its
 * skill icons. RsMod's display names differ in one place.
 */
public object SkillNames {
    private val renamed = mapOf("runecrafting" to "Runecraft")

    /** The name the API sends and accepts for an RsMod stat display name. */
    public fun toApi(displayName: String): String = renamed[displayName.lowercase()] ?: displayName
}
