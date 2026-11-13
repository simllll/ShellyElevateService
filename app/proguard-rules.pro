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
# --- PanelElevate (R8 enabled for release) ---
# Readable stack traces in logcat/crash reports.
-keepattributes SourceFile,LineNumberTable

# Paho MQTT v5 loads its logger (JSR47Logger), network module factories (ServiceLoader) and
# message bundles by name.
-keep class org.eclipse.paho.mqttv5.** { *; }
-dontwarn org.eclipse.paho.mqttv5.**

# NanoHTTPD (HTTP API) and AndroidSVG: small, keep as is.
-keep class fi.iki.elonen.** { *; }
-keep class com.caverock.androidsvg.** { *; }
-dontwarn com.caverock.androidsvg.**

# Our own code uses no reflection on app classes, but the service, receivers and activities
# are referenced from the manifest (kept automatically). Hidden framework APIs are reached
# through reflection on android.* classes, which R8 does not touch.
