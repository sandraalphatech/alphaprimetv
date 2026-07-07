# Retrofit
-keep class retrofit2.** { *; }
-keepattributes Signature
-keepattributes Exceptions

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Gson
-keepattributes Signature
-keep class sun.misc.Unsafe { *; }
-keep class com.google.gson.stream.** { *; }

# Coil
-keep class coil.** { *; }

# Keep data classes
-keepclasseswithmembers class com.velvetiptv.app.** {
    <init>(...);
}

# AndroidX/Material3
-dontwarn androidx.**
-keep class androidx.** { *; }

# Media3
-dontwarn com.google.android.media3.**
-keep class com.google.android.media3.** { *; }
