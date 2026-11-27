package kawaii.viey.browser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BT 自检（不联网）：bencode 往返、magnet 解析（hex / base32）、.torrent 解析、
 * piece SHA-1 校验、tracker compact peers 解析、peer wire 请求字节编码。
 */
public class BtSelfTest {

    public static String run() {
        StringBuilder sb = new StringBuilder();
        int pass = 0;
        int fail = 0;

        // 1) bencode 往返
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("a", 1L);
            List<Object> l = new ArrayList<>();
            l.add("x".getBytes());
            l.add(2L);
            m.put("b", l);
            byte[] enc = Bencode.encode(m);
            @SuppressWarnings("unchecked")
            Map<String, Object> back = (Map<String, Object>) Bencode.decode(enc);
            if (Bencode.num(back.get("a")) == 1L && ((List<?>) back.get("b")).size() == 2) {
                pass++;
                sb.append("PASS bencode 往返（dict/list/int/bytes）\n");
            } else throw new Exception("结构不一致");
        } catch (Exception e) {
            fail++;
            sb.append("FAIL bencode：").append(e.getMessage()).append('\n');
        }

        // 2) magnet hex
        try {
            Magnet mg = Magnet.parse("magnet:?xt=urn:btih:a9993e364706816aba3e25717850c26c9cd0d89d&dn=abc.txt&tr=http://t.example/a");
            if (mg.infoHashHex.equals("a9993e364706816aba3e25717850c26c9cd0d89d")
                    && mg.displayName.equals("abc.txt") && mg.trackers.size() == 1) {
                pass++;
                sb.append("PASS magnet 解析（hex40 + dn + tr）\n");
            } else throw new Exception("字段不符");
        } catch (Exception e) {
            fail++;
            sb.append("FAIL magnet hex：").append(e.getMessage()).append('\n');
        }

        // 3) magnet base32
        try {
            Magnet mg = Magnet.parse("magnet:?xt=urn:btih:VGMT4NSHA2AWVOR6EVYXQUGCNSONBWE5");
            if (mg.infoHashHex.equals("a9993e364706816aba3e25717850c26c9cd0d89d")) {
                pass++;
                sb.append("PASS magnet base32 → hex40 归一\n");
            } else throw new Exception("得到 " + mg.infoHashHex);
        } catch (Exception e) {
            fail++;
            sb.append("FAIL magnet base32：").append(e.getMessage()).append('\n');
        }

        // 4) .torrent 解析 + info hash + piece 校验
        try {
            byte[] content = "VieY piece verify test payload 0123456789".getBytes();
            byte[] sha = java.security.MessageDigest.getInstance("SHA-1").digest(content);
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("length", (long) content.length);
            info.put("name", "test.bin".getBytes());
            info.put("piece length", 16384L);
            info.put("pieces", sha);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("announce", "http://tracker.example/announce".getBytes());
            meta.put("info", info);
            byte[] metainfo = Bencode.encode(meta);

            Torrent t = Torrent.parse(metainfo);
            boolean ok = t.name.equals("test.bin") && t.pieceCount() == 1
                    && t.totalLength == content.length
                    && t.checkPiece(0, content, content.length)
                    && !t.checkPiece(0, "bad".getBytes(), 3);
            if (!ok) throw new Exception("解析或校验不符 name=" + t.name + " pc=" + t.pieceCount());
            // info hash 必须等于对原始 info 段字节做 SHA-1
            byte[] expect = java.security.MessageDigest.getInstance("SHA-1").digest(t.infoRaw);
            if (!java.util.Arrays.equals(expect, t.infoHash)) throw new Exception("info hash 不一致");
            pass++;
            sb.append("PASS .torrent 解析 + info hash + piece SHA-1 校验\n");
        } catch (Exception e) {
            fail++;
            sb.append("FAIL .torrent：").append(e.getMessage()).append('\n');
        }

        // 5) fromInfo（magnet 元数据 → Torrent）
        try {
            byte[] content = "magnet metadata path".getBytes();
            byte[] sha = java.security.MessageDigest.getInstance("SHA-1").digest(content);
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("length", (long) content.length);
            info.put("name", "m.bin".getBytes());
            info.put("piece length", 16384L);
            info.put("pieces", sha);
            byte[] infoBytes = Bencode.encode(info);
            List<String> trs = new ArrayList<>();
            trs.add("http://t.example/announce");
            Torrent t = Torrent.fromInfo(infoBytes, trs, "m.bin");
            if (t.name.equals("m.bin") && t.trackers.size() == 1 && t.pieceCount() == 1) {
                pass++;
                sb.append("PASS fromInfo（BEP-9 元数据 → 可下载 Torrent）\n");
            } else throw new Exception("字段不符");
        } catch (Exception e) {
            fail++;
            sb.append("FAIL fromInfo：").append(e.getMessage()).append('\n');
        }

        // 6) tracker compact peers
        try {
            byte[] compact = new byte[]{127, 0, 0, 1, 0x1A, (byte) 0xE1, 10, 0, 0, 2, 0x1F, 0x40};
            List<Tracker.Peer> ps = Tracker.parseCompact(compact);
            if (ps.size() == 2 && ps.get(0).host.equals("127.0.0.1") && ps.get(0).port == 6881
                    && ps.get(1).host.equals("10.0.0.2") && ps.get(1).port == 8000) {
                pass++;
                sb.append("PASS tracker compact peers（IPv4:port）\n");
            } else throw new Exception("解析出错");
        } catch (Exception e) {
            fail++;
            sb.append("FAIL compact：").append(e.getMessage()).append('\n');
        }

        // 7) info_hash 百分号编码（tracker 必须转义二进制）
        try {
            byte[] ih = new byte[]{0x12, 0x34, 0x56, 0x78, (byte) 0x9a};
            String q = Tracker.percent(ih);
            if (q.equals("%124Vx%9A")) {
                pass++;
                sb.append("PASS info_hash percent 编码\n");
            } else throw new Exception("得到 " + q);
        } catch (Exception e) {
            fail++;
            sb.append("FAIL percent：").append(e.getMessage()).append('\n');
        }

        sb.append("\n").append(pass).append(" 通过 / ").append(fail).append(" 失败");
        return sb.toString();
    }
}
