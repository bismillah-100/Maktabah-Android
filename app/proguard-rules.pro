# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /Users/ghoys/Library/Android/sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.kts.

# Keep Compose specific rules
-keep class androidx.compose.material3.** { *; }

# Keep OkHttp and Kotlin serialization if used
-keepattributes Signature, *Annotation*, EnclosingMethod
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
# A resource is known at compile time, then we can (often) code-gen a optimization
# for this resource name, which requires the class to be kept.
-keepclassmembers class **.R$* {
    public static <fields>;
}

# Add any other project-specific rules here

# Keep Zstd JNI classes and members
-keep class com.github.luben.zstd.** { *; }
-dontwarn com.github.luben.zstd.**

# Keep models to prevent reflection/serialization issues with org.json
-keep class com.maktabah.models.** { *; }

# Keep Firebase Cloud Messaging services
-keep class com.maktabah.cloudKit.SyncMessagingService { *; }
