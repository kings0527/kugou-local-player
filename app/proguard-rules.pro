# ================= AIDL（语音助手协议，不可混淆） =================
# 酷狗新版协议
-keep class com.kugou.android.thirdmap.** { *; }
-keep interface com.kugou.android.thirdmap.** { *; }
# 酷狗旧版协议
-keep class com.kugou.android.third.api.** { *; }
-keep interface com.kugou.android.third.api.** { *; }
# 网易云协议
-keep class com.netease.cloudmusic.third.api.** { *; }
-keep interface com.netease.cloudmusic.third.api.** { *; }

# AIDL Stub / Proxy 由系统通过 Binder 调用，必须保留
-keep class * extends android.os.Binder { *; }
-keep class * implements android.os.IInterface { *; }

# ================= 应用组件（Manifest 引用） =================
-keep class com.kugou.android.MainActivity { *; }
-keep class com.kugou.android.PlayerActivity { *; }
-keep class com.kugou.android.PlayerService { *; }
-keep class com.kugou.android.thirdapi.** { *; }
-keep class com.kugou.android.App { *; }

# ================= Media3 / ExoPlayer =================
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ================= AndroidX =================
-keep class androidx.datastore.** { *; }
-keep class * extends androidx.fragment.app.Fragment { *; }
-keep class * implements androidx.viewbinding.ViewBinding { *; }

# ViewBinding 生成的类
-keep class com.kugou.android.databinding.** { *; }

# ================= 协程 / Kotlin =================
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# ================= 通用 =================
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod, Exceptions
-dontwarn org.jetbrains.annotations.**
-dontwarn javax.annotation.**
