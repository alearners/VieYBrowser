package kawaii.viey.browser;

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
import java.util.List;

/**
 * 单个订阅源的文章列表；点击在浏览器页签中打开。
 */
public class FeedItemsActivity extends BaseActivity {

    public static final String EXTRA_SOURCE_JSON = "feed_source_json";

    private final List<FeedParser.Article> articles = new ArrayList<>();
    private BaseAdapter adapter;
    private TextView empty;
    private FeedSource src;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_feed_items);

        empty = findViewById(R.id.feed_empty);
        ListView list = findViewById(R.id.feed_list);

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

        ((TextView) findViewById(R.id.feed_title)).setText(src.displayTitle());
        findViewById(R.id.back_tool).setOnClickListener(v -> finish());
        findViewById(R.id.feed_refresh).setOnClickListener(v -> load());

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
                ((TextView) v.findViewById(R.id.item_title)).setText(TextUtils.isEmpty(a.title) ? a.link : a.title);
                ((TextView) v.findViewById(R.id.item_sub)).setText(a.subtitle());
                return v;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            FeedParser.Article a = articles.get(position);
            if (TextUtils.isEmpty(a.link)) return;
            Intent it = new Intent(this, MainActivity.class);
            it.setData(Uri.parse(a.link));
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(it);
        });

        load();
    }

    private void load() {
        empty.setVisibility(View.VISIBLE);
        empty.setText("正在加载…");
        new Thread(() -> {
            String err = null;
            List<FeedParser.Article> parsed = new ArrayList<>();
            try {
                String body = fetch(src.url);
                parsed = FeedParser.parse(body, src, src.url);
                if (parsed.isEmpty()) err = "未能解析出文章，请检查订阅地址或栏目规则";
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

    private String fetch(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setInstanceFollowRedirects(true);
        String ua = WebSettings.getDefaultUserAgent(this);
        conn.setRequestProperty("User-Agent", TextUtils.isEmpty(ua) ? "Mozilla/5.0 (Linux; Android) VieY" : ua);
        conn.setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml, text/html, */*");
        FeedParser.applyHeader(conn, src.header);
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
