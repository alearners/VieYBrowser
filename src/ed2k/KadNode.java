package kawaii.viey.browser;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Kad 骨架：节点表 + 源缓存 + 可选 UDP bootstrap（实验）。
 *
 * 说明（诚实标注）：
 * - 真正的 Kad 互操作需要 Kademlia2 报文（0xE4 头、128 位 node id、桶路由、关键字发布/搜索），
 *   这里已做节点表持久化、bootstrap 发包与源缓存，尚未做完整的 128-bit 桶与发布/搜索语义。
 * - doSearch 目前只查本地缓存（由服务器源、对端源交换写入），开关默认关闭。
 */
public class KadNode {

    private static KadNode inst;

    public static synchronized KadNode instance(Context c) {
        if (inst == null) inst = new KadNode(c.getApplicationContext());
        return inst;
    }

    private final Context ctx;
    private final Map<String, List<String>> sourceCache = new LinkedHashMap<>();
    private final List<String> nodes = new ArrayList<>();
    private DatagramSocket udp;
    private volatile boolean running;

    private KadNode(Context c) {
        this.ctx = c;
        load();
    }

    public boolean isReady() {
        return !sourceCache.isEmpty();
    }

    public int nodeCount() {
        synchronized (nodes) {
            return nodes.size();
        }
    }

    private File cacheFile() {
        return new File(ctx.getFilesDir(), "kad_cache.json");
    }

    private synchronized void load() {
        try {
            File f = cacheFile();
            if (!f.exists()) return;
            JSONObject o = new JSONObject(PluginStore.read(f));
            JSONObject src = o.optJSONObject("sources");
            if (src != null) {
                java.util.Iterator<String> it = src.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    JSONArray arr = src.getJSONArray(k);
                    List<String> list = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) list.add(arr.getString(i));
                    sourceCache.put(k, list);
                }
            }
            JSONArray ns = o.optJSONArray("nodes");
            if (ns != null) for (int i = 0; i < ns.length(); i++) nodes.add(ns.getString(i));
        } catch (Exception ignored) {
        }
    }

    public synchronized void save() {
        try {
            JSONObject o = new JSONObject();
            JSONObject src = new JSONObject();
            for (Map.Entry<String, List<String>> e : sourceCache.entrySet()) {
                JSONArray arr = new JSONArray();
                for (String s : e.getValue()) arr.put(s);
                src.put(e.getKey(), arr);
            }
            o.put("sources", src);
            JSONArray ns = new JSONArray();
            synchronized (nodes) {
                for (String s : nodes) ns.put(s);
            }
            o.put("nodes", ns);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(cacheFile());
            fos.write(o.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Exception ignored) {
        }
    }

    /** 记录一个文件的已知源（来自服务器/对端源交换） */
    public synchronized void rememberSources(String hashHex, List<String> hpList) {
        if (hashHex == null || hpList == null || hpList.isEmpty()) return;
        List<String> list = sourceCache.get(hashHex.toLowerCase());
        if (list == null) list = new ArrayList<>();
        for (String hp : hpList) if (!list.contains(hp)) list.add(hp);
        sourceCache.put(hashHex.toLowerCase(), list);
        save();
    }

    public synchronized List<String> findSources(String hashHex, long size) {
        List<String> list = sourceCache.get(hashHex.toLowerCase());
        return list == null ? new ArrayList<>() : new ArrayList<>(list);
    }

    /** 可选 UDP bootstrap：向节点表发送 0xE4 头探测包，收到应答就把节点记下（实验性） */
    public void startUdp() {
        if (running || !VieYApp.getPrefs(ctx).getBoolean("ed2k_kad", false)) return;
        running = true;
        new Thread(() -> {
            try {
                udp = new DatagramSocket();
                udp.setSoTimeout(3000);
                byte[] id = new byte[16];
                new java.util.Random().nextBytes(id);
                byte[] probe = new byte[18];
                probe[0] = (byte) 0xE4;
                probe[1] = 0x01;
                System.arraycopy(id, 0, probe, 2, 16);
                List<String> snapshot;
                synchronized (nodes) {
                    snapshot = new ArrayList<>(nodes);
                }
                for (String hp : snapshot) {
                    try {
                        int i = hp.lastIndexOf(':');
                        InetAddress addr = InetAddress.getByName(hp.substring(0, i));
                        DatagramPacket dp = new DatagramPacket(probe, probe.length, addr,
                                Integer.parseInt(hp.substring(i + 1)));
                        udp.send(dp);
                        LogStore.add(ctx, "kad", "bootstrap 探测 " + hp);
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception e) {
                LogStore.add(ctx, "kad", "UDP 启动失败：" + e.getMessage());
            }
        }, "kad-udp").start();
    }

    public void stopUdp() {
        running = false;
        try {
            if (udp != null) udp.close();
        } catch (Exception ignored) {
        }
    }
}
