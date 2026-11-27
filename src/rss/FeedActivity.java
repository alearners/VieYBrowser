package kawaii.viey.browser;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 订阅源管理：新增/删除/打开，Legado backup.zip 导入导出。
 */
public class FeedActivity extends BaseActivity {

    private static final int REQ_IMPORT = 4001;
    private static final int REQ_EXPORT = 4002;
    private static final int REQ_EDIT = 4003;
    private final java.util.Map<String, Integer> unreadMap = new java.util.HashMap<>();

    private final List<FeedSource> data = new ArrayList<>();
    private BaseAdapter adapter;
    private ListView list;

    @Override
    protected void onResume() {
        super.onResume();
        if (list != null) reload();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_feed);

        list = findViewById(R.id.feed_list);
        findViewById(R.id.back_tool).setOnClickListener(v -> finish());
        findViewById(R.id.feed_add).setOnClickListener(v ->
                startActivityForResult(FeedEditActivity.intent(this, new FeedSource(), true), REQ_EDIT));
        findViewById(R.id.feed_import).setOnClickListener(v -> pickImport());
        findViewById(R.id.feed_export).setOnClickListener(v -> pickExport());
        findViewById(R.id.feed_all).setOnClickListener(v -> {
            Intent it = new Intent(this, FeedItemsActivity.class);
            it.putExtra(FeedItemsActivity.EXTRA_ALL, true);
            startActivity(it);
        });
        findViewById(R.id.feed_favs).setOnClickListener(v -> {
            Intent it = new Intent(this, FeedItemsActivity.class);
            it.putExtra(FeedItemsActivity.EXTRA_FAVS, true);
            startActivity(it);
        });

        adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return data.size();
            }

            @Override
            public Object getItem(int position) {
                return data.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = convertView != null ? convertView : getLayoutInflater().inflate(R.layout.item_feed, parent, false);
                FeedSource s = data.get(position);
                ((TextView) v.findViewById(R.id.item_title)).setText(s.displayTitle());
                Integer u = unreadMap.get(FeedActivity.k(s));
                int unread = u == null ? 0 : u;
                String sub = s.displaySubtitle();
                if (unread > 0) sub = "未读 " + unread + " · " + sub;
                else {
                    long t = FeedRepo.cachedTime(FeedActivity.this, s);
                    if (t > 0) sub = "已读 · " + sub;
                }
                ((TextView) v.findViewById(R.id.item_sub)).setText(sub);
                return v;
            }
        };
        list.setAdapter(adapter);

        list.setOnItemClickListener((parent, view, position, id) -> {
            FeedSource s = data.get(position);
            Intent it = new Intent(this, FeedItemsActivity.class);
            it.putExtra(FeedItemsActivity.EXTRA_SOURCE_JSON, s.toLegado().toString());
            startActivity(it);
        });

        list.setOnItemLongClickListener((parent, view, position, id) -> {
            FeedSource s = data.get(position);
            new AlertDialog.Builder(this)
                    .setTitle(s.displayTitle())
                    .setItems(new String[]{"编辑规则", "立即刷新", "删除订阅源", "复制链接"}, (d, which) -> {
                        if (which == 0) {
                            startActivityForResult(FeedEditActivity.intent(this, s, false), REQ_EDIT);
                        } else if (which == 1) {
                            refreshOne(s);
                        } else if (which == 2) {
                            FeedStore.removeOne(this, s.url, s.displayTitle());
                            reload();
                        } else {
                            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("url", s.url));
                            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .show();
            return true;
        });

        reload();
    }

    private void refreshOne(FeedSource s) {
        Toast.makeText(this, "刷新 " + s.displayTitle() + "…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String msg;
            try {
                int n = FeedRepo.refresh(this, s);
                msg = s.displayTitle() + " 抓取 " + n + " 条";
            } catch (Exception e) {
                msg = "刷新失败：" + e.getMessage();
            }
            final String fmsg = msg;
            runOnUiThread(() -> {
                Toast.makeText(this, fmsg, Toast.LENGTH_SHORT).show();
                reload();
            });
        }, "feed-refresh-one").start();
    }

    private static String k(FeedSource s) {
        return s.url + "|" + s.displayTitle();
    }

    private void reload() {
        data.clear();
        data.addAll(FeedStore.load(this));
        unreadMap.clear();
        java.util.Set<String> read = FeedState.readKeys(this);
        for (FeedSource s : data) if (s.enabled) unreadMap.put(k(s), FeedRepo.unreadCount(this, s, read));
        adapter.notifyDataSetChanged();
        TextView t = findViewById(R.id.feed_title);
        int unread = FeedRepo.totalUnread(this);
        t.setText(unread > 0 ? "订阅 (" + data.size() + ") · 未读 " + unread : "订阅 (" + data.size() + ")");
    }

    
    private void pickImport() {
        Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType("*/*");
        try {
            startActivityForResult(it, REQ_IMPORT);
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    private void pickExport() {
        Intent it = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType("application/zip");
        it.putExtra(Intent.EXTRA_TITLE, "VieY-rss-backup.zip");
        try {
            startActivityForResult(it, REQ_EXPORT);
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && data == null && requestCode == REQ_EDIT) {
            reload();
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_IMPORT) {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                int added = FeedStore.importFrom(this, in);
                reload();
                Toast.makeText(this, "导入完成，新增 " + added + " 个订阅源", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "导入失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQ_EXPORT) {
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                int n = FeedStore.exportTo(this, out);
                Toast.makeText(this, "已导出 " + n + " 个订阅源（Legado 可直接恢复）", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "导出失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

}
