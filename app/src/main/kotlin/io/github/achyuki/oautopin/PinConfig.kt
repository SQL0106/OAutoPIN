package io.github.achyuki.oautopin

/**
 * The complete configuration shared by the settings UI and the hook.
 *
 * Two PINs are supported because a dual-SIM device can have two locked cards with
 * two different PINs. [slot1] / [slot2] are 1-based card slots ("卡1" / "卡2").
 *
 * Values are validated on the way in; anything that is not 4-8 digits is stored as
 * `null`, which simply disables automatic unlocking for that slot.
 */
internal data class PinConfig(
    val slot1: String? = null,
    val slot2: String? = null,
    /** Allow auto-unlock while the device is still in BFU (before the first unlock). */
    val allowBfu: Boolean = false,
) {

    val hasAnyPin: Boolean
        get() = slot1 != null || slot2 != null

    /**
     * Resolves the PIN to submit for a card slot.
     *
     * @param slot 0-based card slot, or `null` when the slot could not be determined.
     * @return the PIN to submit, or `null` when submitting would risk a wrong PIN
     *   (and therefore a burned retry out of the SIM's three).
     */
    fun pinForSlot(slot: Int?): String? = when (slot) {
        0 -> slot1 ?: slot2
        1 -> slot2 ?: slot1
        // Unknown slot: only safe when both slots share one PIN or exactly one is
        // configured. Two different PINs and no slot info means guessing.
        else -> if (slot1 != null && slot2 != null && slot1 != slot2) null else slot1 ?: slot2
    }

    companion object {
        val PIN_PATTERN = Regex("^\\d{4,8}$")

        fun sanitize(raw: String?): String? =
            raw?.trim()?.takeIf { PIN_PATTERN.matches(it) }
    }
}
