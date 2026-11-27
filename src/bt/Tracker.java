package kawaii.viey.browser;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** HTTP tracker announce（BEP-3 简版）：compact=1，解析 peers / peers6 */
public class Tracker {

    private static final String TAG = "viey-bt";
    private static final String UA = "VieY/0.9 (Android)";

    public static class Peer {
        public String host;
        public int port;

        public Peer(String h, int p) {
            host = h;
            port = p;
        }

        public String key() {
            return host + ":" + port;
        }
    }

    public static String peerId() {
        byte[] r = new byte[12];
        new java.util.Random().nextBytes(r);
        StringBuilder sb = new StringBuilder("-VY0900-");
        for (byte b : r) sb.append(String.format(Locale.US, "%02x", b & 0xff));
        return sb.substring(0, 20);
    }

    /** 可选：返回 {"ok":bool,"msg":String,"peers":List<Peer>} ；用 List 直接返回 peers，失败抛异常 */
    public static List<Peer> announce(String trackerUrl, byte[] infoHash, String peerId,
                                      int port, long left, int event) throws Exception {
        String base = trackerUrl;
        StringBuilder qs = new StringBuilder(base.contains("?") ? "&" : "?");
        qs.append("info_hash=").append(percent(infoHash))
                .append("&peer_id=").append(percent(peerId.getBytes(StandardCharsets.UTF_8)))
                .append("&port=").append(port)
                .append("&uploaded=0&downloaded=0&left=").append(Math.max(0, left))
                .append("&compact=1&supportcrypto=1&numwant=50");
        if (event == 1) qs.append("&event=started");
        else if (event == 2) qs.append("&event=completed");

        URL u = new URL(base + qs);
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept-Encoding", "identity");
        int code = c.getResponseCode();
        if (code != 200) throw new Exception("tracker HTTP " + code);
        byte[] body;
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
            body = bo.toByteArray();
        }
        c.disconnect();

        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) Bencode.decode(body);
        Object fail = m.get("failure reason");
        if (fail != null) throw new Exception("tracker 拒绝：" + Bencode.str(fail));

        List<Peer> peers = new ArrayList<>();
        Object p = m.get("peers");
        if (p instanceof byte[]) peers.addAll(parseCompact((byte[]) p));
        else if (p instanceof List) {
            for (Object o : (List<?>) p) {
                @SuppressWarnings("unchecked")
                Map<String, Object> pm = (Map<String, Object>) o;
                peers.add(new Peer(Bencode.str(pm.get("ip")), (int) Bencode.num(pm.get("port"))));
            }
        }
        Object p6 = m.get("peers6");
        if (p6 instanceof byte[]) peers.addAll(parseCompact6((byte[]) p6));
        Log.i(TAG, "tracker peers=" + peers.size() + " " + trackerUrl);
        return peers;
    }

    static List<Peer> parseCompact(byte[] b) {
        List<Peer> r = new ArrayList<>();
        for (int i = 0; i + 6 <= b.length; i += 6) {
            String host = (b[i] & 0xff) + "." + (b[i + 1] & 0xff) + "." + (b[i + 2] & 0xff) + "." + (b[i + 3] & 0xff);
            int port = ((b[i + 4] & 0xff) << 8) | (b[i + 5] & 0xff);
            if (port > 0) r.add(new Peer(host, port));
        }
        return r;
    }

    static List<Peer> parseCompact6(byte[] b) {
        List<Peer> r = new ArrayList<>();
        for (int i = 0; i + 18 <= b.length; i += 18) {
            byte[] ip = new byte[16];
            System.arraycopy(b, i, ip, 0, 16);
            int port = ((b[i + 16] & 0xff) << 8) | (b[i + 17] & 0xff);
            try {
                String host = InetAddress.getByAddress(ip).getHostAddress();
                if (port > 0) r.add(new Peer(host, port));
            } catch (Exception ignored) {
            }
        }
        return r;
    }

    static String percent(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            int v = x & 0xff;
            if ((v >= 'a' && v <= 'z') || (v >= 'A' && v <= 'Z') || (v >= '0' && v <= '9')
                    || v == '-' || v == '_' || v == '.' || v == '~') {
                sb.append((char) v);
            } else {
                sb.append('%').append(String.format(Locale.US, "%02X", v));
            }
        }
        return sb.toString();
    }

    public static String sha1Hex(byte[] b) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        StringBuilder sb = new StringBuilder();
        for (byte x : md.digest(b)) sb.append(String.format(Locale.US, "%02x", x & 0xff));
        return sb.toString();
    }
}
