package com.litereader.app.data;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Locale;

/**
 * Secure ContentProvider that serves temporarily decrypted books from `cache/share_export/`
 * ONLY when the user explicitly clicks "分享 (Share)" on a book in LiteReader.
 */
public class BookShareProvider extends ContentProvider {

    public static final String AUTHORITY = "com.litereader.offline.shareprovider";

    public static Uri getShareUriForFile(File decryptedShareFile) {
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath(decryptedShareFile.getName())
                .build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    private File resolveShareFile(Uri uri) throws FileNotFoundException {
        if (uri == null || getContext() == null) {
            throw new FileNotFoundException("Invalid URI");
        }
        String fileName = uri.getLastPathSegment();
        if (fileName == null || fileName.contains("..") || fileName.contains("/")) {
            throw new FileNotFoundException("Invalid filename");
        }
        File shareDir = new File(getContext().getCacheDir(), "share_export");
        File target = new File(shareDir, fileName);
        if (!target.exists()) {
            throw new FileNotFoundException("Shared file not found: " + fileName);
        }
        return target;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        try {
            File file = resolveShareFile(uri);
            String[] cols = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
            MatrixCursor cursor = new MatrixCursor(cols, 1);
            Object[] row = new Object[cols.length];
            for (int i = 0; i < cols.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) {
                    row[i] = file.getName();
                } else if (OpenableColumns.SIZE.equals(cols[i])) {
                    row[i] = file.length();
                } else {
                    row[i] = null;
                }
            }
            cursor.addRow(row);
            return cursor;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String getType(Uri uri) {
        String name = uri != null ? uri.getLastPathSegment() : "";
        if (name == null) return "application/octet-stream";
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".epub")) return "application/epub+zip";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".mobi") || lower.endsWith(".prc")) return "application/x-mobipocket-ebook";
        if (lower.endsWith(".azw3")) return "application/vnd.amazon.ebook";
        if (lower.endsWith(".fb2")) return "application/x-fictionbook+xml";
        if (lower.endsWith(".fb2.zip") || lower.endsWith(".fbz")) return "application/zip";
        if (lower.endsWith(".txt")) return "text/plain";
        int dot = lower.lastIndexOf('.');
        if (dot >= 0) {
            String ext = lower.substring(dot + 1);
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime != null) return mime;
        }
        return "application/octet-stream";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = resolveShareFile(uri);
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
