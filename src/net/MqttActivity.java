package kawaii.viey.browser;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.ArrayList;
import java.util.List;

/**
 * MQTT 订阅面板：移植 Vie 6.5.0 新增的 kawaii.vie.mqttv3（Paho MQTT 客户端）。
 * 用同步 MqttClient + MemoryPersistence，不依赖 Android Service。
 */
public class MqttActivity extends BaseActivity {

    private EditText inputServer, inputTopic;
    private TextView state;
    private ListView list;
    private final List<String> data = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private MqttClient client;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mqtt);

        inputServer = findViewById(R.id.mqtt_server);
        inputTopic = findViewById(R.id.mqtt_topic);
        state = findViewById(R.id.mqtt_state);
        list = findViewById(R.id.mqtt_list);
        findViewById(R.id.back_tool).setOnClickListener(v -> {
            closeQuietly();
            finish();
        });

        inputServer.setText("tcp://");
        inputTopic.setText("test/#");

        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, data) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                TextView tv = v.findViewById(android.R.id.text1);
                tv.setTextSize(12);
                return v;
            }
        };
        list.setAdapter(adapter);

        findViewById(R.id.mqtt_connect).setOnClickListener(v -> connect());
        findViewById(R.id.mqtt_disconnect).setOnClickListener(v -> {
            closeQuietly();
            state.setText("未连接");
        });
    }

    private void connect() {
        final String server = inputServer.getText().toString().trim();
        final String topic = inputTopic.getText().toString().trim();
        if (TextUtils.isEmpty(server) || server.equals("tcp://")) {
            Toast.makeText(this, "请填写 tcp://broker:1883", Toast.LENGTH_SHORT).show();
            return;
        }
        if (TextUtils.isEmpty(topic)) {
            Toast.makeText(this, "请填写订阅主题", Toast.LENGTH_SHORT).show();
            return;
        }
        state.setText("连接中…");
        new Thread(() -> {
            try {
                closeQuietly();
                String cid = "viey_" + android.os.Build.MODEL.replaceAll("[^A-Za-z0-9]", "") + "_" + (System.currentTimeMillis() % 100000);
                client = new MqttClient(server, cid, new MemoryPersistence());
                client.setCallback(new MqttCallback() {
                    @Override
                    public void connectionLost(Throwable cause) {
                        ui.post(() -> state.setText("连接已断开：" + (cause == null ? "" : cause.getMessage())));
                    }

                    @Override
                    public void messageArrived(String t, MqttMessage m) {
                        final String line = t + "  ←  " + new String(m.getPayload());
                        ui.post(() -> {
                            data.add(0, line.length() > 600 ? line.substring(0, 600) + "…" : line);
                            adapter.notifyDataSetChanged();
                            LogStore.add(MqttActivity.this, "mqtt", line);
                        });
                    }

                    @Override
                    public void deliveryComplete(IMqttDeliveryToken token) {
                    }
                });
                MqttConnectOptions opt = new MqttConnectOptions();
                opt.setAutomaticReconnect(true);
                opt.setCleanSession(true);
                opt.setConnectionTimeout(10);
                opt.setKeepAliveInterval(30);
                client.connect(opt);
                client.subscribe(topic, 1);
                ui.post(() -> state.setText("已连接 " + server + "，订阅 " + topic));
            } catch (Exception e) {
                final String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                ui.post(() -> state.setText("连接失败：" + msg));
                LogStore.add(this, "mqtt", "connect fail " + msg);
            }
        }).start();
    }

    private void closeQuietly() {
        try {
            if (client != null) {
                if (client.isConnected()) client.disconnect();
                client.close();
            }
        } catch (Exception ignored) {
        }
        client = null;
    }

    @Override
    protected void onDestroy() {
        closeQuietly();
        super.onDestroy();
    }
}
