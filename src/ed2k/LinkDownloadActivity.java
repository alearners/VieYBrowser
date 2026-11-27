package kawaii.viey.browser;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * ed2k 链接 / HTTP 多源下载面板：
 * - 解析 ed2k://|file|... 链接，显示名称/大小/hash/内嵌源
 * - 通过 eD2k 服务器取源（Ed2kCore v0.1）
 * - HTTP 镜像多源分段下载 + 断点续传 + ed2k hash 校验
 */
public class LinkDownloadActivity extends BaseActivity {

    public static final String EXTRA_LINK = "ed2k_link";

    private EditText input, server, mirrors;
    private TextView status;
    private ProgressBar progress;
    private Ed2kLink link;
    private MultiSource current;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_link);

        input = findViewById(R.id.link_input);
        server = findViewById(R.id.link_server);
        mirrors = findViewById(R.id.link_mirrors);
        status = findViewById(R.id.link_status);
        progress = findViewById(R.id.link_progress);
        findViewById(R.id.back_tool).setOnClickListener(v -> finish());

        String extra = getIntent().getStringExtra(EXTRA_LINK);
        if (!TextUtils.isEmpty(extra)) input.setText(extra);

        findViewById(R.id.link_p2p).setOnClickListener(v -> doP2p());
        findViewById(R.id.link_p2p_stop).setOnClickListener(v -> {
            Ed2kService.stop(this);
            append("已停止 P2P 下载服务");
        });
        findViewById(R.id.link_parse).setOnClickListener(v -> doParse());
        findViewById(R.id.link_sources).setOnClickListener(v -> doSources());
        findViewById(R.id.link_start).setOnClickListener(v -> doStart());
    }

    private void append(String s) {
        status.append("\n" + s);
    }

    private void doParse() {
        String raw = input.getText().toString().trim();
        if (Ed2kLink.looksLike(raw)) {
            link = Ed2kLink.parse(raw);
            if (link == null) {
                append("链接格式无法识别");
                return;
            }
            status.setText(link.describe());
            if (!link.sources.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (String s : link.sources) sb.append(s).append('\n');
                mirrors.setText(sb.toString());
                append("已把 " + link.sources.size() + " 个内嵌源填入镜像框（这些是 eD2k 节点，需要 ed2k 内核才能直连）");
            }
            return;
        }
        // 不是 ed2k，按 http 链接处理
        if (raw.startsWith("http")) {
            link = new Ed2kLink();
            link.name = Uri.parse(raw).getLastPathSegment();
            link.size = 0;
            mirrors.setText(raw + "\n" + mirrors.getText().toString());
            append("按 HTTP 直链处理：" + link.name);
        } else {
            append("请输入 ed2k:// 或 http(s):// 链接");
        }
    }

    private void doSources() {
        if (link == null || TextUtils.isEmpty(link.hash)) {
            Toast.makeText(this, "请先解析 ed2k 链接", Toast.LENGTH_SHORT).show();
            return;
        }
        String hostPort = server.getText().toString().trim();
        if (!hostPort.contains(":")) {
            Toast.makeText(this, "服务器格式 host:port", Toast.LENGTH_SHORT).show();
            return;
        }
        String h = hostPort.substring(0, hostPort.lastIndexOf(':'));
        int p;
        try {
            p = Integer.parseInt(hostPort.substring(hostPort.lastIndexOf(':') + 1));
        } catch (Exception e) {
            Toast.makeText(this, "端口无效", Toast.LENGTH_SHORT).show();
            return;
        }
        final List<String> out = new ArrayList<>();
        append("正在连接 " + hostPort + " 取源…");
        new Thread(() -> {
            Ed2kCore.Result r = Ed2kCore.querySources(h, p, link.hash, link.size, 4662, out, 15000);
            uiPost(() -> {
                append(r.message);
                if (!out.isEmpty()) {
                    StringBuilder sb = new StringBuilder(mirrors.getText().toString());
                    for (String s : out) {
                        sb.append('\n').append("ed2k://").append(s);
                    }
                    mirrors.setText(sb.toString().trim());
                    append("取到 " + out.size() + " 个源（P2P 直连未实现，需 HTTP 镜像或后续 ed2k 内核）");
                }
            });
        }).start();
    }

    private void doP2p() {
        if (link == null) doParse();
        if (link == null || TextUtils.isEmpty(link.hash) || link.size <= 0) {
            Toast.makeText(this, "需要完整 ed2k 链接（名称 + 大小 + hash）", Toast.LENGTH_SHORT).show();
            return;
        }
        String hostPort = server.getText().toString().trim();
        if (hostPort.contains(":")) {
            link.serverHost = hostPort.substring(0, hostPort.lastIndexOf(':'));
            try {
                link.serverPort = Integer.parseInt(hostPort.substring(hostPort.lastIndexOf(':') + 1));
            } catch (Exception ignored) {
            }
        }
        for (String line : mirrors.getText().toString().split("\n")) {
            String hp = line.trim();
            if (hp.length() > 0 && !hp.startsWith("http")) {
                hp = hp.replace("ed2k://", "");
                if (hp.contains(":")) link.sources.add(hp);
            }
        }
        Ed2kService.start(this, link);
        append("P2P 已启动：源 " + link.sources.size() + " + 服务器 " + link.serverHost);
        pollP2p();
    }

    private final Handler poller = new Handler(Looper.getMainLooper());

    private void pollP2p() {
        Ed2kDownload d = Ed2kService.current();
        if (d == null) return;
        status.setText("P2P：" + Ed2kService.lastState() + "\n源 " + d.sourceCount() + " · 对端 " + d.peerCount()
                + " · " + (d.speedBps() / 1024) + " KB/s · " + d.doneBytes() + " 字节");
        poller.postDelayed(this::pollP2p, 1200);
    }

    private void doStart() {
        if (link == null) doParse();
        if (link == null) return;
        List<String> ms = new ArrayList<>();
        for (String line : mirrors.getText().toString().split("\n")) {
            String s = line.trim();
            if (s.startsWith("http://") || s.startsWith("https://")) ms.add(s);
        }
        if (ms.isEmpty()) {
            Toast.makeText(this, "没有 HTTP/HTTPS 镜像可直接下载", Toast.LENGTH_SHORT).show();
            append("ed2k 源直连（P2P 传输）在 Ed2kCore v0.1 里只有服务器取源，字节搬运需要 HTTP 镜像。");
            return;
        }
        if (current != null) current.cancel();
        current = new MultiSource(this);
        progress.setProgress(0);
        append("开始多源下载：" + ms.size() + " 个镜像");
        current.start(link.name, link.size, ms, link.hash, new MultiSource.Callback() {
            @Override
            public void onProgress(long done, long total, String note) {
                if (total > 0) progress.setProgress((int) (done * 100 / total));
                status.setText("进度 " + done + "/" + total + " 字节 · " + note);
            }

            @Override
            public void onDone(boolean ok, String msg, File file) {
                progress.setProgress(ok ? 100 : 0);
                append(msg);
                if (ok) toast("下载完成：" + file.getName());
            }
        });
    }

    private void uiPost(Runnable r) {
        runOnUiThread(r);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        if (current != null) current.cancel();
        super.onDestroy();
    }
}
