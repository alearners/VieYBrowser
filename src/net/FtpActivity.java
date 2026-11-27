package kawaii.viey.browser;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * FTP 客户端面板：移植 Vie 6.5.0 新增的 kawaii.vie.net（Apache Commons Net）。
 * 支持目录浏览与单文件下载；SMTP/TFTP/NNTP 同库，可按需再接 UI。
 */
public class FtpActivity extends BaseActivity {

    private EditText host, user, pass, path;
    private TextView state;
    private ListView list;
    private final List<String> names = new ArrayList<>();
    private final List<FTPFile> files = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private FTPClient ftp;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ftp);

        host = findViewById(R.id.ftp_host);
        user = findViewById(R.id.ftp_user);
        pass = findViewById(R.id.ftp_pass);
        path = findViewById(R.id.ftp_path);
        state = findViewById(R.id.ftp_state);
        list = findViewById(R.id.ftp_list_view);
        findViewById(R.id.back_tool).setOnClickListener(v -> {
            close();
            finish();
        });

        user.setText("anonymous");
        list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names);
        list.setAdapter(adapter);

        findViewById(R.id.ftp_list).setOnClickListener(v -> doList());
        findViewById(R.id.ftp_download).setOnClickListener(v -> doDownload());
    }

    private boolean ensureConnected() throws Exception {
        if (ftp != null && ftp.isConnected()) return true;
        ftp = new FTPClient();
        ftp.setConnectTimeout(15000);
        ftp.connect(host.getText().toString().trim(), 21);
        int reply = ftp.getReplyCode();
        if (reply < 200 || reply >= 300) {
            close();
            throw new Exception("FTP 拒绝连接，代码 " + reply);
        }
        if (!ftp.login(user.getText().toString().trim(), pass.getText().toString())) {
            close();
            throw new Exception("登录失败，代码 " + ftp.getReplyCode());
        }
        ftp.enterLocalPassiveMode();
        ftp.setFileType(FTP.BINARY_FILE_TYPE);
        ftp.setControlKeepAliveTimeout(15);
        return true;
    }

    private void doList() {
        state.setText("读取目录…");
        new Thread(() -> {
            String err = null;
            try {
                ensureConnected();
                String dir = path.getText().toString().trim();
                if (dir.length() == 0) dir = "/";
                FTPFile[] arr = ftp.listFiles(dir);
                files.clear();
                names.clear();
                for (FTPFile f : arr) {
                    if (f.getName().equals(".") || f.getName().equals("..")) continue;
                    files.add(f);
                    names.add((f.isDirectory() ? "[目录] " : "[文件] ") + f.getName()
                            + (f.isFile() ? "  " + f.getSize() + "B" : ""));
                }
            } catch (Exception e) {
                err = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            final String ferr = err;
            ui.post(() -> {
                adapter.notifyDataSetChanged();
                state.setText(ferr == null ? ("共 " + names.size() + " 项") : ("失败：" + ferr));
                if (ferr != null) LogStore.add(this, "ftp", ferr);
            });
        }).start();
    }

    private void doDownload() {
        final int pos = list.getCheckedItemPosition();
        if (pos < 0 || pos >= files.size() || files.get(pos).isDirectory()) {
            Toast.makeText(this, "请先在列表中点选一个文件", Toast.LENGTH_SHORT).show();
            return;
        }
        final FTPFile f = files.get(pos);
        state.setText("下载 " + f.getName() + "…");
        new Thread(() -> {
            String err = null;
            File out = null;
            try {
                ensureConnected();
                File dir = getExternalFilesDir("download") != null ? getExternalFilesDir("download") : getFilesDir();
                if (!dir.exists()) dir.mkdirs();
                out = new File(dir, f.getName());
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    String base = path.getText().toString().trim();
                    String remote = (base.length() == 0 ? "" : (base.endsWith("/") ? base : base + "/")) + f.getName();
                    boolean ok = ftp.retrieveFile(remote, fos);
                    if (!ok) throw new Exception("retrieveFile 返回 false，代码 " + ftp.getReplyCode());
                }
            } catch (Exception e) {
                err = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            final String ferr = err;
            final String fpath = out == null ? "" : out.getAbsolutePath();
            final long fsize = out == null ? 0 : out.length();
            ui.post(() -> {
                state.setText(ferr == null ? ("已保存 " + fpath + "（" + (fsize / 1024) + " KB）") : ("下载失败：" + ferr));
                Toast.makeText(this, ferr == null ? "下载完成" : "下载失败", Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    private void close() {
        try {
            if (ftp != null) {
                if (ftp.isConnected()) ftp.logout();
                ftp.disconnect();
            }
        } catch (Exception ignored) {
        }
        ftp = null;
    }

    @Override
    protected void onDestroy() {
        close();
        super.onDestroy();
    }
}
