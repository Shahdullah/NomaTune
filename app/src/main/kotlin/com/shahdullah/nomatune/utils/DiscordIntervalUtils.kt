/*
 * NomaTune (2026)
 * © Shahdullah — github.com/shahdullah
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.shahdullah.nomatune.utils

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import com.shahdullah.nomatune.constants.DiscordPresenceIntervalUnitKey
import com.shahdullah.nomatune.constants.DiscordPresenceIntervalValueKey
import com.shahdullah.nomatune.utils.dataStore

fun getPresenceIntervalMillis(context: Context): Long {
    val intervalPreset = context.dataStore[stringPreferencesKey("discordPresenceIntervalPreset")] ?: "20s"
    val customValue = (context.dataStore[DiscordPresenceIntervalValueKey] as? Int) ?: 30
    val customUnit = (context.dataStore[DiscordPresenceIntervalUnitKey] as? String) ?: "S"

    return when (intervalPreset) {
        "Disabled" -> 0L // no throttling
        "20s" -> 20_000L
        "50s" -> 50_000L
        "1m" -> 60_000L
        "5m" -> 300_000L
        "Custom" -> {
            val safeValue = if (customUnit == "S" && customValue < 30) 30 else customValue
            val multiplier = when (customUnit) {
                "M" -> 60_000L
                "H" -> 3_600_000L
                else -> 1_000L
            }
            safeValue * multiplier
        }
        else -> 20_000L
    }
}

