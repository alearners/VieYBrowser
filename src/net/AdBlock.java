package kawaii.viey.browser;

import android.content.Context;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * 广告拦截引擎（Adblock Plus 语法，对齐 Via 的拦截能力）。
 *
 * 网络规则：
 *   ||ads.example.com^            主机锚定（含子域）
 *   ||ads.example.com/promo*      主机 + 路径规则
 *   ads.example.com^              裸主机
 *   /banner/*.gif                 片段通配
 *   /ad[0-9]+/                    正则
 *   |https://a.com/x.js|          首尾锚定
 *   @@||good.com^                 白名单（含 @@/regex/、@@/path/）
 *   $third-party / $~third-party
 *   $domain=a.com|b.com / $domain=~c.com
 *   $image $script $stylesheet $xmlhttprequest $subdocument $media $font
 *   $document $popup $object $other $important
 *
 * 元素隐藏：
 *   example.com##.ad-box     ##div[class^="sponsor"]     example.com#@#.ad-box
 *
 * 解析结果放在 Engine 内，整体构建后原子替换，避免拦截线程读到半成品。
 */
public class AdBlock {

	public static final String PREF = "adblock_prefs";
	private static final String KEY_ON = "adblock_on";
	private static final String KEY_RULES = "adblock_rules";
	private static final String KEY_SUBS = "adblock_subs";
	private static final String KEY_HITS = "adblock_hits";
	private static final String KEY_SEEDED = "adblock_seeded";
	private static final String KEY_SITE_OFF = "adblock_sites_off";
	private static final String KEY_LAST_UPDATE = "adblock_last_update";
	private static final String KEY_AUTO = "adblock_auto";

	private static final String DEFAULT_RULES =
		"! VieY 内置基础规则（可删改）\n"
			+ "||doubleclick.net^\n"
			+ "||googlesyndication.com^\n"
			+ "||googleadservices.com^\n"
			+ "||adservice.google.com^\n"
			+ "||amazon-adsystem.com^\n"
			+ "||adnxs.com^\n"
			+ "||criteo.com^\n"
			+ "||taboola.com^\n"
			+ "||outbrain.com^\n"
			+ "||scorecardresearch.com^\n"
			+ "||ad-delivery.net^\n"
			+ "||pos.baidu.com^\n"
			+ "||cpro.baidu.com^\n"
			+ "||hm.baidu.com^\n"
			+ "||union.baidu.com^\n"
			+ "||tanx.com^\n"
			+ "||mediav.com^\n"
			+ "||irs01.com^\n"
			+ "||cnzz.com^\n"
			+ "||51.la^\n"
			+ "/ad_banner/\n"
			+ "/ads/banner/\n"
			+ "##.ad-banner\n"
			+ "##.adsbygoogle\n";

	private static final int T_IMAGE = 1;
	private static final int T_SCRIPT = 2;
	private static final int T_STYLE = 4;
	private static final int T_OBJECT = 8;
	private static final int T_XHR = 16;
	private static final int T_MEDIA = 32;
	private static final int T_FONT = 64;
	private static final int T_DOC = 128;
	private static final int T_POPUP = 256;
	private static final int T_OTHER = 512;

	/** 单条网络规则 */
	static class Net {
		boolean exception;
		int types;
		boolean thirdParty;
		boolean noThirdParty;
		String[] incDomains;
		String[] excDomains;
		Pattern regex;
		boolean pathOnly;   // 正则只匹配 path+query（主机已由 byHost 桶保证）
		String key;
	}

	/** 编译后的规则集合 */
	static class Engine {
		List<Net> generic = new ArrayList<>();
		Map<String, List<Net>> byHost = new HashMap<>();
		Map<String, List<Net>> byPathFirst = new HashMap<>();
		Map<String, List<String>> hideByDomain = new HashMap<>();
		Map<String, List<String>> hideExceptions = new HashMap<>();
		List<String> hideGeneric = new ArrayList<>();
		int count;
		String cssHost;
		String css;

		void parse(String text) {
			if (text == null) return;
			for (String line : text.split("\n")) {
				String s = line.trim();
				if (s.isEmpty()) continue;
				if (s.startsWith("!") || s.startsWith("[") || s.startsWith(";")) continue;
				if (s.indexOf("##") >= 0 || s.indexOf("#@#") >= 0) {
					if (parseCosmetic(s)) count++;
					continue;
				}
				if (s.startsWith("#")) continue;
				if (parseNet(s) != null) count++;
			}
		}

		boolean parseCosmetic(String s) {
			String domains;
			String sel;
			boolean exception = false;
			int p = s.indexOf("#@#");
			if (p >= 0) {
				exception = true;
				domains = s.substring(0, p);
				sel = s.substring(p + 3);
			} else {
				p = s.indexOf("##");
				if (p < 0) return false;
				domains = s.substring(0, p);
				sel = s.substring(p + 2);
			}
			sel = sel.trim();
			if (sel.isEmpty()) return false;
			if (sel.startsWith("+js") || sel.startsWith("^") || sel.startsWith("#@")) return false;
			String[] ds = domains.trim().isEmpty() ? null : domains.trim().toLowerCase().split(",");
			if (exception) {
				if (ds == null) return false;
				for (String d : ds) {
					String dd = d.trim();
					if (dd.isEmpty()) continue;
					List<String> l = hideExceptions.get(dd);
					if (l == null) { l = new ArrayList<>(); hideExceptions.put(dd, l); }
					l.add(sel);
				}
			} else if (ds == null) {
				if (hideGeneric.size() < 1200) hideGeneric.add(sel);
			} else {
				for (String d : ds) {
					String dd = d.trim();
					if (dd.isEmpty()) continue;
					List<String> l = hideByDomain.get(dd);
					if (l == null) { l = new ArrayList<>(); hideByDomain.put(dd, l); }
					l.add(sel);
				}
			}
			return true;
		}

		Net parseNet(String raw) {
			String s = raw;
			Net r = new Net();
			if (s.startsWith("@@")) {
				r.exception = true;
				s = s.substring(2);
			}
			String options = null;
			int d = s.lastIndexOf('$');
			if (d > 0) {
				options = s.substring(d + 1).toLowerCase();
				s = s.substring(0, d);
			}
			if (s.isEmpty()) return null;
			if (options != null && parseOptions(r, options)) return null;

			boolean anchorStart = false, anchorEnd = false, hostAnchor = false;
			if (s.startsWith("||")) {
				hostAnchor = true;
				s = s.substring(2);
			} else if (s.startsWith("|")) {
				anchorStart = true;
				s = s.substring(1);
			}
			if (s.endsWith("|")) {
				anchorEnd = true;
				s = s.substring(0, s.length() - 1);
			}
			if (s.isEmpty()) return null;

			// 纯正则 /re/
			if (s.length() > 2 && s.startsWith("/") && s.endsWith("/")) {
				try {
					r.regex = Pattern.compile(s.substring(1, s.length() - 1), Pattern.CASE_INSENSITIVE);
					generic.add(r);
					return r;
				} catch (Throwable t) {
					return null;
				}
			}

			if (hostAnchor) {
				String body = s;
				boolean sepTail = body.endsWith("^");
				if (sepTail) body = body.substring(0, body.length() - 1);
				int slash = body.indexOf('/');
				int star = body.indexOf('*');
				int cut = body.length();
				if (slash >= 0) cut = Math.min(cut, slash);
				if (star >= 0) cut = Math.min(cut, star);
				String h = body.substring(0, cut).toLowerCase();
				if (h.isEmpty()) return null;
				if (cut == body.length()) {
					// ||host 或 ||host^：整主机拦截
					r.key = h;
					bucket(h).add(r);
					return r;
				}
				// ||host/path 或 ||host*
				String rest = body.substring(cut);
				if (rest.startsWith("*")) {
					r.key = h;
					bucket(h).add(r);
					return r;
				}
				r.key = h;
				r.pathOnly = true;
				r.regex = compile(rest, true, anchorEnd);
				if (r.regex == null) return null;
				bucket(h).add(r);
				return r;
			}

			// 纯主机（含 "example.com"、"{domain}.com^" 这类）与通用子串规则要分开：
			// 只有形如合法域名的才进主机桶，"-ad-banner." 这类仍走通用正则。
			String hostCandidate = s;
			if (hostCandidate.endsWith("^")) hostCandidate = hostCandidate.substring(0, hostCandidate.length() - 1);
			boolean looksHost = hostCandidate.matches("[a-z0-9][a-z0-9.-]*") && hostCandidate.contains(".")
				&& !hostCandidate.endsWith(".");
			if (!s.contains("/") && !s.contains("*") && !s.contains("?") && looksHost) {
				r.key = hostCandidate.toLowerCase();
				bucket(r.key).add(r);
				return r;
			}

			r.regex = compile(s, anchorStart, anchorEnd);
			if (r.regex == null) return null;
			if (s.startsWith("/")) {
				int slash = s.indexOf('/', 1);
				String seg = slash > 0 ? s.substring(1, slash) : s.substring(1);
				seg = seg.replace("*", "");
				if (seg.length() >= 3 && seg.matches("[a-z0-9_-]+")) {
					List<Net> l = byPathFirst.get(seg);
					if (l == null) { l = new ArrayList<>(); byPathFirst.put(seg, l); }
					l.add(r);
					return r;
				}
			}
			generic.add(r);
			return r;
		}

		String[] appendArr(String[] old, String v) {
			if (old == null) return new String[]{v};
			String[] n = new String[old.length + 1];
			System.arraycopy(old, 0, n, 0, old.length);
			n[old.length] = v;
			return n;
		}

		List<Net> bucket(String host) {
			List<Net> l = byHost.get(host);
			if (l == null) { l = new ArrayList<>(); byHost.put(host, l); }
			return l;
		}

		/** true 表示该规则不可用，丢弃 */
		boolean parseOptions(Net r, String opt) {
			List<String> tokens = new ArrayList<>();
			for (String chunk : opt.split("\\$")) {
				String c = chunk.trim();
				if (c.isEmpty()) continue;
				if (c.startsWith("domain=")) {
					tokens.add(c);
				} else {
					for (String t : c.split(",")) if (!t.trim().isEmpty()) tokens.add(t.trim());
				}
			}
			boolean inDomain = false;
			for (String o : tokens) {
				String t = o.trim();
				if (t.isEmpty()) continue;
				boolean neg = false;
				if (t.startsWith("~")) { neg = true; t = t.substring(1); }
				// $script,domain=a.com,~b.com 这类写法：domain= 之后的 ~项 归到排除表
				if (inDomain && neg && t.matches("[a-z0-9.*_-]+")) {
					r.excDomains = appendArr(r.excDomains, t.toLowerCase());
					continue;
				}
				if (t.equals("third-party") || t.equals("3p")) {
					if (neg) r.noThirdParty = true; else r.thirdParty = true;
				} else if (t.startsWith("domain=")) {
					inDomain = true;
					String v = t.substring(7);
					List<String> inc = new ArrayList<>();
					List<String> exc = new ArrayList<>();
					for (String dv : v.split("[|,]")) {
						String x = dv.trim();
						if (x.isEmpty()) continue;
						if (x.startsWith("~")) exc.add(x.substring(1).toLowerCase());
						else inc.add(x.toLowerCase());
					}
					if (!inc.isEmpty()) r.incDomains = inc.toArray(new String[0]);
					if (!exc.isEmpty()) r.excDomains = exc.toArray(new String[0]);
				} else if (t.equals("image")) {
					r.types |= T_IMAGE;
				} else if (t.equals("script")) {
					r.types |= T_SCRIPT;
				} else if (t.equals("stylesheet") || t.equals("css")) {
					r.types |= T_STYLE;
				} else if (t.equals("object") || t.equals("object-subrequest") || t.equals("subdocument") || t.equals("frame")) {
					r.types |= T_OBJECT;
				} else if (t.equals("xmlhttprequest") || t.equals("xhr")) {
					r.types |= T_XHR;
				} else if (t.equals("media")) {
					r.types |= T_MEDIA;
				} else if (t.equals("font")) {
					r.types |= T_FONT;
				} else if (t.equals("document")) {
					r.types |= T_DOC;
				} else if (t.equals("popup")) {
					r.types |= T_POPUP;
				} else if (t.equals("other")) {
					r.types |= T_OTHER;
				} else if (t.equals("important") || t.equals("match-case") || t.equals("all")) {
					// 兼容项
				} else if (t.startsWith("redirect=") || t.startsWith("csp=") || t.startsWith("removeparam=")
					|| t.startsWith("rewrite=") || t.startsWith("replace=") || t.startsWith("cookie=")
					|| t.startsWith("denyallow=") || t.startsWith("method=")) {
					return true;
				} else if (t.equals("badfilter") || t.equals("csp") || t.equals("rewrite")
					|| t.equals("redirect") || t.equals("removeparam") || t.equals("generichide")
					|| t.equals("elemhide") || t.equals("genericblock") || t.equals("webrtc")
					|| t.equals("websocket") || t.equals("ping") || t.equals("beacon")) {
					return true;
				}
			}
			return false;
		}

		Pattern compile(String body, boolean anchorStart, boolean anchorEnd) {
			StringBuilder rx = new StringBuilder();
			if (anchorStart) rx.append('^');
			else rx.append(".*");
			for (int i = 0; i < body.length(); i++) {
				char ch = body.charAt(i);
				if (ch == '*') {
					rx.append(".*");
				} else if (ch == '^') {
					rx.append("(?:[^a-z0-9._%-]|$)");
				} else if ("\\.[]{}()+-?$|".indexOf(ch) >= 0) {
					rx.append('\\').append(ch);
				} else {
					rx.append(ch);
				}
			}
			if (anchorEnd) rx.append('$');
			try {
				return Pattern.compile(rx.toString(), Pattern.CASE_INSENSITIVE);
			} catch (Throwable t) {
				return null;
			}
		}

		boolean applies(Net r, String url, String pageHost, int type) {
			// pageHost 允许传整条页面 URL（自检/旧调用方），统一归一成主机名
			String ph = pageHost == null ? null : pageHost.toLowerCase();
			if (ph != null && (ph.contains("://") || ph.contains("/"))) ph = host(ph);
			pageHost = ph;
			if (r.incDomains != null && !domainHit(pageHost, r.incDomains)) return false;
			if (r.excDomains != null && domainHit(pageHost, r.excDomains)) return false;
			if (r.types != 0 && type != 0 && (r.types & type) == 0) return false;
			String uh = host(url);
			boolean third = pageHost != null && !pageHost.isEmpty() && uh != null
				&& !pageHost.equals(uh) && !pageHost.endsWith("." + uh) && !uh.endsWith("." + pageHost);
			if (r.thirdParty && !third) return false;
			if (r.noThirdParty && third) return false;
			return true;
		}

		boolean matches(Net r, String url, String pageHost, int type, String path) {
			if (!applies(r, url, pageHost, type)) return false;
			if (r.regex == null) return true; // 纯主机规则，只看选项
			String subject = r.pathOnly ? path : url;
			try {
				return r.regex.matcher(subject).find();
			} catch (Throwable ignored) {
				return false;
			}
		}

		/** 是否有任一规则命中（exception 决定查白名单还是拦截图） */
		boolean any(String url, String pageHost, int type, boolean exception, boolean requireDoc) {
			String h = host(url);
			String path = pathOf(url);
			boolean found = false;
			if (h != null) {
				String probe = h;
				while (probe.length() > 0) {
					List<Net> l = byHost.get(probe);
					if (l != null) {
						for (Net r : l) {
							if (r.exception != exception) continue;
							if (requireDoc && (r.types & T_DOC) == 0) continue;
							if (matches(r, url, pageHost, type, path)) { found = true; break; }
						}
					}
					if (found) break;
					int dot = probe.indexOf('.');
					if (dot < 0) break;
					probe = probe.substring(dot + 1);
				}
			}
			if (found) return true;
			for (Net r : generic) {
				if (r.exception != exception) continue;
				if (requireDoc && (r.types & T_DOC) == 0) continue;
				if (matches(r, url, pageHost, type, path)) return true;
			}
			int start = 1;
			while (start < path.length()) {
				int e = path.indexOf('/', start);
				String seg = e > 0 ? path.substring(start, e) : path.substring(start);
				if (!seg.isEmpty()) {
					List<Net> l = byPathFirst.get(seg);
					if (l != null) {
						for (Net r : l) {
							if (r.exception != exception) continue;
							if (!applies(r, url, pageHost, type)) continue;
							if (r.regex != null && r.regex.matcher(url).find()) return true;
						}
					}
				}
				if (e < 0) break;
				start = e + 1;
				if (start > 60) break;
			}
			return false;
		}

		boolean blocked(String url, String pageHost, int type) {
			if (any(url, pageHost, type, true, false)) return false;
			return any(url, pageHost, type, false, false);
		}

		String hideCss(String hostName) {
			if (hostName == null) return null;
			if (hostName.equals(cssHost)) return css;
			List<String> sels = new ArrayList<>();
			String probe = hostName;
			while (probe.length() > 0) {
				List<String> l = hideByDomain.get(probe);
				if (l != null) sels.addAll(l);
				int dot = probe.indexOf('.');
				if (dot < 0) break;
				probe = probe.substring(dot + 1);
			}
			if (!hideGeneric.isEmpty()) sels.addAll(hideGeneric);
			List<String> exc = new ArrayList<>();
			probe = hostName;
			while (probe.length() > 0) {
				List<String> l = hideExceptions.get(probe);
				if (l != null) exc.addAll(l);
				int dot = probe.indexOf('.');
				if (dot < 0) break;
				probe = probe.substring(dot + 1);
			}
			if (sels.isEmpty()) { cssHost = hostName; css = null; return null; }
			StringBuilder b = new StringBuilder();
			int n = 0;
			for (String sel : sels) {
				if (exc.contains(sel) || sel.contains("{")) continue;
				b.append(sel).append("{display:none!important}\n");
				if (++n >= 3000) break;
			}
			String out = b.length() == 0 ? null : b.toString();
			cssHost = hostName;
			css = out;
			return out;
		}
	}

	private static volatile Engine cur = null;
	private static String cachedRaw = null;
	private static String cachedSub = null;

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

	private static void seed(Context c) {
		if (sp(c).getBoolean(KEY_SEEDED, false)) return;
		if (sp(c).getString(KEY_RULES, "").trim().isEmpty()) {
			sp(c).edit().putString(KEY_RULES, DEFAULT_RULES).putBoolean(KEY_SEEDED, true).apply();
		} else {
			sp(c).edit().putBoolean(KEY_SEEDED, true).apply();
		}
		cachedRaw = null;
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

	public static boolean autoUpdate(Context c) {
		return sp(c).getBoolean(KEY_AUTO, true);
	}

	public static void setAutoUpdate(Context c, boolean on) {
		sp(c).edit().putBoolean(KEY_AUTO, on).apply();
	}

	private static Set<String> sitesOff(Context c) {
		Set<String> out = new HashSet<>();
		String raw = sp(c).getString(KEY_SITE_OFF, "");
		if (raw != null) {
			for (String s : raw.split("\n")) {
				String t = s.trim();
				if (!t.isEmpty()) out.add(t);
			}
		}
		return out;
	}

	public static boolean siteOff(Context c, String pageUrl) {
		String host = host(pageUrl);
		if (host == null || host.isEmpty()) return false;
		for (String h : sitesOff(c)) {
			if (host.equals(h) || host.endsWith("." + h)) return true;
		}
		return false;
	}

	public static boolean toggleSite(Context c, String pageUrl) {
		String host = host(pageUrl);
		if (host == null || host.isEmpty()) return false;
		Set<String> off = sitesOff(c);
		boolean nowOff;
		if (off.contains(host)) {
			off.remove(host);
			nowOff = false;
		} else {
			off.add(host);
			nowOff = true;
		}
		StringBuilder b = new StringBuilder();
		for (String h : off) b.append(h).append('\n');
		sp(c).edit().putString(KEY_SITE_OFF, b.toString()).apply();
		return nowOff;
	}

	public static int offSiteCount(Context c) {
		return sitesOff(c).size();
	}

	// ---------- 统计 ----------

	public static int hits(Context c) {
		flush(c);
		return rawHits(c);
	}

	private static int rawHits(Context c) {
		return sp(c).getInt(KEY_HITS, 0);
	}

	public static void resetHits(Context c) {
		sp(c).edit().putInt(KEY_HITS, 0).apply();
	}

	public static long lastUpdate(Context c) {
		return sp(c).getLong(KEY_LAST_UPDATE, 0);
	}

	public static int ruleCount(Context c) {
		return engine(c).count;
	}

	// ---------- 规则加载 ----------

	private static java.io.File subsFile(Context c) {
		return new java.io.File(c.getFilesDir(), "adblock/rules.txt");
	}

	public static String subsCache(Context c) {
		try {
			java.io.File f = subsFile(c);
			if (!f.exists()) return "";
			byte[] b = new byte[(int) f.length()];
			java.io.FileInputStream in = new java.io.FileInputStream(f);
			int off = 0;
			while (off < b.length) {
				int n = in.read(b, off, b.length - off);
				if (n <= 0) break;
				off += n;
			}
			in.close();
			return new String(b, 0, off, "UTF-8");
		} catch (Throwable t) {
			return "";
		}
	}

	/** 取当前引擎，规则变化时整体重建后原子替换 */
	private static Engine engine(Context c) {
		String manual = rules(c);
		String sub = subsCache(c);
		if (manual == null) manual = "";
		if (sub == null) sub = "";
		Engine e = cur;
		if (e != null && manual.equals(cachedRaw) && sub.equals(cachedSub)) return e;
		synchronized (AdBlock.class) {
			if (cur != null && manual.equals(cachedRaw) && sub.equals(cachedSub)) return cur;
			Engine fresh = new Engine();
			fresh.parse(manual);
			fresh.parse(sub);
			cachedRaw = manual;
			cachedSub = sub;
			cur = fresh;
			return fresh;
		}
	}

	private static String host(String url) {
		if (url == null) return null;
		int s = url.indexOf("://");
		String rest = s >= 0 ? url.substring(s + 3) : url;
		int e = rest.indexOf('/');
		String h = e >= 0 ? rest.substring(0, e) : rest;
		int at = h.indexOf('@');
		if (at >= 0) h = h.substring(at + 1);
		int colon = h.indexOf(':');
		if (colon > 0) h = h.substring(0, colon);
		return h.toLowerCase();
	}

	private static String pathOf(String url) {
		int s = url.indexOf("://");
		if (s >= 0) {
			int slash = url.indexOf('/', s + 3);
			return slash >= 0 ? url.substring(slash) : "/";
		}
		int slash = url.indexOf('/');
		return slash >= 0 ? url.substring(slash) : "/";
	}

	private static boolean domainHit(String host, String[] list) {
		if (host == null) return false;
		for (String d : list) {
			if (host.equals(d) || host.endsWith("." + d)) return true;
		}
		return false;
	}

	// ---------- 对外匹配 ----------

	public static boolean blocked(Context c, String url, String pageUrl) {
		return blocked(c, url, pageUrl, 0);
	}

	public static boolean blocked(Context c, String url, String pageUrl, int type) {
		if (url == null || !enabled(c)) return false;
		if (url.startsWith("viek://") || url.startsWith("data:") || url.startsWith("blob:") || url.startsWith("about:")) return false;
		if (siteOff(c, pageUrl == null ? url : pageUrl)) return false;
		Engine e = engine(c);
		if (!e.blocked(url.toLowerCase(), host(pageUrl), type)) return false;
		hit(c);
		return true;
	}

	public static String hideCss(Context c, String pageUrl) {
		if (pageUrl == null || !enabled(c)) return null;
		if (pageUrl.startsWith("viek://") || pageUrl.startsWith("file://")) return null;
		if (siteOff(c, pageUrl)) return null;
		return engine(c).hideCss(host(pageUrl));
	}

	public static String hideScript(Context c, String pageUrl) {
		String css = hideCss(c, pageUrl);
		if (css == null || css.isEmpty()) return "";
		String esc = css.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "");
		return "(function(){try{"
			+ "var s=document.getElementById('viey-adstyle');"
			+ "if(!s){s=document.createElement('style');s.id='viey-adstyle';(document.head||document.documentElement).appendChild(s);}"
			+ "s.textContent='" + esc + "';}catch(e){}})();";
	}

	private static void hit(Context c) {
		if (pending.incrementAndGet() % 8 != 0) return;
		flush(c);
	}

	private static void flush(Context c) {
		int n = pending.getAndSet(0);
		if (n <= 0) return;
		sp(c).edit().putInt(KEY_HITS, rawHits(c) + n).apply();
	}

	// ---------- 拦截入口 ----------

	public static WebResourceResponse intercept(Context c, WebResourceRequest req) {
		try {
			if (req == null || req.getUrl() == null) return null;
			String url = req.getUrl().toString();
			int type = inferType(url, req);
			String referer = null;
			try {
				Map<String, String> hd = req.getRequestHeaders();
				if (hd != null) {
					for (Map.Entry<String, String> e : hd.entrySet()) {
						if (e.getKey() != null && e.getKey().equalsIgnoreCase("Referer")) {
							referer = e.getValue();
							break;
						}
					}
				}
			} catch (Throwable ignored) {}
			boolean frame = req.isForMainFrame();
			boolean hitIt;
			if (frame) {
				// 主框架只有显式 $document 规则才拦
				if (!enabled(c) || siteOff(c, url)) return null;
				Engine e = engine(c);
				if (e.any(url.toLowerCase(), host(url), T_DOC, true, false)) hitIt = false;
				else hitIt = e.any(url.toLowerCase(), host(url), T_DOC, false, true);
				if (hitIt) hit(c);
			} else {
				hitIt = blocked(c, url, referer != null ? referer : url, type);
			}
			if (!hitIt) return null;
			if (android.util.Log.isLoggable("viey-adblock", android.util.Log.DEBUG)) {
				android.util.Log.d("viey-adblock", "blocked type=" + type + " url=" + url);
			}
			WebResourceResponse r = new WebResourceResponse("text/plain", "utf-8",
				new ByteArrayInputStream(new byte[0]));
			try {
				r.setStatusCodeAndReasonPhrase(204, "No Content");
			} catch (Throwable ignored) {}
			return r;
		} catch (Throwable ignored) {}
		return null;
	}

	private static int inferType(String url, WebResourceRequest req) {
		String low = url.toLowerCase();
		String accept = null;
		try {
			Map<String, String> hd = req.getRequestHeaders();
			if (hd != null) {
				for (Map.Entry<String, String> e : hd.entrySet()) {
					if (e.getKey() != null && e.getKey().equalsIgnoreCase("Accept")) {
						accept = e.getValue();
						break;
					}
				}
			}
		} catch (Throwable ignored) {}
		if (accept != null) {
			accept = accept.toLowerCase();
			if (accept.contains("image/")) return T_IMAGE;
			if (accept.contains("text/css")) return T_STYLE;
			if (accept.contains("text/html")) return T_DOC;
			if (accept.contains("javascript")) return T_SCRIPT;
			if (accept.contains("font/")) return T_FONT;
			if (accept.contains("video/") || accept.contains("audio/")) return T_MEDIA;
			if (accept.contains("application/json")) return T_XHR;
		}
		return inferTypeNoReq(url);
	}

	private static int inferTypeNoReq(String url) {
		String low = url.toLowerCase();
		int q = low.indexOf('?');
		String path = q >= 0 ? low.substring(0, q) : low;
		if (path.endsWith(".js") || path.endsWith(".mjs")) return T_SCRIPT;
		if (path.endsWith(".css")) return T_STYLE;
		if (path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".gif")
			|| path.endsWith(".webp") || path.endsWith(".svg") || path.endsWith(".ico") || path.endsWith(".bmp")) return T_IMAGE;
		if (path.endsWith(".woff") || path.endsWith(".woff2") || path.endsWith(".ttf") || path.endsWith(".otf")) return T_FONT;
		if (path.endsWith(".mp4") || path.endsWith(".m3u8") || path.endsWith(".mp3") || path.endsWith(".m4a")
			|| path.endsWith(".webm") || path.endsWith(".flv")) return T_MEDIA;
		if (path.endsWith(".html") || path.endsWith(".htm")) return T_DOC;
		return T_OTHER;
	}

	// ---------- 订阅 ----------

	public static int updateSubs(Context c) throws Exception {
		String subs = subs(c).trim();
		if (subs.isEmpty()) return 0;
		StringBuilder all = new StringBuilder();
		int lines = 0;
		for (String u : subs.split("\n")) {
			String url = u.trim();
			if (url.isEmpty() || url.startsWith("#") || url.startsWith("!")) continue;
			String text = fetch(url);
			for (String line : text.split("\n")) {
				String s = line.trim();
				if (s.isEmpty() || s.startsWith("!") || s.startsWith("[") || s.startsWith(";")) continue;
				all.append(s).append('\n');
				lines++;
			}
		}
		writeSubs(c, all.toString());
		sp(c).edit().putLong(KEY_LAST_UPDATE, System.currentTimeMillis()).apply();
		cachedSub = null;
		cachedRaw = null;
		return lines;
	}

	public static void maybeAutoUpdate(final Context c) {
		if (!enabled(c) || !autoUpdate(c)) return;
		if (subs(c).trim().isEmpty()) return;
		long last = lastUpdate(c);
		if (System.currentTimeMillis() - last < 3L * 24 * 3600 * 1000) return;
		new Thread(() -> {
			try { updateSubs(c); } catch (Throwable ignored) {}
		}, "viey-adblock-update").start();
	}

	private static void writeSubs(Context c, String text) throws Exception {
		java.io.File dir = new java.io.File(c.getFilesDir(), "adblock");
		if (!dir.exists()) dir.mkdirs();
		java.io.FileOutputStream out = new java.io.FileOutputStream(subsFile(c));
		out.write(text.getBytes("UTF-8"));
		out.close();
	}

	private static String fetch(String url) throws Exception {
		java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
		conn.setConnectTimeout(12000);
		conn.setReadTimeout(25000);
		conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) VieY");
		conn.setRequestProperty("Accept", "text/plain,text/*");
		try (java.io.InputStream in = conn.getInputStream()) {
			java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			int n;
			while ((n = in.read(buf)) > 0) {
				bos.write(buf, 0, n);
				if (bos.size() > 8 * 1024 * 1024) break;
			}
			return new String(bos.toByteArray(), "UTF-8");
		} finally {
			conn.disconnect();
		}
	}

	// ---------- 自检 ----------

	private static final String TEST_RULES =
		"||ads.example.com^\n"
			+ "||ads.example.com/promo*\n"
			+ "||example.com^$document\n"
			+ "||tracker.net^$third-party\n"
			+ "||img.example.net^$image\n"
			+ "||js.example.net^$script\n"
			+ "||dom.example.net^$domain=site-a.com|site-b.com\n"
			+ "||exc.example.net^$domain=~site-a.com\n"
			+ "/banner/*.gif\n"
			+ "/ad[0-9]+/\n"
			+ "|https://exact.example.com/x.js|\n"
			+ "@@||good.example.com^\n"
			+ "@@/whitelist/\n"
			+ "news.example##.ad-box\n"
			+ "news.example#@#.keep-box\n"
			+ "##.generic-ad\n"
			+ "-ad-banner.\n"
			+ "||dropped.example.com^$redirect=noop.js\n";

	/**
	 * 离线自检：用内置规则集构造独立引擎，逐条断言匹配结果与元素隐藏输出，
	 * 不影响用户已保存的规则，也不写统计。
	 */
	public static String selfTest(Context c) {
		Engine e = new Engine();
		e.parse(TEST_RULES);
		StringBuilder b = new StringBuilder();
		int pass = 0, total = 0;

		pass += check(b, total++, "主机锚定命中", e.blocked("https://ads.example.com/a.js", "https://news.example/", T_SCRIPT));
		pass += check(b, total++, "子域命中", e.blocked("https://sub.ads.example.com/a.png", "https://news.example/", T_IMAGE));
		pass += check(b, total++, "主机+路径命中", e.blocked("https://ads.example.com/promo1/a.png", "https://news.example/", T_IMAGE));
		pass += check(b, total++, "主机下其它路径也拦", e.blocked("https://ads.example.com/other/a.png", "https://news.example/", T_IMAGE));
		pass += check(b, total++, "无关域名放行", !e.blocked("https://neutral.example/a.png", "https://news.example/", T_IMAGE));
		pass += check(b, total++, "白名单 @@ 优先", !e.blocked("https://good.example.com/a.js", "https://news.example/", T_SCRIPT));
		pass += check(b, total++, "白名单正则 @@/path/", !e.blocked("https://x.example/whitelist/a.js", "https://news.example/", T_SCRIPT));
		pass += check(b, total++, "$third-party 第三方命中", e.blocked("https://tracker.net/t.js", "https://news.example/", T_SCRIPT));
		pass += check(b, total++, "$third-party 第一方放行", !e.blocked("https://tracker.net/t.js", "https://tracker.net/", T_SCRIPT));
		pass += check(b, total++, "$image 只拦图片", e.blocked("https://img.example.net/a.png", "https://news.example/", T_IMAGE)
			&& !e.blocked("https://img.example.net/a.js", "https://news.example/", T_SCRIPT));
		pass += check(b, total++, "$script 只拦脚本", e.blocked("https://js.example.net/a.js", "https://news.example/", T_SCRIPT)
			&& !e.blocked("https://js.example.net/a.css", "https://news.example/", T_STYLE));
		pass += check(b, total++, "$domain 包含命中", e.blocked("https://dom.example.net/x.js", "https://site-a.com/", T_SCRIPT));
		pass += check(b, total++, "$domain 未列出放行", !e.blocked("https://dom.example.net/x.js", "https://other.com/", T_SCRIPT));
		pass += check(b, total++, "$domain 排除域放行", !e.blocked("https://exc.example.net/x.js", "https://site-a.com/", T_SCRIPT));
		pass += check(b, total++, "路径通配 + 后缀", e.blocked("https://x.example/banner/300.gif", "https://x.example/", T_IMAGE)
			&& !e.blocked("https://x.example/banner/300.png", "https://x.example/", T_IMAGE));
		pass += check(b, total++, "正则规则", e.blocked("https://x.example/ad123/a.js", "https://x.example/", T_SCRIPT));
		pass += check(b, total++, "首尾锚定精确匹配", e.blocked("https://exact.example.com/x.js", "https://exact.example.com/", T_SCRIPT)
			&& !e.blocked("https://exact.example.com/x.js?v=1", "https://exact.example.com/", T_SCRIPT));
		pass += check(b, total++, "主框架仅 $document 拦截", e.blocked("https://example.com/", "https://example.com/", T_DOC)
			&& !e.blocked("https://ads.example.com/", "https://ads.example.com/", T_DOC));

		String css = e.hideCss("news.example");
		boolean cssOk = css != null && css.contains(".ad-box") && css.contains(".generic-ad") && !css.contains(".keep-box");
		pass += check(b, total++, "元素隐藏含域规则与例外", cssOk);
		pass += check(b, total++, "元素隐藏不串域", e.hideCss("other.example") != null && !e.hideCss("other.example").contains(".ad-box"));

		pass += check(b, total++, "子串规则匹配（-ad-banner.）",
			e.blocked("https://x.example/img-ad-banner.png", "https://x.example/", T_IMAGE));
		pass += check(b, total++, "带值选项 redirect 整条丢弃",
			!e.blocked("https://dropped.example.com/s.js", "https://x.example/", T_SCRIPT));
		pass += check(b, total++, "页面地址传整串 URL 也归一",
			e.blocked("https://ads.example.com/a.js", "https://news.example/page", T_SCRIPT));

		// 畸形输入不应抛异常
		Engine bad = new Engine();
		boolean noCrash = true;
		try {
			bad.parse("||\n/[/\n##\n$domain=\n||x^$unknown=1\n||y^$domain=a.com,b.com\n");
		} catch (Throwable t) {
			noCrash = false;
		}
		pass += check(b, total++, "畸形规则不崩", noCrash);

		b.append("\n规则样例：").append(e.count).append(" 条");
		b.append("\n通过 ").append(pass).append(" / ").append(total)
			.append(pass == total ? "（全部通过）" : "（存在失败项）");
		b.append("\n当前生效规则：").append(ruleCount(c)).append(" 条，累计拦截 ").append(hits(c)).append(" 次");
		return b.toString();
	}

	private static int check(StringBuilder b, int idx, String name, boolean ok) {
		b.append(ok ? "PASS  " : "FAIL  ").append(name).append('\n');
		return ok ? 1 : 0;
	}
}
