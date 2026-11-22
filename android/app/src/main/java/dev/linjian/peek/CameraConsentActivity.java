package dev.linjian.peek;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * One-shot, user-consented camera bridge.
 *
 * Privacy contract:
 * 1) A remote request only creates a local notification; it never opens the camera by itself.
 * 2) The user must open this Activity and tap "允许并打开相机".
 * 3) The system camera UI requires the user to press the shutter.
 * 4) The captured image is shown locally first and is uploaded only after "发送给 Riven".
 * 5) Cancel/deny deletes the temporary MediaStore item and reports failure for this command.
 *
 * The app itself never receives a standing CAMERA permission and never captures in background.
 */
public class CameraConsentActivity extends Activity {
    private static final int REQUEST_IMAGE_CAPTURE = 4201;
    private static final int MAX_CAMERA_BYTES = 24 * 1024 * 1024;

    private String serverUrl = "";
    private String token = "";
    private String commandId = "";
    private String lens = "front";
    private Uri photoUri;
    private Button sendButton;
    private boolean finishedOutcome = false;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        serverUrl = ScreenshotService.normalizeUrl(intent == null ? "" : intent.getStringExtra("server_url"));
        token = intent == null ? "" : safe(intent.getStringExtra("token"));
        commandId = intent == null ? "" : safe(intent.getStringExtra("command_id"));
        lens = normalizeLens(intent == null ? "" : intent.getStringExtra("camera_lens"));

        if (serverUrl.isEmpty()) serverUrl = ScreenshotService.normalizeUrl(AppPrefs.server(this));
        if (token.isEmpty()) token = AppPrefs.token(this);
        if (commandId.isEmpty() || serverUrl.isEmpty() || token.isEmpty()) {
            Toast.makeText(this, "相机请求信息不完整，本次不会拍照", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        showConsent("");
    }

    private String safe(String value) { return value == null ? "" : value.trim(); }

    private String normalizeLens(String value) {
        String v = safe(value).toLowerCase();
        return "back".equals(v) || "rear".equals(v) ? "back" : "front";
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView text(String value, float sp, int color) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setLineSpacing(0, 1.12f);
        return tv;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(10);
        b.setLayoutParams(p);
        return b;
    }

    private LinearLayout baseLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(30), dp(24), dp(24));
        root.setBackgroundColor(Color.rgb(247, 250, 253));
        return root;
    }

    private void showConsent(String note) {
        LinearLayout root = baseLayout();

        TextView title = text("Riven 想看一眼", 24, Color.rgb(43, 57, 72));
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title);

        TextView privacy = text(
                "\n这次请求不会自动打开摄像头，也不会后台拍照。\n\n" +
                "你点“允许并打开相机”后，仍然要由你自己按快门；拍完会先在掌心窗里预览，只有你再点一次“发送给 Riven”，照片才会上传。",
                16, Color.rgb(82, 101, 120));
        root.addView(privacy);

        TextView lensView = text(
                "\n本次希望：" + ("front".equals(lens) ? "前置相机" : "后置相机") +
                "（只是启动偏好；系统相机不支持时你仍可自己切换）",
                14, Color.rgb(111, 129, 146));
        root.addView(lensView);

        if (!safe(note).isEmpty()) {
            TextView noteView = text("\n" + note, 14, Color.rgb(153, 78, 78));
            root.addView(noteView);
        }

        Button allow = button("允许并打开相机");
        allow.setOnClickListener(v -> launchCamera());
        root.addView(allow);

        Button deny = button("拒绝这次请求");
        deny.setOnClickListener(v -> denyAndFinish("camera_request_denied_by_user"));
        root.addView(deny);

        TextView foot = text("\n不选择也没关系。忽略通知或离开此页，都不会让摄像头在后台持续工作。", 13, Color.rgb(130, 145, 160));
        root.addView(foot);
        setContentView(root);
    }

    private void launchCamera() {
        deleteTempPhoto();
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, "linjian_camera_" + System.currentTimeMillis() + ".jpg");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LinjianTemp");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
            }
            photoUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (photoUri == null) throw new IllegalStateException("MediaStore insert failed");

            Intent camera = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            camera.putExtra(MediaStore.EXTRA_OUTPUT, photoUri);
            camera.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if ("front".equals(lens)) {
                camera.putExtra("android.intent.extras.CAMERA_FACING", 1);
                camera.putExtra("android.intent.extra.USE_FRONT_CAMERA", true);
            } else {
                camera.putExtra("android.intent.extras.CAMERA_FACING", 0);
                camera.putExtra("android.intent.extra.USE_REAR_CAMERA", true);
            }
            startActivityForResult(camera, REQUEST_IMAGE_CAPTURE);
        } catch (Exception e) {
            deleteTempPhoto();
            showConsent("系统相机没有成功打开：" + ScreenshotService.shortMsg(e));
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMAGE_CAPTURE) return;
        if (resultCode == RESULT_OK && photoUri != null) {
            showPreview();
        } else {
            deleteTempPhoto();
            showConsent("刚才没有拍照，什么都没有发送。");
        }
    }

    private void showPreview() {
        LinearLayout root = baseLayout();

        TextView title = text("先给梨梨自己看", 22, Color.rgb(43, 57, 72));
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title);

        TextView hint = text("\n这张照片现在只在手机本地临时预览。满意再发送，不满意可以重拍或取消。", 14, Color.rgb(94, 113, 132));
        root.addView(hint);

        ImageView preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setBackgroundColor(Color.rgb(232, 238, 244));
        preview.setImageURI(photoUri);
        LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        imageParams.topMargin = dp(16);
        preview.setLayoutParams(imageParams);
        root.addView(preview);

        sendButton = button("发送给 Riven");
        sendButton.setOnClickListener(v -> uploadApprovedPhoto());
        root.addView(sendButton);

        Button retake = button("重拍");
        retake.setOnClickListener(v -> launchCamera());
        root.addView(retake);

        Button cancel = button("取消，不发送");
        cancel.setOnClickListener(v -> denyAndFinish("camera_photo_cancelled_after_preview"));
        root.addView(cancel);

        setContentView(root);
    }

    private byte[] readPhotoBytes() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = getContentResolver().openInputStream(photoUri)) {
            if (in == null) throw new IllegalStateException("camera image unavailable");
            byte[] buf = new byte[16 * 1024];
            int total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_CAMERA_BYTES) throw new IllegalStateException("camera image too large");
                out.write(buf, 0, n);
            }
        }
        byte[] data = out.toByteArray();
        if (data.length < 100) throw new IllegalStateException("camera image empty");
        return data;
    }

    private void uploadApprovedPhoto() {
        if (photoUri == null || sendButton == null) return;
        sendButton.setEnabled(false);
        sendButton.setText("正在发送…");

        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                byte[] data = readPhotoBytes();
                conn = (HttpURLConnection) new URL(serverUrl + "/api/camera").openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("X-Auth-Token", token);
                conn.setRequestProperty("X-Command-Id", commandId);
                conn.setRequestProperty("X-Camera-Lens", lens);
                conn.setRequestProperty("Content-Type", "image/jpeg");
                conn.setFixedLengthStreamingMode(data.length);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(data);
                }
                int code = conn.getResponseCode();
                String body = ScreenshotService.readBody(conn, code);
                if (code >= 200 && code < 300) {
                    finishedOutcome = true;
                    CompanionService.rememberCommandReceipt(this, commandId, true, "camera_photo_sent");
                    clearPendingFlag();
                    deleteTempPhoto();
                    runOnUiThread(() -> {
                        Toast.makeText(this, "已经发送给 Riven", Toast.LENGTH_SHORT).show();
                        finish();
                    });
                } else {
                    runOnUiThread(() -> {
                        if (sendButton != null) {
                            sendButton.setEnabled(true);
                            sendButton.setText("重新发送");
                        }
                        Toast.makeText(this, "发送失败（HTTP " + code + "），照片还只在本地，可以重试或取消", Toast.LENGTH_LONG).show();
                    });
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (sendButton != null) {
                        sendButton.setEnabled(true);
                        sendButton.setText("重新发送");
                    }
                    Toast.makeText(this, "发送失败：" + ScreenshotService.shortMsg(e), Toast.LENGTH_LONG).show();
                });
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "LinjianCameraUpload").start();
    }

    private void deleteTempPhoto() {
        Uri uri = photoUri;
        photoUri = null;
        if (uri == null) return;
        try { getContentResolver().delete(uri, null, null); } catch (Exception ignored) { }
    }

    private void clearPendingFlag() {
        try {
            getSharedPreferences(AppPrefs.PREFS, MODE_PRIVATE)
                    .edit().remove("camera_pending_" + commandId).apply();
        } catch (Exception ignored) { }
    }

    private void denyAndFinish(String reason) {
        if (finishedOutcome) return;
        finishedOutcome = true;
        deleteTempPhoto();
        clearPendingFlag();
        CompanionService.reportCommandOutcome(this, serverUrl, token, commandId, false, reason);
        finish();
    }

    @Override public void onBackPressed() {
        denyAndFinish("camera_request_cancelled_by_user");
    }
}
