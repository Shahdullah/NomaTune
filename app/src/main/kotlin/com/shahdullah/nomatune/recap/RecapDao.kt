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

package com.shahdullah.nomatune.recap

import androidx.room.Dao
import androidx.room.Query

// ArchiveTune filters on s.isPodcast and on blocked artists (artist.blockedAt).
// NomaTune has neither column nor a podcast/blocked-artist feature, so only the
// play-time guard is kept.
private const val EligibleMusic = """
    e.playTime > 0
"""

private const val RecapPeriod = "e.timestamp >= :start AND e.timestamp < :end AND " + EligibleMusic

@Dao
interface RecapDao {
    @Query("SELECT MIN(e.timestamp) FROM event e JOIN song s ON s.id = e.songId WHERE " + EligibleMusic)
    suspend fun firstTimestamp(): Long?

    @Query("""
        SELECT COALESCE(SUM(e.playTime), 0) AS milliseconds,
               COUNT(*) AS plays, COUNT(DISTINCT s.id) AS tracks
        FROM event e JOIN song s ON s.id = e.songId WHERE
    """ + RecapPeriod)
    suspend fun totals(start: Long, end: Long): RecapTotalsRow

    @Query("""
        SELECT s.id, s.title, s.thumbnailUrl AS artwork,
               COALESCE((SELECT GROUP_CONCAT(artist.name, ', ')
                         FROM song_artist_map names JOIN artist ON artist.id = names.artistId
                         WHERE names.songId = s.id), '') AS subtitle,
               SUM(e.playTime) AS milliseconds, COUNT(*) AS plays
        FROM event e JOIN song s ON s.id = e.songId WHERE
    """ + RecapPeriod + " GROUP BY s.id ORDER BY milliseconds DESC, plays DESC, s.id ASC LIMIT 5")
    suspend fun songs(start: Long, end: Long): List<RecapRankedRow>

    @Query("""
        SELECT a.id, a.name AS title, '' AS subtitle, a.thumbnailUrl AS artwork,
               SUM(e.playTime) AS milliseconds, COUNT(*) AS plays
        FROM event e JOIN song s ON s.id = e.songId
        JOIN song_artist_map map ON map.songId = s.id JOIN artist a ON a.id = map.artistId
        WHERE
    """ + RecapPeriod + " GROUP BY a.id ORDER BY milliseconds DESC, plays DESC, a.id ASC LIMIT 5")
    suspend fun artists(start: Long, end: Long): List<RecapRankedRow>

    @Query("""
        SELECT CAST(strftime('%m', e.timestamp / 1000, 'unixepoch') AS INTEGER) AS month,
               a.id, a.name AS title, a.thumbnailUrl AS artwork,
               SUM(e.playTime) AS milliseconds, COUNT(*) AS plays
        FROM event e JOIN song s ON s.id = e.songId
        JOIN song_artist_map map ON map.songId = s.id JOIN artist a ON a.id = map.artistId
        WHERE
    """ + RecapPeriod + " GROUP BY month, a.id ORDER BY month, milliseconds DESC, plays DESC, a.id ASC")
    suspend fun monthlyArtists(start: Long, end: Long): List<RecapMonthlyArtistRow>

    @Query("""
        SELECT a.id, a.title, a.thumbnailUrl AS artwork,
               COALESCE((SELECT GROUP_CONCAT(artist.name, ', ')
                         FROM album_artist_map names JOIN artist ON artist.id = names.artistId
                         WHERE names.albumId = a.id), '') AS subtitle,
               SUM(e.playTime) AS milliseconds, COUNT(*) AS plays
        FROM event e JOIN song s ON s.id = e.songId
        JOIN song_album_map map ON map.songId = s.id JOIN album a ON a.id = map.albumId
        WHERE
    """ + RecapPeriod + """
        GROUP BY a.id ORDER BY milliseconds DESC, plays DESC, a.id ASC LIMIT 5
    """)
    suspend fun albums(start: Long, end: Long): List<RecapRankedRow>

    @Query("""
        SELECT strftime('%Y-%m-%d', e.timestamp / 1000, 'unixepoch') AS day,
               CAST(strftime('%H', e.timestamp / 1000, 'unixepoch') AS INTEGER) AS hour,
               SUM(e.playTime) AS milliseconds
        FROM event e JOIN song s ON s.id = e.songId WHERE
    """ + RecapPeriod + " GROUP BY day, hour ORDER BY day, hour")
    suspend fun dayHours(start: Long, end: Long): List<RecapDayHourRow>
}
