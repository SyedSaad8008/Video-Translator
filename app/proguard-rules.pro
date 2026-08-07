# Add project-specific ProGuard rules here.

# Vosk
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-dontwarn org.vosk.**

# ML Kit
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.mlkit.**

# Kotlinx serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.example.videotranslator.model.**$$serializer { *; }
-keepclassmembers class com.example.videotranslator.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.example.videotranslator.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Media3
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
