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
package com.shahdullah.nomatune.morideobfuscator.youtubei

import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest

internal class YoutubeiRequestAuthentication private constructor(
    private val cookie: String,
    private val delegatedSessionId: String?,
) {
    private val cookies =
        cookie.split(';').mapNotNull { entry ->
            val separator = entry.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            val name = entry.substring(0, separator).trim()
            val value = entry.substring(separator + 1).trim()
            if (name.isEmpty() || value.isEmpty()) null else name to value
        }.toMap()

    fun applyTo(request: Request): Request {
        if (request.url.host !in AUTHENTICATED_HOSTS ||
            !request.url.encodedPath.startsWith("/youtubei/") ||
            !request.url.isHttps ||
            request.header("Cookie").isNullOrBlank()
        ) {
            return request
        }
        val origin = "https://${request.url.host}"
        val timestamp = System.currentTimeMillis() / 1_000L
        val authorization =
            listOfNotNull(
                signature("SAPISIDHASH", cookies["SAPISID"] ?: cookies["__Secure-3PAPISID"], origin, timestamp),
                signature("SAPISID1PHASH", cookies["__Secure-1PAPISID"], origin, timestamp),
                signature("SAPISID3PHASH", cookies["__Secure-3PAPISID"], origin, timestamp),
            ).joinToString(" ")
        if (authorization.isEmpty()) return request
        return request.newBuilder()
            .header("Cookie", cookie)
            .header("Authorization", authorization)
            .header("Origin", origin)
            .header("X-Origin", origin)
            .header("X-Goog-AuthUser", "0")
            .header("X-Youtube-Bootstrap-Logged-In", "true")
            .apply {
                if (delegatedSessionId != null) {
                    header("X-Goog-PageId", delegatedSessionId)
                } else {
                    removeHeader("X-Goog-PageId")
                }
            }
            .build()
    }

    private fun signature(
        scheme: String,
        sid: String?,
        origin: String,
        timestamp: Long,
    ): String? {
        if (sid == null) return null
        val input = "$timestamp $sid $origin"
        val hash = MessageDigest.getInstance("SHA-1")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return "$scheme ${timestamp}_$hash"
    }

    companion object {
        private val AUTHENTICATED_HOSTS = setOf("www.youtube.com", "music.youtube.com")

        fun fromRequest(request: JSONObject): YoutubeiRequestAuthentication? {
            val cookie = request.optString("cookie").trim().takeIf { it.isNotEmpty() && it != "null" }
                ?: return null
            val dataSyncId = request.optString("dataSyncId").trim().takeIf { it.isNotEmpty() && it != "null" }
            val first = dataSyncId?.substringBefore("||")?.trim()?.takeIf(String::isNotEmpty)
            val second = dataSyncId?.substringAfter("||", "")?.trim()?.takeIf(String::isNotEmpty)
            return YoutubeiRequestAuthentication(
                cookie = cookie,
                delegatedSessionId = first.takeIf { second != null },
            )
        }
    }
}
