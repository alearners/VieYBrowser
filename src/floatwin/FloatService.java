package kawaii.viey.browser;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

/**
 * 悬浮窗浏览：Service + WebView 浮层（对应 6.5.0 的悬浮窗能力，改动局部）。
 */
public class FloatService extends Service {

    public static final String EXTRA_URL = "float_url";
    private static final String CH_ID = "viey_float";
    private static final int CH_NID = 0x7601;

    private static volatile boolean showing = false;

    public static boolean isShowing() {
        return showing;
    }

    private WindowManager wm;
    private View root;
    private WebView web;
    private WindowManager.LayoutParams lp;
    private int lastX, lastY;
    private float downX, downY;

    public static void show(Context c, String url) {
        Intent it = new Intent(c, FloatService.class).putExtra(EXTRA_URL, url);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(it);
        else c.startService(it);
    }

    public static void hide(Context c) {
        c.stopService(new Intent(c, FloatService.class));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        showing = true;
        startAsForeground();
        if (root == null) attach();
        String url = intent != null ? intent.getStringExtra(EXTRA_URL) : null;
        if (url != null && url.length() > 0) {
            ((TextView) root.findViewById(R.id.float_title)).setText(shortUrl(url));
            web.loadUrl(url);
        }
        return START_NOT_STICKY;
    }

    private void startAsForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH_ID, "悬浮窗浏览", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH_ID) : new Notification.Builder(this);
        b.setContentTitle("悬浮窗浏览中").setContentText("点按回到浏览器")
                .setSmallIcon(R.mipmap.icon).setContentIntent(pi).setOngoing(true);
        startForeground(CH_NID, b.build());
    }

    private void attach() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        LayoutInflater li = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
        root = li.inflate(R.layout.float_window, null);

        lp = new WindowManager.LayoutParams(
                (int) (getResources().getDisplayMetrics().widthPixels * 0.86),
                (int) (getResources().getDisplayMetrics().heightPixels * 0.62),
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = (int) (getResources().getDisplayMetrics().widthPixels * 0.07);
        lp.y = (int) (getResources().getDisplayMetrics().heightPixels * 0.12);

        web = root.findViewById(R.id.float_web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(WebView v, android.webkit.WebResourceRequest req) {
                android.webkit.WebResourceResponse r = FontInject.intercept(getApplicationContext(), req);
                if (r == null) r = VieAsset.intercept(getApplicationContext(), req);
                return r;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                ((TextView) root.findViewById(R.id.float_title)).setText(shortUrl(url));
                FontInject.inject(v, url);
                String plugin = PluginStore.buildInjection(getApplicationContext(), url);
                if (plugin.length() > 0) v.evaluateJavascript(plugin, null);
            }
        });

        root.findViewById(R.id.float_close).setOnClickListener(v -> stopSelf());

        View header = root.findViewById(R.id.float_header);
        header.setOnTouchListener((v, e) -> {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = lp.x;
                    lastY = lp.y;
                    downX = e.getRawX();
                    downY = e.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    lp.x = lastX + (int) (e.getRawX() - downX);
                    lp.y = lastY + (int) (e.getRawY() - downY);
                    wm.updateViewLayout(root, lp);
                    return true;
                default:
                    return false;
            }
        });

        wm.addView(root, lp);
        web.loadUrl("about:blank");
    }

    private static String shortUrl(String u) {
        if (u == null) return "悬浮窗";
        try {
            String h = android.net.Uri.parse(u).getHost();
            return h == null ? u : h;
        } catch (Exception e) {
            return u;
        }
    }

    @Override
    public void onDestroy() {
        showing = false;
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
        if (root != null && wm != null) {
            try {
                wm.removeView(root);
            } catch (Exception ignored) {
            }
            root = null;
        }
        super.onDestroy();
    }
}
