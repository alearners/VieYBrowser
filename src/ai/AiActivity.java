package kawaii.viey.browser;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 对话页：既可作为普通聊天使用，也可接收网页正文做总结。
 *
 * 界面完全以代码构建，避免为单一页面新增布局文件；配色跟随应用的夜间模式。
 * 网络请求走 {@link AiClient} 的流式接口，逐段上屏。
 */
public class AiActivity extends BaseActivity {

	public static final String EXTRA_TEXT = "ai_text";
	public static final String EXTRA_TITLE = "ai_title";
	public static final String EXTRA_URL = "ai_url";

	private static final int MAX_SUMMARY_CHARS = 12000;

	private LinearLayout msgBox;
	private ScrollView scroll;
	private EditText input;
	private Button send;
	private TextView status;

	private final List<AiClient.Msg> history = new ArrayList<>();
	private boolean busy = false;

	/** 打开聊天页 */
	public static void open(Context c) {
		c.startActivity(new Intent(c, AiActivity.class));
	}

	/** 打开总结页：正文由调用方抽取后传入 */
	public static void summarize(Context c, String title, String url, String body) {
		Intent it = new Intent(c, AiActivity.class);
		it.putExtra(EXTRA_TITLE, title);
		it.putExtra(EXTRA_URL, url);
		it.putExtra(EXTRA_TEXT, body);
		c.startActivity(it);
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(buildRoot());
		history.add(AiClient.system(AiConfig.systemPrompt(this)));

		String text = getIntent().getStringExtra(EXTRA_TEXT);
		if (!TextUtils.isEmpty(text)) {
			appendBubble("正在总结：" + shortTitle(), false);
			askSummary(text);
		} else if (!AiConfig.ready(this)) {
			appendBubble("尚未配置 AI 接口。点右上角「设置」填写接口地址、模型与密钥。", false);
		}
	}

	private String shortTitle() {
		String t = getIntent().getStringExtra(EXTRA_TITLE);
		if (TextUtils.isEmpty(t)) t = getIntent().getStringExtra(EXTRA_URL);
		if (TextUtils.isEmpty(t)) return "当前网页";
		return t.length() > 40 ? t.substring(0, 40) + "…" : t;
	}

	private View buildRoot() {
		boolean dark = isDark();
		int bg = dark ? 0xFF141414 : 0xFFFFFFFF;
		int barBg = dark ? 0xFF1C1C1C : 0xFFF7F7F7;
		int textColor = dark ? 0xFFEDEDED : 0xFF1A1A1A;
		int hintColor = dark ? 0xFF8A8A8A : 0xFF8A8A8A;

		LinearLayout root = new LinearLayout(this);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setBackgroundColor(bg);

		// 顶部栏
		LinearLayout bar = new LinearLayout(this);
		bar.setOrientation(LinearLayout.HORIZONTAL);
		bar.setGravity(Gravity.CENTER_VERTICAL);
		bar.setBackgroundColor(barBg);
		bar.setPadding(dp(14), dp(10), dp(8), dp(10));

		TextView title = new TextView(this);
		title.setText(TextUtils.isEmpty(getIntent().getStringExtra(EXTRA_TEXT)) ? "AI 助手" : "网页总结");
		title.setTextSize(17);
		title.setTextColor(textColor);
		title.setSingleLine(true);
		title.setLayoutParams(new LinearLayout.LayoutParams(0,
			ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		bar.addView(title);

		TextView cfg = new TextView(this);
		cfg.setText("设置");
		cfg.setTextSize(14);
		cfg.setTextColor(0xFF4C8DFF);
		cfg.setPadding(dp(10), dp(6), dp(10), dp(6));
		cfg.setOnClickListener(v -> showConfigDialog());
		bar.addView(cfg);

		TextView close = new TextView(this);
		close.setText("关闭");
		close.setTextSize(14);
		close.setTextColor(0xFF4C8DFF);
		close.setPadding(dp(10), dp(6), dp(6), dp(6));
		close.setOnClickListener(v -> finish());
		bar.addView(close);
		root.addView(bar);

		// 消息区
		scroll = new ScrollView(this);
		scroll.setLayoutParams(new LinearLayout.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
		msgBox = new LinearLayout(this);
		msgBox.setOrientation(LinearLayout.VERTICAL);
		msgBox.setPadding(dp(12), dp(12), dp(12), dp(12));
		scroll.addView(msgBox);
		root.addView(scroll);

		// 状态行
		status = new TextView(this);
		status.setTextSize(12);
		status.setTextColor(hintColor);
		status.setPadding(dp(14), 0, dp(14), dp(4));
		status.setText("输入问题后发送；回答较长时点击任意气泡复制");
		root.addView(status);

		// 输入区
		LinearLayout bar2 = new LinearLayout(this);
		bar2.setOrientation(LinearLayout.HORIZONTAL);
		bar2.setGravity(Gravity.CENTER_VERTICAL);
		bar2.setBackgroundColor(barBg);
		bar2.setPadding(dp(10), dp(8), dp(10), dp(8));

		input = new EditText(this);
		input.setHint("输入问题…");
		input.setHintTextColor(hintColor);
		input.setTextColor(textColor);
		input.setTextSize(15);
		input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
		input.setMinLines(1);
		input.setMaxLines(4);
		input.setBackgroundColor(0x00000000);
		input.setLayoutParams(new LinearLayout.LayoutParams(0,
			ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		bar2.addView(input);

		send = new Button(this);
		send.setText("发送");
		send.setAllCaps(false);
		send.setOnClickListener(v -> sendUserInput());
		bar2.addView(send);
		root.addView(bar2);

		return root;
	}

	private void sendUserInput() {
		if (busy) return;
		String q = input.getText().toString().trim();
		if (q.isEmpty()) return;
		input.setText("");
		appendBubble(q, true);
		history.add(AiClient.user(q));
		request();
	}

	private void askSummary(String body) {
		String cut = body.length() > MAX_SUMMARY_CHARS ? body.substring(0, MAX_SUMMARY_CHARS) : body;
		String url = getIntent().getStringExtra(EXTRA_URL);
		String prompt = AiConfig.summaryPrompt(this)
			+ (TextUtils.isEmpty(url) ? "" : "\n\n来源：" + url)
			+ "\n\n正文：\n" + cut;
		history.add(AiClient.user(prompt));
		request();
	}

	private void request() {
		if (!AiConfig.ready(this)) {
			appendBubble("尚未配置 AI 接口。点右上角「设置」填写接口地址、模型与密钥。", false);
			return;
		}
		busy = true;
		send.setEnabled(false);
		status.setText("生成中…");
		final TextView out = appendBubble("", false);
		final StringBuilder acc = new StringBuilder();

		new Thread(() -> AiClient.chatStream(this, new ArrayList<>(history), new AiClient.StreamCb() {
			@Override
			public void onDelta(final String text) {
				acc.append(text);
				runOnUiThread(() -> {
					out.setText(acc.toString());
					scrollDown();
				});
			}

			@Override
			public void onDone(final String full) {
				runOnUiThread(() -> {
					if (full.isEmpty()) out.setText("（模型未返回内容）");
					history.add(AiClient.assistant(full));
					busy = false;
					send.setEnabled(true);
					status.setText("完成。点气泡可复制");
					scrollDown();
				});
			}

			@Override
			public void onError(final String message) {
				runOnUiThread(() -> {
					out.setTextColor(0xFFD9534F);
					out.setText("请求失败：" + message + "\n检查接口地址 / 模型 / 密钥，或服务器是否允许该客户端访问。");
					busy = false;
					send.setEnabled(true);
					status.setText("失败");
				});
			}
		}), "viey-ai-chat").start();
	}

	private TextView appendBubble(String text, boolean isUser) {
		boolean dark = isDark();
		TextView tv = new TextView(this);
		tv.setText(text);
		tv.setTextSize(15);
		tv.setTextColor(isUser ? Color.WHITE : (dark ? 0xFFEDEDED : 0xFF1A1A1A));
		tv.setTextIsSelectable(true);
		tv.setPadding(dp(12), dp(10), dp(12), dp(10));

		GradientDrawable bg = new GradientDrawable();
		bg.setCornerRadius(dp(10));
		bg.setColor(isUser ? 0xFF4C8DFF : (dark ? 0xFF232323 : 0xFFF2F3F5));
		tv.setBackground(bg);

		LinearLayout row = new LinearLayout(this);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setPadding(0, dp(4), 0, dp(4));
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
			ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		if (isUser) {
			row.setGravity(Gravity.END);
			lp.leftMargin = dp(48);
		} else {
			lp.rightMargin = dp(48);
		}
		row.addView(tv, lp);
		msgBox.addView(row);

		tv.setOnClickListener(v -> {
			ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
			cm.setPrimaryClip(ClipData.newPlainText("viey-ai", tv.getText().toString()));
			Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
		});
		scrollDown();
		return tv;
	}

	private void scrollDown() {
		scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
	}

	private int dp(int v) {
		return i.dp2px(v);
	}

	private void showConfigDialog() {
		LinearLayout box = new LinearLayout(this);
		box.setOrientation(LinearLayout.VERTICAL);
		box.setPadding(dp(20), dp(12), dp(20), dp(4));

		EditText base = field("接口地址（OpenAI 兼容 /v1）", AiConfig.baseUrl(this));
		EditText model = field("模型", AiConfig.model(this));
		EditText key = field("API Key", AiConfig.apiKey(this));
		EditText sys = field("系统提示词（可选）", AiConfig.systemPrompt(this));

		box.addView(base);
		box.addView(model);
		box.addView(key);
		box.addView(sys);

		new android.app.AlertDialog.Builder(this)
			.setTitle("AI 接口配置")
			.setView(box)
			.setPositiveButton("保存", (d, w) -> {
				AiConfig.setBaseUrl(this, base.getText().toString());
				AiConfig.setModel(this, model.getText().toString());
				AiConfig.setApiKey(this, key.getText().toString());
				AiConfig.setSystemPrompt(this, sys.getText().toString());
				history.clear();
				history.add(AiClient.system(AiConfig.systemPrompt(this)));
				Toast.makeText(this, "已保存，后续请求使用新配置", Toast.LENGTH_SHORT).show();
			})
			.setNegativeButton("取消", null)
			.show();
	}

	private EditText field(String hint, String value) {
		EditText et = new EditText(this);
		et.setHint(hint);
		et.setText(value);
		et.setTextSize(14);
		et.setSingleLine(true);
		et.setPadding(0, dp(6), 0, dp(6));
		return et;
	}
}
