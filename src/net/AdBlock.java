package kawaii.viey.browser;

import android.content.Context;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * 广告拦截引擎（完整 Adblock Plus 语法子集，对齐 Via 的拦截能力）：
 *
 * 网络规则：
 *   ||ads.example.com^           主机锚定（含子域、按路径分隔）
 *   ||ads.example.com/x*         主机 + 路径通配
 *   ads.example.com^             裸主机
 *   /banner/.gif                 片段通配
 *   /regex/                      正则
 *   @@||good.com^                白名单，优先
 *   $third-party / $~third-party 第三方 / 仅第一方
 *   $domain=a.com,b.com / $domain=~c.com
 *   $image $script $stylesheet $xmlhttprequest $subdocument $media $font
 *   $document $popup $object $other $important
 *
 * 元素隐藏：
 *   example.com##.ad-box
 *   ##div[class^="sponsor"]      通用规则（限条数，避免拖慢页面）
 *   example.com#@#.ad-box        隐藏例外
 *
 * 规则来源 = 手动规则 + 订阅列表（订阅单独缓存，更新整体替换，不无限膨胀）。
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

	private static class Net {
		boolean exception;
		int types;
		boolean thirdParty;
		boolean noThirdParty;
		String[] incDomains;
		String[] excDomains;
		Pattern regex;
		String key;
	}

	private static final List<Net> generic = new ArrayList<>();
	private static final Map<String, List<Net>> byHost = new HashMap<>();
	private static final Map<String, List<Net>> byPathFirst = new HashMap<>();

	private static final Map<String, List<String>> hideByDomain = new HashMap<>();
	private static final Map<String, List<String>> hideExceptions = new HashMap<>();
	private static final List<String> hideGeneric = new ArrayList<>();

	private static String cachedRaw = null;
	private static String cachedSub = null;
	private static int cachedCount = 0;
	private static String cachedCssHost = null;
	private static String cachedCss = null;

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

	public static int hits(Context c) {
		return sp(c).getInt(KEY_HITS, 0);
	}

	public static void resetHits(Context c) {
		sp(c).edit().putInt(KEY_HITS, 0).apply();
	}

	public static long lastUpdate(Context c) {
		return sp(c).getLong(KEY_LAST_UPDATE, 0);
	}

	public static int ruleCount(Context c) {
		load(c);
		return cachedCount;
	}

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

	private static synchronized void load(Context c) {
		String manual = rules(c);
		String sub = subsCache(c);
		if (manual == null) manual = "";
		if (sub == null) sub = "";
		if (manual.equals(cachedRaw) && sub.equals(cachedSub)) return;
		cachedRaw = manual;
		cachedSub = sub;
		generic.clear();
		byHost.clear();
		byPathFirst.clear();
		hideByDomain.clear();
		hideExceptions.clear();
		hideGeneric.clear();
		int n = parseBlock(manual);
		n += parseBlock(sub);
		cachedCount = n;
		cachedCssHost = null;
		cachedCss = null;
	}

	private static int parseBlock(String text) {
		int n = 0;
		for (String line : text.split("\n")) {
			String s = line.trim();
			if (s.isEmpty()) continue;
			if (s.startsWith("!") || s.startsWith("[") || s.startsWith(";")) continue;
			if (s.contains("##") || s.contains("#@#")) {
				if (parseCosmetic(s)) n++;
				continue;
			}
			if (s.startsWith("#")) continue;
			if (parseNet(s) != null) n++;
		}
		return n;
	}

	private static boolean parseCosmetic(String s) {
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

	private static Net parseNet(String raw) {
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

		boolean anchorStart = false, anchorEnd = false;
		if (s.startsWith("|")) { anchorStart = true; s = s.substring(1); }
		if (s.endsWith("|")) { anchorEnd = true; s = s.substring(0, s.length() - 1); }
		if (s.isEmpty()) return null;

		boolean hostAnchor = false;
		if (s.startsWith("||")) { hostAnchor = true; s = s.substring(2); }

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
			if (body.endsWith("^")) body = body.substring(0, body.length() - 1);
			int slash = body.indexOf('/');
			int star = body.indexOf('*');
			int cut = body.length();
			if (slash >= 0) cut = Math.min(cut, slash);
			if (star >= 0) cut = Math.min(cut, star);
			String h = body.substring(0, cut).toLowerCase();
			if (h.isEmpty()) return null;
			if (cut == body.length() && !s.contains("*") && !s.contains("?")) {
				r.key = h;
				List<Net> l = byHost.get(h);
				if (l == null) { l = new ArrayList<>(); byHost.put(h, l); }
				l.add(r);
				return r;
			}
			r.regex = compile("||" + body, false, anchorEnd);
			if (r.regex == null) return null;
			generic.add(r);
			return r;
		}

		if (!s.contains("/") && !s.contains("*") && !s.contains("?")) {
			String h = s;
			if (h.endsWith("^")) h = h.substring(0, h.length() - 1);
			if (h.isEmpty()) return null;
			r.key = h.toLowerCase();
			List<Net> l = byHost.get(r.key);
			if (l == null) { l = new ArrayList<>(); byHost.put(r.key, l); }
			l.add(r);
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

	private static boolean parseOptions(Net r, String opt) {
		for (String o : opt.split("\\$|,")) {
			String t = o.trim();
			if (t.isEmpty()) continue;
			boolean neg = false;
			if (t.startsWith("~")) { neg = true; t = t.substring(1); }
			if (t.equals("third-party") || t.equals("3p")) {
				if (neg) r.noThirdParty = true; else r.thirdParty = true;
			} else if (t.startsWith("domain=")) {
				String v = t.substring(7);
				List<String> inc = new ArrayList<>();
				List<String> exc = new ArrayList<>();
				for (String dv : v.split("\\|")) {
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
				// 兼容项，不改变行为
			} else if (t.equals("badfilter") || t.equals("csp") || t.equals("rewrite")
				|| t.equals("redirect") || t.equals("removeparam") || t.equals("generichide")
				|| t.equals("elemhide") || t.equals("genericblock") || t.equals("webrtc")
				|| t.equals("websocket") || t.equals("ping") || t.equals("beacon")) {
				return true;
			}
		}
		return false;
	}

	private static Pattern compile(String body, boolean anchorStart, boolean anchorEnd) {
		StringBuilder rx = new StringBuilder();
		if (anchorStart) rx.append('^');
		rx.append(".*");
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

	private static boolean domainListHit(String host, String[] list) {
		if (host == null) return false;
		for (String d : list) {
			if (host.equals(d) || host.endsWith("." + d)) return true;
		}
		return false;
	}

	private static boolean ruleApplies(Net r, String url, String pageHost) {
		if (r.incDomains != null && !domainListHit(pageHost, r.incDomains)) return false;
		if (r.excDomains != null && domainListHit(pageHost, r.excDomains)) return false;
		String uh = host(url);
		boolean third = pageHost != null && !pageHost.isEmpty() && uh != null
			&& !pageHost.equals(uh) && !pageHost.endsWith("." + uh) && !uh.endsWith("." + pageHost);
		if (r.thirdParty && !third) return false;
		if (r.noThirdParty && third) return false;
		return true;
	}

	public static boolean blocked(Context c, String url, String pageUrl) {
		if (url == null || !enabled(c)) return false;
		if (url.startsWith("viek://") || url.startsWith("data:") || url.startsWith("blob:") || url.startsWith("about:")) return false;
		if (siteOff(c, pageUrl == null ? url : pageUrl)) return false;
		load(c);
		String low = url.toLowerCase();
		String h = host(low);
		String pageHost = host(pageUrl);

		boolean[] allowed = {false};
		matchHostRules(h, low, pageHost, true, allowed);
		if (allowed[0]) return false;
		if (matchHostRules(h, low, pageHost, false, null) > 0
			|| matchPathRules(low, pageHost) > 0
			|| matchGeneric(low, pageHost) > 0) {
			hit(c);
			return true;
		}
		return false;
	}

	private static int matchHostRules(String h, String url, String pageHost, boolean exception, boolean[] allowed) {
		if (h == null) return 0;
		int hits = 0;
		String probe = h;
		while (probe.length() > 0) {
			List<Net> l = byHost.get(probe);
			if (l != null) {
				for (Net r : l) {
					if (r.exception != exception) continue;
					if (!ruleApplies(r, url, pageHost)) continue;
					if (exception) { if (allowed != null) allowed[0] = true; return 1; }
					hits++;
				}
			}
			int dot = probe.indexOf('.');
			if (dot < 0) break;
			probe = probe.substring(dot + 1);
		}
		return hits;
	}

	private static int matchPathRules(String url, String pageHost) {
		if (byPathFirst.isEmpty()) return 0;
		int hits = 0;
		String path = url;
		int s = url.indexOf("://");
		if (s >= 0) {
			int slash = url.indexOf('/', s + 3);
			path = slash >= 0 ? url.substring(slash) : "/";
		}
		int start = 1;
		while (start < path.length()) {
			int e = path.indexOf('/', start);
			String seg = e > 0 ? path.substring(start, e) : path.substring(start);
			if (!seg.isEmpty()) {
				List<Net> l = byPathFirst.get(seg);
				if (l != null) {
					for (Net r : l) {
						if (r.exception || r.regex == null) continue;
						if (!ruleApplies(r, url, pageHost)) continue;
						if (r.regex.matcher(url).find()) hits++;
					}
				}
			}
			if (e < 0) break;
			start = e + 1;
			if (start > 60) break;
		}
		return hits;
	}

	private static int matchGeneric(String url, String pageHost) {
		int hits = 0;
		for (Net r : generic) {
			if (r.exception || r.regex == null) continue;
			if (!ruleApplies(r, url, pageHost)) continue;
			try {
				if (r.regex.matcher(url).find()) hits++;
			} catch (Throwable ignored) {}
		}
		return hits;
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

	public static String hideCss(Context c, String pageUrl) {
		if (pageUrl == null || !enabled(c)) return null;
		if (siteOff(c, pageUrl)) return null;
		String h = host(pageUrl);
		if (h == null) return null;
		if (h.equals(cachedCssHost)) return cachedCss;
		load(c);
		List<String> sels = new ArrayList<>();
		String probe = h;
		while (probe.length() > 0) {
			List<String> l = hideByDomain.get(probe);
			if (l != null) sels.addAll(l);
			int dot = probe.indexOf('.');
			if (dot < 0) break;
			probe = probe.substring(dot + 1);
		}
		if (hideGeneric.size() > 0) sels.addAll(hideGeneric);
		List<String> exc = new ArrayList<>();
		probe = h;
		while (probe.length() > 0) {
			List<String> l = hideExceptions.get(probe);
			if (l != null) exc.addAll(l);
			int dot = probe.indexOf('.');
			if (dot < 0) break;
			probe = probe.substring(dot + 1);
		}
		if (sels.isEmpty()) { cachedCssHost = h; cachedCss = null; return null; }
		StringBuilder css = new StringBuilder();
		int n = 0;
		for (String sel : sels) {
			boolean skipped = false;
			for (String e : exc) if (e.equals(sel)) { skipped = true; break; }
			if (skipped) continue;
			if (sel.contains("{")) continue;
			css.append(sel).append("{display:none!important}\n");
			if (++n >= 3000) break;
		}
		String out = css.length() == 0 ? null : css.toString();
		cachedCssHost = h;
		cachedCss = out;
		return out;
	}

	public static String hideScript(Context c, String pageUrl) {
		String css = hideCss(c, pageUrl);
		if (css == null || css.isEmpty()) return "";
		String esc = css.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n");
		return "(function(){try{"
			+ "var s=document.getElementById('viey-adstyle');"
			+ "if(!s){s=document.createElement('style');s.id='viey-adstyle';(document.head||document.documentElement).appendChild(s);}"
			+ "s.textContent='" + esc + "';}catch(e){}})();";
	}

	public static WebResourceResponse intercept(Context c, WebResourceRequest req) {
		try {
			if (req == null || req.getUrl() == null) return null;
			String url = req.getUrl().toString();
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
			boolean hitIt = frame ? blockedDocument(c, url) : blocked(c, url, referer != null ? referer : url);
			if (!hitIt) return null;
			WebResourceResponse r = new WebResourceResponse("text/plain", "utf-8",
				new ByteArrayInputStream(new byte[0]));
			try {
				r.setStatusCodeAndReasonPhrase(204, "No Content");
			} catch (Throwable ignored) {}
			return r;
		} catch (Throwable ignored) {}
		return null;
	}

	private static boolean blockedDocument(Context c, String url) {
		if (!enabled(c)) return false;
		if (siteOff(c, url)) return false;
		load(c);
		String low = url.toLowerCase();
		String h = host(low);
		if (h == null) return false;
		boolean[] allowed = {false};
		matchHostRules(h, low, null, true, allowed);
		if (allowed[0]) return false;
		int n = 0;
		String probe = h;
		while (probe.length() > 0) {
			List<Net> l = byHost.get(probe);
			if (l != null) {
				for (Net r : l) {
					if (r.exception || r.regex != null) continue;
					if ((r.types & T_DOC) == 0) continue;
					if (!ruleApplies(r, url, null)) continue;
					n++;
				}
			}
			int dot = probe.indexOf('.');
			if (dot < 0) break;
			probe = probe.substring(dot + 1);
		}
		// 主框架也允许普通主机规则命中，但只在带 $document 时才拦，避免误伤
		if (n > 0) { hit(c); return true; }
		return false;
	}

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
		java.io.File f = subsFile(c);
		java.io.FileOutputStream out = new java.io.FileOutputStream(f);
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
}
