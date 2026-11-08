# Keep the Application entry point so R8 does not strip it.
-keep class com.discord.evs.generator.App { <init>(); }

# Keep native bridge classes and their JNI methods with exact names/signatures.
# These class names are referenced by the native .so, so R8 must not rename them.
-keep class com.discord.evs.generator.security.SecretsManager {
    private java.lang.String nativeDecrypt(byte[]);
    java.lang.String decryptWithPassphrase(char[], byte[]);
    public static java.lang.String dcWebhookUrl(android.content.Context);
    public static java.lang.String dcWebhookName(android.content.Context);
    public static java.lang.String dcWebhookPfp(android.content.Context);
    public static java.lang.String tgBotToken(android.content.Context);
    public static java.lang.String tgChannelId(android.content.Context);
}
-keep class com.discord.evs.generator.security.NativeGuard {
    public static boolean verifyIntegrity(android.content.Context);
    public static boolean isSafeEnvironment(android.content.Context);
    public static boolean sendDcWebhook(android.content.Context, java.lang.String, int, boolean);
    public static boolean sendTgMessage(android.content.Context, java.lang.String, int, boolean);
    public static java.lang.String nativeGetSignatureHash(android.content.Context);
}
-keepclasseswithmembernames class * { native <methods>; }

# Keep BuildConfig so the native anti-debug check can read the DEBUG flag.
-keep class com.discord.evs.generator.BuildConfig { public static boolean DEBUG; }

# Keep the main activity and exported components.
-keep class com.discord.evs.generator.MainActivity { <init>(); }

# Keep fragments that are referenced by Android Navigation XML.
-keep class com.discord.evs.generator.ui.*Fragment { <init>(); }

# Keep all classes that are referenced from layout XML by name.
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Keep JavaScript interface (if any WebView bridge is added later).
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep @Keep annotated members and classes.
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers @androidx.annotation.Keep class * { *; }

# Room: keep entities, DAOs, and the database.
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep @androidx.room.TypeConverter class * { *; }
-dontwarn androidx.room.paging.**

# Navigation: keep fragment arguments and safe-args classes.
-keepclassmembers class * extends androidx.navigation.fragment.NavHostFragment { <init>(); }
-keepnames class * extends android.os.Parcelable
-keepnames class * extends java.io.Serializable

# Kotlin: keep metadata, companion objects, and reflection support.
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, Exceptions
-keepclassmembers class **$Companion { *; }
-keepclassmembers class kotlin.Metadata { *; }
-dontwarn kotlin.coroutines.**
-dontwarn kotlin.reflect.**
-keep class kotlin.coroutines.CoroutineContext { *; }
-keepclassmembers class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# OkHttp / Okio / Retrofit-style: keep internal models and method names used by reflection.
-keepattributes Exceptions, InnerClasses, Signature, SourceFile, LineNumberTable
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class com.squareup.okhttp.** { *; }
-dontwarn com.squareup.okhttp.**

# Gson: keep serialized names and model classes.
-keepattributes Signature, *Annotation*
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**
-keep class * implements com.google.gson.TypeAdapterFactory { *; }
-keep class * implements com.google.gson.JsonSerializer { *; }
-keep class * implements com.google.gson.JsonDeserializer { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ML Kit: keep native/model classes and signatures.
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# AndroidX / Material: keep class names referenced by XML, but allow obfuscation of bodies.
-keep class androidx.navigation.** { *; }
-dontwarn androidx.navigation.**
-keep class androidx.lifecycle.** { *; }
-dontwarn androidx.lifecycle.**
-keep class androidx.appcompat.widget.** { *; }
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**

# Keep generic signatures of interfaces used by reflection.
-keepattributes Signature

# Strip ALL logging from release — removes log strings that leak logic.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}

# Do not keep source file / line numbers in release (harder mapping after decompile).
-renamesourcefileattribute SourceFile
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, Exceptions

# Aggressive shrinking / renaming of app code (not libraries).
-optimizationpasses 7
-allowaccessmodification
-repackageclasses 'a'
-overloadaggressively
# removed: conflicts with -repackageclasses (R8 warning)

# Obfuscate automation / data logic class names (entry points already kept above).
-keep,allowobfuscation class com.discord.evs.generator.automation.** { *; }
-keep,allowobfuscation class com.discord.evs.generator.data.Prefs { *; }
-keep,allowobfuscation class com.discord.evs.generator.security.StringCipher { *; }

# Hide kotlin metadata where safe (reduces readable structure).
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    static void checkParameterIsNotNull(java.lang.Object, java.lang.String);
    static void checkNotNullParameter(java.lang.Object, java.lang.String);
    static void checkExpressionValueIsNotNull(java.lang.Object, java.lang.String);
    static void checkNotNullExpressionValue(java.lang.Object, java.lang.String);
    static void checkReturnedValueIsNotNull(java.lang.Object, java.lang.String);
    static void throwUninitializedPropertyAccessException(java.lang.String);
}


# WebView JS bridge methods (must keep names for @JavascriptInterface)
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepclassmembers class com.discord.evs.generator.ui.GeneratorFragment$* {
    @android.webkit.JavascriptInterface <methods>;
}

-keep class com.discord.evs.generator.security.NativeBridge { *; }
-keepclassmembers class com.discord.evs.generator.security.NativeBridge {
    native <methods>;
}
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
