package kawaii.viey.browser;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 用户脚本（UserScript）插件：替代 6.5.0 的插件机制。
 * 目录 filesDir/plugins，*.user.js 启用，*.user.js.off 停用。
 * 元数据解析 // ==UserScript== 头：@name @match @include @run-at。
 */
public class PluginStore {

    public static class Plugin {
        public String name = "";
        public String namespace = "";
        public String matches = "";
        public String runAt = "document-end";
        public String code = "";
        public boolean enabled = true;
        public File file;

        public String subtitle() {
            return (enabled ? "已启用" : "已停用") + " · " + runAt
                    + (matches.length() > 0 ? " · " + matches.replace("\n", " ") : "");
        }
    }

    public static final String DEFAULT_MARKET = "https://greasyfork.org/zh-CN/scripts";

    public static File dir(Context c) {
        File d = new File(c.getFilesDir(), "plugins");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static String marketUrl(Context c) {
        String v = VieYApp.getPrefs(c).getString("plugin_market", "");
        return v == null || v.length() == 0 ? DEFAULT_MARKET : v;
    }

    public static void setMarketUrl(Context c, String url) {
        VieYApp.getPrefs(c).edit().putString("plugin_market", url == null ? "" : url.trim()).apply();
    }

    public static List<Plugin> load(Context c) {
        List<Plugin> out = new ArrayList<>();
        File[] files = dir(c).listFiles();
        if (files == null) return out;
        List<File> sorted = new ArrayList<>();
        Collections.addAll(sorted, files);
        Collections.sort(sorted, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File f : sorted) {
            String n = f.getName();
            if (!n.endsWith(".user.js") && !n.endsWith(".user.js.off")) continue;
            Plugin p = parse(c, f);
            if (p != null) out.add(p);
        }
        return out;
    }

    private static Plugin parse(Context c, File f) {
        try {
            String code = read(f);
            Plugin p = new Plugin();
            p.file = f;
            p.code = code;
            p.enabled = !f.getName().endsWith(".off");
            String name = "";
            StringBuilder matches = new StringBuilder();
            for (String line : code.split("\n")) {
                String t = line.trim();
                if (t.startsWith("// @name") && !t.startsWith("// @namespace")) name = t.substring(t.indexOf('@') + 5).trim();
                else if (t.startsWith("// @match") || t.startsWith("// @include")) {
                    String m = t.substring(t.indexOf('@') + (t.startsWith("// @match") ? 6 : 8)).trim();
                    if (m.length() > 0) matches.append(m).append(matches.length() == 0 ? "" : "\n");
                } else if (t.startsWith("// @run-at")) p.runAt = t.substring(8).trim();
                else if (t.equals("// ==/UserScript==")) break;
            }
            p.name = name.length() == 0 ? f.getName().replace(".user.js.off", "").replace(".user.js", "") : name;
            p.matches = matches.toString();
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    public static File save(Context c, String code, String hintName) throws Exception {
        String name = hintName;
        for (String line : code.split("\n")) {
            String t = line.trim();
            if (t.startsWith("// @name")) {
                name = t.substring(t.indexOf('@') + 5).trim();
                break;
            }
        }
        if (name == null || name.length() == 0) name = "script_" + System.currentTimeMillis();
        String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        File f = new File(dir(c), safe + ".user.js");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(code.getBytes("UTF-8"));
        }
        return f;
    }

    public static void setEnabled(Plugin p, boolean on) {
        String base = p.file.getName().replace(".user.js.off", ".user.js");
        File target = new File(p.file.getParentFile(), on ? base : base + ".off");
        p.file.renameTo(target);
        p.enabled = on;
    }

    public static void remove(Plugin p) {
        p.file.delete();
    }

    /** 生成匹配当前 URL 的注入代码（多个脚本按文件名顺序拼接） */
    public static String buildInjection(Context c, String url) {
        if (url == null || !url.startsWith("http")) return "";
        List<Plugin> all = load(c);
        StringBuilder sb = new StringBuilder();
        for (Plugin p : all) {
            if (!p.enabled) continue;
            if (!matches(p, url)) continue;
            if (p.runAt.contains("document-start")) {
                // document-start 脚本放前面，这里统一按顺序拼接
                sb.append("(function(){try{").append(p.code).append("\n}catch(e){console.error('[plugin:").append(p.name.replace("'", "")).append("]',e);}})();\n");
            } else {
                sb.append("(function(){try{").append(p.code).append("\n}catch(e){console.error('[plugin:").append(p.name.replace("'", "")).append("]',e);}})();\n");
            }
        }
        return sb.length() == 0 ? "" : "(function(){if(window.__viePluginsDone)return;window.__viePluginsDone=1;\n" + sb + "})();";
    }

    public static boolean matches(Plugin p, String url) {
        if (p.matches == null || p.matches.trim().length() == 0) return false;
        String u = url.toLowerCase(Locale.ROOT);
        for (String raw : p.matches.split("\n")) {
            String pat = raw.trim();
            if (pat.length() == 0) continue;
            String regex = pat.toLowerCase(Locale.ROOT)
                    .replace(".", "\\.")
                    .replace("*", ".*")
                    .replace("?", ".")
                    .replace("\\/", "/");
            try {
                if (u.matches(regex)) return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    public static String read(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        }
    }
}
