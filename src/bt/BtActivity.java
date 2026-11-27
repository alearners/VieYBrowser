package kawaii.viey.browser;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * BT 下载界面：输入 magnet 或 .torrent（本地路径 / http(s) 地址），
 * 走 tracker → peer wire → 逐片 SHA-1 校验 → 落盘。
 */
public class BtActivity extends Activity {

    private static final int REQ_PICK = 1001;

    private EditText input;
    private TextView status;
    private ProgressBar bar;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private BtDown task;
    private long t0;
    private long t0bytes;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_bt);

        ImageView back = findViewById(R.id.bt_back_btn);
        back.setOnClickListener(v -> finish());
        input = findViewById(R.id.bt_input);
        status = findViewById(R.id.bt_status);
        bar = findViewById(R.id.bt_progress);

        boolean autoStart = false;
        if (getIntent() != null && getIntent().hasExtra("url")) {
            input.setText(getIntent().getStringExtra("url"));
        } else if (getIntent() != null && getIntent().getData() != null) {
            String d = getIntent().getData().toString();
            input.setText(d);
            autoStart = d.startsWith("magnet:");
            if (!autoStart) {
                // content:// / file:// 的 .torrent：复制到 cache 再解析
                try {
                    String p = copyToCache(getIntent().getData());
                    input.setText(p);
                    autoStart = true;
                } catch (Exception e) {
                    status.setText("读取 .torrent 失败：" + e.getMessage());
                }
            }
        }

        findViewById(R.id.bt_pick).setOnClickListener(v -> pick());
        findViewById(R.id.bt_selftest).setOnClickListener(v -> {
            status.setText("BT 自检中…");
            new Thread(() -> {
                String r = BtSelfTest.run();
                ui.post(() -> {
                    status.setText(r);
                    bar.setProgress(0);
                });
            }, "bttest").start();
        });
        findViewById(R.id.bt_start).setOnClickListener(v -> startTask());
        if (autoStart) ui.postDelayed(this::startTask, 300);
        findViewById(R.id.bt_pause).setOnClickListener(v -> {
            if (task != null) {
                task.pause();
                append("已请求暂停（piece 进度已保存，可续传）");
            }
        });
        findViewById(R.id.bt_open).setOnClickListener(v -> status.setText("下载目录：" + dir().getAbsolutePath()));
    }

    private void pick() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            status.setText("无法打开文件选择器：" + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK && res == RESULT_OK && data != null && data.getData() != null) {
            String path = copyToCache(data.getData());
            if (path != null) {
                input.setText(path);
                status.setText("已载入 .torrent：" + path);
            }
        }
    }

    private String copyToCache(Uri uri) {
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            File f = new File(getCacheDir(), "pick-" + System.currentTimeMillis() + ".torrent");
            FileOutputStream out = new FileOutputStream(f);
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            out.close();
            in.close();
            return f.getAbsolutePath();
        } catch (Exception e) {
            status.setText("读取文件失败：" + e.getMessage());
            return null;
        }
    }

    private void startTask() {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) {
            status.setText("先填 magnet 链接或 .torrent 路径");
            return;
        }
        String first = text.split("\n")[0].trim();
        if (first.startsWith("http://") || first.startsWith("https://")) {
            status.setText("下载 .torrent 中…");
            new Thread(() -> {
                try {
                    File f = fetch(text);
                    ui.post(() -> {
                        input.setText(f.getAbsolutePath());
                        begin(f.getAbsolutePath());
                    });
                } catch (Exception e) {
                    ui.post(() -> status.setText("取 .torrent 失败：" + e.getMessage()));
                }
            }, "torrent-fetch").start();
            return;
        }
        begin(first);
    }

    private void begin(String src) {
        try {
            task = new BtDown(this, src, VieYApp.getPrefs(this).getInt("bt_peers", 4));
        } catch (Exception e) {
            status.setText("解析失败：" + e.getMessage());
            return;
        }
        t0 = System.currentTimeMillis();
        t0bytes = 0;
        bar.setProgress(0);
        status.setText("开始：" + src);
        task.start(new BtDown.Cb() {
            @Override
            public void onProgress(long done, long total, String note) {
                ui.post(() -> {
                    if (total > 0) bar.setProgress((int) (done * 100 / total));
                    long dt = Math.max(1, System.currentTimeMillis() - t0);
                    long sp = done / (dt / 1000 + 1);
                    status.setText(human(done) + " / " + (total > 0 ? human(total) : "?")
                            + "  ·  ~" + human(sp) + "/s\n" + note);
                });
            }

            @Override
            public void onDone(boolean ok, String msg, File out) {
                ui.post(() -> {
                    status.setText((ok ? "[done] " : "[stop] ") + msg);
                    if (ok) bar.setProgress(100);
                    task = null;
                });
            }
        });
    }

    private File fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        int code = c.getResponseCode();
        if (code != 200) throw new Exception("HTTP " + code);
        File f = new File(getCacheDir(), "remote-" + System.currentTimeMillis() + ".torrent");
        InputStream in = c.getInputStream();
        FileOutputStream out = new FileOutputStream(f);
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        out.close();
        in.close();
        c.disconnect();
        return f;
    }

    private File dir() {
        File d = getExternalFilesDir("download");
        if (d == null) d = new File(getFilesDir(), "download");
        return d;
    }

    private void append(String s) {
        status.setText(status.getText() + "\n" + s);
    }

    private static String human(long v) {
        double d = v;
        String u = "B";
        if (d >= 1024) {
            d /= 1024;
            u = "KB";
        }
        if (d >= 1024) {
            d /= 1024;
            u = "MB";
        }
        if (d >= 1024) {
            d /= 1024;
            u = "GB";
        }
        return String.format(Locale.US, "%.1f %s", d, u);
    }
}
