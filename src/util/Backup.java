package kawaii.viey.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 整体备份：把 shared_prefs 下的全部设置项 + filesDir/rss 下的订阅数据
 * 打包成一个 JSON 文件，导入时按原样写回，实现「换机迁移」。
 *
 * 同时保留阅读（Legado）订阅的子选项：只导出/导入 rss 部分，
 * 走 FeedStore 的 Legado backup.zip，可在阅读 App 内直接恢复。
 */
public class Backup {

	private static final String RSS_DIR = "rss";

	public static String exportJson(Context c) throws Exception {
		JSONObject root = new JSONObject();
		root.put("app", "VieY");
		root.put("version", "0.11.6-dev");
		root.put("time", System.currentTimeMillis());

		JSONObject prefs = new JSONObject();
		for (String name : prefsNames(c)) {
			JSONObject one = new JSONObject();
			Map<String, ?> all = c.getSharedPreferences(name, Context.MODE_PRIVATE).getAll();
			for (Map.Entry<String, ?> e : all.entrySet()) {
				Object v = e.getValue();
				if (v instanceof Set) {
					JSONObject wrap = new JSONObject();
					JSONArray arr = new JSONArray();
					for (Object o : (Set<?>) v) arr.put(String.valueOf(o));
					wrap.put("$set", arr);
					one.put(e.getKey(), wrap);
				} else {
					one.put(e.getKey(), v);
				}
			}
			prefs.put(name, one);
		}
		root.put("prefs", prefs);

		JSONObject rss = new JSONObject();
		File dir = new File(c.getFilesDir(), RSS_DIR);
		if (dir.exists()) collectRss(dir, dir, rss);
		root.put("rss", rss);

		return root.toString();
	}

	private static List<String> prefsNames(Context c) {
		List<String> names = new ArrayList<>();
		try {
			File dir = new File(c.getApplicationInfo().dataDir, "shared_prefs");
			File[] fs = dir.listFiles();
			if (fs != null) {
				for (File f : fs) {
					String n = f.getName();
					if (n.endsWith(".xml")) names.add(n.substring(0, n.length() - 4));
				}
			}
		} catch (Throwable ignored) {}
		if (names.isEmpty()) names.add("VieY");
		return names;
	}

	private static void collectRss(File base, File dir, JSONObject out) {
		File[] fs = dir.listFiles();
		if (fs == null) return;
		for (File f : fs) {
			if (f.isDirectory()) {
				collectRss(base, f, out);
				continue;
			}
			try {
				if (f.length() > 2 * 1024 * 1024) continue;
				String rel = f.getAbsolutePath().substring(base.getAbsolutePath().length() + 1);
				byte[] data = readAllBytes(f);
				JSONObject item = new JSONObject();
				item.put("b64", Base64.encodeToString(data, Base64.NO_WRAP));
				item.put("t", f.lastModified());
				out.put(rel, item);
			} catch (Throwable ignored) {}
		}
	}

	/** 导入整体备份，返回写入的 prefs 文件数 */
	public static int importJson(Context c, String json) throws Exception {
		JSONObject root = new JSONObject(json);
		JSONObject prefs = root.optJSONObject("prefs");
		int n = 0;
		if (prefs != null) {
			Iterator<String> it = prefs.keys();
			while (it.hasNext()) {
				String name = it.next();
				JSONObject one = prefs.optJSONObject(name);
				if (one == null) continue;
				SharedPreferences.Editor ed = c.getSharedPreferences(name, Context.MODE_PRIVATE).edit();
				ed.clear();
				Iterator<String> kits = one.keys();
				while (kits.hasNext()) {
					String k = kits.next();
					Object v = one.opt(k);
					if (v instanceof JSONObject && ((JSONObject) v).optJSONArray("$set") != null) {
						JSONArray arr = ((JSONObject) v).optJSONArray("$set");
						Set<String> set = new HashSet<>();
						for (int i = 0; i < arr.length(); i++) set.add(arr.optString(i));
						ed.putStringSet(k, set);
					} else if (v instanceof Boolean) {
						ed.putBoolean(k, (Boolean) v);
					} else if (v instanceof Integer) {
						ed.putInt(k, (Integer) v);
					} else if (v instanceof Long) {
						ed.putLong(k, (Long) v);
					} else if (v instanceof Double) {
						ed.putFloat(k, ((Double) v).floatValue());
					} else if (v != null) {
						ed.putString(k, String.valueOf(v));
					}
				}
				ed.apply();
				n++;
			}
		}

		JSONObject rss = root.optJSONObject("rss");
		if (rss != null) {
			Iterator<String> it = rss.keys();
			while (it.hasNext()) {
				String rel = it.next();
				JSONObject item = rss.optJSONObject(rel);
				if (item == null) continue;
				try {
					File f = new File(c.getFilesDir(), RSS_DIR + "/" + rel);
					File parent = f.getParentFile();
					if (parent != null && !parent.exists()) parent.mkdirs();
					byte[] data = Base64.decode(item.optString("b64"), Base64.NO_WRAP);
					writeAllBytes(f, data);
					long t = item.optLong("t", 0);
					if (t > 0) f.setLastModified(t);
				} catch (Throwable ignored) {}
			}
		}
		return n;
	}

	/** 只导出阅读订阅（Legado backup.zip，可在阅读里恢复，也可被本软件导入） */
	public static byte[] exportRssZip(Context c) throws Exception {
		java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
		FeedStore.exportTo(c, bos);
		return bos.toByteArray();
	}

	/** 从阅读订阅备份（zip 或 rssSources.json）导入，返回新增条数 */
	public static int importRss(Context c, java.io.InputStream in) throws Exception {
		return FeedStore.importFrom(c, in);
	}

	private static byte[] readAllBytes(File f) throws Exception {
		try (FileInputStream in = new FileInputStream(f)) {
			java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			int n;
			while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
			return bos.toByteArray();
		}
	}

	private static void writeAllBytes(File f, byte[] data) throws Exception {
		try (FileOutputStream out = new FileOutputStream(f)) {
			out.write(data);
		}
	}
}
