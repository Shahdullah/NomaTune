/*
 * NomaTune (2026)
 * © Shahdullah — github.com/shahdullah
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.shahdullah.nomatune.repository

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json
import com.shahdullah.nomatune.models.NewsItem
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NewsRepository @Inject constructor() {

    private val client = HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(15, TimeUnit.SECONDS)
                readTimeout(15, TimeUnit.SECONDS)
                writeTimeout(15, TimeUnit.SECONDS)
                retryOnConnectionFailure(false)
            }
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Volatile private var metadataCache: List<NewsItem>? = null

    suspend fun fetchNews(): List<NewsItem> {
        return try {
            val response = client.get(METADATA_URL) {
                headers {
                    append(HttpHeaders.CacheControl, "no-cache, no-store, must-revalidate")
                    append(HttpHeaders.Pragma, "no-cache")
                    append(HttpHeaders.Expires, "0")
                }
            }
            val text = response.bodyAsText()
            val items = json.decodeFromString<List<NewsItem>>(text)
            metadataCache = items
            items
        } catch (e: Exception) {
            // Return static fallback news when the remote feed is unavailable
            val fallback = buildFallbackNews()
            metadataCache = fallback
            fallback
        }
    }

    suspend fun fetchNewsContent(id: String): String {
        return try {
            val response = client.get("$CONTENT_BASE_URL$id") {
                headers {
                    append(HttpHeaders.CacheControl, "no-cache, no-store, must-revalidate")
                    append(HttpHeaders.Pragma, "no-cache")
                    append(HttpHeaders.Expires, "0")
                }
            }
            response.bodyAsText()
        } catch (e: Exception) {
            getFallbackContent(id)
        }
    }

    fun getCachedItem(id: String): NewsItem? = metadataCache?.find { it.id == id }

    private fun buildFallbackNews(): List<NewsItem> = listOf(
        NewsItem(
            id = "dev_holiday_2026",
            title = "Development Pause — Developer on Holiday",
            description = "Development will be paused for a few weeks because the developer (Shahdullah) is on holiday. NomaTune will be back with new updates soon. Thank you for your patience and continued support!",
            author = "Shahdullah",
            timestamp = 1750809600L, // 2026-06-25
            important = true,
            imageUrls = emptyList(),
        ),
        NewsItem(
            id = "nomatune_intro_2026",
            title = "Welcome to NomaTune",
            description = "NomaTune is a modern Material 3 Expressive music player with YouTube Music integration, Spotify playlist sync, local file playback, synced lyrics, offline downloads, and much more. Built by Shahdullah.",
            author = "Shahdullah",
            timestamp = 1748131200L, // 2026-05-25
            important = false,
            imageUrls = emptyList(),
        ),
    )

    private fun getFallbackContent(id: String): String = when (id) {
        "dev_holiday_2026" -> """
# Development Pause

The developer (**Shahdullah**) is currently on holiday for a few weeks.

## What this means

- No new releases during this period
- Bug reports are still accepted via GitHub Issues
- The app continues to work normally — this only affects new updates

## When will development resume?

Development is expected to resume in a few weeks. Follow the GitHub repository for updates:

👉 **[github.com/Shahdullah/NomaTune](https://github.com/Shahdullah/NomaTune)**

Thank you for using NomaTune and for your patience!

— Shahdullah
        """.trimIndent()
        else -> "No content available for this article."
    }

    private companion object {
        const val METADATA_URL =
            "https://raw.githubusercontent.com/Shahdullah/NomaTuneNewsRepository/main/metadata.json"
        const val CONTENT_BASE_URL =
            "https://raw.githubusercontent.com/Shahdullah/NomaTuneNewsRepository/main/content/"
    }
}
