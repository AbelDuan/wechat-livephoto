package com.abel.wechatlive;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * 模块 App ↔ 微信进程 的双向通道。
 *
 * 为什么用 ContentProvider 而不是 XSharedPreferences / /sdcard 文件：
 *  - LSPosed 的应用列表会过滤掉模块自身，所以「把模块勾进自己的作用域」根本做不到，
 *    传统的 isModuleActive() 自 hook 自检方案在 LSPosed 上不可用；
 *  - /sdcard 在 Android 10+ 分区存储下，微信写的文件模块 App 读不到，反之亦然；
 *  - ContentProvider 走 Binder，不需要任何存储权限，全版本可用。
 *
 * 一次 call(report) 同时完成两件事：
 *  上行：微信侧把「我还活着 + Activity 名 + 日志」送过来；
 *  下行：把用户在 App 里勾的开关回给微信侧。
 * 省一次 IPC，也保证开关能实时生效。
 */
public class StatusProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Context ctx = getContext();
        if (ctx == null) return null;
        SharedPreferences sp = ctx.getSharedPreferences(Const.PREFS, Context.MODE_PRIVATE);

        if (Const.METHOD_REPORT.equals(method)) {
            try {
                SharedPreferences.Editor e = sp.edit();
                e.putLong(Const.K_LAST_SEEN, System.currentTimeMillis());
                e.putInt(Const.K_HITS, sp.getInt(Const.K_HITS, 0) + 1);
                if (extras != null) {
                    String act = extras.getString(Const.K_LAST_ACT);
                    if (act != null) e.putString(Const.K_LAST_ACT, act);
                    String ver = extras.getString(Const.K_WX_VER);
                    if (ver != null) e.putString(Const.K_WX_VER, ver);
                }
                e.apply();
                if (extras != null) {
                    String log = extras.getString(Const.KEY_LOG);
                    if (log != null) LogStore.append(ctx, log);
                }
            } catch (Throwable ignored) {
            }
        }

        // 无论 report 还是 ping，都把当前开关回给调用方
        Bundle out = new Bundle();
        out.putBoolean(Const.K_ENABLED, sp.getBoolean(Const.K_ENABLED, true));
        out.putBoolean(Const.K_LIVE, sp.getBoolean(Const.K_LIVE, true));
        out.putBoolean(Const.K_ORIG, sp.getBoolean(Const.K_ORIG, true));
        out.putBoolean(Const.K_VERBOSE, sp.getBoolean(Const.K_VERBOSE, true));
        out.putBoolean("ok", true);
        return out;
    }

    // ── 以下接口用不到，返回空实现即可 ──

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
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
