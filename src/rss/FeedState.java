package kawaii.viey.browser;

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 订阅阅读状态：已读 / 收藏 / 正文离线缓存。
 * 存储：filesDir/rss/state.json + filesDir/rss/cache/<hash>.html
 */
public class FeedState {

    private static final String DIR = "rss";
    private static final String FILE = "state.json";

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), DIR);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static File stateFile(Context c) {
        return new File(dir(c), FILE);
    }

    /** 内存缓存：state.json 会被 unreadCount 逐条读，避免每次解析磁盘文件 */
    private static JSONObject memObj;
    private static long memMtime = -1L;

    public static synchronized JSONObject load(Context c) {
        File f = stateFile(c);
        if (!f.exists()) {
            memObj = new JSONObject();
            memMtime = 0L;
            return memObj;
        }
        long m = f.lastModified();
        if (memObj != null && m == memMtime) return memObj;
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
            memObj = new JSONObject(sb.toString());
            memMtime = m;
            return memObj;
        } catch (Exception e) {
            memObj = new JSONObject();
            memMtime = m;
            return memObj;
        }
    }

    private static synchronized void save(Context c, JSONObject o) {
        try (OutputStream out = new FileOutputStream(stateFile(c))) {
            out.write(o.toString().getBytes("UTF-8"));
            out.flush();
        } catch (Exception ignored) {
        }
        memObj = o;
        memMtime = stateFile(c).lastModified();
    }

    /** 已读键集合快照，供批量统计未读 */
    public static java.util.Set<String> readKeys(Context c) {
        java.util.Set<String> out = new java.util.HashSet<>();
        JSONObject read = load(c).optJSONObject("read");
        if (read != null) {
            java.util.Iterator<String> it = read.keys();
            while (it.hasNext()) out.add(it.next());
        }
        return out;
    }

    public static String key(String link) {
        if (TextUtils.isEmpty(link)) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(link.trim().getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 10; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(link.hashCode());
        }
    }

    public static boolean isRead(Context c, String link) {
        String k = key(link);
        return k.length() > 0 && load(c).optJSONObject("read") != null
                && load(c).optJSONObject("read").has(k);
    }

    public static void markRead(Context c, String link) {
        String k = key(link);
        if (k.isEmpty()) return;
        JSONObject o = load(c);
        JSONObject read = o.optJSONObject("read");
        if (read == null) read = new JSONObject();
        try {
            read.put(k, System.currentTimeMillis());
            o.put("read", read);
        } catch (Exception ignored) {
        }
        save(c, o);
    }

    public static void markUnread(Context c, String link) {
        String k = key(link);
        if (k.isEmpty()) return;
        JSONObject o = load(c);
        JSONObject read = o.optJSONObject("read");
        if (read != null) {
            read.remove(k);
            save(c, o);
        }
    }

    public static boolean isFav(Context c, String link) {
        String k = key(link);
        return k.length() > 0 && load(c).optJSONObject("fav") != null
                && load(c).optJSONObject("fav").has(k);
    }

    /** 返回切换后的状态：true=已收藏 */
    public static boolean toggleFav(Context c, FeedParser.Article a, String sourceName) {
        String k = key(a.link);
        if (k.isEmpty()) return false;
        JSONObject o = load(c);
        JSONObject fav = o.optJSONObject("fav");
        if (fav == null) fav = new JSONObject();
        boolean now;
        try {
            if (fav.has(k)) {
                fav.remove(k);
                now = false;
            } else {
                JSONObject it = new JSONObject();
                it.put("title", a.title == null ? "" : a.title);
                it.put("link", a.link == null ? "" : a.link);
                it.put("source", sourceName == null ? "" : sourceName);
                it.put("excerpt", a.description == null ? "" : trim(a.description, 120));
                it.put("time", System.currentTimeMillis());
                fav.put(k, it);
                now = true;
            }
            o.put("fav", fav);
        } catch (Exception e) {
            return isFav(c, a.link);
        }
        save(c, o);
        return now;
    }

    public static List<FeedParser.Article> favArticles(Context c) {
        List<FeedParser.Article> out = new ArrayList<>();
        JSONObject fav = load(c).optJSONObject("fav");
        if (fav == null) return out;
        for (java.util.Iterator<String> it = fav.keys(); it.hasNext(); ) {
            JSONObject o = fav.optJSONObject(it.next());
            if (o == null) continue;
            FeedParser.Article a = new FeedParser.Article();
            a.title = o.optString("title", "");
            a.link = o.optString("link", "");
            a.description = o.optString("excerpt", "");
            a.pubDate = o.optString("source", "");
            out.add(a);
        }
        return out;
    }

    public static int favCount(Context c) {
        JSONObject fav = load(c).optJSONObject("fav");
        return fav == null ? 0 : fav.length();
    }

    /** 正文离线缓存 */
    public static File cacheFile(Context c, String link) {
        File d = new File(dir(c), "cache");
        if (!d.exists()) d.mkdirs();
        return new File(d, key(link) + ".html");
    }

    public static void writeCache(Context c, String link, String html) {
        if (TextUtils.isEmpty(link) || html == null) return;
        try (OutputStream out = new FileOutputStream(cacheFile(c, link))) {
            out.write(html.getBytes("UTF-8"));
        } catch (Exception ignored) {
        }
    }

    public static String readCache(Context c, String link) {
        File f = cacheFile(c, link);
        if (!f.exists()) return null;
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    public static long cacheSize(Context c) {
        File d = new File(dir(c), "cache");
        File[] fs = d.listFiles();
        long total = 0;
        if (fs != null) for (File f : fs) total += f.length();
        return total;
    }

    public static void clearCache(Context c) {
        File d = new File(dir(c), "cache");
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) f.delete();
    }

    private static String trim(String s, int max) {
        String p = s.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        return p.length() > max ? p.substring(0, max) + "…" : p;
    }
}
