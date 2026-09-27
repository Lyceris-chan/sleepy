# smali/baksmali — keep all public API
-keep class com.android.tools.smali.** { *; }
-dontwarn com.android.tools.smali.**
-dontwarn org.antlr.**
-dontwarn org.stringtemplate.**
-dontwarn java.awt.**
-dontwarn javax.swing.**

# apksig — keep signing classes
-keep class com.android.apksig.** { *; }
-dontwarn com.android.apksig.**
-dontwarn sun.security.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Keep data model classes for JSON deserialization
-keep class dev.sleepy.app.model.** { *; }

# Remove all debug logging in release
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
}
