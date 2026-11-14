package dev.linjian.peek;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SystemUiWeChatBridge {
    private static final String SYSTEMUI = "com.android.systemui";
    private static final String CLONE_MARKER = "克隆";
    private static final long DEDUPE_MS = 2500L;
    private static final Map<String, Long> RECENT = new HashMap<>();
    private static final Map<String, EventMeta> EVENTS = new HashMap<>();
    private static volatile String latestEventId = "";

    private SystemUiWeChatBridge() {}

    private static final class EventMeta {
        String id;
        String sender;
        String text;
        long when;
    }

    public static void capture(Context ctx, AccessibilityEvent event) {
        if (ctx == null || event == null) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !SYSTEMUI.contentEquals(pkg)) return;

        try {
            ScreenshotService svc = ScreenshotService.getInstance();
            if (svc == null) return;
            NotificationCard card = findCloneCard(svc, "");
            if (card == null || card.message.isEmpty()) return;
            emit(ctx, card.sender, card.message);
            recycle(card);
        } catch (Exception ignored) {
        }
    }

    private static void emit(Context ctx, String sender, String message) {
        String cleanSender = clean(sender);
        String cleanMessage = clean(message);
        if (cleanMessage.isEmpty()) return;
        String dedupeKey = cleanSender + "\n" + cleanMessage;
        long now = System.currentTimeMillis();
        synchronized (RECENT) {
            Long old = RECENT.get(dedupeKey);
            if (old != null && now - old < DEDUPE_MS) return;
            RECENT.put(dedupeKey, now);
            if (RECENT.size() > 40) {
                List<String> stale = new ArrayList<>();
                for (Map.Entry<String, Long> e : RECENT.entrySet()) if (now - e.getValue() > 30000L) stale.add(e.getKey());
                for (String k : stale) RECENT.remove(k);
            }
        }

        String eventId = Integer.toHexString((dedupeKey + ":" + now).hashCode()) + "-" + now;
        EventMeta meta = new EventMeta();
        meta.id = eventId;
        meta.sender = cleanSender;
        meta.text = cleanMessage;
        meta.when = now;
        synchronized (EVENTS) {
            EVENTS.put(eventId, meta);
            latestEventId = eventId;
            if (EVENTS.size() > 50) {
                String oldest = null; long oldestWhen = Long.MAX_VALUE;
                for (EventMeta e : EVENTS.values()) {
                    if (e.when < oldestWhen) { oldestWhen = e.when; oldest = e.id; }
                }
                if (oldest != null) EVENTS.remove(oldest);
            }
        }

        new Thread(() -> upload(ctx.getApplicationContext(), meta), "SystemUiWechatUpload").start();
    }

    private static void upload(Context ctx, EventMeta meta) {
        try {
            String server = ScreenshotService.normalizeUrl(AppPrefs.server(ctx));
            String token = AppPrefs.token(ctx);
            if (server == null || server.isEmpty() || token == null || token.isEmpty()) return;

            JSONObject body = new JSONObject();
            body.put("id", meta.id);
            body.put("device_id", AppPrefs.device(ctx));
            body.put("user_id", 999);
            body.put("sender", meta.sender);
            body.put("text", meta.text);
            body.put("notification_key", "systemui:" + meta.id);
            body.put("posted_at_ms", meta.when);
            body.put("can_inline_reply", true);

            HttpURLConnection c = (HttpURLConnection) new URL(server + "/api/wechat-bridge/event-from-device").openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setDoOutput(true);
            c.setRequestProperty("X-Auth-Token", token);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = c.getOutputStream()) { os.write(data); }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in != null) { byte[] buf = new byte[512]; while (in.read(buf) > 0) {} in.close(); }
            c.disconnect();
            if (code >= 200 && code < 300) {
                DebugState.append(ctx, "SystemUI抓到双开微信：" + meta.sender + " · " + clip(meta.text));
            } else {
                DebugState.append(ctx, "SystemUI双开微信上传失败：HTTP " + code);
            }
        } catch (Exception e) {
            DebugState.append(ctx, "SystemUI双开微信上传异常：" + ScreenshotService.shortMsg(e));
        }
    }

    public static JSONObject reply(Context ctx, String eventId, String text) {
        JSONObject out = new JSONObject();
        try {
            ScreenshotService svc = ScreenshotService.getInstance();
            if (svc == null) return error("accessibility_service_not_ready");
            String value = text == null ? "" : text.trim();
            if (value.isEmpty()) return error("empty_reply_text");

            String wanted = eventId == null ? "" : eventId.trim();
            if (wanted.isEmpty() || "LATEST".equalsIgnoreCase(wanted)) wanted = latestEventId;
            EventMeta meta;
            synchronized (EVENTS) { meta = EVENTS.get(wanted); }
            if (meta == null) return error("systemui_event_not_found");

            if (!svc.doNotifications()) return error("open_notification_shade_failed");
            sleep(700);

            NotificationCard card = findCloneCard(svc, meta.sender);
            if (card == null) { svc.doBack(); return error("clone_notification_card_not_found"); }

            if (card.expandNode != null) {
                card.expandNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                sleep(450);
                recycle(card);
                card = findCloneCard(svc, meta.sender);
            }

            if (card == null) { svc.doBack(); return error("clone_notification_card_lost"); }
            AccessibilityNodeInfo replyButton = findTextIn(card.root, "回复", true);
            if (replyButton == null) {
                svc.doBack();
                recycle(card);
                return error("systemui_reply_button_not_found");
            }
            clickSelfOrParent(replyButton);
            sleep(450);

            AccessibilityNodeInfo editable = findEditableInSystemUi(svc);
            if (editable == null) {
                svc.doBack();
                recycle(card);
                return error("systemui_reply_editor_not_found");
            }
            Bundle b = new Bundle();
            b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
            boolean wrote = editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
            if (!wrote) {
                svc.doBack();
                recycle(card);
                return error("systemui_reply_set_text_failed");
            }
            sleep(300);

            AccessibilityNodeInfo send = findTextAcrossSystemUi(svc, "发送");
            if (send == null) send = findTextAcrossSystemUi(svc, "Send");
            if (send == null) {
                svc.doBack();
                recycle(card);
                return error("systemui_reply_send_button_not_found");
            }
            boolean sent = clickSelfOrParent(send);
            sleep(250);
            svc.doBack();
            recycle(card);

            out.put("ok", sent);
            out.put("mode", "systemui_clone_notification_reply");
            out.put("event_id", wanted);
            out.put("sender", meta.sender);
            out.put("replied_text", value);
            out.put("user_id", 999);
            if (!sent) out.put("error", "systemui_send_click_failed");
            return out;
        } catch (Exception e) {
            try {
                ScreenshotService svc = ScreenshotService.getInstance();
                if (svc != null) svc.doBack();
            } catch (Exception ignored) {}
            return error("systemui_reply_failed:" + ScreenshotService.shortMsg(e));
        }
    }

    private static final class NotificationCard {
        AccessibilityNodeInfo root;
        AccessibilityNodeInfo expandNode;
        String sender = "";
        String message = "";
    }

    private static NotificationCard findCloneCardFromSource(AccessibilityNodeInfo source) {
        if (source == null) return null;
        AccessibilityNodeInfo cur = AccessibilityNodeInfo.obtain(source);
        for (int up = 0; cur != null && up < 8; up++) {
            String all = collectText(cur, 0, new StringBuilder()).toString();
            if (all.contains(CLONE_MARKER)) {
                NotificationCard card = parseCard(AccessibilityNodeInfo.obtain(cur));
                try { cur.recycle(); } catch (Exception ignored) {}
                return card;
            }
            AccessibilityNodeInfo parent = cur.getParent();
            try { cur.recycle(); } catch (Exception ignored) {}
            cur = parent;
        }
        return null;
    }

    private static NotificationCard findCloneCard(ScreenshotService svc, String sender) {
        List<AccessibilityNodeInfo> roots = systemUiRoots(svc);
        try {
            for (AccessibilityNodeInfo root : roots) {
                AccessibilityNodeInfo marker = findCloneMarker(root, 0);
                while (marker != null) {
                    NotificationCard card = cardFromMarker(marker, sender);
                    if (card != null) {
                        try { marker.recycle(); } catch (Exception ignored) {}
                        return card;
                    }
                    AccessibilityNodeInfo next = findNextCloneMarker(root, marker);
                    try { marker.recycle(); } catch (Exception ignored) {}
                    marker = next;
                }
            }
        } finally {
            for (AccessibilityNodeInfo r : roots) try { r.recycle(); } catch (Exception ignored) {}
        }
        return null;
    }

    private static AccessibilityNodeInfo findCloneMarker(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 16) return null;
        if (CLONE_MARKER.equals(nodeText(node))) return AccessibilityNodeInfo.obtain(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            AccessibilityNodeInfo hit = findCloneMarker(c, depth + 1);
            try { if (c != null) c.recycle(); } catch (Exception ignored) {}
            if (hit != null) return hit;
        }
        return null;
    }

    private static AccessibilityNodeInfo findNextCloneMarker(AccessibilityNodeInfo root, AccessibilityNodeInfo previous) {
        final int prevWindow = previous == null ? -1 : previous.getWindowId();
        final android.graphics.Rect prevRect = new android.graphics.Rect();
        if (previous != null) previous.getBoundsInScreen(prevRect);
        return findCloneMarkerAfter(root, 0, prevWindow, prevRect.top, prevRect.left);
    }

    private static AccessibilityNodeInfo findCloneMarkerAfter(AccessibilityNodeInfo node, int depth, int windowId, int top, int left) {
        if (node == null || depth > 16) return null;
        if (CLONE_MARKER.equals(nodeText(node))) {
            android.graphics.Rect r = new android.graphics.Rect();
            node.getBoundsInScreen(r);
            boolean after = node.getWindowId() != windowId || r.top > top || (r.top == top && r.left > left);
            if (after) return AccessibilityNodeInfo.obtain(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            AccessibilityNodeInfo hit = findCloneMarkerAfter(c, depth + 1, windowId, top, left);
            try { if (c != null) c.recycle(); } catch (Exception ignored) {}
            if (hit != null) return hit;
        }
        return null;
    }

    private static NotificationCard cardFromMarker(AccessibilityNodeInfo marker, String wantedSender) {
        if (marker == null) return null;
        AccessibilityNodeInfo cur = AccessibilityNodeInfo.obtain(marker);
        for (int up = 0; cur != null && up < 7; up++) {
            List<String> texts = new ArrayList<>();
            collectStrings(cur, texts, 0);
            String sender = "";
            String message = "";
            boolean hasExpand = false;
            for (String t : texts) {
                if ("展开".equals(t)) hasExpand = true;
                if (t.matches("^\\[\\d+条\\].+?:\\s*.*$") || t.matches("^.+?:\\s*.+$")) {
                    message = t;
                    String body = t.replaceFirst("^\\[\\d+条\\]", "");
                    int colon = body.indexOf(":");
                    if (colon > 0) sender = body.substring(0, colon).trim();
                }
            }
            boolean senderOk = wantedSender == null || wantedSender.isEmpty()
                    || sender.equals(wantedSender) || collectText(cur, 0, new StringBuilder()).toString().contains(wantedSender);
            if (hasExpand && !message.isEmpty() && senderOk) {
                NotificationCard card = parseCard(AccessibilityNodeInfo.obtain(cur));
                if (!sender.isEmpty()) card.sender = sender;
                card.message = message;
                try { cur.recycle(); } catch (Exception ignored) {}
                return card;
            }
            AccessibilityNodeInfo p = cur.getParent();
            try { cur.recycle(); } catch (Exception ignored) {}
            cur = p;
        }
        return null;
    }

    private static NotificationCard parseCard(AccessibilityNodeInfo root) {
        NotificationCard card = new NotificationCard();
        card.root = root;
        List<String> texts = new ArrayList<>();
        collectStrings(root, texts, 0);
        for (String s : texts) {
            if (s == null || s.isEmpty() || CLONE_MARKER.equals(s) || "展开".equals(s) || "回复".equals(s)) continue;
            if (s.matches(".*\\d+\\s*(分钟|小时|秒)前.*") || "现在".equals(s) || "•".equals(s)) continue;
            if (card.sender.isEmpty() && !s.startsWith("[") && !s.contains(":")) card.sender = s;
            if (s.contains(":") || s.startsWith("[")) card.message = s;
        }
        if (card.message.isEmpty() && texts.size() > 0) {
            for (int i = texts.size() - 1; i >= 0; i--) {
                String s = texts.get(i);
                if (!s.equals(CLONE_MARKER) && !s.equals("展开") && !s.equals("•")) { card.message = s; break; }
            }
        }
        card.expandNode = findTextIn(root, "展开", true);
        return card;
    }

    private static List<AccessibilityNodeInfo> systemUiRoots(ScreenshotService svc) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        try {
            List<AccessibilityWindowInfo> windows = svc.getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo w : windows) {
                    if (w == null) continue;
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r == null) continue;
                    CharSequence p = r.getPackageName();
                    if (p != null && SYSTEMUI.contentEquals(p)) out.add(r); else r.recycle();
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static AccessibilityNodeInfo findEditableInSystemUi(ScreenshotService svc) {
        List<AccessibilityNodeInfo> roots = systemUiRoots(svc);
        try {
            for (AccessibilityNodeInfo r : roots) {
                AccessibilityNodeInfo hit = findEditable(r, 0);
                if (hit != null) return hit;
            }
        } finally {
            for (AccessibilityNodeInfo r : roots) try { r.recycle(); } catch (Exception ignored) {}
        }
        return null;
    }

    private static AccessibilityNodeInfo findEditable(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 14) return null;
        if (node.isEditable()) return AccessibilityNodeInfo.obtain(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            AccessibilityNodeInfo hit = findEditable(c, depth + 1);
            try { if (c != null) c.recycle(); } catch (Exception ignored) {}
            if (hit != null) return hit;
        }
        return null;
    }

    private static AccessibilityNodeInfo findTextAcrossSystemUi(ScreenshotService svc, String query) {
        List<AccessibilityNodeInfo> roots = systemUiRoots(svc);
        try {
            for (AccessibilityNodeInfo r : roots) {
                AccessibilityNodeInfo hit = findTextIn(r, query, true);
                if (hit != null) return hit;
            }
        } finally {
            for (AccessibilityNodeInfo r : roots) try { r.recycle(); } catch (Exception ignored) {}
        }
        return null;
    }

    private static AccessibilityNodeInfo findTextIn(AccessibilityNodeInfo node, String query, boolean exact) {
        if (node == null) return null;
        String v = nodeText(node);
        if ((!exact && v.contains(query)) || (exact && v.equals(query))) return AccessibilityNodeInfo.obtain(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            AccessibilityNodeInfo hit = findTextIn(c, query, exact);
            try { if (c != null) c.recycle(); } catch (Exception ignored) {}
            if (hit != null) return hit;
        }
        return null;
    }

    private static boolean clickSelfOrParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = node;
        for (int i = 0; cur != null && i < 7; i++) {
            if (cur.isClickable() && cur.isEnabled()) return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            AccessibilityNodeInfo p = cur.getParent();
            if (cur != node) try { cur.recycle(); } catch (Exception ignored) {}
            cur = p;
        }
        return node != null && node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private static void collectStrings(AccessibilityNodeInfo node, List<String> out, int depth) {
        if (node == null || depth > 12 || out.size() > 80) return;
        String v = nodeText(node);
        if (!v.isEmpty()) out.add(v);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            collectStrings(c, out, depth + 1);
            try { if (c != null) c.recycle(); } catch (Exception ignored) {}
        }
    }

    private static StringBuilder collectText(AccessibilityNodeInfo node, int depth, StringBuilder sb) {
        if (node == null || depth > 10 || sb.length() > 1200) return sb;
        String v = nodeText(node);
        if (!v.isEmpty()) sb.append(v).append(" | ");
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            collectText(c, depth + 1, sb);
            try { if (c != null) c.recycle(); } catch (Exception ignored) {}
        }
        return sb;
    }

    private static String nodeText(AccessibilityNodeInfo node) {
        if (node == null) return "";
        CharSequence t = node.getText();
        if (t != null && t.length() > 0) return t.toString().trim();
        CharSequence d = node.getContentDescription();
        return d == null ? "" : d.toString().trim();
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim();
    }

    private static String clip(String s) {
        if (s == null) return "";
        return s.length() <= 100 ? s : s.substring(0, 100) + "…";
    }

    private static void recycle(NotificationCard card) {
        if (card == null) return;
        try { if (card.expandNode != null) card.expandNode.recycle(); } catch (Exception ignored) {}
        try { if (card.root != null) card.root.recycle(); } catch (Exception ignored) {}
    }

    private static JSONObject error(String message) {
        JSONObject out = new JSONObject();
        try {
            out.put("ok", false);
            out.put("error", message);
            out.put("mode", "systemui_clone_notification_reply");
            out.put("user_id", 999);
        } catch (Exception ignored) {}
        return out;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }
}
