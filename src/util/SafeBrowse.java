package kawaii.viey.browser;

import android.content.Context;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 简易安全浏览（移植 Vie 6.5.0 的“威胁提示/继续访问”拦截页）。
 * 规则来源：filesDir/blocklist.txt，每行一个 host 或 host 后缀；# 开头为注释。
 */
public class SafeBrowse {

    public static final String FILE = "blocklist.txt";

    public static boolean enabled(Context c) {
        return "true".equals(VieYApp.getPrefs(c).getString("safe_browsing", "false"));
    }

    public static void setEnabled(Context c, boolean on) {
        VieYApp.getPrefs(c).edit().putString("safe_browsing", on ? "true" : "false").apply();
    }

    private static List<String> rules(Context c) {
        List<String> out = new ArrayList<>();
        try {
            File f = new File(c.getFilesDir(), FILE);
            InputStream in = f.exists() ? new java.io.FileInputStream(f) : c.getAssets().open(FILE);
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim().toLowerCase(Locale.ROOT);
                if (line.length() == 0 || line.startsWith("#")) continue;
                out.add(line);
            }
            br.close();
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 命中返回威胁类型描述，未命中返回 null */
    public static String threat(Context c, String url) {
        if (!enabled(c) || url == null) return null;
        if (!url.startsWith("http")) return null;
        String host;
        try {
            host = new java.net.URL(url).getHost();
        } catch (Exception e) {
            return null;
        }
        if (host == null) return null;
        host = host.toLowerCase(Locale.ROOT);
        for (String rule : rules(c)) {
            if (rule.startsWith("*.")) {
                String tail = rule.substring(1); // ".bad.com"
                if (host.endsWith(tail)) return "潜在有害应用（规则 " + rule + "）";
            } else if (host.equals(rule) || host.endsWith("." + rule)) {
                return "钓鱼网站 / 恶意软件（规则 " + rule + "）";
            }
        }
        return null;
    }

    public static String warningHtml(String url, String reason) {
        String u = url == null ? "" : url.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;");
        return "<!DOCTYPE html><html><head><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<style>body{background:#111;color:#eee;font-family:sans-serif;padding:28px 20px}"
                + "h2{color:#ff5a5a;font-size:20px}p{font-size:14px;line-height:1.6;color:#aaa}"
                + ".u{word-break:break-all;color:#6cf;font-size:13px}"
                + "a{display:block;margin-top:14px;padding:12px;text-align:center;border-radius:8px;text-decoration:none;font-size:15px}"
                + ".go{background:#2a2a2a;color:#ff8080;border:1px solid #444}"
                + ".back{background:#00b3b3;color:#fff}</style></head><body>"
                + "<h2>⚠ 已拦截可疑页面</h2>"
                + "<p>威胁类型：" + reason + "</p>"
                + "<p class=u>" + u + "</p>"
                + "<a class=back href=\"javascript:history.back()\">返回上一页</a>"
                + "<a class=go href=\"javascript:viey.loadUrl(" + jsStr(url) + ")\">仍要继续访问</a>"
                + "</body></html>";
    }

    private static String jsStr(String s) {
        if (s == null) return "''";
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
