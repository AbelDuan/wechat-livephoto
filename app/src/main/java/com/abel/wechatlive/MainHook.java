package com.abel.wechatlive;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
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
 * WechatLive - 微信发图默认「实况(LivePhoto) + 原图」
 *
 * ════════ 为什么上一版「启用后没作用」 ════════
 * 1) XposedBridge.log 只进 LSPosed 日志页，adb logcat -s WCLP 抓不到 → 看起来毫无输出。
 *    本版所有日志同时走 android.util.Log(TAG=WCLP)，logcat 能直接抓。
 * 2) 上一版只 hook 了 4 个「猜」出来的相册 Activity 类名，微信 8.0.76 实际类名大概率不同，
 *    4 个 hook 全部抛异常 → 什么都没发生，且你看不到任何失败提示。
 *    本版改为 hook 框架层 android.app.Activity#onResume，微信进程里**任何** Activity
 *    都会被记录，真实类名一目了然。
 * 3) 没有 android:label / 没有 launcher Activity，LSPosed 列表里辨识困难。本版已补。
 *
 * ════════ 分三阶段调试 ════════
 * 阶段 1（当前默认）：LOG_ACTIVITY + DUMP_VIEWS
 *   打开相册 → logcat 抓 WCLP → 得到真实 Activity 类名 + 完整 View 树
 *   （含每个 View 的 id / clickable / selected / activated / desc / text）
 *   「实况」按钮的选中状态极可能就体现在 selected 或 activated 上——
 *   那样就能直接判断是否已勾选，比像素比对可靠得多。
 * 阶段 2：PROBE=true，扫描该 Activity 内部 boolean 字段，定位混淆后的
 *   「实况 / 原图」开关字段名。
 * 阶段 3：DO_FORCE=true + FORCE_FIELDS 填入字段名 → 源头默认全开，
 *   缩略图多选直接发送即为实况+原图，无需任何点击。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "WCLP";
    private static final String WECHAT_PKG = "com.tencent.mm";

    // ══════════ 开关 ══════════
    /** 记录微信进程内每个 Activity 的 onResume（找真实相册类名） */
    private static final boolean LOG_ACTIVITY = true;
    /** 命中相册类名时打印完整 View 树（找实况按钮及其状态属性） */
    private static final boolean DUMP_VIEWS = true;
    /** 扫描并打印 Activity 内部 boolean 字段（量大，阶段 2 再开） */
    private static final boolean PROBE = false;
    /** 把 FORCE_FIELDS 中的字段强制 true（阶段 3） */
    private static final boolean DO_FORCE = false;
    /** 点按兜底：在界面上模拟点一下「实况」开关 */
    private static final boolean CLICK_FALLBACK = false;

    /** 阶段 3 用：确认后的混淆字段名 */
    private static final Set<String> FORCE_FIELDS = new HashSet<>();

    /** Activity 类名（小写）包含任一关键字才视为相册相关，避免刷屏 */
    private static final String[] GALLERY_HINTS = {
            "gallery", "album", "image", "picture", "photo", "media", "preview"
    };

    // 点击兜底用
    private static final String LIVE_VID = "uzc";
    private static final String LIVE_TEXT = "实况";

    private final Handler handler = new Handler(Looper.getMainLooper());

    // ══════════ 日志：双写，logcat 与 LSPosed 日志页都能看到 ══════════
    private static void log(String msg) {
        Log.i(TAG, msg);
        try {
            XposedBridge.log(TAG + ": " + msg);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!WECHAT_PKG.equals(lpparam.packageName)) return;

        // ★ 只要能在 logcat 看到这一行，就说明模块已成功注入微信进程
        log("=== WechatLive injected === pkg=" + lpparam.packageName
                + " process=" + lpparam.processName);

        // hook 框架层 Activity#onResume：微信进程内所有 Activity 通吃，不用猜类名
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Activity act = (Activity) param.thisObject;
                        String cn = act.getClass().getName();
                        if (LOG_ACTIVITY) {
                            log("onResume [" + lpparam.processName + "] " + cn);
                        }
                        if (isGalleryLike(cn)) {
                            onGallery(act, cn);
                        }
                    } catch (Throwable t) {
                        log("onResume handler error: " + t);
                    }
                }
            });
            log("hook android.app.Activity#onResume OK");
        } catch (Throwable t) {
            log("hook android.app.Activity#onResume FAILED: " + t);
        }
    }

    private boolean isGalleryLike(String className) {
        String lc = className.toLowerCase();
        for (String h : GALLERY_HINTS) {
            if (lc.contains(h)) return true;
        }
        return false;
    }

    private void onGallery(final Activity activity, final String cn) {
        log(">>> GALLERY-LIKE ACTIVITY: " + cn);

        // 延迟一点，等 View 树 / 内部状态初始化完成
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (DUMP_VIEWS) {
                    try {
                        log("---- VIEW TREE of " + cn + " ----");
                        View root = activity.getWindow().getDecorView();
                        dumpView(root, 0);
                        log("---- VIEW TREE END ----");
                    } catch (Throwable t) {
                        log("dumpView error: " + t);
                    }
                }
                if (PROBE || DO_FORCE) {
                    try {
                        applyForce(activity, 0);
                    } catch (Throwable t) {
                        log("applyForce error: " + t);
                    }
                }
                if (CLICK_FALLBACK) {
                    try {
                        autoCheckLive(activity);
                    } catch (Throwable t) {
                        log("clickFallback error: " + t);
                    }
                }
            }
        }, 600);
    }

    // ══════════ View 树 dump：找「实况」按钮 & 它的选中状态载体 ══════════
    private void dumpView(View v, int depth) {
        if (v == null || depth > 14) return;

        String id = "-";
        try {
            int vid = v.getId();
            if (vid != View.NO_ID) id = v.getResources().getResourceEntryName(vid);
        } catch (Throwable ignored) {
        }

        StringBuilder pad = new StringBuilder();
        for (int i = 0; i < depth; i++) pad.append("| ");

        StringBuilder sb = new StringBuilder();
        sb.append("VIEW ").append(pad)
                .append(v.getClass().getSimpleName())
                .append(" id=").append(id)
                .append(" clk=").append(v.isClickable() ? 1 : 0)
                .append(" sel=").append(v.isSelected() ? 1 : 0)
                .append(" act=").append(v.isActivated() ? 1 : 0)
                .append(" en=").append(v.isEnabled() ? 1 : 0)
                .append(" vis=").append(v.getVisibility() == View.VISIBLE ? "V" : "X");

        CharSequence cd = v.getContentDescription();
        if (cd != null && cd.length() > 0) sb.append(" desc=\"").append(cd).append('"');
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && t.length() > 0) sb.append(" text=\"").append(t).append('"');
        }
        Object tag = null;
        try {
            tag = v.getTag();
        } catch (Throwable ignored) {
        }
        if (tag != null) sb.append(" tag=").append(tag);

        log(sb.toString());

        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                dumpView(g.getChildAt(i), depth + 1);
            }
        }
    }

    // ══════════ 阶段 2/3：反射扫描 / 强制内部 boolean 字段 ══════════
    private void applyForce(Object target, int depth) {
        if (target == null || depth > 2) return;
        Class<?> c = target.getClass();
        while (c != null) {
            String pn = c.getName();
            if (!pn.startsWith("com.tencent.mm")) break;
            for (Field f : c.getDeclaredFields()) {
                Class<?> t = f.getType();
                boolean isBool = (t == boolean.class || t == Boolean.class);
                try {
                    f.setAccessible(true);
                    Object v = f.get(target);
                    if (PROBE && isBool) {
                        log("probe " + pn + "#" + f.getName() + " = " + v);
                    }
                    if (DO_FORCE && isBool && FORCE_FIELDS.contains(f.getName())) {
                        f.set(target, true);
                        log("FORCED " + pn + "#" + f.getName() + " = true");
                    }
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

    // ══════════ 点按兜底 ══════════
    private void autoCheckLive(Activity activity) {
        View root = activity.getWindow().getDecorView();
        if (root == null) return;

        int vidId = activity.getResources().getIdentifier(LIVE_VID, "id", WECHAT_PKG);
        if (vidId != 0) {
            View v = root.findViewById(vidId);
            if (v != null && v.isClickable() && !v.isSelected()) {
                v.performClick();
                log("click-checked via vid=" + LIVE_VID);
                return;
            }
        }
        View tv = findTextViewByText(root, LIVE_TEXT);
        if (tv != null && tv.getParent() instanceof View) {
            View parent = (View) tv.getParent();
            if (parent.isClickable() && !parent.isSelected()) {
                parent.performClick();
                log("click-checked via text=" + LIVE_TEXT);
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
