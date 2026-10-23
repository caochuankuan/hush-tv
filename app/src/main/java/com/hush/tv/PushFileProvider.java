package com.hush.tv;

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
import java.io.IOException;

/** Serves received files to external viewers without exposing a file:// path. */
public final class PushFileProvider extends ContentProvider {

    public static final String AUTHORITY = "com.hush.tv.pushfiles";

    public static Uri uriFor(File file) {
        return Uri.parse("content://" + AUTHORITY + "/" + Uri.encode(file.getName()));
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File file = resolve(uri);
        if (file == null || !file.isFile()) {
            return null;
        }
        String[] columns = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) {
                row[i] = file.getName();
            } else if (OpenableColumns.SIZE.equals(columns[i])) {
                row[i] = file.length();
            } else {
                row[i] = null;
            }
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name == null) {
            return "application/octet-stream";
        }
        String extension = MimeTypeMap.getFileExtensionFromUrl(Uri.encode(name));
        if (extension == null || extension.isEmpty()) {
            int dot = name.lastIndexOf('.');
            extension = dot >= 0 ? name.substring(dot + 1) : "";
        }
        String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                extension.toLowerCase());
        return type == null ? "application/octet-stream" : type;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode != null && !mode.contains("r")) {
            throw new FileNotFoundException("read-only provider");
        }
        File file = resolve(uri);
        if (file == null || !file.isFile()) {
            throw new FileNotFoundException(String.valueOf(uri));
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    private File resolve(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name == null || name.isEmpty()) {
            return null;
        }
        try {
            File base = RemotePushServer.getPushDir(getContext()).getCanonicalFile();
            File file = new File(base, name).getCanonicalFile();
            if (!file.getPath().startsWith(base.getPath() + File.separator)) {
                return null;
            }
            return file;
        } catch (IOException e) {
            return null;
        }
    }
}
