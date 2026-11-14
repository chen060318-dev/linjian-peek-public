package dev.linjian.peek.wechatbridge;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.UserManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
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
    private Button notifyButton;

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

        TextView sub = text("主空间监听双开微信通知 · 后台收信与回复", 14, Color.rgb(105, 121, 139));
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

        Button pair = new Button(this);
        pair.setText("配对掌心窗");
        pair.setOnClickListener(v -> pair());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        bp.bottomMargin = dp(10);
        root.addView(pair, bp);

        notifyButton = new Button(this);
        notifyButton.setText("打开本应用的通知使用权");
        notifyButton.setOnClickListener(v -> openNotificationAccess());
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        np.bottomMargin = dp(18);
        root.addView(notifyButton, np);

        TextView foot = text(
                "重要：这个小桥必须安装在主空间，不要双开它。\n" +
                "微信可以继续使用双开；小桥在主空间监听双开微信的系统通知。",
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

    private ComponentName listenerComponent() {
        return new ComponentName(this, BridgeNotificationService.class);
    }

    private boolean isManagedProfile() {
        try {
            UserManager um = (UserManager) getSystemService(USER_SERVICE);
            return um != null && Build.VERSION.SDK_INT >= 24 && um.isManagedProfile();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean hasListenerAccess() {
        try {
            if (Build.VERSION.SDK_INT >= 27) {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                return nm != null && nm.isNotificationListenerAccessGranted(listenerComponent());
            }
            String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
            return flat != null && flat.contains(listenerComponent().flattenToString());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void openNotificationAccess() {
        if (isManagedProfile()) {
            status.setText("当前装在双开/工作空间，Android 不允许这里开启通知使用权。请把本 APK 安装到主空间。");
            return;
        }

        ComponentName cn = listenerComponent();
        Intent intent;
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS);
                intent.putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, cn.flattenToString());
                intent.putExtra(":settings:fragment_args_key", cn.flattenToString());
            } else {
                intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                intent.putExtra(":settings:fragment_args_key", cn.flattenToString());
            }
            startActivity(intent);
        } catch (Throwable detailFailed) {
            try {
                Intent fallback = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                fallback.putExtra(":settings:fragment_args_key", cn.flattenToString());
                startActivity(fallback);
            } catch (Throwable allFailed) {
                status.setText("系统没有打开通知使用权页面。请确认本应用安装在主空间。");
            }
        }
    }

    private void refreshStatus() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean paired = !p.getString(KEY_TOKEN, "").isEmpty();
        boolean managed = isManagedProfile();
        boolean listener = !managed && hasListenerAccess();
        int userHash;
        try { userHash = Process.myUserHandle().hashCode(); } catch (Throwable e) { userHash = Integer.MIN_VALUE; }

        if (managed) {
            status.setText("检测到双开/工作空间 · 这里无法开启通知使用权 · 请回主空间安装");
        } else {
            status.setText(
                    (paired ? "已配对" : "未配对") +
                    " · 主空间" +
                    " · 通知使用权" + (listener ? "已开启" : "未开启") +
                    " · user hash: " + userHash);
        }
        codeInput.setVisibility(paired ? EditText.GONE : EditText.VISIBLE);
        notifyButton.setEnabled(!managed);
    }

    private void pair() {
        if (isManagedProfile()) {
            status.setText("先把本 APK 安装到主空间再配对；双开空间无法使用通知监听。");
            return;
        }
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
                runOnUiThread(() -> {
                    status.setText("配对成功。现在点“打开本应用的通知使用权”授权。");
                    refreshStatus();
                });
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
