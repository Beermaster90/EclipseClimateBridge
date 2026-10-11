package fi.mitsukanny.phevbridge

import android.content.Context

/**
 * Phone numbers allowed to trigger the car via [PhevCallScreeningService]. Numbers
 * are normalized to digits-only, last 9 (enough to match a local number regardless of
 * +358/00358/leading-0 prefix differences) so "+358401234567" and "0401234567" match
 * the same allowlist entry.
 */
object CallAllowlist {
    private const val PREFS_NAME = "phevbridge_allowlist"
    private const val PREF_NUMBERS = "numbers"
    private const val PREF_ENABLED = "call_trigger_enabled"
    private const val PREF_MODE = "call_trigger_mode"
    private const val PREF_DURATION = "call_trigger_duration"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun normalize(number: String): String {
        val digits = number.filter { it.isDigit() }
        return if (digits.length > 9) digits.takeLast(9) else digits
    }

    fun all(context: Context): Set<String> = prefs(context).getStringSet(PREF_NUMBERS, emptySet()) ?: emptySet()

    fun add(context: Context, number: String) {
        val normalized = normalize(number)
        if (normalized.isEmpty()) return
        val updated = all(context) + normalized
        prefs(context).edit().putStringSet(PREF_NUMBERS, updated).apply()
    }

    fun remove(context: Context, number: String) {
        val updated = all(context) - normalize(number)
        prefs(context).edit().putStringSet(PREF_NUMBERS, updated).apply()
    }

    fun isAllowed(context: Context, number: String): Boolean = normalize(number) in all(context)

    fun isCallTriggerEnabled(context: Context): Boolean = prefs(context).getBoolean(PREF_ENABLED, false)
    fun setCallTriggerEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(PREF_ENABLED, enabled).apply()

    /** What an allowed call actually triggers -- defaults to heat, 10 min. */
    fun defaultMode(context: Context): Rc4CarClient.Mode =
        Rc4CarClient.Mode.entries.getOrElse(prefs(context).getInt(PREF_MODE, Rc4CarClient.Mode.HEAT.ordinal)) { Rc4CarClient.Mode.HEAT }

    fun defaultDuration(context: Context): Rc4CarClient.Duration =
        Rc4CarClient.Duration.entries.getOrElse(prefs(context).getInt(PREF_DURATION, Rc4CarClient.Duration.MIN10.ordinal)) { Rc4CarClient.Duration.MIN10 }

    fun setDefaultCommand(context: Context, mode: Rc4CarClient.Mode, duration: Rc4CarClient.Duration) {
        prefs(context).edit()
            .putInt(PREF_MODE, mode.ordinal)
            .putInt(PREF_DURATION, duration.ordinal)
            .apply()
    }
}
