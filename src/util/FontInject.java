package kawaii.viey.browser;

import android.content.Context;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;

/**
 * 网页自定义字体注入（CSP 安全实现）。
 *
 * 原理：带 CSP 的站点会拦截内联 style / data: 字体（font-src 'self'）。
 * 这里改为在 shouldInterceptRequest 里，用「页面同源 URL」返回字体与 JS：
 *   /__vie__/font.ttf  -> 字体字节（font/ttf）
 *   /__vie__/font.js   -> 注入脚本（application/javascript）
 * 同源资源天然在 CSP 允许名单内，不需要放行 data: 或 eval。
 * 注入方式优先网络加载 <script src>，被 script-src 拦截时退回 WebView 直接执行。
 */
public class FontInject {

    public static final String SEG = "/__vie__/";
    public static final String FONT_PATH = SEG + "font.ttf";
    public static final String JS_PATH = SEG + "font.js";

    private static final String FLAG = "网页字体";
    private static final String FONT_FILE = "网页字体";
    private static final String FONT_FILE_FALLBACK = "全局字体";

    private static byte[] sFont;
    private static long sStamp = -1;
    private static String sFamily = "VieWebFont";

    /** 是否开启网页字体注入 */
    public static boolean enabled(Context c) {
        if (c == null) return false;
        return "true".equals(read(new File(c.getFilesDir(), "/sz/" + FLAG)));
    }

    public static void setEnabled(Context c, boolean on) {
        File f = new File(c.getFilesDir(), "/sz/" + FLAG);
        File dir = f.getParentFile();
        if (dir != null && !dir.exists()) dir.mkdirs();
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream(f);
            out.write((on ? "true" : "false").getBytes("UTF-8"));
            out.close();
        } catch (Exception ignored) {
        }
    }

    public static File fontFile(Context c) {
        File a = new File(c.getFilesDir(), "/diy/" + FONT_FILE);
        if (a.exists() && a.length() > 0) return a;
        File b = new File(c.getFilesDir(), "/diy/" + FONT_FILE_FALLBACK);
        return (b.exists() && b.length() > 0) ? b : null;
    }

    public static synchronized byte[] fontBytes(Context c) {
        File f = fontFile(c);
        if (f == null) return null;
        long st = f.length() + f.lastModified();
        if (sFont != null && st == sStamp) return sFont;
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            sFont = bo.toByteArray();
            sStamp = st;
        } catch (Exception e) {
            sFont = null;
        }
        return sFont;
    }

    /** shouldInterceptRequest 入口，命中同源伪资源则直接返回内容 */
    public static WebResourceResponse intercept(Context c, WebResourceRequest req) {
        if (req == null || req.getUrl() == null) return null;
        String path = req.getUrl().getPath();
        if (path == null || !path.startsWith(SEG)) return null;
        try {
            if (path.endsWith("/font.ttf")) {
                byte[] data = fontBytes(c);
                if (data == null) return null;
                WebResourceResponse r = new WebResourceResponse("font/ttf", null, new ByteArrayInputStream(data));
                java.util.Map<String, String> h = new java.util.HashMap<>();
                h.put("Cache-Control", "max-age=86400");
                h.put("Access-Control-Allow-Origin", "*");
                r.setResponseHeaders(h);
                return r;
            }
            if (path.endsWith("/font.js")) {
                WebResourceResponse r = new WebResourceResponse("application/javascript", "UTF-8",
                        new ByteArrayInputStream(js().getBytes("UTF-8")));
                java.util.Map<String, String> h = new java.util.HashMap<>();
                h.put("Cache-Control", "no-cache");
                r.setResponseHeaders(h);
                return r;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 页面加载完成后注入 */
    public static void inject(final WebView view, String pageUrl) {
        if (view == null || pageUrl == null) return;
        final Context c = view.getContext();
        if (!enabled(c) || fontBytes(c) == null) return;
        final String origin = origin(pageUrl);
        if (origin == null) return;
        final String script = "(function(){try{if(window.__vieFontDone)return;"
                + "var s=document.createElement('script');s.src='" + origin + JS_PATH + "?v='+Date.now();"
                + "s.async=false;s.onerror=function(){window.__vieFontDone=window.__vieFontDone||'net-err';};"
                + "(document.head||document.documentElement).appendChild(s);}catch(e){}})();";
        view.post(() -> {
            try {
                view.evaluateJavascript(script, null);
                view.postDelayed(() -> view.evaluateJavascript(
                        "(function(){return window.__vieFontDone?1:0;})();",
                        value -> {
                            if (value != null && value.contains("0")) {
                                view.evaluateJavascript(js(), null);
                            }
                        }), 700);
            } catch (Exception ignored) {
            }
        });
    }

    public static String origin(String url) {
        try {
            java.net.URL u = new java.net.URL(url);
            String host = u.getHost();
            if (host == null) return null;
            int port = u.getPort();
            return u.getProtocol() + "://" + host + (port > 0 ? ":" + port : "");
        } catch (Exception e) {
            return null;
        }
    }

    /** 注入脚本：用二进制 FontFace + adoptedStyleSheets 落地，避免 data: URL 与内联 style 被 CSP 拦 */
    public static String js() {
        return "(function(){try{"
                + "if(window.__vieFontDone===true)return;"
                + "var U='/__vie__/font.ttf',F='VieWebFont';"
                + "var C='*:not([class*=icon]):not([class*=Icon]):not([class*=fa-]):not(.fa):not(.material-icons){font-family:\"'+F+'\" !important;}';"
                + "function css(){"
                + " try{var s=new CSSStyleSheet();s.replaceSync(C);var a=document.adoptedStyleSheets||[];"
                + "  document.adoptedStyleSheets=a.concat([s]);window.__vieFontCss='adopted';return;}"
                + " catch(e){try{var st=document.createElement('style');st.appendChild(document.createTextNode(C));"
                + "  (document.head||document.documentElement).appendChild(st);window.__vieFontCss='style';}catch(e2){}}"
                + "}"
                + "function done(f){try{document.fonts.add(f);window.__vieFontDone=true;css();}catch(e){}}"
                + "function bin(){try{var x=new XMLHttpRequest();x.open('GET',U,true);x.responseType='arraybuffer';"
                + " x.onload=function(){try{done(new FontFace(F,x.response));}catch(e){}};x.onerror=css;x.send();}catch(e){css();}}"
                + "try{var f=new FontFace(F,'url(\"'+U+'\")');f.load().then(function(ff){done(ff);},function(){bin();});}"
                + "catch(e){bin();}"
                + "}catch(e){}})();";
    }

    private static String read(File f) {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8").trim();
        } catch (Exception e) {
            return "";
        }
    }
}
