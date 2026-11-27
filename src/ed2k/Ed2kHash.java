package kawaii.viey.browser;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;

/**
 * eD2k 文件哈希（AICH 之外的 ed2k hash）：9500KB 分块 MD4，块哈希再拼一次 MD4。
 * 单块文件直接取该块 MD4。
 */
public class Ed2kHash {

    public static final int CHUNK = 9728000; // 9500 KiB

    /** 计算整个文件的 ed2k hash（32 位十六进制） */
    public static String hashFile(File f) throws Exception {
        long len = f.length();
        if (len <= CHUNK) {
            try (InputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[(int) len];
                int off = 0;
                while (off < buf.length) {
                    int n = in.read(buf, off, buf.length - off);
                    if (n <= 0) break;
                    off += n;
                }
                return hex(md4(buf));
            }
        }
        java.io.ByteArrayOutputStream digests = new java.io.ByteArrayOutputStream();
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long pos = 0;
            byte[] buf = new byte[CHUNK];
            while (pos < len) {
                int want = (int) Math.min(CHUNK, len - pos);
                raf.seek(pos);
                int got = 0;
                while (got < want) {
                    int n = raf.read(buf, got, want - got);
                    if (n <= 0) break;
                    got += n;
                }
                byte[] part = new byte[got];
                System.arraycopy(buf, 0, part, 0, got);
                digests.write(md4(part));
                pos += got;
            }
        }
        return hex(md4(digests.toByteArray()));
    }

    /** 单个 ed2k part（CHUNK）的 MD4，用于分块校验 */
    public static String hashChunk(File f, int chunkIndex) throws Exception {
        long len = f.length();
        long from = (long) chunkIndex * CHUNK;
        if (from >= len) return "";
        int want = (int) Math.min(CHUNK, len - from);
        byte[] buf = new byte[want];
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            raf.seek(from);
            int got = 0;
            while (got < want) {
                int n = raf.read(buf, got, want - got);
                if (n <= 0) break;
                got += n;
            }
            if (got != want) return "";
        }
        return hex(md4(buf));
    }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte v : b) sb.append(String.format("%02x", v & 0xff));
        return sb.toString();
    }

    /** 纯 Java MD4（RFC 1320），ed2k 用 */
    public static byte[] md4(byte[] input) {
        int[] x = new int[16];
        int len = input.length;
        int full = (len + 8) / 64 + 1;
        int[] words = new int[full * 16];
        for (int i = 0; i < len; i++) {
            words[i >> 2] |= (input[i] & 0xff) << ((i & 3) * 8);
        }
        words[len >> 2] |= 0x80 << ((len & 3) * 8);
        long bitLen = (long) len * 8;
        words[full * 16 - 2] = (int) (bitLen & 0xffffffffL);
        words[full * 16 - 1] = (int) ((bitLen >> 32) & 0xffffffffL);

        int a = 0x67452301, b = 0xefcdab89, c = 0x98badcfe, d = 0x10325476;
        for (int i = 0; i < full; i++) {
            for (int j = 0; j < 16; j++) x[j] = words[i * 16 + j];
            int aa = a, bb = b, cc = c, dd = d;
            // Round 1
            a = ff(a, b, c, d, x[0], 3);
            d = ff(d, a, b, c, x[1], 7);
            c = ff(c, d, a, b, x[2], 11);
            b = ff(b, c, d, a, x[3], 19);
            a = ff(a, b, c, d, x[4], 3);
            d = ff(d, a, b, c, x[5], 7);
            c = ff(c, d, a, b, x[6], 11);
            b = ff(b, c, d, a, x[7], 19);
            a = ff(a, b, c, d, x[8], 3);
            d = ff(d, a, b, c, x[9], 7);
            c = ff(c, d, a, b, x[10], 11);
            b = ff(b, c, d, a, x[11], 19);
            a = ff(a, b, c, d, x[12], 3);
            d = ff(d, a, b, c, x[13], 7);
            c = ff(c, d, a, b, x[14], 11);
            b = ff(b, c, d, a, x[15], 19);
            // Round 2
            a = gg(a, b, c, d, x[0], 3);
            d = gg(d, a, b, c, x[4], 5);
            c = gg(c, d, a, b, x[8], 9);
            b = gg(b, c, d, a, x[12], 13);
            a = gg(a, b, c, d, x[1], 3);
            d = gg(d, a, b, c, x[5], 5);
            c = gg(c, d, a, b, x[9], 9);
            b = gg(b, c, d, a, x[13], 13);
            a = gg(a, b, c, d, x[2], 3);
            d = gg(d, a, b, c, x[6], 5);
            c = gg(c, d, a, b, x[10], 9);
            b = gg(b, c, d, a, x[14], 13);
            a = gg(a, b, c, d, x[3], 3);
            d = gg(d, a, b, c, x[7], 5);
            c = gg(c, d, a, b, x[11], 9);
            b = gg(b, c, d, a, x[15], 13);
            // Round 3
            a = hh(a, b, c, d, x[0], 3);
            d = hh(d, a, b, c, x[8], 9);
            c = hh(c, d, a, b, x[4], 11);
            b = hh(b, c, d, a, x[12], 15);
            a = hh(a, b, c, d, x[2], 3);
            d = hh(d, a, b, c, x[10], 9);
            c = hh(c, d, a, b, x[6], 11);
            b = hh(b, c, d, a, x[14], 15);
            a = hh(a, b, c, d, x[1], 3);
            d = hh(d, a, b, c, x[9], 9);
            c = hh(c, d, a, b, x[5], 11);
            b = hh(b, c, d, a, x[13], 15);
            a = hh(a, b, c, d, x[3], 3);
            d = hh(d, a, b, c, x[11], 9);
            c = hh(c, d, a, b, x[7], 11);
            b = hh(b, c, d, a, x[15], 15);

            a += aa;
            b += bb;
            c += cc;
            d += dd;
        }
        byte[] out = new byte[16];
        writeLE(out, 0, a);
        writeLE(out, 4, b);
        writeLE(out, 8, c);
        writeLE(out, 12, d);
        return out;
    }

    private static void writeLE(byte[] o, int off, int v) {
        o[off] = (byte) v;
        o[off + 1] = (byte) (v >>> 8);
        o[off + 2] = (byte) (v >>> 16);
        o[off + 3] = (byte) (v >>> 24);
    }

    private static int ff(int a, int b, int c, int d, int x, int s) {
        int t = a + ((b & c) | (~b & d)) + x;
        return Integer.rotateLeft(t, s);
    }

    private static int gg(int a, int b, int c, int d, int x, int s) {
        int t = a + ((b & c) | (b & d) | (c & d)) + x + 0x5a827999;
        return Integer.rotateLeft(t, s);
    }

    private static int hh(int a, int b, int c, int d, int x, int s) {
        int t = a + (b ^ c ^ d) + x + 0x6ed9eba1;
        return Integer.rotateLeft(t, s);
    }
}
