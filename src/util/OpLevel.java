package kawaii.viey.browser;

import android.content.Context;

/**
 * 操作等级：用密钥解锁不同的设置分区。
 *
 * 等级 1（默认）：常规浏览 / 下载 / 订阅 / 广告拦截 / 虚拟鼠标 等全部日常项
 * 等级 2（密钥 viey）：额外显示 协议工具、插件与下载、备份与恢复
 * 等级 3（密钥 viey-admin）：额外显示 自检、日志、源仓库批量导入等实验项
 *
 * 管理员也可把自己设定的密钥写进 prefs（op_key_admin）作为等级 3 的钥匙。
 */
public class OpLevel {

	public static final int NORMAL = 1;
	public static final int POWER = 2;
	public static final int ADMIN = 3;

	private static final String PREFS = "op_level";
	private static final String KEY_LEVEL = "level";
	private static final String KEY_ADMIN = "op_key_admin";
	private static final String KEY_POWER = "op_key_power";

	private static android.content.SharedPreferences sp(Context c) {
		return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
	}

	public static int get(Context c) {
		return sp(c).getInt(KEY_LEVEL, POWER);
	}

	public static void set(Context c, int level) {
		sp(c).edit().putInt(KEY_LEVEL, level).apply();
	}

	public static String adminKey(Context c) {
		return sp(c).getString(KEY_ADMIN, "viey-admin");
	}

	public static String powerKey(Context c) {
		return sp(c).getString(KEY_POWER, "viey");
	}

	/** 自定义某一级的密钥；空串表示恢复默认 */
	public static void setKey(Context c, int level, String key) {
		String k = key == null ? "" : key.trim();
		if (level >= ADMIN) {
			sp(c).edit().putString(KEY_ADMIN, k.isEmpty() ? "viey-admin" : k).apply();
		} else {
			sp(c).edit().putString(KEY_POWER, k.isEmpty() ? "viey" : k).apply();
		}
	}

	public static void setAdminKey(Context c, String key) {
		sp(c).edit().putString(KEY_ADMIN, key == null ? "" : key.trim()).apply();
	}

	/** 输入密钥，命中则升级等级；返回解锁后的等级，未命中返回 0 */
	public static int unlock(Context c, String input) {
		if (input == null) return 0;
		String s = input.trim();
		if (s.isEmpty()) return 0;
		if (s.equals(adminKey(c))) {
			set(c, ADMIN);
			return ADMIN;
		}
		if (s.equals(powerKey(c))) {
			if (get(c) < POWER) set(c, POWER);
			return get(c);
		}
		return 0;
	}

	public static String label(int level) {
		if (level >= ADMIN) return "等级 3 · 管理员（全部功能）";
		if (level >= POWER) return "等级 2 · 进阶（协议工具 / 插件 / 备份）";
		return "等级 1 · 标准（日常浏览与设置）";
	}
}
