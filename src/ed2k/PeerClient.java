package kawaii.viey.browser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * 单个 eD2k 对端连接（下载方向）：
 * 明文/混淆握手 → FILEREQUEST → SLOT_REQUEST → REQUEST_PARTS，收 SENDING_PART。
 */
public class PeerClient {

    public interface Sink {
        void onLog(String msg);

        void onIdle(PeerClient p);

        void onPart(PeerClient p, long start, byte[] data);

        void onHashset(PeerClient p, List<String> chunkHashes);

        void onDead(PeerClient p, String reason);

        void onAccept(PeerClient p);
    }

    public final String host;
    public final int port;
    public final String hashHex;
    public final long size;
    private final byte[] userHash;
    private final byte[] peerHash;
    private final Sink sink;
    private final boolean allowObf;

    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private volatile boolean alive = true;
    private volatile boolean accepted = false;
    private volatile boolean slot = false;
    private Ed2kProto.Rc4 rc4Send, rc4Recv;
    private int requestedBlock = -1;

    public int downloaded;
    public long connectedAt;

    public PeerClient(String host, int port, String hashHex, long size, byte[] userHash,
                      byte[] peerHash, Sink sink, boolean allowObf) {
        this.host = host;
        this.port = port;
        this.hashHex = hashHex;
        this.size = size;
        this.userHash = userHash;
        this.peerHash = peerHash;
        this.sink = sink;
        this.allowObf = allowObf;
    }

    public void start() {
        new Thread(this::run, "ed2k-peer-" + host).start();
    }

    private void run() {
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 8000);
            socket.setSoTimeout(120000);
            socket.setTcpNoDelay(true);
            connectedAt = System.currentTimeMillis();
            in = socket.getInputStream();
            out = socket.getOutputStream();

            boolean obf = allowObf && peerHash != null && peerHash.length == 16;
            if (obf) {
                try {
                    Ed2kProto.Rc4[] keys = Ed2kProto.clientHandshake(in, out, peerHash);
                    rc4Send = keys[0];
                    rc4Recv = keys[1];
                    sink.onLog("与 " + host + ":" + port + " 混淆握手完成");
                } catch (Exception e) {
                    rc4Send = rc4Recv = null;
                    sink.onLog("混淆握手失败，退回明文：" + e.getMessage());
                }
            }

            sendHello();
            sendFileRequest();
            loop();
        } catch (Exception e) {
            if (alive) sink.onLog("连接 " + host + ":" + port + " 失败：" + e.getClass().getSimpleName() + " " + e.getMessage());
        } finally {
            close("结束");
        }
    }

    /** HELLO: 0x10 + userhash + clientid + port + tagcount + ([我的IP][端口]) */
    private void sendHello() throws IOException {
        byte[] payload = Ed2kProto.cat(
                new byte[]{0x10}, userHash,
                Ed2kProto.le32(0),
                Ed2kProto.le16(0x1236),
                Ed2kProto.le32(0),
                Ed2kProto.le32(0),
                Ed2kProto.le16(0x1236));
        send(Ed2kProto.PROT_EDONKEY, Ed2kProto.C_HELLO, payload);
    }

    private void sendFileRequest() throws IOException {
        send(Ed2kProto.PROT_EDONKEY, Ed2kProto.C_FILE_REQUEST, Ed2kProto.hexToBytes(hashHex));
    }

    public void requestHashset() {
        try {
            send(Ed2kProto.PROT_EDONKEY, Ed2kProto.C_HASHSET_REQUEST, Ed2kProto.hexToBytes(hashHex));
        } catch (Exception e) {
            sink.onLog("请求 hashset 失败：" + e.getMessage());
        }
    }

    /** 请求一个块（长度 ≤ 180KB） */
    public void requestBlocks(int[] blocks, long[] starts, int[] lens) throws IOException {
        if (!slot) {
            sink.onLog(host + " 还没给上传槽，稍后重试");
            return;
        }
        byte[] hash = Ed2kProto.hexToBytes(hashHex);
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        bo.write(hash);
        for (long s : starts) bo.write(Ed2kProto.le32(s));
        for (int i = 0; i < starts.length; i++) bo.write(Ed2kProto.le32(starts[i] + lens[i] - 1));
        if (starts.length > 0) requestedBlock = blocks[0];
        send(Ed2kProto.PROT_EDONKEY, Ed2kProto.C_REQUEST_PARTS, bo.toByteArray());
    }

    private void loop() throws IOException {
        while (alive) {
            Ed2kProto.Packet p = Ed2kProto.readPacket(in, rc4Recv);
            if (p.prot == Ed2kProto.PROT_COMPRESSED) {
                for (Ed2kProto.Packet inner : Ed2kProto.decompress(p.payload)) handle(inner);
                continue;
            }
            handle(p);
        }
    }

    private void handle(Ed2kProto.Packet p) throws IOException {
        switch (p.op) {
            case Ed2kProto.C_HELLO_ANSWER:
                sink.onLog("对端 " + host + " 握手应答");
                break;
            case Ed2kProto.C_FILE_REQUEST_ANSWER:
                if (!accepted) {
                    accepted = true;
                    sink.onAccept(this);
                    sendSlotRequest();
                }
                break;
            case Ed2kProto.C_SLOT_GIVEN:
                slot = true;
                sink.onLog(host + " 已分配上传槽");
                sink.onIdle(this);
                break;
            case Ed2kProto.C_SENDING_PART: {
                parsePart(p.payload);
                break;
            }
            case Ed2kProto.C_NO_SUCH_FILE:
                sink.onLog(host + " 没有这个文件（或已改名）");
                close("对方无此文件");
                break;
            case EPM_QUEUE_FULL:
                sink.onLog(host + " 上传队列已满");
                break;
            case Ed2kProto.C_HASHSET_ANSWER: {
                parseHashset(p.payload);
                break;
            }
            case Ed2kProto.EM_AICH_ANSWER:
                sink.onLog("收到 AICH 应答 " + p.payload.length + " 字节");
                break;
            case Ed2kProto.C_CLIENT_MESSAGE:
                sink.onLog("对端消息：" + Ed2kProto.str(p.payload, 0));
                break;
            case Ed2kProto.C_END_OF_DOWNLOAD:
                close("对端结束传输");
                break;
            default:
                break;
        }
    }

    private static final int EPM_QUEUE_FULL = 0x93;

    private void parsePart(byte[] payload) throws IOException {
        if (payload.length < 24) return;
        byte[] hash = new byte[16];
        System.arraycopy(payload, 0, hash, 0, 16);
        long start = Ed2kProto.u32(payload, 16);
        long end = Ed2kProto.u32(payload, 20);
        int len = (int) (end - start + 1);
        if (len <= 0 || 24 + len > payload.length) return;
        byte[] data = new byte[len];
        System.arraycopy(payload, 24, data, 0, len);
        downloaded += len;
        requestedBlock = -1;
        sink.onPart(this, start, data);
        sink.onIdle(this);
    }

    private void parseHashset(byte[] payload) {
        if (payload.length < 18) return;
        int count = Ed2kProto.u16(payload, 16);
        List<String> list = new ArrayList<>();
        int off = 18;
        for (int i = 0; i < count && off + 16 <= payload.length; i++, off += 16) {
            byte[] h = new byte[16];
            System.arraycopy(payload, off, h, 0, 16);
            list.add(Ed2kProto.bytesToHex(h));
        }
        sink.onHashset(this, list);
    }

    private void sendSlotRequest() throws IOException {
        send(Ed2kProto.PROT_EDONKEY, Ed2kProto.C_SLOT_REQUEST, Ed2kProto.hexToBytes(hashHex));
    }

    public void releaseSlot() {
        try {
            send(Ed2kProto.PROT_EDONKEY, Ed2kProto.C_SLOT_RELEASE, Ed2kProto.hexToBytes(hashHex));
        } catch (Exception ignored) {
        }
    }

    public boolean isSlot() {
        return slot;
    }

    public int requestedBlock() {
        return requestedBlock;
    }

    private synchronized void send(int prot, int op, byte[] payload) throws IOException {
        if (out == null) throw new IOException("连接已关闭");
        byte[] pkt = Ed2kProto.pack(prot, op, payload);
        if (rc4Send != null) pkt = rc4Send.crypt(pkt);
        out.write(pkt);
        out.flush();
    }

    public void close(String reason) {
        if (!alive) return;
        alive = false;
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {
        }
        sink.onDead(this, reason);
    }

    public boolean isAlive() {
        return alive;
    }
}
