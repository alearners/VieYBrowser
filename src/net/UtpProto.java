package kawaii.viey.browser;

/**
 * uTP（BEP-29，BitTorrent 的 UDP 传输）报文头编解码。
 *
 * 头部 20 字节：
 *   [0]      htype<<4 | version   （htype=1，version=1）
 *   [1]      type<<4  | extension

 *   [2..3]   connection_id
 *   [4..7]   timestamp_microseconds（发送方微秒时钟）
 *   [8..11]  timestamp_difference（与对端时钟差，有符号）
 *   [12..15] window_size（接收窗口，字节）
 *   [16..17] sequence_number
 *   [18..19] acknowledge_number
 *
 * type：0 DATA / 1 FIN / 2 STATE(ACK) / 3 RESET / 4 SYN
 * 注意：uTP 只属于 BitTorrent 一侧，eD2k 没有这个传输层。
 */
public class UtpProto {

    public static final int ST_DATA = 0;
    public static final int ST_FIN = 1;
    public static final int ST_STATE = 2;
    public static final int ST_RESET = 3;
    public static final int ST_SYN = 4;

    public static final int HEADER = 20;
    public static final int MTU = 1400;

    public static class Header {
        public int type;
        public int extension;
        public int connId;
        public long tsMicro;
        public int tsDiff;
        public int wndSize;
        public int seq;
        public int ack;
    }

    public static byte[] pack(int type, int connId, long tsMicro, int tsDiff, int wnd, int seq, int ack) {
        byte[] b = new byte[HEADER];
        b[0] = (byte) ((1 << 4) | 1);
        b[1] = (byte) ((type << 4) | 0);
        put16(b, 2, connId);
        put32(b, 4, tsMicro & 0xFFFFFFFFL);
        put32(b, 8, tsDiff & 0xFFFFFFFFL);
        put32(b, 12, wnd);
        put16(b, 16, seq);
        put16(b, 18, ack);
        return b;
    }

    public static Header parse(byte[] b, int off) {
        if (b.length - off < HEADER) return null;
        Header h = new Header();
        int b0 = b[off] & 0xFF;
        int b1 = b[off + 1] & 0xFF;
        h.type = b1 >>> 4;
        h.extension = b1 & 0x0F;
        h.connId = (int) get16(b, off + 2);
        h.tsMicro = get32(b, off + 4);
        h.tsDiff = (int) get32(b, off + 8);
        h.wndSize = (int) get32(b, off + 12);
        h.seq = (int) get16(b, off + 16);
        h.ack = (int) get16(b, off + 18);
        // htype/version 校验：非 1/1 视为未知报文
        if ((b0 >>> 4) != 1) h.type = -1;
        return h;
    }

    public static boolean isUtp(byte[] buf, int off, int len) {
        if (len < HEADER) return false;
        return ((buf[off] & 0xFF) >>> 4) == 1;
    }

    static void put16(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >>> 8) & 0xFF);
    }

    static void put32(byte[] b, int off, long v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >>> 8) & 0xFF);
        b[off + 2] = (byte) ((v >>> 16) & 0xFF);
        b[off + 3] = (byte) ((v >>> 24) & 0xFF);
    }

    static long get16(byte[] b, int off) {
        return ((b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8));
    }

    static long get32(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24);
    }

    public static String typeName(int t) {
        switch (t) {
            case ST_DATA: return "DATA";
            case ST_FIN: return "FIN";
            case ST_STATE: return "STATE";
            case ST_RESET: return "RESET";
            case ST_SYN: return "SYN";
            default: return "?";
        }
    }
}
