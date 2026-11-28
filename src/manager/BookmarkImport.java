package kawaii.viey.browser;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 书签导入：把其他浏览器导出的书签合并进本地书签树。
 *
 * 支持两种通用格式，不依赖额外解析库：
 * - Netscape bookmark HTML：Chrome / Edge / Firefox / Safari 的「导出书签」都是这种；
 * - JSON：接受 `[{title,url}]` 数组、`{bookmarks:[...]}` 包装对象，以及本应用自身的导出结构。
 *
 * 导入结果统一挂到「导入 <日期>」文件夹下，已存在的 URL 会被跳过，不覆盖原书签。
 */
public class BookmarkImport {

	private static final Pattern ENTRY = Pattern.compile(
		"<h3[^>]*>(.*?)</h3>|<a[^>]*href\\s*=\\s*\"([^\"]+)\"[^>]*>(.*?)</a>",
		Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

	private static final Pattern TAG = Pattern.compile("<[^>]+>");

	/** 一条待导入的书签 */
	private static class Item {
		final String title;
		final String url;
		final String folder;

		Item(String title, String url, String folder) {
			this.title = title;
			this.url = url;
			this.folder = folder;
		}
	}

	/** 导入结果 */
	public static class Result {
		public int added;
		public int skipped;
		public int folders;

		public String text() {
			return "导入 " + added + " 条，跳过重复 " + skipped + " 条，新建文件夹 " + folders + " 个";
		}
	}

	public static Result importText(Context c, String text) {
		Result r = new Result();
		if (text == null || text.trim().isEmpty()) return r;
		List<Item> items = text.contains("<")
			? parseHtml(text)
			: parseJson(text);
		if (items.isEmpty()) return r;

		String folderName = "导入 " + new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
		List<BookmarkManager.Bookmark> root = BookmarkManager.getBookmarks(c);
		BookmarkManager.Bookmark folder = null;

		for (Item it : items) {
			if (it.url == null || it.url.isEmpty()) continue;
			if (it.url.startsWith("javascript:") || it.url.startsWith("place:")) continue;
			if (containsUrl(root, it.url)) {
				r.skipped++;
				continue;
			}
			String name = it.folder == null || it.folder.isEmpty() ? folderName : it.folder;
			BookmarkManager.Bookmark target = findFolder(root, name);
			if (target == null) {
				target = BookmarkManager.Bookmark.createFolder(name);
				if (target.children == null) target.children = new ArrayList<>();
				root.add(0, target);
				r.folders++;
			}
			if (target.children == null) target.children = new ArrayList<>();
			target.children.add(new BookmarkManager.Bookmark(it.title, it.url));
			r.added++;
		}

		if (r.added > 0) BookmarkManager.saveBookmarks(c, root);
		return r;
	}

	private static List<Item> parseHtml(String html) {
		List<Item> out = new ArrayList<>();
		Matcher m = ENTRY.matcher(html);
		String current = null;
		while (m.find()) {
			if (m.group(1) != null) {
				String f = plain(m.group(1));
				current = f.isEmpty() ? null : f;
				continue;
			}
			String url = m.group(2) == null ? "" : m.group(2).trim();
			String title = plain(m.group(3) == null ? "" : m.group(3));
			if (url.isEmpty()) continue;
			out.add(new Item(title.isEmpty() ? url : title, url, current));
		}
		return out;
	}

	private static List<Item> parseJson(String text) {
		List<Item> out = new ArrayList<>();
		try {
			Object v = new JSONTokener(text).nextValue();
			if (v instanceof JSONArray) {
				collectArray((JSONArray) v, null, out);
			} else if (v instanceof JSONObject) {
				JSONObject o = (JSONObject) v;
				JSONArray arr = o.optJSONArray("bookmarks");
				if (arr == null) arr = o.optJSONArray("children");
				if (arr == null) arr = o.optJSONArray("items");
				if (arr != null) collectArray(arr, null, out);
			}
		} catch (Exception ignore) {
			// 非法 JSON 视为无内容
		}
		return out;
	}

	private static void collectArray(JSONArray arr, String folder, List<Item> out) {
		for (int k = 0; k < arr.length(); k++) {
			JSONObject o = arr.optJSONObject(k);
			if (o == null) continue;
			boolean isFolder = o.optBoolean("isFolder", false) || o.optBoolean("folder", false);
			String title = o.optString("title", "");
			String url = o.optString("url", "");
			JSONArray children = o.optJSONArray("children");
			if (isFolder || (children != null && url.isEmpty())) {
				collectArray(children == null ? new JSONArray() : children,
					title.isEmpty() ? folder : title, out);
				continue;
			}
			if (url.isEmpty()) continue;
			out.add(new Item(title.isEmpty() ? url : title, url, folder));
		}
	}

	private static boolean containsUrl(List<BookmarkManager.Bookmark> list, String url) {
		for (BookmarkManager.Bookmark b : list) {
			if (b == null) continue;
			if (url.equals(b.url)) return true;
			if (b.children != null && containsUrl(b.children, url)) return true;
		}
		return false;
	}

	private static BookmarkManager.Bookmark findFolder(List<BookmarkManager.Bookmark> list, String title) {
		for (BookmarkManager.Bookmark b : list) {
			if (b == null) continue;
			if (b.isFolder && title.equals(b.title)) return b;
		}
		return null;
	}

	private static String plain(String s) {
		if (s == null) return "";
		String t = TAG.matcher(s).replaceAll("");
		t = t.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
			.replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
			.replace("&#x27;", "'");
		return t.trim();
	}
}
