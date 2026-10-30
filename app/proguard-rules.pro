# ---------- sherpa-onnx ----------
# JNI 绑定全靠反射/原生符号查找，混淆必崩
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class com.k2fsa.sherpa.onnx.* {
    <init>(...);
    <methods>;
}
-keepclasseswithmembernames class * {
    native <methods>;
}

# ---------- ML Kit 翻译 ----------
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# ---------- OkHttp / 序列化 ----------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn kotlinx.serialization.**
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions

# ---------- Room ----------
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# ---------- 数据模型（供序列化/反射用）----------
-keep class com.screensub.core.asr.model.** { *; }
-keep class com.screensub.core.mt.model.** { *; }
