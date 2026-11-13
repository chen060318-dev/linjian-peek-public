package dev.linjian.peek.wechatbridge;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BridgeNotificationService extends NotificationListenerService {
    private static final String WECHAT = "com.tencent.mm";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Runnable poller = new Runnable() {
        @Override public void run() {
            io.execute(() -> {
                try { pollReplies(); } catch (Exception ignored) {}
            });
            handler.postDelayed(this, 3000);
        }
    };

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        handler.removeCallbacks(poller);
        handler.postDelayed(poller, 1000);
    }

    @Override public void onListenerDisconnected() {
        handler.removeCallbacks(poller);
        super.onListenerDisconnected();
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(poller);
        io.shutdownNow();
        super.onDestroy();
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || !WECHAT.equals(sbn.getPackageName())) return;
        final Notification n = sbn.getNotification();
        if (n == null) return;
        final Bundle extras = n.extras;
        final String sender = clean(extras == null ? null : extras.getCharSequence(Notification.EXTRA_TITLE));
        String body = clean(extras == null ? null : extras.getCharSequence(Notification.EXTRA_TEXT));
        if (body.isEmpty()) body = clean(extras == null ? null : extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        final String text = body;
        if (sender.isEmpty() && text.isEmpty()) return;
        final String eventId = eventId(sbn);
        final String notificationKey = sbn.getKey() == null ? "" : sbn.getKey();
        final long posted = sbn.getPostTime();
        final boolean reply = findReplyAction(n) != null;
        io.execute(() -> {
            try {
                SharedPreferences p = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
                String token = p.getString(MainActivity.KEY_TOKEN, "");
                String server = p.getString(MainActivity.KEY_SERVER, MainActivity.DEFAULT_SERVER);
                if (token.isEmpty()) return;
                JSONObject event = new JSONObject();
                event.put("id", eventId);
                event.put("sender", sender);
                event.put("text", text);
                event.put("notification_key", notificationKey);
                event.put("posted_at_ms", posted);
                event.put("can_inline_reply", reply);
                MainActivity.post(server + "/bridge/event", event, token);
            } catch (Exception ignored) {}
        });
    }

    private void pollReplies() throws Exception {
        SharedPreferences p = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        String token = p.getString(MainActivity.KEY_TOKEN, "");
        String server = p.getString(MainActivity.KEY_SERVER, MainActivity.DEFAULT_SERVER);
        if (token.isEmpty()) return;

        HttpURLConnection c = (HttpURLConnection) new URL(server + "/bridge/replies?device_id=wechat-bridge").openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.setRequestMethod("GET");
        c.setRequestProperty("X-Bridge-Token", token);
        int http = c.getResponseCode();
        BufferedReader br = new BufferedReader(new InputStreamReader(
                http >= 200 && http < 300 ? c.getInputStream() : c.getErrorStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        c.disconnect();
        if (http < 200 || http >= 300) return;

        JSONObject out = new JSONObject(sb.length() == 0 ? "{}" : sb.toString());
        JSONArray items = out.optJSONArray("items");
        if (items == null) return;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String replyId = item.optString("id", "");
            String eventId = item.optString("event_id", "");
            String text = item.optString("text", "");
            String status = applyReply(eventId, text);
            JSONObject ack = new JSONObject();
            ack.put("reply_id", replyId);
            ack.put("status", status);
            try { MainActivity.post(server + "/bridge/reply-ack", ack, token); } catch (Exception ignored) {}
        }
    }

    private String applyReply(String wantedEventId, String text) {
        if (text == null || text.trim().isEmpty()) return "empty_reply_text";
        StatusBarNotification[] active;
        try { active = getActiveNotifications(); } catch (Throwable e) { return "active_notifications_unavailable"; }
        if (active == null) return "notification_not_active";
        StatusBarNotification target = null;
        for (StatusBarNotification sbn : active) {
            if (sbn == null || !WECHAT.equals(sbn.getPackageName())) continue;
            if (wantedEventId.equals(eventId(sbn))) { target = sbn; break; }
        }
        if (target == null) return "notification_not_active";
        Notification.Action action = findReplyAction(target.getNotification());
        if (action == null) return "inline_reply_unavailable";
        RemoteInput[] inputs = action.getRemoteInputs();
        if (inputs == null || inputs.length == 0) return "inline_reply_unavailable";
        Intent fillIn = new Intent();
        Bundle results = new Bundle();
        for (RemoteInput input : inputs) results.putCharSequence(input.getResultKey(), text);
        RemoteInput.addResultsToIntent(inputs, fillIn, results);
        try {
            action.actionIntent.send(this, 0, fillIn);
            return "sent";
        } catch (PendingIntent.CanceledException e) {
            return "pending_intent_canceled";
        } catch (Exception e) {
            return "reply_failed";
        }
    }

    private static Notification.Action findReplyAction(Notification n) {
        if (n == null || n.actions == null) return null;
        for (Notification.Action action : n.actions) {
            if (action == null || action.actionIntent == null) continue;
            RemoteInput[] inputs = action.getRemoteInputs();
            if (inputs != null && inputs.length > 0) return action;
        }
        return null;
    }

    private static String eventId(StatusBarNotification sbn) {
        String key = sbn.getKey() == null ? "" : sbn.getKey();
        return Integer.toHexString((key + ":" + sbn.getPostTime()).hashCode()) + "-" + sbn.getPostTime();
    }

    private static String clean(CharSequence cs) {
        return cs == null ? "" : cs.toString().trim();
    }
}
