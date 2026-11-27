package kawaii.viey.browser;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * eD2k / eMule 线协议常量与工具。
 * 常量来源：Wireshark packet-edonkey.h（eMule 0.49a 源码命名）。
 */
public class Ed2kProto {

    // 协议头字节
    public static final int PROT_EDONKEY = 0xE3;
    public static final int PROT_EMULE = 0xC5;
    public static final int PROT_COMPRESSED = 0xD4;

    // 服务器 <-> 客户端
    public static final int SV_LOGIN_REQUEST = 0x01;
    public static final int SV_GET_SERVER_LIST = 0x14;
    public static final int SV_OFFER_FILES = 0x15;
    public static final int SV_SEARCH_REQUEST = 0x16;
    public static final int SV_DISCONNECT = 0x18;
    public static final int SV_GET_SOURCES = 0x19;
    public static final int SV_GET_SOURCES_OBFU = 0x23;
    public static final int SV_SERVER_LIST = 0x32;
    public static final int SV_SEARCH_RESULTS = 0x33;
    public static final int SV_SERVER_STATUS = 0x34;
    public static final int SV_SERVER_MESSAGE = 0x38;
    public static final int SV_ID_CHANGE = 0x40;
    public static final int SV_FOUND_SOURCES = 0x42;
    public static final int SV_FOUND_SOURCES_OBFU = 0x44;

    // 客户端 <-> 客户端
    public static final int C_HELLO = 0x01;
    public static final int C_SENDING_PART = 0x46;
    public static final int C_REQUEST_PARTS = 0x47;
    public static final int C_NO_SUCH_FILE = 0x48;
    public static final int C_END_OF_DOWNLOAD = 0x49;
    public static final int C_VIEW_FILES = 0x4A;
    public static final int C_VIEW_FILES_ANSWER = 0x4B;
    public static final int C_HELLO_ANSWER = 0x4C;
    public static final int C_NEW_CLIENT_ID = 0x4D;
    public static final int C_CLIENT_MESSAGE = 0x4E;
    public static final int C_FILE_STATUS_REQUEST = 0x4F;
    public static final int C_FILE_STATUS = 0x50;
    public static final int C_HASHSET_REQUEST = 0x51;
    public static final int C_HASHSET_ANSWER = 0x52;
    public static final int C_SLOT_REQUEST = 0x54;
    public static final int C_SLOT_GIVEN = 0x55;
    public static final int C_SLOT_RELEASE = 0x56;
    public static final int C_SLOT_TAKEN = 0x57;
    public static final int C_FILE_REQUEST = 0x58;
    public static final int C_FILE_REQUEST_ANSWER = 0x59;

    // eMule 扩展（0xC5）
    public static final int EM_HELLO = 0x01;
    public static final int EM_HELLO_ANSWER = 0x02;
    public static final int EM_DATA_COMPRESSED = 0x40;
    public static final int EM_QUEUE_RANKING = 0x60;
    public static final int EM_FILE_DESC = 0x61;
    public static final int EM_SOURCES_REQUEST = 0x81;
    public static final int EM_SOURCES_ANSWER = 0x82;
    public static final int EM_MULTIPACKET = 0x92;
    public static final int EM_MULTIPACKET_ANSWER = 0x93;
    public static final int EM_AICH_REQUEST = 0x9B;
    public static final int EM_AICH_ANSWER = 0x9C;
    public static final int EM_AICH_FILEHASH_REQUEST = 0x9E;
    public static final int EM_AICH_FILEHASH_ANSWER = 0x9D;

    public static final int BLOCK = 184320;      // 180 KB
    public static final int CHUNK = 9728000;     // 9500 KiB，一个 ed2k part

    // eMule 混淆握手 magic
    public static final int MAGIC_CLIENT = 0x22; // 34
    public static final int MAGIC_PEER = 0xCB;   // 203
    public static final int MAGIC_VER = 0x3D;
    public static final int MAGIC_EMULE_VERSION = 0x31;

    // ---- 读工具 ----

    public static int u8(byte[] b, int off) {
        return b[off] & 0xFF;
    }

    public static int u16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    public static long u32(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8) | ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24);
    }

    public static long u64(byte[] b, int off) {
        return u32(b, off) | (u32(b, off + 4) << 32);
    }

    public static String str(byte[] b, int off) {
        if (off + 2 > b.length) return "";
        int len = u16(b, off);
        if (off + 2 + len > b.length) len = b.length - off - 2;
        try {
            return new String(b, off + 2, len, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /** 跳过 tag 列表，返回读取后的偏移；tags 是 client info 之后的内容 */
    public static int skipTags(byte[] b, int off) {
        if (off + 4 > b.length) return b.length;
        long count = u32(b, off);
        off += 4;
        for (int i = 0; i < count && off + 3 <= b.length; i++) {
            int nameLen = u8(b, off);
            off += 1 + nameLen;
            if (off + 2 > b.length) return b.length;
            int type = u16(b, off);
            off += 2;
            switch (type) {
                case 0x01: // hash
                    if (off + 16 <= b.length) off += 16;
                    break;
                case 0x02: // string
                    off = skipString(b, off);
                    break;
                case 0x03: // dword
                case 0x08: // word (4 bytes in tag)
                case 0x11: // str1: 1 byte len
                case 0x09: // byte
                case 0x05: // bool
                case 0x0A:
                    off += 4;
                    break;
                case 0x20: // str16
                    off = skipString(b, off);
                    break;
                default:
                    return b.length;
            }
        }
        return Math.min(off, b.length);
    }

    private static int skipString(byte[] b, int off) {
        if (off + 2 > b.length) return b.length;
        int len = u16(b, off);
        return Math.min(b.length, off + 2 + len);
    }

    // ---- 写工具 ----

    public static byte[] pack(int prot, int op, byte[] payload) {
        int size = 1 + (payload == null ? 0 : payload.length);
        byte[] out = new byte[5 + size];
        out[0] = (byte) prot;
        out[1] = (byte) size;
        out[2] = (byte) (size >> 8);
        out[3] = (byte) (size >> 16);
        out[4] = (byte) (size >> 24);
        out[5] = (byte) op;
        if (payload != null && payload.length > 0) System.arraycopy(payload, 0, out, 6, payload.length);
        return out;
    }

    public static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        for (byte[] p : parts) if (p != null) bo.write(p, 0, p.length);
        return bo.toByteArray();
    }

    public static byte[] le32(long v) {
        return new byte[]{(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)};
    }

    public static byte[] le16(int v) {
        return new byte[]{(byte) v, (byte) (v >> 8)};
    }

    public static byte[] hexToBytes(String hex) {
        if (hex == null) return new byte[0];
        hex = hex.trim().toLowerCase();
        if (hex.length() % 2 != 0) return new byte[0];
        byte[] b = new byte[hex.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return b;
    }

    public static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x & 0xFF));
        return sb.toString();
    }

    /** 生成一个稳定的本机 user hash：MD4(package + androidId 前缀) */
    public static byte[] localUserHash(android.content.Context c) {
        String saved = VieYApp.getPrefs(c).getString("ed2k_user_hash", "");
        if (saved != null && saved.length() == 32) return hexToBytes(saved);
        byte[] raw;
        try {
            String seed = "viey-ed2k-" + android.provider.Settings.Secure.getString(
                    c.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID)
                    + "-" + new Random().nextLong();
            raw = Ed2kHash.md4(seed.getBytes("UTF-8"));
        } catch (Exception e) {
            raw = Ed2kHash.md4(String.valueOf(System.nanoTime()).getBytes());
        }
        byte[] h = new byte[16];
        System.arraycopy(raw, 0, h, 0, 16);
        VieYApp.getPrefs(c).edit().putString("ed2k_user_hash", bytesToHex(h)).apply();
        return h;
    }

    /** 读取一个完整 TCP 包：返回 [prot, op, payload] */
    public static Packet readPacket(InputStream in) throws IOException {
        return readPacket(in, null);
    }

    public static Packet readPacket(InputStream in, Rc4 rc4) throws IOException {
        byte[] head = new byte[5];
        readFully(in, head);
        if (rc4 != null) head = rc4.crypt(head);
        int prot = u8(head, 0);
        int size = u8(head, 1) | (u8(head, 2) << 8) | (u8(head, 3) << 16) | (u8(head, 4) << 24);
        if (size <= 0 || size > 8 * 1024 * 1024) throw new IOException("非法包长 " + size);
        byte[] body = new byte[size];
        readFully(in, body);
        if (rc4 != null) body = rc4.crypt(body);
        Packet p = new Packet();
        p.prot = prot;
        p.op = u8(body, 0);
        p.payload = new byte[size - 1];
        System.arraycopy(body, 1, p.payload, 0, size - 1);
        return p;
    }

    public static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new EOFException("连接关闭");
            off += n;
        }
    }

    public static class Packet {
        public int prot;
        public int op;
        public byte[] payload = new byte[0];

        @Override
        public String toString() {
            return String.format("prot=0x%02x op=0x%02x len=%d", prot, op, payload.length);
        }
    }

    // ---- 哈希工具（MD4/SHA1） ----

    public static byte[] md4(byte[] data) {
        return Ed2kHash.md4(data);
    }

    public static byte[] sha1(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(data);
        } catch (Exception e) {
            return new byte[20];
        }
    }

    public static byte[] sha1(byte[] a, int off, int len) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update(a, off, len);
            return md.digest();
        } catch (Exception e) {
            return new byte[20];
        }
    }

    // ---- 混淆握手（RC4） ----

    /**
     * 客户端发起混淆握手（eMule Protocol_obfuscation）。
     * 需要知道对端 user hash；未知时调用方应退回明文。
     */
    public static Rc4[] clientHandshake(InputStream in, OutputStream out, byte[] peerHash) throws IOException {
        byte marker = 0x08; // != 0xE3/0xC5/0xD4
        byte[] randKey = new byte[4];
        new Random().nextBytes(randKey);

        byte[] sendKey = Ed2kHash.md4(cat(peerHash, new byte[]{(byte) MAGIC_CLIENT}, randKey));
        byte[] recvKey = Ed2kHash.md4(cat(peerHash, new byte[]{(byte) MAGIC_PEER}, randKey));

        Rc4 send = new Rc4(sendKey, 1024);
        Rc4 recv = new Rc4(recvKey, 1024);

        out.write(marker);
        out.write(randKey);
        out.flush();

        int pad = new Random().nextInt(16);
        byte[] padBytes = new byte[pad];
        new Random().nextBytes(padBytes);
        byte[] hello = cat(
                new byte[]{(byte) MAGIC_PEER, (byte) MAGIC_PEER, (byte) MAGIC_PEER, (byte) MAGIC_PEER},
                new byte[]{0x00, 0x00, (byte) pad}, padBytes);
        out.write(send.crypt(hello));
        out.flush();

        byte[] buf = new byte[4 + 1 + 1];
        readFully(in, buf);
        byte[] plain = recv.crypt(buf);
        int pad2 = u8(plain, 5);
        if (pad2 > 0) {
            byte[] pb = new byte[pad2];
            readFully(in, pb);
            recv.crypt(pb);
        }
        return new Rc4[]{send, recv};
    }

    /** 简单 RC4，按 eMule 规则丢弃前 drop 字节 */
    public static class Rc4 {
        private final int[] s = new int[256];
        private int i = 0, j = 0;

        public Rc4(byte[] key, int drop) {
            for (int k = 0; k < 256; k++) s[k] = k;
            int jj = 0;
            for (int k = 0; k < 256; k++) {
                jj = (jj + s[k] + (key[k % key.length] & 0xFF)) & 0xFF;
                int t = s[k];
                s[k] = s[jj];
                s[jj] = t;
            }
            if (drop > 0) {
                byte[] tmp = new byte[drop];
                crypt(tmp);
            }
        }

        public byte[] crypt(byte[] data) {
            byte[] out = new byte[data.length];
            for (int k = 0; k < data.length; k++) {
                i = (i + 1) & 0xFF;
                j = (j + s[i]) & 0xFF;
                int t = s[i];
                s[i] = s[j];
                s[j] = t;
                int r = s[(s[i] + s[j]) & 0xFF];
                out[k] = (byte) (data[k] ^ r);
            }
            return out;
        }
    }

    /** 解析 GZip/zlib 压缩包（0xD4）内容为内部 eD2k 包列表 */
    public static List<Packet> decompress(byte[] zdata) {
        List<Packet> out = new ArrayList<>();
        try {
            java.util.zip.Inflater inf = new java.util.zip.Inflater();
            inf.setInput(zdata);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0) break;
                bo.write(buf, 0, n);
            }
            byte[] plain = bo.toByteArray();
            int off = 0;
            while (off + 5 <= plain.length) {
                int prot = u8(plain, off);
                int size = u8(plain, off + 1) | (u8(plain, off + 2) << 8) | (u8(plain, off + 3) << 16) | (u8(plain, off + 4) << 24);
                if (size <= 0 || off + 5 + size > plain.length) break;
                Packet p = new Packet();
                p.prot = prot;
                p.op = u8(plain, off + 5);
                p.payload = new byte[size - 1];
                System.arraycopy(plain, off + 6, p.payload, 0, size - 1);
                out.add(p);
                off += 5 + size;
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}
