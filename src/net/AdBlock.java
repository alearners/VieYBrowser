package kawaii.viey.browser;

import android.content.Context;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 规则式广告拦截（Adblock Plus 语法子集），规则可手动填写，也可订阅远程列表。
 *
 * 规则文件每行一条：
 *   # 注释
 *   ||ads.example.com^        主机名（含子域）拦截
 *   ads.example.com           同上（裸主机名）
 *   /banner/                   URL 包含该片段即拦截
 *   *://*.example.com/ads/*    带通配符的 URL 匹配
 *   @@||good.example.com^     白名单，优先于拦截规则
 */
public class AdBlock {

	public static final String PREF = "adblock_prefs";
	private static final String KEY_ON = "adblock_on";
	private static final String KEY_RULES = "adblock_rules";
	private static final String KEY_SUBS = "adblock_subs";
	private static final String KEY_HITS = "adblock_hits";
	private static final String KEY_SEEDED = "adblock_seeded";

	/** 内置基础规则，首次开启时写入，之后可自行删改 */
	private static final String DEFAULT_RULES =
		"# VieY 内置基础规则（可删改）\n"
			+ "doubleclick.net\n"
			+ "googlesyndication.com\n"
			+ "googleadservices.com\n"
			+ "adservice.google.com\n"
			+ "amazon-adsystem.com\n"
			+ "adnxs.com\n"
			+ "criteo.com\n"
			+ "taboola.com\n"
			+ "outbrain.com\n"
			+ "ads.yahoo.com\n"
			+ "scorecardresearch.com\n"
			+ "ad-delivery.net\n"
			+ "/ad_banner/\n"
			+ "/ads/banner/\n";

	private static String cachedRaw = null;
	private static List<String> blockedHosts = new ArrayList<>();
	private static List<String> allowHosts = new ArrayList<>();
	private static List<String> blockedSubs = new ArrayList<>();
	private static List<String> allowSubs = new ArrayList<>();

	private static final AtomicInteger pending = new AtomicInteger(0);

	private static android.content.SharedPreferences sp(Context c) {
		return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
	}

	public static boolean enabled(Context c) {
		return sp(c).getBoolean(KEY_ON, false);
	}

	public static void setEnabled(Context c, boolean on) {
		sp(c).edit().putBoolean(KEY_ON, on).apply();
		if (on) seed(c);
	}

	/** 首次开启时写入内置规则，用户清空后不会再次覆盖 */
	private static void seed(Context c) {
		if (sp(c).getBoolean(KEY_SEEDED, false)) return;
		if (sp(c).getString(KEY_RULES, "").trim().isEmpty()) {
			sp(c).edit().putString(KEY_RULES, DEFAULT_RULES).putBoolean(KEY_SEEDED, true).apply();
		} else {
			sp(c).edit().putBoolean(KEY_SEEDED, true).apply();
		}
	}

	public static String rules(Context c) {
		return sp(c).getString(KEY_RULES, "");
	}

	public static void setRules(Context c, String text) {
		sp(c).edit().putString(KEY_RULES, text == null ? "" : text).apply();
		cachedRaw = null;
	}

	public static String subs(Context c) {
		return sp(c).getString(KEY_SUBS, "");
	}

	public static void setSubs(Context c, String text) {
		sp(c).edit().putString(KEY_SUBS, text == null ? "" : text).apply();
	}

	public static int hits(Context c) {
		return sp(c).getInt(KEY_HITS, 0);
	}

	public static void resetHits(Context c) {
		sp(c).edit().putInt(KEY_HITS, 0).apply();
	}

	/** 规则条数（不含注释与空行） */
	public static int ruleCount(Context c) {
		parse(c);
		return blockedHosts.size() + blockedSubs.size() + allowHosts.size() + allowSubs.size();
	}

	private static void parse(Context c) {
		String raw = rules(c);
		if (raw != null && raw.equals(cachedRaw)) return;
		cachedRaw = raw;
		blockedHosts = new ArrayList<>();
		allowHosts = new ArrayList<>();
			blockedSubs = new ArrayList<>();
		allowSubs = new ArrayList<>();
		if (raw == null) return;
		for (String line : raw.split("\n")) {
			String s = line.trim();
			if (s.isEmpty() || s.startsWith("#") || s.startsWith("!")) continue;
			boolean allow = false;
			if (s.startsWith("@@")) {
				allow = true;
				s = s.substring(2);
			}
			if (s.startsWith("||")) s = s.substring(2);
			if (s.endsWith("^")) s = s.substring(0, s.length() - 1);
			if (s.isEmpty()) continue;
			boolean pattern = s.contains("*") || s.startsWith("/") || s.contains("/");
			if (pattern) {
				(allow ? allowSubs : blockedSubs).add(s.toLowerCase());
			} else {
				(allow ? allowHosts : blockedHosts).add(s.toLowerCase());
			}
		}
	}

	/** 命中拦截规则返回 true */
	public static boolean blocked(Context c, String url) {
		if (url == null || !enabled(c)) return false;
		if (url.startsWith("viek://") || url.startsWith("data:") || url.startsWith("blob:")) return false;
		parse(c);
		String low = url.toLowerCase();
		for (String h : allowHosts) if (hostHit(low, h)) return false;
		for (String p : allowSubs) if (wildHit(low, p)) return false;
		for (String h : blockedHosts) if (hostHit(low, h)) { hit(c); return true; }
		for (String p : blockedSubs) if (wildHit(low, p)) { hit(c); return true; }
		return false;
	}

	private static boolean hostHit(String url, String host) {
		int s = url.indexOf("://");
		String rest = s >= 0 ? url.substring(s + 3) : url;
		int e = rest.indexOf('/');
		String h = e >= 0 ? rest.substring(0, e) : rest;
		int at = h.indexOf('@');
		if (at >= 0) h = h.substring(at + 1);
		int colon = h.lastIndexOf(':');
		if (colon > 0) h = h.substring(0, colon);
		return h.equals(host) || h.endsWith("." + host);
	}

	private static boolean wildHit(String url, String pattern) {
		StringBuilder rx = new StringBuilder();
		for (char ch : pattern.toCharArray()) {
			if (ch == '*') rx.append("[^ ]*");
			else if ("\\.[]{}()+-^$|?".indexOf(ch) >= 0) rx.append('\\').append(ch);
			else rx.append(ch);
		}
		try {
			return url.matches(".*" + rx + ".*");
		} catch (Exception e) {
			return url.contains(pattern.replace("*", ""));
		}
	}

	private static void hit(Context c) {
		if (pending.incrementAndGet() % 8 != 0) return;
		flush(c);
	}

	private static void flush(Context c) {
		int n = pending.getAndSet(0);
		if (n <= 0) return;
		sp(c).edit().putInt(KEY_HITS, hits(c) + n).apply();
	}

	/** shouldInterceptRequest 入口：命中则返回空响应 */
	public static WebResourceResponse intercept(Context c, WebResourceRequest req) {
		try {
			if (req == null || req.getUrl() == null) return null;
			if (req.isForMainFrame()) return null;
			String url = req.getUrl().toString();
			if (blocked(c, url)) {
				WebResourceResponse r = new WebResourceResponse("text/plain", "utf-8",
					new ByteArrayInputStream(new byte[0]));
				try {
					r.setStatusCodeAndReasonPhrase(204, "No Content");
				} catch (Throwable ignored) {}
				return r;
			}
		} catch (Throwable ignored) {}
		return null;
	}

	/** 拉取订阅地址（每行一个），把内容并入规则；返回新增行数，失败抛异常由调用方提示 */
	public static int updateSubs(Context c) throws Exception {
		String subs = subs(c).trim();
		if (subs.isEmpty()) return 0;
		StringBuilder add = new StringBuilder();
		int lines = 0;
		for (String u : subs.split("\n")) {
			String url = u.trim();
			if (url.isEmpty() || url.startsWith("#")) continue;
			String text = fetch(url);
			for (String line : text.split("\n")) {
				String s = line.trim();
				if (s.isEmpty() || s.startsWith("#") || s.startsWith("!")) continue;
				if (s.startsWith("[")) continue; // 跳过 Adblock Plus 头部元数据
				add.append(s).append('\n');
				lines++;
			}
		}
		if (lines > 0) {
			StringBuilder merged = new StringBuilder(rules(c));
			merged.append("\n# 订阅更新 ").append(System.currentTimeMillis()).append('\n').append(add);
			setRules(c, merged.toString());
		}
		return lines;
	}

	private static String fetch(String url) throws Exception {
		java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
		conn.setConnectTimeout(12000);
		conn.setReadTimeout(20000);
		conn.setRequestProperty("User-Agent", "VieY-Browser");
		try (java.io.InputStream in = conn.getInputStream()) {
			java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			int n;
			while ((n = in.read(buf)) > 0) {
				bos.write(buf, 0, n);
				if (bos.size() > 4 * 1024 * 1024) break;
			}
			return new String(bos.toByteArray(), "UTF-8");
		} finally {
			conn.disconnect();
		}
	}
}
