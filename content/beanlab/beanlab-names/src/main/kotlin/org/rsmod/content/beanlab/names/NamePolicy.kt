package org.rsmod.content.beanlab.names

import dev.or2.central.display.DisplayNameFormatResult
import dev.or2.central.display.DisplayNamePlayerChangeResult
import dev.or2.central.display.DisplayNamePolicy

private const val MILLIS_PER_DAY = 86_400_000L

internal const val USAGE = "Usage: ::name <new name> (up to 12 letters, numbers, - or _)"
internal const val TAKEN = "That display name is already taken."

/**
 * Central's player rename rules ([DisplayNamePolicy.validatePlayerChange]): format, no change,
 * ban, the rename cooldown, then held names. Staff skip the cooldown. Central's bad-word list is
 * off on Beanscape, so no roots are passed. Central's sanitizer silently drops characters it
 * doesn't allow (spaces too), so a name it would change is refused instead.
 */
internal fun checkRename(
    raw: String,
    current: String?,
    lastChangedAtMillis: Long?,
    nowMillis: Long,
    staff: Boolean,
    heldUntilMillis: Long?,
): DisplayNamePlayerChangeResult =
    if (raw.isNotEmpty() && DisplayNamePolicy.sanitize(raw) != raw) {
        DisplayNamePlayerChangeResult.InvalidFormat(DisplayNameFormatResult.InvalidLoginCharacters)
    } else {
        policyCheck(raw, current, lastChangedAtMillis, nowMillis, staff, heldUntilMillis)
    }

private fun policyCheck(
    raw: String,
    current: String?,
    lastChangedAtMillis: Long?,
    nowMillis: Long,
    staff: Boolean,
    heldUntilMillis: Long?,
): DisplayNamePlayerChangeResult =
    DisplayNamePolicy.validatePlayerChange(
        raw,
        current,
        lastChangedAtMillis,
        null,
        nowMillis,
        staff,
        heldUntilMillis,
        emptySet(),
    )

/** A hold on a name blocks everyone but the character that gave it up (they may take it back). */
internal fun blockingHold(holdUntilMillis: Long?, holder: Int?, characterId: Int): Long? =
    holdUntilMillis?.takeIf { holder != characterId }

/** The player-facing reason a rename was refused, or null when it was accepted. */
internal fun refusal(
    result: DisplayNamePlayerChangeResult,
    lastChangedAtMillis: Long?,
    nowMillis: Long,
): String? =
    when (result) {
        is DisplayNamePlayerChangeResult.Accepted -> null
        is DisplayNamePlayerChangeResult.NoChange -> "That is already your display name."
        is DisplayNamePlayerChangeResult.Banned -> "You can't change your name right now."
        is DisplayNamePlayerChangeResult.NameHeld -> "That name was given up recently and is held."
        is DisplayNamePlayerChangeResult.CooldownActive,
        is DisplayNamePlayerChangeResult.BondRequiredForRenameWindow ->
            "You can change your name again in " +
                "${daysUntilRename(lastChangedAtMillis, nowMillis)} day(s)."
        is DisplayNamePlayerChangeResult.InvalidFormat -> formatRefusal(result.reason)
        else -> "That name isn't allowed."
    }

private fun formatRefusal(reason: DisplayNameFormatResult): String =
    when (reason) {
        is DisplayNameFormatResult.Empty -> USAGE
        is DisplayNameFormatResult.TooLong ->
            "Names can be at most ${DisplayNamePolicy.MAX_LENGTH} characters."
        is DisplayNameFormatResult.ContainsMod -> "Names can't contain \"mod\"."
        is DisplayNameFormatResult.Deceptive -> "That name looks like a staff or official name."
        is DisplayNameFormatResult.InvalidLoginCharacters ->
            "Use letters, numbers, - or _ only (no spaces)."
        else -> "That name isn't allowed."
    }

/** Whole days, rounded up, until [DisplayNamePolicy]'s rename window opens again. */
internal fun daysUntilRename(lastChangedAtMillis: Long?, nowMillis: Long): Long {
    if (lastChangedAtMillis == null) return 0
    val left =
        lastChangedAtMillis + DisplayNamePolicy.BOND_OR_STAFF_RENAME_WINDOW_MILLIS - nowMillis
    return if (left <= 0) 0 else (left + MILLIS_PER_DAY - 1) / MILLIS_PER_DAY
}
