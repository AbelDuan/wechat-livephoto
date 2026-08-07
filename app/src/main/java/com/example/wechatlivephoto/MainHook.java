package com.example.wechatlivephoto;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 微信「发送图片默认实况 + 原图」- LSPosed 模块（源头强制版）
 *
 * ── 思路升级 ───────────────────────────────────────────────
 * 不再依赖「点击 UI 开关」（GKD / performClick），而是直接在微信进程内
 * 把控制「实况(LivePhoto)」「原图」的内部 boolean 字段强制为 true。
 * 源头就是开 → 在缩略图网格里多选、直接发送，所有照片天然是实况 + 原图，
 * 无需逐张点、批量选择也全覆盖，且根本不存在循环/重开/滑动那类问题。
 *
 * ── 两种运行模式 ──────────────────────────────────────────
 * 1) PROBE = true（默认）：打开相册时把微信活动里所有 boolean 字段名+值
 *    打到 Xposed 日志（tag: WCLP probe）。把日志发给我，我据此认出
 *    实况 / 原图 对应的混淆字段名，写进 FORCE_FIELDS。
 * 2) DO_FORCE = true + FORCE_FIELDS 填好字段名：把这些字段强制 true，
 *    实现「全部默认开」。
 *
 * CLICK_FALLBACK：保留旧的点按兜底（vid=uzc / 文字"实况"），默认关闭。
 * 仅在你想要「界面上也点一下勾上」时设为 true。
 */
public class MainHook implements IXposedHookLoadPackage {

    // ===== 开关 =====
    private static final boolean PROBE = true;            // 探测模式：打印字段
    private static final boolean DO_FORCE = false;        // 强制模式：写死字段后开
    private static final boolean CLICK_FALLBACK = false;  // 点击兜底（界面勾选）

    // 强制为 true 的字段名（混淆后的真名，先用 PROBE 拿到再填）
    private static final Set<String> FORCE_FIELDS = new HashSet<>();

    // ===== 目标 Activity（微信版本不同类名可能微调，用 GKD 快照 activityId 核对）=====
    private static final String WECHAT_PKG = "com.tencent.mm";
    private static final String[] GALLERY_ACTIVITIES = {
            "com.tencent.mm.plugin.gallery.ui.GalleryUI",        // 缩略图多选网格
            "com.tencent.mm.plugin.gallery.ui.AlbumUI",          // 相册网格（备选）
            "com.tencent.mm.plugin.gallery.ui.AlbumPreviewUI",   // 单图预览
            "com.tencent.mm.plugin.gallery.ui.ImagePreviewUI",   // 单图预览（备选）
    };

    // 点击兜底用（仅 CLICK_FALLBACK=true 时生效）
    private static final String LIVE_VID = "uzc";
    private static final String LIVE_TEXT = "实况";

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!WECHAT_PKG.equals(lpparam.packageName)) return;

        for (String act : GALLERY_ACTIVITIES) {
            hookLifecycle(act, lpparam.classLoader);
        }
    }

    private void hookLifecycle(String className, ClassLoader cl) {
        // onResume：每次进入/重开/左右滑动切换都触发
        try {
            XposedHelpers.findAndHookMethod(className, cl, "onResume",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            onGallery(param.thisObject);
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("WCLP: hook onResume " + className + " failed: " + t);
        }
        // onCreate：更早一步，进入即设默认值
        try {
            XposedHelpers.findAndHookMethod(className, cl, "onCreate",
                    android.os.Bundle.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            onGallery(param.thisObject);
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("WCLP: hook onCreate " + className + " failed: " + t);
        }
    }

    private void onGallery(Object activityObj) {
        if (!(activityObj instanceof Activity)) return;
        Activity activity = (Activity) activityObj;

        // 源头强制：扫字段、探测或写死
        applyForce(activity, 0);

        // 可选点击兜底
        if (CLICK_FALLBACK) {
            handler.postDelayed(() -> {
                try {
                    autoCheckLive(activity);
                } catch (Throwable t) {
                    XposedBridge.log("WCLP: clickFallback error: " + t);
                }
            }, 500);
        }
    }

    /**
     * 反射扫描微信活动（及一层 com.tencent 内部对象）里的 boolean 字段：
     *  - PROBE 模式：打印 类名#字段名 = 当前值
     *  - DO_FORCE 模式：若字段名在 FORCE_FIELDS 中，强制 true 并打日志
     * 只扫 com.tencent.mm 包下的类，避开 android 框架的噪音。
     */
    private void applyForce(Object target, int depth) {
        if (target == null || depth > 2) return;
        Class<?> c = target.getClass();
        while (c != null) {
            String pn = c.getName();
            if (!pn.startsWith("com.tencent.mm")) break; // 到框架层就停
            for (Field f : c.getDeclaredFields()) {
                Class<?> t = f.getType();
                boolean isBool = (t == boolean.class || t == Boolean.class);
                f.setAccessible(true);
                try {
                    Object v = f.get(target);
                    if (PROBE) {
                        XposedBridge.log("WCLP probe " + pn + "#" + f.getName()
                                + " (" + t.getSimpleName() + ") = " + v);
                    }
                    if (DO_FORCE && isBool && FORCE_FIELDS.contains(f.getName())) {
                        f.set(target, true);
                        XposedBridge.log("WCLP FORCED " + pn + "#" + f.getName() + " = true");
                    }
                    // 递归一层：内部状态对象（如 presenter/controller）
                    if (depth < 2 && !isBool && v != null
                            && v.getClass().getName().startsWith("com.tencent.mm")) {
                        applyForce(v, depth + 1);
                    }
                } catch (Throwable ignored) {
                }
            }
            c = c.getSuperclass();
        }
    }

    // ===== 点击兜底（CLICK_FALLBACK=true 时）=====
    private void autoCheckLive(Activity activity) {
        View root = activity.getWindow().getDecorView();
        if (root == null) return;
        int vidId = activity.getResources().getIdentifier(LIVE_VID, "id", WECHAT_PKG);
        if (vidId != 0) {
            View v = root.findViewById(vidId);
            if (v != null && v.isClickable() && !v.isSelected()) {
                v.performClick();
                XposedBridge.log("WCLP: click-checked via vid=" + LIVE_VID);
                return;
            }
        }
        View tv = findTextViewByText(root, LIVE_TEXT);
        if (tv != null) {
            View parent = (View) tv.getParent();
            if (parent != null && parent.isClickable() && !parent.isSelected()) {
                parent.performClick();
                XposedBridge.log("WCLP: click-checked via text");
            }
        }
    }

    private View findTextViewByText(View root, String text) {
        if (root instanceof TextView) {
            CharSequence t = ((TextView) root).getText();
            if (text.equals(String.valueOf(t))) return root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View r = findTextViewByText(vg.getChildAt(i), text);
                if (r != null) return r;
            }
        }
        return null;
    }
}
