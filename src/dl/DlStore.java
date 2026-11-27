package kawaii.viey.browser;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;

/**
 * 下载任务状态存储：文件 + 原子写（tmp → rename），供 :dl 进程与 UI 进程共享读取。
 */
public class DlStore {

    public static File stateFile(Context c) {
        return new File(c.getFilesDir(), "dl_task.json");
    }

    public static synchronized void writeAtomic(File f, String text) throws Exception {
        File tmp = new File(f.getAbsolutePath() + ".tmp");
        FileOutputStream fos = new FileOutputStream(tmp);
        fos.write(text.getBytes("UTF-8"));
        fos.close();
        if (f.exists() && !f.delete()) {
            // 部分机型 rename 覆盖失败时退化为原地截断写
            RandomAccessFile raf = new RandomAccessFile(f, "rw");
            raf.setLength(0);
            raf.write(text.getBytes("UTF-8"));
            raf.close();
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(f)) {
            RandomAccessFile raf = new RandomAccessFile(f, "rw");
            raf.setLength(0);
            raf.write(text.getBytes("UTF-8"));
            raf.close();
            tmp.delete();
        }
    }

    /** 写任务快照：状态 reading/running/paused/done/failed、进度、速度、消息 */
    public static void publish(Context c, String name, String state, long done, long total,
                               String note, String msg) {
        try {
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("state", state);
            o.put("done", done);
            o.put("total", total);
            o.put("note", note == null ? "" : note);
            o.put("msg", msg == null ? "" : msg);
            o.put("ts", System.currentTimeMillis());
            writeAtomic(stateFile(c), o.toString());
        } catch (Exception ignored) {
        }
    }

    public static JSONObject read(Context c) {
        try {
            File f = stateFile(c);
            if (!f.exists()) return null;
            return new JSONObject(PluginStore.read(f));
        } catch (Exception e) {
            return null;
        }
    }
}
