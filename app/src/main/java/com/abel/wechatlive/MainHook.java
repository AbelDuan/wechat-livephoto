package com.abel.wechatlive;

import android.app.Activity;
import android.app.AndroidAppHelper;
import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * WechatLive v3 - 微信发图默认「实况(LivePhoto) + 原图」
 *
 * ════════ v2「启用后没作用、logcat 也没输出」的排查结论 ════════
 * A) 【已确认的构建缺陷】AGP 在 minSdk>=21 的 debug 构建下按类分 dex，
 *    v2 的 APK 里 classes.dex 只有 R 类，MainHook 被丢进 classes2.dex。
 *    部分 LSPosed/Xposed 实现加载模块时只扫主 dex → 入口类 ClassNotFound
 *    → 模块静默加载失败，既不生效也没有任何日志。
 *    修复：gradle.properties 关闭 dexing artifact transform + multiDexEnabled false。
 * B) 【调试通道太脆弱】不少国产 ROM 默认压制第三方应用的 logcat 输出，
 *    "没有日志"无法区分"模块没跑"和"日志被吞"。
 *    修复：三重输出 —— logcat(TAG=WCLP) + XposedBridge 日志页 + 落盘文件。
 * C) 【缺少自检】没法在不接电脑的情况下判断模块到底激活没有。
 *    修复：作用域加入模块自身，hook MainActivity#isModuleActive 返回 true，
 *    打开 WechatLive 首页即可一眼看到激活状态。
 *
 * ════════ 分三阶段推进 ════════
 * 阶段 1（当前）：LOG_ACTIVITY + DUMP_VIEWS
 *   打开相册 → 拿到真实 Activity 类名 + 完整 View 树
 *   （每个 View 的 id / clickable / selected / activated / desc / text）
 *   「实况」选中态很可能就落在 selected 或 activated 上 → 得到可靠的已勾选判断依据。
 * 阶段 2：PROBE=true，扫 Activity 内部 boolean 字段，定位混淆后的实况/原图开关。
 * 阶段 3：DO_FORCE=true + FORCE_FIELDS 填字段名 → 源头默认全开，
 *   缩略图多选直接发送即为实况+原图，无需任何点击。
 */
public class MainHook implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    private static final String TAG = "WCLP";
    private static final String WECHAT_PKG = "com.tencent.mm";
    private static final String SELF_PKG = "com.abel.wechatlive";

    /** 日志文件名；写到 /sdcard 根与 Download 下，模块首页会直接读出来展示 */
    private static final String LOG_NAME = "WechatLive.log";

    // ══════════ 阶段开关 ══════════
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

    /** Activity 类名（小写）含任一关键字才视为相册相关，避免刷屏 */
    private static final String[] GALLERY_HINTS = {
            "gallery", "album", "image", "picture", "photo", "media", "preview"
    };

    // 点击兜底用
    private static final String LIVE_VID = "uzc";
    private static final String LIVE_TEXT = "实况";

    private static final SimpleDateFormat TS =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private final Handler handler = new Handler(Looper.getMainLooper());

    // ══════════════════ 日志：logcat + Xposed 日志页 + 文件，三重保险 ══════════════════
    private static void log(String msg) {
        Log.i(TAG, msg);
        try {
            XposedBridge.log(TAG + ": " + msg);
        } catch (Throwable ignored) {
        }
        writeFile(msg);
    }

    /** 依次尝试多个可写位置，任一成功即可；全失败就只剩 logcat */
    private static void writeFile(String msg) {
        String line = TS.format(new Date()) + "  " + msg + "\n";
        File[] candidates = logFiles();
        for (File f : candidates) {
            if (f == null) continue;
            FileWriter w = null;
            try {
                File dir = f.getParentFile();
                if (dir != null && !dir.exists()) dir.mkdirs();
                w = new FileWriter(f, true);
                w.write(line);
                w.flush();
                return; // 写成功一个就够
            } catch (Throwable ignored) {
            } finally {
                if (w != null) {
                    try {
                        w.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    static File[] logFiles() {
        File ext = null, dl = null, priv = null;
        try {
            ext = new File(Environment.getExternalStorageDirectory(), LOG_NAME);
        } catch (Throwable ignored) {
        }
        try {
            dl = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), LOG_NAME);
        } catch (Throwable ignored) {
        }
        try {
            Context c = AndroidAppHelper.currentApplication();
            if (c != null) priv = new File(c.getFilesDir(), LOG_NAME);
        } catch (Throwable ignored) {
        }
        return new File[]{ext, dl, priv};
    }

    // ══════════════════ Zygote 阶段：证明模块本体已被框架加载 ══════════════════
    @Override
    public void initZygote(StartupParam startupParam) {
        // 这一行只要出现，就说明模块 APK 与入口类被 LSPosed 成功加载（排除 dex/入口问题）
        log("=== WechatLive v3 loaded === modulePath=" + startupParam.modulePath
                + " startsSystemServer=" + startupParam.startsSystemServer);
    }

    // ══════════════════ 每个作用域内应用启动时 ══════════════════
    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        // ① 自身进程：让首页自检能显示"已激活"
        if (SELF_PKG.equals(lpparam.packageName)) {
            try {
                XposedHelpers.findAndHookMethod(SELF_PKG + ".MainActivity",
                        lpparam.classLoader, "isModuleActive",
                        XC_MethodReplacement.returnConstant(true));
                log("self-check hook installed");
            } catch (Throwable t) {
                log("self-check hook failed: " + t);
            }
            return;
        }

        // ② 目标：微信
        if (!WECHAT_PKG.equals(lpparam.packageName)) return;

        // ★ 看到这一行 = 已成功注入微信进程
        log("=== WechatLive injected === pkg=" + lpparam.packageName
                + " process=" + lpparam.processName);

        // hook 框架层 Activity#onResume：微信所有进程、所有 Activity 通吃，不用猜类名
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
            log("hook android.app.Activity#onResume OK  [" + lpparam.processName + "]");
        } catch (Throwable t) {
            log("hook android.app.Activity#onResume FAILED: " + t);
        }
    }

    private boolean isGalleryLike(String className) {
        String lc = className.toLowerCase(Locale.US);
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
                        dumpView(activity.getWindow().getDecorView(), 0);
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

    // ══════════════════ View 树 dump：找「实况」按钮 & 其选中状态载体 ══════════════════
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

    // ══════════════════ 阶段 2/3：反射扫描 / 强制内部 boolean 字段 ══════════════════
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

    // ══════════════════ 点按兜底 ══════════════════
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
