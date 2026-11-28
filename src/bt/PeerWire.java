package kawaii.viey.browser;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * BitTorrent peer wire 协议（BEP-3 基础集 + BEP-10 扩展 + BEP-9 ut_metadata）。
 * 只做「下别人的数据」：handshake → interested → unchoke → request/piece。
 */
public class PeerWire implements Closeable {

    private static final String TAG = "viey-bt";
    private static final int BLOCK = 16384;

    private final String host;
    private final int port;
    private final byte[] infoHash;
    private final String peerId;
    private Socket sock;
    private DataInputStream in;
    private DataOutputStream out;

    private int peerMetaId = -1;
    private long metaSize = 0;
    public boolean supportsMetadata = false;

    public PeerWire(String host, int port, byte[] infoHash, String peerId) {
        this.host = host;
        this.port = port;
        this.infoHash = infoHash;
        this.peerId = peerId;
    }

    public void connect() throws Exception {
        sock = new Socket();
        sock.connect(new InetSocketAddress(host, port), 8000);
        sock.setSoTimeout(25000);
        in = new DataInputStream(sock.getInputStream());
        out = new DataOutputStream(sock.getOutputStream());
        // handshake
        byte[] hs = new byte[68];
        hs[0] = 19;
        System.arraycopy("BitTorrent protocol".getBytes(), 0, hs, 1, 19);
        // 扩展位：0x00 0x00 0x00 0x00 0x10 0x00 0x00 0x00（第 5 字节 bit4 = 扩展）
        hs[25] = 0x10;
        System.arraycopy(infoHash, 0, hs, 28, 20);
        System.arraycopy(peerId.getBytes(), 0, hs, 48, 20);
        out.write(hs);
        out.flush();
        byte[] rh = new byte[68];
        int got = 0;
        while (got < 68) {
            int r = in.read(rh, got, 68 - got);
            if (r <= 0) throw new Exception("handshake 中断");
            got += r;
        }
        if (rh[0] != 19) throw new Exception("对端不是 BT 协议");
        for (int i = 0; i < 20; i++) {
            if (rh[28 + i] != infoHash[i]) throw new Exception("info_hash 不匹配");
        }
        supportsMetadata = (rh[25] & 0x10) != 0;
    }

    private void sendRaw(int id, byte[] payload) throws Exception {
        int len = 1 + (payload == null ? 0 : payload.length);
        out.writeInt(len);
        out.writeByte(id);
        if (payload != null) out.write(payload);
        out.flush();
    }

    private static class Msg {
        int id;
        byte[] payload;
    }

    private Msg read() throws Exception {
        int len = in.readInt();
        if (len == 0) {
            Msg m = new Msg();
            m.id = -1;
            m.payload = new byte[0];
            return m;
        }
        Msg m = new Msg();
        m.id = in.readByte();
        m.payload = new byte[len - 1];
        int got = 0;
        while (got < m.payload.length) {
            int r = in.read(m.payload, got, m.payload.length - got);
            if (r <= 0) throw new Exception("连接断开");
            got += r;
        }
        return m;
    }

    /** 通过 BEP-9 拉取 info 段（magnet 场景），校验 SHA-1 == info_hash */
    public byte[] fetchMetadata() throws Exception {
        if (!supportsMetadata) throw new Exception("对端不支持扩展协议");
        // 扩展握手：sub=0，payload b"d1:md11:ut_metadatai1eee"
        byte[] ext = "d1:md11:ut_metadatai1ee13:metadata_sizei0eee".getBytes();
        sendRaw(20, concat(new byte[]{0}, ext));

        long deadline = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < deadline && peerMetaId < 0) {
            Msg m = read();
            if (m.id == 20 && m.payload.length > 0 && m.payload[0] == 0) {
                Map<String, Object> o = asMap(bencodeOf(m.payload, 1));
                Object mm = o.get("m");
                if (mm instanceof Map) {
                    Object id = ((Map<?, ?>) mm).get("ut_metadata");
                    if (id != null) peerMetaId = (int) Bencode.num(id);
                }
                metaSize = Bencode.num(o.get("metadata_size"));
                Log.i(TAG, "扩展握手 ut_metadata=" + peerMetaId + " size=" + metaSize);
            }
        }
        if (peerMetaId < 0 || metaSize <= 0) throw new Exception("对端未提供 metadata");
        if (metaSize > 4 * 1024 * 1024) throw new Exception("metadata 过大");

        List<byte[]> parts = new ArrayList<>();
        int count = (int) ((metaSize + BLOCK - 1) / BLOCK);
        for (int i = 0; i < count; i++) {
            byte[] pieceBytes = fetchMetaPiece(i);
            parts.add(pieceBytes);
        }
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        for (byte[] p : parts) bo.write(p);
        byte[] meta = bo.toByteArray();
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] got = md.digest(meta);
        for (int i = 0; i < 20; i++) if (got[i] != infoHash[i]) throw new Exception("metadata SHA-1 不匹配");
        return meta;
    }

    private byte[] fetchMetaPiece(int index) throws Exception {
        byte[] req = Bencode.encode(map("msg_type", 0L, "piece", (long) index));
        byte[] payload = new byte[req.length + 1];
        payload[0] = (byte) peerMetaId;
        System.arraycopy(req, 0, payload, 1, req.length);
        sendRaw(20, payload);

        long deadline = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < deadline) {
            Msg m = read();
            if (m.id == 20 && m.payload.length > 1 && m.payload[0] == 1) {
                // dictEnd 返回的是"dict 之后第一个字节"的绝对偏移（已吃掉结尾 e）
                int headEnd = dictEnd(m.payload, 1);
                if (headEnd <= 0 || headEnd >= m.payload.length) continue;
                Map<String, Object> o = asMap(slice(m.payload, 1, headEnd));
                long mt = Bencode.num(o.get("msg_type"));
                long pc = Bencode.num(o.get("piece"));
                if (pc != index) continue;
                if (mt != 1) throw new Exception("metadata 请求被拒");
                byte[] body = slice(m.payload, headEnd, m.payload.length);
                if (body.length == 0) throw new Exception("metadata 片空");
                return body;
            }
        }
        throw new Exception("metadata 片超时");
    }

    /** 下载整片：自动 request 16KB 块并拼接 */
    /** 声明自己没有任一片（全 0 bitfield）。不少客户端要看到 bitfield 才会给我们 unchoke */
    public void sendBitfield(int pieceCount) throws Exception {
        sendRaw(5, new byte[(pieceCount + 7) / 8]);
    }

    public byte[] downloadPiece(int index, long pieceLen) throws Exception {
        sendRaw(2, null); // interested
        // 连接刚建立时对方默认是 choke 状态、会先发一条 id=0，那是状态同步不是拒绝。
        // 老代码把它当拒绝直接抛，导致永远拿不到数据。
        long deadline = System.currentTimeMillis() + 30000;
        boolean unchoked = false;
        while (System.currentTimeMillis() < deadline && !unchoked) {
            Msg m = read();
            if (m.id == 1) unchoked = true;
            else if (m.id == 0 || m.id == 6 || m.id == 4 || m.id == 5 || m.id == -1) continue;
        }
        if (!unchoked) throw new Exception("对端未 unchoke");

        byte[] buf = new byte[(int) pieceLen];
        boolean[] got = new boolean[(int) ((pieceLen + BLOCK - 1) / BLOCK)];
        int need = 0;
        for (int begin = 0; begin < pieceLen; begin += BLOCK) need++;
        // 先发前 8 个 request（流水），其余按需补
        int nextBegin = 0;
        int inflight = 0;
        int maxInflight = 8;
        while (need > 0 && System.currentTimeMillis() < deadline + 60000) {
            while (inflight < maxInflight && nextBegin < pieceLen) {
                int len = (int) Math.min(BLOCK, pieceLen - nextBegin);
                request(index, nextBegin, len);
                nextBegin += len;
                inflight++;
            }
            Msg m = read();
            if (m.id == 7) {
                int idx = readInt(m.payload, 0);
                int begin = readInt(m.payload, 4);
                int len = m.payload.length - 8;
                if (idx != index) continue;
                System.arraycopy(m.payload, 8, buf, begin, len);
                int slot = begin / BLOCK;
                if (slot < got.length && !got[slot]) {
                    got[slot] = true;
                    need--;
                }
                inflight--;
            } else if (m.id == 0) {
                throw new Exception("对端 choke");
            }
        }
        if (need > 0) throw new Exception("分片不完整，缺 " + need + " 块");
        return buf;
    }

    private void request(int index, int begin, int len) throws Exception {
        sendRaw(6, reqPayload(index, begin, len));
    }

    /**
     * BEP-3 request payload：index / begin / length 各 4 字节大端，共 12 字节。
     * 老代码写成了 3 字节（int3），对端按 12 字节解析必然错位并 RST 连接，
     * 表现就是每一片都 "Broken pipe"、进度永远 0。
     */
    static byte[] reqPayload(int index, int begin, int len) {
        byte[] p = new byte[12];
        putInt(p, 0, index);
        putInt(p, 4, begin);
        putInt(p, 8, len);
        return p;
    }

    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) (v >> 24);
        b[off + 1] = (byte) (v >> 16);
        b[off + 2] = (byte) (v >> 8);
        b[off + 3] = (byte) v;
    }

    private static int readInt(byte[] b, int off) {
        return ((b[off] & 0xff) << 24) | ((b[off + 1] & 0xff) << 16) | ((b[off + 2] & 0xff) << 8) | (b[off + 3] & 0xff);
    }

    // ---------- 小工具 ----------

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private static byte[] slice(byte[] b, int s, int e) {
        byte[] r = new byte[e - s];
        System.arraycopy(b, s, r, 0, r.length);
        return r;
    }

    private static byte[] bencodeOf(byte[] payload, int from) {
        return slice(payload, from, dictEnd(payload, from));
    }

    /** 从 from 开始找到一个完整 bencode 字典的结束位置（含 'e'） */
    static int dictEnd(byte[] p, int from) {
        int depth = 0;
        int i = from;
        while (i < p.length) {
            byte c = p[i];
            if (c == 'd' || c == 'l') {
                depth++;
                i++;
            } else if (c == 'e') {
                depth--;
                i++;
                if (depth == 0) return i;
            } else if (c == 'i') {
                while (i < p.length && p[i] != 'e') i++;
                i++;
            } else if (c >= '0' && c <= '9') {
                int len = 0;
                while (i < p.length && p[i] != ':') {
                    len = len * 10 + (p[i] - '0');
                    i++;
                }
                i++; // ':'
                i += len;
            } else {
                return -1;
            }
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(byte[] bencoded) throws Exception {
        return (Map<String, Object>) Bencode.decode(bencoded);
    }

    private static Map<String, Object> map(String k1, Object v1, String k2, Object v2) {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }

    @Override
    public void close() {
        try {
            if (sock != null) sock.close();
        } catch (Exception ignored) {
        }
    }
}
