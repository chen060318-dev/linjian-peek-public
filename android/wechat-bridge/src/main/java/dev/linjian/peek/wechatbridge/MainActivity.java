package dev.linjian.peek.wechatbridge;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    static final String PREFS = "wechat_bridge";
    static final String KEY_TOKEN = "bridge_token";
    static final String KEY_SERVER = "server_url";
    static final String DEFAULT_SERVER = "https://zhangxinchuang-server-gi8r.onrender.com";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private TextView status;
    private EditText codeInput;
    private Button pairButton;

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String value, float size, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(28), dp(42), dp(28), dp(32));
        root.setBackgroundColor(Color.rgb(248, 250, 252));

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.bridge_photo);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(116), dp(116));
        ip.bottomMargin = dp(18);
        root.addView(icon, ip);

        TextView title = text("陨石边牧信使", 23, Color.rgb(35, 48, 64));
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.bottomMargin = dp(8);
        root.addView(title, tp);

        TextView sub = text("User 999 双开微信 · 通知事件桥", 14, Color.rgb(105, 121, 139));
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.bottomMargin = dp(22);
        root.addView(sub, sp);

        status = text("", 14, Color.rgb(64, 86, 108));
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stp.bottomMargin = dp(14);
        root.addView(status, stp);

        codeInput = new EditText(this);
        codeInput.setHint("6 位配对码");
        codeInput.setGravity(Gravity.CENTER);
        codeInput.setTextSize(20);
        codeInput.setSingleLine(true);
        codeInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
        cp.bottomMargin = dp(12);
        root.addView(codeInput, cp);

        pairButton = new Button(this);
        pairButton.setText("配对掌心窗");
        pairButton.setOnClickListener(v -> pair());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        bp.bottomMargin = dp(10);
        root.addView(pairButton, bp);

        Button accessibility = new Button(this);
        accessibility.setText("开启通知事件桥");
        accessibility.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Throwable e) {
                status.setText("系统没有打开无障碍设置页");
            }
        });
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        ap.bottomMargin = dp(18);
        root.addView(accessibility, ap);

        TextView foot = text(
                "这一版不再使用“通知使用权”。\n" +
                "只订阅微信的通知变化事件，并且只接受 PendingIntent 创建用户为 999 的双开微信。\n" +
                "不读屏、不自动点按主微信。",
                13, Color.rgb(120, 132, 145));
        foot.setGravity(Gravity.CENTER);
        root.addView(foot, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        refreshStatus();
    }

    @Override protected void onResume() {
        super.onResume();
        if (status != null) refreshStatus();
    }

    private ComponentName accessibilityComponent() {
        return new ComponentName(this, BridgeAccessibilityService.class);
    }

    private boolean isAccessibilityEnabled() {
        try {
            String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null || enabled.isEmpty()) return false;
            String wanted = accessibilityComponent().flattenToString();
            for (String item : enabled.split(":")) {
                if (wanted.equalsIgnoreCase(item)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void refreshStatus() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean paired = !p.getString(KEY_TOKEN, "").isEmpty();
        boolean a11y = isAccessibilityEnabled();
        status.setText((paired ? "已配对" : "未配对") + " · 通知事件桥" + (a11y ? "已开启" : "未开启"));
        codeInput.setVisibility(paired ? View.GONE : View.VISIBLE);
        pairButton.setVisibility(paired ? View.GONE : View.VISIBLE);
    }

    private void pair() {
        final String code = codeInput.getText().toString().trim();
        if (!code.matches("\\d{6}")) {
            status.setText("先输入我这边生成的 6 位配对码");
            return;
        }
        status.setText("正在配对…");
        io.execute(() -> {
            try {
                JSONObject req = new JSONObject();
                req.put("code", code);
                req.put("device_id", "wechat-bridge");
                JSONObject out = post(DEFAULT_SERVER + "/bridge/pair", req, "");
                if (!out.optBoolean("ok")) throw new Exception(out.optString("error", "pair_failed"));
                String token = out.optString("bridge_token", "");
                String server = out.optString("server_url", DEFAULT_SERVER);
                if (token.isEmpty()) throw new Exception("empty_bridge_token");
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString(KEY_TOKEN, token)
                        .putString(KEY_SERVER, server)
                        .apply();
                runOnUiThread(this::refreshStatus);
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("配对失败：" + shortMsg(e)));
            }
        });
    }

    static JSONObject post(String endpoint, JSONObject body, String bridgeToken) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(endpoint).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(12000);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        if (bridgeToken != null && !bridgeToken.isEmpty()) c.setRequestProperty("X-Bridge-Token", bridgeToken);
        c.setDoOutput(true);
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = c.getOutputStream()) { os.write(bytes); }
        int code = c.getResponseCode();
        BufferedReader br = new BufferedReader(new InputStreamReader(
                code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        c.disconnect();
        return new JSONObject(sb.length() == 0 ? "{}" : sb.toString());
    }

    static String shortMsg(Throwable e) {
        String s = e == null ? "unknown" : String.valueOf(e.getMessage());
        return s.length() > 100 ? s.substring(0, 100) : s;
    }
}
