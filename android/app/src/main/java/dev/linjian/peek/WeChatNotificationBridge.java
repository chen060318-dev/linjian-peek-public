package dev.linjian.peek;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * WeChat notification bridge for the dedicated cloned profile only.
 *
 * Safety invariant: never records or replies to the owner's primary WeChat.
 * The current HyperOS clone is UserHandle{999}; all other users are ignored.
 */
public final class WeChatNotificationBridge {
    private static final String PREFS = "wechat_clone_bridge";
    private static final String KEY_EVENTS = "events";
    private static final String WECHAT_PACKAGE = "com.tencent.mm";
    private static final int TARGET_CLONE_USER_ID = 999;
    private static final int MAX_EVENTS = 30;

    private WeChatNotificationBridge() {}

    public static boolean isTargetClone(StatusBarNotification sbn) {
        if (sbn == null || !WECHAT_PACKAGE.equals(sbn.getPackageName())) return false;
        try {
            return sbn.getUser() != null && sbn.getUser().getIdentifier() == TARGET_CLONE_USER_ID;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void capture(Context ctx, StatusBarNotification sbn) {
        if (!isTargetClone(sbn)) return;
        try {
            Notification n = sbn.getNotification();
            Bundle extras = n == null ? null : n.extras;
            String title = clean(extras == null ? "" : extras.getCharSequence(Notification.EXTRA_TITLE));
            String text = clean(extras == null ? "" : extras.getCharSequence(Notification.EXTRA_TEXT));
            if (text.isEmpty()) {
                text = clean(extras == null ? "" : extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
            }
            if (title.isEmpty() && text.isEmpty()) return;

            JSONObject event = new JSONObject();
            event.put("id", eventId(sbn));
            event.put("notification_key", sbn.getKey());
            event.put("package", sbn.getPackageName());
            event.put("user_id", TARGET_CLONE_USER_ID);
            event.put("sender", title);
            event.put("text", text);
            event.put("posted_at_ms", sbn.getPostTime());
            event.put("captured_at_ms", System.currentTimeMillis());
            event.put("can_inline_reply", hasRemoteInput(n));

            JSONArray existing = readEvents(ctx);
            JSONArray next = new JSONArray();
            next.put(event);
            String id = event.optString("id", "");
            for (int i = 0; i < existing.length() && next.length() < MAX_EVENTS; i++) {
                JSONObject old = existing.optJSONObject(i);
                if (old == null) continue;
                if (id.equals(old.optString("id", ""))) continue;
                next.put(old);
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_EVENTS, next.toString()).apply();
            DebugState.append(ctx, "双开微信收到消息：" + title + (text.isEmpty() ? "" : " · " + clip(text)));
        } catch (Exception e) {
            DebugState.append(ctx, "双开微信通知记录失败：" + ScreenshotService.shortMsg(e));
        }
    }

    public static JSONObject list(Context ctx, int limit) {
        JSONObject out = new JSONObject();
        JSONArray items = new JSONArray();
        JSONArray all = readEvents(ctx);
        int safeLimit = Math.max(1, Math.min(limit <= 0 ? 10 : limit, MAX_EVENTS));
        for (int i = 0; i < all.length() && items.length() < safeLimit; i++) {
            JSONObject event = all.optJSONObject(i);
            if (event != null) items.put(event);
        }
        try {
            out.put("ok", true);
            out.put("clone_user_id", TARGET_CLONE_USER_ID);
            out.put("items", items);
            out.put("count", items.length());
            out.put("safety_note", "Only UserHandle{999} clone WeChat notifications are retained; primary WeChat is ignored.");
        } catch (Exception ignored) {}
        return out;
    }

    public static JSONObject reply(MediaNotificationService service, String eventId, String replyText) {
        JSONObject out = new JSONObject();
        try {
            String text = replyText == null ? "" : replyText.trim();
            if (text.isEmpty()) return error("empty_reply_text");
            JSONObject event = findEvent(service, eventId);
            if (event == null) return error("wechat_event_not_found");

            String key = event.optString("notification_key", "");
            StatusBarNotification target = null;
            StatusBarNotification[] active = service.getActiveNotifications();
            if (active != null) {
                for (StatusBarNotification sbn : active) {
                    if (!isTargetClone(sbn)) continue;
                    if (key.equals(sbn.getKey())) { target = sbn; break; }
                }
            }
            if (target == null) return error("wechat_notification_not_active");

            Notification.Action action = findReplyAction(target.getNotification());
            if (action == null) return error("inline_reply_unavailable");

            RemoteInput[] inputs = action.getRemoteInputs();
            if (inputs == null || inputs.length == 0) return error("inline_reply_unavailable");

            Intent fillIn = new Intent();
            Bundle results = new Bundle();
            for (RemoteInput input : inputs) {
                results.putCharSequence(input.getResultKey(), text);
            }
            RemoteInput.addResultsToIntent(inputs, fillIn, results);
            action.actionIntent.send(service, 0, fillIn);

            markReplied(service, eventId, text);
            out.put("ok", true);
            out.put("mode", "notification_remote_input");
            out.put("event_id", eventId);
            out.put("replied_text", text);
            out.put("user_id", TARGET_CLONE_USER_ID);
            return out;
        } catch (PendingIntent.CanceledException e) {
            return error("reply_pending_intent_canceled");
        } catch (Exception e) {
            return error("reply_failed:" + ScreenshotService.shortMsg(e));
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

    private static boolean hasRemoteInput(Notification n) {
        return findReplyAction(n) != null;
    }

    private static JSONObject findEvent(Context ctx, String eventId) {
        JSONArray all = readEvents(ctx);
        String wanted = eventId == null ? "" : eventId.trim();
        for (int i = 0; i < all.length(); i++) {
            JSONObject event = all.optJSONObject(i);
            if (event == null) continue;
            if (wanted.isEmpty() || "LATEST".equalsIgnoreCase(wanted) || wanted.equals(event.optString("id", ""))) {
                return event;
            }
        }
        return null;
    }

    private static void markReplied(Context ctx, String eventId, String replyText) {
        try {
            JSONArray all = readEvents(ctx);
            for (int i = 0; i < all.length(); i++) {
                JSONObject event = all.optJSONObject(i);
                if (event == null) continue;
                if (eventId.equals(event.optString("id", ""))) {
                    event.put("replied", true);
                    event.put("replied_at_ms", System.currentTimeMillis());
                    event.put("reply_text", replyText);
                    break;
                }
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_EVENTS, all.toString()).apply();
        } catch (Exception ignored) {}
    }

    private static JSONArray readEvents(Context ctx) {
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return new JSONArray(p.getString(KEY_EVENTS, "[]"));
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private static String eventId(StatusBarNotification sbn) {
        return Integer.toHexString((sbn.getKey() + ":" + sbn.getPostTime()).hashCode()) + "-" + sbn.getPostTime();
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
            out.put("clone_user_id", TARGET_CLONE_USER_ID);
        } catch (Exception ignored) {}
        return out;
    }
}
