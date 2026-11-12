package dev.linjian.peek;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

public class PostInboxProvider extends ContentProvider {
    public static final String AUTHORITY = "dev.linjian.peek.postinbox";

    public static Uri uriFor(String filename) {
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath(filename)
                .build();
    }

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) {
        String name = uri == null ? "" : uri.getLastPathSegment();
        return getContext() == null ? "application/octet-stream" : PostInboxStore.mimeForName(getContext(), name);
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("read only");
        try {
            String name = uri == null ? "" : uri.getLastPathSegment();
            File f = PostInboxStore.resolveItem(getContext(), name);
            if (f == null) throw new FileNotFoundException("not found");
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (FileNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new FileNotFoundException(String.valueOf(e.getMessage()));
        }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        try {
            String name = uri == null ? "" : uri.getLastPathSegment();
            File f = PostInboxStore.resolveItem(getContext(), name);
            if (f != null) c.addRow(new Object[]{f.getName(), f.length()});
        } catch (Exception ignored) {}
        return c;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("read only"); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("read only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("read only"); }
}
