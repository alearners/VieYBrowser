package kawaii.viey.browser;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * DownloadClient：eD2k 下载状态机。
 * 状态：解析 → 取源(服务器/Kad/内嵌) → 连接对端 → FILEREQUEST → SLOT → 请求块 → 校验 → 完成。
 */
public class Ed2kDownload implements PeerClient.Sink {

    public interface Listener {
        void onState(String state);

        void onProgress(long done, long total, int peers, int speedBps, int blocksDone, int blocksTotal);

        void onDone(boolean ok, String msg, File file);
    }

    private static final int MAX_PEERS = 4;

    private final Context ctx;
    private final String name;
    private final long size;
    private final String hashHex;
    private final List<String> sources = Collections.synchronizedList(new ArrayList<>());
    private final List<PeerClient> peers = Collections.synchronizedList(new ArrayList<>());
    private final Handler ui = new Handler(Looper.getMainLooper());

    private PartFile part;
    private List<String> chunkHashes = new ArrayList<>();
    private volatile boolean running = false;
    private volatile boolean hashsetAsked = false;
    private long lastBytes = 0;
    private long lastTick = 0;
    private int speed = 0;
    private Listener listener;
    private String serverHost;
    private int serverPort;
    private long totalDownloaded = 0;
    private volatile boolean verifying = false;

    public Ed2kDownload(Context c, Ed2kLink link) {
        this.ctx = c.getApplicationContext();
        this.name = link.name;
        this.size = link.size;
        this.hashHex = link.hash;
        if (link.serverHost != null && link.serverHost.length() > 0) {
            serverHost = link.serverHost;
            serverPort = link.serverPort;
        }
        for (String s : link.sources) {
            String hp = s.replace("ed2k://", "").trim();
            int i = hp.lastIndexOf(':');
            if (i > 0) addSource(hp.substring(0, i), safePort(hp.substring(i + 1)));
        }
    }

    private static int safePort(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public void addSource(String host, int port) {
        if (port <= 0 || host == null || host.length() == 0) return;
        String key = host + ":" + port;
        synchronized (sources) {
            for (String s : sources) if (s.equals(key)) return;
            sources.add(key);
        }
    }

    public int sourceCount() {
        return sources.size();
    }

    public int peerCount() {
        return peers.size();
    }

    public int speedBps() {
        return speed;
    }

    public long doneBytes() {
        return part == null ? 0 : (long) part.doneBlocks() * Ed2kProto.BLOCK;
    }

    public void start() {
        if (running) return;
        if (size <= 0 || hashHex == null || hashHex.length() != 32) {
            fail("ed2k 链接缺少 size 或 hash，无法开始 P2P 下载");
            return;
        }
        running = true;
        state("准备 .part 文件");
        new Thread(() -> {
            try {
                part = new PartFile(ctx, name, size);
                if (part.loadState()) state("发现续传状态：" + part.doneBlocks() + "/" + part.blockCount + " 块");
                part.prepare();
                if (part.isComplete()) {
                    finishVerify();
                    return;
                }
                state("取源：服务器 + 内嵌");
                resolveSources();
                state("源数量 " + sources.size() + "，开始连接对端");
                managerLoop();
            } catch (Exception e) {
                fail("下载异常：" + e.getMessage());
            }
        }, "ed2k-download").start();
    }

    public void stop() {
        running = false;
        synchronized (peers) {
            for (PeerClient p : new ArrayList<>(peers)) p.close("用户停止");
            peers.clear();
        }
        if (part != null) part.saveState();
        state("已停止（.part 与状态已保留，可续传）");
    }

    private void resolveSources() {
        if (serverHost != null && serverPort > 0 && sources.size() < 8) {
            List<String> got = new ArrayList<>();
            Ed2kCore.Result r = Ed2kCore.querySources(serverHost, serverPort, hashHex, size, 4662, got, 12000);
            state("服务器取源：" + r.message + (got.isEmpty() ? "" : "，+" + got.size()));
            for (String hp : got) {
                int i = hp.lastIndexOf(':');
                if (i > 0) addSource(hp.substring(0, i), safePort(hp.substring(i + 1)));
            }
        }
        KadNode kad = KadNode.instance(ctx);
        if (kad.isReady() && sources.size() < 8) {
            List<String> got = kad.findSources(hashHex, size);
            if (!got.isEmpty()) state("Kad 取源 +" + got.size());
            for (String hp : got) {
                int i = hp.lastIndexOf(':');
                if (i > 0) addSource(hp.substring(0, i), safePort(hp.substring(i + 1)));
            }
        }
    }

    private void managerLoop() {
        while (running) {
            try {
                fillPeers();
                assignBlocks();
                pruneDead();
                tickSpeed();
                Thread.sleep(400);
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                state("调度异常：" + e.getMessage());
                try {
                    Thread.sleep(800);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        }
    }

    private void fillPeers() {
        synchronized (peers) {
            int n = peers.size();
            if (n >= MAX_PEERS) return;
            List<String> cand = new ArrayList<>();
            synchronized (sources) {
                for (String s : sources) {
                    boolean used = false;
                    for (PeerClient p : peers) if ((p.host + ":" + p.port).equals(s)) used = true;
                    if (!used) cand.add(s);
                }
            }
            for (String hp : cand) {
                if (peers.size() >= MAX_PEERS) break;
                int i = hp.lastIndexOf(':');
                if (i <= 0) continue;
                boolean allowObf = VieYApp.getPrefs(ctx).getBoolean("ed2k_obf", true);
                PeerClient p = new PeerClient(hp.substring(0, i), safePort(hp.substring(i + 1)),
                        hashHex, size, Ed2kProto.localUserHash(ctx), null, this, allowObf);
                peers.add(p);
                p.start();
                state("连接 " + hp);
            }
        }
    }

    private void assignBlocks() {
        if (part == null) return;
        if (!hashsetAsked) {
            synchronized (peers) {
                if (!peers.isEmpty() && peers.get(0).isSlot()) {
                    peers.get(0).requestHashset();
                    hashsetAsked = true;
                }
            }
        }
        synchronized (peers) {
            for (PeerClient p : new ArrayList<>(peers)) {
                if (!p.isAlive() || !p.isSlot()) continue;
                int b = claimNext();
                if (b < 0) return;
                try {
                    p.requestBlocks(new int[]{b}, new long[]{part.startOf(b)}, new int[]{part.lenOf(b)});
                } catch (Exception e) {
                    part.release(b);
                    p.close("请求失败：" + e.getMessage());
                }
            }
        }
    }

    /** 原子领取下一个未完成块（claim 已在 PartFile 里去重） */
    private int claimNext() {
        for (int i = 0; i < part.blockCount; i++) {
            if (!part.isDone(i) && part.claim(i)) return i;
        }
        return -1;
    }

    private void pruneDead() {
        synchronized (peers) {
            peers.removeIf(p -> !p.isAlive());
        }
    }

    private void tickSpeed() {
        long now = System.currentTimeMillis();
        if (lastTick == 0) {
            lastTick = now;
            lastBytes = totalDownloaded;
            return;
        }
        long dt = now - lastTick;
        if (dt >= 1000) {
            speed = (int) ((totalDownloaded - lastBytes) * 1000 / dt);
            lastBytes = totalDownloaded;
            lastTick = now;
            final int s = speed;
            final long done = doneBytes();
            final int pc, bd, bt;
            synchronized (peers) {
                pc = peers.size();
            }
            bd = part.doneBlocks();
            bt = part.blockCount;
            final Listener l = listener;
            if (l != null) ui.post(() -> l.onProgress(done, size, pc, s, bd, bt));
            if (part != null && part.isComplete() && !verifying) finishVerify();
        }
    }

    // ---- PeerClient.Sink ----

    @Override
    public void onLog(String msg) {
        state(msg);
    }

    @Override
    public void onAccept(PeerClient p) {
        state(p.host + " 接受文件请求，申请上传槽");
    }

    @Override
    public void onIdle(PeerClient p) {
        if (!running || part == null) return;
        if (!p.isAlive() || !p.isSlot()) return;
        int b = claimNext();
        if (b < 0) return;
        try {
            p.requestBlocks(new int[]{b}, new long[]{part.startOf(b)}, new int[]{part.lenOf(b)});
        } catch (Exception e) {
            part.release(b);
            p.close("发送请求失败：" + e.getMessage());
        }
    }

    @Override
    public void onPart(PeerClient p, long start, byte[] data) {
        if (part == null) return;
        int block = (int) (start / Ed2kProto.BLOCK);
        try {
            part.writeBlock(block, start, data);
            totalDownloaded += data.length;
            // chunk 完成时校验一次（有 hashset 才能做）
            int chunk = (int) (start / Ed2kHash.CHUNK);
            if (isChunkComplete(chunk) && chunk < chunkHashes.size()) {
                String actual = Ed2kHash.hashChunk(part.file, chunk);
                String expect = chunkHashes.get(chunk);
                if (!expect.equalsIgnoreCase(actual)) {
                    state("chunk " + chunk + " 校验失败，丢弃重下（AICH 恢复入口）");
                    part.invalidateChunk(chunk);
                    synchronized (peers) {
                        for (PeerClient q : peers) if (q.isAlive()) q.releaseSlot();
                    }
                }
            }
            if (part.isComplete() && !verifying) finishVerify();
        } catch (Exception e) {
            state("写入块失败：" + e.getMessage());
        }
    }

    private boolean isChunkComplete(int chunk) {
        long from = (long) chunk * Ed2kHash.CHUNK;
        long to = Math.min(size, from + Ed2kHash.CHUNK);
        int bFrom = (int) (from / Ed2kProto.BLOCK);
        int bTo = (int) ((to + Ed2kProto.BLOCK - 1) / Ed2kProto.BLOCK);
        for (int b = bFrom; b < bTo && b < part.blockCount; b++) if (!part.isDone(b)) return false;
        return true;
    }

    @Override
    public void onHashset(PeerClient p, List<String> hashes) {
        if (hashes.isEmpty()) return;
        chunkHashes = hashes;
        state("收到 hashset：" + hashes.size() + " 个 chunk 哈希");
    }

    @Override
    public void onDead(PeerClient p, String reason) {
        state(p.host + " 断开：" + reason);
    }

    private void finishVerify() {
        if (verifying) return;
        verifying = true;
        state("下载完成，校验 ed2k hash…");
        new Thread(() -> {
            try {
                String actual = Ed2kHash.hashFile(part.file);
                if (!hashHex.equalsIgnoreCase(actual)) {
                    state("整文件 hash 不符（期望 " + hashHex + " 实际 " + actual + "），按 chunk 标脏重下");
                    for (int i = 0; i < part.chunkCount; i++) part.invalidateChunk(i);
                    part.saveState();
                    hashsetAsked = false;
                    chunkHashes.clear();
                    verifying = false;
                    synchronized (peers) {
                        for (PeerClient q : peers) if (q.isAlive()) q.releaseSlot();
                    }
                    return;
                }
                File out = part.finish(name);
                part.saveState();
                running = false;
                final Listener l = listener;
                if (l != null) ui.post(() -> l.onDone(true, "ed2k 校验通过：" + out.getName(), out));
                state("完成并已校验：" + out.getAbsolutePath());
                Ed2kUploader.instance(ctx).addShared(out);
            } catch (Exception e) {
                verifying = false;
                fail("校验异常：" + e.getMessage());
            }
        }, "ed2k-verify").start();
    }

    private void fail(String msg) {
        running = false;
        state(msg);
        final Listener l = listener;
        if (l != null) ui.post(() -> l.onDone(false, msg, part == null ? null : part.file));
    }

    private void state(String s) {
        LogStore.add(ctx, "ed2k", s);
        final Listener l = listener;
        if (l != null) ui.post(() -> l.onState(s));
    }
}
