# ProGuard rules for readest-shell

# Keep Tauri runtime classes (loaded reflectively from native side)
-keep class app.tauri.** { *; }
-keep class com.readest.app.** { *; }
-keepclassmembers class com.readest.app.** { *; }

# Keep WebView JS interface (in case the remote page injects one)
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep WebViewClient / WebChromeClient subclasses (instantiated reflectively
# by the Android WebView framework in some scenarios)
-keep class * extends android.webkit.WebViewClient { *; }
-keep class * extends android.webkit.WebChromeClient { *; }

# Keep DownloadListener
-keep class * implements android.webkit.DownloadListener { *; }

# OkHttp / DownloadManager dependencies
-dontwarn okhttp3.**
-dontwarn okio.**

# Keep source file + line numbers for crash stacks
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
