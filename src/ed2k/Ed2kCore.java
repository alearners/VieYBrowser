package kawaii.viey.browser;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * eMule 内核 v0.1：eD2k 服务器 TCP 握手 + 取源（ed2k 协议）。
 * 已实现：登录(0x01)、ID 变更(0x41)、文件取源(0x19/0x1A) 的最小流程，带超时与日志。
 * 未实现（后续里程碑）：Kad(UDP) 取源、AICH 校验、客户端上传/排队、ed2k 混淆。
 */
public class Ed2kCore {

    public static final int LOGINREQUEST = 0x01;
    public static final int IDCHANGE = 0x41;
    public static final int GETSOURCES = 0x19;
    public static final int GETSOURCES_OBFU = 0x1A;
    public static final int FOUNDSOURCES = 0x42;

    public static class Result {
        public boolean ok;
        public int clientId = 0;
        public String message = "";
        public int sources = 0;
    }

    /** 连接服务器并登录，返回分配的 clientID（低 ID 时为 0 或 <0x1000000） */
    public static Result login(String host, int port, int tcpPort, int timeoutMs) {
        Result r = new Result();
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            s.setSoTimeout(timeoutMs);
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            DataInputStream in = new DataInputStream(s.getInputStream());

            // 登录包：size(1B, 单位 4 字节) + type + clientID(4B) + tcpPort(2B) + nameLen(2B) + name + ...
            byte[] name = "VieY".getBytes("UTF-8");
            int size = 1 + 4 + 2 + 2 + name.length + 4 + 4;
            out.writeByte(size);
            out.writeByte(LOGINREQUEST);
            out.writeInt(0);
            out.writeShort(tcpPort);
            out.writeShort(name.length);
            out.write(name);
            out.writeInt(0); // tag count
            out.writeInt(0); // more data
            out.flush();

            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                int len = in.readUnsignedByte();
                if (len <= 0) break;
                int type = in.readUnsignedByte();
                int payload = Math.max(0, len * 4 - 2);
                byte[] buf = new byte[payload];
                if (payload > 0) in.readFully(buf, 0, Math.min(payload, buf.length));
                if (type == IDCHANGE && buf.length >= 4) {
                    int id = ((buf[0] & 0xff)) | ((buf[1] & 0xff) << 8) | ((buf[2] & 0xff) << 16) | ((buf[3] & 0xff) << 24);
                    r.ok = true;
                    r.clientId = id;
                    r.message = "服务器已登录，clientID=0x" + Integer.toHexString(id)
                            + (id < 0x1000000 ? "（低 ID，取源受限）" : "（高 ID）");
                    return r;
                }
                if (type == 0x40) { // 服务器拒绝/错误
                    r.message = "服务器拒绝登录（type=0x40）";
                    return r;
                }
            }
            r.message = "登录超时：未收到 IDCHANGE";
            return r;
        } catch (Exception e) {
            r.message = "登录失败：" + e.getClass().getSimpleName() + " " + e.getMessage();
            return r;
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** 向服务器查询文件源（0x19）；返回源数量，源列表追加到 out */
    public static Result querySources(String host, int port, String fileHash, long fileSize,
                                      int tcpPort, java.util.List<String> out, int timeoutMs) {
        Result r = login(host, port, tcpPort, timeoutMs);
        if (!r.ok) return r;
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            s.setSoTimeout(timeoutMs);
            DataOutputStream dos = new DataOutputStream(s.getOutputStream());
            DataInputStream dis = new DataInputStream(s.getInputStream());

            byte[] name = "VieY".getBytes("UTF-8");
            int size = 1 + 4 + 2 + 2 + name.length + 4 + 4;
            dos.writeByte(size);
            dos.writeByte(LOGINREQUEST);
            dos.writeInt(r.clientId);
            dos.writeShort(tcpPort);
            dos.writeShort(name.length);
            dos.write(name);
            dos.writeInt(0);
            dos.writeInt(0);
            dos.flush();

            // 取源：0x19 + hash(16B) + size(4B)
            byte[] hash = hexToBytes(fileHash);
            dos.writeByte((1 + 16 + 4) / 4);
            dos.writeByte(GETSOURCES);
            dos.write(hash);
            dos.writeInt((int) fileSize);
            dos.flush();

            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                int len = dis.readUnsignedByte();
                if (len <= 0) break;
                int type = dis.readUnsignedByte();
                int payload = Math.max(0, len * 4 - 2);
                byte[] buf = new byte[payload];
                if (payload > 0) dis.readFully(buf, 0, payload);
                if (type == FOUNDSOURCES && buf.length >= 1) {
                    int count = buf[0] & 0xff;
                    int off = 1;
                    for (int k = 0; k < count && off + 6 <= buf.length; k++) {
                        int ip = ((buf[off + 3] & 0xff) << 24) | ((buf[off + 2] & 0xff) << 16)
                                | ((buf[off + 1] & 0xff) << 8) | (buf[off] & 0xff);
                        int pt = ((buf[off + 5] & 0xff) << 8) | (buf[off + 4] & 0xff);
                        out.add(((ip >>> 24) & 0xff) + "." + ((ip >>> 16) & 0xff) + "."
                                + ((ip >>> 8) & 0xff) + "." + (ip & 0xff) + ":" + pt);
                        off += 6;
                    }
                    r.sources = out.size();
                    r.message = "取源完成：" + out.size() + " 个源";
                    return r;
                }
            }
            r.message = "取源超时（服务器可能要求低 ID 校验/未响应 0x42）";
            return r;
        } catch (Exception e) {
            r.message = "取源失败：" + e.getClass().getSimpleName() + " " + e.getMessage();
            return r;
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    public static byte[] hexToBytes(String hex) {
        if (hex == null) return new byte[0];
        byte[] b = new byte[hex.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }
}
