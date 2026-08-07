package com.abel.wechatlive;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * 日志落在模块 App 自己的私有目录（getFilesDir），
 * 完全不碰 /sdcard —— 不需要任何存储权限，也不受 Android 10+ 分区存储影响。
 * 微信侧的日志通过 StatusProvider 跨进程送进来，由本类统一写入。
 */
public final class LogStore {

    private LogStore() {
    }

    public static File file(Context c) {
        return new File(c.getFilesDir(), Const.LOG_FILE);
    }

    public static synchronized void append(Context c, String text) {
        if (text == null || text.length() == 0) return;
        FileOutputStream fos = null;
        try {
            File f = file(c);
            fos = new FileOutputStream(f, true);
            fos.write(text.getBytes("UTF-8"));
            if (!text.endsWith("\n")) fos.write('\n');
            fos.flush();
        } catch (Throwable ignored) {
        } finally {
            close(fos);
        }
        try {
            trim(c);
        } catch (Throwable ignored) {
        }
    }

    /** 超限时保留后半段，避免无限增长 */
    private static void trim(Context c) throws Exception {
        File f = file(c);
        if (!f.exists() || f.length() <= Const.LOG_MAX) return;
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        try {
            long keep = Const.LOG_MAX / 2;
            raf.seek(f.length() - keep);
            raf.readLine(); // 丢弃半行
            byte[] buf = new byte[(int) (f.length() - raf.getFilePointer())];
            raf.readFully(buf);
            FileOutputStream fos = new FileOutputStream(f, false);
            try {
                fos.write("… 日志已自动截断 …\n".getBytes("UTF-8"));
                fos.write(buf);
            } finally {
                close(fos);
            }
        } finally {
            try {
                raf.close();
            } catch (Throwable ignored) {
            }
        }
    }

    public static synchronized void clear(Context c) {
        try {
            File f = file(c);
            if (f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 读取末尾 n 行 */
    public static synchronized List<String> tail(Context c, int n) {
        List<String> all = new ArrayList<String>();
        File f = file(c);
        if (!f.exists()) return all;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(f));
            String ln;
            while ((ln = r.readLine()) != null) {
                all.add(ln);
                if (all.size() > 6000) all.remove(0);
            }
        } catch (Throwable t) {
            all.add("[读取日志失败] " + t);
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable ignored) {
                }
            }
        }
        if (all.size() <= n) return all;
        return new ArrayList<String>(all.subList(all.size() - n, all.size()));
    }

    /** 读取整个日志文件内容（用于导出）。文件不存在返回空串。 */
    public static synchronized String readFully(Context c) {
        File f = file(c);
        if (!f.exists()) return "";
        java.io.FileInputStream fis = null;
        try {
            fis = new java.io.FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            int n = fis.read(buf);
            return new String(buf, 0, n < 0 ? 0 : n, "UTF-8");
        } catch (Throwable t) {
            return "[读取失败] " + t;
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void close(FileOutputStream fos) {
        if (fos != null) {
            try {
                fos.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
