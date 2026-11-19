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

package com.shahdullah.nomatune.innertube.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.Locale

@Serializable
data class YouTubeClient(
    val clientName: String,
    val clientVersion: String,
    val clientId: String,
    val userAgent: String,
    val osName: String? = null,
    val osVersion: String? = null,
    val deviceMake: String? = null,
    val deviceModel: String? = null,
    val androidSdkVersion: String? = null,
    val buildId: String? = null,
    val cronetVersion: String? = null,
    val packageName: String? = null,
    val friendlyName: String? = null,
    val loginSupported: Boolean = false,
    val supportsCookieAuthentication: Boolean = false,
    val loginRequired: Boolean = false,
    val useSignatureTimestamp: Boolean = false,
    val useWebPoTokens: Boolean = false,
    val isEmbedded: Boolean = false,
) {
    fun toContext(
        locale: YouTubeLocale,
        visitorData: String?,
        dataSyncId: String?,
    ) = Context(
        client =
            Context.Client(
                clientName = clientName,
                clientVersion = clientVersion,
                osName = osName,
                osVersion = osVersion,
                deviceMake = deviceMake,
                deviceModel = deviceModel,
                androidSdkVersion = androidSdkVersion,
                gl = locale.gl,
                hl = locale.hl,
                visitorData = visitorData,
            ),
        user =
            Context.User(
                onBehalfOfUser = if (supportsCookieAuthentication) dataSyncId.delegatedSessionIdOrNull() else null,
            ),
    )

    fun requestOrigin(): String =
        when (clientName.uppercase(Locale.US)) {
            "WEB_REMIX" -> ORIGIN_YOUTUBE_MUSIC
            "MWEB" -> ORIGIN_YOUTUBE_MOBILE
            else -> ORIGIN_YOUTUBE
        }

    fun requestReferer(): String =
        when (clientName.uppercase(Locale.US)) {
            "WEB_REMIX" -> REFERER_YOUTUBE_MUSIC
            "MWEB" -> REFERER_YOUTUBE_MOBILE
            "TVHTML5", "TVHTML5_SIMPLY_EMBEDDED_PLAYER", "TVHTML5_SIMPLY" -> REFERER_YOUTUBE_TV
            else -> REFERER_YOUTUBE
        }

    fun requestApiUrl(endpoint: String): String = "${requestOrigin()}/youtubei/v1/$endpoint"

    companion object {
        const val USER_AGENT_WEB = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
        const val USER_AGENT_WEB_REMIX = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

        const val ORIGIN_YOUTUBE_MUSIC = "https://music.youtube.com"
        const val REFERER_YOUTUBE_MUSIC = "$ORIGIN_YOUTUBE_MUSIC/"
        const val API_URL_YOUTUBE_MUSIC = "$ORIGIN_YOUTUBE_MUSIC/youtubei/v1/"

        const val ORIGIN_YOUTUBE = "https://www.youtube.com"
        const val REFERER_YOUTUBE = "$ORIGIN_YOUTUBE/"
        const val ORIGIN_YOUTUBE_MOBILE = "https://m.youtube.com"
        const val REFERER_YOUTUBE_MOBILE = "$ORIGIN_YOUTUBE_MOBILE/"
        const val REFERER_YOUTUBE_TV = "$ORIGIN_YOUTUBE/tv"

        val WEB: YouTubeClient
            get() = profile("WEB")

        val WEB_PRIMARY: YouTubeClient
            get() = profile("WEB_PRIMARY")

        val WEB_REMIX: YouTubeClient
            get() = profile("WEB_REMIX")

        val WEB_CREATOR: YouTubeClient
            get() = profile("WEB_CREATOR")

        val TVHTML5: YouTubeClient
            get() = profile("TVHTML5")

        val TVHTML5_DOWNGRADED: YouTubeClient
            get() = profile("TVHTML5_DOWNGRADED")

        val TVHTML5_SIMPLY_EMBEDDED_PLAYER: YouTubeClient
            get() = profile("TVHTML5_SIMPLY_EMBEDDED_PLAYER")

        val TVHTML5_SIMPLY: YouTubeClient
            get() = profile("TVHTML5_SIMPLY")

        val IOS: YouTubeClient
            get() = profile("IOS")

        val MOBILE: YouTubeClient
            get() = profile("MOBILE")

        val ANDROID_VR_NO_AUTH: YouTubeClient
            get() = profile("ANDROID_VR_NO_AUTH")

        val ANDROID_VR_1_65_10: YouTubeClient
            get() = profile("ANDROID_VR_1_65_10")

        val ANDROID_VR_1_61_48: YouTubeClient
            get() = profile("ANDROID_VR_1_61_48")

        val ANDROID_VR_1_43_32: YouTubeClient
            get() = profile("ANDROID_VR_1_43_32")

        val ANDROID_CREATOR: YouTubeClient
            get() = profile("ANDROID_CREATOR")

        val VISIONOS: YouTubeClient
            get() = profile("VISIONOS")

        val IPADOS: YouTubeClient
            get() = profile("IPADOS")

        val MWEB: YouTubeClient
            get() = profile("MWEB")

        val WEB_SAFARI: YouTubeClient
            get() = profile("WEB_SAFARI")

        val WEB_EMBEDDED: YouTubeClient
            get() = profile("WEB_EMBEDDED")

        val WEB_MUSIC: YouTubeClient
            get() = profile("WEB_MUSIC")

        val ANDROID_MUSIC: YouTubeClient
            get() = profile("ANDROID_MUSIC")

        val ANDROID_TESTSUITE: YouTubeClient
            get() = profile("ANDROID_TESTSUITE")

        val ANDROID_UNPLUGGED: YouTubeClient
            get() = profile("ANDROID_UNPLUGGED")

        val IOS_MUSIC: YouTubeClient
            get() = profile("IOS_MUSIC")

        private fun profile(key: String): YouTubeClient =
            clientCatalog.profiles[key]
                ?: error("Missing YouTube client profile: $key")

        private val clientCatalog: YouTubeClientCatalog by lazy(LazyThreadSafetyMode.PUBLICATION) {
            loadClientCatalog()
        }
    }
}

@Serializable
private data class YouTubeClientCatalog(
    val profiles: Map<String, YouTubeClient>,
    val youtubeOriginClientNames: Set<String>,
    val upstreamClientIdentifiers: List<YouTubeClientIdentifier>,
)

@Serializable
private data class YouTubeClientIdentifier(
    val clientName: String,
    val clientVersion: String,
    val clientId: String,
)

private val clientCatalogJson = Json {
    ignoreUnknownKeys = false
}

private fun loadClientCatalog(): YouTubeClientCatalog {
    val resource =
        checkNotNull(YouTubeClient::class.java.getResourceAsStream("/innertube/youtube_clients.json")) {
            "Missing YouTube client catalog resource."
        }

    return resource.bufferedReader().use { reader ->
        clientCatalogJson.decodeFromString(reader.readText())
    }
}

private fun String?.delegatedSessionIdOrNull(): String? {
    val value = this?.trim()?.takeIf(String::isNotBlank) ?: return null
    val separatorIndex = value.indexOf("||")
    if (separatorIndex <= 0 || separatorIndex + 2 >= value.length) return null

    return value.substring(0, separatorIndex).trim().takeIf(String::isNotBlank)
}
