package kawaii.viey.browser;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * 镜像重定向：把指定站点在主框架导航时改写为镜像地址。
 *
 * 规则写在偏好项 mirror_rules 里，每行一条，格式为 `源>目标`：
 * - `twitter.com>xcancel.com`：只替换主机名，协议与路径保持不变；
 * - `https://x.com/>https://xcancel.com/`：以协议开头的规则按前缀整体替换。
 *
 * 仅对主框架导航生效，页面内的子资源请求不改写，避免镜像站资源路径不匹配时白屏。
 */
public class MirrorMap {

	private static final String KEY_RULES = "mirror_rules";
	private static final String KEY_ON = "mirror_enabled";

	public static boolean enabled(Context c) {
		return VieYApp.getPrefs(c).getBoolean(KEY_ON, false) && !rules(c).isEmpty();
	}

	public static void setEnabled(Context c, boolean on) {
		VieYApp.getPrefs(c).edit().putBoolean(KEY_ON, on).apply();
	}

	public static String rules(Context c) {
		SharedPreferences p = VieYApp.getPrefs(c);
		String v = p.getString(KEY_RULES, "");
		return v == null ? "" : v;
	}

	public static void setRules(Context c, String v) {
		VieYApp.getPrefs(c).edit().putString(KEY_RULES, v == null ? "" : v).apply();
	}

	/** 供设置页展示的规则条数 */
	public static int ruleCount(Context c) {
		return parse(c).size();
	}

	private static List<String[]> parse(Context c) {
		List<String[]> out = new ArrayList<>();
		for (String line : rules(c).split("\n")) {
			String t = line.trim();
			if (t.isEmpty() || t.startsWith("#")) continue;
			int idx = t.lastIndexOf('>');
			if (idx <= 0 || idx == t.length() - 1) continue;
			out.add(new String[]{t.substring(0, idx).trim(), t.substring(idx + 1).trim()});
		}
		return out;
	}

	public static String rewrite(Context c, String url) {
		if (url == null || !enabled(c)) return url;
		for (String[] r : parse(c)) {
			String from = r[0];
			String to = r[1];
			if (from.contains("://")) {
				if (url.startsWith(from)) return to + url.substring(from.length());
				continue;
			}
			String host = hostOf(url);
			if (host == null) continue;
			if (host.equals(from) || host.endsWith("." + from)) {
				int hs = url.indexOf("://") + 3;
				String rest = url.substring(hs);
				int slash = rest.indexOf('/');
				String tail = slash >= 0 ? rest.substring(slash) : "";
				return url.substring(0, hs) + to + tail;
			}
		}
		return url;
	}

	private static String hostOf(String url) {
		try {
			int hs = url.indexOf("://");
			if (hs < 0) return null;
			String rest = url.substring(hs + 3);
			int end = rest.length();
			for (int k = 0; k < rest.length(); k++) {
				char ch = rest.charAt(k);
				if (ch == '/' || ch == '?' || ch == '#') {
					end = k;
					break;
				}
			}
			String host = rest.substring(0, end);
			int at = host.indexOf('@');
			if (at >= 0) host = host.substring(at + 1);
			int colon = host.lastIndexOf(':');
			if (colon > 0) host = host.substring(0, colon);
			return host.toLowerCase();
		} catch (Exception e) {
			return null;
		}
	}
}
