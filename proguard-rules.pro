# WebView JS 桥接不可混淆
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class kawaii.viey.browser.WebJs { *; }
-keep class kawaii.viey.browser.WebViey { *; }

# BouncyCastle：只保留用到的 provider 与 ASN1/PKIX 入口，其余交给 R8 收缩
# 已移除 BouncyCastleProvider 注册，JCE 反射矩阵（jcajce.provider，约 1400 个类）不再需要保留
-dontwarn org.bouncycastle.jce.provider.**
-dontwarn org.bouncycastle.jcajce.**
# 只保留实际可达的类：证书构造链路（X509v3CertificateBuilder / ContentSigner / PEM）
# 均为直接调用，无反射入口，放开 keep 交给 R8 按引用裁剪
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

# AI 页面由 Intent 显式启动，类名必须保留
-keep public class kawaii.viey.browser.AiActivity
