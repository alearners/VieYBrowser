package kawaii.viey.browser;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * BitTorrent 下载调度：magnet / .torrent → tracker 取 peer → peer wire 拉 piece →
 * 逐片 SHA-1 校验（piece 校验）→ 落盘 + 断点续传。
 *
 * 范围（诚实标注）：不建议做种子上传/做种；不做 DHT/PEX（只有 tracker + magnet 里的 tr）；
 * 单 tracker 协议（HTTP/HTTPS announce），不做 UDP tracker。
 */
public class BtDown {

    private static final String TAG = "viey-bt";

    public interface Cb {
        void onProgress(long done, long total, String note);

        void onDone(boolean ok, String msg, File out);
    }

    private final Context ctx;
    private final Magnet magnet;      // 可为空（.torrent 直接给）
    private final byte[] metainfo;    // 可为空（magnet 走 BEP-9）
    private Torrent tor;
    private volatile Tracker.Peer metaPeer;   // BEP-9 拉到元数据的 peer，下载阶段优先用
    private final int maxPeers;
    private volatile boolean paused = false;
    private volatile String lastError = "无可用 peer";
    private final AtomicLong doneBytes = new AtomicLong(0);
    private final AtomicLong baseDone = new AtomicLong(0);
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private final List<PeerWire> live = Collections.synchronizedList(new ArrayList<>());

    private File dir;
    private File stateFile;
    private File[] rafs;
    private RandomAccessFile[] handles;
    private final List<Integer> remain = Collections.synchronizedList(new ArrayList<>());

    public BtDown(Context c, String torrentText, int maxPeers) throws Exception {
        this.ctx = c.getApplicationContext();
        this.maxPeers = Math.max(1, Math.min(8, maxPeers));
        String s = torrentText == null ? "" : torrentText.trim();
        if (s.toLowerCase(Locale.US).startsWith("magnet:")) {
            magnet = Magnet.parse(s);
            boolean added = magnet.ensureHttpTrackers();
            if (added) Log.i(TAG, "magnet 无 HTTP tracker，已补默认 " + magnet.trackers.size() + " 个");
            metainfo = null;
        } else {
            magnet = null;
            byte[] d = readAll(new File(s));
            metainfo = d;
        }
    }

    public BtDown(Context c, byte[] metainfoBytes, int maxPeers) throws Exception {
        this.ctx = c.getApplicationContext();
        this.maxPeers = Math.max(1, Math.min(8, maxPeers));
        this.magnet = null;
        this.metainfo = metainfoBytes;
    }

    public void pause() {
        paused = true;
        synchronized (live) {
            for (PeerWire p : live) p.close();
        }
    }

    private File baseDir() {
        File d = ctx.getExternalFilesDir("download");
        if (d == null) d = new File(ctx.getFilesDir(), "download");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public void start(Cb cb) {
        new Thread(() -> {
            try {
                if (metainfo != null) tor = Torrent.parse(metainfo);
                else {
                    cb.onProgress(0, magnet.exactLength, "magnet：btih=" + magnet.infoHashHex.substring(0, 8)
                            + "… 等待从 peer 拉取元数据（BEP-9）");
                    tor = fetchTorrentViaMetadata(cb);
                }
                if (paused) throw new Exception("已取消");
                dir = baseDir();
                if (!tor.files.isEmpty() && tor.files.size() == 1) {
                    stateFile = new File(dir, tor.name + ".bt.state");
                } else {
                    File folder = new File(dir, tor.name);
                    if (!folder.exists()) folder.mkdirs();
                    dir = folder;
                    stateFile = new File(folder, ".bt.state");
                }
                prepare();
                cb.onProgress(baseDone.get(), tor.totalLength,
                        "已准备 " + tor.pieceCount() + " 片 / " + (baseDone.get() * 100 / Math.max(1, tor.totalLength)) + "% 续传");
                long t0 = System.currentTimeMillis();
                long t0bytes = doneBytes.get();
                run(cb);
                if (paused) {
                    save();
                    cb.onDone(false, "已暂停（piece 进度已保存）", dir);
                    return;
                }
                if (!allDone()) throw new Exception(lastError);
                verifyAll(cb);
                closeAll();
                File out = finishFile(cb);
                stateFile.delete();
                cb.onDone(true, "BT 完成：" + out.getName(), out);
            } catch (Exception e) {
                save();
                closeAll();
                String msg = e.getClass().getSimpleName() + " " + e.getMessage();
                Log.w(TAG, "BT 失败 " + msg);
                cb.onDone(false, msg, dir);
            }
        }, "bt-" + (magnet != null ? magnet.infoHashHex.substring(0, 6) : "torrent")).start();
    }

    // ---------- 元数据 ----------

    private Torrent fetchTorrentViaMetadata(Cb cb) throws Exception {
        List<Tracker.Peer> peers = announceAll(1L, 1);
        if (peers.isEmpty()) {
            throw new Exception("tracker 没有返回 peer（已试 " + magnet.trackers.size() + " 个 tracker，"
                    + "本内核不做 DHT/UDP tracker，公开 HTTP tracker 多数已失效）");
        }
        cb.onProgress(0, magnet.exactLength, "拿到 " + peers.size() + " 个 peer，并发拉元数据（BEP-9）");
        final String peerId = Tracker.peerId();
        int n = Math.min(peers.size(), 40);
        java.util.concurrent.ExecutorService es = java.util.concurrent.Executors.newFixedThreadPool(4);
        java.util.concurrent.CompletionService<Torrent> cs =
                new java.util.concurrent.ExecutorCompletionService<>(es);
        for (int i = 0; i < n; i++) {
            final Tracker.Peer p = peers.get(i);
            cs.submit(() -> {
                if (paused) throw new Exception("已取消");
                PeerWire w = new PeerWire(p.host, p.port, magnet.infoHash(), peerId);
                try {
                    w.connect();
                    byte[] info = w.fetchMetadata();
                    Torrent t = Torrent.fromInfo(info, magnet.trackers, magnet.displayName);
                    metaPeer = p;
                    Log.i(TAG, "metadata ok from " + p.key() + " name=" + t.name);
                    return t;
                } finally {
                    w.close();
                }
            });
        }
        es.shutdown();
        Exception last = null;
        long deadline = System.currentTimeMillis() + 120000L;
        try {
            for (int i = 0; i < n; i++) {
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) break;
                java.util.concurrent.Future<Torrent> f =
                        cs.poll(remain, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (f == null) break;
                try {
                    Torrent t = f.get();
                    cb.onProgress(0, t.totalLength, "元数据到手：" + t.name + " / " + t.pieceCount() + " 片");
                    return t;
                } catch (Exception e) {
                    last = e;
                    Log.w(TAG, "metadata 失败：" + e.getMessage());
                }
            }
        } finally {
            es.shutdownNow();
        }
        throw new Exception("所有 peer 都没给出元数据" + (last != null ? "（最后：" + last.getMessage() + "）" : ""));
    }

    private List<Tracker.Peer> announceAll(long left, int event) {
        List<Tracker.Peer> all = new ArrayList<>();
        List<String> ts = new ArrayList<>();
        if (tor != null) ts.addAll(tor.trackers);
        else if (magnet != null) ts.addAll(magnet.trackers);
        byte[] ih = tor != null ? tor.infoHash : magnet.infoHash();
        String pid = Tracker.peerId();
        for (String t : ts) {
            try {
                all.addAll(Tracker.announce(t, ih, pid, 6881, left, event));
            } catch (Exception e) {
                Log.w(TAG, "tracker " + t + " 失败：" + e.getMessage());
            }
        }
        return all;
    }

    // ---------- 落盘 ----------

    private void prepare() throws Exception {
        rafs = new File[tor.files.size()];
        handles = new RandomAccessFile[tor.files.size()];
        if (tor.files.size() == 1) {
            rafs[0] = new File(dir, tor.name + ".btpart");
            if (!rafs[0].exists()) rafs[0].createNewFile();
            RandomAccessFile r = new RandomAccessFile(rafs[0], "rw");
            if (r.length() < tor.totalLength) r.setLength(tor.totalLength);
            r.close();
        } else {
            for (int i = 0; i < tor.files.size(); i++) {
                File f = new File(dir, tor.files.get(i).path);
                File parent = f.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                rafs[i] = f;
                if (!f.exists()) f.createNewFile();
                RandomAccessFile r = new RandomAccessFile(f, "rw");
                if (r.length() < tor.files.get(i).length) r.setLength(tor.files.get(i).length);
                r.close();
            }
        }
        load();
    }

    private RandomAccessFile handle(int i) throws Exception {
        if (handles[i] == null) handles[i] = new RandomAccessFile(rafs[i], "rw");
        return handles[i];
    }

    private void load() {
        remain.clear();
        for (int i = 0; i < tor.pieceCount(); i++) remain.add(i);
        baseDone.set(0);
        doneBytes.set(0);
        if (stateFile == null || !stateFile.exists()) return;
        try {
            JSONObject o = new JSONObject(PluginStore.read(stateFile));
            if (o.optLong("total", -1) != tor.totalLength) return;
            JSONArray a = o.getJSONArray("done");
            for (int i = 0; i < a.length(); i++) remain.remove(Integer.valueOf(a.getInt(i)));
            baseDone.set(o.optLong("bytes", 0));
            doneBytes.set(baseDone.get());
            Log.i(TAG, "续传：已完成 " + (tor.pieceCount() - remain.size()) + "/" + tor.pieceCount());
        } catch (Exception e) {
            Log.w(TAG, "读状态失败：" + e.getMessage());
        }
    }

    private void save() {
        if (stateFile == null || tor == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("total", tor.totalLength);
            o.put("bytes", doneBytes.get());
            JSONArray a = new JSONArray();
            synchronized (remain) {
                for (int i = 0; i < tor.pieceCount(); i++) if (!remain.contains(i)) a.put(i);
            }
            o.put("done", a);
            DlStore.writeAtomic(stateFile, o.toString());
        } catch (Exception ignored) {
        }
    }

    private void writePiece(int index, byte[] data) throws Exception {
        long start = (long) index * tor.pieceLength;
        int len = data.length;
        int written = 0;
        for (int i = 0; i < tor.files.size() && written < len; i++) {
            Torrent.Entry e = tor.files.get(i);
            long eEnd = e.offset + e.length;
            if (start + written >= eEnd) continue;
            if (start + written < e.offset) continue;
            long inFile = start + written - e.offset;
            int can = (int) Math.min(len - written, e.length - inFile);
            RandomAccessFile h = handle(i);
            h.seek(inFile);
            h.write(data, written, can);
            written += can;
        }
    }

    private void closeAll() {
        if (handles != null) {
            for (RandomAccessFile h : handles) {
                try {
                    if (h != null) h.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private File finishFile(Cb cb) {
        closeAll();
        if (tor.files.size() == 1) {
            File out = new File(dir, tor.name);
            if (out.exists()) out.delete();
            if (!rafs[0].renameTo(out)) return rafs[0];
            return out;
        }
        return dir;
    }

    // ---------- 调度 ----------

    /**
     * peer 调度：滚动补池。tracker 一次给的 50 个 peer 里往往大半连不上，
     * 老实现只取前 maxPeers 个，前 8 个全挂就直接失败；这里改成
     * 失败即换下一个、池空（或不够并发）就重新 announce，直到全部试过或超时。
     */
    private void run(Cb cb) throws Exception {
        if (remain.isEmpty()) return;
        final String peerId = Tracker.peerId();
        final long[] lastTick = {System.currentTimeMillis()};
        final long[] lastBytes = {doneBytes.get()};
        final java.util.List<Tracker.Peer> pool = new java.util.ArrayList<>();
        final java.util.Set<String> tried =
                java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
        final Tracker.Peer first = metaPeer;
        if (first != null) {
            tried.add(first.key());
            pool.add(first);
        }
        List<Thread> ts = new ArrayList<>();
        int round = 0;
        long t0 = System.currentTimeMillis();
        boolean announced = false;
        while (!allDone() && !paused) {
            java.util.Iterator<Thread> it = ts.iterator();
            while (it.hasNext()) {
                if (!it.next().isAlive()) it.remove();
            }
            if (ts.size() >= maxPeers) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {
                }
                continue;
            }
            if (pool.isEmpty()) {
                if (round >= 8 || System.currentTimeMillis() - t0 > 40L * 60000L) break;
                long left = Math.max(1, tor.totalLength - doneBytes.get());
                List<Tracker.Peer> got = announceAll(left, round == 0 ? 1 : 0);
                announced = true;
                synchronized (tried) {
                    for (Tracker.Peer p : got) if (tried.add(p.key())) pool.add(p);
                }
                round++;
                if (pool.isEmpty()) {
                    if (ts.isEmpty()) {
                        if (round >= 8) break;
                        try {
                            Thread.sleep(1200);
                        } catch (InterruptedException ignored) {
                        }
                    }
                    continue;
                }
            }
            Tracker.Peer p = pool.remove(0);
            final Tracker.Peer fp = p;
            Thread th = new Thread(() -> peerLoop(fp, peerId, cb, lastTick, lastBytes), "peer-" + fp.key());
            ts.add(th);
            th.start();
        }
        for (Thread th : ts) {
            try {
                th.join(3000);
            } catch (InterruptedException ignored) {
            }
        }
        if (!allDone()) {
            if (!announced && !tor.webSeeds.isEmpty()) {
                webSeedDownload(cb);
                return;
            }
            lastError = "peer 全部退出但未完成（试过 " + tried.size() + " 个 peer / " + round + " 轮 announce"
                    + (paused ? "，已暂停" : "") + "）";
        }
    }

    /** BEP-19 HTTP 种子：无 peer 时按 Range 逐片取，仍然走 piece SHA-1 校验 */
    private void webSeedDownload(Cb cb) throws Exception {
        if (tor.files.size() > 1)
            throw new Exception("HTTP 种子暂时只支持单文件种子（多文件请等 peer 或改为单文件打包）");
        int seedIdx = 0;
        cb.onProgress(doneBytes.get(), tor.totalLength, "无 peer，改用 HTTP 种子 " + webSeedUrl(tor, tor.webSeeds.get(0)));
        long lastTick = System.currentTimeMillis();
        long lastBytes = doneBytes.get();
        while (!paused) {
            int index = nextPiece();
            if (index < 0) break;
            long start = (long) index * tor.pieceLength;
            int len = (int) tor.pieceSize(index);
            String url = webSeedUrl(tor, tor.webSeeds.get(seedIdx));
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Range", "bytes=" + start + "-" + (start + len - 1));
            c.setRequestProperty("User-Agent", "VieY/0.9 (Android)");
            try {
                int code = c.getResponseCode();
                if (code != 200 && code != 206) throw new Exception("HTTP 种子返回 " + code);
                byte[] data = new byte[len];
                java.io.InputStream in = c.getInputStream();
                if (code == 200) {
                    // 不支持 Range：顺序读完再丢弃到本片偏移
                    long skip = start;
                    while (skip > 0) {
                        long s2 = in.skip(skip);
                        if (s2 <= 0) {
                            if (in.read() < 0) break;
                            s2 = 1;
                        }
                        skip -= s2;
                    }
                }
                int got = 0;
                while (got < len) {
                    int r = in.read(data, got, len - got);
                    if (r <= 0) break;
                    got += r;
                }
                in.close();
                if (got != len) throw new Exception("片不完整 " + got + "/" + len);
                if (!tor.checkPiece(index, data, len)) throw new Exception("piece " + index + " 校验失败");
                writePiece(index, data);
                doneBytes.addAndGet(len);
                long now = System.currentTimeMillis();
                if (now - lastTick >= 1000) {
                    long delta = doneBytes.get() - lastBytes;
                    lastTick = now;
                    lastBytes = doneBytes.get();
                    cb.onProgress(doneBytes.get(), tor.totalLength,
                            "HTTP 种子 · " + speed(delta) + " · 剩 " + remain.size() + " 片");
                    save();
                }
            } catch (Exception e) {
                synchronized (remain) {
                    remain.add(index);
                }
                if (tor.webSeeds.size() > 1) {
                    // 多个 HTTP 种子：换一个再试，不行就往下抛
                    seedIdx = (seedIdx + 1) % tor.webSeeds.size();
                    android.util.Log.w("viey-bt", "HTTP 种子失败，换源：" + e.getMessage());
                    continue;
                }
                throw e;
            } finally {
                c.disconnect();
            }
        }
    }

    /**
     * BEP-19 种子 URL 解析：
     * - 以 / 结尾 → 目录型，拼上文件名
     * - 末段等于种子里的文件名（Debian 这类整文件 URL）→ 直接用
     * - 其他 → 当目录前缀，拼上文件名
     */
    static String webSeedUrl(Torrent tor, String seed) throws Exception {
        String s = seed.trim();
        if (s.endsWith("/")) return s + urlPath(tor.name);
        String tail = s.substring(s.lastIndexOf('/') + 1);
        if (tail.equals(urlPath(tor.name))) return s;
        return s + "/" + urlPath(tor.name);
    }

    /** 单文件种子的 HTTP 种子路径：按段 URL 编码，空格转 %20 */
    private static String urlPath(String name) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (String seg : name.split("/")) {
            if (sb.length() > 0) sb.append('/');
            sb.append(java.net.URLEncoder.encode(seg, "UTF-8").replace("+", "%20"));
        }
        return sb.toString();
    }

    private void peerLoop(Tracker.Peer p, String peerId, Cb cb, long[] lastTick, long[] lastBytes) {
        PeerWire w = null;
        try {
            w = new PeerWire(p.host, p.port, tor.infoHash, peerId);
            w.connect();
            w.sendBitfield(tor.pieceCount());
            live.add(w);
            Log.i(TAG, "peer 接入 " + p.key());
            int fails = 0;
            while (!paused && !remain.isEmpty()) {
                int index = nextPiece();
                if (index < 0) return;
                try {
                    byte[] data = w.downloadPiece(index, tor.pieceSize(index));
                    if (data.length != tor.pieceSize(index)) throw new Exception("片长度不符");
                    if (!tor.checkPiece(index, data, data.length)) {
                        // piece 校验失败：丢弃，放回队列重下
                        synchronized (remain) {
                            remain.add(index);
                        }
                        throw new Exception("piece " + index + " SHA-1 校验失败");
                    }
                    writePiece(index, data);
                    long add = data.length;
                    doneBytes.addAndGet(add);
                    long now = System.currentTimeMillis();
                    if (now - lastTick[0] >= 1000) {
                        long delta = doneBytes.get() - lastBytes[0];
                        lastTick[0] = now;
                        lastBytes[0] = doneBytes.get();
                        cb.onProgress(doneBytes.get(), tor.totalLength,
                                "peer " + p.key() + " · " + speed(delta) + " · 剩 " + remain.size() + " 片");
                        save();
                    }
                } catch (Exception e) {
                    String m = e.getMessage() == null ? "" : e.getMessage();
                    Log.w(TAG, "peer " + p.key() + " 取片 " + index + " 失败：" + m);
                    lastError = m;
                    synchronized (remain) {
                        if (!remain.contains(index)) remain.add(index);
                    }
                    fails++;
                    // 连接级错误直接放弃该 peer，避免同一断链上疯狂重试刷日志
                    if (fails >= 3 || m.contains("连接断开") || m.contains("Broken pipe")
                            || m.contains("choke") || m.contains("超时") || m.contains("Connection reset")) break;
                }
            }
        } catch (Exception e) {
            lastError = e.getMessage();
            Log.w(TAG, "peer " + p.key() + " 断开：" + e.getMessage());
        } finally {
            if (w != null) w.close();
            live.remove(w);
        }
    }

    /** 领取一片（从队列移除，避免多个 peer 重复下同一片） */
    private int nextPiece() {
        synchronized (remain) {
            if (remain.isEmpty()) return -1;
            return remain.remove(0);
        }
    }

    private boolean allDone() {
        return remain.isEmpty();
    }

    /** 完成后逐片复核 SHA-1（piece 校验的最终确认） */
    private void verifyAll(Cb cb) throws Exception {
        for (int i = 0; i < tor.pieceCount(); i++) {
            long start = (long) i * tor.pieceLength;
            int len = (int) tor.pieceSize(i);
            byte[] buf = new byte[len];
            long pos = start;
            int off = 0;
            for (int f = 0; f < tor.files.size() && off < len; f++) {
                Torrent.Entry e = tor.files.get(f);
                if (pos + (len - off) <= e.offset) continue;
                if (pos >= e.offset + e.length) continue;
                long inFile = pos - e.offset;
                int can = (int) Math.min(len - off, e.length - inFile);
                RandomAccessFile h = handle(f);
                h.seek(inFile);
                h.readFully(buf, off, can);
                off += can;
                pos += can;
            }
            if (!tor.checkPiece(i, buf, len)) throw new Exception("复核失败：piece " + i);
            if (i % 200 == 0) cb.onProgress(tor.totalLength, tor.totalLength, "复核 piece " + i + "/" + tor.pieceCount());
        }
    }

    static String speed(long bps) {
        double v = bps;
        String u = "B/s";
        if (v >= 1024) {
            v /= 1024;
            u = "KB/s";
        }
        if (v >= 1024) {
            v /= 1024;
            u = "MB/s";
        }
        return String.format(Locale.US, "%.1f %s", v, u);
    }

    private static byte[] readAll(File f) throws Exception {
        if (!f.exists()) throw new Exception("文件不存在：" + f.getAbsolutePath());
        byte[] b = new byte[(int) f.length()];
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        int got = 0;
        while (got < b.length) {
            int r = in.read(b, got, b.length - got);
            if (r <= 0) break;
            got += r;
        }
        in.close();
        return b;
    }

    public Torrent torrent() {
        return tor;
    }
}
