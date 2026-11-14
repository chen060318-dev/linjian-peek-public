package dev.linjian.peek;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.UserHandle;
import android.view.accessibility.AccessibilityEvent;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;

public final class WeChatAccessibilityBridge {
    private static final String WECHAT_PACKAGE = "com.tencent.mm";
    private static final int TARGET_CLONE_USER_ID = 999;
    private static final int MAX_ACTIONS = 50;
    private static final ConcurrentHashMap<String, Notification.Action> REPLY_ACTIONS = new ConcurrentHashMap<>();
    private static volatile String latestEventId = "";

    private WeChatAccessibilityBridge() {}

    public static void capture(Context ctx, AccessibilityEvent event) {
        if (ctx == null || event == null) return;
        try {
            if (event.getEventType() != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) return;
            CharSequence pkg = event.getPackageName();
            if (pkg == null || !WECHAT_PACKAGE.contentEquals(pkg)) return;

            Object raw = event.getParcelableData();
            if (!(raw instanceof Notification)) return;
            Notification n = (Notification) raw;

            int userId = creatorUserHash(n);
            if (userId != TARGET_CLONE_USER_ID) return;

            Bundle extras = n.extras;
            String sender = clean(extras == null ? null : extras.getCharSequence(Notification.EXTRA_TITLE));
            String text = clean(extras == null ? null : extras.getCharSequence(Notification.EXTRA_TEXT));
            if (text.isEmpty()) text = clean(extras == null ? null : extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
            if (text.isEmpty() && event.getText() != null && !event.getText().isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (CharSequence cs : event.getText()) {
                    String s = clean(cs);
                    if (s.isEmpty()) continue;
                    if (sb.length() > 0) sb.append(" · ");
                    sb.append(s);
                }
                text = sb.toString();
            }
            if (sender.isEmpty() && text.isEmpty()) return;

            long postedAt = System.currentTimeMillis();
            String eventId = Integer.toHexString((sender + ":" + text + ":" + postedAt).hashCode()) + "-" + postedAt;
            Notification.Action replyAction = findReplyAction(n);
            if (replyAction != null) {
                REPLY_ACTIONS.put(eventId, replyAction);
                latestEventId = eventId;
                trimActions();
            }

            final String fSender = sender;
            final String fText = text;
            final String fEventId = eventId;
            final boolean canReply = replyAction != null;
            new Thread(() -> upload(ctx, fEventId, fSender, fText, postedAt, canReply), "WechatA11yUpload").start();
        } catch (Exception e) {
            DebugState.append(ctx, "双开微信无障碍通知捕获失败：" + ScreenshotService.shortMsg(e));
        }
    }

    public static JSONObject reply(Context ctx, String eventId, String replyText) {
        JSONObject out = new JSONObject();
        try {
            String text = replyText == null ? "" : replyText.trim();
            if (text.isEmpty()) return error("empty_reply_text");
            String wanted = eventId == null ? "" : eventId.trim();
            if (wanted.isEmpty() || "LATEST".equalsIgnoreCase(wanted)) wanted = latestEventId;
            Notification.Action action = REPLY_ACTIONS.get(wanted);
            if (action == null) return error("accessibility_reply_action_not_found");
            RemoteInput[] inputs = action.getRemoteInputs();
            if (inputs == null || inputs.length == 0) return error("inline_reply_unavailable");

            Intent fillIn = new Intent();
            Bundle results = new Bundle();
            for (RemoteInput input : inputs) results.putCharSequence(input.getResultKey(), text);
            RemoteInput.addResultsToIntent(inputs, fillIn, results);
            action.actionIntent.send(ctx, 0, fillIn);

            out.put("ok", true);
            out.put("mode", "accessibility_notification_remote_input");
            out.put("event_id", wanted);
            out.put("replied_text", text);
            out.put("user_id", TARGET_CLONE_USER_ID);
            return out;
        } catch (PendingIntent.CanceledException e) {
            return error("reply_pending_intent_canceled");
        } catch (Exception e) {
            return error("reply_failed:" + ScreenshotService.shortMsg(e));
        }
    }

    private static void upload(Context ctx, String eventId, String sender, String text, long postedAt, boolean canReply) {
        try {
            String server = ScreenshotService.normalizeUrl(AppPrefs.server(ctx));
            String token = AppPrefs.token(ctx);
            if (server == null || server.isEmpty() || token == null || token.isEmpty()) return;

            JSONObject body = new JSONObject();
            body.put("id", eventId);
            body.put("device_id", AppPrefs.device(ctx));
            body.put("user_id", TARGET_CLONE_USER_ID);
            body.put("sender", sender);
            body.put("text", text);
            body.put("notification_key", "a11y:" + eventId);
            body.put("posted_at_ms", postedAt);
            body.put("can_inline_reply", canReply);

            HttpURLConnection conn = (HttpURLConnection)new URL(server + "/api/wechat-bridge/event-from-device").openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("X-Auth-Token", token);
            conn.setDoOutput(true);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) { os.write(data); }
            int code = conn.getResponseCode();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (is != null) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[512];
                int n;
                while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                is.close();
            }
            conn.disconnect();
            if (code >= 200 && code < 300) {
                DebugState.append(ctx, "双开微信后台消息已送达：" + sender + (text.isEmpty() ? "" : " · " + clip(text)));
            } else {
                DebugState.append(ctx, "双开微信后台消息上传失败：HTTP " + code);
            }
        } catch (Exception e) {
            DebugState.append(ctx, "双开微信后台消息上传异常：" + ScreenshotService.shortMsg(e));
        }
    }

    private static int creatorUserHash(Notification n) {
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

    private static void trimActions() {
        while (REPLY_ACTIONS.size() > MAX_ACTIONS) {
            Iterator<String> it = REPLY_ACTIONS.keySet().iterator();
            if (!it.hasNext()) break;
            REPLY_ACTIONS.remove(it.next());
        }
    }

    private static String clean(CharSequence value) {
        return value == null ? "" : value.toString().trim();
    }

    private static String clip(String s) {
        if (s == null) return "";
        return s.length() <= 80 ? s : s.substring(0, 80) + "…";
    }

    private static JSONObject error(String message) {
        JSONObject out = new JSONObject();
        try {
            out.put("ok", false);
            out.put("error", message);
            out.put("user_id", TARGET_CLONE_USER_ID);
        } catch (Exception ignored) {}
        return out;
    }
}
