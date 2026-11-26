package org.rsmod.content.beanlab.admin.api

/**
 * Discord profile pictures for accounts created through Discord registration.
 *
 * The web service stores the avatar hash Discord gives at sign-in (`accounts.discord_avatar`). The
 * admin console turns that into a CDN link. An account that is linked to Discord but has no stored
 * hash (older accounts, or a user who never set a picture) gets Discord's default avatar for their
 * id, so the console always has something real to show. Accounts with no Discord link get an empty
 * string and the console draws an initial instead.
 */
public object DiscordAvatars {
    private const val CDN: String = "https://cdn.discordapp.com"
    private const val DEFAULT_AVATARS: Long = 6
    private const val SNOWFLAKE_SHIFT: Int = 22
    private val HASH = Regex("[A-Za-z0-9_]{1,64}")

    public fun url(discordId: Long?, avatarHash: String?): String =
        when {
            discordId == null || discordId <= 0 -> ""
            avatarHash != null && HASH.matches(avatarHash) ->
                "$CDN/avatars/$discordId/$avatarHash.png?size=128"
            else -> "$CDN/embed/avatars/${(discordId shr SNOWFLAKE_SHIFT) % DEFAULT_AVATARS}.png"
        }
}
