package kawaii.viey.browser;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 多源 / 多进程下载界面。输入接受：
 *  - 一条或多条 HTTP(S) 直链（每行一个，即多源镜像）
 *  - ed2k:// 链接：只当元数据用（取文件名/大小），实际字节仍走 HTTP 多源
 */
public class DlActivity extends Activity {

    private EditText input, fileName;
    private TextView status;
    private ProgressBar bar;
    private boolean polling;
    private final Handler ui = new Handler(Looper.getMainLooper());

    static final Pattern ED2K = Pattern.compile("ed2k://\\|file\\|([^|]+)\\|(\\d+)\\|([0-9a-fA-F]{32})");
    static final Pattern HTTP = Pattern.compile("https?://\\S+");

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_link);

        ImageView back = findViewById(R.id.link_back_btn);
        back.setOnClickListener(v -> finish());

        input = findViewById(R.id.link_input);
        fileName = findViewById(R.id.link_name);
        status = findViewById(R.id.link_status);
        bar = findViewById(R.id.link_progress);

        Intent2Extras(getIntent());

        Button parse = findViewById(R.id.link_parse);
        parse.setOnLongClickListener(v -> {
            // 测试用示例源（公开测速文件，支持 Range），长按“解析”填入，避免中文输入法把 URL 转成候选词
            input.setText("https://proof.ovh.net/files/10Mb.dat\nhttps://speed.cloudflare.com/__down?bytes=10485760\nhttps://ash-speed.hetzner.com/10MB.bin");
            fileName.setText("10MB.dat");
            status.setText("已填入示例源（3 个镜像，10MB）。点“开始”跑多连接分段下载。");
            return true;
        });
        parse.setOnClickListener(v -> {
            List<String> urls = urls();
            String[] meta = meta(urls);
            fileName.setText(meta[0]);
            status.setText("识别：文件名 " + meta[0] + " · 大小 " + human(Long.parseLong(meta[1])) + " · 源 " + urls.size() + " 个");
            append("任务跑在独立进程 :dl，主界面退出不中断；进度每秒刷新。");
        });

        Button self = findViewById(R.id.link_selftest);
        self.setOnClickListener(v -> {
            status.setText("uTP 自检中…");
            new Thread(() -> {
                String r = UtpSelfTest.run(DlActivity.this);
                ui.post(() -> {
                    status.setText(r);
                    bar.setProgress(0);
                });
            }, "utp-selftest").start();
        });

        Button start = findViewById(R.id.link_start);
        start.setOnClickListener(v -> {
            List<String> urls = urls();
            if (urls.isEmpty()) {
                status.setText("先填至少一个 HTTP(S) 直链");
                return;
            }
            String[] meta = meta(urls);
            String name = fileName.getText().toString().trim();
            if (name.isEmpty()) name = meta[0];
            fileName.setText(name);
            long size = Long.parseLong(meta[1]);
            DlService.startTask(this, name, size, urls, null);
            append("已提交：" + name + " · " + urls.size() + " 源 · 连接上限 "
                    + VieYApp.getPrefs(this).getInt("dl_conns", 8));
            startPolling();
        });

        Button pause = findViewById(R.id.link_pause);
        pause.setOnClickListener(v -> {
            DlService.pauseTask(this);
            append("已请求暂停（分段偏移已保存，可续传）");
        });

        Button open = findViewById(R.id.link_open);
        open.setOnClickListener(v -> {
            status.setText("下载目录：" + dir());
        });
    }

    private void Intent2Extras(android.content.Intent it) {
        if (it != null && it.hasExtra("url")) {
            input.setText(it.getStringExtra("url"));
        }
    }

    private File dir() {
        File d = getExternalFilesDir("download");
        if (d == null) d = new File(getFilesDir(), "download");
        return d;
    }

    private List<String> urls() {
        List<String> out = new ArrayList<>();
        String text = input.getText().toString();
        Matcher m = HTTP.matcher(text);
        while (m.find()) out.add(m.group());
        return out;
    }

    /** 返回 [文件名, 大小]；ed2k 链接可解析出两者，HTTP 直链从路径猜文件名 */
    private String[] meta(List<String> urls) {
        String text = input.getText().toString();
        Matcher e = ED2K.matcher(text);
        if (e.find()) return new String[]{e.group(1), e.group(2)};
        String name = "";
        long size = 0;
        if (!urls.isEmpty()) {
            String u = urls.get(0);
            int q = u.indexOf('?');
            if (q > 0) u = u.substring(0, q);
            int s = u.lastIndexOf('/');
            name = s >= 0 ? u.substring(s + 1) : u;
            if (name.isEmpty()) name = "viey-download.bin";
        }
        return new String[]{name, String.valueOf(size)};
    }

    private void startPolling() {
        if (polling) return;
        polling = true;
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                JSONObject o = DlStore.read(DlActivity.this);
                if (o != null) {
                    long done = o.optLong("done");
                    long total = o.optLong("total");
                    if (total > 0) bar.setProgress((int) (done * 100 / total));
                    status.setText("[" + o.optString("state") + "] " + o.optString("name")
                            + "\n" + human(done) + " / " + (total > 0 ? human(total) : "?")
                            + "\n" + o.optString("note")
                            + (o.optString("msg").isEmpty() ? "" : "\n" + o.optString("msg")));
                    if ("done".equals(o.optString("state")) || "failed".equals(o.optString("state"))
                            || "paused".equals(o.optString("state"))) {
                        polling = false;
                        return;
                    }
                }
                if (polling) ui.postDelayed(this, 1000);
            }
        }, 500);
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
        return String.format(java.util.Locale.US, "%.1f %s", d, u);
    }
}
