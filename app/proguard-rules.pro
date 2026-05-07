# 混淆规则 - SMB Photo Library

# Hilt
-keep class dagger.hilt.** { *; }
-keepnames class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper

# SMBJ
-keep class com.hierynomus.** { *; }
-dontwarn com.hierynomus.**
-dontwarn org.bouncycastle.**
-keep class org.bouncycastle.** { *; }

# SMBJ 间接依赖 net.engio.mbassy（使用 javax.el，Android 无此库）
-dontwarn javax.el.**
-dontwarn net.engio.**
-keep class net.engio.** { *; }

# Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { *; }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}

# Security Crypto
-keep class androidx.security.crypto.** { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Data classes
-keep class com.example.smbphoto.data.model.** { *; }
