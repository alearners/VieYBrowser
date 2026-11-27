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
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * 插件（用户脚本）管理：从 URL 导入 / 粘贴导入 / 启停 / 删除 / 打开脚本市场。
 */
public class PluginActivity extends BaseActivity {

    private final List<PluginStore.Plugin> data = new ArrayList<>();
    private EditText input;
    private TextView empty;
    private ListView list;
    private BaseAdapter adapter;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_plugin);

        input = findViewById(R.id.plugin_input);
        empty = findViewById(R.id.plugin_empty);
        list = findViewById(R.id.plugin_list);
        findViewById(R.id.back_tool).setOnClickListener(v -> finish());
        findViewById(R.id.plugin_market).setOnClickListener(v -> {
            Intent it = new Intent(this, MainActivity.class);
            it.setData(Uri.parse(PluginStore.marketUrl(this)));
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(it);
        });
        findViewById(R.id.plugin_add).setOnClickListener(v -> doAdd());

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
                PluginStore.Plugin p = data.get(position);
                ((TextView) v.findViewById(R.id.item_title)).setText(p.name);
                ((TextView) v.findViewById(R.id.item_sub)).setText(p.subtitle());
                return v;
            }
        };
        list.setAdapter(adapter);

        list.setOnItemClickListener((parent, view, position, id) -> {
            PluginStore.Plugin p = data.get(position);
            PluginStore.setEnabled(p, !p.enabled);
            reload();
            Toast.makeText(this, (p.enabled ? "已启用 " : "已停用 ") + p.name, Toast.LENGTH_SHORT).show();
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            PluginStore.Plugin p = data.get(position);
            new AlertDialog.Builder(this).setTitle(p.name)
                    .setItems(new String[]{"查看脚本内容", "删除插件"}, (d, which) -> {
                        if (which == 0) {
                            new AlertDialog.Builder(this).setTitle(p.name)
                                    .setMessage(p.code.length() > 4000 ? p.code.substring(0, 4000) + "…" : p.code)
                                    .setPositiveButton("关闭", null).show();
                        } else {
                            PluginStore.remove(p);
                            reload();
                        }
                    }).show();
            return true;
        });

        reload();
    }

    private void reload() {
        data.clear();
        data.addAll(PluginStore.load(this));
        adapter.notifyDataSetChanged();
        empty.setVisibility(data.isEmpty() ? View.VISIBLE : View.GONE);
        ((TextView) findViewById(R.id.plugin_title)).setText("用户脚本插件 (" + data.size() + ")");
    }

    private void doAdd() {
        String text = input.getText().toString().trim();
        if (TextUtils.isEmpty(text)) {
            Toast.makeText(this, "请先粘贴脚本内容或脚本地址", Toast.LENGTH_SHORT).show();
            return;
        }
        if (text.startsWith("http://") || text.startsWith("https://")) {
            importFromUrl(text);
            return;
        }
        saveCode(text);
    }

    private void importFromUrl(final String url) {
        Toast.makeText(this, "正在下载脚本…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String code = null, err = null;
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(20000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "VieY-PluginImporter");
                try (InputStream in = conn.getInputStream()) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    code = bo.toString("UTF-8");
                }
            } catch (Exception e) {
                err = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            final String fc = code, fe = err;
            ui.post(() -> {
                if (fc == null) {
                    Toast.makeText(this, "下载失败：" + fe, Toast.LENGTH_LONG).show();
                    return;
                }
                saveCode(fc);
            });
        }).start();
    }

    private void saveCode(String code) {
        try {
            java.io.File f = PluginStore.save(this, code, null);
            input.setText("");
            reload();
            Toast.makeText(this, "已导入 " + f.getName(), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
