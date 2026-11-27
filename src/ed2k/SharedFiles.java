package kawaii.viey.browser;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 共享文件索引：ed2k 目录里的完整文件，hash → 文件。hash 懒计算并缓存。
 */
public class SharedFiles {

    private static final Map<String, Entry> index = new HashMap<>();
    private static boolean loaded = false;

    public static class Entry {
        public String hash;
        public String name;
        public long size;
        public File file;
    }

    public static synchronized void load(Context c) {
        if (loaded) return;
        loaded = true;
        try {
            File idx = new File(c.getFilesDir(), "ed2k_shared.json");
            if (!idx.exists()) return;
            JSONObject o = new JSONObject(PluginStore.read(idx));
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String key = it.next();
                JSONObject v = o.getJSONObject(key);
                Entry e = new Entry();
                e.hash = key;
                e.name = v.optString("name");
                e.size = v.optLong("size");
                e.file = new File(v.optString("path"));
                if (e.file.exists()) index.put(key, e);
            }
        } catch (Exception ignored) {
        }
    }

    public static synchronized void save(Context c) {
        try {
            JSONObject o = new JSONObject();
            for (Entry e : index.values()) {
                JSONObject v = new JSONObject();
                v.put("name", e.name);
                v.put("size", e.size);
                v.put("path", e.file.getAbsolutePath());
                o.put(e.hash, v);
            }
            java.io.FileOutputStream fos = new java.io.FileOutputStream(new File(c.getFilesDir(), "ed2k_shared.json"));
            fos.write(o.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Exception ignored) {
        }
    }

    public static List<Entry> scan(Context c) {
        load(c);
        List<Entry> out = new ArrayList<>();
        File dir = PartFile.dir(c);
        File[] fs = dir.listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            if (f.getName().endsWith(".part") || f.getName().endsWith(".state")) continue;
            if (!f.isFile() || f.length() == 0) continue;
            Entry e = findByName(f);
            if (e == null) {
                String h;
                try {
                    h = Ed2kHash.hashFile(f);
                } catch (Exception ex) {
                    continue;
                }
                e = new Entry();
                e.hash = h;
                e.name = f.getName();
                e.size = f.length();
                e.file = f;
                index.put(h, e);
            }
            out.add(e);
        }
        save(c);
        return out;
    }

    private static Entry findByName(File f) {
        for (Entry e : index.values()) if (e.file.equals(f) || e.file.getAbsolutePath().equals(f.getAbsolutePath())) return e;
        return null;
    }

    public static Entry add(Context c, File f) {
        load(c);
        try {
            Entry e = new Entry();
            e.hash = Ed2kHash.hashFile(f);
            e.name = f.getName();
            e.size = f.length();
            e.file = f;
            index.put(e.hash, e);
            save(c);
            return e;
        } catch (Exception ex) {
            return null;
        }
    }

    public static Entry byHash(Context c, String hash) {
        load(c);
        return index.get(hash.toLowerCase());
    }

    public static void remove(String hash) {
        index.remove(hash);
    }
}
