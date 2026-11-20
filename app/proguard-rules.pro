# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Keep readable stack traces in release builds (matches the upstream ArchiveTune
# release configuration) so playback failures stay diagnosable from a user report.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Do not rename classes, methods or fields in release builds.
#
# NomaTune resolves several members reflectively / by name at runtime:
#  - BotGuardTokenGenerator's WebView bridge (@JavascriptInterface `BotGuardBridge`)
#  - LoginScreen's WebView bridge (@JavascriptInterface `Android`)
#  - youtubei.js <-> QuickJS binding names and Kotlin serialization models
# Renaming them silently breaks anonymous playback (PoToken minting fails),
# therefore obfuscation stays disabled exactly like upstream ArchiveTune.
-dontobfuscate

# Defensive: WebView JavaScript interface methods must never be renamed/stripped.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.shahdullah.nomatune.utils.potoken.** { *; }
-keep class com.shahdullah.nomatune.morideobfuscator.youtubei.** { *; }

# Skip R8's class-level optimisation pass. It is what mis-dexed the coroutine
# state machine above; obfuscation and shrinking stay enabled below.
-dontoptimize

# VerifyError hardening (crash on v1.3.14, Android 15 / ART):
#   java.lang.VerifyError: App$newImageLoader$1.invokeSuspend failed to verify:
#   'this' argument 'Uninitialized Reference: org.json.JSONException' not instance of
#   'Reference: java.lang.RuntimeException'
# R8's full-mode optimizer can mis-dex Kotlin coroutine state machines and their
# exception ranges. Keep the coroutine/continuation scaffolding the optimizer needs
# to understand, and skip the class-level optimisation pass that produced the bad
# bytecode. Obfuscation stays off, so nothing user-visible changes.
-keep class kotlin.coroutines.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keepclassmembers class kotlinx.coroutines.** { *; }
-keepclassmembers class * extends kotlin.coroutines.jvm.internal.ContinuationImpl { *; }
-keepclassmembers class * extends kotlin.coroutines.jvm.internal.BaseContinuationImpl { *; }
-keepclassmembers class **$* implements kotlin.coroutines.Continuation { *; }
-keep class org.json.** { *; }

## Kotlin Serialization
# Keep `Companion` object fields of serializable classes.
# This avoids serializer lookup through `getDeclaredClasses` as done for named companion objects.
-if @kotlinx.serialization.Serializable class **
-keepclasseswithmembers class <1> {
    static <1>$Companion Companion;
}

# Keep `serializer()` on companion objects (both default and named) of serializable classes.
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclasseswithmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep `INSTANCE.serializer()` of serializable objects.
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclasseswithmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# @Serializable and @Polymorphic are used at runtime for polymorphic serialization.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

## Markwon — optional GIF support (android-gif-drawable) not bundled
-dontwarn pl.droidsonroids.gif.**

-dontwarn javax.servlet.ServletContainerInitializer
-dontwarn org.bouncycastle.jsse.BCSSLParameters
-dontwarn org.bouncycastle.jsse.BCSSLSocket
-dontwarn org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
-dontwarn org.conscrypt.Conscrypt$Version
-dontwarn org.conscrypt.Conscrypt
-dontwarn org.conscrypt.ConscryptHostnameVerifier
-dontwarn org.openjsse.javax.net.ssl.SSLParameters
-dontwarn org.openjsse.javax.net.ssl.SSLSocket
-dontwarn org.openjsse.net.ssl.OpenJSSE
-dontwarn org.slf4j.impl.StaticLoggerBinder

## Rules for NewPipeExtractor & QuickJS runtime
-keep class org.schabi.newpipe.extractor.services.youtube.protos.** { *; }
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
-keep class org.schabi.newpipe.extractor.** { *; }
-keepclassmembers class org.schabi.newpipe.extractor.** { *; }
-keep class com.dokar.quickjs.** { *; }
-keepclassmembers class com.dokar.quickjs.** { *; }
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.javascript.engine.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.JavaToJSONConverters
-dontwarn org.mozilla.javascript.tools.**
-keep class javax.script.** { *; }
-dontwarn javax.script.**
-keep class jdk.dynalink.** { *; }
-dontwarn jdk.dynalink.**

## Logging (does not affect Timber)
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    ## Leave in release builds
    #public static int i(...);
    #public static int w(...);
    #public static int e(...);
}

# Generated automatically by the Android Gradle plugin.
-dontwarn java.beans.BeanDescriptor
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
-dontwarn java.lang.management.**

# Keep all classes within the kuromoji package
-keep class com.atilika.kuromoji.** { *; }

## Queue Persistence Rules
# Keep queue-related classes to prevent serialization issues in release builds
-keep class com.shahdullah.nomatune.models.PersistQueue { *; }
-keep class com.shahdullah.nomatune.models.PersistPlayerState { *; }
-keep class com.shahdullah.nomatune.models.QueueData { *; }
-keep class com.shahdullah.nomatune.models.QueueType { *; }
-keep class com.shahdullah.nomatune.playback.queues.** { *; }

# Keep serialization methods for queue persistence
-keepclassmembers class * implements java.io.Serializable {
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
}

## Media3 Protection Rules
# Protect Guava from conflicts with system versions
-keep class com.google.common.** { *; }
-keep class com.google.common.util.concurrent.** { *; }
-keep class com.google.common.collect.** { *; }
-dontwarn com.google.common.**

# Protect Media3 from obfuscation
-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }
-dontwarn androidx.media3.**

## JAudioTagger - suppress missing AWT/ImageIO classes (not available on Android)
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn javax.swing.**
-keep class org.jaudiotagger.** { *; }

## Jetpack Glance
# Keep ActionCallback implementations and their no-arg constructors
-keep class * implements androidx.glance.appwidget.action.ActionCallback {
    public <init>();
}
-keep class * implements androidx.glance.action.ActionCallback {
    public <init>();
}
# Keep GlanceAppWidget and its receiver
-keep class * extends androidx.glance.appwidget.GlanceAppWidget { *; }
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver { *; }
