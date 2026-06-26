/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/Shahdullah
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.shahdullah.nomatune.library

import android.content.Context
import com.google.common.collect.ImmutableList
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import com.shahdullah.nomatune.ai.AiServiceConfig
import com.shahdullah.nomatune.ai.AiTextService
import com.shahdullah.nomatune.constants.AiApiKeyKey
import com.shahdullah.nomatune.constants.AiApiValidationStatus
import com.shahdullah.nomatune.constants.AiApiValidationStatusKey
import com.shahdullah.nomatune.constants.AiCustomEndpointKey
import com.shahdullah.nomatune.constants.AiCustomModelKey
import com.shahdullah.nomatune.constants.AiProvider
import com.shahdullah.nomatune.constants.AiProviderKey
import com.shahdullah.nomatune.constants.AiSelectedModelKey
import com.shahdullah.nomatune.db.entities.Song
import com.shahdullah.nomatune.extensions.toEnum
import com.shahdullah.nomatune.innertube.YouTube
import com.shahdullah.nomatune.innertube.models.SongItem
import com.shahdullah.nomatune.models.MediaMetadata
import com.shahdullah.nomatune.models.toMediaMetadata
import com.shahdullah.nomatune.repository.LibraryTopMixRepository
import com.shahdullah.nomatune.utils.dataStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import javax.inject.Inject

private const val TopMixCountLimit = 5
private const val TopMixCandidateLimit = 100
private const val TopMixSongsPerMix = 25
private const val TopMixPromptCandidateLimit = 80

class RefreshLibraryTopMixesUseCase
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val repository: LibraryTopMixRepository,
    ) {
        suspend operator fun invoke(): RefreshLibraryTopMixesResult {
            val config = readAiConfig()
            if (!config.canCallApi || isAiValidationFailed()) {
                return RefreshLibraryTopMixesResult.Failure(TopMixGenerationFailure.AI_NOT_CONFIGURED)
            }

            val candidates =
                repository
                    .recentSongsForTopMixes(TopMixCandidateLimit)
                    .validateWithYtm()
                    .take(TopMixPromptCandidateLimit)
            if (candidates.isEmpty()) {
                return RefreshLibraryTopMixesResult.Failure(TopMixGenerationFailure.NO_RECENT_HISTORY)
            }

            return runCatching {
                val mixes = requestAiMixes(config = config, candidates = candidates)
                if (mixes.isEmpty()) {
                    RefreshLibraryTopMixesResult.Failure(TopMixGenerationFailure.NO_VALID_MIXES)
                } else {
                    repository.replaceTopMixes(mixes)
                    RefreshLibraryTopMixesResult.Success
                }
            }.getOrElse { throwable ->
                RefreshLibraryTopMixesResult.Failure(
                    reason = TopMixGenerationFailure.AI_REQUEST_FAILED,
                    cause = throwable,
                )
            }
        }

        private suspend fun readAiConfig(): AiServiceConfig {
            val prefs = context.dataStore.data.first()
            val provider = prefs[AiProviderKey].toEnum(AiProvider.NONE)
            return AiServiceConfig(
                provider = provider,
                apiKey = prefs[AiApiKeyKey].orEmpty(),
                customEndpoint = prefs[AiCustomEndpointKey].orEmpty(),
                model =
                    if (provider == AiProvider.CUSTOM) {
                        prefs[AiCustomModelKey].orEmpty()
                    } else {
                        prefs[AiSelectedModelKey].orEmpty()
                    },
            )
        }

        private suspend fun isAiValidationFailed(): Boolean =
            context.dataStore.data
                .first()[AiApiValidationStatusKey]
                .toEnum(AiApiValidationStatus.UNKNOWN) == AiApiValidationStatus.FAILED

        private suspend fun requestAiMixes(
            config: AiServiceConfig,
            candidates: List<ValidatedTopMixSong>,
        ): List<GeneratedLibraryTopMix> {
            val candidateById = candidates.associateBy { it.id }
            val candidatePayload =
                JSONArray().apply {
                    candidates.forEachIndexed { index, candidate ->
                        put(
                            JSONObject()
                                .put("id", candidate.id)
                                .put("title", candidate.title)
                                .put("artists", candidate.artists.joinToString(", "))
                                .put("recentRank", index + 1),
                        )
                    }
                }
            val response =
                AiTextService.complete(
                    config = config,
                    systemPrompt =
                        """
                        You are a music curator for ArchiveTune.
                        Build up to $TopMixCountLimit personal mixes using only the provided candidate song IDs.
                        Return JSON only with this schema: {"mixes":[{"title":"short title containing Mix","description":"short genre and mood description","songIds":["id"]}]}.
                        Every title must contain the word Mix.
                        Every description must describe the genre, mood, or sound of the selected songs.
                        Select at most $TopMixSongsPerMix songs per mix, avoid duplicate songs inside a mix, and prioritize coherence.
                        The mixes must be related to the user's recent listening history represented by the candidate list.
                        """.trimIndent(),
                    userPrompt =
                        JSONObject()
                            .put("basis", "recent listening history")
                            .put("maxMixes", TopMixCountLimit)
                            .put("maxSongsPerMix", TopMixSongsPerMix)
                            .put("candidates", candidatePayload)
                            .toString(),
                    temperature = 0.35,
                    maxTokens = 4096,
                )
            return parseGeneratedMixes(
                response = response,
                candidateById = candidateById,
            )
        }

        private fun parseGeneratedMixes(
            response: String,
            candidateById: Map<String, ValidatedTopMixSong>,
        ): List<GeneratedLibraryTopMix> {
            val json = JSONObject(response.substringAfter('{').substringBeforeLast('}').let { "{$it}" })
            val globalSongIds = LinkedHashSet<String>()
            val mixes = json.optJSONArray("mixes") ?: JSONArray()
            return buildList {
                for (index in 0 until mixes.length()) {
                    if (size >= TopMixCountLimit) break
                    val mixJson = mixes.optJSONObject(index) ?: continue
                    val selected =
                        mixJson
                            .optJSONArray("songIds")
                            .toStringList()
                            .mapNotNull(candidateById::get)
                            .filter { globalSongIds.add(it.id) }
                            .distinctBy { it.id }
                            .take(TopMixSongsPerMix)
                    if (selected.isEmpty()) continue

                    add(
                        GeneratedLibraryTopMix(
                            id = "ai_top_mix_${System.currentTimeMillis()}_$index",
                            title = mixJson.optString("title").sanitizeMixTitle(),
                            description = mixJson.optString("description").sanitizeMixDescription(),
                            tracks = ImmutableList.copyOf(selected.map { it.mediaMetadata }),
                        ),
                    )
                }
            }
        }
    }

sealed interface RefreshLibraryTopMixesResult {
    data object Success : RefreshLibraryTopMixesResult

    data class Failure(
        val reason: TopMixGenerationFailure,
        val cause: Throwable? = null,
    ) : RefreshLibraryTopMixesResult
}

enum class TopMixGenerationFailure {
    AI_NOT_CONFIGURED,
    NO_RECENT_HISTORY,
    NO_VALID_MIXES,
    AI_REQUEST_FAILED,
}

private data class ValidatedTopMixSong(
    val ytmSong: SongItem,
) {
    val id: String
        get() = ytmSong.id
    val title: String
        get() = ytmSong.title
    val artists: List<String>
        get() = ytmSong.artists.map { it.name }
    val mediaMetadata: MediaMetadata
        get() = ytmSong.toMediaMetadata()
}

private suspend fun List<Song>.validateWithYtm(): List<ValidatedTopMixSong> {
    val localSongs = distinctBy { it.id }
    return buildList {
        localSongs.forEach { song ->
            val validatedSong = song.validateWithYtm()
            if (validatedSong != null) {
                add(validatedSong)
            }
        }
    }
}

private suspend fun Song.validateWithYtm(): ValidatedTopMixSong? {
    val query = ytmValidationQuery()
    if (query.isBlank()) return null
    val songs =
        YouTube
            .search(query, YouTube.SearchFilter.FILTER_SONG)
            .getOrNull()
            ?.items
            .orEmpty()
            .filterIsInstance<SongItem>()
    val match =
        songs.firstOrNull { it.id == id }
            ?: songs.firstOrNull { it.matchesLocalIdentity(this) }
    return match?.let { songItem ->
        ValidatedTopMixSong(
            ytmSong = songItem,
        )
    }
}

private fun Song.ytmValidationQuery(): String =
    buildString {
        append(song.title)
        val artistNames = artists.joinToString(" ") { it.name }
        if (artistNames.isNotBlank()) {
            append(' ')
            append(artistNames)
        }
    }.trim()

private fun SongItem.matchesLocalIdentity(song: Song): Boolean =
    title.matchesComparableTitle(song.song.title) && artists.matchesAnyLocalArtist(song)

private fun String.matchesComparableTitle(other: String): Boolean {
    val self = normalizedForMixMatch()
    val target = other.normalizedForMixMatch()
    return self.isNotBlank() &&
        target.isNotBlank() &&
        (self == target || (self.length > 3 && target.contains(self)) || (target.length > 3 && self.contains(target)))
}

private fun List<com.shahdullah.nomatune.innertube.models.Artist>.matchesAnyLocalArtist(song: Song): Boolean {
    val remoteArtists = map { it.name.normalizedForMixMatch() }.filter { it.isNotBlank() }
    val localArtists = song.artists.map { it.name.normalizedForMixMatch() }.filter { it.isNotBlank() }
    if (remoteArtists.isEmpty() || localArtists.isEmpty()) return false
    return localArtists.any { localArtist ->
        remoteArtists.any { remoteArtist ->
            localArtist == remoteArtist ||
                (localArtist.length > 3 && remoteArtist.contains(localArtist)) ||
                (remoteArtist.length > 3 && localArtist.contains(remoteArtist))
        }
    }
}

private fun JSONArray?.toStringList(): List<String> =
    if (this == null) {
        emptyList()
    } else {
        List(length()) { index -> optString(index) }.filter { it.isNotBlank() }
    }

private fun String.sanitizeMixTitle(): String {
    val title =
        lineSequence()
            .firstOrNull()
            .orEmpty()
            .trim()
            .take(80)
            .ifBlank { "Recent Listening Mix" }
    return if (title.contains("mix", ignoreCase = true)) title else "$title Mix"
}

private fun String.sanitizeMixDescription(): String =
    lineSequence()
        .firstOrNull()
        .orEmpty()
        .trim()
        .take(120)
        .ifBlank { "A genre-focused mix based on your recent listening history." }

private fun String.normalizedForMixMatch(): String =
    lowercase(Locale.getDefault())
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
