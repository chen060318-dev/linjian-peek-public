package dev.linjian.peek.wechatbridge;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.view.accessibility.AccessibilityEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BridgeAccessibilityService extends AccessibilityService {
    private static final String WECHAT = "com.tencent.mm";
    private static final int TARGET_CLONE_USER_ID = 999;
    private static final int MAX_ACTIONS = 50;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ConcurrentHashMap<String, Notification.Action> replyActions = new ConcurrentHashMap<>();

    private final Runnable poller = new Runnable() {
        @Override public void run() {
            io.execute(() -> {
                try { pollReplies(); } catch (Exception ignored) {}
            });
            handler.postDelayed(this, 3000);
        }
    };

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        handler.removeCallbacks(poller);
        handler.postDelayed(poller, 1000);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !WECHAT.contentEquals(pkg)) return;

        Object raw = event.getParcelableData();
        if (!(raw instanceof Notification)) return;
        Notification n = (Notification) raw;

        int creatorUser = creatorUserHash(n);
        if (creatorUser != TARGET_CLONE_USER_ID) return;

        String sender = "";
        String body = "";
        Bundle extras = n.extras;
        if (extras != null) {
            sender = clean(extras.getCharSequence(Notification.EXTRA_TITLE));
            body = clean(extras.getCharSequence(Notification.EXTRA_TEXT));
            if (body.isEmpty()) body = clean(extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        }
        if (body.isEmpty() && event.getText() != null && !event.getText().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (CharSequence cs : event.getText()) {
                String s = clean(cs);
                if (s.isEmpty()) continue;
                if (sb.length() > 0) sb.append(" · ");
                sb.append(s);
            }
            body = sb.toString();
        }
        if (sender.isEmpty() && body.isEmpty()) return;

        final String finalSender = sender;
        final String finalBody = body;
        final long posted = event.getEventTime() > 0 ? event.getEventTime() : System.currentTimeMillis();
        final String eventId = Integer.toHexString((finalSender + ":" + finalBody + ":" + posted).hashCode()) + "-" + posted;
        final Notification.Action replyAction = findReplyAction(n);
        if (replyAction != null) {
            replyActions.put(eventId, replyAction);
            trimActions();
        }

        io.execute(() -> {
            try {
                SharedPreferences p = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
                String token = p.getString(MainActivity.KEY_TOKEN, "");
                String server = p.getString(MainActivity.KEY_SERVER, MainActivity.DEFAULT_SERVER);
                if (token.isEmpty()) return;

                JSONObject payload = new JSONObject();
                payload.put("id", eventId);
                payload.put("sender", finalSender);
                payload.put("text", finalBody);
                payload.put("notification_key", "a11y:" + eventId);
                payload.put("posted_at_ms", posted);
                payload.put("can_inline_reply", replyAction != null);
                MainActivity.post(server + "/bridge/event", payload, token);
            } catch (Exception ignored) {}
        });
    }

    @Override public void onInterrupt() {}

    @Override public void onDestroy() {
        handler.removeCallbacks(poller);
        io.shutdownNow();
        replyActions.clear();
        super.onDestroy();
    }

    private int creatorUserHash(Notification n) {
        if (n == null) return Integer.MIN_VALUE;
        try {
            if (n.contentIntent != null) {
                UserHandle u = n.contentIntent.getCreatorUserHandle();
                if (u != null) return u.hashCode();
            }
        } catch (Throwable ignored) {}
        if (n.actions != null) {
            for (Notification.Action action : n.actions) {
                if (action == null || action.actionIntent == null) continue;
                try {
                    UserHandle u = action.actionIntent.getCreatorUserHandle();
                    if (u != null) return u.hashCode();
                } catch (Throwable ignored) {}
            }
        }
        return Integer.MIN_VALUE;
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

    private void trimActions() {
        while (replyActions.size() > MAX_ACTIONS) {
            Iterator<String> it = replyActions.keySet().iterator();
            if (!it.hasNext()) return;
            replyActions.remove(it.next());
        }
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

    private String applyReply(String eventId, String text) {
        if (text == null || text.trim().isEmpty()) return "empty_reply_text";
        Notification.Action action = replyActions.get(eventId);
        if (action == null) return "accessibility_reply_action_not_found";
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

    private static String clean(CharSequence cs) {
        return cs == null ? "" : cs.toString().trim();
    }
}
