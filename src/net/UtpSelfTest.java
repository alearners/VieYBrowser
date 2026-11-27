package kawaii.viey.browser;

import android.content.Context;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Random;

/**
 * uTP 自检：
 *  1) 头部 pack/parse 往返（20 字节定长字段）
 *  2) 回环可靠传输：SYN 建链 → 8KB 数据按 MTU 切片 → 停等 ACK → FIN 收尾，逐字节比对
 * 不依赖外网，只在本机 127.0.0.1 上跑，能在真机上判定传输层骨架是否正确。
 */
public class UtpSelfTest {

    public static String run(Context c) {
        StringBuilder sb = new StringBuilder();
        int pass = 0, fail = 0;

        boolean head;
        try {
            byte[] pkt = UtpProto.pack(UtpProto.ST_DATA, 0x1234, 987654321L, -20, 0x7FFF, 7, 3);
            UtpProto.Header h = UtpProto.parse(pkt, 0);
            head = pkt.length == UtpProto.HEADER && h != null && h.type == UtpProto.ST_DATA
                    && h.connId == 0x1234 && h.seq == 7 && h.ack == 3 && h.wndSize == 0x7FFF
                    && h.tsDiff == -20;
        } catch (Exception e) {
            head = false;
        }
        sb.append(head ? "PASS " : "FAIL ").append("uTP 头部 pack/parse\n");
        if (head) pass++;
        else fail++;

        String loop = loopback();
        boolean ok = loop.startsWith("PASS");
        sb.append(loop).append("\n");
        if (ok) pass++;
        else fail++;

        sb.append("\n结果：").append(pass).append(" 通过，").append(fail).append(" 失败");
        return sb.toString();
    }

    private static String loopback() {
        final int want = 8192;
        DatagramSocket srv = null, cli = null;
        try {
            InetAddress lo = InetAddress.getByName("127.0.0.1");
            srv = new DatagramSocket(0, lo);
            cli = new DatagramSocket(0, lo);

            byte[] data = new byte[want];
            new Random(41).nextBytes(data);

            final DatagramSocket srvSock = srv;
            final byte[][] received = new byte[1][];
            final int connId = UtpStream.randomConnId();

            Thread server = new Thread(() -> {
                try {
                    byte[] buf = new byte[2048];
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    srvSock.setSoTimeout(8000);
                    srvSock.receive(p);
                    UtpProto.Header h = UtpProto.parse(p.getData(), 0);
                    if (h == null || h.type != UtpProto.ST_SYN) return;
                    UtpStream s = new UtpStream(srvSock, (java.net.InetSocketAddress) p.getSocketAddress(), h.connId);
                    s.accept();
                    received[0] = s.receive(want);
                    s.finish();
                } catch (Exception ignored) {
                }
            }, "utp-srv");
            server.start();
            Thread.sleep(60);

            UtpStream client = new UtpStream(cli, new java.net.InetSocketAddress(lo, srv.getLocalPort()), connId);
            long t0 = System.currentTimeMillis();
            boolean connected = client.connect();
            if (!connected) {
                server.interrupt();
                return "FAIL 回环 uTP：SYN 未被应答";
            }
            client.send(data);
            client.finish();
            server.join(8000);
            long ms = System.currentTimeMillis() - t0;
            byte[] got = received[0];
            if (got == null || got.length != want) {
                return "FAIL 回环 uTP：对端收到 " + (got == null ? 0 : got.length) + "B";
            }
            for (int i = 0; i < want; i++) {
                if (got[i] != data[i]) return "FAIL 回环 uTP：第 " + i + " 字节不一致";
            }
            return "PASS 回环 uTP 传输 8KB / " + ms + "ms / 停等 ACK";
        } catch (Exception e) {
            return "FAIL 回环 uTP：" + e.getClass().getSimpleName() + " " + e.getMessage();
        } finally {
            if (srv != null) srv.close();
            if (cli != null) cli.close();
        }
    }
}
