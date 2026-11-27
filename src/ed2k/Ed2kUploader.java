package kawaii.viey.browser;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 上传队列 + 入站对端服务（端口默认 4662）。
 * 支持明文与 server 侧混淆握手（需要我们的 user hash，eMule 的入站混淆正是用接收方 hash）。
 */
public class Ed2kUploader {

    private static Ed2kUploader inst;
    private static final Object LOCK = new Object();

    public static synchronized Ed2kUploader instance(Context c) {
        if (inst == null) inst = new Ed2kUploader(c.getApplicationContext());
        return inst;
    }

    private final Context ctx;
    private ServerSocket server;
    private volatile boolean running = false;
    private int port = 4662;
    private final List<Conn> active = new ArrayList<>();
    private final List<Conn> waiting = new ArrayList<>();
    private final Map<String, Integer> servedCount = new HashMap<>();
    private long uploadedBytes = 0;

    public Ed2kUploader(Context c) {
        this.ctx = c;
    }

    public boolean isRunning() {
        return running;
    }

    public int servedCount() {
        synchronized (active) {
            int n = 0;
            for (Conn c : active) n += c.blocksServed;
            return n;
        }
    }

    public long uploadedBytes() {
        return uploadedBytes;
    }

    public void start() {
        if (running) return;
        port = VieYApp.getPrefs(ctx).getInt("ed2k_port", 4662);
        new Thread(() -> {
            try {
                server = new ServerSocket();
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(port));
                running = true;
                LogStore.add(ctx, "ed2k-up", "上传服务已启动 :" + port);
                SharedFiles.scan(ctx);
                while (running) {
                    Socket s = server.accept();
                    Conn conn = new Conn(s);
                    new Thread(conn, "ed2k-up-conn").start();
                }
            } catch (Exception e) {
                running = false;
                LogStore.add(ctx, "ed2k-up", "上传服务启动失败：" + e.getMessage());
            }
        }, "ed2k-up").start();
    }

    public void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
        }
        synchronized (active) {
            for (Conn c : active) c.close("服务停止");
            active.clear();
        }
        LogStore.add(ctx, "ed2k-up", "上传服务已停止");
    }

    public void addShared(File f) {
        SharedFiles.add(ctx, f);
    }

    private int maxSlots() {
        return VieYApp.getPrefs(ctx).getInt("ed2k_upload_slots", 2);
    }

    /** 入尝试占一个槽，返回 true 表示立刻可传，false 进等待队列 */
    boolean takeSlot(Conn c) {
        synchronized (active) {
            if (active.size() < maxSlots()) {
                active.add(c);
                return true;
            }
            waiting.add(c);
            return false;
        }
    }

    void releaseSlot(Conn c) {
        synchronized (active) {
            active.remove(c);
            waiting.remove(c);
            if (!waiting.isEmpty() && running && active.size() < maxSlots()) {
                Conn next = waiting.remove(0);
                active.add(next);
                next.slotGranted();
            }
        }
    }

    int queueRank(Conn c) {
        synchronized (active) {
            int i = waiting.indexOf(c);
            return i < 0 ? 0 : i + 1;
        }
    }

    class Conn implements Runnable {
        private final Socket socket;
        private InputStream in;
        private OutputStream out;
        private Ed2kProto.Rc4 send, recv;
        private volatile boolean alive = true;
        private String hashHex = "";
        SharedFiles.Entry file;
        private boolean slot = false;
        int blocksServed = 0;

        Conn(Socket s) {
            this.socket = s;
        }

        @Override
        public void run() {
            try {
                socket.setSoTimeout(180000);
                socket.setTcpNoDelay(true);
                in = new PushbackInputStream(socket.getInputStream(), 1);
                out = socket.getOutputStream();
                if (!handshake()) return;
                LogStore.add(ctx, "ed2k-up", "入站连接 " + socket.getInetAddress().getHostAddress());
                loop();
            } catch (Exception e) {
                // 连接异常静默关闭
            } finally {
                close("结束");
            }
        }

        /** 明文或入站混淆握手 */
        private boolean handshake() throws IOException {
            int first = in.read();
            if (first < 0) return false;
            if (first == Ed2kProto.PROT_EDONKEY || first == Ed2kProto.PROT_EMULE || first == Ed2kProto.PROT_COMPRESSED) {
                ((PushbackInputStream) in).unread(first);
                return true;
            }
            // 混淆：first 为 SemiRandomNotProtocolMarker
            byte[] rand = new byte[4];
            Ed2kProto.readFully(in, rand);
            byte[] ourHash = Ed2kProto.localUserHash(ctx);
            byte[] sendKey = Ed2kProto.md4(Ed2kProto.cat(ourHash, new byte[]{(byte) 0xCB}, rand));
            byte[] recvKey = Ed2kProto.md4(Ed2kProto.cat(ourHash, new byte[]{(byte) 0x22}, rand));
            send = new Ed2kProto.Rc4(sendKey, 1024);
            recv = new Ed2kProto.Rc4(recvKey, 1024);
            byte[] head = new byte[6];
            Ed2kProto.readFully(in, head);
            head = recv.crypt(head);
            int pad = head[5] & 0xFF;
            if (pad > 0) {
                byte[] pb = new byte[pad];
                Ed2kProto.readFully(in, pb);
                recv.crypt(pb);
            }
            int p2 = new java.util.Random().nextInt(8);
            byte[] pb2 = new byte[p2];
            new java.util.Random().nextBytes(pb2);
            byte[] resp = Ed2kProto.cat(
                    new byte[]{(byte) 0xCB, (byte) 0xCB, (byte) 0xCB, (byte) 0xCB, 0x00, (byte) p2}, pb2);
            out.write(send.crypt(resp));
            out.flush();
            LogStore.add(ctx, "ed2k-up", "入站混淆握手完成 " + socket.getInetAddress().getHostAddress());
            return true;
        }

        private void loop() throws IOException {
            while (alive) {
                Ed2kProto.Packet p = Ed2kProto.readPacket(in, recv);
                if (p.prot == Ed2kProto.PROT_COMPRESSED) {
                    for (Ed2kProto.Packet q : Ed2kProto.decompress(p.payload)) handle(q);
                } else {
                    handle(p);
                }
            }
        }

        private void handle(Ed2kProto.Packet p) throws IOException {
            switch (p.op) {
                case Ed2kProto.C_HELLO:
                    sendHelloAnswer();
                    break;
                case Ed2kProto.C_FILE_REQUEST:
                    if (p.payload.length < 16) break;
                    hashHex = Ed2kProto.bytesToHex(p.payload).substring(0, 32);
                    file = SharedFiles.byHash(ctx, hashHex);
                    if (file == null) {
                        send(Ed2kProto.C_NO_SUCH_FILE, Ed2kProto.hexToBytes(hashHex));
                        LogStore.add(ctx, "ed2k-up", "请求的文件未共享：" + hashHex);
                    } else {
                        byte[] payload = Ed2kProto.cat(Ed2kProto.hexToBytes(hashHex),
                                Ed2kProto.le16(file.name.length()), file.name.getBytes("UTF-8"));
                        send(Ed2kProto.C_FILE_REQUEST_ANSWER, payload);
                    }
                    break;
                case Ed2kProto.C_SLOT_REQUEST:
                    if (file == null) {
                        send(Ed2kProto.C_NO_SUCH_FILE, Ed2kProto.hexToBytes(hashHex));
                        break;
                    }
                    if (takeSlot(this)) {
                        slotGranted();
                    } else {
                        LogStore.add(ctx, "ed2k-up", "队列等待（第 " + queueRank(this) + " 位）");
                    }
                    break;
                case Ed2kProto.C_REQUEST_PARTS:
                    serveParts(p.payload);
                    break;
                case Ed2kProto.C_SLOT_RELEASE:
                case Ed2kProto.C_END_OF_DOWNLOAD:
                    alive = false;
                    break;
                default:
                    break;
            }
        }

        void slotGranted() {
            slot = true;
            try {
                send(Ed2kProto.C_SLOT_GIVEN, Ed2kProto.hexToBytes(hashHex));
            } catch (Exception ignored) {
            }
        }

        private void sendHelloAnswer() throws IOException {
            byte[] ourHash = Ed2kProto.localUserHash(ctx);
            byte[] payload = Ed2kProto.cat(
                    ourHash,
                    Ed2kProto.le32(0),
                    Ed2kProto.le16(port),
                    Ed2kProto.le32(0),
                    Ed2kProto.le32(0),
                    Ed2kProto.le16(0));
            send(Ed2kProto.C_HELLO_ANSWER, payload);
        }

        private void serveParts(byte[] payload) throws IOException {
            if (file == null || payload.length < 24) return;
            int pairs = (payload.length - 16) / 8;
            for (int i = 0; i < pairs && alive; i++) {
                long start = Ed2kProto.u32(payload, 16 + i * 4);
                long end = Ed2kProto.u32(payload, 16 + pairs * 4 + i * 4);
                if (end < start) continue;
                long len = end - start + 1;
                if (len > 4 * Ed2kProto.BLOCK) len = 4 * Ed2kProto.BLOCK;
                if (start < 0 || start + len > file.size) continue;
                byte[] data = new byte[(int) len];
                try (FileInputStream fis = new FileInputStream(file.file)) {
                    fis.getChannel().position(start);
                    int got = 0;
                    while (got < data.length) {
                        int n = fis.read(data, got, data.length - got);
                        if (n <= 0) break;
                        got += n;
                    }
                    if (got < data.length) continue;
                }
                byte[] outPayload = Ed2kProto.cat(
                        Ed2kProto.hexToBytes(hashHex),
                        Ed2kProto.le32(start),
                        Ed2kProto.le32(start + len - 1),
                        data);
                send(Ed2kProto.C_SENDING_PART, outPayload);
                blocksServed++;
                uploadedBytes += len;
            }
        }

        private void send(int op, byte[] payload) throws IOException {
            if (!alive || out == null) return;
            byte[] pkt = Ed2kProto.pack(Ed2kProto.PROT_EDONKEY, op, payload);
            if (send != null) pkt = send.crypt(pkt);
            out.write(pkt);
            out.flush();
        }

        void close(String why) {
            alive = false;
            releaseSlot(this);
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }
}
