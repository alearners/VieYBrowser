package kawaii.viey.browser;

import java.util.ArrayList;
import java.util.List;

/**
 * ed2k:// 链接解析：file / server / sources。
 * ed2k://|file|name|size|hash|/            文件
 * ed2k://|file|name|size|hash|sources,ip:port,ip:port|/  带源
 * ed2k://|server|ip|port|/                 服务器
 */
public class Ed2kLink {

    public String name = "";
    public long size = 0;
    public String hash = "";
    public final List<String> sources = new ArrayList<>();
    public String serverHost = "";
    public int serverPort = 0;

    public static boolean looksLike(String s) {
        return s != null && s.trim().toLowerCase().startsWith("ed2k://");
    }

    public static Ed2kLink parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (!s.toLowerCase().startsWith("ed2k://")) return null;
        String body = s.substring("ed2k://".length());
        if (body.startsWith("|")) body = body.substring(1);
        String[] parts = body.split("\\|", -1);
        Ed2kLink link = new Ed2kLink();
        if (parts.length >= 2 && parts[0].equalsIgnoreCase("file")) {
            link.name = parts.length > 1 ? parts[1] : "";
            try {
                link.size = parts.length > 2 ? Long.parseLong(parts[2].trim()) : 0;
            } catch (Exception ignored) {
            }
            link.hash = parts.length > 3 ? parts[3].trim().toLowerCase() : "";
            if (parts.length > 4) {
                String src = parts[4];
                if (src.startsWith("sources,")) src = src.substring("sources,".length());
                for (String one : src.split(",")) {
                    String t = one.trim();
                    if (t.matches("\\d{1,3}(\\.\\d{1,3}){3}:\\d+")) link.sources.add(t);
                }
            }
            return link;
        }
        if (parts.length >= 3 && parts[0].equalsIgnoreCase("server")) {
            link.serverHost = parts[1];
            try {
                link.serverPort = Integer.parseInt(parts[2].trim());
            } catch (Exception ignored) {
            }
            return link;
        }
        return null;
    }

    public String toEd2k() {
        return "ed2k://|file|" + name + "|" + size + "|" + hash + "|/";
    }

    public String describe() {
        if (serverHost.length() > 0) return "服务器 " + serverHost + ":" + serverPort;
        return name + "\n" + size + " 字节 · " + hash
                + (sources.isEmpty() ? "" : "\n源 " + sources.size() + " 个");
    }
}
