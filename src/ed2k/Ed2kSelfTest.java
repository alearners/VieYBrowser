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

        String loop = loopback(c);
        boolean loopOk = loop.startsWith("PASS");
        sb.append(loop).append("\n");
        if (loopOk) pass++;
        else fail++;

        sb.append("\n结果：").append(pass).append(" 通过，").append(fail).append(" 失败");
        return sb.toString();
    }

    /** 环回 P2P：本机上传服务 + PeerClient 走完整状态机（HELLO/FILEREQ/SLOT/REQPARTS/SENDING_PART） */
    private static String loopback(Context c) {
        File dir = PartFile.dir(c);
        if (!dir.exists()) dir.mkdirs();
        File f = new File(dir, "viey_loop_test.bin");
        String hash;
        long size;
        try {
            byte[] data = new byte[65536];
            new Random(29).nextBytes(data);
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(data);
            }
            hash = Ed2kHash.hashFile(f);
            size = f.length();
        } catch (Exception e) {
            return "FAIL 环回 P2P：造文件失败 " + e.getMessage();
        }

        SharedFiles.add(c, f);
        Ed2kUploader up = Ed2kUploader.instance(c);
        boolean alreadyRunning = up.isRunning();
        if (!alreadyRunning) up.start();
        for (int i = 0; i < 40 && !up.isRunning(); i++) {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        }
        if (!up.isRunning()) {
            f.delete();
            return "FAIL 环回 P2P：上传服务未就绪（端口被占用？）";
        }
        int port = VieYApp.getPrefs(c).getInt("ed2k_port", 4662);

        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        final byte[][] got = new byte[1][];
        final String[] err = new String[1];
        PeerClient.Sink sink = new PeerClient.Sink() {
            public void onLog(String msg) {}

            public void onAccept(PeerClient p) {}

            public void onHashset(PeerClient p, java.util.List<String> hashes) {}

            public void onPart(PeerClient p, long start, byte[] data) {
                got[0] = data;
                done.countDown();
            }

            public void onIdle(PeerClient p) {
                if (got[0] != null) return;
                try {
                    p.requestBlocks(new int[]{0}, new long[]{0}, new int[]{(int) size});
                } catch (Exception e) {
                    err[0] = e.getMessage();
                    done.countDown();
                }
            }

            public void onDead(PeerClient p, String reason) {
                err[0] = reason;
                done.countDown();
            }
        };

        PeerClient pc = new PeerClient("127.0.0.1", port, hash, size, Ed2kProto.localUserHash(c), null, sink, false);
        long t0 = System.currentTimeMillis();
        pc.start();
        boolean ok;
        try {
            ok = done.await(15, java.util.concurrent.TimeUnit.SECONDS) && got[0] != null
                    && Ed2kHash.hex(Ed2kHash.md4(got[0])).equals(hash);
        } catch (InterruptedException e) {
            ok = false;
        }
        if (ok && err[0] != null) ok = false;
        long ms = System.currentTimeMillis() - t0;
        pc.close("自检结束");
        SharedFiles.remove(hash);
        f.delete();
        if (!alreadyRunning) up.stop();
        String res = ok ? "PASS 环回 P2P 传输 64KB / " + ms + "ms / " + (got[0] == null ? 0 : got[0].length) + "B"
                : "FAIL 环回 P2P：收到 " + (got[0] == null ? 0 : got[0].length) + "B，错误=" + err[0];
        return res;
    }

}
