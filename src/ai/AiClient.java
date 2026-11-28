package kawaii.viey.browser;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI 兼容接口的最小客户端。
 *
 * 只依赖 HttpURLConnection，不引入 OkHttp；支持普通响应与 SSE 流式响应两种模式，
 * 后者用于逐字上屏。所有方法都是阻塞的，必须在工作线程调用。
 */
public class AiClient {

	private static final String TAG = "viey-ai";
	private static final int TIMEOUT_MS = 60000;

	/** 一条对话消息 */
	public static class Msg {
		public final String role;
		public final String content;

		public Msg(String role, String content) {
			this.role = role;
			this.content = content;
		}
	}

	/** 流式回调，delta 可能为空串，结束时调用 onDone */
	public interface StreamCb {
		void onDelta(String text);

		void onDone(String full);

		void onError(String message);
	}

	public static Msg user(String s) {
		return new Msg("user", s);
	}

	public static Msg assistant(String s) {
		return new Msg("assistant", s);
	}

	public static Msg system(String s) {
		return new Msg("system", s);
	}

	/**
	 * 发起一次对话请求并等待完整结果。
	 *
	 * @param history 已包含 system 消息的完整上下文
	 */
	public static String chat(Context ctx, List<Msg> history) throws Exception {
		HttpURLConnection conn = open(ctx, buildBody(history, false, AiConfig.model(ctx)));
		try {
			int code = conn.getResponseCode();
			String body = code >= 200 && code < 300 ? readAll(conn.getInputStream()) : readAll(conn.getErrorStream());
			if (code < 200 || code >= 300) {
				throw new Exception("HTTP " + code + " " + brief(body));
			}
			JSONObject root = new JSONObject(body);
			JSONArray choices = root.optJSONArray("choices");
			if (choices == null || choices.length() == 0) throw new Exception("响应无 choices");
			JSONObject first = choices.optJSONObject(0);
			JSONObject msg = first == null ? null : first.optJSONObject("message");
			String content = msg == null ? null : msg.optString("content", "");
			if (content == null || content.isEmpty()) throw new Exception("响应内容为空");
			return content;
		} finally {
			conn.disconnect();
		}
	}

	/** 流式对话：每收到一段增量回调 onDelta，结束回调 onDone */
	public static void chatStream(Context ctx, List<Msg> history, StreamCb cb) {
		HttpURLConnection conn = null;
		StringBuilder full = new StringBuilder();
		try {
			conn = open(ctx, buildBody(history, true, AiConfig.model(ctx)));
			int code = conn.getResponseCode();
			if (code < 200 || code >= 300) {
				String err = readAll(conn.getErrorStream());
				cb.onError("HTTP " + code + " " + brief(err));
				return;
			}
			BufferedReader reader = new BufferedReader(
				new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
			String line;
			while ((line = reader.readLine()) != null) {
				if (!line.startsWith("data:")) continue;
				String payload = line.substring(5).trim();
				if (payload.isEmpty()) continue;
				if ("[DONE]".equals(payload)) break;
				try {
					JSONObject chunk = new JSONObject(payload);
					JSONArray choices = chunk.optJSONArray("choices");
					if (choices == null || choices.length() == 0) continue;
					JSONObject delta = choices.optJSONObject(0).optJSONObject("delta");
					if (delta == null) continue;
					String piece = delta.optString("content", "");
					if (!piece.isEmpty()) {
						full.append(piece);
						cb.onDelta(piece);
					}
				} catch (Exception ignore) {
					// 单条分片解析失败不影响整体
				}
			}
			cb.onDone(full.toString());
		} catch (Exception e) {
			cb.onError(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
		} finally {
			if (conn != null) conn.disconnect();
		}
	}

	private static HttpURLConnection open(Context ctx, String body) throws Exception {
		String url = AiConfig.chatUrl(ctx);
		HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
		conn.setRequestMethod("POST");
		conn.setConnectTimeout(15000);
		conn.setReadTimeout(TIMEOUT_MS);
		conn.setDoOutput(true);
		conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
		conn.setRequestProperty("Accept", "application/json");
		String key = AiConfig.apiKey(ctx);
		if (!key.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + key);
		try (OutputStream os = conn.getOutputStream()) {
			os.write(body.getBytes(StandardCharsets.UTF_8));
		}
		return conn;
	}

	private static String buildBody(List<Msg> history, boolean stream, String model) throws Exception {
		JSONObject body = new JSONObject();
		body.put("model", model);
		body.put("stream", stream);
		JSONArray messages = new JSONArray();
		for (Msg m : history) {
			JSONObject o = new JSONObject();
			o.put("role", m.role);
			o.put("content", m.content);
			messages.put(o);
		}
		body.put("messages", messages);
		return body.toString();
	}

	private static String readAll(java.io.InputStream in) throws Exception {
		if (in == null) return "";
		StringBuilder sb = new StringBuilder();
		try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = br.readLine()) != null) sb.append(line).append('\n');
		}
		return sb.toString();
	}

	private static String brief(String s) {
		if (s == null) return "";
		String t = s.replace('\n', ' ').trim();
		return t.length() > 200 ? t.substring(0, 200) + "…" : t;
	}

	/** 组装带 system 提示的上下文 */
	public static List<Msg> newHistory(Context ctx) {
		List<Msg> list = new ArrayList<>();
		list.add(system(AiConfig.systemPrompt(ctx)));
		return list;
	}
}
