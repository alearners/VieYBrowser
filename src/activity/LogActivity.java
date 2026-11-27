package kawaii.viey.browser;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 运行日志 / 资源日志面板（移植 Vie 6.5.0 的 log.html + awzx.journal）。
 */
public class LogActivity extends BaseActivity {

    private TextView text;
    private ScrollView scroll;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);

        text = findViewById(R.id.log_text);
        scroll = findViewById(R.id.log_scroll);
        findViewById(R.id.back_tool).setOnClickListener(v -> finish());
        findViewById(R.id.log_refresh).setOnClickListener(v -> {
            load();
            Toast.makeText(this, "已刷新", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.log_clear).setOnClickListener(v -> {
            LogStore.clear(this);
            load();
        });

        load();
    }

    private void load() {
        final String s = LogStore.tail(this, 500);
        ui.post(() -> {
            text.setText(s.length() == 0 ? "暂无日志。浏览网页后这里会记录页面加载、资源与脚本 console 输出。" : s);
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        });
    }
}
