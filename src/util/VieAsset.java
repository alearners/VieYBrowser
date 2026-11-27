package kawaii.viey.browser;

import android.content.Context;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 同源资源服务：把 assets 里的注入脚本以「页面同源 URL」暴露出来，
 * 绕过站点 CSP 的 script-src（与字体注入同一套机制）。
 *
 *   /__vie__/js/<name>.js  -> assets/js/<name>.js
 */
public class VieAsset {

    public static final String SEG = "/__vie__/";
    public static final String JS_SEG = SEG + "js/";

    public static WebResourceResponse intercept(Context c, WebResourceRequest req) {
        if (req == null || req.getUrl() == null) return null;
        String path = req.getUrl().getPath();
        if (path == null) return null;
        if (path.startsWith(JS_SEG) && path.endsWith(".js")) {
            String name = path.substring(JS_SEG.length());
            if (name.contains("/") || name.contains("..")) return null;
            try {
                byte[] data = read(c, "js/" + name);
                if (data == null) return null;
                WebResourceResponse r = new WebResourceResponse("application/javascript", "UTF-8",
                        new ByteArrayInputStream(data));
                Map<String, String> h = new HashMap<>();
                h.put("Cache-Control", "no-cache");
                r.setResponseHeaders(h);
                return r;
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    public static byte[] read(Context c, String assetPath) {
        try (InputStream in = c.getAssets().open(assetPath)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /** 以同源 <script src> 注入 assets/js/<name>.js，被 CSP 拦时退回本地执行 */
    public static void inject(WebView view, String pageUrl, String name) {
        if (view == null || pageUrl == null || name == null) return;
        final Context c = view.getContext();
        final String origin = FontInject.origin(pageUrl);
        if (origin == null) return;
        final byte[] code = read(c, "js/" + name);
        if (code == null) return;
        final String js = new String(code).trim();
        final String script = "(function(){try{if(window['__vieJs_" + name.replace("-", "_") + "'])return;"
                + "var s=document.createElement('script');s.src='" + origin + JS_SEG + name + "?v='+Date.now();"
                + "s.async=false;s.onerror=function(){window['__vieJs_" + name.replace("-", "_") + "']='err';};"
                + "(document.head||document.documentElement).appendChild(s);}catch(e){}})();";
        view.post(() -> {
            try {
                view.evaluateJavascript(script, null);
                view.postDelayed(() -> view.evaluateJavascript(
                        "(function(){return window['__vieJs_" + name.replace("-", "_") + "']?1:0;})();",
                        value -> {
                            if (value != null && value.contains("0")) view.evaluateJavascript(js, null);
                        }), 700);
            } catch (Exception ignored) {
            }
        });
    }
}
