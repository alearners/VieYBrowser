package kawaii.viey.browser;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 下载服务：跑在独立进程 :dl（Android 没有 fork，多进程 = 独立 Service 进程），
 * 好处是浏览器主进程被回收/切后台时下载不中断，两个进程只通过 files 目录里的 JSON 快照通信。
 *
 * 指令：ACTION_START（urls/name/size/sha256）、ACTION_PAUSE、ACTION_STOP。
 */
public class DlService extends Service {

    public static final String ACTION_START = "kawaii.viey.browser.DL_START";
    public static final String ACTION_PAUSE = "kawaii.viey.browser.DL_PAUSE";
    public static final String ACTION_STOP = "kawaii.viey.browser.DL_STOP";
    private static final String CHANNEL = "viey_dl";
    private static final int NOTIFY_ID = 0x0D1;

    private HttpSegDown cur;

    public static void startTask(Context c, String name, long size, List<String> urls, String sha256) {
        Intent i = new Intent(c, DlService.class);
        i.setAction(ACTION_START);
        i.putExtra("name", name);
        i.putExtra("size", size);
        i.putExtra("sha256", sha256);
        JSONArray a = new JSONArray();
        for (String u : urls) a.put(u);
        i.putExtra("urls", a.toString());
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    public static void pauseTask(Context c) {
        Intent i = new Intent(c, DlService.class);
        i.setAction(ACTION_PAUSE);
        c.startService(i);
    }

    public static void stopTask(Context c) {
        Intent i = new Intent(c, DlService.class);
        i.setAction(ACTION_STOP);
        c.startService(i);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_PAUSE.equals(action)) {
            if (cur != null) cur.pause();
            DlStore.publish(this, nameOf(), "paused", 0, 0, "", "已暂停");
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(action)) {
            if (cur != null) cur.pause();
            cur = null;
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            if (cur != null) cur.pause();
            String name = intent.getStringExtra("name");
            long size = intent.getLongExtra("size", 0);
            String sha = intent.getStringExtra("sha256");
            List<String> urls = new ArrayList<>();
            try {
                JSONArray a = new JSONArray(intent.getStringExtra("urls"));
                for (int i = 0; i < a.length(); i++) urls.add(a.getString(i));
            } catch (Exception ignored) {
            }
            if (name == null || urls.isEmpty()) {
                DlStore.publish(this, "?", "failed", 0, 0, "", "缺少文件名或下载地址");
                return START_NOT_STICKY;
            }
            int conns = VieYApp.getPrefs(this).getInt("dl_conns", 8);
            startForeground(NOTIFY_ID, notification("准备下载 " + name, 0, 0));
            DlStore.publish(this, name, "running", 0, size, "启动", "");
            final HttpSegDown.Cb cb = new HttpSegDown.Cb() {
                @Override
                public void onProgress(long done, long total, String note) {
                    DlStore.publish(DlService.this, name, "running", done, total, note, "");
                    notifyProgress(name, done, total, note);
                }

                @Override
                public void onDone(boolean ok, String msg, File file) {
                    long t = cur != null ? cur.totalSize() : size;
                    DlStore.publish(DlService.this, name, ok ? "done" : "failed", t, t, "", msg);
                    NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                    if (nm != null) nm.notify(NOTIFY_ID, notification(ok ? "下载完成 " + name : "下载失败：" + msg, 0, 0));
                    stopForeground(false);
                    stopSelf();
                }
            };
            cur = new HttpSegDown(this, name, size, urls, conns, sha);
            cur.start(cb);
            return START_NOT_STICKY;
        }
        return START_NOT_STICKY;
    }

    private String nameOf() {
        JSONObject o = DlStore.read(this);
        return o == null ? "?" : o.optString("name", "?");
    }

    private void notifyProgress(String name, long done, long total, String note) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        int pct = total > 0 ? (int) (done * 100 / total) : 0;
        nm.notify(NOTIFY_ID, notification(name + " " + pct + "%", done, total));
    }

    private Notification notification(String text, long done, long total) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "下载", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        Intent open = new Intent(this, DlActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        b.setContentTitle("VieY 下载")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(pi)
                .setOngoing(true);
        if (total > 0) {
            b.setProgress(100, (int) (done * 100 / total), false);
        }
        return b.build();
    }
}
