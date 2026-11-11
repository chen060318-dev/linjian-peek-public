package dev.lr.sense;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int REQ_PERMS = 71;
    private EditText endpointInput;
    private EditText tokenInput;
    private CheckBox locationBox;
    private CheckBox actionSyncBox;
    private TextView statusView;

    private SharedPreferences prefs() {
        return getSharedPreferences("lr_sense", MODE_PRIVATE);
    }

    private TextView text(String value, int size) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(Color.rgb(45, 55, 68));
        v.setPadding(0, 10, 0, 10);
        return v;
    }

    private EditText input(String hint) {
        EditText v = new EditText(this);
        v.setHint(hint);
        v.setTextSize(15);
        v.setSingleLine(true);
        v.setPadding(24, 18, 24, 18);
        return v;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        SharedPreferences p = prefs();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 56, 48, 48);
        root.setGravity(Gravity.TOP);
        root.setBackgroundColor(Color.rgb(248, 250, 252));

        TextView title = text("LR Sense Sensor", 24);
        title.setTextColor(Color.rgb(30, 41, 59));
        root.addView(title);

        root.addView(text("只负责把环境数据送给 LR Sense。设置一次后可以长期放在后台，不需要日常打开。", 14));

        root.addView(text("Render 地址", 13));
        endpointInput = input("https://lr-sense-0714.onrender.com");
        endpointInput.setText(p.getString("endpoint", "https://lr-sense-0714.onrender.com"));
        root.addView(endpointInput, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(text("SENSOR_API_TOKEN", 13));
        tokenInput = input("只保存在本机");
        tokenInput.setInputType(
            android.text.InputType.TYPE_CLASS_TEXT |
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tokenInput.setText(p.getString("token", ""));
        root.addView(tokenInput, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        locationBox = new CheckBox(this);
        locationBox.setText("允许上传粗位置，用于天气与空气质量");
        locationBox.setChecked(p.getBoolean("location_enabled", true));
        locationBox.setPadding(0, 18, 0, 18);
        root.addView(locationBox);

        actionSyncBox = new CheckBox(this);
        actionSyncBox.setText("开启行动同步（耳机、屏幕、前台 App、使用时长）");
        actionSyncBox.setChecked(p.getBoolean("action_sync_enabled", true));
        actionSyncBox.setPadding(0, 6, 0, 12);
        root.addView(actionSyncBox);

        Button usageAccess = new Button(this);
        usageAccess.setText("授权 App 使用情况访问");
        usageAccess.setOnClickListener(v -> {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS));
                statusView.setText("请在系统页面找到“感知你”，允许使用情况访问，然后返回这里点“开始感知”");
            } catch (Exception e) {
                statusView.setText("无法打开使用情况访问设置，请在系统设置里搜索“使用情况访问权限”");
            }
        });
        root.addView(usageAccess, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button start = new Button(this);
        start.setText("开始感知");
        start.setOnClickListener(v -> saveAndStart());
        root.addView(start, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button stop = new Button(this);
        stop.setText("停止");
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, SenseService.class));
            statusView.setText("已停止后台感知");
        });
        root.addView(stop, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        statusView = text("环境光与运动状态不会上传原始传感器流；行动同步只上传设备状态、包名和使用时长，不读取 App 内容。", 13);
        root.addView(statusView);

        setContentView(root);
    }

    private void saveAndStart() {
        String endpoint = endpointInput.getText().toString().trim();
        String token = tokenInput.getText().toString().trim();

        if (endpoint.isEmpty() || token.isEmpty()) {
            statusView.setText("请先填 Render 地址和 SENSOR_API_TOKEN");
            return;
        }

        prefs().edit()
            .putString("endpoint", endpoint.replaceAll("/+$", ""))
            .putString("token", token)
            .putBoolean("location_enabled", locationBox.isChecked())
            .putBoolean("action_sync_enabled", actionSyncBox.isChecked())
            .apply();

        java.util.ArrayList<String> needed = new java.util.ArrayList<>();

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        if (locationBox.isChecked() &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }

        if (!needed.isEmpty() && Build.VERSION.SDK_INT >= 23) {
            requestPermissions(needed.toArray(new String[0]), REQ_PERMS);
        } else {
            startSense();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS) startSense();
    }

    private void startSense() {
        Intent intent = new Intent(this, SenseService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        statusView.setText("后台感知已启动。可以退出这个页面。");
    }
}
