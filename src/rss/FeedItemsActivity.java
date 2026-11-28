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
    private boolean compact = false;

    @Override
    protected void onResume() {
        super.onResume();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
            empty.setVisibility(articles.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_feed_items);

        empty = findViewById(R.id.feed_empty);
        ListView list = findViewById(R.id.feed_list);
        TextView title = findViewById(R.id.feed_title);
        findViewById(R.id.back_tool).setOnClickListener(v -> finish());
        findViewById(R.id.feed_refresh).setOnClickListener(v -> load());
        compact = "true".equals(getPrefs(this).getString("rss_list_compact", "false"));
        findViewById(R.id.feed_more).setOnClickListener(v -> showRssMenu());

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
                sub.setVisibility(compact ? View.GONE : View.VISIBLE);
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

        // 先上缓存，秒开；再后台刷新
        List<FeedParser.Article> cached = allMode ? FeedRepo.cachedAll(this) : FeedRepo.loadItems(this, src);
        articles.clear();
        articles.addAll(cached);
        adapter.notifyDataSetChanged();
        if (cached.isEmpty()) {
            empty.setVisibility(View.VISIBLE);
            empty.setText("正在加载…");
        } else {
            empty.setVisibility(View.GONE);
        }

        // 缓存 3 分钟内不重复联网（手动下拉/长按「立即刷新」不受限）
        long stale = System.currentTimeMillis() - (allMode ? 0 : FeedRepo.cachedTime(this, src));
        if (!allMode && stale < 3 * 60 * 1000L && !cached.isEmpty()) return;

        new Thread(() -> {
            String err = null;
            List<FeedParser.Article> fresh = new ArrayList<>();
            try {
                if (allMode) {
                    FeedRepo.refreshAll(this);
                    fresh = FeedRepo.cachedAll(this);
                } else {
                    FeedRepo.refresh(this, src);
                    fresh = FeedRepo.loadItems(this, src);
                }
                if (fresh.isEmpty()) err = allMode ? "没有启用的订阅源，或全部抓取失败"
                        : "未能解析出文章，请到「订阅源管理」检查规则";
            } catch (Exception e) {
                err = "刷新失败：" + e.getMessage();
            }
            final String ferr = err;
            final List<FeedParser.Article> ffresh = fresh;
            ui.post(() -> {
                if (ffresh.isEmpty()) {
                    if (articles.isEmpty()) {
                        empty.setVisibility(View.VISIBLE);
                        empty.setText(ferr == null ? "没有内容" : ferr + "\n（长按条目可编辑规则）");
                    } else {
                        Toast.makeText(this, ferr == null ? "刷新无新内容" : ferr, Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
                articles.clear();
                articles.addAll(ffresh);
                adapter.notifyDataSetChanged();
                empty.setVisibility(View.GONE);
                if (!TextUtils.isEmpty(ferr)) Toast.makeText(this, ferr, Toast.LENGTH_SHORT).show();
            });
        }).start();
    }


    // ===== RSS 浏览菜单（可自定义，参照阅读的三点菜单）=====
    private static final String P_MENU_OFF = "rss_menu_off";

    private String[] menuIds() {
        return new String[]{"resort", "vars", "edit", "layout", "clear"};
    }

    private String[] menuLabels() {
        return new String[]{"重新整理分类", "设定源变量", "编辑源", "切换布局（紧凑/常规）", "清除本列表缓存"};
    }

    private boolean isMenuOn(String id) {
        String off = getPrefs(this).getString(P_MENU_OFF, "");
        return !("," + off + ",").contains("," + id + ",");
    }

    private void showRssMenu() {
        final String[] ids = menuIds();
        final String[] labels = menuLabels();
        java.util.List<String> shown = new java.util.ArrayList<>();
        final java.util.List<String> keys = new java.util.ArrayList<>();
        for (int k = 0; k < ids.length; k++) {
            if (isMenuOn(ids[k])) {
                shown.add(labels[k]);
                keys.add(ids[k]);
            }
        }
        shown.add("菜单项设置");
        new AlertDialog.Builder(this)
            .setTitle(allMode ? "全部订阅" : (src == null ? "收藏" : src.displayTitle()))
            .setItems(shown.toArray(new String[0]), (d, w) -> {
                if (w == shown.size() - 1) {
                    configRssMenu();
                    return;
                }
                String id = keys.get(w);
                if ("resort".equals(id)) resort();
                else if ("vars".equals(id)) editSourceVars();
                else if ("edit".equals(id)) editSource();
                else if ("layout".equals(id)) toggleCompact();
                else if ("clear".equals(id)) clearList();
            })
            .show();
    }

    private void configRssMenu() {
        final String[] ids = menuIds();
        final String[] labels = menuLabels();
        boolean[] checked = new boolean[ids.length];
        for (int k = 0; k < ids.length; k++) checked[k] = isMenuOn(ids[k]);
        new AlertDialog.Builder(this)
            .setTitle("菜单项设置（勾选显示）")
            .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
            .setPositiveButton("保存", (d, w) -> {
                StringBuilder off = new StringBuilder();
                for (int k = 0; k < ids.length; k++) {
                    if (!checked[k]) {
                        if (off.length() > 0) off.append(',');
                        off.append(ids[k]);
                    }
                }
                getPrefs(this).edit().putString(P_MENU_OFF, off.toString()).apply();
                Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void resort() {
        java.util.Collections.sort(articles, (a, b) -> {
            String x = a.pubDate == null ? "" : a.pubDate;
            String y = b.pubDate == null ? "" : b.pubDate;
            int c = y.compareTo(x);
            if (c != 0) return c;
            String n1 = a.source == null ? "" : a.source;
            String n2 = b.source == null ? "" : b.source;
            return n1.compareTo(n2);
        });
        adapter.notifyDataSetChanged();
        Toast.makeText(this, "已按时间重新整理", Toast.LENGTH_SHORT).show();
    }

    private void editSourceVars() {
        if (src == null) {
            Toast.makeText(this, "全部订阅/收藏没有单独的源变量", Toast.LENGTH_SHORT).show();
            return;
        }
        android.widget.EditText et = new android.widget.EditText(this);
        et.setText(src.header == null ? "" : src.header);
        et.setTextSize(12);
        et.setSingleLine(false);
        et.setMinLines(4);
        et.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        et.setHint("请求头 JSON，例如 {\"Cookie\":\"a=b\",\"User-Agent\":\"Mozilla/5.0\"}");
        new AlertDialog.Builder(this)
            .setTitle("设定源变量（请求头）")
            .setView(et)
            .setPositiveButton("保存", (d, w) -> {
                src.header = et.getText().toString().trim();
                FeedStore.replace(this, src.url, src.name, src);
                Toast.makeText(this, "已保存源变量", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void editSource() {
        if (src == null) {
            Toast.makeText(this, "全部订阅/收藏没有单独的源", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent it = new Intent(this, FeedEditActivity.class);
        it.putExtra(FeedEditActivity.EXTRA_SOURCE_JSON, src.toLegado().toString());
        it.putExtra(FeedEditActivity.EXTRA_ORIG_URL, src.url);
        it.putExtra(FeedEditActivity.EXTRA_ORIG_NAME, src.name);
        startActivity(it);
    }

    private void toggleCompact() {
        compact = !compact;
        getPrefs(this).edit().putString("rss_list_compact", compact ? "true" : "false").apply();
        adapter.notifyDataSetChanged();
        Toast.makeText(this, compact ? "紧凑布局" : "常规布局", Toast.LENGTH_SHORT).show();
    }

    private void clearList() {
        FeedState.clearCache(this);
        articles.clear();
        adapter.notifyDataSetChanged();
        empty.setVisibility(View.VISIBLE);
        empty.setText("已清除缓存，正在重新加载…");
        load();
    }
}
