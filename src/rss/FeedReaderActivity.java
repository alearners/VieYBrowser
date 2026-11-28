package kawaii.viey.browser;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 应用内阅读页：抓正文 → 提取 → 排版渲染，离线缓存 + 已读/收藏。
 * 抓取失败时读缓存，缓存也没有就提示跳原网页。
 */
public class FeedReaderActivity extends BaseActivity {

    public static final String EXTRA_LINK = "feed_link";
    public static final String EXTRA_TITLE = "feed_article_title";
    public static final String EXTRA_SOURCE = "feed_source_json";
    public static final String EXTRA_SOURCE_NAME = "feed_source_name";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private WebView web;
    private TextView titleView;
    private ImageView favBtn;
    private FeedSource src;
    private FeedParser.Article article = new FeedParser.Article();
    private int textZoom = 17;
    private boolean fromCache = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_feed_reader);

        web = findViewById(R.id.reader_web);
        titleView = findViewById(R.id.reader_title);
        favBtn = findViewById(R.id.reader_fav);

        article.link = getIntent().getStringExtra(EXTRA_LINK);
        article.title = getIntent().getStringExtra(EXTRA_TITLE);
        String srcJson = getIntent().getStringExtra(EXTRA_SOURCE);
        if (!TextUtils.isEmpty(srcJson)) {
            try {
                src = FeedSource.fromLegado(new JSONObject(srcJson));
            } catch (Exception ignored) {
            }
        }
        String srcName = getIntent().getStringExtra(EXTRA_SOURCE_NAME);
        if (TextUtils.isEmpty(srcName) && src != null) srcName = src.displayTitle();
        article.pubDate = srcName == null ? "" : srcName;

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(false);
        s.setLoadsImagesAutomatically(true);
        s.setBlockNetworkImage(false);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(false);
        s.setTextZoom(100);
        web.setBackgroundColor(VieYApp.isDarkMode(this) ? 0xFF121212 : 0xFFFFFFFF);

        titleView.setText(TextUtils.isEmpty(article.title) ? "阅读" : article.title);
        findViewById(R.id.back_tool).setOnClickListener(v -> finish());
        findViewById(R.id.reader_orig).setOnClickListener(v -> openOriginal());
        findViewById(R.id.reader_zoom).setOnClickListener(v -> cycleZoom());
        favBtn.setOnClickListener(v -> {
            boolean now = FeedState.toggleFav(this, article, article.pubDate);
            updateFavIcon(now);
            Toast.makeText(this, now ? "已收藏" : "已取消收藏", Toast.LENGTH_SHORT).show();
        });

        updateFavIcon(FeedState.isFav(this, article.link));
        FeedState.markRead(this, article.link);
        load();
    }

    private void updateFavIcon(boolean fav) {
        favBtn.setImageResource(fav ? R.drawable.ic_bookmark_filled : R.drawable.ic_bookmark_border);
    }

    private void cycleZoom() {
        textZoom = textZoom >= 21 ? 15 : textZoom + 2;
        web.getSettings().setTextZoom((int) (textZoom * 100f / 17f));
        Toast.makeText(this, "字号 " + textZoom, Toast.LENGTH_SHORT).show();
    }

    private void openOriginal() {
        if (TextUtils.isEmpty(article.link)) return;
        Intent it = new Intent(this, MainActivity.class);
        it.setData(Uri.parse(article.link));
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(it);
    }

    private void load() {
        if (TextUtils.isEmpty(article.link)) {
            render(null, "没有可读的链接");
            return;
        }
        String rule = src == null || src.ruleContent == null ? "" : src.ruleContent.trim();
        if (rule.startsWith("@js:")) {
            loadByJs(rule.substring(4).trim());
            return;
        }
        titleView.setText(TextUtils.isEmpty(article.title) ? article.link : article.title);
        web.getSettings().setJavaScriptEnabled(false);
        new Thread(() -> {
            String page = null;
            String err = null;
            try {
                page = fetch(article.link);
            } catch (Exception e) {
                err = e.getMessage();
            }
            String body;
            if (page != null) {
                body = ContentExtractor.extract(page, article.link, src);
                if (!TextUtils.isEmpty(body)) FeedState.writeCache(this, article.link, body);
                fromCache = false;
            } else {
                body = FeedState.readCache(this, article.link);
                fromCache = body != null;
                if (body == null) body = "";
            }
            final String fbody = body;
            final String ferr = err;
            ui.post(() -> {
                if (TextUtils.isEmpty(fbody)) {
                    render(null, (ferr == null ? "正文为空" : "加载失败：" + ferr) + "\n（可点右上角↗ 直接看原网页）");
                    return;
                }
                String html = ContentExtractor.wrap(fbody, null, article.link,
                        VieYApp.isDarkMode(this), textZoom);
                web.loadDataWithBaseURL(article.link, html, "text/html", "UTF-8", null);
                if (fromCache) Toast.makeText(this, "离线缓存", Toast.LENGTH_SHORT).show();
            });
        }, "feed-detail").start();
    }

    /** @js: 规则：在页面上下文执行，取返回值（字符串 / 元素 / 元素集合） */
    private void loadByJs(String js) {
        titleView.setText(TextUtils.isEmpty(article.title) ? article.link : article.title);
        android.util.Log.i("viey-rss", "@js 开始 页面=" + article.link + " 规则长=" + js.length());
        final java.util.concurrent.atomic.AtomicBoolean evalStarted =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.atomic.AtomicBoolean gotResult =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        // @js: 规则需要在页面上下文执行，临时开启 JS；取到结果后立即关闭再渲染
        web.getSettings().setJavaScriptEnabled(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (!evalStarted.compareAndSet(false, true)) {
                    android.util.Log.i("viey-rss", "@js onPageFinished 重复，忽略 " + url);
                    return;
                }
                android.util.Log.i("viey-rss", "@js onPageFinished 开始 eval " + url);
                // eval 语义对齐 Legado：多语句脚本返回最后一个表达式的值
                String expr = "(function(){try{var r=eval(" + org.json.JSONObject.quote(js) + ");"
                        + "if(r==null)return '';"
                        + "if(typeof r==='string')return r;"
                        + "if(r.innerHTML!==undefined)return r.innerHTML;"
                        + "if(typeof r.length==='number'){var s='';for(var i=0;i<r.length;i++){var e=r[i];"
                        + "s+=((e&&e.innerHTML!==undefined)?e.innerHTML:String(e));}return s;}"
                        + "var t=(r.innerText!==undefined)?r.innerText:String(r);return t;"
                        + "}catch(e){return 'ERRJS:'+(e&&e.message?e.message:e);}})()";
                view.evaluateJavascript(expr, value -> {
                    gotResult.set(true);
                    view.getSettings().setJavaScriptEnabled(false);
                    String text;
                    try {
                        Object o = new org.json.JSONTokener(value).nextValue();
                        text = o == null ? "" : String.valueOf(o);
                    } catch (Exception e) {
                        text = value;
                    }
                    android.util.Log.i("viey-rss", "@js 结果 len=" + (text == null ? -1 : text.length())
                            + " head=" + (text == null ? "null"
                            : text.substring(0, Math.min(40, text.length()))));
                    if (value == null || "null".equals(value)) {
                        render(null, "JS 规则返回空（检查规则是否能取到元素）");
                        return;
                    }
                    if (text.startsWith("ERRJS:")) {
                        render(null, "JS 规则执行失败：" + text.substring(6));
                        return;
                    }
                    if (TextUtils.isEmpty(text)) {
                        render(null, "JS 规则没有取到内容");
                        return;
                    }
                    FeedState.writeCache(FeedReaderActivity.this, article.link, text);
                    String html = ContentExtractor.wrap(text, null, article.link,
                            VieYApp.isDarkMode(FeedReaderActivity.this), textZoom);
                    web.loadDataWithBaseURL(article.link, html, "text/html", "UTF-8", null);
                });
            }
        });
        web.loadUrl(article.link);
        // 兜底：15s 内没有结果就明确提示，不再留白屏
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (gotResult.get()) return;
            android.util.Log.w("viey-rss", "@js 超时未回调 evalStarted=" + evalStarted.get());
            String cached = FeedState.readCache(this, article.link);
            if (!TextUtils.isEmpty(cached)) {
                web.setWebViewClient(new WebViewClient());
                String html = ContentExtractor.wrap(cached, null, article.link,
                        VieYApp.isDarkMode(this), textZoom);
                web.loadDataWithBaseURL(article.link, html, "text/html", "UTF-8", null);
            } else {
                render(null, "JS 规则未在 15 秒内返回（页面可能加载失败或一直有跳转）\n"
                        + "可点右上角↗ 直接看原网页");
            }
        }, 15000);
    }

    private void render(String body, String msg) {
        if (web != null) {
            // 换回普通 client，避免 loadData 再次触发 @js 的 onPageFinished 复算
            web.setWebViewClient(new WebViewClient());
            web.getSettings().setJavaScriptEnabled(false);
        }
        String html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<style>body{padding:24px;font:15px/1.8 sans-serif;color:"
                + (VieYApp.isDarkMode(this) ? "#cccccc" : "#666666")
                + ";white-space:pre-wrap}</style></head><body>" + ContentExtractor.escape(msg) + "</body></html>";
        web.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    private String fetch(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setInstanceFollowRedirects(true);
        String ua = WebSettings.getDefaultUserAgent(this);
        conn.setRequestProperty("User-Agent", TextUtils.isEmpty(ua) ? "Mozilla/5.0 (Linux; Android) VieY" : ua);
        conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*");
        if (src != null) FeedParser.applyHeader(conn, src.header);
        int code = conn.getResponseCode();
        if (code >= 400) throw new Exception("HTTP " + code);
        try (InputStream in = conn.getInputStream()) {
            return new String(FeedRepo.readBytes(in), FeedRepo.charsetOf(conn));
        }
    }

    @Override
    public void onBackPressed() {
        // 阅读页是单篇视图（正文用 loadDataWithBaseURL 渲染，历史里没有可回退的网页），直接退出
        finish();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
        }
        super.onDestroy();
    }

    /** 供其他页面调用 */
    public static Intent intent(Context c, FeedParser.Article a, FeedSource s) {
        Intent it = new Intent(c, FeedReaderActivity.class);
        it.putExtra(EXTRA_LINK, a.link);
        it.putExtra(EXTRA_TITLE, a.title);
        it.putExtra(EXTRA_SOURCE_NAME, s == null ? "" : s.displayTitle());
        if (s != null) it.putExtra(EXTRA_SOURCE, s.toLegado().toString());
        return it;
    }
}
