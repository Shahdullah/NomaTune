/*
 * NomaTune (2026)
 * © Shahdullah — github.com/shahdullah
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.shahdullah.nomatune

import android.app.Application
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.edit
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.request.CachePolicy
import coil3.request.allowHardware
import coil3.request.crossfade
import com.shahdullah.nomatune.constants.*
import com.shahdullah.nomatune.extensions.*
import com.shahdullah.nomatune.ui.screens.settings.ThemePalettes
import com.shahdullah.nomatune.ui.theme.ThemeSeedPalette
import com.shahdullah.nomatune.ui.theme.ThemeSeedPaletteCodec
import com.shahdullah.nomatune.utils.dataStore
import com.shahdullah.nomatune.utils.PreferenceStore
import com.shahdullah.nomatune.utils.ProxyUtils
import com.shahdullah.nomatune.utils.YTPlayerUtils
import com.shahdullah.nomatune.utils.get
import com.shahdullah.nomatune.utils.reportException
import com.shahdullah.nomatune.utils.clearPlaybackWebAuthSession
import com.shahdullah.nomatune.utils.clearPlaybackAuthSession
import com.shahdullah.nomatune.innertube.YouTube
import com.shahdullah.nomatune.innertube.models.YouTubeLocale
import com.shahdullah.nomatune.kugou.KuGou
import com.shahdullah.nomatune.lastfm.LastFM
import com.shahdullah.nomatune.canvas.NomaTuneCanvas
import com.shahdullah.nomatune.paxsenix.PaxsenixLyrics
import com.shahdullah.nomatune.storage.StorageFolderKind
import com.shahdullah.nomatune.storage.StorageLocationRepository
import com.shahdullah.nomatune.ui.player.CanvasArtworkPlaybackCache
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import android.content.Intent
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess
import timber.log.Timber
import okhttp3.Dns
import androidx.datastore.preferences.core.stringPreferencesKey
import java.net.Proxy
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import com.shahdullah.nomatune.utils.toPlaybackAuthState
import com.shahdullah.nomatune.utils.potoken.BotGuardTokenGenerator

@HiltAndroidApp
class App : Application(), SingletonImageLoader.Factory {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile private var isInitialized = false
    private val didRunImageCacheTrim = AtomicBoolean(false)

    private fun currentProcessName(): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            val pid = android.os.Process.myPid()
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            activityManager?.runningAppProcesses
                ?.firstOrNull { it.pid == pid }
                ?.processName
        }
    }
    
    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        instance = this
        if (currentProcessName()?.endsWith(":crash") == true) {
            Timber.plant(Timber.DebugTree())
            return
        }
        BotGuardTokenGenerator.initialize(this)
        PreferenceStore.start(this)
        Timber.plant(Timber.DebugTree())
        try {
            Timber.plant(com.shahdullah.nomatune.utils.GlobalLogTree())
        } catch (_: Exception) {}

        initializeCriticalSync()
        initializeDeferredAsync()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // WebView cleanup happens automatically on process death
    }

    private fun initializeCriticalSync() {
        CanvasArtworkPlaybackCache.init(this)
        NomaTuneCanvas.initialize(BuildConfig.CANVAS_BEARER_TOKEN)
        PaxsenixLyrics.setUserAgent("NomaTune", BuildConfig.VERSION_NAME)

        val locale = Locale.getDefault()
        val languageTag = locale.toLanguageTag().replace("-Hant", "")
        YouTube.locale = YouTubeLocale(
            gl = locale.country.takeIf { it in CountryCodeToName } ?: "US",
            hl = locale.language.takeIf { it in LanguageCodeToName }
                ?: languageTag.takeIf { it in LanguageCodeToName }
                ?: "en"
        )
        if (languageTag == "zh-TW") {
            KuGou.useTraditionalChinese = true
        }
        LastFM.initialize(
            apiKey = BuildConfig.LASTFM_API_KEY,
            secret = BuildConfig.LASTFM_SECRET
        )
    }

    private fun initializeDeferredAsync() {
        applicationScope.launch(Dispatchers.IO) {
            try {
                val prefs = dataStore.data.first()
                
                prefs[ContentCountryKey]?.takeIf { it != SYSTEM_DEFAULT }?.let { country ->
                    YouTube.locale = YouTube.locale.copy(gl = country)
                }
                prefs[ContentLanguageKey]?.takeIf { it != SYSTEM_DEFAULT }?.let { lang ->
                    YouTube.locale = YouTube.locale.copy(hl = lang)
                }
                
                LastFM.sessionKey = prefs[LastFMSessionKey]

                ProxyUtils.applyYouTubeProxy(
                    enabled = prefs[ProxyEnabledKey] == true,
                    type = prefs[ProxyTypeKey].toEnum(defaultValue = Proxy.Type.HTTP),
                    host = prefs[ProxyHostKey],
                    port = prefs[ProxyPortKey],
                    username = prefs[ProxyUsernameKey],
                    password = prefs[ProxyPasswordKey],
                )
                YouTube.streamBypassProxy = YouTube.proxy != null && prefs[StreamBypassProxyKey] == true

                if (prefs[IpRotationEnabledKey] == true) {
                    try {
                        YouTube.enableIpRotation()
                    } catch (e: Exception) {
                        reportException(e)
                    }
                }

                if (prefs[UseLoginForBrowse] != false) {
                    YouTube.useLoginForBrowse = true
                }
                
                // Pre-warm BotGuard token generator
                val initialVisitor = prefs[VisitorDataKey] ?: YouTube.visitorData
                if (!initialVisitor.isNullOrBlank()) {
                    applicationScope.launch(Dispatchers.IO) {
                        BotGuardTokenGenerator.preWarm(initialVisitor)
                    }
                }
                
                // Apply random theme on startup if enabled
                if (prefs[RandomThemeOnStartupKey] == true) {
                    val randomPalette = ThemePalettes.generateRandomPalette()
                    val seedPalette = ThemeSeedPalette(
                        primary = randomPalette.primary,
                        secondary = randomPalette.secondary,
                        tertiary = randomPalette.tertiary,
                        neutral = randomPalette.neutral
                    )
                    val encodedPalette = ThemeSeedPaletteCodec.encodeForPreference(seedPalette, "Random")
                    dataStore.edit { settings ->
                        settings[CustomThemeColorKey] = encodedPalette
                    }
                }
                
                isInitialized = true
            } catch (e: Exception) {
                Timber.e(e, "Error during deferred initialization")
                reportException(e)
            }
        }

        applicationScope.launch(Dispatchers.IO) {
            dataStore.data
                .map {
                    Triple(
                        it[EnableDnsOverHttpsKey] ?: false,
                        it[DnsOverHttpsProviderKey] ?: "Cloudflare",
                        it[stringPreferencesKey("customDnsUrl")] ?: "https://"
                    )
                }
                .distinctUntilChanged()
                .collect { (enabled, provider, customUrl) ->
                    if (enabled) {
                        val dnsProviderUrls = mapOf(
                            "Google" to "https://dns.google/dns-query",
                            "Cloudflare" to "https://cloudflare-dns.com/dns-query",
                            "AdGuard" to "https://dns.adguard.com/dns-query",
                            "Quad9" to "https://dns.quad9.net/dns-query"
                        )
                        val url = if (provider == "Custom") customUrl else dnsProviderUrls[provider]
                        if (!url.isNullOrBlank() && url.startsWith("https://")) {
                            runCatching {
                                YouTube.dns = YouTube.createDnsOverHttps(url)
                            }
                        } else {
                            YouTube.dns = Dns.SYSTEM
                        }
                    } else {
                        YouTube.dns = Dns.SYSTEM
                    }
                }
        }

        applicationScope.launch(Dispatchers.IO) {
            dataStore.data
                .map { it.toPlaybackAuthState() }
                .distinctUntilChanged()
                .collect { authState ->
                    val previousFingerprint = YouTube.currentPlaybackAuthState().fingerprint
                    YouTube.authState = authState
                    if (previousFingerprint != authState.fingerprint) {
                        YTPlayerUtils.clearPlaybackAuthCaches()
                        val newSessionId = authState.sessionId
                        if (!newSessionId.isNullOrBlank()) {
                            BotGuardTokenGenerator.preWarm(newSessionId)
                        }
                    }
                }
        }

        applicationScope.launch(Dispatchers.IO) {
            dataStore.data
                .map { it.toPlaybackAuthState().visitorData }
                .distinctUntilChanged()
                .collect { visitorData ->
                    if (!visitorData.isNullOrBlank()) return@collect
                    YouTube.visitorData().onFailure {
                        reportException(it)
                    }.getOrNull()?.also { newVisitorData ->
                        dataStore.edit { settings ->
                            settings[VisitorDataKey] = newVisitorData
                        }
                    }
                }
        }

        try {
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    val sw = StringWriter()
                    val pw = PrintWriter(sw)
                    throwable.printStackTrace(pw)
                    val stack = sw.toString()

                    val intent = Intent(this@App, DebugActivity::class.java).apply {
                        putExtra(DebugActivity.EXTRA_STACK_TRACE, stack)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    }
                    startActivity(intent)
                    try { Thread.sleep(100) } catch (_: InterruptedException) {}
                } catch (e: Exception) {
                    reportException(e)
                } finally {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    exitProcess(2)
                }
            }
        } catch (e: Exception) {
            reportException(e)
        }
        applicationScope.launch(Dispatchers.IO) {
            dataStore.data
                .map { it[LastFMSessionKey] }
                .distinctUntilChanged()
                .collect { sessionKey ->
                    LastFM.sessionKey = sessionKey
                }
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val smartTrimmer = dataStore[SmartTrimmerKey] ?: false
        val imageCacheConfig = resolveImageDiskCacheConfig(dataStore[MaxImageCacheSizeKey])

        val diskCache = DiskCache.Builder()
            .directory(StorageLocationRepository.cacheDirectory(this, StorageFolderKind.IMAGE_CACHE))
            .maxSizeBytes(imageCacheConfig.maxSizeBytes)
            .build()

        if (smartTrimmer && imageCacheConfig.policy == CachePolicy.ENABLED && didRunImageCacheTrim.compareAndSet(false, true)) {
            applicationScope.launch(Dispatchers.IO) { trimImageDiskCache(diskCache) }
        }

        return ImageLoader.Builder(this)
            .crossfade(true)
            .allowHardware(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            .diskCache(diskCache)
            .diskCachePolicy(imageCacheConfig.policy)
            .build()
    }

    private fun trimImageDiskCache(diskCache: DiskCache) {
        try {
            val limitBytes = diskCache.maxSize
            if (limitBytes <= 0L || limitBytes == Long.MAX_VALUE) return

            val dir = java.io.File(diskCache.directory.toString())
            if (!dir.exists()) return

            val files = dir.walkTopDown().filter { it.isFile }.sortedBy { it.lastModified() }.toList()
            var currentSize = files.sumOf { it.length() }
            if (currentSize <= limitBytes) return

            for (file in files) {
                if (currentSize <= limitBytes) break
                val size = file.length()
                if (runCatching { file.delete() }.getOrDefault(false)) currentSize -= size
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        lateinit var instance: App
            private set

        fun forgetAccount(
            context: Context,
            clearWebAuthSession: Boolean = true,
        ) {
            if (clearWebAuthSession) {
                clearPlaybackWebAuthSession(context)
            }
            CoroutineScope(Dispatchers.IO).launch {
                context.dataStore.edit { settings ->
                    settings.clearPlaybackAuthSession()
                }
            }
        }
    }
}

internal data class ImageDiskCacheConfig(
    val policy: CachePolicy,
    val maxSizeBytes: Long,
)

internal fun resolveImageDiskCacheConfig(maxImageCacheSizeMb: Int?): ImageDiskCacheConfig {
    val sizeMb = maxImageCacheSizeMb ?: 512
    if (sizeMb == 0) return ImageDiskCacheConfig(policy = CachePolicy.DISABLED, maxSizeBytes = 1L)
    if (sizeMb < 0) return ImageDiskCacheConfig(policy = CachePolicy.ENABLED, maxSizeBytes = Long.MAX_VALUE)
    val bytesPerMb = 1024L * 1024L
    val safeSizeMb = sizeMb.toLong().coerceAtMost(Long.MAX_VALUE / bytesPerMb)
    return ImageDiskCacheConfig(policy = CachePolicy.ENABLED, maxSizeBytes = safeSizeMb * bytesPerMb)
}
