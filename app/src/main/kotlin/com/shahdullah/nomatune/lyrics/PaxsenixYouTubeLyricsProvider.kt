/*
 * NomaTune (2026)
 * © Shahdullah — github.com/shahdullah
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.shahdullah.nomatune.lyrics

import android.content.Context
import com.shahdullah.nomatune.constants.EnablePaxsenixYouTubeLyricsKey
import com.shahdullah.nomatune.paxsenix.PaxsenixLyrics
import com.shahdullah.nomatune.utils.dataStore
import com.shahdullah.nomatune.utils.get

object PaxsenixYouTubeLyricsProvider : LyricsProvider {
    override val name = "Paxsenix: YouTube"

    override fun isEnabled(context: Context): Boolean = context.dataStore[EnablePaxsenixYouTubeLyricsKey] ?: true

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): Result<String> = PaxsenixLyrics.getYouTubeLyrics(title, artist, duration)

    override suspend fun getAllLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
        callback: (String) -> Unit,
    ) {
        getLyrics(id, title, artist, album, duration).onSuccess(callback)
    }
}
