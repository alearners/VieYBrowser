# WebView JS 桥接不可混淆
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class kawaii.viey.browser.WebJs { *; }
-keep class kawaii.viey.browser.WebViey { *; }

# BouncyCastle：只保留用到的 provider 与 ASN1/PKIX 入口，其余交给 R8 收缩
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.cert.** { *; }
-keep class org.bouncycastle.operator.** { *; }
-keep class org.bouncycastle.openssl.** { *; }
-keep class org.bouncycastle.asn1.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
-dontwarn org.slf4j.**

# Paho MQTT / commons-net FTP 内部使用反射与包名约定
-keep class org.eclipse.paho.** { *; }
-dontwarn org.eclipse.paho.**
-dontwarn org.apache.commons.net.**

-allowaccessmodification
-repackageclasses ''
-optimizationpasses 5
