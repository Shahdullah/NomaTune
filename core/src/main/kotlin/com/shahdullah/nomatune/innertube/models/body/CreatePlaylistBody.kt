/*
 * NomaTune (2026)
 * © Shahdullah — github.com/shahdullah
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.shahdullah.nomatune.innertube.models.body

import com.shahdullah.nomatune.innertube.models.Context
import kotlinx.serialization.Serializable

@Serializable
data class CreatePlaylistBody(
    val context: Context,
    val title: String,
    val videoIds: List<String>,
    val privacyStatus: String = PrivacyStatus.PRIVATE
) {
    object PrivacyStatus {
        const val PRIVATE = "PRIVATE"
        const val PUBLIC = "PUBLIC"
        const val UNLISTED = "UNLISTED"
    }
}
