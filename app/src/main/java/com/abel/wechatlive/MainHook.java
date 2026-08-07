package com.abel.wechatlive;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 运行在微信进程内的 Hook 主体。
 *
 * 这是整个工程里**唯一**允许出现 de.robv.android.xposed.* 的文件。
 * 模块 App 的 UI 代码绝不能引用本类（否则 App 进程加载不到 XposedBridge → 闪退）。
 *
 * 设计要点：
 *  1. 只 hook 框架层 android.app.Activity#onResume —— 不猜微信的混淆类名，
 *     任何 Activity 进前台都能拿到真实类名。
 *  2. 每次 onResume 通过 ContentProvider 向模块 App 回报心跳 + 日志，
 *     并同步取回用户在 App 里勾的开关（启用/暂停实时生效）。
 *  3. 「原图」有 contentDescription（含"已选中/未选中"），可确定性判断与勾选；
 *     「实况」无 text/desc，先把 isSelected/isActivated/tag 全打出来，
 *     确认哪个属性能表达勾选态，再决定强制策略。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "WCLP";
    private static final String URI_STR = Const.URI;

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final List<String> PENDING = new ArrayList<String>();
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private static String sProc = "?";
    private static long sLastReport = 0L;

    // 用户开关（默认全开，取不到配置时按默认走）
    private static volatile boolean cEnabled = true;
    private static volatile boolean cLive = true;
    private static volatile boolean cOrig = true;
    private static volatile boolean cVerbose = true;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!Const.WECHAT_PKG.equals(lp.packageName)) return;
        sProc = lp.processName;
        log("========================================");
        log("WechatLive v4 注入成功  proc=" + sProc);

        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        onResume((Activity) param.thisObject);
                    } catch (Throwable t) {
                        log("onResume handler error: " + t);
                    }
                }
            });
            log("已挂载 Activity#onResume");
        } catch (Throwable t) {
            log("挂载 Activity#onResume 失败: " + t);
        }
    }

    // ────────────────────────────── 生命周期 ──────────────────────────────

    private static void onResume(final Activity act) {
        if (act == null) return;
        final String cls = act.getClass().getName();
        log("onResume [" + sProc + "] " + cls);

        // 心跳 + 拉取开关（后台线程，避免阻塞微信主线程）
        report(act.getApplicationContext(), cls);

        if (!cEnabled) return;
        if (!looksLikeGallery(cls)) return;

        // 相册界面：延迟两次扫描，兼顾"控件还没 inflate 完"的情况
        H.postDelayed(new Runnable() {
            @Override
            public void run() {
                scan(act, cls, 1);
            }
        }, 600);
        H.postDelayed(new Runnable() {
            @Override
            public void run() {
                scan(act, cls, 2);
            }
        }, 1800);
    }

    private static boolean looksLikeGallery(String cls) {
        String s = cls.toLowerCase(Locale.US);
        return s.contains("gallery") || s.contains("album") || s.contains("preview")
                || s.contains("image") || s.contains("photo") || s.contains("media")
                || s.contains("pic") || s.contains("select");
    }

    // ────────────────────────────── 扫描 / 操作 ──────────────────────────────

    private static void scan(Activity act, String cls, int pass) {
        try {
            if (act.isFinishing()) return;
            View root = act.getWindow().getDecorView();
            if (root == null) return;

            if (cVerbose && pass == 1) {
                log("──── VIEW TREE  " + cls + " ────");
                dump(root, 0, new int[]{0});
                log("──── END TREE ────");
            }

            List<View> all = new ArrayList<View>();
            collect(root, all);

            if (cOrig) handleOriginal(all, pass);
            if (cLive) handleLive(all, pass);

            report(act.getApplicationContext(), cls);
        } catch (Throwable t) {
            log("scan error(pass" + pass + "): " + t);
        }
    }

    /**
     * 「原图」是可确定性判断的：ImageView 的 contentDescription 形如
     *   "未选中,原图,复选框" / "已选中,原图,复选框"
     * 所以只在"未选中"时点一次，天然幂等、不会来回切。
     */
    private static void handleOriginal(List<View> all, int pass) {
        for (View v : all) {
            CharSequence d = v.getContentDescription();
            if (d == null) continue;
            String s = d.toString();
            if (!s.contains("原图")) continue;
            log("[原图] " + brief(v) + " desc=" + s);
            if (s.contains("未选中")) {
                View target = clickable(v);
                if (target != null) {
                    boolean ok = target.performClick();
                    log("[原图] performClick -> " + ok + " (pass" + pass + ")");
                } else {
                    log("[原图] 找不到可点击容器");
                }
            }
            return;
        }
        if (pass == 1) log("[原图] 本页未找到（正常：仅预览页有）");
    }

    /**
     * 「实况」在无障碍树里没有 text/desc，勾选态藏在别处。
     * 这里把所有可疑属性全打出来，等日志回来确认哪一个能表达状态。
     * 在确认之前，只有当 selected/activated 都为 false 时才点一次，避免反复切换。
     */
    private static void handleLive(List<View> all, int pass) {
        View hit = null;
        String how = null;

        for (View v : all) {
            String idn = idName(v);
            if ("uzc".equals(idn)) {
                hit = v;
                how = "id=uzc";
                break;
            }
        }
        if (hit == null) {
            for (View v : all) {
                if (v instanceof TextView) {
                    CharSequence t = ((TextView) v).getText();
                    if (t != null && "实况".contentEquals(t)) {
                        hit = v;
                        how = "text=实况";
                        break;
                    }
                }
            }
        }
        if (hit == null) {
            if (pass == 1) log("[实况] 本页未找到 uzc / 文字\"实况\"");
            return;
        }

        log("[实况] 命中 " + how + " -> " + brief(hit));
        View target = clickable(hit);
        if (target == null) {
            log("[实况] 找不到可点击容器");
            return;
        }
        log("[实况] 容器 " + brief(target));

        if (target.isSelected() || target.isActivated()) {
            log("[实况] 已是 selected/activated，跳过点击");
            return;
        }
        if (pass != 1) {
            log("[实况] pass2 不重复点击，避免来回切换");
            return;
        }
        boolean ok = target.performClick();
        log("[实况] performClick -> " + ok
                + "  点击后 sel=" + target.isSelected()
                + " act=" + target.isActivated());
    }

    // ────────────────────────────── View 工具 ──────────────────────────────

    private static void collect(View v, List<View> out) {
        if (v == null || out.size() > 3000) return;
        out.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collect(g.getChildAt(i), out);
            }
        }
    }

    private static void dump(View v, int depth, int[] count) {
        if (v == null || depth > 30 || count[0] > 600) return;
        count[0]++;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(brief(v));
        log(sb.toString());
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                dump(g.getChildAt(i), depth + 1, count);
            }
        }
    }

    /** 一行摘要：类名 / id / 是否可点 / selected / activated / 可见 / desc / text / tag */
    private static String brief(View v) {
        StringBuilder sb = new StringBuilder();
        sb.append(v.getClass().getSimpleName());
        String idn = idName(v);
        if (idn != null) sb.append(" id=").append(idn);
        sb.append(" clk=").append(v.isClickable() ? 1 : 0);
        sb.append(" sel=").append(v.isSelected() ? 1 : 0);
        sb.append(" act=").append(v.isActivated() ? 1 : 0);
        sb.append(" en=").append(v.isEnabled() ? 1 : 0);
        sb.append(" vis=").append(v.getVisibility() == View.VISIBLE ? 1 : 0);
        sb.append(" sz=").append(v.getWidth()).append('x').append(v.getHeight());
        CharSequence d = v.getContentDescription();
        if (!TextUtils.isEmpty(d)) sb.append(" desc=\"").append(d).append('"');
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (!TextUtils.isEmpty(t)) {
                String s = t.toString();
                if (s.length() > 24) s = s.substring(0, 24) + "…";
                sb.append(" text=\"").append(s).append('"');
            }
        }
        Object tag = null;
        try {
            tag = v.getTag();
        } catch (Throwable ignored) {
        }
        if (tag != null) {
            String s = String.valueOf(tag);
            if (s.length() > 40) s = s.substring(0, 40) + "…";
            sb.append(" tag=").append(s);
        }
        return sb.toString();
    }

    private static String idName(View v) {
        try {
            int id = v.getId();
            if (id == View.NO_ID) return null;
            return v.getResources().getResourceEntryName(id);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 自身可点则返回自身，否则向上找最近的可点击祖先（最多 5 层） */
    private static View clickable(View v) {
        View cur = v;
        for (int i = 0; i < 6 && cur != null; i++) {
            if (cur.isClickable() && cur.isEnabled()) return cur;
            if (!(cur.getParent() instanceof View)) break;
            cur = (View) cur.getParent();
        }
        return null;
    }

    // ────────────────────────────── 日志 / 上报 ──────────────────────────────

    private static void log(String msg) {
        String line = TS.format(new Date()) + "  " + msg;
        synchronized (PENDING) {
            PENDING.add(line);
            if (PENDING.size() > 2000) PENDING.remove(0);
        }
        try {
            XposedBridge.log("[" + TAG + "] " + line);
        } catch (Throwable ignored) {
        }
        try {
            android.util.Log.i(TAG, line);
        } catch (Throwable ignored) {
        }
    }

    private static String drain() {
        synchronized (PENDING) {
            if (PENDING.isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            for (String s : PENDING) sb.append(s).append('\n');
            PENDING.clear();
            return sb.toString();
        }
    }

    /** 心跳上报 + 同步开关；放后台线程，避免在微信主线程做 Binder IPC */
    private static void report(final Context ctx, final String activity) {
        if (ctx == null) return;
        long now = System.currentTimeMillis();
        boolean hasLog;
        synchronized (PENDING) {
            hasLog = !PENDING.isEmpty();
        }
        if (!hasLog && now - sLastReport < 2000) return;
        sLastReport = now;

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Bundle in = new Bundle();
                    in.putString(Const.K_LAST_ACT, activity);
                    in.putString(Const.K_WX_VER, versionOf(ctx));
                    String logs = drain();
                    if (logs != null) in.putString(Const.KEY_LOG, logs);

                    Bundle out = ctx.getContentResolver()
                            .call(Uri.parse(URI_STR), Const.METHOD_REPORT, null, in);
                    if (out != null) {
                        cEnabled = out.getBoolean(Const.K_ENABLED, true);
                        cLive = out.getBoolean(Const.K_LIVE, true);
                        cOrig = out.getBoolean(Const.K_ORIG, true);
                        cVerbose = out.getBoolean(Const.K_VERBOSE, true);
                    } else {
                        XposedBridge.log("[" + TAG + "] provider 返回 null，"
                                + "模块 App 可能被冻结/卸载，按默认开关运行");
                    }
                } catch (Throwable t) {
                    try {
                        XposedBridge.log("[" + TAG + "] report 失败: " + t);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "wclp-report").start();
    }

    private static String versionOf(Context ctx) {
        try {
            return ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }
}
