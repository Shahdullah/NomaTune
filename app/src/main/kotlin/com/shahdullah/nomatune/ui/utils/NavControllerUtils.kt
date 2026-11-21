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

package com.shahdullah.nomatune.ui.utils

import android.net.Uri
import androidx.navigation.NavController
import com.shahdullah.nomatune.ui.screens.Screens

fun NavController.backToMain() {
    val mainRoutes = Screens.MainScreens.map { it.route }

    while (previousBackStackEntry != null &&
        currentBackStackEntry?.destination?.route !in mainRoutes
    ) {
        popBackStack()
    }
}

/*
 * Ported from ArchiveTune's ui/utils/NavControllerUtils.kt.
 * ArchiveTune also redirects podcast browse ids to a "podcast" route via
 * PODCAST_SHOW_BROWSE_PREFIX; NomaTune has no podcast browse route, so that
 * branch is intentionally omitted rather than importing a constant that does
 * not exist here.
 */

internal fun NavController.navigateToArtist(artistId: String?) {
    navigateToMediaDetail(artistId, "artist")
}

internal fun NavController.navigateToAlbum(albumId: String?) {
    navigateToMediaDetail(albumId, "album")
}

private fun NavController.navigateToMediaDetail(
    id: String?,
    destination: String,
) {
    if (id.isNullOrBlank()) return
    navigate("$destination/${Uri.encode(id)}")
}
