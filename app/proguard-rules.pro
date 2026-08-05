# Glance / AppWidget entry points are referenced from the manifest and from
# RemoteViews, so they must survive shrinking under their original names.
-keep class com.t212widgets.widget.** { *; }
-keep class com.t212widgets.refresh.** { *; }

# Never let the shrinker keep source file names / line numbers that could end up
# in a crash report alongside user data.
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# Strip every log call from release builds. The API key must never be able to
# reach logcat, not even through a stray debug statement.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}
