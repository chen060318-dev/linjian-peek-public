package dev.linjian.peek;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

public final class PostInboxStore {
    private static final String DIR_NAME = "post_inbox";
    private static final String META_PREFS = "post_inbox_meta";
    private static final int MAX_ITEMS = 40;
    private static final long MAX_ITEM_BYTES = 512L * 1024L * 1024L;

    private PostInboxStore() {}

    public static JSONObject handleCommand(Context ctx, JSONObject cmd) {
        try {
            String action = cmd.optString("action", "");
            if ("list_post_inbox".equals(action)) {
                return new JSONObject().put("ok", true).put("items", listItems(ctx));
            }
            if ("share_post_inbox".equals(action)) {
                String filename = cmd.optString("filename", "");
                String caption = cmd.optString("caption", "");
                boolean chooser = cmd.optBoolean("chooser", true);
                String targetPackage = cmd.optString("package", "");
                return shareItem(ctx, filename, caption, chooser, targetPackage);
            }
            return new JSONObject().put("ok", false).put("result", "unsupported_post_inbox_action:" + action);
        } catch (Exception e) {
            JSONObject error = new JSONObject();
            try {
                error.put("ok", false);
                error.put("result", "post_inbox_error:" + e.getClass().getSimpleName() + ":" + String.valueOf(e.getMessage()));
            } catch (Exception ignored) { }
            return error;
        }
    }

    public static JSONObject receiveIntent(Context ctx, Intent intent) {
        JSONArray saved = new JSONArray();
        JSONArray errors = new JSONArray();
        try {
            String action = intent == null ? "" : intent.getAction();
            if (Intent.ACTION_SEND.equals(action)) {
                Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (uri != null) {
                    try { saved.put(copyIntoInbox(ctx, uri)); }
                    catch (Exception e) { errors.put(String.valueOf(e.getMessage())); }
                }
            } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
                ArrayList<Uri> uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (uris != null) {
                    for (Uri uri : uris) {
                        if (uri == null) continue;
                        try { saved.put(copyIntoInbox(ctx, uri)); }
                        catch (Exception e) { errors.put(String.valueOf(e.getMessage())); }
                    }
                }
            }
        } catch (Exception e) {
            errors.put(String.valueOf(e.getMessage()));
        }
        cleanup(ctx);
        try {
            return new JSONObject().put("ok", saved.length() > 0).put("saved", saved).put("errors", errors);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static JSONObject copyIntoInbox(Context ctx, Uri source) throws Exception {
        ContentResolver resolver = ctx.getContentResolver();
        String displayName = queryDisplayName(resolver, source);
        String mime = resolver.getType(source);
        if (mime == null || mime.trim().isEmpty()) mime = mimeFromName(displayName);
        if (mime == null || mime.trim().isEmpty()) mime = "application/octet-stream";
        if (!(mime.startsWith("image/") || mime.startsWith("video/"))) {
            throw new IllegalArgumentException("仅支持图片或视频素材");
        }

        String safe = sanitizeName(displayName);
        if (safe.isEmpty()) safe = mime.startsWith("video/") ? "video.mp4" : "image.jpg";
        String filename = System.currentTimeMillis() + "_" + safe;
        File dir = inboxDir(ctx);
        File out = new File(dir, filename);

        long total = 0L;
        try (InputStream in = resolver.openInputStream(source); OutputStream os = new FileOutputStream(out)) {
            if (in == null) throw new IllegalStateException("无法读取分享素材");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n == 0) continue;
                total += n;
                if (total > MAX_ITEM_BYTES) throw new IllegalArgumentException("素材超过 512MB 限制");
                os.write(buf, 0, n);
            }
        } catch (Exception e) {
            try { out.delete(); } catch (Exception ignored) {}
            throw e;
        }

        SharedPreferences sp = ctx.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE);
        sp.edit().putString("mime:" + filename, mime).apply();
        return new JSONObject()
                .put("filename", filename)
                .put("display_name", safe)
                .put("mime", mime)
                .put("size_bytes", total)
                .put("added_at_ms", out.lastModified());
    }

    public static JSONArray listItems(Context ctx) throws Exception {
        JSONArray arr = new JSONArray();
        File[] files = inboxDir(ctx).listFiles(file -> file != null && file.isFile());
        if (files == null) return arr;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        SharedPreferences sp = ctx.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE);
        for (File f : files) {
            String mime = sp.getString("mime:" + f.getName(), mimeFromName(f.getName()));
            arr.put(new JSONObject()
                    .put("filename", f.getName())
                    .put("mime", mime == null ? "application/octet-stream" : mime)
                    .put("size_bytes", f.length())
                    .put("added_at_ms", f.lastModified()));
        }
        return arr;
    }

    public static JSONObject shareItem(Context ctx, String filename, String caption, boolean chooser, String targetPackage) throws Exception {
        File file = resolveItem(ctx, filename);
        if (file == null) return new JSONObject().put("ok", false).put("result", "post_inbox_empty_or_item_not_found");
        String mime = ctx.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
                .getString("mime:" + file.getName(), mimeFromName(file.getName()));
        if (mime == null || mime.isEmpty()) mime = "application/octet-stream";

        Uri uri = PostInboxProvider.uriFor(file.getName());
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(mime);
        send.putExtra(Intent.EXTRA_STREAM, uri);
        if (caption != null && !caption.trim().isEmpty()) send.putExtra(Intent.EXTRA_TEXT, caption);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        Intent launch;
        if (!chooser && targetPackage != null && !targetPackage.trim().isEmpty()) {
            send.setPackage(targetPackage.trim());
            launch = send;
        } else {
            launch = Intent.createChooser(send, "选择发布应用");
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }

        ctx.startActivity(launch);
        return new JSONObject()
                .put("ok", true)
                .put("result", chooser ? "share_chooser_opened" : "share_target_opened")
                .put("filename", file.getName())
                .put("mime", mime)
                .put("chooser", chooser)
                .put("package", targetPackage == null ? "" : targetPackage)
                .put("caption_included", caption != null && !caption.trim().isEmpty());
    }

    public static File resolveItem(Context ctx, String filename) throws Exception {
        File dir = inboxDir(ctx);
        if (filename == null || filename.trim().isEmpty() || "LATEST".equalsIgnoreCase(filename.trim())) {
            File[] files = dir.listFiles(file -> file != null && file.isFile());
            if (files == null || files.length == 0) return null;
            Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
            return files[0];
        }
        File f = new File(dir, filename);
        String base = dir.getCanonicalPath() + File.separator;
        if (!f.getCanonicalPath().startsWith(base)) throw new SecurityException("非法素材路径");
        return f.isFile() ? f : null;
    }

    public static String mimeForName(Context ctx, String filename) {
        String mime = ctx.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
                .getString("mime:" + filename, null);
        if (mime != null && !mime.isEmpty()) return mime;
        mime = mimeFromName(filename);
        return mime == null ? "application/octet-stream" : mime;
    }

    private static File inboxDir(Context ctx) {
        File dir = new File(ctx.getFilesDir(), DIR_NAME);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static void cleanup(Context ctx) {
        try {
            File[] files = inboxDir(ctx).listFiles(file -> file != null && file.isFile());
            if (files == null || files.length <= MAX_ITEMS) return;
            Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
            SharedPreferences.Editor ed = ctx.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE).edit();
            for (int i = MAX_ITEMS; i < files.length; i++) {
                ed.remove("mime:" + files[i].getName());
                files[i].delete();
            }
            ed.apply();
        } catch (Exception ignored) {}
    }

    private static String queryDisplayName(ContentResolver resolver, Uri uri) {
        try (Cursor c = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String name = c.getString(idx);
                    if (name != null && !name.trim().isEmpty()) return name;
                }
            }
        } catch (Exception ignored) {}
        String last = uri.getLastPathSegment();
        return last == null ? "" : last;
    }

    private static String sanitizeName(String name) {
        if (name == null) return "";
        String s = name.replaceAll("[\\\\/:*?\"<>|\\r\\n]+", "_").trim();
        if (s.length() > 120) s = s.substring(s.length() - 120);
        return s;
    }

    private static String mimeFromName(String name) {
        if (name == null) return null;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot >= name.length() - 1) return null;
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
    }
}
