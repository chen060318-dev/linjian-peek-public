package dev.lr.sense;

import android.Manifest;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class SenseService extends Service implements SensorEventListener, LocationListener {
    private static final int NOTIFICATION_ID = 714;
    private static final String CHANNEL_ID = "lr_sense";
    private static final long UPLOAD_MS = 60_000L;

    private SensorManager sensorManager;
    private LocationManager locationManager;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private Float lightLux = null;
    private final java.util.Map<String, Float> lightReadings = new java.util.HashMap<>();
    private int lightSensorCount = 0;
    private float lastAccelMag = Float.NaN;
    private float movementScore = 0f;
    private Location lastLocation;

    private SharedPreferences prefs() {
        return getSharedPreferences("lr_sense", MODE_PRIVATE);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification("正在感知环境"));

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        java.util.List<Sensor> lights = sensorManager.getSensorList(Sensor.TYPE_LIGHT);
        lightSensorCount = lights == null ? 0 : lights.size();
        if (lights != null) {
            for (Sensor light : lights) {
                sensorManager.registerListener(this, light, SensorManager.SENSOR_DELAY_UI);
            }
        }

        Sensor accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accel != null) sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_NORMAL);

        maybeStartLocation();
        handler.post(uploadLoop);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    private final Runnable uploadLoop = new Runnable() {
        @Override
        public void run() {
            uploadSnapshot();
            handler.postDelayed(this, UPLOAD_MS);
        }
    };

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "LR Sense 环境感知",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("保持 LR Sense 在后台读取环境传感器");
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);
        return b
            .setContentTitle("LR Sense")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build();
    }

    private void maybeStartLocation() {
        if (!prefs().getBoolean("location_enabled", true)) return;
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        try {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, 300_000L, 100f, this);
            }
        } catch (Exception ignored) {}
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 300_000L, 100f, this);
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_LIGHT && event.values.length > 0) {
            float value = event.values[0];
            if (!Float.isNaN(value) && !Float.isInfinite(value) && value >= 0f) {
                String key = event.sensor.getName() + "|" + event.sensor.getVendor();
                lightReadings.put(key, value);
                float best = 0f;
                boolean have = false;
                for (Float v : lightReadings.values()) {
                    if (v != null && !Float.isNaN(v) && !Float.isInfinite(v) && v >= 0f) {
                        if (!have || v > best) best = v;
                        have = true;
                    }
                }
                lightLux = have ? best : null;
            }
            return;
        }

        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER && event.values.length >= 3) {
            float x = event.values[0], y = event.values[1], z = event.values[2];
            float mag = (float)Math.sqrt(x*x + y*y + z*z);
            if (!Float.isNaN(lastAccelMag)) {
                float delta = Math.abs(mag - lastAccelMag);
                if (delta > movementScore) movementScore = delta;
            }
            lastAccelMag = mag;
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    @Override public void onLocationChanged(Location location) { lastLocation = location; }
    @Override public void onProviderEnabled(String provider) {}
    @Override public void onProviderDisabled(String provider) {}
    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}

    private String motionState() {
        if (movementScore < 0.25f) return "still";
        if (movementScore < 1.5f) return "moving";
        return "active";
    }

    private JSONObject batteryJson(JSONObject out) throws Exception {
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) return out;

        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level >= 0 && scale > 0) out.put("batteryPercent", level * 100.0 / scale);

        int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL;
        out.put("charging", charging);
        out.put("batteryStatus", String.valueOf(status));

        int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
        out.put("batteryPlugged", String.valueOf(plugged));

        int health = battery.getIntExtra(BatteryManager.EXTRA_HEALTH, -1);
        out.put("batteryHealth", String.valueOf(health));

        int temp = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        if (temp != Integer.MIN_VALUE) out.put("batteryTemperatureC", temp / 10.0);

        if (Build.VERSION.SDK_INT >= 21) {
            BatteryManager bm = (BatteryManager)getSystemService(BATTERY_SERVICE);
            long current = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            if (current != Long.MIN_VALUE) out.put("batteryCurrentUa", current);
        }
        return out;
    }

    private void addDeviceVitals(JSONObject out) throws Exception {
        out.put("deviceModel", Build.MODEL);
        out.put("deviceManufacturer", Build.MANUFACTURER);
        out.put("androidVersion", Build.VERSION.RELEASE);
        out.put("sdkInt", Build.VERSION.SDK_INT);
        out.put("timezone", TimeZone.getDefault().getID());
        out.put("uptimeSeconds", SystemClock.elapsedRealtime() / 1000.0);

        ActivityManager am = (ActivityManager)getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        out.put("memoryTotalMb", mi.totalMem / 1048576.0);
        out.put("memoryAvailableMb", mi.availMem / 1048576.0);

        StatFs stat = new StatFs(getFilesDir().getAbsolutePath());
        out.put("storageTotalGb", stat.getTotalBytes() / 1073741824.0);
        out.put("storageFreeGb", stat.getAvailableBytes() / 1073741824.0);

        try {
            int brightness = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
            out.put("screenBrightness", brightness);
        } catch (Exception ignored) {}
    }

    private String isoNow() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date());
    }

    private void uploadSnapshot() {
        final String endpoint = prefs().getString("endpoint", "");
        final String token = prefs().getString("token", "");
        if (endpoint.isEmpty() || token.isEmpty()) return;

        final JSONObject payload = new JSONObject();
        try {
            batteryJson(payload);
            addDeviceVitals(payload);

            if (lightLux != null) payload.put("lightLux", lightLux);
            payload.put("motionState", motionState());
            movementScore = 0f;

            if (lastLocation != null) {
                payload.put("latitude", lastLocation.getLatitude());
                payload.put("longitude", lastLocation.getLongitude());
            }

            payload.put("capturedAt", isoNow());
        } catch (Exception ignored) {}

        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(endpoint + "/sensor/update");
                conn = (HttpURLConnection)url.openConnection();
                conn.setConnectTimeout(15_000);
                conn.setReadTimeout(15_000);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Authorization", "Bearer " + token);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("User-Agent", "LR-Sense-Sensor/0.1");

                byte[] body = payload.toString().getBytes("UTF-8");
                conn.setFixedLengthStreamingMode(body.length);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body);
                }

                int code = conn.getResponseCode();
                String note = code >= 200 && code < 300
                    ? "已连接 · 光感" + lightSensorCount + "颗 · " + (lightLux == null ? "等待读数" : Math.round(lightLux) + " lux")
                    : "上传失败 HTTP " + code;

                final String finalNote = note;
                handler.post(() -> {
                    NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
                    nm.notify(NOTIFICATION_ID, buildNotification(finalNote));
                });
            } catch (Exception e) {
                handler.post(() -> {
                    NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
                    nm.notify(NOTIFICATION_ID, buildNotification("暂时无法连接 Render"));
                });
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(uploadLoop);
        if (sensorManager != null) sensorManager.unregisterListener(this);
        if (locationManager != null) {
            try { locationManager.removeUpdates(this); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
