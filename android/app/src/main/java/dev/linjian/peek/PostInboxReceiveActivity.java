package dev.linjian.peek;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

import org.json.JSONObject;

public class PostInboxReceiveActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        receiveAndFinish();
    }

    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        receiveAndFinish();
    }

    private void receiveAndFinish() {
        JSONObject result = PostInboxStore.receiveIntent(this, getIntent());
        int count = 0;
        try { count = result.optJSONArray("saved") == null ? 0 : result.optJSONArray("saved").length(); } catch (Exception ignored) {}
        Toast.makeText(this, count > 0 ? "已加入掌心窗待发布素材箱" : "没有可接收的图片或视频", Toast.LENGTH_SHORT).show();
        finish();
    }
}
