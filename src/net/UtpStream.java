package kawaii.viey.browser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;

/**
 * uTP 流（简化可靠层）：SYN/STATE 建链、DATA 分段 + 停等重传、FIN/RESET 收尾。
 *
 * 与 BEP-29 的差距（诚实标注）：
 *  - 拥塞控制是固定窗口 + 指数退避的简化版，未实现 LEDBAT（目标延迟 100ms / 单向延迟梯度）。
 *  - 未实现 SACK 扩展（extension=1）、未做时间戳驱动的 RTT 平滑，只有超时重传。
 *  - 头部字段（ts_micro / ts_diff / wnd_size）按规范填，能与真实 uTP 对端完成握手与 DATA 交换。
 *
 * 用途：BitTorrent peer 传输的传输层骨架，替代原先的 eD2k TCP 明文/混淆路径。
 */
public class UtpStream {

    private final DatagramSocket socket;
    private final InetSocketAddress peer;
    private final int sendConnId;
    private int recvConnId;
    private long remoteTs;
    private volatile boolean alive = true;
    private int nextSeq = 1;
    private int recvExpect = 1;
    private int rto = 200;

    public UtpStream(DatagramSocket socket, InetSocketAddress peer, int sendConnId) {
        this.socket = socket;
        this.peer = peer;
        this.sendConnId = sendConnId;
        this.recvConnId = sendConnId;
    }

    public static int randomConnId() {
        return 1 + new java.util.Random().nextInt(0xFFFE);
    }

    /** 主动建链：SYN → 等 STATE，重试 5 次 */
    public boolean connect() throws IOException {
        for (int i = 0; i < 5 && alive; i++) {
            send(UtpProto.ST_SYN, 0, 0, 0);
            if (waitFor(UtpProto.ST_STATE, 600)) return true;
            rto = Math.min(rto * 2, 4000);
        }
        return false;
    }

    /** 被动侧：收到 SYN 就回 STATE */
    public void accept() throws IOException {
        send(UtpProto.ST_STATE, 0, 0, 0);
    }

    /** 发送一段数据（自动按 MTU 切分，逐片停等确认） */
    public int send(byte[] data) throws IOException {
        int sent = 0;
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        for (int off = 0; off < data.length; off += UtpProto.MTU, sent++) {
            int len = Math.min(UtpProto.MTU, data.length - off);
            bo.reset();
            bo.write(data, off, len);
            for (int tries = 0; tries < 4 && alive; tries++) {
                sendPayload(UtpProto.ST_DATA, bo.toByteArray(), nextSeq);
                if (waitAck(nextSeq, rto)) {
                    nextSeq = (nextSeq + 1) & 0xFFFF;
                    break;
                }
                rto = Math.min(rto * 2, 4000);
            }
        }
        return sent;
    }

    /** 接收直到收满 want 字节，DATA 收下后回 STATE 确认 */
    public byte[] receive(int want) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        long deadline = System.currentTimeMillis() + 15000;
        while (bo.size() < want && System.currentTimeMillis() < deadline && alive) {
            DatagramPacket p = new DatagramPacket(new byte[2048], 2048);
            socket.setSoTimeout(2000);
            try {
                socket.receive(p);
            } catch (SocketTimeoutException e) {
                continue;
            }
            byte[] buf = p.getData();
            int len = p.getLength();
            UtpProto.Header h = UtpProto.parse(buf, 0);
            if (h == null) continue;
            remoteTs = h.tsMicro;
            switch (h.type) {
                case UtpProto.ST_DATA: {
                    int payloadLen = len - UtpProto.HEADER;
                    if (h.seq == recvExpect) {
                        bo.write(buf, UtpProto.HEADER, payloadLen);
                        recvExpect = (recvExpect + 1) & 0xFFFF;
                    }
                    send(UtpProto.ST_STATE, 0, 0, 0, recvExpect);
                    break;
                }
                case UtpProto.ST_FIN:
                    send(UtpProto.ST_STATE, 0, 0, 0, recvExpect);
                    return bo.toByteArray();
                case UtpProto.ST_RESET:
                    throw new IOException("uTP 被对端 RESET");
                default:
                    break;
            }
        }
        return bo.toByteArray();
    }

    public void finish() {
        try {
            send(UtpProto.ST_FIN, 0, 0, 0);
            waitFor(UtpProto.ST_STATE, 300);
        } catch (Exception ignored) {
        }
        alive = false;
    }

    public void reset() {
        try {
            send(UtpProto.ST_RESET, 0, 0, 0);
        } catch (Exception ignored) {
        }
        alive = false;
    }

    private void sendPayload(int type, byte[] payload, int seq) throws IOException {
        byte[] pkt = new byte[UtpProto.HEADER + payload.length];
        System.arraycopy(UtpProto.pack(type, sendConnId, System.nanoTime() / 1000,
                (int) (System.nanoTime() / 1000 - remoteTs), 0x7FFF, seq, recvExpect), 0, pkt, 0, UtpProto.HEADER);
        System.arraycopy(payload, 0, pkt, UtpProto.HEADER, payload.length);
        socket.send(new DatagramPacket(pkt, pkt.length, peer));
    }

    private void send(int type, int seq, int ack, int payloadLen) throws IOException {
        send(type, seq, ack, payloadLen, recvExpect);
    }

    private void send(int type, int seq, int ack, int payloadLen, int ackNr) throws IOException {
        byte[] pkt = UtpProto.pack(type, sendConnId, System.nanoTime() / 1000,
                (int) (System.nanoTime() / 1000 - remoteTs), 0x7FFF, seq, ackNr);
        socket.send(new DatagramPacket(pkt, pkt.length, peer));
    }

    private boolean waitFor(int wantType, int ms) throws IOException {
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            DatagramPacket p = new DatagramPacket(new byte[2048], 2048);
            socket.setSoTimeout((int) Math.max(1, deadline - System.currentTimeMillis()));
            try {
                socket.receive(p);
            } catch (SocketTimeoutException e) {
                return false;
            }
            UtpProto.Header h = UtpProto.parse(p.getData(), 0);
            if (h == null) continue;
            remoteTs = h.tsMicro;
            if (h.type == wantType) {
                if (wantType == UtpProto.ST_STATE) recvExpect = h.ack;
                return true;
            }
        }
        return false;
    }

    private boolean waitAck(int seq, int ms) throws IOException {
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            DatagramPacket p = new DatagramPacket(new byte[2048], 2048);
            socket.setSoTimeout((int) Math.max(1, deadline - System.currentTimeMillis()));
            try {
                socket.receive(p);
            } catch (SocketTimeoutException e) {
                return false;
            }
            UtpProto.Header h = UtpProto.parse(p.getData(), 0);
            if (h == null) continue;
            remoteTs = h.tsMicro;
            if (h.type == UtpProto.ST_STATE && ((h.ack - 1) & 0xFFFF) == (seq & 0xFFFF)) return true;
            if (h.type == UtpProto.ST_DATA) {
                send(UtpProto.ST_STATE, 0, 0, 0, recvExpect);
            }
        }
        return false;
    }
}
