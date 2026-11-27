package kawaii.viey.browser;

import android.content.Context;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Random;

/**
 * eD2k 内核自检：MD4 向量、RC4 对称性、包编解码、分块哈希一致性。
 * 真机上先跑这个，能在不连外网的情况下判断内核基础是否正确。
 */
public class Ed2kSelfTest {

    public static String run(Context c) {
        StringBuilder sb = new StringBuilder();
        int pass = 0, fail = 0;

        String h1 = Ed2kHash.hex(Ed2kHash.md4("".getBytes()));
        String h2 = Ed2kHash.hex(Ed2kHash.md4("abc".getBytes()));
        boolean md4 = "31d6cfe0d16ae931b73c59d7e0c089c0".equals(h1) && "a448017aaf21d8525fc10ae87aa6729d".equals(h2);
        sb.append(md4 ? "PASS " : "FAIL ").append("MD4 向量  ").append(h1).append(" / ").append(h2).append("\n");
        if (md4) pass++;
        else fail++;

        byte[] key = Ed2kProto.md4("viey-rc4-test".getBytes());
        Ed2kProto.Rc4 a = new Ed2kProto.Rc4(key, 1024), b = new Ed2kProto.Rc4(key, 1024);
        byte[] plain = new byte[300];
        new Random(7).nextBytes(plain);
        byte[] enc = a.crypt(plain);
        byte[] dec = b.crypt(enc);
        boolean rc4 = java.util.Arrays.equals(plain, dec);
        sb.append(rc4 ? "PASS " : "FAIL ").append("RC4 加解密对称\n");
        if (rc4) pass++;
        else fail++;

        boolean pkt;
        try {
            byte[] buf = Ed2kProto.pack(Ed2kProto.PROT_EDONKEY, Ed2kProto.C_REQUEST_PARTS,
                    Ed2kProto.cat(Ed2kProto.hexToBytes("00112233445566778899aabbccddeeff"),
                            Ed2kProto.le32(184320), Ed2kProto.le32(368639)));
            Ed2kProto.Packet p = Ed2kProto.readPacket(new ByteArrayInputStream(buf));
            pkt = p.prot == Ed2kProto.PROT_EDONKEY && p.op == Ed2kProto.C_REQUEST_PARTS
                    && p.payload.length == 24 && Ed2kProto.u32(p.payload, 16) == 184320;
        } catch (Exception e) {
            pkt = false;
        }
        sb.append(pkt ? "PASS " : "FAIL ").append("REQUEST_PARTS 包编解码\n");
        if (pkt) pass++;
        else fail++;

        boolean hash;
        try {
            File f = new File(c.getCacheDir(), "viey_hash_test.bin");
            byte[] data = new byte[1024 * 1024];
            new Random(11).nextBytes(data);
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(data);
            }
            String whole = Ed2kHash.hashFile(f);
            String chunk = Ed2kHash.hashChunk(f, 0);
            hash = whole.equals(chunk);
            f.delete();
        } catch (Exception e) {
            hash = false;
        }
        sb.append(hash ? "PASS " : "FAIL ").append("单 chunk 文件 hash == chunk hash\n");
        if (hash) pass++;
        else fail++;

        boolean userHash = Ed2kProto.localUserHash(c).length == 16;
        sb.append(userHash ? "PASS " : "FAIL ").append("本机 user hash 生成/持久化\n");
        if (userHash) pass++;
        else fail++;

        sb.append("\n结果：").append(pass).append(" 通过，").append(fail).append(" 失败");
        return sb.toString();
    }
}
