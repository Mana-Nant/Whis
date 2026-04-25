# Keep JNI methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep whisper bridge
-keep class com.example.whisperandroid.whisper.** { *; }

# Room
-keep class androidx.room.** { *; }
-keep @androidx.room.* class *

# ffmpeg-kit
-keep class com.arthenica.** { *; }

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
