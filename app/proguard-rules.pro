-keep class com.k2fsa.sherpa.onnx.** { *; }

-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class net.sourceforge.lame.** { *; }
-dontwarn net.sourceforge.lame.**

-keep class javax.sound.sampled.** { *; }
-dontwarn javax.sound.**

-keep class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-dontwarn org.brotli.**
-dontwarn com.github.luben.zstd.**

-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn org.bouncycastle.**

-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_common.** { *; }
-dontwarn com.google.mlkit.**

-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlin.Metadata { *; }

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
