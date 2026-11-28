package kawaii.viey.browser;

import java.io.ByteArrayOutputStream;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** magnet:?xt=urn:btih:<hex40|base32-32>[&dn=][&tr=][&xl=] 解析（BEP-9 的入口信息） */
public class Magnet {

    public String infoHashHex = "";
    public String displayName = "";
    public long exactLength = 0;
    public final List<String> trackers = new ArrayList<>();

    public static Magnet parse(String uri) throws Exception {
        Magnet m = new Magnet();
        if (uri == null) throw new Exception("空链接");
        String s = uri.trim();
        if (!s.toLowerCase(Locale.US).startsWith("magnet:")) throw new Exception("不是 magnet 链接");
        int q = s.indexOf('?');
        if (q < 0) throw new Exception("magnet 缺少参数");
        String query = s.substring(q + 1);
        for (String kv : query.split("&")) {
            if (kv.isEmpty()) continue;
            int eq = kv.indexOf('=');
            if (eq < 0) continue;
            String k = kv.substring(0, eq);
            String v = URLDecoder.decode(kv.substring(eq + 1), "UTF-8");
            if ("xt".equals(k)) {
                String low = v.toLowerCase(Locale.US);
                if (low.startsWith("urn:btih:")) {
                    String h = v.substring(9).trim();
                    if (h.length() == 40) m.infoHashHex = h.toLowerCase(Locale.US);
                    else if (h.length() == 32) m.infoHashHex = base32ToHex(h);
                    else throw new Exception("btih 长度非法：" + h.length());
                }
            } else if ("dn".equals(k)) {
                m.displayName = v;
            } else if ("xl".equals(k)) {
                try {
                    m.exactLength = Long.parseLong(v.trim());
                } catch (Exception ignored) {
                }
            } else if ("tr".equals(k)) {
                if (!m.trackers.contains(v)) m.trackers.add(v);
            }
        }
        if (m.infoHashHex.length() != 40) throw new Exception("缺少 xt=urn:btih");
        return m;
    }

    public byte[] infoHash() {
        byte[] b = new byte[20];
        for (int i = 0; i < 20; i++) {
            b[i] = (byte) Integer.parseInt(infoHashHex.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }

    /** RFC4648 base32（bitTorrent 用大写 A-Z2-7）→ 40 位 hex */
    public static String base32ToHex(String b32) throws Exception {
        String s = b32.toUpperCase(Locale.US);
        StringBuilder bits = new StringBuilder();
        for (char c : s.toCharArray()) {
            int v;
            if (c >= 'A' && c <= 'Z') v = c - 'A';
            else if (c >= '2' && c <= '7') v = 26 + (c - '2');
            else throw new Exception("base32 非法字符：" + c);
            String bin = Integer.toBinaryString(v);
            while (bin.length() < 5) bin = "0" + bin;
            bits.append(bin);
        }
        if (bits.length() < 160) throw new Exception("base32 长度不足");
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < 160; i += 4) {
            hex.append(Integer.toHexString(Integer.parseInt(bits.substring(i, i + 4), 2)));
        }
        return hex.toString().toLowerCase(Locale.US);
    }

    public String toUri() {
        StringBuilder sb = new StringBuilder("magnet:?xt=urn:btih:").append(infoHashHex);
        if (!displayName.isEmpty()) sb.append("&dn=").append(displayName);
        for (String tr : trackers) sb.append("&tr=").append(tr);
        return sb.toString();
    }

    /**
     * magnet 没写 tr，或 tr 全是 UDP（本内核只支持 HTTP(S)）时，补一组默认 HTTP tracker，
     * 否则一定取不到 peer，BEP-9 元数据永远拉不下来。
     */
    public boolean ensureHttpTrackers() {
        for (String t : trackers) {
            if (t != null && t.toLowerCase(Locale.US).startsWith("http")) return false;
        }
        int n = 0;
        for (String d : Tracker.DEFAULT_TRACKERS) {
            if (!trackers.contains(d)) {
                trackers.add(d);
                n++;
            }
        }
        return n > 0;
    }
}
