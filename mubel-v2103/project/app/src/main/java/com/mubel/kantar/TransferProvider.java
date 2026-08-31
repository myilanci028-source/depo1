package com.mubel.kantar;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

public class TransferProvider extends ContentProvider {
    private static final String MIME = "application/vnd.mubel.kantar-transfer";

    @Override
    public boolean onCreate() {
        return true;
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        if (getContext() == null) throw new FileNotFoundException("Context yok");
        String name = uri == null ? null : uri.getLastPathSegment();
        if (name == null || name.trim().isEmpty()) throw new FileNotFoundException("Dosya adı yok");
        name = Uri.decode(name);
        if (!name.matches("[A-Za-z0-9._-]+\\.mubel")) throw new FileNotFoundException("Geçersiz dosya adı");
        File dir = new File(getContext().getCacheDir(), "mubelshare");
        File file = new File(dir, name);
        if (!file.exists() || !file.isFile()) throw new FileNotFoundException("Aktarım dosyası bulunamadı");
        return file;
    }

    @Override
    public String getType(Uri uri) {
        return MIME;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode != null && mode.contains("w")) throw new FileNotFoundException("Salt okunur");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        try {
            File file = resolve(uri);
            String[] cols = projection == null
                    ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
                    : projection;
            MatrixCursor c = new MatrixCursor(cols, 1);
            MatrixCursor.RowBuilder row = c.newRow();
            for (String col : cols) {
                if (OpenableColumns.DISPLAY_NAME.equals(col)) row.add(file.getName());
                else if (OpenableColumns.SIZE.equals(col)) row.add(file.length());
                else row.add(null);
            }
            return c;
        } catch (Exception e) {
            return new MatrixCursor(projection == null
                    ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
                    : projection, 0);
        }
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Salt okunur"); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
