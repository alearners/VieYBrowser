package kawaii.viey.browser;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 订阅源持久化 + 阅读(Legado) backup.zip 导入导出。
 * 存储位置：filesDir/rss/sources.json
 */
public class FeedStore {

    private static final String DIR = "rss";
    private static final String FILE = "sources.json";
    public static final String LEGADO_ENTRY = "rssSources.json";

    public static File storeFile(Context c) {
        File dir = new File(c.getFilesDir(), DIR);
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, FILE);
    }

    public static synchronized List<FeedSource> load(Context c) {
        List<FeedSource> list = new ArrayList<>();
        File f = storeFile(c);
        if (!f.exists()) return list;
        try (InputStream in = new FileInputStream(f)) {
            String s = readAll(in);
            JSONArray a = new JSONArray(s);
            for (int k = 0; k < a.length(); k++) {
                JSONObject o = a.optJSONObject(k);
                if (o != null) list.add(FeedSource.fromLegado(o));
            }
        } catch (Exception e) {
            // 文件损坏时保留现场，避免下次保存把用户订阅源整片覆盖
            File bad = new File(f.getParentFile(), "sources.broken-" + System.currentTimeMillis() + ".json");
            try { f.renameTo(bad); } catch (Exception ignored) {
            }
            android.util.Log.w("FeedStore", "sources.json 损坏，已另存为 " + bad.getName(), e);
        }
        return list;
    }

    public static synchronized void save(Context c, List<FeedSource> list) {
        JSONArray a = new JSONArray();
        for (FeedSource s : list) a.put(s.toLegado());
        try (OutputStream out = new FileOutputStream(storeFile(c))) {
            out.write(a.toString().getBytes("UTF-8"));
        } catch (Exception ignored) {
        }
    }

    public static synchronized int upsert(Context c, FeedSource src) {
        List<FeedSource> list = load(c);
        for (int k = 0; k < list.size(); k++) {
            if (list.get(k).url.equals(src.url)) {
                list.set(k, src);
                save(c, list);
                return k;
            }
        }
        list.add(src);
        save(c, list);
        return list.size() - 1;
    }

    /** 只更新 lastUpdate，绝不增删别的源（refresh 用，避免同 URL 多源互相顶掉） */
    public static synchronized void touch(Context c, FeedSource src) {
        List<FeedSource> list = load(c);
        for (FeedSource x : list) {
            if (x.url.equals(src.url) && x.displayTitle().equals(src.displayTitle())) {
                x.lastUpdate = src.lastUpdate;
                save(c, list);
                return;
            }
        }
    }

    /** 按「地址 + 名称」原位替换；找不到才新增（编辑器保存用） */
    public static synchronized void replace(Context c, String oldUrl, String oldName, FeedSource src) {
        List<FeedSource> list = load(c);
        for (int k = 0; k < list.size(); k++) {
            FeedSource x = list.get(k);
            if (x.url.equals(oldUrl) && x.displayTitle().equals(oldName)) {
                list.set(k, src);
                save(c, list);
                return;
            }
        }
        upsert(c, src);
    }

    /** 按「地址 + 名称」删单条 */
    public static synchronized void removeOne(Context c, String url, String name) {
        List<FeedSource> list = load(c);
        for (int k = list.size() - 1; k >= 0; k--) {
            FeedSource x = list.get(k);
            if (x.url.equals(url) && x.displayTitle().equals(name)) list.remove(k);
        }
        save(c, list);
    }

    public static synchronized void remove(Context c, String url) {
        List<FeedSource> list = load(c);
        for (int k = list.size() - 1; k >= 0; k--) {
            if (list.get(k).url.equals(url)) list.remove(k);
        }
        save(c, list);
    }

    /** 从 Legado backup.zip（或单独的 rssSources.json）导入，按 url 去重合并。返回新增条数。 */
    public static synchronized int importFrom(Context c, InputStream in) throws Exception {
        byte[] all = readAllBytes(in);
        String json = null;
        if (all.length >= 2 && (all[0] == 'P' || all[0] == 'p') && all[1] == 'K') {
            try (ZipInputStream zin = new ZipInputStream(new java.io.ByteArrayInputStream(all))) {
                ZipEntry e;
                while ((e = zin.getNextEntry()) != null) {
                    String n = e.getName();
                    if (n != null && (n.endsWith(LEGADO_ENTRY) || n.endsWith("rssSources.json"))) {
                        json = readAll(zin);
                        break;
                    }
                }
            }
        }
        if (json == null) json = new String(all, "UTF-8");
        JSONArray a = new JSONArray(stripBom(json));
        List<FeedSource> list = load(c);
        int added = 0;
        for (int k = 0; k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            if (o == null) continue;
            FeedSource src = FeedSource.fromLegado(o);
            if (src.url == null || src.url.length() == 0) continue;
            boolean exists = false;
            for (FeedSource e : list) {
                if (e.url.equals(src.url)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                list.add(src);
                added++;
            }
        }
        save(c, list);
        return added;
    }

    /** 导出为 Legado 可直接恢复的 backup.zip（内部仅含 rssSources.json）。 */
    public static synchronized int exportTo(Context c, OutputStream out) throws Exception {
        List<FeedSource> list = load(c);
        JSONArray a = new JSONArray();
        for (FeedSource s : list) a.put(s.toLegado());
        try (ZipOutputStream zout = new ZipOutputStream(out)) {
            ZipEntry e = new ZipEntry(LEGADO_ENTRY);
            e.setTime(System.currentTimeMillis());
            zout.putNextEntry(e);
            zout.write(a.toString(2).getBytes("UTF-8"));
            zout.closeEntry();
        }
        return list.size();
    }

    public static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toString("UTF-8");
    }

    private static byte[] readAllBytes(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    private static String stripBom(String s) {
        if (s == null) return "";
        if (s.length() > 0 && s.charAt(0) == '\ufeff') return s.substring(1);
        return s;
    }

    public static String nameFromUri(Uri uri) {
        if (uri == null) return "";
        String last = uri.getLastPathSegment();
        return last == null ? "" : last;
    }
}
