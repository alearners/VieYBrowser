# WebView JS 桥接不可混淆
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class kawaii.viey.browser.WebJs { *; }
-keep class kawaii.viey.browser.WebViey { *; }
# BouncyCastle 反射/JCE provider
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
# org.json / jsoup 反射较少，保留入口
-keep class org.json.** { *; }
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**
