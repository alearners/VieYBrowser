package kawaii.viey.browser;

import android.text.TextUtils;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.List;

/**
 * 正文提取（简化 readable）：先按订阅源 ruleContent 取，再按文本密度找主容器。
 * 输出可直接丢给 WebView 的 HTML 片段。
 */
public class ContentExtractor {

    private static final String[] DROP_TAGS =
            {"script", "style", "noscript", "iframe", "form", "nav", "aside", "footer", "header", "svg", "button"};

    public static String extract(String html, String baseUrl, FeedSource src) {
        if (html == null) return "";
        Document doc = Jsoup.parse(html, baseUrl == null ? "" : baseUrl);
        for (String tag : DROP_TAGS) doc.select(tag).remove();
        doc.select("[class*=advert], [id*=advert], [class*=sidebar], [class*=comment]").remove();

        if (src != null && !TextUtils.isEmpty(src.ruleContent)) {
            String byRule = byRule(doc, src.ruleContent);
            if (!TextUtils.isEmpty(byRule)) return byRule;
        }

        Element best = pickMain(doc);
        if (best == null) best = doc.body();
        if (best == null) return "";
        return best.html();
    }

    /** 只支持 CSS 选择器形态（@css: / @@css: / 裸选择器），够覆盖绝大多数订阅源 */
    private static String byRule(Document doc, String rule) {
        String r = rule.trim();
        if (r.startsWith("@css:")) r = r.substring(5);
        else if (r.startsWith("@@css:")) r = r.substring(6);
        else if (r.contains("@") || r.startsWith("$") || r.startsWith("//")) return null;
        try {
            Elements es = doc.select(r.trim());
            if (es.isEmpty()) return null;
            List<String> parts = new ArrayList<>();
            for (Element e : es) parts.add(e.html());
            return TextUtils.join("\n", parts);
        } catch (Exception e) {
            return null;
        }
    }

    private static Element pickMain(Document doc) {
        Element best = null;
        double bestScore = 0;
        for (Element e : doc.select("article, main, [role=main], div, section")) {
            int textLen = e.text().length();
            int pCount = e.select("p").size();
            if (textLen < 200) continue;
            int linkLen = 0;
            for (Element a : e.select("a")) linkLen += a.text().length();
            double density = (double) (textLen - linkLen) / Math.max(1, e.getAllElements().size());
            double score = density * Math.log(1 + textLen) + pCount * 8;
            if (score > bestScore) {
                bestScore = score;
                best = e;
            }
        }
        return best;
    }

    /** 生成完整可渲染页面：正文 + 排版样式（暗色/字号由外部决定） */
    /**
     * 阅读页净化：抽出 body 内层，去掉 script/style/link/iframe/form 和 display:none 容器。
     * 不净化的话，@js: 返回整页 body.innerHTML 时会把原站 CSS 一起塞进阅读页（常见的 body{display:none}
     * 或入场动画会导致整页白屏）。
     */
    public static String clean(String html) {
        if (TextUtils.isEmpty(html)) return "";
        String s = html;
        String low = s.toLowerCase(java.util.Locale.US);
        int b = low.indexOf("<body");
        if (b >= 0) {
            int gt = s.indexOf('>', b);
            int end = low.lastIndexOf("</body>");
            if (gt > 0) s = end > gt ? s.substring(gt + 1, end) : s.substring(gt + 1);
        }
        Document d = Jsoup.parseBodyFragment(s);
        d.outputSettings().prettyPrint(false);
        d.select("script,style,link,meta,iframe,noscript,form,input,button,textarea,object,embed")
                .remove();
        for (Element e : new ArrayList<>(d.select("[style]"))) {
            String st = e.attr("style").toLowerCase(java.util.Locale.US).replace(" ", "");
            if (st.contains("display:none") || st.contains("visibility:hidden") || st.contains("opacity:0")) {
                e.remove();
            }
        }
        d.select("[hidden]").remove();
        return d.body().html();
    }

    public static String wrap(String bodyHtml, String title, String baseUrl, boolean dark, int textZoom) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">");
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">");
        if (!TextUtils.isEmpty(baseUrl)) sb.append("<base href=\"").append(escape(baseUrl)).append("\">");
        sb.append("<style>");
        sb.append("html,body{margin:0;padding:0;background:").append(dark ? "#121212" : "#ffffff").append(";}")
          .append("body{padding:14px 16px 40px;font-size:").append(textZoom).append("px;line-height:1.8;")
          .append("color:").append(dark ? "#d6d6d6" : "#222222").append(";")
          .append("font-family:-apple-system,'Noto Sans CJK SC','Source Han Sans SC',sans-serif;")
          .append("word-wrap:break-word;overflow-wrap:break-word;-webkit-text-size-adjust:100%;}")
          .append("h1,h2,h3{line-height:1.45;margin:1.1em 0 .5em;}")
          .append("h1{font-size:1.35em;}")
          .append("img,svg,video{max-width:100%;height:auto;display:block;margin:10px auto;}")
          .append("figure{margin:12px 0;}figcaption{font-size:.85em;opacity:.7;text-align:center;}")
          .append("a{color:").append(dark ? "#7fb3ff" : "#1a66cc").append(";}")
          .append("pre,code{white-space:pre-wrap;word-break:break-all;background:")
          .append(dark ? "#1e1e1e" : "#f5f5f5").append(";border-radius:4px;padding:2px 4px;font-size:.92em;}")
          .append("blockquote{border-left:3px solid #8884;margin:12px 0;padding:2px 12px;opacity:.85;}")
          .append("table{max-width:100%;border-collapse:collapse;}td,th{border:1px solid #8884;padding:4px 6px;}")
          .append("hr{border:0;border-top:1px solid #8884;margin:18px 0;}")
          .append("</style></head><body>");
        if (!TextUtils.isEmpty(title)) {
            sb.append("<h1>").append(escape(title)).append("</h1>");
        }
        sb.append(bodyHtml == null ? "" : clean(bodyHtml));
        sb.append("</body></html>");
        return sb.toString();
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
