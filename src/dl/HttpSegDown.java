package kawaii.viey.browser;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 多源多连接分段下载引擎（HTTP/HTTPS Range）。
 *
 * 规则：
 *  - 只用返回 206 + Content-Range 的源做分段；不支持 Range 的源直接判失败（提示换镜像），
 *    避免把 200 的整包写进错误偏移。
 *  - 段级重试：每段最多 3 次，失败自动换下一个镜像；各段独立续传（.part.state 记 offset）。
 *  - 动态分片：某段剩余 > 4MB 且活跃连接未达上限时对半再切。
 *  - 完成后可选校验 SHA-256，再改名为正式文件。
 */
public class HttpSegDown {

    private static final String TAG = "viey-dl";
    private static final long SPLIT_MIN = 4L * 1024 * 1024;
    private static final int MAX_ATTEMPTS = 8;
    private static final int MAX_ROUNDS = 20;
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    public interface Cb {
        void onProgress(long done, long total, String note);

        void onDone(boolean ok, String msg, File file);
    }

    private static class Seg {
        long start;
        long end;
        final AtomicLong done = new AtomicLong(0);
        boolean locked = false;
        int attempts = 0;

        Seg(long s, long e) {
            start = s;
            end = e;
        }

        long total() {
            return end - start + 1;
        }

        long remaining() {
            return total() - done.get();
        }

        long pos() {
            return start + done.get();
        }

        boolean finished() {
            return done.get() >= total();
        }
    }

    private final Context ctx;
    private final String name;
    private final List<String> urls;
    private final int maxConn;
    private final String expectSha256;
    private long size;
    private volatile boolean paused = false;
    private volatile String lastError = "";
    private final List<Seg> segs = Collections.synchronizedList(new ArrayList<Seg>());
    private final AtomicLong written = new AtomicLong(0);
    private File part;
    private File state;

    public HttpSegDown(Context c, String name, long size, List<String> urls, int maxConn, String sha256) {
        this.ctx = c.getApplicationContext();
        this.name = name;
        this.size = size;
        this.urls = new ArrayList<>(urls);
        this.maxConn = Math.max(1, Math.min(16, maxConn));
        this.expectSha256 = sha256;
    }

    public void pause() {
        paused = true;
    }

    public File dir() {
        File d = ctx.getExternalFilesDir("download");
        if (d == null) d = new File(ctx.getFilesDir(), "download");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public void start(Cb cb) {
        new Thread(() -> {
            try {
                File d = dir();
                part = new File(d, name + ".part");
                state = new File(d, name + ".part.state");
                if (size <= 0) size = probeSize();
                if (size <= 0) throw new IOException("无法确定文件大小（源可能不支持 Range）");
                plan();
                cb.onProgress(written.get(), size, "已规划 " + segs.size() + " 段 / " + urls.size() + " 源");
                coordinate(cb);
                if (paused) {
                    save();
                    cb.onDone(false, "已暂停（进度已保存）", part);
                    return;
                }
                if (!allDone()) throw new IOException("未完成：" + lastError);
                if (expectSha256 != null && !expectSha256.isEmpty()) {
                    cb.onProgress(size, size, "校验 SHA-256…");
                    if (!sha256(part).equalsIgnoreCase(expectSha256)) {
                        part.delete();
                        state.delete();
                        throw new IOException("SHA-256 不匹配，已删除残档");
                    }
                }
                if (part.length() != size) throw new IOException("长度校验失败：" + part.length() + " != " + size);
                File out = new File(d, name);
                if (out.exists() && !out.delete()) throw new IOException("旧文件无法删除：" + out.getName());
                if (!part.renameTo(out)) throw new IOException("改名失败");
                state.delete();
                cb.onDone(true, "完成：" + out.getName(), out);
            } catch (Exception e) {
                save();
                String msg = e.getClass().getSimpleName() + " " + e.getMessage();
                Log.w(TAG, "下载失败 " + name + " " + msg);
                cb.onDone(false, msg, part);
            }
        }, "httpseg-" + name).start();
    }

    /** 探测大小：GET Range 0-1 读 Content-Range 总长（唯一可信来源），HEAD 长度仅作 >=4KB 的兜底 */
    private long probeSize() throws IOException {
        String last = "";
        for (String u : urls) {
            // 首选：Range 0-1 → 206 + Content-Range: bytes 0-1/总长
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("Range", "bytes=0-1");
                c.setRequestProperty("Accept-Encoding", "identity");
                c.setRequestProperty("User-Agent", UA);
                int code = c.getResponseCode();
                String cr = c.getHeaderField("Content-Range");
                c.disconnect();
                if (code == 206 && cr != null && cr.contains("/")) {
                    long len = Long.parseLong(cr.substring(cr.lastIndexOf('/') + 1).trim());
                    if (len >= 4096) return len;
                    last = "Content-Range 异常:" + cr;
                } else {
                    last = "HTTP " + code + " cr=" + cr;
                }
            } catch (Exception e) {
                last = e.getMessage();
            }
            // 兜底：HEAD 长度（必须 >= 4KB 才认可，避免把错误页长度当文件大小）
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                c.setRequestMethod("HEAD");
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", UA);
                long len = c.getContentLengthLong();
                c.disconnect();
                if (len >= 4096) return len;
            } catch (Exception e) {
                last = e.getMessage();
            }
        }
        throw new IOException("探测失败：" + last);
    }

    private void plan() throws IOException {
        if (state.exists() && part.exists()) {
            try {
                JSONObject o = new JSONObject(PluginStore.read(state));
                if (o.optLong("size", -1) == size) {
                    JSONArray a = o.getJSONArray("segs");
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject so = a.getJSONObject(i);
                        Seg g = new Seg(so.getLong("start"), so.getLong("end"));
                        g.done.set(so.optLong("done", 0));
                        segs.add(g);
                        written.addAndGet(g.done.get());
                    }
                    if (part.length() < size) {
                        RandomAccessFile raf = new RandomAccessFile(part, "rw");
                        raf.setLength(size);
                        raf.close();
                    }
                    Log.i(TAG, "续传 " + name + " 已 " + written.get() + "/" + size);
                    return;
                }
            } catch (Exception ignored) {
            }
        }
        segs.clear();
        written.set(0);
        int n = Math.min(maxConn, Math.max(1, (int) Math.min(8, size / SPLIT_MIN + 1)));
        long chunk = size / n;
        for (int i = 0; i < n; i++) {
            long s = i * chunk;
            long e = (i == n - 1) ? size - 1 : (s + chunk - 1);
            segs.add(new Seg(s, e));
        }
        long sum = 0;
        for (Seg g : segs) sum += g.total();
        if (sum != size) throw new IOException("分段规划错误：" + sum + " != " + size);
        RandomAccessFile raf = new RandomAccessFile(part, "rw");
        raf.setLength(size);
        raf.close();
        save();
    }

    private void coordinate(Cb cb) throws InterruptedException, IOException {
        final AtomicInteger active = new AtomicInteger();
        long lastTick = System.currentTimeMillis();
        long lastBytes = written.get();
        int rounds = 0;
        while (!paused) {
            if (active.get() == 0 && allDone()) break;   // 全部段完成，退出协调循环
            boolean launched = false;
            synchronized (segs) {
                for (Seg g : segs) {
                    if (active.get() >= maxConn) break;
                    if (g.finished() || g.locked || g.attempts >= MAX_ATTEMPTS) continue;
                    g.locked = true;
                    active.incrementAndGet();
                    launched = true;
                    final Seg target = g;
                    new Thread(() -> {
                        try {
                            fetch(target);
                            target.attempts = 0;
                        } catch (Exception e) {
                            target.attempts++;
                            lastError = target.start + ":" + e.getMessage();
                            Log.w(TAG, "段 " + target.start + " 第 " + target.attempts + " 次失败：" + e.getMessage());
                        } finally {
                            target.locked = false;
                            active.decrementAndGet();
                        }
                    }, "seg-" + g.start).start();
                }
            }
            long now = System.currentTimeMillis();
            if (now - lastTick >= 1000) {
                long cur = written.get();
                long delta = cur - lastBytes;
                lastTick = now;
                lastBytes = cur;
                cb.onProgress(cur, size, "速度 " + speed(delta) + " · " + segs.size() + " 段 · " + active.get() + " 连接"
                        + (rounds > 0 ? " · 第 " + (rounds + 1) + " 轮重试" : ""));
                save();
                split();
            }
            // 所有存活段都用尽重试次数但未完成：整体退避后开新一轮，而不是直接判失败
            if (!launched && active.get() == 0 && !allDone()) {
                if (rounds >= MAX_ROUNDS) throw new IOException("重试 " + MAX_ROUNDS + " 轮仍失败：" + lastError);
                rounds++;
                Log.i(TAG, "第 " + rounds + " 轮整体重试，退避 " + Math.min(30, rounds * 2) + "s");
                cb.onProgress(written.get(), size, "全部连接被限流/失败，退避 " + Math.min(30, rounds * 2) + "s 后第 "
                        + (rounds + 1) + " 轮重试");
                Thread.sleep(Math.min(30000, rounds * 2000L));
                synchronized (segs) {
                    for (Seg g : segs) if (!g.finished()) g.attempts = 0;
                }
            }
            Thread.sleep(120);
        }
    }

    private void fetch(Seg g) throws IOException {
        if (g.finished()) return;
        String url = urls.get(g.attempts % urls.size());
        httpRange(url, g);
    }

    private void httpRange(String url, Seg g) throws IOException {
        long from = g.pos();
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Range", "bytes=" + from + "-" + g.end);
        c.setRequestProperty("Accept-Encoding", "identity");
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Referer", "https://" + new URL(url).getHost() + "/");
        try {
            int code = c.getResponseCode();
            if (code == 403 || code == 408 || code == 429 || code >= 500) {
                // 限流 / 临时拒绝：退避后重试，不算“不支持分段”
                try {
                    Thread.sleep(Math.min(8000, 1000L * g.attempts + 800));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                throw new IOException("限流（HTTP " + code + "），已退避重试");
            }
            if (code != 206) throw new IOException("源不支持分段（HTTP " + code + "）");
            String cr = c.getHeaderField("Content-Range");
            if (cr != null && cr.contains("/")) {
                try {
                    long total = Long.parseLong(cr.substring(cr.lastIndexOf('/') + 1).trim());
                    if (total > 0 && total != size) {
                        throw new IOException("长度不一致（探测 " + size + " / 实际 " + total + "），请重新开始");
                    }
                } catch (Exception ignored) {
                }
            }
            InputStream in = c.getInputStream();
            RandomAccessFile raf = new RandomAccessFile(part, "rw");
            try {
                raf.seek(from);
                byte[] buf = new byte[16384];
                while (!paused) {
                    long left = g.remaining();
                    if (left <= 0) break;
                    int r = in.read(buf, 0, (int) Math.min(buf.length, left));
                    if (r <= 0) break;
                    raf.write(buf, 0, r);
                    g.done.addAndGet(r);
                    written.addAndGet(r);
                }
            } finally {
                try {
                    raf.close();
                } catch (Exception ignored) {
                }
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        } finally {
            c.disconnect();
        }
    }

    private void split() {
        if (paused) return;
        synchronized (segs) {
            int activeSeg = 0;
            Seg biggest = null;
            for (Seg g : segs) {
                if (!g.finished()) activeSeg++;
                if (!g.finished() && (biggest == null || g.remaining() > biggest.remaining())) biggest = g;
            }
            if (activeSeg < maxConn && biggest != null && biggest.remaining() > SPLIT_MIN && !biggest.locked) {
                long mid = biggest.pos() + biggest.remaining() / 2;
                Seg right = new Seg(mid, biggest.end);
                biggest.end = mid - 1;
                segs.add(right);
            }
        }
    }

    private boolean allDone() {
        synchronized (segs) {
            if (segs.isEmpty()) return false;
            for (Seg g : segs) if (!g.finished()) return false;
        }
        return true;
    }

    private void save() {
        try {
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("size", size);
            JSONArray a = new JSONArray();
            synchronized (segs) {
                for (Seg g : segs) {
                    JSONObject so = new JSONObject();
                    so.put("start", g.start);
                    so.put("end", g.end);
                    so.put("done", g.done.get());
                    a.put(so);
                }
            }
            o.put("segs", a);
            o.put("written", written.get());
            DlStore.writeAtomic(state, o.toString());
        } catch (Exception ignored) {
        }
    }

    private static String speed(long bytesPerSec) {
        double v = bytesPerSec;
        String unit = "B/s";
        if (v >= 1024) {
            v /= 1024;
            unit = "KB/s";
        }
        if (v >= 1024) {
            v /= 1024;
            unit = "MB/s";
        }
        return String.format(Locale.US, "%.1f %s", v, unit);
    }

    public static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        FileInputStream in = new FileInputStream(f);
        byte[] buf = new byte[16384];
        int r;
        while ((r = in.read(buf)) > 0) md.update(buf, 0, r);
        in.close();
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
