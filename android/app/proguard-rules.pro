# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.estudiofenix.cerca.data.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.estudiofenix.cerca.data.**$$serializer { *; }
# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
