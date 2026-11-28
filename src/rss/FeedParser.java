package kawaii.viey.browser;

import android.text.TextUtils;

import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.StringReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 订阅解析：RSS2 / RDF(RSS1) / Atom，以及 Legado 规则式 HTML 列表。
 */
public class FeedParser {

    public static class Article {
        public String title = "";
        /** 聚合视图用来标注来源 */
        public String source = "";
        public String link = "";
        public String pubDate = "";
        public String description = "";
        public String image = "";

        public String subtitle() {
            if (pubDate != null && pubDate.length() > 0) return pubDate;
            return TextUtils.isEmpty(description) ? link : description;
        }
    }

    /** 带上下文入口：命中列表级 @js: 规则时先走离屏沙箱，失败再退回 CSS 解析 */
    public static List<Article> parse(android.content.Context c, String body, FeedSource src, String baseUrl) {
        try {
            if (RuleJs.needed(src)) {
                List<Article> js = RuleJs.parse(c, src, baseUrl, body);
                if (!js.isEmpty()) return js;
            }
        } catch (Throwable ignored) {
        }
        return parse(body, src, baseUrl);
    }

    public static List<Article> parse(String body, FeedSource src, String baseUrl) {
        if (body == null) return new ArrayList<>();
        String head = body.substring(0, Math.min(body.length(), 512)).toLowerCase(Locale.ROOT);
        if (head.contains("<rss") || head.contains("<feed") || head.contains("<rdf") || head.contains("<rdf:rdf")) {
            List<Article> list = parseXml(body);
            if (!list.isEmpty()) return list;
        }
        if (!TextUtils.isEmpty(src.ruleArticles)) {
            return parseByRules(body, src, baseUrl);
        }
        return new ArrayList<>();
    }

    /**
     * 兜底：页面没有规则、也不是 RSS 时，把同域下文字够长的链接当成条目。
     * 主要用于源仓库/目录型网页，条目少于 3 条则不采用，避免把导航页当订阅。
     */
    public static List<Article> parseAnchors(String body, String baseUrl) {
        return parseAnchors(body, baseUrl, false);
    }

    /**
     * 从纯链接页面兜底提取条目。sameHostOnly=false 时允许跨域（导航页/发布页常见），
     * 但会丢掉 #、javascript、以及纯功能链接。
     */
    public static List<Article> parseAnchors(String body, String baseUrl, boolean allowCrossHost) {
        List<Article> out = new ArrayList<>();
        if (body == null) return out;
        String baseHost = hostOf(baseUrl);
        try {
            Document doc = Jsoup.parse(body, baseUrl == null ? "" : baseUrl);
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (Element a : doc.select("a[href]")) {
                String href = a.absUrl("href");
                if (href.isEmpty()) href = a.attr("href");
                String low = href.toLowerCase();
                if (low.startsWith("javascript:") || low.startsWith("mailto:") || low.startsWith("#") || low.isEmpty()) continue;
                if (!allowCrossHost && baseHost != null && !baseHost.isEmpty() && !hostOf(href).endsWith(baseHost)) continue;
                String text = a.text().trim();
                if (text.length() < 6) continue;
                if (seen.contains(href)) continue;
                seen.add(href);
                Article x = new Article();
                x.title = text;
                x.link = href;
                out.add(x);
                if (out.size() >= 80) break;
            }
        } catch (Throwable ignored) {
        }
        return out.size() >= 3 ? out : new ArrayList<>();
    }

    private static String hostOf(String url) {
        try {
            java.net.URL u = new java.net.URL(url);
            String h = u.getHost();
            return h == null ? "" : h.toLowerCase();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 看起来是 JS 渲染的空壳页（内容由前端框架填充） */
    public static boolean looksLikeSpa(String body) {
        if (body == null) return false;
        String low = body.toLowerCase(Locale.ROOT);
        int anchors = low.split("<a ").length - 1;
        if (anchors >= 6) return false;
        return low.contains("id=\"app\"") || low.contains("id='app'")
            || (low.contains("<script") && body.length() < 70000 && anchors <= 2);
    }

    // ---------------- XML ----------------

    private static List<Article> parseXml(String body) {
        List<Article> out = new ArrayList<>();
        try {
            XmlPullParserFactory f = XmlPullParserFactory.newInstance();
            f.setNamespaceAware(false);
            XmlPullParser p = f.newPullParser();
            p.setInput(new StringReader(body));
            int ev = p.getEventType();
            Article cur = null;
            String tag = "";
            StringBuilder text = new StringBuilder();
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    tag = p.getName();
                    text.setLength(0);
                    if (tag.equalsIgnoreCase("item") || tag.equalsIgnoreCase("entry")) {
                        cur = new Article();
                    } else if (cur != null && tag.equalsIgnoreCase("link") && p.getAttributeCount() > 0) {
                        String href = p.getAttributeValue(null, "href");
                        String rel = p.getAttributeValue(null, "rel");
                        if (href != null && (rel == null || rel.equals("alternate"))) cur.link = href;
                    }
                } else if (ev == XmlPullParser.TEXT) {
                    text.append(p.getText());
                } else if (ev == XmlPullParser.END_TAG) {
                    String name = p.getName();
                    String val = text.toString().trim();
                    if (cur != null) {
                        if (name.equalsIgnoreCase("title")) cur.title = val;
                        else if (name.equalsIgnoreCase("link") && cur.link.length() == 0) cur.link = val;
                        else if (name.equalsIgnoreCase("pubDate") || name.equalsIgnoreCase("published") || name.equalsIgnoreCase("updated") || name.equalsIgnoreCase("date")) {
                            if (cur.pubDate.length() == 0) cur.pubDate = normalizeDate(val);
                        } else if (name.equalsIgnoreCase("description") || name.equalsIgnoreCase("summary") || name.equalsIgnoreCase("content")) {
                            if (cur.description.length() == 0) cur.description = plain(val);
                        } else if (name.equalsIgnoreCase("item") || name.equalsIgnoreCase("entry")) {
                            if (!TextUtils.isEmpty(cur.title) || !TextUtils.isEmpty(cur.link)) out.add(cur);
                            cur = null;
                        }
                    }
                    text.setLength(0);
                }
                ev = p.next();
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static String normalizeDate(String raw) {
        if (raw == null) return "";
        String[] patterns = {
                "EEE, dd MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm:ss Z",
                "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd"
        };
        for (String pat : patterns) {
            try {
                SimpleDateFormat in = new SimpleDateFormat(pat, Locale.US);
                Date d = in.parse(raw);
                if (d != null) return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(d);
            } catch (Exception ignored) {
            }
        }
        return raw;
    }

    private static String plain(String html) {
        if (html == null) return "";
        return Jsoup.parse(html).text();
    }

    // ---------------- 规则式 HTML ----------------

    private static List<Article> parseByRules(String body, FeedSource src, String baseUrl) {
        List<Article> out = new ArrayList<>();
        try {
            Document doc = Jsoup.parse(body, baseUrl == null ? "" : baseUrl);
            String articlesRule = src.ruleArticles == null ? "" : src.ruleArticles;
            // 去掉 # 后的索引段，例如 class.post@tag.article#2
            articlesRule = articlesRule.split("#")[0];
            Elements items = select(doc, articlesRule);
            for (Element item : items) {
                Article a = new Article();
                a.title = value(item, src.ruleTitle, "title");
                a.link = absUrl(item, baseUrl, value(item, src.ruleLink, "href"));
                a.pubDate = normalizeDate(value(item, src.rulePubDate, ""));
                a.description = plain(value(item, src.ruleDescription, ""));
                String imgRule = src.ruleImage;
                if (!TextUtils.isEmpty(imgRule)) {
                    Elements imgs = select(item, imgRule.split("#")[0]);
                    if (!imgs.isEmpty()) {
                        a.image = imgs.first().absUrl("src");
                        if (TextUtils.isEmpty(a.image)) a.image = imgs.first().attr("data-src");
                    }
                }
                if (!TextUtils.isEmpty(a.title) || !TextUtils.isEmpty(a.link)) out.add(a);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static Elements select(Element root, String rule) {
        if (TextUtils.isEmpty(rule)) return new Elements();
        rule = rule.trim();
        if (rule.startsWith("@css:")) rule = rule.substring(5);
        if (rule.startsWith("class.")) return root.select("." + rule.substring(6).trim());
        if (rule.startsWith("id.")) return root.select("#" + rule.substring(3).trim());
        if (rule.startsWith("tag.")) return root.select(rule.substring(4).trim());
        return root.select(rule);
    }

    /** 取字段值，支持 Legado 风格：class.x@tag.a@text / @href / @html / @attr:xxx */
    private static String value(Element root, String rule, String defAttr) {
        if (TextUtils.isEmpty(rule)) {
            switch (defAttr) {
                case "title":
                    return root.text();
                case "href":
                    return root.absUrl("href");
                default:
                    return "";
            }
        }
        String[] parts = rule.split("@");
        Element cur = root;
        String last = "";
        for (int k = 0; k < parts.length; k++) {
            String seg = parts[k].trim();
            if (seg.length() == 0) continue;
            if (seg.equals("text")) {
                last = cur.text();
            } else if (seg.equals("html") || seg.equals("content")) {
                last = cur.html();
            } else if (seg.equals("href")) {
                last = cur.absUrl("href");
            } else if (seg.startsWith("attr:")) {
                last = cur.attr(seg.substring(5));
            } else if (seg.contains(".") || seg.startsWith("[") || seg.startsWith(":")) {
                Elements next = select(cur, seg.startsWith("class.") || seg.startsWith("tag.") || seg.startsWith("id.") ? seg : ("@css:" + seg));
                if (next.isEmpty()) return "";
                cur = next.first();
                last = cur.text();
            } else {
                // 纯标签名，如 tag.a 之外的 "a"
                Elements next = cur.select(seg);
                if (!next.isEmpty()) {
                    cur = next.first();
                    last = cur.text();
                }
            }
        }
        return last;
    }

    private static String absUrl(Element item, String baseUrl, String link) {
        if (link == null) return "";
        if (link.startsWith("http://") || link.startsWith("https://")) return link;
        try {
            Element a = item.selectFirst("a[href]");
            if (a != null) {
                String abs = a.absUrl("href");
                if (!TextUtils.isEmpty(abs)) return abs;
            }
            if (baseUrl != null) {
                java.net.URL base = new java.net.URL(baseUrl);
                return new java.net.URL(base, link).toString();
            }
        } catch (Exception ignored) {
        }
        return link;
    }

    /** 请求头：Legado header 字段是 JSON 对象字符串 */
    public static void applyHeader(java.net.HttpURLConnection conn, String header) {
        if (TextUtils.isEmpty(header)) return;
        try {
            JSONObject o = new JSONObject(header);
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                String v = o.optString(k, "");
                if (k != null && k.length() > 0 && v != null) conn.setRequestProperty(k, v);
            }
        } catch (Exception ignored) {
        }
    }
}
