package kawaii.viey.browser;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * AI 功能的配置项，统一存放在默认偏好文件中。
 *
 * 只支持 OpenAI 兼容的 chat/completions 接口：任何遵循该协议的服务（官方接口、
 * 自建 one-api、各类中转）都可以直接填写地址使用。密钥仅保存在本机偏好文件内，
 * 不会出现在日志中。
 */
public class AiConfig {

	private static final String KEY_BASE = "ai_base_url";
	private static final String KEY_MODEL = "ai_model";
	private static final String KEY_KEY = "ai_api_key";
	private static final String KEY_SYSTEM = "ai_system_prompt";
	private static final String KEY_SUMMARY = "ai_summary_prompt";

	public static final String DEFAULT_BASE = "https://api.openai.com/v1";
	public static final String DEFAULT_MODEL = "gpt-4o-mini";

	public static final String DEFAULT_SYSTEM =
		"你是一个简洁、准确的中文助手。回答直接给结论，不要寒暄，不要重复问题。";

	public static final String DEFAULT_SUMMARY =
		"请阅读下面的网页正文，输出：\n"
			+ "1. 三到五条要点（每条不超过 30 字）；\n"
			+ "2. 一段不超过 120 字的内容概括；\n"
			+ "3. 如有明确的数据、结论或时间点，单独列出。\n"
			+ "不要编造正文中没有的信息。";

	private static SharedPreferences p(Context c) {
		return VieYApp.getPrefs(c);
	}

	public static String baseUrl(Context c) {
		String v = p(c).getString(KEY_BASE, DEFAULT_BASE);
		return v == null ? DEFAULT_BASE : v.trim();
	}

	public static void setBaseUrl(Context c, String v) {
		p(c).edit().putString(KEY_BASE, v == null ? "" : v.trim()).apply();
	}

	public static String model(Context c) {
		String v = p(c).getString(KEY_MODEL, DEFAULT_MODEL);
		return v == null || v.trim().isEmpty() ? DEFAULT_MODEL : v.trim();
	}

	public static void setModel(Context c, String v) {
		p(c).edit().putString(KEY_MODEL, v == null ? "" : v.trim()).apply();
	}

	public static String apiKey(Context c) {
		String v = p(c).getString(KEY_KEY, "");
		return v == null ? "" : v.trim();
	}

	public static void setApiKey(Context c, String v) {
		p(c).edit().putString(KEY_KEY, v == null ? "" : v.trim()).apply();
	}

	public static String systemPrompt(Context c) {
		String v = p(c).getString(KEY_SYSTEM, DEFAULT_SYSTEM);
		return v == null || v.trim().isEmpty() ? DEFAULT_SYSTEM : v;
	}

	public static void setSystemPrompt(Context c, String v) {
		p(c).edit().putString(KEY_SYSTEM, v == null ? "" : v).apply();
	}

	public static String summaryPrompt(Context c) {
		String v = p(c).getString(KEY_SUMMARY, DEFAULT_SUMMARY);
		return v == null || v.trim().isEmpty() ? DEFAULT_SUMMARY : v;
	}

	public static void setSummaryPrompt(Context c, String v) {
		p(c).edit().putString(KEY_SUMMARY, v == null ? "" : v).apply();
	}

	/** 是否已经填入了可用的接口地址与密钥 */
	public static boolean ready(Context c) {
		return !baseUrl(c).isEmpty() && !apiKey(c).isEmpty();
	}

	/** 拼接 chat/completions 的完整地址，兼容用户只填到 /v1 或直接填完整 URL */
	public static String chatUrl(Context c) {
		String base = baseUrl(c);
		while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
		if (base.endsWith("/chat/completions")) return base;
		return base + "/chat/completions";
	}
}
