package kawaii.viey.browser;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 文章列表：单订阅源 / 全部订阅（聚合）/ 收藏。
 * 点击进应用内阅读页；长按给「原网页 / 收藏 / 标记未读 / 复制链接」。
 */
public class FeedItemsActivity extends BaseActivity {

    public static final String EXTRA_SOURCE_JSON = "feed_source_json";
    public static final String EXTRA_ALL = "feed_all";
    public static final String EXTRA_FAVS = "feed_favs";

    private final List<FeedParser.Article> articles = new ArrayList<>();
    private BaseAdapter adapter;
    private TextView empty;
    private FeedSource src;
    private boolean allMode, favMode;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_feed_items);

        empty = findViewById(R.id.feed_empty);
        ListView list = findViewById(R.id.feed_list);
        TextView title = findViewById(R.id.feed_title);
        findViewById(R.id.back_tool).setOnClickListener(v -> finish());
        findViewById(R.id.feed_refresh).setOnClickListener(v -> load());

        allMode = getIntent().getBooleanExtra(EXTRA_ALL, false);
        favMode = getIntent().getBooleanExtra(EXTRA_FAVS, false);

        if (!allMode && !favMode) {
            try {
                String json = getIntent().getStringExtra(EXTRA_SOURCE_JSON);
                if (json != null) src = FeedSource.fromLegado(new JSONObject(json));
            } catch (Exception ignored) {
            }
            if (src == null) {
                Toast.makeText(this, "订阅源数据缺失", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            title.setText(src.displayTitle());
        } else {
            title.setText(allMode ? "全部订阅" : "收藏");
        }

        adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return articles.size();
            }

            @Override
            public Object getItem(int position) {
                return articles.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = convertView != null ? convertView : getLayoutInflater().inflate(R.layout.item_feed, parent, false);
                FeedParser.Article a = articles.get(position);
                TextView t = v.findViewById(R.id.item_title);
                TextView sub = v.findViewById(R.id.item_sub);
                t.setText(TextUtils.isEmpty(a.title) ? a.link : a.title);
                t.setAlpha(FeedState.isRead(FeedItemsActivity.this, a.link) ? 0.55f : 1f);
                sub.setText(a.subtitle());
                return v;
            }
        };
        list.setAdapter(adapter);

        list.setOnItemClickListener((parent, view, position, id) -> {
            FeedParser.Article a = articles.get(position);
            if (TextUtils.isEmpty(a.link)) return;
            FeedSource s = src;
            if (s == null && !TextUtils.isEmpty(a.source)) {
                for (FeedSource x : FeedStore.load(this)) {
                    if (x.displayTitle().equals(a.source)) {
                        s = x;
                        break;
                    }
                }
            }
            startActivity(FeedReaderActivity.intent(this, a, s));
        });

        list.setOnItemLongClickListener((parent, view, position, id) -> {
            showItemMenu(articles.get(position));
            return true;
        });

        load();
    }

    private void showItemMenu(FeedParser.Article a) {
        String[] items = {"在浏览器打开", "收藏 / 取消收藏", "标记为未读", "复制链接"};
        new AlertDialog.Builder(this)
                .setTitle(TextUtils.isEmpty(a.title) ? a.link : a.title)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: {
                            Intent it = new Intent(this, MainActivity.class);
                            it.setData(Uri.parse(a.link));
                            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            startActivity(it);
                            break;
                        }
                        case 1: {
                            boolean now = FeedState.toggleFav(this, a, TextUtils.isEmpty(a.source)
                                    ? (src == null ? "" : src.displayTitle()) : a.source);
                            Toast.makeText(this, now ? "已收藏" : "已取消收藏", Toast.LENGTH_SHORT).show();
                            break;
                        }
                        case 2:
                            FeedState.markUnread(this, a.link);
                            adapter.notifyDataSetChanged();
                            Toast.makeText(this, "已标记未读", Toast.LENGTH_SHORT).show();
                            break;
                        case 3:
                            android.content.ClipboardManager cm =
                                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("link", a.link));
                            Toast.makeText(this, "链接已复制", Toast.LENGTH_SHORT).show();
                            break;
                    }
                })
                .show();
    }

    private void load() {
        if (favMode) {
            articles.clear();
            articles.addAll(FeedState.favArticles(this));
            adapter.notifyDataSetChanged();
            empty.setVisibility(articles.isEmpty() ? View.VISIBLE : View.GONE);
            empty.setText("还没有收藏，长按文章条目可收藏");
            return;
        }

        empty.setVisibility(View.VISIBLE);
        empty.setText("正在加载…");
        new Thread(() -> {
            String err = null;
            List<FeedParser.Article> parsed = new ArrayList<>();
            try {
                if (allMode) {
                    parsed = loadAll();
                    if (parsed.isEmpty()) err = "没有启用的订阅源，或全部抓取失败";
                } else {
                    String body = fetch(src.url, src);
                    parsed = FeedParser.parse(body, src, src.url);
                    for (FeedParser.Article a : parsed) a.source = src.displayTitle();
                    if (parsed.isEmpty()) err = "未能解析出文章，请检查订阅地址或栏目规则";
                }
            } catch (Exception e) {
                err = "加载失败：" + e.getMessage();
            }
            final String ferr = err;
            final List<FeedParser.Article> fres = parsed;
            ui.post(() -> {
                articles.clear();
                articles.addAll(fres);
                adapter.notifyDataSetChanged();
                if (fres.isEmpty()) {
                    empty.setVisibility(View.VISIBLE);
                    empty.setText(ferr == null ? "没有内容" : ferr);
                } else {
                    empty.setVisibility(View.GONE);
                }
            });
        }).start();
    }

    /** 并发抓取所有启用的源，按 pubDate 倒序合并（归一化过的日期直接字符串比较） */
    private List<FeedParser.Article> loadAll() {
        List<FeedSource> sources = new ArrayList<>();
        for (FeedSource s : FeedStore.load(this)) if (s.enabled) sources.add(s);
        List<FeedParser.Article> out = new ArrayList<>();
        if (sources.isEmpty()) return out;

        int threads = Math.min(4, sources.size());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<List<FeedParser.Article>>> futures = new ArrayList<>();
        for (FeedSource s : sources) {
            futures.add(pool.submit((Callable<List<FeedParser.Article>>) () -> {
                try {
                    String body = fetch(s.url, s);
                    List<FeedParser.Article> list = FeedParser.parse(body, s, s.url);
                    for (FeedParser.Article a : list) a.source = s.displayTitle();
                    return list;
                } catch (Exception e) {
                    android.util.Log.w("viey-rss", s.displayTitle() + " 抓取失败：" + e.getMessage());
                    return new ArrayList<FeedParser.Article>();
                }
            }));
        }
        for (Future<List<FeedParser.Article>> f : futures) {
            try {
                out.addAll(f.get());
            } catch (Exception ignored) {
            }
        }
        pool.shutdownNow();
        Collections.sort(out, (a, b) -> {
            String x = a.pubDate == null ? "" : a.pubDate;
            String y = b.pubDate == null ? "" : b.pubDate;
            return y.compareTo(x);
        });
        return out;
    }

    private String fetch(String url, FeedSource s) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setInstanceFollowRedirects(true);
        String ua = WebSettings.getDefaultUserAgent(this);
        conn.setRequestProperty("User-Agent", TextUtils.isEmpty(ua) ? "Mozilla/5.0 (Linux; Android) VieY" : ua);
        conn.setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml, text/html, */*");
        if (s != null) FeedParser.applyHeader(conn, s.header);
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
}
