# Cast options provider is referenced by class name string in AndroidManifest.xml.
-keep class com.foreverjukebox.app.cast.ForeverJukeboxCastOptionsProvider { *; }

# JNI entrypoints in C++ use Java_* symbol names for these classes/methods.
-keep class com.foreverjukebox.app.local.NativeAnalysisBridge { *; }
-keep class com.foreverjukebox.app.audio.BufferedAudioPlayer { *; }

# JNI progress callbacks call interface methods by literal name via GetMethodID.
-keep interface com.foreverjukebox.app.local.NativeAnalysisBridge$MadmomBeatsPortProgressCallback { *; }
-keep interface com.foreverjukebox.app.local.NativeAnalysisBridge$EssentiaProgressCallback { *; }

# Keep serialization models and serializers stable for cached analysis JSON,
# preferences payloads, and server responses in minified release builds.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class com.foreverjukebox.app.** {
    public static ** Companion;
}
-keepclasseswithmembers class com.foreverjukebox.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Sentry initializes SDK pieces reflectively from manifest metadata.
-keep class io.sentry.** { *; }
-dontwarn io.sentry.**

# Full release artifact verification needs these server-only names to remain
# inspectable. Play release cannot keep them because the classes are not compiled.
-keep class com.foreverjukebox.app.data.ApiClient { *; }
-keep class com.foreverjukebox.app.data.YoutubeSearchItem { *; }
-keep class com.foreverjukebox.app.ui.SearchCoordinator { *; }
