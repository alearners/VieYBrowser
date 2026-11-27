package kawaii.viey.browser;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 运行日志 / 资源日志（journal）存储，移植自 Vie 6.5.0 的 log.html + awzx.console。
 */
public class LogStore {

    private static final String DIR = "logs";
    private static final String FILE = "vie.log";
    private static final long MAX = 512 * 1024L;
    private static final Object LOCK = new Object();

    private static File file(Context c) {
        File d = new File(c.getFilesDir(), DIR);
        if (!d.exists()) d.mkdirs();
        return new File(d, FILE);
    }

    public static void add(Context c, String level, String msg) {
        if (c == null || msg == null) return;
        String t = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date());
        String line = t + " [" + (level == null ? "log" : level) + "] " + msg.replace('\n', ' ') + "\n";
        synchronized (LOCK) {
            try {
                File f = file(c);
                if (f.exists() && f.length() > MAX) f.delete();
                try (FileOutputStream out = new FileOutputStream(f, true)) {
                    out.write(line.getBytes("UTF-8"));
                }
            } catch (Exception ignored) {
            }
        }
    }

    /** 资源日志：移植 journal 脚本，记录资源类型与地址 */
    public static void journal(Context c, String type, String url) {
        String t = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        add(c, "res", t + "#" + type + "#" + url);
    }

    public static String tail(Context c, int lines) {
        File f = file(c);
        if (!f.exists()) return "";
        String all;
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            all = bo.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
        String[] arr = all.split("\n");
        int from = Math.max(0, arr.length - lines);
        StringBuilder sb = new StringBuilder();
        for (int k = from; k < arr.length; k++) sb.append(arr[k]).append('\n');
        return sb.toString();
    }

    public static void clear(Context c) {
        synchronized (LOCK) {
            file(c).delete();
        }
    }

    public static List<String> lines(Context c, int max) {
        List<String> out = new ArrayList<>();
        String s = tail(c, max);
        if (s.length() == 0) return out;
        for (String l : s.split("\n")) {
            if (l.length() > 0) out.add(l);
        }
        return out;
    }
}
