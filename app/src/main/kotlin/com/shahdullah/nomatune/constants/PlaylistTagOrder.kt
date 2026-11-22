/*
 * NomaTune (2026)
 * © Shahdullah — github.com/shahdullah
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Based on ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.shahdullah.nomatune.constants

/**
 * Restores a saved playlist-tag order, dropping tags that no longer exist and
 * appending ones that were added since — so newly introduced tags always appear.
 */
fun String.toPlaylistTagOrder(availableTagIds: List<String>): List<String> {
    val availableIds = availableTagIds.toSet()
    val savedOrder =
        split(",")
            .map(String::trim)
            .filter { it.isNotEmpty() && it in availableIds }
            .distinct()

    return savedOrder + availableTagIds.filterNot(savedOrder::contains)
}

fun List<String>.toPlaylistTagPreference(): String = distinct().joinToString(",")
