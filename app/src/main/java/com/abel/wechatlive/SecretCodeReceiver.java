package com.abel.wechatlive;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ComponentName;
import android.content.pm.PackageManager;

/**
 * 隐藏桌面图标后的「找回」入口。
 *
 * 隐藏图标 = 禁用 LauncherEntry 这个 activity-alias（见 AndroidManifest.xml）。
 * 一旦桌面图标消失，普通点击进不来，本接收器负责在拨号盘输入
 *     *#*#7356#*#*
 * 时：① 重新启用 LauncherEntry；② 直接拉起 MainActivity。
 *
 * 不需要任何权限：SECRET_CODE 是系统发出的受保护广播，声明了 data host=7356
 * 的接收器即可收到（各大厂商的 *#*# 工程码都走这套机制）。
 */
public class SecretCodeReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (!"android.provider.Telephony.SECRET_CODE".equals(intent.getAction())) return;
        android.net.Uri u = intent.getData();
        if (u == null || !Const.SECRET_CODE.equals(u.getHost())) return;

        try {
            PackageManager pm = context.getPackageManager();
            ComponentName cn = new ComponentName(context, Const.LAUNCHER_ALIAS);
            pm.setComponentEnabledSetting(cn,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
        } catch (Throwable ignored) {
        }

        try {
            Intent i = new Intent(context, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } catch (Throwable ignored) {
        }
    }
}
