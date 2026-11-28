package kawaii.viey.browser;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.List;

/**
 * 订阅源规则编辑器：读写 Legado 字段（ruleArticles/ruleContent 等），支持 @@css: 与 @js: 前缀。
 * 带「测试」：抓一次地址，列出解析到的前几条标题；正文规则给出提取长度。
 */
public class FeedEditActivity extends BaseActivity {

    public static final String EXTRA_SOURCE_JSON = "edit_source_json";
    public static final String EXTRA_IS_NEW = "edit_is_new";
    public static final String EXTRA_ORIG_URL = "edit_orig_url";
    public static final String EXTRA_ORIG_NAME = "edit_orig_name";

    private EditText etName, etGroup, etUrl, etHeader, etArticles, etTitle, etLink, etDate, etDesc, etImage, etContent, etNext;
    private CheckBox cbEnabled;
    private TextView hint;
    private FeedSource src = new FeedSource();
    private boolean isNew;
    private String origUrl, origName;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_feed_edit);

        etName = findViewById(R.id.et_name);
        etGroup = findViewById(R.id.et_group);
        etUrl = findViewById(R.id.et_url);
        etHeader = findViewById(R.id.et_header);
        etArticles = findViewById(R.id.et_articles);
        etTitle = findViewById(R.id.et_title);
        etLink = findViewById(R.id.et_link);
        etDate = findViewById(R.id.et_date);
        etDesc = findViewById(R.id.et_desc);
        etImage = findViewById(R.id.et_image);
        etContent = findViewById(R.id.et_content);
        etNext = findViewById(R.id.et_nextpage);
        cbEnabled = findViewById(R.id.cb_enabled);
        hint = findViewById(R.id.edit_hint);

        isNew = getIntent().getBooleanExtra(EXTRA_IS_NEW, false);
        origUrl = getIntent().getStringExtra(EXTRA_ORIG_URL);
        origName = getIntent().getStringExtra(EXTRA_ORIG_NAME);
        String json = getIntent().getStringExtra(EXTRA_SOURCE_JSON);
        if (json != null) {
            try {
                src = FeedSource.fromLegado(new JSONObject(json));
            } catch (Exception ignored) {
            }
        }
        bind();

        findViewById(R.id.back_tool).setOnClickListener(v -> finish());
        findViewById(R.id.edit_save).setOnClickListener(v -> save());
        findViewById(R.id.edit_test).setOnClickListener(v -> test());
        ((TextView) findViewById(R.id.edit_title)).setText(isNew ? "新增订阅源" : "编辑订阅源");
        if (!isNew) {
            // 长按删除入口放在标题上
            findViewById(R.id.edit_title).setOnLongClickListener(v -> {
                new AlertDialog.Builder(this)
                        .setTitle("删除订阅源")
                        .setMessage(src.displayTitle())
                        .setPositiveButton("删除", (d, w) -> {
                            FeedStore.removeOne(this, origUrl == null ? src.url : origUrl,
                                    origName == null ? src.displayTitle() : origName);
                            setResult(RESULT_OK);
                            finish();
                        })
                        .setNegativeButton("取消", null)
                        .show();
                return true;
            });
        }
    }

    private void bind() {
        etName.setText(src.name);
        etGroup.setText(src.group);
        etUrl.setText(src.url);
        etHeader.setText(src.header);
        etArticles.setText(src.ruleArticles);
        etTitle.setText(src.ruleTitle);
        etLink.setText(src.ruleLink);
        etDate.setText(src.rulePubDate);
        etDesc.setText(src.ruleDescription);
        etImage.setText(src.ruleImage);
        etContent.setText(src.ruleContent);
        etNext.setText(src.ruleNextPage);
        cbEnabled.setChecked(src.enabled);
    }

    private void collect() {
        src.name = t(etName);
        src.group = t(etGroup);
        src.url = t(etUrl);
        src.header = t(etHeader);
        src.ruleArticles = t(etArticles);
        src.ruleTitle = t(etTitle);
        src.ruleLink = t(etLink);
        src.rulePubDate = t(etDate);
        src.ruleDescription = t(etDesc);
        src.ruleImage = t(etImage);
        src.ruleContent = t(etContent);
        src.ruleNextPage = t(etNext);
        src.enabled = cbEnabled.isChecked();
        if (TextUtils.isEmpty(src.name)) src.name = src.url;
    }

    private String t(EditText e) {
        return e.getText().toString().trim();
    }

    private void save() {
        collect();
        if (TextUtils.isEmpty(src.url)) {
            Toast.makeText(this, "订阅地址不能为空", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isNew && origUrl != null) FeedStore.replace(this, origUrl, origName == null ? src.displayTitle() : origName, src);
        else FeedStore.upsert(this, src);
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    private void test() {
        collect();
        if (TextUtils.isEmpty(src.url)) {
            Toast.makeText(this, "先填订阅地址", Toast.LENGTH_SHORT).show();
            return;
        }
        hint.setText("测试中…");
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            try {
                FeedRepo.FetchResult res = FeedRepo.load(this, src);
                String body = res.body;
                sb.append("抓取 ").append(res.bytes).append(" 字节，方式：").append(res.mode);
                if (res.rendered > 0) sb.append("（渲染后 ").append(res.rendered).append(" 字节）");
                sb.append("\n");
                List<FeedParser.Article> list = res.items;
                sb.append("解析到 ").append(list.size()).append(" 条\n\n");
                for (int i = 0; i < Math.min(5, list.size()); i++) {
                    FeedParser.Article a = list.get(i);
                    sb.append(i + 1).append(". ").append(TextUtils.isEmpty(a.title) ? "(无标题)" : a.title).append('\n');
                    if (!TextUtils.isEmpty(a.pubDate)) sb.append("   ").append(a.pubDate).append('\n');
                    if (!TextUtils.isEmpty(a.link)) sb.append("   ").append(a.link).append('\n');
                }
                String rule = src.ruleContent;
                if (!TextUtils.isEmpty(rule)) {
                    if (rule.trim().startsWith("@js:")) {
                        sb.append("\n正文规则是 @js:，需在阅读页用 WebView 执行，这里不预览");
                    } else {
                        String c = ContentExtractor.extract(body, src.url, src);
                        sb.append("\n正文提取 ").append(c == null ? 0 : c.length()).append(" 字符");
                        if (!TextUtils.isEmpty(c)) {
                            String plain = c.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
                            sb.append("\n").append(plain.length() > 160 ? plain.substring(0, 160) + "…" : plain);
                        }
                    }
                }
            } catch (Exception e) {
                sb.append("测试失败：").append(e.getMessage());
            }
            final String out = sb.toString();
            ui.post(() -> {
                hint.setText(isNew ? "保存后会在列表里出现" : "改动保存后立即应用到该源");
                new AlertDialog.Builder(this)
                    .setTitle("规则测试")
                    .setMessage(out)
                    .setPositiveButton("好", null)
                    .show();
            });
        }, "rule-test").start();
    }

    public static Intent intent(FeedActivity from, FeedSource s, boolean isNew) {
        Intent it = new Intent(from, FeedEditActivity.class);
        it.putExtra(EXTRA_IS_NEW, isNew);
        if (s != null) {
            it.putExtra(EXTRA_SOURCE_JSON, s.toLegado().toString());
            it.putExtra(EXTRA_ORIG_URL, s.url);
            it.putExtra(EXTRA_ORIG_NAME, s.displayTitle());
        }
        return it;
    }
}
