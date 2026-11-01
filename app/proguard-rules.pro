# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# The module entry point is loaded by name: META-INF/xposed/java_init.list
# contains "io.github.achyuki.oautopin.Hook" and the framework instantiates that
# class reflectively inside the hooked process.
#
# The class must therefore survive minification under its original name, so this
# rule is an unqualified -keep (no allowobfuscation, no allowshrinking). If the
# class is renamed, the release build silently stops working: the framework
# looks up a name that no longer exists in the dex.
-keep class io.github.achyuki.oautopin.Hook {
    *;
}

# Safety net for any further libxposed entry class added later: they all inherit
# from XposedModule.
-keep class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# libxposed annotations are compile-time only and are not shipped in the APK.
-dontwarn io.github.libxposed.annotation.**
