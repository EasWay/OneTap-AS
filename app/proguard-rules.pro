# --- UNIVERSAL SAFEGUARD RULES ---
# 1. Keep Attributes (The most important part)
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses
-keepattributes SourceFile,LineNumberTable
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault

# 2. Keep Kotlin Metadata (Prevents "ClassCast" in Coroutines)
-keep class kotlin.Metadata { *; }
-keep class kotlinx.coroutines.** { *; }

# 3. Keep Retrofit & OkHttp
-keep class retrofit2.** { *; }
-keep interface retrofit2.** { *; }
-keep class okhttp3.** { *; }
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**

# 4. Keep YOUR App Code (The Safety Net)
# This prevents your own classes from being renamed/stripped
-keep class com.example.onetap.** { *; }
-keepclassmembers class com.example.onetap.** { *; }
-keep interface com.example.onetap.** { *; }