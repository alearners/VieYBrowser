package kawaii.viey.browser;

import android.webkit.JavascriptInterface;
import android.content.Intent;
import java.util.concurrent.CountDownLatch;
import android.text.TextUtils;

public class WebJs {
	
	private final WebViey mWeb;
	
	public WebJs(WebViey webViey) {
		this.mWeb = webViey;
	}
	
	@JavascriptInterface
	public void loadUrl(String url) {
		mWeb.post(() -> {
			try {
				mWeb.loadUrl(url);
			} catch (Exception e) {
				e.printStackTrace();
			}
		});
	}
    
    @JavascriptInterface
	public void toast(String str) {
		mWeb.post(() -> {
			i.tw(str);
		});
	}
	
	@JavascriptInterface
	public void loadHtml(String html) {
		mWeb.post(() -> {
			try {
				mWeb.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
			} catch (Exception e) {
				e.printStackTrace();
			}
		});
	}
	
	@JavascriptInterface
	public void function(String data) {
		mWeb.post(() -> {
			Tools.function(data, null);
		});
	}
	
	@JavascriptInterface
	public String getHomeData(String info) {
		if(TextUtils.isEmpty(info)) return "";
		else if(info.equals("mode")) return VieYApp.getHomeMode(mWeb.getContext());
		else if(info.equals("json")) return BookmarkManager.outputHome(mWeb.getContext());
		return "";
	}
	
	@JavascriptInterface
	public void getImg(String url, String id) {
		mWeb.post(() -> {
			mWeb.getSmolnetImg(url, id);
		});
	}
	
	
	@JavascriptInterface
	public void titan(String url, String isFile, String text, String mime, String token) {
		mWeb.post(() -> {
			mWeb.titan(url, isFile, text, mime, token);
		});
	}
	
	@JavascriptInterface
	public void misfin(String url, String to, String text, String type) {
		mWeb.post(() -> {
			mWeb.misfin(url, to, text, type);
		});
	}
	
	@JavascriptInterface
	public void scroll(String url) {
		mWeb.post(() -> {
			mWeb.scroll(url, "scrollMeta");
		});
	}
	
	@JavascriptInterface
	public void nps(String url, String str) {
		mWeb.post(() -> {
			mWeb.nps(url, str);
		});
	}
	
	@JavascriptInterface
	public void molerat(String url, String method, String form) {
		mWeb.post(() -> {
			mWeb.molerat(url, method, form);
		});
	}
	
	@JavascriptInterface
	public void getCert(String text) {
		mWeb.post(() -> {
			try {
				Intent intent = new Intent(mWeb.getContext(), CertActivity.class);
				intent.putExtra("extra_pem_text", text);
				mWeb.getContext().startActivity(intent);
			} catch (Exception e) {
				e.printStackTrace();
			}
		});
	}
	
	@JavascriptInterface
	public void smolnet_file() {
		mWeb.post(() -> {
			mWeb.onSmolnetFileSelect();
		});
	}
	
	/** 控制台日志（移植 Vie 6.5.0 的 viek.console / end 脚本） */
	@JavascriptInterface
	public void console(final String level, final String msg) {
		LogStore.add(mWeb.getContext(), level, msg);
	}
	
	/**
	 * Java 侧 HTTP 代理（移植 Vie 6.5.0 的 gm 桥：定制 XHR 底层）。
	 * 结果通过 window.vieHttpDone(id, json) 异步回传；二进制以 base64 返回。
	 */
	@JavascriptInterface
	public void http(final String id, final String method, final String url,
			final String headersJson, final String body, final String responseType, final int timeoutMs) {
		new Thread(() -> {
			String result;
			try {
				java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
				conn.setConnectTimeout(Math.max(3000, timeoutMs));
				conn.setReadTimeout(Math.max(3000, timeoutMs));
				conn.setInstanceFollowRedirects(true);
				conn.setRequestMethod(TextUtils.isEmpty(method) ? "GET" : method.toUpperCase());
				String ua = mWeb.getSettings().getUserAgentString();
				conn.setRequestProperty("User-Agent", TextUtils.isEmpty(ua) ? "Mozilla/5.0 VieY" : ua);
				conn.setRequestProperty("Accept", "*/*");
				if (!TextUtils.isEmpty(headersJson)) {
					try {
						org.json.JSONObject h = new org.json.JSONObject(headersJson);
						java.util.Iterator<String> it = h.keys();
						while (it.hasNext()) {
							String k = it.next();
							conn.setRequestProperty(k, h.optString(k, ""));
						}
					} catch (Exception ignored) {
					}
				}
				if (!TextUtils.isEmpty(body) && !"GET".equalsIgnoreCase(method)) {
					conn.setDoOutput(true);
					try (java.io.OutputStream out = conn.getOutputStream()) {
						out.write(body.getBytes("UTF-8"));
					}
				}
				int code = conn.getResponseCode();
				java.io.InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
				java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
				if (in != null) {
					byte[] buf = new byte[8192];
					int n;
					while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
					in.close();
				}
				String ctype = conn.getContentType();
				StringBuilder hs = new StringBuilder();
				for (java.util.Map.Entry<String, java.util.List<String>> e : conn.getHeaderFields().entrySet()) {
					if (e.getKey() == null) continue;
					hs.append(e.getKey()).append(':').append(TextUtils.join(",", e.getValue())).append('\n');
				}
				boolean bin = "arraybuffer".equalsIgnoreCase(responseType) || "blob".equalsIgnoreCase(responseType)
						|| (ctype != null && (ctype.startsWith("image/") || ctype.startsWith("audio/") || ctype.startsWith("video/") || ctype.contains("octet-stream")));
				String payload = bin ? android.util.Base64.encodeToString(bo.toByteArray(), android.util.Base64.NO_WRAP)
						: bo.toString("UTF-8");
				org.json.JSONObject o = new org.json.JSONObject();
				o.put("ok", true);
				o.put("status", code);
				o.put("headers", hs.toString());
				o.put("body", payload);
				o.put("url", conn.getURL().toString());
				result = o.toString();
			} catch (Exception e) {
				try {
					org.json.JSONObject o = new org.json.JSONObject();
					o.put("ok", false);
					o.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
					result = o.toString();
				} catch (Exception e2) {
					result = "{\"ok\":false}";
				}
			}
			final String cb = "(function(){try{window.vieHttpDone&&window.vieHttpDone("
					+ org.json.JSONObject.quote(id) + "," + org.json.JSONObject.quote(result) + ");}catch(e){}})();";
			mWeb.post(() -> mWeb.evaluateJavascript(cb, null));
		}, "viey-http").start();
	}
	
	
	public void callbackFileResult(String filePath, String fileName, long fileSize, String mimeType) {
		mWeb.post(() -> {
			String jsCode = String.format(
			"javascript:window.onSmolnetFileResult(%s,%s,%d,%s)",
			jsonStr(filePath),
			jsonStr(fileName),
			fileSize,
			jsonStr(mimeType)
			);
			mWeb.evaluateJavascript(jsCode, null);
		});
	}
	
	private String jsonStr(String s) {
		if(s == null) return "null";
		StringBuilder sb = new StringBuilder();
		sb.append('"');
		for (char c : s.toCharArray()) {
			if (c == '"') sb.append("\\\"");
			else if (c == '\\') sb.append("\\\\");
			else sb.append(c);
		}
		sb.append('"');
		return sb.toString();
	}
	
}