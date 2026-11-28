package kawaii.viey.browser;

import org.json.JSONObject;

/**
 * 订阅源（兼容 Legado / 阅读 3.x 的 rssSources.json 字段口径）。
 */
public class FeedSource {

    public String name = "";
    public String url = "";
    public String group = "";
    public String header = "";          // JSON 对象字符串
    public String jsLib = "";
    public String ruleArticles = "";
    public String ruleTitle = "";
    public String ruleLink = "";
    public String rulePubDate = "";
    public String ruleDescription = "";
    public String ruleImage = "";
    public String ruleContent = "";
    public String ruleNextPage = "";
    public String icon = "";
    public boolean enabled = true;
    public long lastUpdate = 0L;

    public static FeedSource fromLegado(JSONObject o) {
        FeedSource s = new FeedSource();
        s.name = opt3(o, "rssSourceName", "sourceName", "name");
        s.url = opt3(o, "rssSourceUrl", "sourceUrl", "url");
        s.group = opt3(o, "rssSourceGroup", "sourceGroup", "group");
        s.header = opt(o, "header", "header", "");
        s.jsLib = opt(o, "jsLib", "jsLib", "");
        s.ruleArticles = opt(o, "ruleArticles", "ruleArticles", "");
        s.ruleTitle = opt(o, "ruleTitle", "ruleTitle", "");
        s.ruleLink = opt(o, "ruleLink", "ruleLink", "");
        s.rulePubDate = opt(o, "rulePubDate", "rulePubDate", "");
        s.ruleDescription = opt(o, "ruleDescription", "ruleDescription", "");
        s.ruleImage = opt(o, "ruleImage", "ruleImage", "");
        s.ruleContent = opt(o, "ruleContent", "ruleContent", "");
        s.ruleNextPage = opt(o, "ruleNextPage", "ruleNextPage", "");
        s.icon = opt3(o, "rssSourceIcon", "sourceIcon", "icon");
        s.enabled = !o.has("enabled") || o.optBoolean("enabled", true);
        s.lastUpdate = o.optLong("lastUpdate", 0L);
        return s;
    }

    public JSONObject toLegado() {
        JSONObject o = new JSONObject();
        put(o, "rssSourceName", name);
        put(o, "rssSourceUrl", url);
        put(o, "rssSourceGroup", group);
        put(o, "header", header);
        put(o, "jsLib", jsLib);
        put(o, "ruleArticles", ruleArticles);
        put(o, "ruleTitle", ruleTitle);
        put(o, "ruleLink", ruleLink);
        put(o, "rulePubDate", rulePubDate);
        put(o, "ruleDescription", ruleDescription);
        put(o, "ruleImage", ruleImage);
        put(o, "ruleContent", ruleContent);
        put(o, "ruleNextPage", ruleNextPage);
        put(o, "rssSourceIcon", icon);
        try {
            o.put("enabled", enabled);
            o.put("lastUpdate", lastUpdate);
        } catch (Exception ignored) {
        }
        return o;
    }

    public String displayTitle() {
        if (name != null && name.length() > 0) return name;
        return url;
    }

    public String displaySubtitle() {
        if (group != null && group.length() > 0) return group + " · " + url;
        return url;
    }

    private static void put(JSONObject o, String k, String v) {
        try {
            o.put(k, v == null ? "" : v);
        } catch (Exception ignored) {
        }
    }

    /** 兼容阅读 3.x（rssSourceName）与新式订阅源（sourceName）两套字段名 */
    private static String opt3(JSONObject o, String a, String b, String c) {
        String v = o.optString(a, null);
        if (v == null || v.length() == 0) v = o.optString(b, null);
        if (v == null || v.length() == 0) v = o.optString(c, "");
        return v == null ? "" : v;
    }

    private static String opt(JSONObject o, String a, String b, String def) {
        String v = o.optString(a, null);
        if (v == null || v.length() == 0) v = o.optString(b, def);
        return v == null ? def : v;
    }
}
