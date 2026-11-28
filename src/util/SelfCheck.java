package kawaii.viey.browser;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 全功能离线自检：不联网、不改用户数据、不依赖点击，一次跑完主要模块的纯逻辑路径。
 * 输出写入 logcat（tag: viey-selfcheck，逐行一条），也可在设置页里直接看。
 */
public class SelfCheck {

	public static final String TAG = "viey-selfcheck";

	private static final List<String> FAILS = new ArrayList<>();
	private static int pass = 0;
	private static int total = 0;

	public static String run(Context c) {
		pass = 0;
		total = 0;
		FAILS.clear();
		StringBuilder b = new StringBuilder();
		b.append("===== VieY 全功能自检 =====\n");

		checkAdBlock(c, b);
		checkRss(c, b);
		checkFeedStore(c, b);
			checkBookmark(c, b);
		checkDownload(c, b);
		checkAi(c, b);
		checkBt(b);
		checkSafety(c, b);
		checkComponents(c, b);
		checkResources(c, b);
		checkDefaults(c, b);

		b.append("----- 汇总 -----\n");
		b.append("TOTAL  ").append(pass).append(" / ").append(total)
			.append(pass == total ? "（全部通过）" : "（存在失败项）").append('\n');
		if (!FAILS.isEmpty()) {
			b.append("FAIL 明细：\n");
			for (String f : FAILS) b.append("  - ").append(f).append('\n');
		}
		return b.toString();
	}

	/** 后台跑一遍并把每一行打到 logcat，便于 adb 抓取 */
	public static void runAndLog(final Context c) {
		new Thread(() -> {
			String r;
			try {
				r = run(c);
			} catch (Throwable t) {
				r = "自检异常：" + t;
			}
			for (String line : r.split("\n")) Log.i(TAG, line);
		}, "viey-selfcheck").start();
	}

	/** dev 包启动时的联网探测：取第一条启用的订阅源抓一次（只做静态解析，不启动 WebView），结果进 logcat */
	public static void probeFeed(final Context c) {
		new Thread(() -> doProbe(c), "viey-netprobe").start();
	}

	private static void doProbe(Context c) {
		try {
			List<FeedSource> list = FeedStore.load(c);
			FeedSource src = null;
			for (FeedSource x : list) {
				if (x != null && x.enabled && x.url != null && !x.url.isEmpty()) { src = x; break; }
			}
			if (src == null) {
				Log.i(TAG, "网络探测：没有已启用的订阅源，跳过");
				return;
			}
			Log.i(TAG, "网络探测 开始：" + src.url);
			long t0 = System.currentTimeMillis();
			String body = FeedRepo.fetch(c, src.url, src);
			int n = FeedParser.parse(body, src, src.url).size();
			Log.i(TAG, "网络探测 结果：" + src.url + " → " + body.length() + " 字节 / 解析 "
				+ n + " 条，用时 " + (System.currentTimeMillis() - t0) + "ms");
		} catch (Throwable t) {
			Log.i(TAG, "网络探测 失败：" + t);
		}
	}

	private static void ok(StringBuilder b, String name) {
		total++;
		pass++;
		b.append("PASS  ").append(name).append('\n');
	}

	private static void fail(StringBuilder b, String name, String detail) {
		total++;
		String line = name + (detail == null || detail.isEmpty() ? "" : "（" + detail + "）");
		FAILS.add(line);
		b.append("FAIL  ").append(line).append('\n');
	}

	private static void check(StringBuilder b, String name, boolean cond, String detailIfFail) {
		if (cond) ok(b, name);
		else fail(b, name, detailIfFail);
	}

	// ---------------- 广告拦截 ----------------

	private static void checkAdBlock(Context c, StringBuilder b) {
		String r;
		try {
			r = AdBlock.selfTest(c);
		} catch (Throwable t) {
			fail(b, "广告拦截自检可执行", String.valueOf(t));
			return;
		}
		int p = 0, f = 0;
		for (String line : r.split("\n")) {
			if (line.startsWith("PASS")) p++;
			else if (line.startsWith("FAIL")) f++;
		}
		check(b, "广告拦截 ABP 断言 " + p + "/" + (p + f), f == 0 && p >= 24, "失败 " + f + " 条");
		check(b, "内置规则数 ≥ 24", AdBlock.ruleCount(c) >= 24, "实际 " + AdBlock.ruleCount(c));
		check(b, "拦截计数可读", AdBlock.hits(c) >= 0, "");
		check(b, "站点暂停列表可读", AdBlock.offSiteCount(c) >= 0, "");
		check(b, "订阅地址/手动规则可读", AdBlock.rules(c) != null && AdBlock.subs(c) != null, "");
	}

	// ---------------- RSS ----------------

	private static final String ATOM = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
		+ "<feed xmlns=\"http://www.w3.org/2005/Atom\"><title>阮一峰</title>"
		+ "<entry><title>第 421 期</title><link href=\"https://x.example/421\"/>"
		+ "<updated>2026-11-27T00:10:00Z</updated><summary>a</summary></entry>"
		+ "<entry><title>第 420 期</title><link href=\"https://x.example/420\"/>"
		+ "<updated>2026-11-20T00:07:00Z</updated><summary>b</summary></entry></feed>";

	private static final String RSS2 = "<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>t</title>"
		+ "<item><title>条目一</title><link>https://y.example/1</link>"
		+ "<pubDate>Fri, 20 Nov 2026 08:00:00 GMT</pubDate>"
		+ "<description>描述一</description></item>"
		+ "<item><title>条目二</title><link>https://y.example/2</link>"
		+ "<pubDate>Sat, 21 Nov 2026 08:00:00 GMT</pubDate>"
		+ "<description>描述二</description></item></channel></rss>";

	private static final String RDF = "<?xml version=\"1.0\"?><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\""
		+ " xmlns=\"http://purl.org/rss/1.0/\"><channel><title>r</title></channel>"
		+ "<item><title>RDF 条目</title><link>https://z.example/1</link></item></rdf:RDF>";

	private static final String HTML_RULE = "<html><body><ul>"
		+ "<li class=row><a href=\"/p/1\">标题一</a><time>2026-11-20</time></li>"
		+ "<li class=row><a href=\"/p/2\">标题二</a><time>2026-11-21</time></li>"
		+ "<li class=row><a href=\"/p/3\">标题三</a><time>2026-11-22</time></li>"
		+ "</ul></body></html>";

	private static final String LINK_PAGE = "<html><body>"
		+ "<a href=\"/a1\">第一篇足够长的标题</a><a href=\"/a2\">第二篇足够长的标题</a>"
		+ "<a href=\"/a3\">第三篇足够长的标题</a><a href=\"/a4\">第四篇足够长的标题</a>"
		+ "<a href=\"#\">短</a></body></html>";

	private static final String SPA_SHELL = "<html><head><script src=/a.js></script></head>"
		+ "<body><div id=\"root\"></div><script>window.subTitle='Meow'</script></body></html>";

	private static void checkRss(Context c, StringBuilder b) {
		try {
			List<FeedParser.Article> a = FeedParser.parse(ATOM, new FeedSource(), "https://x.example/");
			check(b, "Atom 解析条目数 ≥ 2", a.size() >= 2, "实际 " + a.size());
			check(b, "Atom 标题正确", a.size() > 0 && a.get(0).title.contains("421"), a.isEmpty() ? "空" : a.get(0).title);
			check(b, "Atom 日期归一", a.size() > 0 && a.get(0).pubDate.contains("2026-11"), a.isEmpty() ? "空" : a.get(0).pubDate);

			List<FeedParser.Article> r2 = FeedParser.parse(RSS2, new FeedSource(), "https://y.example/");
			check(b, "RSS2.0 解析条目数 ≥ 2", r2.size() >= 2, "实际 " + r2.size());
			check(b, "RSS2.0 链接为绝对地址", r2.size() > 0 && r2.get(0).link.startsWith("http"), r2.isEmpty() ? "空" : r2.get(0).link);

			List<FeedParser.Article> rf = FeedParser.parse(RDF, new FeedSource(), "https://z.example/");
			check(b, "RDF(RSS1.0) 解析 ≥ 1 条", rf.size() >= 1, "实际 " + rf.size());

			FeedSource rule = new FeedSource();
			rule.ruleArticles = "class.row";
			rule.ruleTitle = "tag.a@text";
			rule.ruleLink = "tag.a@href";
			rule.rulePubDate = "tag.time@text";
			List<FeedParser.Article> hr = FeedParser.parse(HTML_RULE, rule, "https://h.example/");
			check(b, "CSS 规则解析 3 条", hr.size() == 3, "实际 " + hr.size());
			check(b, "CSS 规则链接补全主机", hr.size() > 0 && hr.get(0).link.startsWith("https://h.example/"),
				hr.isEmpty() ? "空" : hr.get(0).link);

			List<FeedParser.Article> same = FeedParser.parseAnchors(LINK_PAGE, "https://n.example/");
			check(b, "同域链接兜底 ≥ 3 条", same.size() >= 3, "实际 " + same.size());

			String cross = "<html><body><a href=\"https://a.example/x1\">跨域来源的足够长标题一</a>"
				+ "<a href=\"https://b.example/x2\">跨域来源的足够长标题二</a>"
				+ "<a href=\"https://c.example/x3\">跨域来源的足够长标题三</a></body></html>";
			List<FeedParser.Article> cr = FeedParser.parseAnchors(cross, "https://n.example/", true);
			check(b, "跨域链接兜底 ≥ 3 条", cr.size() >= 3, "实际 " + cr.size());
			check(b, "跨域默认关闭（同域函数不放行）", FeedParser.parseAnchors(cross, "https://n.example/").isEmpty(), "");

			check(b, "SPA 空壳判定为真", FeedParser.looksLikeSpa(SPA_SHELL), "");
			check(b, "正常页面不判为 SPA", !FeedParser.looksLikeSpa(LINK_PAGE), "");

			boolean noCrash;
			int bad = -1;
			try {
				bad = FeedParser.parse("<rss><item><title>坏", new FeedSource(), "https://q.example/").size();
				noCrash = true;
			} catch (Throwable t) {
				noCrash = false;
			}
			check(b, "畸形 XML 不抛异常", noCrash, "");
			check(b, "畸形 XML 不误产条目", bad <= 1, "产出 " + bad);

			FeedSource s = new FeedSource();
			s.name = "示例源"; s.url = "https://s.example/feed"; s.group = "组";
			s.preloadJs = "pre()"; s.injectJs = "inj()"; s.ruleNextPage = "下一页";
			s.icon = "https://s.example/i.png"; s.enabled = false;
			FeedSource back = FeedSource.fromLegado(s.toLegado());
			check(b, "Legado 往返保留名称/地址",
				"示例源".equals(back.name) && "https://s.example/feed".equals(back.url), back.name + "|" + back.url);
			check(b, "Legado 往返保留 preloadJs/injectJs",
				"pre()".equals(back.preloadJs) && "inj()".equals(back.injectJs), "");
			check(b, "Legado 往返保留 nextPage/icon/enabled",
				"下一页".equals(back.ruleNextPage) && "https://s.example/i.png".equals(back.icon) && !back.enabled, "");

			JSONObject alt = new JSONObject();
			alt.put("sourceName", "新式名");
			alt.put("sourceUrl", "https://alt.example/f");
			FeedSource altS = FeedSource.fromLegado(alt);
			check(b, "兼容 sourceName/sourceUrl 键",
				"新式名".equals(altS.name) && "https://alt.example/f".equals(altS.url), altS.name + "|" + altS.url);

			FeedSource noName = new FeedSource();
			noName.url = "https://only.example/";
			check(b, "空名回落用地址显示", "https://only.example/".equals(noName.displayTitle()), noName.displayTitle());
			check(b, "规则 JS 沙箱开关可读", RuleJs.enabled(c) || !RuleJs.enabled(c), "");
		} catch (Throwable t) {
			fail(b, "RSS 自检可执行", String.valueOf(t));
		}
	}

	private static void checkFeedStore(Context c, StringBuilder b) {
		try {
			List<FeedSource> list = FeedStore.load(c);
			boolean urlsOk = true;
			for (FeedSource s : list) if (s.url == null || s.url.isEmpty()) urlsOk = false;
			check(b, "订阅源存储可读", list != null, "null");
			check(b, "订阅源条目地址完整", urlsOk, "存在空地址");
			check(b, "订阅缓存目录可写",
				new File(c.getFilesDir(), "rss").exists() || new File(c.getFilesDir(), "rss").mkdirs(), "");
			check(b, "订阅定时刷新间隔有效", FeedRefreshJob.interval(c) > 0, "" + FeedRefreshJob.interval(c));
		} catch (Throwable t) {
			fail(b, "订阅存储自检可执行", String.valueOf(t));
		}
	}

	// ---------------- 书签 ----------------

	private static void checkBookmark(Context c, StringBuilder b) {
		try {
			BookmarkManager.Bookmark folder = BookmarkManager.Bookmark.createFolder("文件夹");
			BookmarkManager.Bookmark child = new BookmarkManager.Bookmark("子书签", "https://b.example/");
			folder.children.add(child);
			BookmarkManager.Bookmark back = BookmarkManager.Bookmark.fromJson(folder.toJson());
			check(b, "书签目录 JSON 往返",
				back != null && back.isFolder && "文件夹".equals(back.title), back == null ? "null" : back.title);
			check(b, "书签子项数量保持",
				back != null && back.children != null && back.children.size() == 1,
				back == null || back.children == null ? "0" : "" + back.children.size());
			check(b, "书签子项地址保持",
				back != null && back.children != null && !back.children.isEmpty()
					&& "https://b.example/".equals(back.children.get(0).url), "");
			check(b, "书签读取不崩", BookmarkManager.getBookmarks(c) != null, "null");
		} catch (Throwable t) {
			fail(b, "书签自检可执行", String.valueOf(t));
		}
	}

	// ---------------- 下载 ----------------

	private static void checkDownload(Context c, StringBuilder b) {
		File tmp = new File(c.getCacheDir(), "selfcheck-dl.json");
		try {
			DlStore.writeAtomic(tmp, "{\"a\":1}");
			String s = PluginStore.read(tmp);
			check(b, "下载状态原子写入可回读", s != null && s.contains("\"a\":1"), s);
		} catch (Throwable t) {
			fail(b, "下载状态原子写入", String.valueOf(t));
		} finally {
			//noinspection ResultOfMethodCallIgnored
			tmp.delete();
		}
		try {
			boolean noCrash;
			try {
				DlStore.read(c);
				noCrash = true;
			} catch (Throwable t) {
				noCrash = false;
			}
			check(b, "下载任务状态读取不崩", noCrash, "");
			check(b, "下载并发默认值有效", VieYApp.getDownloadMaxConcurrent(c) >= 1, "" + VieYApp.getDownloadMaxConcurrent(c));
			check(b, "下载线程默认值有效", VieYApp.getDownloadDefaultThreads(c) >= 1, "" + VieYApp.getDownloadDefaultThreads(c));
			check(b, "下载重试默认值有效", VieYApp.getDownloadMaxRetry(c) >= 0, "" + VieYApp.getDownloadMaxRetry(c));
		} catch (Throwable t) {
			fail(b, "下载配置自检可执行", String.valueOf(t));
		}
	}

	// ---------------- AI ----------------

	private static void checkAi(Context c, StringBuilder b) {
		try {
			String u = AiConfig.chatUrl(c);
			check(b, "AI 接口地址以 /chat/completions 结尾",
				u != null && u.endsWith("/chat/completions"), u);
			String noScheme = u == null ? "" : u.replaceFirst("^[A-Za-z][A-Za-z0-9+.-]*://", "");
			check(b, "AI 接口地址无双斜杠", noScheme.contains("/") && !noScheme.contains("//"), u);
			check(b, "AI 模型名非空", AiConfig.model(c) != null && !AiConfig.model(c).isEmpty(), "");
			check(b, "AI 系统提示词非空",
				AiConfig.systemPrompt(c) != null && AiConfig.systemPrompt(c).length() > 10, "");
			check(b, "AI 摘要提示词非空",
				AiConfig.summaryPrompt(c) != null && AiConfig.summaryPrompt(c).length() > 5, "");
		} catch (Throwable t) {
			fail(b, "AI 配置自检可执行", String.valueOf(t));
		}
	}

	// ---------------- BT ----------------

	private static void checkBt(StringBuilder b) {
		try {
			String r = BtSelfTest.run();
			int p = 0, f = 0;
			for (String line : r.split("\n")) {
				if (line.startsWith("PASS")) p++;
				else if (line.startsWith("FAIL")) f++;
			}
			check(b, "BT 自检 " + p + "/" + (p + f), f == 0 && p > 0, "失败 " + f + " 条");
		} catch (Throwable t) {
			fail(b, "BT 自检可执行", String.valueOf(t));
		}
	}

	// ---------------- 安全浏览 / 镜像 ----------------

	private static void checkSafety(Context c, StringBuilder b) {
		try {
			String html = SafeBrowse.warningHtml("https://bad.example/?a=1&b=\"x\"", "测试");
			check(b, "安全浏览提示页转义", html.contains("&amp;") && html.contains("&quot;"), "");
			check(b, "安全浏览威胁判定不崩", SafeBrowse.threat(c, "https://bad.example/") == null
				|| SafeBrowse.threat(c, "https://bad.example/").length() > 0, "");
			check(b, "镜像规则条数可读", MirrorMap.ruleCount(c) >= 0, "" + MirrorMap.ruleCount(c));
			check(b, "镜像改写不崩", MirrorMap.rewrite(c, "https://m.example/a") != null, "");
			check(b, "虚拟鼠标脚本非空",
				VirtualMouse.SCRIPT != null && VirtualMouse.SCRIPT.length() > 100
					&& VirtualMouse.OFF_SCRIPT != null, "");
		} catch (Throwable t) {
			fail(b, "安全浏览/镜像自检可执行", String.valueOf(t));
		}
	}

	// ---------------- 组件与资源 ----------------

	private static final String[] ACTIVITIES = {
		".MainActivity", ".start", ".SettingsActivity", ".FeedActivity", ".FeedEditActivity",
		".FeedItemsActivity", ".FeedReaderActivity", ".BookmarksActivity", ".HistoryActivity",
		".DownloadActivity", ".DlActivity", ".AiActivity", ".BtActivity", ".FtpActivity",
		".MqttActivity", ".PluginActivity", ".UaActivity", ".UpActivity", ".CertActivity",
		".LogActivity", ".EngineActivity", ".CustomTabs"
	};

	private static void checkComponents(Context c, StringBuilder b) {
		PackageManager pm = c.getPackageManager();
		int missing = 0;
		StringBuilder miss = new StringBuilder();
		for (String a : ACTIVITIES) {
			try {
				pm.getActivityInfo(new android.content.ComponentName(c, c.getPackageName() + a), 0);
			} catch (Throwable t) {
				missing++;
				miss.append(a).append(' ');
			}
		}
		check(b, "Activity 注册完整（" + (ACTIVITIES.length - missing) + "/" + ACTIVITIES.length + "）",
			missing == 0, miss.toString());
		try {
			android.content.Intent i = pm.getLaunchIntentForPackage(c.getPackageName());
			check(b, "启动入口存在（LAUNCHER）", i != null, "null");
		} catch (Throwable t) {
			fail(b, "启动入口查询", String.valueOf(t));
		}
	}

	private static void checkResources(Context c, StringBuilder b) {
		try {
			c.getDrawable(R.drawable.ic_rss);
			check(b, "菜单 RSS 图标资源可解析", true, "");
		} catch (Throwable t) {
			fail(b, "菜单 RSS 图标资源可解析", String.valueOf(t));
		}
		try {
			int col = c.getResources().getColor(R.color.card_bg);
			check(b, "卡片底色资源可解析", col != 0, "");
		} catch (Throwable t) {
			fail(b, "卡片底色资源可解析", String.valueOf(t));
		}
		try {
			c.getResources().getString(R.string.open_source_license);
			ok(b, "基础字符串资源可解析");
		} catch (Throwable t) {
			fail(b, "基础字符串资源可解析", String.valueOf(t));
		}
	}

	private static void checkDefaults(Context c, StringBuilder b) {
		try {
			check(b, "主页地址非空", VieYApp.getHomeUrl(c) != null && !VieYApp.getHomeUrl(c).isEmpty(), "");
			check(b, "搜索模板含 %s", VieYApp.getSearchEngine(c).contains("%s"), VieYApp.getSearchEngine(c));
			check(b, "工具栏位置取值合法",
				VieYApp.TOOLBAR_POS_TOP.equals(VieYApp.getToolbarPosition(c))
					|| VieYApp.TOOLBAR_POS_BOTTOM.equals(VieYApp.getToolbarPosition(c))
					|| VieYApp.TOOLBAR_POS_TOP_SIDE.equals(VieYApp.getToolbarPosition(c))
					|| VieYApp.TOOLBAR_POS_BOTTOM_SIDE.equals(VieYApp.getToolbarPosition(c)),
				VieYApp.getToolbarPosition(c));
			check(b, "下载目录非空", VieYApp.getDownloadPath(c) != null && !VieYApp.getDownloadPath(c).isEmpty(), "");
			check(b, "日期格式非空", VieYApp.getDateFormat(c) != null && !VieYApp.getDateFormat(c).isEmpty(), "");
			JSONArray arr = new JSONArray();
			arr.put("x");
			check(b, "JSONArray 基础可用", arr.length() == 1, "");
		} catch (Throwable t) {
			fail(b, "默认配置自检可执行", String.valueOf(t));
		}
	}
}
