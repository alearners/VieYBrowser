package kawaii.viey.browser;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 列表级 @js: 规则沙箱。
 *
 * 阅读(Legado) 的订阅源里，ruleArticles / ruleTitle / ruleLink / ruleContent 等可以写成
 * `@js:` 脚本，并在脚本里调用 java.getString / java.getElements / java.getElement 等。
 * 裸 Jsoup 解析不了这类规则，这里用一个离屏 WebView 把页面与脚本跑一遍，拿回 JSON 列表。
 *
 * 约定（对齐阅读的常用写法）：
 *   - ruleArticles 为 @js: 时，脚本返回值可以是元素、元素数组、或 HTML 字符串；
 *   - 字段规则为 @js: 时，脚本里的 `result` 是当前条目元素（带 select/text/html/attr）；
 *   - 可用对象：java.* / baseUrl / source / document / window。
 */
public class RuleJs {

    private static final String PREFS = "rss_rulejs";
    private static final String KEY_ON = "rulejs_on";

    public static boolean enabled(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, true);
    }

    public static void setEnabled(Context c, boolean on) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply();
    }

    /** 有任一列表级规则是 @js: 才需要沙箱 */
    public static boolean needed(FeedSource s) {
        if (s == null) return false;
        return js(s.ruleArticles) || js(s.ruleNextPage) || js(s.ruleTitle) || js(s.ruleLink)
            || js(s.rulePubDate) || js(s.ruleDescription) || js(s.ruleImage);
    }

    private static boolean js(String rule) {
        return rule != null && rule.trim().startsWith("@js:");
    }

    /**
     * 在离屏 WebView 里执行规则，返回解析出的条目。调用方应位于工作线程：
     * 这里会把 WebView 的创建与求值 post 到主线程，并用闭锁等待结果。
     */
    public static List<FeedParser.Article> parse(Context c, FeedSource src, String url, String html) {
        final List<FeedParser.Article> out = new ArrayList<>();
        if (!enabled(c) || html == null || html.length() == 0) return out;

        final CountDownLatch latch = new CountDownLatch(1);
        final Handler ui = new Handler(Looper.getMainLooper());
        final String script = buildScript(src, url);

        ui.post(() -> {
            WebView wv = null;
            try {
                wv = new WebView(c.getApplicationContext());
                WebSettings ws = wv.getSettings();
                ws.setJavaScriptEnabled(true);
                ws.setBlockNetworkLoads(true);
                ws.setBlockNetworkImage(true);
                ws.setDomStorageEnabled(true);
                final WebView fwv = wv;
                final boolean[] done = {false};
                wv.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String u) {
                        if (done[0]) return;
                        done[0] = true;
                        try {
                            view.evaluateJavascript(script, value -> {
                                try {
                                    collect(value, out);
                                } catch (Throwable t) {
                                    android.util.Log.w("viey-rulejs", "结果解析失败：" + t);
                                } finally {
                                    latch.countDown();
                                    try { fwv.destroy(); } catch (Throwable ignored) {}
                                }
                            });
                        } catch (Throwable t) {
                            android.util.Log.w("viey-rulejs", "求值失败：" + t);
                            latch.countDown();
                        }
                    }
                });
                wv.loadDataWithBaseURL(url == null || url.isEmpty() ? "https://localhost/" : url,
                    html, "text/html", "utf-8", null);
            } catch (Throwable t) {
                android.util.Log.w("viey-rulejs", "沙箱启动失败：" + t);
                latch.countDown();
            }
        });

        try {
            if (!latch.await(25, TimeUnit.SECONDS)) {
                android.util.Log.w("viey-rulejs", "沙箱超时：" + src.displayTitle());
            }
        } catch (InterruptedException ignored) {
        }
        return out;
    }

    private static void collect(String value, List<FeedParser.Article> out) throws org.json.JSONException {
        if (value == null) return;
        String s = value.trim();
        if (s.startsWith("\"")) {
            // evaluateJavascript 会再包一层 JSON 字符串
            s = new org.json.JSONTokener(s).nextValue().toString();
        }
        JSONArray arr = new JSONArray(s);
        for (int k = 0; k < arr.length(); k++) {
            JSONObject o = arr.optJSONObject(k);
            if (o == null) continue;
            FeedParser.Article a = new FeedParser.Article();
            a.title = o.optString("title", "");
            a.link = o.optString("link", "");
            a.pubDate = o.optString("pubDate", "");
            a.description = o.optString("description", "");
            a.image = o.optString("image", "");
            if (!a.title.isEmpty() || !a.link.isEmpty()) out.add(a);
        }
    }

    private static String buildScript(FeedSource src, String url) {
        JSONObject rules = new JSONObject();
        try {
            rules.put("ruleArticles", src.ruleArticles);
            rules.put("ruleTitle", src.ruleTitle);
            rules.put("ruleLink", src.ruleLink);
            rules.put("rulePubDate", src.rulePubDate);
            rules.put("ruleDescription", src.ruleDescription);
            rules.put("ruleImage", src.ruleImage);
        } catch (Exception ignored) {
        }
        String rulesJson = rules.toString();
        String srcJson = src.toLegado().toString();
        String base = url == null ? "" : url;
        String lib = src.jsLib == null ? "" : src.jsLib;

        return "(function(){\n"
            + "var LIBS = " + jsStr(lib) + ";\n"
            + "var RULES = " + rulesJson + ";\n"
            + "var SRC = " + srcJson + ";\n"
            + "var BASE = " + jsStr(base) + ";\n"
            + "try{ if(LIBS){ (new Function(LIBS))(); } }catch(e){ console.log('jsLib: '+e); }\n"
            + "function wrapList(list){\n"
            + "  var arr=[]; for(var i=0;i<list.length;i++) arr.push(wrapNode(list[i]));\n"
            + "  arr.text=function(){ return arr.length?arr[0].text():''; };\n"
            + "  arr.size=function(){ return arr.length; };\n"
            + "  arr.get=function(i){ return arr[i]; };\n"
            + "  arr.select=function(s){ if(!arr.length) return wrapList([]); return arr[0].selectList(s); };\n"
            + "  arr.attr=function(n){ return arr.length?arr[0].attr(n):''; };\n"
            + "  arr.html=function(){ return arr.length?arr[0].html():''; };\n"
            + "  return arr;\n"
            + "}\n"
            + "function wrapNode(el){\n"
            + "  if(!el) return null;\n"
            + "  return {\n"
            + "    el: el,\n"
            + "    text: function(){ return (el.innerText||el.textContent||'').trim(); },\n"
            + "    html: function(){ return el.innerHTML||''; },\n"
            + "    attr: function(n){ return el.getAttribute? (el.getAttribute(n)||''):''; },\n"
            + "    select: function(s){ return el.querySelector? wrapNode(el.querySelector(strip(s))):null; },\n"
            + "    selectFirst: function(s){ return this.select(s); },\n"
            + "    selectList: function(s){ return el.querySelectorAll? wrapList(el.querySelectorAll(strip(s))):wrapList([]); },\n"
            + "    toString: function(){ return (el.innerText||el.textContent||'').trim(); }\n"
            + "  };\n"
            + "}\n"
            + "function strip(s){\n"
            + "  s=String(s||'').trim();\n"
            + "  if(s.indexOf('@css:')===0) s=s.substring(5);\n"
            + "  if(s.indexOf('class.')===0) return '.'+s.substring(6);\n"
            + "  if(s.indexOf('id.')===0) return '#'+s.substring(3);\n"
            + "  if(s.indexOf('tag.')===0) return s.substring(4);\n"
            + "  return s;\n"
            + "}\n"
            + "var java = {\n"
            + "  getString: function(rule){ return pick(document, rule); },\n"
            + "  getElements: function(rule){ return wrapList(document.querySelectorAll(strip(rule))); },\n"
            + "  getElement: function(rule){ return wrapNode(document.querySelector(strip(rule))); },\n"
            + "  log: function(s){ console.log(String(s)); },\n"
            + "  toast: function(s){ console.log(String(s)); },\n"
            + "  base64: function(s){ try{ return btoa(unescape(encodeURIComponent(String(s)))); }catch(e){ return ''; } },\n"
            + "  md5: function(s){ return String(s); },\n"
            + "  getCookie: function(){ return ''; },\n"
            + "  put: function(k,v){ try{ localStorage.setItem(k,String(v)); }catch(e){} },\n"
            + "  get: function(k){ try{ return localStorage.getItem(k)||''; }catch(e){ return ''; } },\n"
            + "  ajax: function(u){ console.log('java.ajax 不支持同步请求：'+u); return ''; }\n"
            + "};\n"
            + "function runJs(code, result){\n"
            + "  try{ var fn=new Function('result','java','baseUrl','source','document','window',code);\n"
            + "    return fn(result, java, BASE, SRC, document, window); }catch(e){ console.log('rule js: '+e); return ''; }\n"
            + "}\n"
            + "function pick(root, rule){\n"
            + "  if(!rule) return '';\n"
            + "  rule=String(rule).trim();\n"
            + "  if(rule.indexOf('@js:')===0) return String(runJs(rule.substring(4), wrapNode(root))||'');\n"
            + "  var parts=rule.split('@'); var cur=wrapNode(root); var last='';\n"
            + "  for(var i=0;i<parts.length;i++){\n"
            + "    var seg=parts[i].trim(); if(!seg) continue;\n"
            + "    if(seg==='text'){ last=cur.text(); }\n"
            + "    else if(seg==='html'||seg==='content'){ last=cur.html(); }\n"
            + "    else if(seg==='href'){ last=cur.attr('href'); }\n"
            + "    else if(seg.indexOf('attr:')===0){ last=cur.attr(seg.substring(5)); }\n"
            + "    else { var nx=cur.select(seg); if(!nx) return ''; cur=nx; last=cur.text(); }\n"
            + "  }\n"
            + "  return last;\n"
            + "}\n"
            + "var items=[];\n"
            + "var ruleA=RULES.ruleArticles||'';\n"
            + "if(ruleA.indexOf('@js:')===0){\n"
            + "  var r=runJs(ruleA.substring(4), null);\n"
            + "  if(typeof r==='string'){ try{ r=JSON.parse(r); }catch(e){ if(r){ items=[wrapNode(document)]; return finish(); } } }\n"
            + "  if(r && r.length!==undefined){ for(var i=0;i<r.length;i++){ var x=r[i]; items.push(x && x.el? x : wrapNode(x)); } }\n"
            + "  else if(r && r.el){ items=[r]; }\n"
            + "  else if(r && r.nodeType===1){ items=[wrapNode(r)]; }\n"
            + "}else{\n"
            + "  var list=document.querySelectorAll(strip(ruleA));\n"
            + "  for(var i=0;i<list.length;i++) items.push(wrapNode(list[i]));\n"
            + "}\n"
            + "function finish(){\n"
            + "  var out=[]; for(var i=0;i<items.length;i++){\n"
            + "    var it=items[i]; if(!it) continue; var raw=it.el||it;\n"
            + "    var a={title:pick(raw,RULES.ruleTitle||''),link:pick(raw,RULES.ruleLink||''),pubDate:pick(raw,RULES.rulePubDate||''),description:pick(raw,RULES.ruleDescription||''),image:RULES.ruleImage?pick(raw,RULES.ruleImage):''};\n"
            + "    if(!a.title) a.title=(raw.innerText||raw.textContent||'').trim();\n"
            + "    if(!a.link && raw.querySelector){ var an=raw.querySelector('a'); if(an){ var h=an.getAttribute('href')||''; try{ a.link=(new URL(h, BASE)).toString(); }catch(e){ a.link=h; } } }\n"
            + "    if(a.title||a.link) out.push(a);\n"
            + "  }\n"
            + "  return JSON.stringify(out);\n"
            + "}\n"
            + "return finish();\n"
            + "})()";
    }

    private static String jsStr(String s) {
        if (s == null) return "''";
        StringBuilder b = new StringBuilder("\"");
        for (int k = 0; k < s.length(); k++) {
            char ch = s.charAt(k);
            if (ch == '"' || ch == '\\') b.append('\\').append(ch);
            else if (ch == '\n') b.append("\\n");
            else if (ch == '\r') b.append("\\r");
            else if (ch == '\t') b.append("\\t");
            else b.append(ch);
        }
        return b.append('"').toString();
    }
}
