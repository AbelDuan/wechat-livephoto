package com.abel.wechatlive;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 仅供「导出日志」使用：把模块外部私有存储目录里的日志文件，
 * 通过 content:// 暴露给其它 App（邮件 / 微信文件传输 / WorkBuddy 等）读取。
 *
 * exported=false + grantUriPermissions=true：只有被本次分享授权的 App 能读，安全。
 * 解析路径时做了防目录穿越校验，只能访问 getExternalFilesDir(null) 下的文件。
 */
public class LogFileProvider extends ContentProvider {

    public static final String AUTH = "com.abel.wechatlive.fileprovider";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String sel,
                        String[] selArgs, String sort) {
        File f = resolve(uri);
        if (f == null || !f.exists()) return null;
        String[] cols = (projection == null || projection.length == 0)
                ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
                : projection;
        MatrixCursor c = new MatrixCursor(cols);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
        }
        c.addRow(row);
        return c;
    }

    @Override
    public String getType(Uri uri) {
        File f = resolve(uri);
        if (f == null) return null;
        String n = f.getName().toLowerCase();
        if (n.endsWith(".log") || n.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = resolve(uri);
        if (f == null || !f.exists()) throw new FileNotFoundException(uri.toString());
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode));
    }

    /** 只允许访问外部私有目录下的文件，防目录穿越 */
    private File resolve(Uri uri) {
        String p = uri.getPath();
        if (p == null) return null;
        File root = getContext().getExternalFilesDir(null);
        if (root == null) return null;
        File target = new File(root, p);
        try {
            String rp = root.getCanonicalPath();
            String tp = target.getCanonicalPath();
            if (!tp.equals(rp) && !tp.startsWith(rp + File.separator)) return null;
        } catch (Throwable t) {
            return null;
        }
        return target;
    }

    @Override
    public Uri insert(Uri uri, ContentValues v) {
        return null;
    }

    @Override
    public int delete(Uri uri, String s, String[] a) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues v, String s, String[] a) {
        return 0;
    }
}
