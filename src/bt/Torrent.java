package kawaii.viey.browser;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** .torrent 元信息（BEP-3）：单文件/多文件、piece 哈希、info hash、tracker 列表 */
public class Torrent {

    public String name = "";
    /** 每片字节数（除最后一片） */
    public long pieceLength;
    /** 20 字节 * pieceCount 的 SHA-1 表 */
    public byte[] pieces = new byte[0];
    public long totalLength;
    public byte[] infoHash = new byte[20];
    public byte[] infoRaw;
    public final List<String> trackers = new ArrayList<>();
    /** BEP-19 HTTP 种子（url-list / httpseeds），无 peer 时直接用 HTTP Range 取片 */
    public final List<String> webSeeds = new ArrayList<>();

    /** 多文件布局：每个文件在整体中的起始偏移 */
    public static class Entry {
        public long offset;
        public long length;
        public String path;

        public Entry(long o, long l, String p) {
            offset = o;
            length = l;
            path = p;
        }
    }

    public final List<Entry> files = new ArrayList<>();

    public int pieceCount() {
        return pieces.length / 20;
    }

    public long pieceSize(int index) {
        long start = (long) index * pieceLength;
        return Math.min(pieceLength, totalLength - start);
    }

    public static Torrent parse(byte[] metainfo) throws Exception {
        int[] span = Bencode.infoSpan(metainfo);
        if (span == null) throw new Exception("缺少 info 段");
        Torrent t = new Torrent();
        t.infoRaw = new byte[span[1] - span[0]];
        System.arraycopy(metainfo, span[0], t.infoRaw, 0, t.infoRaw.length);
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        t.infoHash = md.digest(t.infoRaw);

        @SuppressWarnings("unchecked")
        Map<String, Object> top = (Map<String, Object>) Bencode.decode(metainfo);
        Object ann = top.get("announce");
        if (ann != null) t.trackers.add(Bencode.str(ann));
        Object al = top.get("announce-list");
        if (al instanceof List) {
            for (Object tier : (List<?>) al) {
                if (tier instanceof List) {
                    for (Object u : (List<?>) tier) {
                        String s = Bencode.str(u);
                        if (!t.trackers.contains(s)) t.trackers.add(s);
                    }
                }
            }
        }

        Object ul = top.get("url-list");
        if (ul instanceof byte[]) t.webSeeds.add(Bencode.str(ul));
        else if (ul instanceof List) for (Object u : (List<?>) ul) t.webSeeds.add(Bencode.str(u));
        Object hs = top.get("httpseeds");
        if (hs instanceof List) for (Object u : (List<?>) hs) t.webSeeds.add(Bencode.str(u));

        @SuppressWarnings("unchecked")
        Map<String, Object> info = (Map<String, Object>) Bencode.decode(t.infoRaw);
        t.name = Bencode.str(info.get("name"));
        t.pieceLength = Bencode.num(info.get("piece length"));
        Object ps = info.get("pieces");
        if (ps instanceof byte[]) t.pieces = (byte[]) ps;
        if (t.pieces.length % 20 != 0) throw new Exception("pieces 长度非法");

        Object len = info.get("length");
        if (len != null) {
            t.totalLength = Bencode.num(len);
            t.files.add(new Entry(0, t.totalLength, t.name));
        } else {
            Object fs = info.get("files");
            if (!(fs instanceof List)) throw new Exception("既非单文件也非多文件");
            long off = 0;
            for (Object f : (List<?>) fs) {
                @SuppressWarnings("unchecked")
                Map<String, Object> fm = (Map<String, Object>) f;
                long l = Bencode.num(fm.get("length"));
                StringBuilder sb = new StringBuilder();
                Object pl = fm.get("path");
                if (pl instanceof List) {
                    for (Object seg : (List<?>) pl) {
                        if (sb.length() > 0) sb.append('/');
                        sb.append(Bencode.str(seg));
                    }
                }
                t.files.add(new Entry(off, l, sb.toString()));
                off += l;
            }
            t.totalLength = off;
        }
        if (t.pieceLength <= 0) throw new Exception("piece length 非法");
        return t;
    }

    /** magnet 拿到 info 段原始字节后，包一层 metainfo 再解析 */
    public static Torrent fromInfo(byte[] infoBytes, List<String> trackers, String displayName) throws Exception {
        byte[] meta = new byte[infoBytes.length + 8]; // "d4:info" 7 字节 + info + "e"
        byte[] head = "d4:info".getBytes();
        System.arraycopy(head, 0, meta, 0, head.length);
        System.arraycopy(infoBytes, 0, meta, head.length, infoBytes.length);
        meta[meta.length - 1] = 'e';
        Torrent t = parse(meta);
        if (!t.trackers.isEmpty()) t.trackers.clear();
        if (trackers != null) t.trackers.addAll(trackers);
        if (displayName != null && t.name.isEmpty()) t.name = displayName;
        return t;
    }

    public boolean checkPiece(int index, byte[] buf, int len) throws Exception {
        if (index < 0 || index >= pieceCount()) return false;
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        md.update(buf, 0, len);
        byte[] got = md.digest();
        int off = index * 20;
        for (int i = 0; i < 20; i++) if (got[i] != pieces[off + i]) return false;
        return true;
    }
}
