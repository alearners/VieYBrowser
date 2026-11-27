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
        titleView.setText(TextUtils.isEmpty(article.title) ? article.link : article.title);
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

    private void render(String body, String msg) {
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
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
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
