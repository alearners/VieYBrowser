package kawaii.viey.browser;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 bencode 编解码（BEP-3）。
 * decode 返回: Map<String,Object> / List<Object> / byte[] / Long。
 * 同时提供 info 段的原始字节切片（info hash 必须对原始字节做 SHA-1，不能重新编码）。
 */
public final class Bencode {

    private final byte[] data;
    private int pos;

    public Bencode(byte[] d) {
        this.data = d;
    }

    public static Object decode(byte[] d) {
        Bencode b = new Bencode(d);
        Object v = b.value();
        return v;
    }

    /** 返回 {infoStart, infoEnd}，找不到返回 null */
    public static int[] infoSpan(byte[] d) {
        Bencode b = new Bencode(d);
        if (d.length == 0 || d[0] != 'd') return null;
        b.pos = 1;
        int[] span = null;
        while (b.pos < d.length && d[b.pos] != 'e') {
            String k = b.str();
            int start = b.pos;
            b.value();
            int end = b.pos;
            if ("info".equals(k)) {
                span = new int[]{start, end};
            }
        }
        return span;
    }

    private String str() {
        int colon = pos;
        while (data[colon] != ':') colon++;
        int len = Integer.parseInt(new String(data, pos, colon - pos));
        String s = new String(data, colon + 1, len);
        pos = colon + 1 + len;
        return s;
    }

    private byte[] bytes() {
        int colon = pos;
        while (data[colon] != ':') colon++;
        int len = Integer.parseInt(new String(data, pos, colon - pos));
        byte[] r = new byte[len];
        System.arraycopy(data, colon + 1, r, 0, len);
        pos = colon + 1 + len;
        return r;
    }

    private Object value() {
        byte c = data[pos];
        if (c == 'd') {
            pos++;
            Map<String, Object> m = new LinkedHashMap<>();
            while (data[pos] != 'e') {
                String k = str();
                m.put(k, value());
            }
            pos++;
            return m;
        }
        if (c == 'l') {
            pos++;
            List<Object> l = new ArrayList<>();
            while (data[pos] != 'e') l.add(value());
            pos++;
            return l;
        }
        if (c == 'i') {
            pos++;
            int e = pos;
            while (data[e] != 'e') e++;
            long v = Long.parseLong(new String(data, pos, e - pos));
            pos = e + 1;
            return v;
        }
        return bytes();
    }

    // ---------- 编码 ----------

    public static byte[] encode(Object o) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        enc(o, out);
        return out.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private static void enc(Object o, ByteArrayOutputStream out) {
        if (o instanceof Map) {
            out.write('d');
            for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
                encStr(e.getKey(), out);
                enc(e.getValue(), out);
            }
            out.write('e');
        } else if (o instanceof List) {
            out.write('l');
            for (Object x : (List<Object>) o) enc(x, out);
            out.write('e');
        } else if (o instanceof byte[]) {
            byte[] b = (byte[]) o;
            for (byte x : String.valueOf(b.length).getBytes()) out.write(x);
            out.write(':');
            for (byte x : b) out.write(x);
        } else if (o instanceof Long || o instanceof Integer) {
            out.write('i');
            for (byte x : String.valueOf(((Number) o).longValue()).getBytes()) out.write(x);
            out.write('e');
        } else {
            encStr(String.valueOf(o), out);
        }
    }

    private static void encStr(String s, ByteArrayOutputStream out) {
        byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (byte x : String.valueOf(b.length).getBytes()) out.write(x);
        out.write(':');
        for (byte x : b) out.write(x);
    }

    public static String str(Object o) {
        if (o instanceof byte[]) {
            byte[] b = (byte[]) o;
            // 优先 UTF-8，失败回退 GBK
            try {
                return new String(b, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                return new String(b);
            }
        }
        return o == null ? "" : String.valueOf(o);
    }

    public static long num(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : 0L;
    }
}
