/*
 * NomaTune (2026)
 * © Shahdullah — github.com/Shahdullah
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.shahdullah.nomatune.cast

import android.content.Context

object CastPlaybackRepositoryLocator {
    @Volatile private var repository: CastPlaybackRepository? = null

    fun get(context: Context): CastPlaybackRepository =
        repository ?: synchronized(this) {
            repository ?: DefaultCastPlaybackRepository(context.applicationContext).also { repository = it }
        }
}
