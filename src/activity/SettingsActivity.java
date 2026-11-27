package kawaii.viey.browser;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.Toast;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import java.io.File;
import android.text.method.DigitsKeyListener;
import android.text.InputType;

public class SettingsActivity extends BaseActivity {
	
	private LinearLayout contentContainer;
	
	private TextView appVersion, textDateFormat, textToolbarPosition, textCertStatus, textDownloadPath, textLanguage, textHomeUrl, textHomeMode, textSearchEngine, textMaxConcurrent, textDefaultThreads, textMaxRetry, textWebFont;
	private Switch switchPull, switchDarkMode, switchPreferIpv4, switchCustomTab, switchVolumePage, switchDownloadNotice, switchApkAutoInstall;
	private ImageView appLogo;
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_settings);
		contentContainer = findViewById(R.id.content_container);
		
		buildUi();
		loadSettings();
		
		findViewById(R.id.back_tool).setOnClickListener(v -> finish());
		findViewById(R.id.menu_tool).setOnClickListener(v -> i.utw(R.string.operation, "test"));
		
		if (VieYApp.isDarkMode(this)) {
			i.zs(findViewById(R.id.back_tool), "#ffffff");
			i.zs(findViewById(R.id.menu_tool), "#ffffff");
			i.zs(findViewById(R.id.sign_tool), "#ffffff");
		}
	}
	
	
	private void buildUi() {
		addSection(getString(R.string.about));
		addAppInfoBlock();
		
		addItem(getString(R.string.about_info), v -> i.utw(getString(R.string.about), getString(R.string.about_text)));
		
		addItem(getString(R.string.open_source_license), v -> i.hw("https://awzx3214.github.io/VieYBrowser/md/license.html"));
		
		addItem(getString(R.string.official_website), v -> openUrlAndFinish("https://palhube666.wodemo.com/"));
		
		addItem(getString(R.string.feedback), v -> showFeedbackDialog());
		
		addSection(getString(R.string.general));
		
		switchDarkMode = addSwitchItem(
		getString(R.string.night_mode), VieYApp.isDarkMode(this), (v, is) -> {
			VieYApp.setDarkMode(this, is);
			AppCompatDelegate.setDefaultNightMode(
			is ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
			recreate();
		});
		
        
		textLanguage = addValueItem(getString(R.string.setting_language), v -> showLanguageDialog());
		textSearchEngine = addValueItem(getString(R.string.search_engine), v -> showSearchEngineDialog());
		textHomeUrl = addValueItem(getString(R.string.home_url), v -> showHomeUrlDialog());
		textDateFormat = addValueItem(getString(R.string.date_format), v -> showDateFormatDialog());
        
        
		addSection(getString(R.string.privacy));
		
		textCertStatus = addValueItem(getString(R.string.cert_settings), v -> startActivity(new Intent(this, CertActivity.class)));
		
		addItem(getString(R.string.ua), v -> startActivity(new Intent(this, UaActivity.class)));
		addItem(getString(R.string.proxy), v -> startActivity(new Intent(this, UpActivity.class)));
		
		
		addSection(getString(R.string.advanced));
		
		switchPreferIpv4 = addSwitchItem(getString(R.string.prefer_ipv4), VieYApp.isPreferIpv4(this), (v, is) -> VieYApp.setPreferIpv4(this, is));
		
		switchCustomTab = addSwitchItem(getString(R.string.use_custom_tab), VieYApp.useTabs(this), (v, is) -> VieYApp.useTabs(this, is));
		addItem(getString(R.string.clear_cache), v -> clearCache());
		
		
		addSection(getString(R.string.download));
		
		textDownloadPath = addValueItem(getString(R.string.download_path), v -> showDownloadPathDialog());
		textMaxConcurrent = addValueItem(getString(R.string.max_concurrent_tasks), v -> showMaxConcurrentDialog());
		textDefaultThreads = addValueItem(getString(R.string.default_threads), v -> showDefaultThreadsDialog());
		textMaxRetry = addValueItem(getString(R.string.max_retry_count), v -> showMaxRetryDialog());
		switchDownloadNotice = addSwitchItem(getString(R.string.show_download_notice), VieYApp.isDownloadNotice(this), (v, is) -> VieYApp.isDownloadNotice(this, is));
		switchApkAutoInstall = addSwitchItem(getString(R.string.auto_install_apk), VieYApp.downloadApkAZ(this), (v, is) -> VieYApp.downloadApkAZ(this, is));
		
		addSection(getString(R.string.gesture));
		
		switchPull = addSwitchItem(getString(R.string.pull_refresh), VieYApp.isPullRefresh(this), (v, is) -> VieYApp.setPullRefresh(this, is));
		switchVolumePage = addSwitchItem(getString(R.string.volume_key_page), VieYApp.isVolumeKeyPage(this), (v, is) -> VieYApp.setVolumeKeyPage(this, is));
		
		addSection(getString(R.string.customize));
		
		addSection("订阅");
		addItem("订阅源管理 / RSS·Atom", v -> startActivity(new Intent(this, FeedActivity.class)));
		addItem("导入·导出 阅读(Legado) 订阅备份", v -> startActivity(new Intent(this, FeedActivity.class)));
		addItem("全部订阅文章（聚合时间线）", v -> {
			Intent it = new Intent(this, FeedItemsActivity.class);
			it.putExtra(FeedItemsActivity.EXTRA_ALL, true);
			startActivity(it);
		});
		addItem("我的收藏（订阅文章）", v -> {
			Intent it = new Intent(this, FeedItemsActivity.class);
			it.putExtra(FeedItemsActivity.EXTRA_FAVS, true);
			startActivity(it);
		});
		addSwitchItem("订阅定时后台刷新（JobScheduler）", FeedRefreshJob.isAuto(this),
				(v, is) -> FeedRefreshJob.setAuto(this, is));
		TextView textRssInterval = addValueItem("刷新间隔", v -> {
			final int cur = FeedRefreshJob.interval(this);
			final int[] mins = {15, 30, 60, 180, 360};
			String[] labels = new String[mins.length];
			for (int i = 0; i < mins.length; i++) labels[i] = mins[i] + " 分钟" + (mins[i] == cur ? "（当前）" : "");
			new android.app.AlertDialog.Builder(this)
					.setTitle("刷新间隔")
					.setItems(labels, (d, w) -> {
						FeedRefreshJob.setInterval(this, mins[w]);
						Toast.makeText(this, "已设为 " + mins[w] + " 分钟", Toast.LENGTH_SHORT).show();
					})
					.show();
		});
		textRssInterval.setText(FeedRefreshJob.interval(this) + " 分钟");
		addItem("立即刷新全部订阅源", v -> {
			Toast.makeText(this, "后台刷新中…", Toast.LENGTH_SHORT).show();
			new Thread(() -> {
				int ok = FeedRepo.refreshAll(this);
				runOnUiThread(() -> Toast.makeText(this, "刷新完成：" + ok + " 个源", Toast.LENGTH_SHORT).show());
			}, "settings-feed-refresh").start();
		});
		addValueItem("正文离线缓存", v -> {
			long kb = FeedState.cacheSize(this) / 1024;
			new android.app.AlertDialog.Builder(this)
					.setTitle("正文离线缓存 " + kb + " KB")
					.setItems(new String[]{"清空缓存"}, (d, w) -> {
						FeedState.clearCache(this);
						Toast.makeText(this, "缓存已清空", Toast.LENGTH_SHORT).show();
					})
					.show();
		});
		
		addSection("网页字体");
		addSwitchItem("网页自定义字体注入（CSP 安全）", FontInject.enabled(this), (v, is) -> FontInject.setEnabled(this, is));
		textWebFont = addValueItem("网页字体文件 (.ttf/.otf)", v -> pickWebFont());
		textWebFont.setText(FontInject.fontFile(this) == null ? "未选择" : FontInject.fontFile(this).getName());
		
		addSection("浏览增强");
		addSwitchItem("安全浏览拦截（可疑域名提示页）", SafeBrowse.enabled(this), (v, is) -> SafeBrowse.setEnabled(this, is));
		addSwitchItem("看图模式（点图放大）", "true".equals(getPrefs(this).getString("image_mode", "false")), (v, is) -> getPrefs(this).edit().putString("image_mode", is ? "true" : "false").apply());
		addItem("运行日志 / 资源日志", v -> startActivity(new Intent(this, LogActivity.class)));
		
		addSection("协议工具");
		addItem("MQTT 订阅（Paho mqttv3）", v -> startActivity(new Intent(this, MqttActivity.class)));
		addItem("FTP 目录浏览 / 下载（commons-net）", v -> startActivity(new Intent(this, FtpActivity.class)));
		addItem("Java 网络代理已启用（页面可用 viek.http）", v -> i.utw("网络代理", "页面脚本可调用 viek.http(url, opts) 经 Java 发起请求，绕同源限制；也可用 viek.fetch。"));

		addSection("插件与下载");
		addItem("用户脚本插件（.user.js 管理 / 脚本市场）", v -> startActivity(new Intent(this, PluginActivity.class)));
		addItem("脚本市场地址", v -> showPluginMarketDialog());
		addItem("多源 / 多进程下载（:dl 独立进程）", v -> startActivity(new Intent(this, DlActivity.class)));
		addItem("BT 下载（magnet / .torrent + piece 校验）", v -> startActivity(new Intent(this, BtActivity.class)));
		addItem("BT 自检（bencode / magnet / piece）", v -> runBtSelfTest());
		addItem("BT 连接数（peer 上限）", v -> showBtPeersDialog());
		addItem("悬浮窗浏览（Service + WebView 浮层）", v -> toggleFloatWindow());
		addItem("uTP 自检（头部 / 停等传输）", v -> runUtpSelfTest());
		addItem("下载连接数（每任务上限）", v -> showDlConnsDialog());
		
		textToolbarPosition = addValueItem(getString(R.string.toolbar_position), v -> showToolbarPosDialog());
		textHomeMode = addValueItem(getString(R.string.home_mode), v -> showHomeModeDialog());
		
		View spacer = new View(this);
		spacer.setLayoutParams(new LinearLayout.LayoutParams(
		LinearLayout.LayoutParams.MATCH_PARENT, i.dp2px(100)));
		contentContainer.addView(spacer);
	}
	
	private void showPluginMarketDialog() {
		android.widget.EditText et = new android.widget.EditText(this);
		et.setText(PluginStore.marketUrl(this));
		et.setTextSize(12);
		new android.app.AlertDialog.Builder(this).setTitle("脚本市场地址")
				.setView(et)
				.setPositiveButton("保存", (d, w) -> {
					PluginStore.setMarketUrl(this, et.getText().toString());
					Toast.makeText(this, "已保存，可从插件页右上角打开", Toast.LENGTH_SHORT).show();
				}).setNegativeButton("取消", null).show();
	}

	private void toggleFloatWindow() {
		if (android.os.Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(this)) {
			new android.app.AlertDialog.Builder(this).setTitle("需要悬浮窗权限")
					.setMessage("请在系统设置里允许“显示在其他应用上层”，开启后回到浏览器，从菜单再次点悬浮窗即可。")
					.setPositiveButton("去授权", (d, w) -> {
						try {
							startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
									android.net.Uri.parse("package:" + getPackageName())));
						} catch (Exception ignored) {
						}
					}).setNegativeButton("取消", null).show();
			return;
		}
		if (FloatService.isShowing()) {
			FloatService.hide(this);
			Toast.makeText(this, "悬浮窗已关闭", Toast.LENGTH_SHORT).show();
		} else {
			FloatService.show(this, null);
			Toast.makeText(this, "悬浮窗已打开，可在浮层里拖动标题栏", Toast.LENGTH_SHORT).show();
		}
	}

	private void addSection(String title) {
		TextView tv = new TextView(this);
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
		LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
		lp.setMargins(i.dp2px(15), i.dp2px(15), i.dp2px(15), i.dp2px(15));
		tv.setLayoutParams(lp);
		tv.setText(title);
		tv.setTextSize(16);
		tv.setTextColor(0xFF00DBDB);
		contentContainer.addView(tv);
	}
	
	private LinearLayout createRow() {
		LinearLayout row = new LinearLayout(this);
		row.setLayoutParams(new LinearLayout.LayoutParams(
		LinearLayout.LayoutParams.MATCH_PARENT, i.dp2px(40)));
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setPadding(i.dp2px(10), 0, i.dp2px(10), 0);
		
		TypedValue out = new TypedValue();
		getTheme().resolveAttribute(android.R.attr.selectableItemBackground, out, true);
		row.setBackgroundResource(out.resourceId);
		return row;
	}
	
	private TextView createTitle(String text) {
		TextView tv = new TextView(this);
		tv.setLayoutParams(new LinearLayout.LayoutParams(
		0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
		tv.setText(text);
		tv.setTextSize(14);
		tv.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
		return tv;
	}
	
	private TextView createValue() {
		TextView tv = new TextView(this);
		tv.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
		tv.setTextSize(10);
		tv.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
		tv.setSingleLine(true);
		tv.setEllipsize(TextUtils.TruncateAt.END);
		return tv;
	}
	
	private void addItem(String text, View.OnClickListener listener) {
		LinearLayout row = createRow();
		row.addView(createTitle(text));
		row.setOnClickListener(listener);
		contentContainer.addView(row);
	}
	
	private TextView addValueItem(String text, View.OnClickListener listener) {
		LinearLayout row = createRow();
		row.addView(createTitle(text));
		TextView value = createValue();
		row.addView(value);
		row.setOnClickListener(listener);
		contentContainer.addView(row);
		return value;
	}
	
	private Switch addSwitchItem(String text, boolean checked, CompoundButton.OnCheckedChangeListener listener) {
		LinearLayout row = createRow();
		row.addView(createTitle(text));
		
		Switch sw = new Switch(this);
		sw.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
		sw.setChecked(checked);
		sw.setOnCheckedChangeListener(listener);
		row.addView(sw);
		row.setOnClickListener(v -> sw.performClick());
		contentContainer.addView(row);
		return sw;
	}
	
	private void addAppInfoBlock() {
		LinearLayout container = new LinearLayout(this);
		container.setLayoutParams(new LinearLayout.LayoutParams(
		LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
		container.setOrientation(LinearLayout.VERTICAL);
		container.setGravity(Gravity.CENTER_HORIZONTAL);
		container.setPadding(i.dp2px(10), 0, i.dp2px(10), 0);
		
		LinearLayout topRow = new LinearLayout(this);
		topRow.setOrientation(LinearLayout.HORIZONTAL);
		topRow.setGravity(Gravity.CENTER_VERTICAL);
		topRow.setLayoutParams(new LinearLayout.LayoutParams(
		LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
		topRow.setPadding(i.dp2px(10), 0, i.dp2px(10), 0);
		
		appLogo = new ImageView(this);
		LinearLayout.LayoutParams logoLp =
		new LinearLayout.LayoutParams(i.dp2px(50), i.dp2px(50));
		logoLp.setMargins(i.dp2px(5), i.dp2px(5), i.dp2px(5), i.dp2px(5));
		appLogo.setLayoutParams(logoLp);
		topRow.addView(appLogo);
		
		LinearLayout infoCol = new LinearLayout(this);
		infoCol.setOrientation(LinearLayout.VERTICAL);
		infoCol.setLayoutParams(new LinearLayout.LayoutParams(
		LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
		
		infoCol.addView(makeInfoText("V i e Y", 18, R.color.text_primary, true));
		appVersion = makeInfoText("", 10, R.color.text_secondary, true);
		infoCol.addView(appVersion);
		infoCol.addView(makeInfoText("呆毛飘啊飘", 10, R.color.text_secondary, true));
		
		topRow.addView(infoCol);
		container.addView(topRow);
		
		LinearLayout greenRow = new LinearLayout(this);
		greenRow.setOrientation(LinearLayout.HORIZONTAL);
		greenRow.setGravity(Gravity.CENTER_VERTICAL);
		greenRow.setLayoutParams(new LinearLayout.LayoutParams(
		LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
		
		ImageView greenIcon = new ImageView(this);
		greenIcon.setLayoutParams(new LinearLayout.LayoutParams(i.dp2px(15), i.dp2px(15)));
		greenIcon.setImageResource(R.drawable.green);
		greenRow.addView(greenIcon);
		greenRow.addView(makeInfoText(
		getString(R.string.green_app_declaration), 10, R.color.text_secondary, false));
		
		container.addView(greenRow);
		contentContainer.addView(container);
	}
	
	private TextView makeInfoText(String text, int sizeSp, int colorRes, boolean bold) {
		TextView tv = new TextView(this);
		tv.setText(text);
		tv.setTextSize(sizeSp);
		tv.setTextColor(ContextCompat.getColor(this, colorRes));
		if (bold) tv.setTypeface(null, Typeface.BOLD);
		return tv;
	}
	
	private void loadSettings() {
		textLanguage.setText(VieYApp.getLanguageDisplayName(this));
		textHomeUrl.setText(VieYApp.getHomeUrl(this));
		textHomeMode.setText(VieYApp.getHomeMode(this));
		textSearchEngine.setText(VieYApp.getSearchEngine(this));
		textDownloadPath.setText(VieYApp.getDownloadPath(this));
		textDateFormat.setText(VieYApp.getDateFormat(this));
        textToolbarPosition.setText(VieYApp.getToolbarPositionDisplayName(this, VieYApp.getToolbarPosition(this)));
		textMaxConcurrent.setText(String.valueOf(VieYApp.getDownloadMaxConcurrent(this)));
		textDefaultThreads.setText(String.valueOf(VieYApp.getDownloadDefaultThreads(this)));
		textMaxRetry.setText(String.valueOf(VieYApp.getDownloadMaxRetry(this)));
		
		updateCertStatus();
		loadAppInfo();
	}
	
	private void updateCertStatus() {
		String activeCert = VieYApp.getActiveCertName(this);
		textCertStatus.setText((activeCert == null || activeCert.isEmpty()) ? getString(R.string.cert_no_active) : activeCert);
	}
	
	private void loadAppInfo() {
		try {
			appLogo.setImageDrawable(
			getPackageManager().getApplicationIcon(getPackageName()));
		} catch (Exception e) {
			e.printStackTrace();
		}
		try {
			PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
			appVersion.setText(pi.versionName + "  " + pi.versionCode);
		} catch (Exception e) {
			appVersion.setText("unknown");
		}
	}
	
	private void openUrlAndFinish(String url) {
		Intent result = new Intent();
		result.putExtra("url", url);
		setResult(RESULT_OK, result);
		finish();
	}
	
	private void showFeedbackDialog() {
		final String[] names = {"email", "gitee", "github", "qq", "coolapk", "tg"};
		final String[] urls = {
			"mailto:VieBrowser@hotmail.com",
			"https://gitee.com/awzx3214/VieYBrowser",
			"https://github.com/awzx3214/VieYBrowser",
			"https://qm.qq.com/q/2JLhoBKXY4",
			"https://www.coolapk.com/u/1318094",
			"https://t.me/kawaii_v"
		};
		i.utw(R.string.feedback, names, new mk.jk() {
			@Override public void onButton1Click() {}
			@Override public void onButton2Click() {}
			@Override public void onButton3Click() {}
			@Override public void onDialogDismissed() {}
			@Override public void onSelect(String content) {}
			@Override public void onListClick(String nr, int num) {
				openUrlAndFinish(urls[num]);
			}
		});
	}
	
	private void showToolbarPosDialog() {
		final String[] posKeys = {
			VieYApp.TOOLBAR_POS_TOP,
			VieYApp.TOOLBAR_POS_BOTTOM,
			VieYApp.TOOLBAR_POS_TOP_SIDE,
			VieYApp.TOOLBAR_POS_BOTTOM_SIDE
		};
		final String[] posNames = i.setWhich(posKeys, new String[]{
			getString(R.string.toolbar_pos_top),
			getString(R.string.toolbar_pos_bottom),
			getString(R.string.toolbar_pos_top_side),
			getString(R.string.toolbar_pos_bottom_side)
		}, VieYApp.getToolbarPosition(this));
		
		i.utw(getString(R.string.toolbar_position), posNames, new mk.jk() {
			@Override public void onButton1Click() {}
			@Override public void onButton2Click() {}
			@Override public void onButton3Click() {}
			@Override public void onDialogDismissed() {}
			@Override public void onSelect(String content) {}
			@Override public void onListClick(String nr, int num) {
				VieYApp.setToolbarPosition(SettingsActivity.this, posKeys[num]);
				textToolbarPosition.setText(nr);
			}
		});
	}
    
    
    private void showDateFormatDialog() {
		final String[] dateData = i.setWhich(new String[]{
			"yyyy-MM-dd HH:mm:ss",
            "dd-MM-yyyy HH:mm:ss",
            "MM-dd-yyyy HH:mm:ss",
            "dd-MMM-yyyy HH:mm:ss",
            "MMM-dd-yyyy HH:mm:ss"
		}, VieYApp.getDateFormat(this));
		
		i.utw(getString(R.string.date_format), dateData, new mk.jk() {
			@Override public void onButton1Click() {}
			@Override public void onButton2Click() {}
			@Override public void onButton3Click() {}
			@Override public void onDialogDismissed() {}
			@Override public void onSelect(String content) {}
			@Override public void onListClick(String nr, int num) {
				VieYApp.setDateFormat(SettingsActivity.this, nr);
				textDateFormat.setText(nr);
			}
		});
	}
	
	private void showMaxConcurrentDialog() {
		final int[] values = {1, 2, 3, 4, 5, 6, 7, 8};
		final String[] names = i.setWhich(new String[] {"1", "2", "3", "4", "5", "6", "7", "8"}, VieYApp.getDownloadMaxConcurrent(this)+"");
		i.utw(R.string.max_concurrent_tasks, names, new mk.jk() {
			@Override public void onButton1Click() {}
			@Override public void onButton2Click() {}
			@Override public void onButton3Click() {}
			@Override public void onDialogDismissed() {}
			@Override public void onSelect(String content) {}
			@Override public void onListClick(String nr, int num) {
				VieYApp.setDownloadMaxConcurrent(SettingsActivity.this, values[num]);
				textMaxConcurrent.setText(nr);
				i.twi(R.string.saved);
			}
		});
	}
	
	private void showDefaultThreadsDialog() {
		final int[] values = {1, 2, 3, 4, 8, 16, 32};
		final String[] names = i.setWhich(new String[] {"1", "2", "3", "4", "8", "16", "32", i.getString(R.string.diy)}, VieYApp.getDownloadDefaultThreads(this)+"");
		i.utw(R.string.default_threads, names, new mk.jk() {
			@Override public void onButton1Click() {}
			@Override public void onButton2Click() {}
			@Override public void onButton3Click() {}
			@Override public void onDialogDismissed() {}
			@Override public void onSelect(String content) {}
			@Override public void onListClick(String nr, int num) {
				if(num==7)
				{
					final EditViey input = new EditViey(SettingsActivity.this);
					input.setSingleLine(true);
					input.setHeight(i.dp2px(56));
					input.setHint("1~32");
					input.setInputType(InputType.TYPE_CLASS_NUMBER);
					input.setKeyListener(DigitsKeyListener.getInstance("0123456789"));
					
					i.utw(getString(R.string.diy), input,
					getString(R.string.cancel), getString(R.string.save), new mk.jk() {
						@Override public void onButton1Click() {}
						@Override public void onButton2Click() {}
						@Override public void onDialogDismissed() {}
						@Override public void onListClick(String nr, int num) {}
						@Override public void onSelect(String content) {}
						@Override public void onButton3Click() {
							String path = input.getText().toString().trim();
							if (path.isEmpty()) return;
							int lin = 1;
							try
							{
								lin = Integer.parseInt(path);
							} catch(Exception e){}
							if(lin<=0) lin=1;
							if(lin>=32) lin=32;
							VieYApp.setDownloadDefaultThreads(SettingsActivity.this, lin);
							textDefaultThreads.setText(lin+"");
							i.twi(R.string.saved);
						}
					});
				}
				else
				{
					VieYApp.setDownloadDefaultThreads(SettingsActivity.this, values[num]);
					textDefaultThreads.setText(nr);
					i.twi(R.string.saved);
				}
			}
		});
	}
	
	private void showMaxRetryDialog() {
		final int[] values = {0, 1, 2, 3};
		final String[] names = i.setWhich(new String[] {"0", "1", "2", "3"}, VieYApp.getDownloadMaxRetry(this)+"");
		i.utw(R.string.max_retry_count, names, new mk.jk() {
			@Override public void onButton1Click() {}
			@Override public void onButton2Click() {}
			@Override public void onButton3Click() {}
			@Override public void onDialogDismissed() {}
			@Override public void onSelect(String content) {}
			@Override public void onListClick(String nr, int num) {
				VieYApp.setDownloadMaxRetry(SettingsActivity.this, values[num]);
				textMaxRetry.setText(nr);
				i.twi(R.string.saved);
			}
		});
	}
	
	private void showDownloadPathDialog() {
		final EditViey input = new EditViey(this);
		input.setSingleLine(true);
		input.setHeight(i.dp2px(56));
		input.setText(VieYApp.getDownloadPath(this));
		input.setSelection(input.getText().length());
		
		i.utw(getString(R.string.set_download_path), input,
		getString(R.string.cancel), getString(R.string.save), new mk.jk() {
			@Override public void onButton1Click() {}
			@Override public void onButton2Click() {}
			@Override public void onDialogDismissed() {}
			@Override public void onListClick(String nr, int num) {}
			@Override public void onSelect(String content) {}
			@Override public void onButton3Click() {
				String path = input.getText().toString().trim();
				if (path.isEmpty()) return;
				File dir = new File(path);
				if (!dir.exists()) dir.mkdirs();
				VieYApp.setDownloadPath(SettingsActivity.this, dir.getAbsolutePath());
				textDownloadPath.setText(dir.getAbsolutePath());
				i.twi(R.string.saved);
			}
		});
	}
	
	private void showHomeUrlDialog() {
		final EditViey input = new EditViey(this);
		input.setSingleLine(true);
		input.setHeight(i.dp2px(56));
		input.setText(VieYApp.getHomeUrl(this));
		input.setSelection(input.getText().length());
		
		i.utw(getString(R.string.set_home_url), input,
		getString(R.string.cancel), getString(R.string.save), new mk.jk() {
			@Override public void onButton1Click() {}
			@Override public void onButton2Click() {}
			@Override public void onDialogDismissed() {}
			@Override public void onListClick(String nr, int num) {}
			@Override public void onSelect(String content) {}
			@Override public void onButton3Click() {
				String url = input.getText().toString().trim();
				if (url.isEmpty()) url = "viek://home/html/VieY.html";
				if (!url.contains("://")) url = "https://" + url;
				VieYApp.setHomeUrl(SettingsActivity.this, url);
				textHomeUrl.setText(url);
				i.twi(R.string.saved);
			}
		});
	}
	
	private void showHomeModeDialog() {
		final String[] langNames = i.setWhich(new String[]{
			"list",
			"grid",
			"card",
			"dock"
		}, VieYApp.getHomeMode(this));
		
		i.utw(getString(R.string.home_mode), langNames, new mk.jk() {
			@Override public void onButton1Click() {}
			@Override public void onButton2Click() {}
			@Override public void onButton3Click() {}
			@Override public void onDialogDismissed() {}
			@Override public void onSelect(String content) {}
			@Override public void onListClick(String nr, int num) {
				VieYApp.setHomeMode(SettingsActivity.this, nr);
				textHomeMode.setText(nr);
			}
		});
	}
	
	private void showLanguageDialog() {
		final String[] langKeys = {
			VieYApp.LANG_AUTO, VieYApp.LANG_ZH, VieYApp.LANG_TW, VieYApp.LANG_EN
		};
		
		final String[] langNames = i.setWhich(langKeys, new String[]{
			getString(R.string.lang_auto),
			getString(R.string.lang_zh),
			getString(R.string.lang_tw),
			getString(R.string.lang_en)
		}, VieYApp.getLanguage(this));
		
		i.utw(getString(R.string.setting_language), langNames, new mk.jk() {
			@Override public void onButton1Click() {}
			@Override public void onButton2Click() {}
			@Override public void onButton3Click() {}
			@Override public void onDialogDismissed() {}
			@Override public void onSelect(String content) {}
			@Override public void onListClick(String nr, int num) {
				VieYApp.setLanguage(SettingsActivity.this, langKeys[num]);
				textLanguage.setText(nr);
				i.restartAsk();
			}
		});
	}
	
	private void pickWebFont() {
		Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
		it.addCategory(Intent.CATEGORY_OPENABLE);
		it.setType("*/*");
		try {
			startActivityForResult(it, 4101);
		} catch (Exception e) {
			i.twi(R.string.error_occurred);
		}
	}
	
	private void showSearchEngineDialog() {
		Intent intent = new Intent(this, EngineActivity.class);
		startActivityForResult(intent, 10001);
	}
	
	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (requestCode == 4101 && resultCode == RESULT_OK && data != null && data.getData() != null) {
			try (java.io.InputStream in = getContentResolver().openInputStream(data.getData())) {
				File dir = new File(getFilesDir(), "diy");
				if (!dir.exists()) dir.mkdirs();
				File target = new File(dir, "网页字体");
				try (java.io.OutputStream out = new java.io.FileOutputStream(target)) {
					byte[] buf = new byte[8192];
					int n;
					while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
				}
				textWebFont.setText("网页字体（" + (target.length() / 1024) + " KB）");
				i.twi(R.string.saved);
			} catch (Exception e) {
				i.twi(R.string.error_occurred);
			}
			return;
		}
		if (requestCode == 10001 && resultCode == RESULT_OK && data != null) {
			String url = data.getStringExtra(EngineActivity.EXTRA_ENGINE_URL);
			if (url != null && !url.isEmpty()) {
				VieYApp.setSearchEngine(this, url);
				textSearchEngine.setText(url);
				i.twi(R.string.saved);
			}
		}
	}
	
	private void clearCache() {
		try {
			android.webkit.WebView webView = new android.webkit.WebView(this);
			webView.clearCache(true);
			webView.destroy();
			
			File externalFilesDir = getExternalFilesDir(null);
			if (externalFilesDir != null) {
				deleteDir(new File(externalFilesDir, "log"));
				deleteDir(new File(externalFilesDir, "crash_logs"));
			}
			i.twi(R.string.cache_cleared);
		} catch (Exception e) {
			i.twi(R.string.error_occurred);
		}
	}
	
	private void deleteDir(File dir) {
		if (dir == null || !dir.exists()) return;
		File[] files = dir.listFiles();
		if (files != null) {
			for (File f : files) {
				if (f.isDirectory()) deleteDir(f);
				f.delete();
			}
		}
		dir.delete();
	}
	
	@Override
	protected void onResume() {
		super.onResume();
		updateCertStatus();
	}
	
	@Override
	public boolean onOptionsItemSelected(android.view.MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			finish();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	private void showDlConnsDialog() {
		android.widget.EditText et = new android.widget.EditText(this);
		et.setText(String.valueOf(getPrefs(this).getInt("dl_conns", 8)));
		et.setHint("1-16");
		new android.app.AlertDialog.Builder(this).setTitle("下载连接数上限")
				.setView(et)
				.setPositiveButton("保存", (d, w) -> {
					try {
						int n = Integer.parseInt(et.getText().toString().trim());
						getPrefs(this).edit().putInt("dl_conns", Math.max(1, Math.min(16, n))).apply();
						Toast.makeText(this, "已保存，新任务生效", Toast.LENGTH_SHORT).show();
					} catch (Exception e) {
						Toast.makeText(this, "请输入数字", Toast.LENGTH_SHORT).show();
					}
				}).setNegativeButton("取消", null).show();
	}

	private void runUtpSelfTest() {
		final android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
		pd.setMessage("uTP 自检（本机回环，约 1 秒）…");
		pd.setCancelable(false);
		pd.show();
		new Thread(() -> {
			String r;
			try {
				r = UtpSelfTest.run(SettingsActivity.this);
			} catch (Throwable t) {
				r = "自检异常：" + t.getClass().getSimpleName() + " " + t.getMessage();
			}
			final String result = r;
			runOnUiThread(() -> {
				if (isFinishing()) return;
				try { pd.dismiss(); } catch (Exception ignored) {}
				new android.app.AlertDialog.Builder(SettingsActivity.this)
						.setTitle("uTP 自检")
						.setMessage(result)
						.setPositiveButton("关闭", null).show();
			});
		}, "ed2k-selftest").start();
	}

	private void showBtPeersDialog() {
		android.widget.EditText et = new android.widget.EditText(this);
		et.setText(String.valueOf(getPrefs(this).getInt("bt_peers", 4)));
		et.setHint("1-8");
		new android.app.AlertDialog.Builder(this).setTitle("BT 同时连接 peer 上限")
				.setView(et)
				.setPositiveButton("保存", (d, w) -> {
					try {
						int n = Integer.parseInt(et.getText().toString().trim());
						getPrefs(this).edit().putInt("bt_peers", Math.max(1, Math.min(8, n))).apply();
						Toast.makeText(this, "已保存，新任务生效", Toast.LENGTH_SHORT).show();
					} catch (Exception e) {
						Toast.makeText(this, "请输入 1-8", Toast.LENGTH_SHORT).show();
					}
				})
				.setNegativeButton("取消", null).show();
	}

	private void runBtSelfTest() {
		final android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
		pd.setMessage("BT 自检（离线，约 1 秒）…");
		pd.setCancelable(false);
		pd.show();
		new Thread(() -> {
			String r;
			try {
				r = BtSelfTest.run();
			} catch (Throwable t) {
				r = "FAIL " + t;
			}
			final String fr = r;
			runOnUiThread(() -> {
				pd.dismiss();
				new android.app.AlertDialog.Builder(this)
						.setTitle("BT 自检")
						.setMessage(fr)
						.setPositiveButton("关闭", null).show();
			});
		}, "bt-selftest").start();
	}
}
