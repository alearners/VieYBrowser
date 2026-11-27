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

import java.io.File;
import java.util.ArrayList;

/**
 * eD2k 前台下载服务（含上传服务生命周期）。
 * 把 Ed2kDownload 放进来，避免切后台被系统杀；同时负责通知栏进度。
 */
public class Ed2kService extends Service {

    public static final String EXTRA_NAME = "ed2k_name";
    public static final String EXTRA_SIZE = "ed2k_size";
    public static final String EXTRA_HASH = "ed2k_hash";
    public static final String EXTRA_SERVER = "ed2k_server";
    public static final String EXTRA_SOURCES = "ed2k_sources";

    private static final String CH = "viey_ed2k";
    private static final int NID = 0x7602;

    private static volatile Ed2kDownload download;
    private static volatile String lastState = "";

    public static Ed2kDownload current() {
        return download;
    }

    public static String lastState() {
        return lastState;
    }

    public static void start(Context c, Ed2kLink link) {
        Intent it = new Intent(c, Ed2kService.class)
                .putExtra(EXTRA_NAME, link.name)
                .putExtra(EXTRA_SIZE, link.size)
                .putExtra(EXTRA_HASH, link.hash)
                .putExtra(EXTRA_SERVER, link.serverHost + ":" + link.serverPort)
                .putStringArrayListExtra(EXTRA_SOURCES, new ArrayList<>(link.sources));
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(it);
        else c.startService(it);
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, Ed2kService.class));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensureChannel();
        startForeground(NID, build("eD2k 下载准备中", "0 B/s"));

        if (download == null && intent != null) {
            Ed2kLink link = new Ed2kLink();
            link.name = intent.getStringExtra(EXTRA_NAME);
            link.size = intent.getLongExtra(EXTRA_SIZE, 0);
            link.hash = intent.getStringExtra(EXTRA_HASH);
            String sp = intent.getStringExtra(EXTRA_SERVER);
            if (sp != null && sp.contains(":")) {
                link.serverHost = sp.substring(0, sp.lastIndexOf(':'));
                try {
                    link.serverPort = Integer.parseInt(sp.substring(sp.lastIndexOf(':') + 1));
                } catch (Exception ignored) {
                }
            }
            ArrayList<String> src = intent.getStringArrayListExtra(EXTRA_SOURCES);
            if (src != null) link.sources.addAll(src);

            download = new Ed2kDownload(this, link);
            download.setListener(new Ed2kDownload.Listener() {
                @Override
                public void onState(String state) {
                    lastState = state;
                    updateNotify("eD2k 下载中", state);
                }

                @Override
                public void onProgress(long done, long total, int peers, int speedBps, int blocksDone, int blocksTotal) {
                    String text = fmt(done) + " / " + fmt(total) + " · " + (speedBps / 1024) + " KB/s · 对端 " + peers
                            + " · 块 " + blocksDone + "/" + blocksTotal;
                    updateNotify("eD2k 下载中", text);
                }

                @Override
                public void onDone(boolean ok, String msg, File file) {
                    updateNotify(ok ? "eD2k 下载完成" : "eD2k 下载结束", msg);
                    stopSelf();
                }
            });
            download.start();
            Ed2kUploader.instance(this).start();
            KadNode.instance(this).startUdp();
        }
        return START_NOT_STICKY;
    }

    private static String fmt(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / 1048576.0);
        return String.format("%.2f GB", bytes / 1073741824.0);
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CH, "eD2k 下载", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
    }

    private Notification build(String title, String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        b.setContentTitle(title).setContentText(text).setSmallIcon(R.mipmap.icon).setContentIntent(pi).setOngoing(true);
        return b.build();
    }

    private void updateNotify(String title, String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NID, build(title, text));
    }

    @Override
    public void onDestroy() {
        if (download != null) {
            download.stop();
            download = null;
        }
        Ed2kUploader.instance(this).stop();
        KadNode.instance(this).stopUdp();
        super.onDestroy();
    }
}
