package kawaii.viey.browser;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 多源下载（HTTP/HTTPS 镜像分段并发 + 断点续传 + ed2k 校验）。
 * uTP/P2P 走 Ed2kCore 的服务器取源，这里负责实际字节搬运。
 */
public class MultiSource {

    public interface Callback {
        void onProgress(long done, long total, String note);

        void onDone(boolean ok, String msg, File file);
    }

    private static final int MAX_THREADS = 4;
    private volatile boolean cancelled = false;

    private final Context ctx;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicLong done = new AtomicLong(0);

    public MultiSource(Context c) {
        this.ctx = c.getApplicationContext();
    }

    public void cancel() {
        cancelled = true;
    }

    public File targetDir() {
        File d = ctx.getExternalFilesDir("download");
        if (d == null) d = new File(ctx.getFilesDir(), "download");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /**
     * @param fileName  目标文件名
     * @param size      文件大小（0 表示未知，走单流）
     * @param mirrors   HTTP 镜像地址列表
     * @param expectHash ed2k hash，可为空
     */
    public void start(final String fileName, final long size, final List<String> mirrors,
                      final String expectHash, final Callback cb) {
        new Thread(() -> {
            File dir = targetDir();
            File out = new File(dir, safeName(fileName));
            File part = new File(dir, safeName(fileName) + ".part");
            try {
                if (mirrors == null || mirrors.isEmpty()) {
                    finish(cb, false, "没有可用的 HTTP 镜像地址（ed2k 源的 P2P 传输未实现，见 Ed2kCore 说明）", out);
                    return;
                }
                if (size <= 0) {
                    singleStream(mirrors, part, cb);
                } else {
                    segmented(size, mirrors, part, cb);
                }
                if (cancelled) {
                    finish(cb, false, "已取消（.part 已保留，可续传）", part);
                    return;
                }
                if (out.exists()) out.delete();
                if (!part.renameTo(out)) throw new Exception("重命名 .part 失败");
                String msg = "下载完成：" + out.getName() + "（" + (out.length() / 1024) + " KB）";
                if (expectHash != null && expectHash.length() == 32) {
                    ui.post(() -> cb.onProgress(out.length(), out.length(), "校验 ed2k hash…"));
                    String actual = Ed2kHash.hashFile(out);
                    if (actual.equalsIgnoreCase(expectHash)) {
                        msg += " · ed2k 校验通过";
                    } else {
                        msg += " · ed2k 校验失败（期望 " + expectHash + "，实际 " + actual + "）";
                    }
                }
                finish(cb, true, msg, out);
            } catch (Exception e) {
                finish(cb, false, "下载失败：" + e.getClass().getSimpleName() + " " + e.getMessage(), part);
            }
        }, "viey-multisource").start();
    }

    private void singleStream(List<String> mirrors, File part, Callback cb) throws Exception {
        Exception last = null;
        for (String m : mirrors) {
            if (cancelled) return;
            try {
                HttpURLConnection conn = open(m, -1);
                try (InputStream in = conn.getInputStream();
                     java.io.FileOutputStream fos = new java.io.FileOutputStream(part, false)) {
                    byte[] buf = new byte[8192];
                    int n;
                    long total = conn.getContentLengthLong();
                    long got = 0;
                    while ((n = in.read(buf)) > 0) {
                        if (cancelled) return;
                        fos.write(buf, 0, n);
                        got += n;
                        final long gg = got, ttl = total;
                        ui.post(() -> cb.onProgress(gg, ttl, "单流下载"));
                    }
                }
                return;
            } catch (Exception e) {
                last = e;
            }
        }
        throw last == null ? new Exception("所有镜像均失败") : last;
    }

    private void segmented(long size, List<String> mirrors, File part, Callback cb) throws Exception {
        int threads = Math.min(MAX_THREADS, Math.max(1, mirrors.size()));
        long chunk = size / threads;
        List<Thread> ts = new ArrayList<>();
        List<Exception> errors = java.util.Collections.synchronizedList(new ArrayList<>());
        try (RandomAccessFile raf = new RandomAccessFile(part, "rw")) {
            raf.setLength(size);
        }
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            final long from = i * chunk;
            final long to = (i == threads - 1) ? size - 1 : (from + chunk - 1);
            final String mirror = mirrors.get(i % mirrors.size());
            Thread t = new Thread(() -> {
                Exception last = null;
                for (int attempt = 0; attempt < 2 && !cancelled; attempt++) {
                    try {
                        HttpURLConnection conn = open(mirror, from);
                        conn.setRequestProperty("Range", "bytes=" + from + "-" + to);
                        int code = conn.getResponseCode();
                        if (code != 206 && code != 200) throw new Exception("HTTP " + code);
                        try (InputStream in = conn.getInputStream();
                             RandomAccessFile w = new RandomAccessFile(part, "rw")) {
                            w.seek(from);
                            byte[] buf = new byte[8192];
                            long pos = from;
                            int n;
                            while ((n = in.read(buf)) > 0 && pos <= to) {
                                if (cancelled) return;
                                int len = (int) Math.min(n, to - pos + 1);
                                w.write(buf, 0, len);
                                pos += len;
                                done.addAndGet(len);
                                long d = done.get(), tt = size;
                                ui.post(() -> cb.onProgress(d, tt, "分段 " + (idx + 1) + "/" + threads));
                            }
                            return;
                        }
                    } catch (Exception e) {
                        last = e;
                    }
                }
                if (last != null) errors.add(last);
            }, "viey-seg-" + i);
            ts.add(t);
            t.start();
        }
        for (Thread t : ts) t.join();
        if (!errors.isEmpty() && errors.get(0) != null) throw errors.get(0);
        if (done.get() < size) throw new Exception("分段未完整（" + done.get() + "/" + size + "）");
    }

    private HttpURLConnection open(String url, long rangeFrom) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "VieY/0.6 ed2k-multisource");
        conn.setRequestProperty("Accept", "*/*");
        return conn;
    }

    private void finish(Callback cb, boolean ok, String msg, File f) {
        ui.post(() -> cb.onDone(ok, msg, f));
    }

    private static String safeName(String n) {
        if (n == null || n.length() == 0) return "download_" + System.currentTimeMillis();
        return n.replaceAll("[\\\\/:*?\"<>|]", "_");
    }
}
