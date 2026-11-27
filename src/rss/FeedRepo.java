package kawaii.viey.browser;

import android.content.Context;
import android.text.TextUtils;
import android.webkit.WebSettings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 订阅刷新与条目缓存：filesDir/rss/items/<sha1(url)>.json
 * 未读数 = 缓存条目里不在 read 集合中的数量（不发网络请求也能算）。
 */
public class FeedRepo {

    /** 每个源最多保留的条目数 */
    private static final int KEEP = 60;

    private static File itemsDir(Context c) {
        File d = new File(c.getFilesDir(), "rss/items");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static File itemsFile(Context c, FeedSource s) {
        return new File(itemsDir(c), FeedState.key(s.url) + ".json");
    }

    public static synchronized List<FeedParser.Article> loadItems(Context c, FeedSource s) {
        List<FeedParser.Article> out = new ArrayList<>();
        File f = itemsFile(c, s);
        if (!f.exists()) return out;
        try (InputStream in = new FileInputStream(f)) {
            String s2 = readAll(in);
            JSONObject o = new JSONObject(s2);
            JSONArray a = o.optJSONArray("items");
            if (a == null) return out;
            for (int i = 0; i < a.length(); i++) {
                JSONObject it = a.optJSONObject(i);
                if (it == null) continue;
                FeedParser.Article art = new FeedParser.Article();
                art.title = it.optString("title", "");
                art.link = it.optString("link", "");
                art.pubDate = it.optString("pubDate", "");
                art.description = it.optString("description", "");
                art.image = it.optString("image", "");
                art.source = s.displayTitle();
                out.add(art);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void saveItems(Context c, FeedSource s, List<FeedParser.Article> items) {
        // 与旧缓存按 link 合并，新条目优先，最多 KEEP 条
        List<FeedParser.Article> merged = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (FeedParser.Article a : items) {
            if (!TextUtils.isEmpty(a.link)) {
                if (!seen.add(a.link)) continue;
            }
            if (merged.size() >= KEEP) break;
            merged.add(a);
        }
        for (FeedParser.Article a : loadItems(c, s)) {
            if (merged.size() >= KEEP) break;
            if (TextUtils.isEmpty(a.link)) continue;
            if (!seen.add(a.link)) continue;
            merged.add(a);
        }
        items = merged;
        try {
            JSONArray a = new JSONArray();
            for (int i = 0; i < Math.min(KEEP, items.size()); i++) {
                FeedParser.Article art = items.get(i);
                JSONObject o = new JSONObject();
                o.put("title", art.title == null ? "" : art.title);
                o.put("link", art.link == null ? "" : art.link);
                o.put("pubDate", art.pubDate == null ? "" : art.pubDate);
                o.put("description", art.description == null ? "" : art.description);
                o.put("image", art.image == null ? "" : art.image);
                a.put(o);
            }
            JSONObject root = new JSONObject();
            root.put("time", System.currentTimeMillis());
            root.put("items", a);
            try (OutputStream out = new FileOutputStream(itemsFile(c, s))) {
                out.write(root.toString().getBytes("UTF-8"));
            }
        } catch (Exception ignored) {
        }
    }

    public static long cachedTime(Context c, FeedSource s) {
        File f = itemsFile(c, s);
        return f.exists() ? f.lastModified() : 0L;
    }

    /** 用已读快照算未读（批量统计用，避免逐条读 state.json） */
    public static int unreadCount(Context c, FeedSource s, java.util.Set<String> read) {
        int n = 0;
        for (FeedParser.Article a : loadItems(c, s)) {
            if (!TextUtils.isEmpty(a.link) && !read.contains(FeedState.key(a.link))) n++;
        }
        return n;
    }

    public static int unreadCount(Context c, FeedSource s) {
        return unreadCount(c, s, FeedState.readKeys(c));
    }

    public static int totalUnread(Context c) {
        java.util.Set<String> read = FeedState.readKeys(c);
        int n = 0;
        for (FeedSource s : FeedStore.load(c)) if (s.enabled) n += unreadCount(c, s, read);
        return n;
    }

    /** 抓一个源并写缓存；返回抓到的条数，异常向上抛 */
    public static int refresh(Context c, FeedSource s) throws Exception {
        String body = fetch(c, s.url, s);
        List<FeedParser.Article> items = FeedParser.parse(body, s, s.url);
        if (items.isEmpty()) throw new Exception("解析不到条目（检查栏目规则或地址）");
        for (FeedParser.Article a : items) a.source = s.displayTitle();
        saveItems(c, s, items);
        s.lastUpdate = System.currentTimeMillis();
        FeedStore.touch(c, s);
        return items.size();
    }

    /** 并发刷新全部启用源，返回成功数 */
    public static int refreshAll(Context c) {
        List<FeedSource> sources = new ArrayList<>();
        for (FeedSource s : FeedStore.load(c)) if (s.enabled) sources.add(s);
        if (sources.isEmpty()) return 0;
        int threads = Math.min(4, sources.size());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Boolean>> fs = new ArrayList<>();
        for (final FeedSource s : sources) {
            fs.add(pool.submit((Callable<Boolean>) () -> {
                try {
                    refresh(c, s);
                    return true;
                } catch (Exception e) {
                    android.util.Log.w("viey-rss", s.displayTitle() + " 刷新失败：" + e.getMessage());
                    return false;
                }
            }));
        }
        int ok = 0;
        for (Future<Boolean> f : fs) {
            try {
                if (f.get()) ok++;
            } catch (Exception ignored) {
            }
        }
        pool.shutdownNow();
        return ok;
    }

    /** 全部源的缓存条目合并，按时间倒序 */
    public static List<FeedParser.Article> cachedAll(Context c) {
        List<FeedParser.Article> out = new ArrayList<>();
        for (FeedSource s : FeedStore.load(c)) if (s.enabled) out.addAll(loadItems(c, s));
        Collections.sort(out, (a, b) -> {
            String x = a.pubDate == null ? "" : a.pubDate;
            String y = b.pubDate == null ? "" : b.pubDate;
            return y.compareTo(x);
        });
        return out;
    }

    public static String fetch(Context c, String url, FeedSource s) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setInstanceFollowRedirects(true);
        String ua = WebSettings.getDefaultUserAgent(c);
        conn.setRequestProperty("User-Agent", TextUtils.isEmpty(ua) ? "Mozilla/5.0 (Linux; Android) VieY" : ua);
        conn.setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml, text/html, */*");
        if (s != null) FeedParser.applyHeader(conn, s.header);
        int code = conn.getResponseCode();
        if (code >= 400) throw new Exception("HTTP " + code);
        try (InputStream in = conn.getInputStream()) {
            return new String(readBytes(in), charsetOf(conn));
        }
    }

    static String readAll(InputStream in) throws Exception {
        return new String(readBytes(in), "UTF-8");
    }

    static byte[] readBytes(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    /** 从 Content-Type 取 charset，取不到按 UTF-8 */
    static String charsetOf(HttpURLConnection conn) {
        try {
            String ct = conn.getContentType();
            if (ct != null) {
                for (String part : ct.split(";")) {
                    String p = part.trim();
                    if (p.toLowerCase().startsWith("charset=")) {
                        String cs = p.substring(8).replace("\"", "").trim();
                        if (!TextUtils.isEmpty(cs)) return cs;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "UTF-8";
    }
}
