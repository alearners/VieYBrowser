package kawaii.viey.browser;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;

/**
 * 定时后台刷新订阅源（JobScheduler 周期任务，需网络）。
 * 开关与间隔存在 prefs：rss_auto_refresh / rss_interval_min。
 */
public class FeedRefreshJob extends JobService {

    public static final int JOB_ID = 4101;

    public static boolean isAuto(Context c) {
        return getPrefs(c).getBoolean("rss_auto_refresh", false);
    }

    public static void setAuto(Context c, boolean on) {
        getPrefs(c).edit().putBoolean("rss_auto_refresh", on).apply();
        if (on) schedule(c);
        else cancel(c);
    }

    public static int interval(Context c) {
        return getPrefs(c).getInt("rss_interval_min", 60);
    }

    public static void setInterval(Context c, int min) {
        getPrefs(c).edit().putInt("rss_interval_min", min).apply();
        if (isAuto(c)) schedule(c);
    }

    private static SharedPreferences getPrefs(Context c) {
        return VieYApp.getPrefs(c);
    }

    public static void schedule(Context c) {
        try {
            JobSchedulerHolder.js(c).schedule(build(c).build());
        } catch (Exception e) {
            android.util.Log.w("viey-rss", "定时刷新注册失败：" + e.getMessage());
        }
    }

    public static void cancel(Context c) {
        try {
            JobSchedulerHolder.js(c).cancel(JOB_ID);
        } catch (Exception ignored) {
        }
    }

    /** 兼容老 API：JobScheduler 从 API 21 起可用，本项目 minSdk 21 */
    private static JobInfo.Builder build(Context c) {
        long min = Math.max(15, interval(c)) * 60000L;
        return new JobInfo.Builder(JOB_ID, new ComponentName(c, FeedRefreshJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setPeriodic(min);
    }

    private static class JobSchedulerHolder {
        static android.app.job.JobScheduler js(Context c) {
            return (android.app.job.JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        final Context ctx = getApplicationContext();
        new Thread(() -> {
            int ok = 0;
            try {
                ok = FeedRepo.refreshAll(ctx);
            } catch (Exception e) {
                android.util.Log.w("viey-rss", "定时刷新异常：" + e.getMessage());
            }
            android.util.Log.i("viey-rss", "定时刷新完成：" + ok + " 个源");
            jobFinished(params, false);
        }, "feed-job").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
