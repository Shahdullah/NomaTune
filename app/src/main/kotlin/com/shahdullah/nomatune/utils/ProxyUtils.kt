/*
 * NomaTune (2026)
 * © Shahdullah — github.com/shahdullah
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.shahdullah.nomatune.utils

import com.shahdullah.nomatune.innertube.YouTube
import java.net.InetSocketAddress
import java.net.Proxy

object ProxyUtils {
    fun createProxyOrNull(
        type: Proxy.Type,
        host: String?,
        port: Int?,
    ): Proxy? {
        val resolvedHost = host?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val resolvedPort = port?.takeIf { it in 1..65535 } ?: return null
        return Proxy(type, InetSocketAddress.createUnresolved(resolvedHost, resolvedPort))
    }

    fun applyYouTubeProxy(
        enabled: Boolean,
        type: Proxy.Type,
        host: String?,
        port: Int?,
        username: String?,
        password: String?,
    ) {
        val proxy = if (enabled) createProxyOrNull(type, host, port) else null
        YouTube.proxy = proxy
        YouTube.proxyUsername = username?.takeIf { proxy != null && it.isNotBlank() }
        YouTube.proxyPassword = password?.takeIf { proxy != null && it.isNotBlank() }
    }
}
